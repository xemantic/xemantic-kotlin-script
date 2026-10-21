/*
 * xemantic-kotlin-script - Kotlin script execution with LLM-friendly diagnostics
 * Copyright (C) 2025-2026  Kazimierz Pogoda / Xemantic
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.xemantic.kotlin.script

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.config.JvmTarget
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass
import java.lang.reflect.Modifier
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.typeOf
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.loadDependencies
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.time.measureTimedValue

/**
 * Provided to every script as `_script`, turning the script body into a block,
 * which [ScriptExecutor.execute] runs in the coroutine of its caller.
 */
public object ScriptBlock {
    public operator fun <S> invoke(
        receiver: S,
        block: S.() -> (suspend CoroutineScope.() -> Any?)
    ): ScriptBody = ScriptBody(receiver.block())
}

/**
 * The script body, with the outer receiver giving access to the shadowed dependencies,
 * see [ScriptSource], run with the coroutine scope of the caller as the inner one.
 */
public class ScriptBody internal constructor(
    internal val block: suspend CoroutineScope.() -> Any?
)

/** The default of the `maxConcurrentCompilations` of the `ScriptExecutor(...)` factory function. */
internal fun defaultMaxConcurrentCompilations(): Int = Runtime.getRuntime().availableProcessors()

/**
 * The default [ScriptExecutor], see the `ScriptExecutor(...)` factory function for the parameters.
 */
internal class DefaultScriptExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    compilationCacheSize: Int = 0,
    maxConcurrentCompilations: Int = defaultMaxConcurrentCompilations(),
    /** Replaced only by the tests of the internal failures of the compiler. */
    compiler: JvmScriptCompiler = JvmScriptCompiler()
) : ScriptExecutor {

    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalAtomicApi::class)
    private val scriptIdSeq = AtomicInt(1)

    @OptIn(ExperimentalAtomicApi::class)
    private val compilations = AtomicInt(0)

    /**
     * The number of the invocations of the compiler, the ones bounded by the `maxConcurrentCompilations`,
     * so not counting the compilations served by the cache,
     * while counting each confirming one, see [ScriptSource.confirming],
     * even when the compilation of the script failed.
     */
    @OptIn(ExperimentalAtomicApi::class)
    internal val compilationCount: Int get() = compilations.load()

    init {
        require(compilationCacheSize >= 0) {
            "compilationCacheSize must not be negative, but was: $compilationCacheSize"
        }
        require(maxConcurrentCompilations > 0) {
            "maxConcurrentCompilations must be positive, but was: $maxConcurrentCompilations"
        }
    }

    private val compilationCache = if (compilationCacheSize > 0) {
        ScriptCompilationCache<Compilation>(compilationCacheSize) {
            it.result is ResultWithDiagnostics.Success
        }
    } else {
        null
    }

    /**
     * Bounds the invocations of the compiler, each loading the whole classpath,
     * acquired only around them, so not by the callers waiting for the cached compilation.
     */
    private val compilationPermits = Semaphore(maxConcurrentCompilations)

    private val scriptingHost = BasicJvmScriptingHost(compiler = compiler)

    /**
     * The class loader the script is compiled against, also evaluating it,
     * instead of the context class loader of the dispatcher thread, which might differ.
     */
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader
        ?: DefaultScriptExecutor::class.java.classLoader

    /**
     * Built on the first compilation, on the [dispatcher], as resolving the whole classpath
     * would otherwise block the caller creating the executor, even if it never executes a script.
     * The concurrent first compilations wait for it, holding their permits,
     * while its failure, reported as the internal failure of the compiler, is not kept,
     * so the next compilation resolves the classpath again, as the failure might be transient.
     */
    private val compilationConfigurationTemplate by lazy {
        ScriptCompilationConfiguration {
            jvm {
                dependenciesFromClassloader(
                    classLoader = classLoader,
                    wholeClasspath = true
                )
                // the default 1.8 target cannot inline functions of the classpath, e.g. `dependency(...)`
                jvmTarget(scriptJvmTarget(System.getProperty("java.specification.version")))
            }
            // the script body has a CoroutineScope receiver, so it can launch coroutines without imports
            defaultImports(
                "kotlinx.coroutines.*"
            )
        }
    }

    override suspend fun execute(
        script: String,
        dependencies: List<ScriptExecutor.Dependency<*>>,
    ): ScriptResult {

        dependencies.groupBy { it.name }.forEach { (name, sameName) ->
            require(sameName.size == 1) { "Duplicate dependency name: $name" }
            require(name != SCRIPT_BLOCK_NAME && name != DEPENDENCIES_CLASS_NAME && !name.startsWith(HIDDEN_DEPENDENCY_PREFIX)) {
                "Reserved dependency name: $name"
            }
            require(name !in coroutineScopeProperties && (name !in coroutineScopeFunctions || !sameName.single().isCallable)) {
                "Reserved dependency name: $name - it would be resolved to the CoroutineScope of the script body"
            }
        }

        @OptIn(ExperimentalAtomicApi::class)
        val scriptId = scriptIdSeq.fetchAndIncrement()
        logger.debug { "Script[$scriptId]: Executing" }

        val (result, duration) = measureTimedValue {
            withContext(dispatcher) {
                ScriptHandler(
                    scriptId,
                    dependencies,
                    script
                ).handle()
            }
        }

        logger.debug { "Script[$scriptId]: Execution took $duration in total" }

        return result
    }

    /**
     * The [source] of the script, together with the [result] of its compilation,
     * or `null`, if the script was not compiled because of the failure recognized in its source.
     */
    private class Compilation(
        val source: ScriptSource,
        val result: ResultWithDiagnostics<CompiledScript>?,
        /** The failure messages of the [ScriptSource.candidates] confirmed by the confirming compilations, one for each kind of them. */
        val confirmedFailures: List<String> = emptyList(),
        /** The positions of the errors of the compilation explained by the [confirmedFailures], which are omitted. */
        val explainedErrors: Set<ScriptPosition> = emptySet()
    )

    private inner class ScriptHandler(
        private val scriptId: Int,
        dependencies: List<ScriptExecutor.Dependency<*>>,
        private val script: String
    ) {

        // in the order independent of the caller, as the source and the cache key are built from it
        private val allDependencies = (dependencies + ScriptExecutor.Dependency(
            name = SCRIPT_BLOCK_NAME,
            type = typeOf<ScriptBlock>(),
            value = ScriptBlock
        )).sortedBy { it.name }

        /** Parsed only when not compiled already, as the cached compilation keeps it. */
        private lateinit var source: ScriptSource

        /** Reporting the failures of the [source], once it is known. */
        private val reporter by lazy { ScriptErrorReporter(source) }

        suspend fun handle(): ScriptResult {
            val compilation = try {
                compile()
            } catch (_: StackOverflowError) {
                // thrown by parsing the script, while the compiler reports it as a diagnostic
                return ScriptResult.Error(
                    "error: The script is nested too deeply to be parsed.",
                    ExecutionPhase.COMPILATION
                )
            } catch (e: Throwable) {
                e.rethrowIfCancelledOrFatal()
                // the internal failure of the parser, or of the compiler, shared by the callers waiting in the cache
                logger.warn(e) { "Script[$scriptId]: Compilation (1/2) failed internally" }
                return ScriptResult.Error(
                    "error: The script could not be compiled because of an internal failure of the compiler: "
                            // the callers waiting in the cache receive the copy recovered by kotlinx.coroutines
                            + e.unwrapRecoveredCopy().let { listOfNotNull(it, it.cause) }.joinToString(", caused by: "),
                    ExecutionPhase.COMPILATION
                )
            }
            source = compilation.source
            val compilationResult = compilation.result ?: return compilationFailure(
                reporter.toSourceFailureMessage()
            )
            if (compilation.confirmedFailures.isNotEmpty()) {
                val failureMessage = (compilation.confirmedFailures + listOfNotNull(
                    // without the errors explained by the confirmed references
                    (compilationResult as? Failure)?.reports?.filterNonIgnorable()?.filterNot { diagnostic ->
                        diagnostic.severity == ScriptDiagnostic.Severity.ERROR && diagnostic.location?.start?.let { start ->
                            source.toScriptPosition(start.line, start.col) in compilation.explainedErrors
                        } == true
                    }?.takeIf { reports -> reports.any { it.severity == ScriptDiagnostic.Severity.ERROR } }?.let {
                        reporter.toFailureMessage(it)
                    }
                )).joinToString(separator = "\n\n")
                return compilationFailure(failureMessage)
            }
            val result = when (compilationResult) {
                is Success -> {
                    evaluate(
                        compiledScript = compilationResult.value,
                        warnings = compilationResult.reports.filter {
                            it.severity == ScriptDiagnostic.Severity.WARNING
                        }
                    )
                }
                is Failure -> compilationFailure(
                    reporter.toFailureMessage(compilationResult.reports.filterNonIgnorable())
                )
            }

            return result
        }

        private fun compilationFailure(
            failureMessage: String
        ): ScriptResult.Error {
            logger.debug {
                "Script[$scriptId]: Compilation (1/2) failure message: $failureMessage"
            }
            return ScriptResult.Error(failureMessage, ExecutionPhase.COMPILATION)
        }

        private suspend fun compile(): Compilation {

            logger.debug { "Script[$scriptId]: Compiling (1/2)" }

            val (compilation, duration) = measureTimedValue {
                compilationCache?.getOrCompile(
                    // the source is built from the script and the accessors of the dependencies of these types
                    ScriptCompilationCache.Key(
                        script = script,
                        dependencies = allDependencies.map { it.name to it.type }
                    ),
                    ::doCompile
                ) ?: doCompile()
            }

            when (compilation.result) {
                is Success -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) succeeded after $duration"
                }
                else -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) failed after $duration"
                }
            }

            return compilation
        }

        private suspend fun doCompile(): Compilation {
            val source = source()
            if (source.unmatchedClosingBrace != null || source.conflictingDependencyDeclarations.isNotEmpty()) {
                return Compilation(source, result = null)
            }
            val result = compile(source)
            // unless already reported as unresolved
            val errors = result.reports.filter { it.severity == ScriptDiagnostic.Severity.ERROR }.mapNotNull { diagnostic ->
                diagnostic.location?.start?.let { source.toScriptPosition(it.line, it.col) }
            }
            val candidates = source.candidates
            if (candidates.isEmpty()) return Compilation(source, result)
            // the types of the local properties, which are known only to the compiler, see ScriptSource.untypedPropertyCalls
            val propertyTypes = if (source.untypedProperties.isEmpty()) emptyMap() else {
                val typeProbes = source(propertyTypeProbes = true)
                val unexpected = mutableListOf<String>()
                val types = typeProbes.propertyTypes(compile(typeProbes).reports) { unexpected += it.message }
                val (mismatches, others) = unexpected.partition { it.startsWith("Initializer type mismatch") }
                // like after the upgrade of the compiler, changing the message naming the type
                if (mismatches.isNotEmpty()) logger.warn { "Script[$scriptId]: Unexpected messages of the type probes: ${mismatches.joinToString(separator = "; ")}" }
                // like `Variable must be initialized` of a property assigned later, or the errors of the script
                if (others.isNotEmpty()) logger.debug { "Script[$scriptId]: Other errors of the type probes: ${others.joinToString(separator = "; ")}" }
                types
            }
            // recognizing the candidates referring to the wrong declarations indeed
            val probe = source(confirming = true, propertyTypes = propertyTypes)
            // only the calls of the properties of unknown types, which cannot be confirmed without their copies
            if (probe.copiedProperties.isEmpty() && candidates.all { it in source.untypedPropertyCalls }) {
                return Compilation(source, result)
            }
            /**
             * The positions, from the start to the end, if known, of the warnings of the deprecated,
             * or opted in, declarations of this [message] in the [source], with their messages, see [ScriptSource.confirming].
             */
            fun List<ScriptDiagnostic>.markedRanges(
                source: ScriptSource,
                message: String
            ) = filter {
                it.severity == ScriptDiagnostic.Severity.WARNING && message in it.message
            }.mapNotNull { diagnostic ->
                diagnostic.location?.let { location ->
                    Triple(
                        source.toScriptPosition(location.start.line, location.start.col),
                        location.end?.let { source.toScriptPosition(it.line, it.col) },
                        diagnostic.message
                    )
                }
            }
            fun List<ScriptDiagnostic>.marked(
                source: ScriptSource,
                message: String
            ) = markedRanges(source, message).map { it.first }
            val reports = compile(probe).reports
            // unlike the other ones, the calls of which cannot be confirmed
            val confirmedTypes = probe.confirmedPropertyTypes(reports)
            fun deprecated(
                message: String
            ) = reports.marked(probe, message)
            val dependencies = deprecated(LATER_DECLARED_DEPENDENCY)
            // only the ones, which the local function applies to, unlike the dependency called anyway
            val functions = deprecated(LATER_DECLARED_FUNCTION)
            // each assigned to the candidate of its name containing it, the one of the same end, if nested, like `a[0][1]`
            val hiddenWarnings = reports.markedRanges(probe, HIDDEN_LOCAL_FUNCTION)
            val hiddenCandidates = source.hiddenLocalFunctionCandidates
            val hidden = hiddenWarnings.mapNotNullTo(HashSet()) { (start, end, message) ->
                hiddenCandidates.filter { candidate ->
                    start in candidate.reference && hiddenLocalFunctionMessage(candidate.reference.name) in message
                            // like a delegate convention, not of a call of the same name within it
                            && candidate.inner.none { start in it }
                }.let { matching ->
                    // the innermost one, nested ones start later, or at the same position, ending earlier
                    matching.firstOrNull { it.reference.end == end } ?: matching.maxWithOrNull(
                        compareBy<HiddenLocalFunctionReference> { it.reference.start }.thenByDescending { it.reference.end }
                    )
                }?.takeIf { candidate ->
                    // chosen first, so that the warning of a plain call, like `get(1)` in `m[get(1)]`, is not assigned to the enclosing operator,
                    // the compiler reports the operators as unresolved, misleading, so they are hinted at as well,
                    // like the calls failing as resolved to the dependency of the same name, see ScriptSource.calledInsteadDependencies
                    candidate.isOperator || start !in errors || candidate.reference.name in source.calledInsteadDependencies
                }
            }
            // the references not resolved to the moved declarations, unless already reported as errors
            val toDeclarations = deprecated(COROUTINE_SCOPE_ORIGINAL)
            val coroutineScopeReferences = source.coroutineScopeCandidates.filter { candidate ->
                toDeclarations.none { it in candidate.reference } && errors.none { it in candidate.reference }
            }
            // the calls not referring to the moved functions already, which would refer to them in the script, if they apply
            val toFunctions = deprecated(SHADOWED_ORIGINAL)
            val toLocals = source.shadowedFunctionCandidates.filter { (reference) -> toFunctions.none { it in reference } }
            // the remaining calls of the functions, which would refer to them in the script, if they apply, like the properties,
            // and the references within the lambdas, unless resolved to their receivers, also in the script, see ScriptSource.coroutineScopeCopies
            fun CoroutineScopeReference.isConfirmedByCopies() = isFunction || isInLambda
            val confirmedCandidates = coroutineScopeReferences.filter { it.isConfirmedByCopies() }
            // independent of each other
            val (shadowed, scopeCopies) = coroutineScope {
                // the calls within the declarations of inferred types are resolved differently, see ScriptSource.overloadingFunctionCopies
                val (inInferredTypes, inDeclaredTypes) = toLocals.partition { it.isInInferredType }
                fun copies(
                    candidates: List<ShadowedFunctionReference>,
                    copies: () -> ScriptSource
                ) = async {
                    if (candidates.isEmpty()) emptyList() else {
                        val source = copies()
                        compile(source).reports.markedRanges(source, SHADOWED_FUNCTION).mapNotNull { (start, _, message) ->
                            candidates.find { start in it.reference }?.let { candidate ->
                                // naming the overload called in the script
                                candidate.copy(functionLine = source.shadowedFunctionLine(message) ?: candidate.functionLine)
                            }
                        }
                    }
                }
                val inferred = copies(inInferredTypes) { source(confirming = true, overloadingFunctionCopies = true) }
                val declared = copies(inDeclaredTypes) { source(confirming = true, shadowedFunctionCopies = true) }
                val shadowed = async { (inferred.await() + declared.await()).distinctBy { it.reference } }
                // also declaring the copies of the constructors of the local classes, see ScriptSource.laterDeclaredClassCandidates
                val scopeCopies = async {
                    if (confirmedCandidates.isEmpty() && source.laterDeclaredClassCandidates.isEmpty()) null else {
                        val copies = source(confirming = true, coroutineScopeCopies = true)
                        copies to compile(copies).reports
                    }
                }
                shadowed.await() to scopeCopies.await()
            }
            val toCoroutineScope = scopeCopies?.let { (copies, reports) -> reports.marked(copies, COROUTINE_SCOPE_FUNCTION) }.orEmpty()
            // only the ones, which the constructors apply to, unlike the dependency called anyway
            val constructors = scopeCopies?.let { (copies, reports) -> reports.marked(copies, LATER_DECLARED_CLASS) }.orEmpty()
            // the calls of the scope functions of the coroutine scope of the script body, the script instance in the script
            val toScriptReceiver = scopeCopies?.let { (copies, reports) -> reports.marked(copies, SCRIPT_RECEIVER) }.orEmpty()
            val laterDeclaredDependencyReferences = (source.laterDeclaredDependencyCandidates.filter { candidate ->
                (functions.takeIf { candidate.isFunction }.orEmpty()
                        // like `::x`, resolved to the property of the dependency instead of the local function
                        + dependencies.takeIf { !candidate.isFunction || candidate.isCallableReference }.orEmpty())
                    .any { it in candidate.reference }
            } + source.laterDeclaredClassCandidates.filter { candidate ->
                // calling the dependency without the copies, rather than e.g. the member of a receiver, which takes precedence in the script,
                // like the script instance, the receiver of the lambdas of its scope functions, like `run { }`, declaring the dependency
                constructors.any { it in candidate.reference } && dependencies.any { it in candidate.reference }
                        && candidate.scopeCalls.none { call -> toScriptReceiver.any { it in call } }
            }).sortedBy { it.reference.start }
            val hiddenLocalFunctionReferences = hiddenCandidates.filter {
                it in hidden && (it !in source.untypedPropertyCalls || it.reference.name in confirmedTypes)
            }
            val reporter = ScriptErrorReporter(source)
            /** The failure message of these confirmed [references] of one kind, unless there are none. */
            fun <T : CandidateReference> message(
                references: List<T>,
                toMessage: ScriptErrorReporter.(List<T>) -> String
            ) = references.takeIf { it.isNotEmpty() }?.let { reporter.toMessage(it) }
            return Compilation(
                source,
                result,
                confirmedFailures = listOfNotNull(
                    // in the order of the script
                    message(
                        coroutineScopeReferences.filter { candidate ->
                            !candidate.isConfirmedByCopies() || toCoroutineScope.any { it in candidate.reference }
                        },
                        ScriptErrorReporter::toCoroutineScopeMessage
                    ),
                    // in the order of the script
                    message(
                        toLocals.mapNotNull { candidate -> shadowed.find { it.reference === candidate.reference } },
                        ScriptErrorReporter::toShadowedFunctionMessage
                    ),
                    message(hiddenLocalFunctionReferences, ScriptErrorReporter::toHiddenLocalFunctionMessage),
                    message(laterDeclaredDependencyReferences, ScriptErrorReporter::toLaterDeclaredDependencyMessage)
                ),
                // the errors of the calls resolved to the dependency instead, like `No value passed for parameter 'p1'` of `log()`,
                // see ScriptSource.calledInsteadDependencies
                explainedErrors = hiddenLocalFunctionReferences.filter {
                    it.reference.name in source.calledInsteadDependencies
                }.mapTo(HashSet()) { it.reference.start }
            )
        }

        /** Inspected once, as recognizing a callable dependency might inspect its members by reflection. */
        private val preambleDependencies by lazy {
            allDependencies.filter { it.isHidden }.map { dependency ->
                PreambleDependency(dependency.name, dependency.accessor, dependency.isCallable)
            }
        }

        private fun source(
            confirming: Boolean = false,
            shadowedFunctionCopies: Boolean = false,
            overloadingFunctionCopies: Boolean = false,
            coroutineScopeCopies: Boolean = false,
            propertyTypeProbes: Boolean = false,
            propertyTypes: Map<String, String> = emptyMap()
        ) = ScriptSource(
            script = script,
            dependencies = preambleDependencies,
            confirming = confirming,
            shadowedFunctionCopies = shadowedFunctionCopies,
            overloadingFunctionCopies = overloadingFunctionCopies,
            coroutineScopeCopies = coroutineScopeCopies,
            propertyTypeProbes = propertyTypeProbes,
            propertyTypes = propertyTypes
        )

        private suspend fun compile(
            source: ScriptSource
        ): ResultWithDiagnostics<CompiledScript> {
            val result = try {
                compilationPermits.withPermit {
                    // resolving the classpath on the first compilation, which is not counted if it fails
                    val configuration = compilationConfig()
                    // not counting the callers cancelled while waiting for the permit
                    @OptIn(ExperimentalAtomicApi::class)
                    compilations.incrementAndFetch()
                    scriptingHost.compiler(
                        script = source,
                        scriptCompilationConfiguration = configuration
                    )
                }
            } catch (e: CancellationException) {
                // the cancellation of the caller is not a failure of the compilation,
                // otherwise it would silently cancel the active caller, and the ones waiting in the cache, instead of failing them
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("The compilation of the script was cancelled", e)
            }
            // the compiler reports what it throws as a diagnostic, like `error: Java heap space`, not an error of the script
            result.reports.firstNotNullOfOrNull { it.exception?.fatalCause() }?.let { throw it }
            return result
        }


        private suspend fun evaluate(
            compiledScript: CompiledScript,
            warnings: List<ScriptDiagnostic>
        ): ScriptResult {

            logger.debug { "Script[$scriptId]: Evaluating (2/2)" }

            val (evaluationResult, duration) = measureTimedValue {
                scriptingHost.evaluator(
                    compiledScript = compiledScript,
                    scriptEvaluationConfiguration = evaluationConfig()
                )
            }

            val result = when (evaluationResult) {
                is Success -> {
                    evaluationResult.value.returnValue.toScriptResult(warnings)
                }
                is Failure -> {
                    // in practice, this never happens
                    val failureMessage = reporter.toFailureMessage(
                        evaluationResult.reports.filterNonIgnorable()
                    )
                    ScriptResult.Error(failureMessage, ExecutionPhase.EVALUATION)
                }
            }

            when (result) {
                is ScriptResult.Value -> logger.debug {
                    "Script[$scriptId]: Evaluation (2/2) succeeded after $duration"
                }
                is ScriptResult.Error -> logger.debug {
                    "Script[$scriptId]: Evaluation (2/2) failed after $duration, failure message: ${result.message}"
                }
            }

            return result
        }

        private suspend fun ResultValue.toScriptResult(
            warnings: List<ScriptDiagnostic>
        ): ScriptResult = when (this) {
            is ResultValue.Value -> {
                // the script block closed early, unnoticed by the check of the unmatched closing brace
                val block = (value as? ScriptBody ?: return earlyClosedBlockError()).block
                // only what the block throws is the failure of the script, not the reporting of its warnings
                val returned = try {
                    // the scripting host sets it only while constructing the script, which just returns the block
                    Result.success(withContext(ContextClassLoader(block::class.java.classLoader)) { block() })
                } catch (e: Throwable) {
                    e.rethrowIfCancelledOrFatal()
                    Result.failure(e.unwrapRecoveredCopy())
                }
                returned.fold(
                    onSuccess = { ScriptResult.Value(if (source.returnsUnit) Unit else it, warningsMessage(warnings)) },
                    // the exception of the script is not passed to the logger,
                    // which might read its properties, overridden by the script to throw
                    onFailure = { failure(it, warnings, block::class.java.name) }
                )
            }
            // the script block closed early as well, followed by a statement of Unit type, or a declaration
            is ResultValue.Unit -> earlyClosedBlockError()
            is ResultValue.Error -> failure(error, warnings, blockClassName = null)
            is ResultValue.NotEvaluated -> throw IllegalStateException(
                "Should never happen"
            )
        }

        private fun earlyClosedBlockError() = ScriptResult.Error(
            "error: The script could not be run, check if its braces are balanced.",
            ExecutionPhase.COMPILATION
        )

        private fun warningsMessage(
            warnings: List<ScriptDiagnostic>
        ): String? = if (warnings.isEmpty()) null else {
            reporter.toFailureMessage(warnings)
        }

        private fun ResultValue.failure(
            throwable: Throwable,
            warnings: List<ScriptDiagnostic>,
            blockClassName: String?
        ) = ScriptResult.Error(
            ScriptErrorReporter(
                source = source,
                scriptClassName = scriptClass?.java?.name,
                blockClassName = blockClassName
            ).toFailureMessage(throwable, warnings),
            ExecutionPhase.EVALUATION
        )

        private fun evaluationConfig() = ScriptEvaluationConfiguration {
            jvm {
                baseClassLoader(classLoader)
                // the classpath of the compilation is taken from the base class loader already
                loadDependencies(false)
            }
            providedProperties(
                *(allDependencies.map { it.providedName to it.value }.toTypedArray())
            )
        }

        private fun compilationConfig() = ScriptCompilationConfiguration(compilationConfigurationTemplate) {
            providedProperties(
                *(allDependencies.map { it.providedName to it.providedType }.toTypedArray())
            )
        }

    }

}

/**
 * Sets the context class loader of every thread the coroutine resumes on.
 */
private class ContextClassLoader(
    private val classLoader: ClassLoader
) : ThreadContextElement<ClassLoader?> {

    companion object Key : CoroutineContext.Key<ContextClassLoader>

    override val key: CoroutineContext.Key<ContextClassLoader> get() = Key

    override fun updateThreadContext(
        context: CoroutineContext
    ): ClassLoader? {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = classLoader
        return previous
    }

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: ClassLoader?
    ) {
        Thread.currentThread().contextClassLoader = oldState
    }

}

/**
 * Rethrows this exception, unless it is a failure of the script, or of its compilation:
 * the cancellation of the caller, or a [VirtualMachineError], after which, unlike after the [StackOverflowError],
 * the JVM might not be able to continue.
 */
private suspend fun Throwable.rethrowIfCancelledOrFatal() {
    if (this is CancellationException) currentCoroutineContext().ensureActive()
    if (this is VirtualMachineError && this !is StackOverflowError) throw this
}

/**
 * This exception, or its first cause, which is a [VirtualMachineError] other than [StackOverflowError],
 * see [rethrowIfCancelledOrFatal], if any.
 */
private fun Throwable.fatalCause(): Throwable? = generateSequence(this) { exception ->
    exception.read(null) { cause }?.takeUnless { it === exception }
}.take(MAX_FATAL_CAUSE_DEPTH).firstOrNull {
    it is VirtualMachineError && it !is StackOverflowError
}

/** Bounds the chain of causes, which might be cyclic. */
private const val MAX_FATAL_CAUSE_DEPTH = 100

/**
 * The original exception, if this one is its copy made by the coroutine stack trace recovery,
 * recognized like kotlinx.coroutines does, by the artificial frame of the coroutine boundary.
 */
private fun Throwable.unwrapRecoveredCopy(): Throwable {
    val cause = read(null) { cause }
    return if (
        cause != null
        && cause.javaClass == javaClass
        && read(emptyList()) { stackTrace.filterNotNull() }.any { it.className.startsWith("_COROUTINE.") }
    ) {
        cause
    } else {
        this
    }
}

/**
 * The JVM target of the running Java version, or the highest one supported by the compiler,
 * if the Java version is newer than the compiler.
 */
internal fun scriptJvmTarget(
    javaSpecificationVersion: String
): String = (JvmTarget.fromString(javaSpecificationVersion) ?: JvmTarget.supportedValues().last()).description

private const val SCRIPT_BLOCK_NAME = "_script"

private const val HIDDEN_DEPENDENCY_PREFIX = "_dependency_"

/*
 * The scripting host erases the type arguments of a provided property,
 * fails to compile the erased type of a nested class,
 * cannot construct the script class taking a property of value class type, which is not boxed,
 * and resolves a provided property before the members of the implicit receivers, like `size` in `list.run { size }`,
 * so a dependency is provided under a hidden name as `Any?`,
 * and cast back to its type by the accessors declared in the preamble of the script, see [ScriptSource].
 */

private val ScriptExecutor.Dependency<*>.isHidden: Boolean
    get() = name != SCRIPT_BLOCK_NAME

private val ScriptExecutor.Dependency<*>.providedName: String
    get() = if (isHidden) "$HIDDEN_DEPENDENCY_PREFIX$name" else name

private val ScriptExecutor.Dependency<*>.providedType: KotlinType
    get() = if (isHidden) KotlinType(typeOf<Any?>()) else KotlinType(type)

// with an initializer, unlike a getter, the dependency can be smart cast
private val ScriptExecutor.Dependency<*>.accessor: String
    get() = "@kotlin.Suppress(\"UNCHECKED_CAST\") val `$name`: $type = `$providedName` as $type"

/** Whether it is of a function type, or of a type with the public `invoke` operator, so the script can call it. */
private val ScriptExecutor.Dependency<*>.isCallable: Boolean
    get() = !type.isMarkedNullable && (type.classifier as? KClass<*>)?.let { type ->
        Function::class.java.isAssignableFrom(type.java) || try {
            if (type.java.isAnnotationPresent(Metadata::class.java)) {
                // named like `invoke-LRDsOJo` in the bytecode, if it takes a value class
                type.members.any {
                    it is KFunction<*> && it.name == "invoke" && it.isOperator
                            && it.visibility == KVisibility.PUBLIC && it.extensionReceiverParameter == null
                }
            } else {
                // every Java method named `invoke` is an operator
                type.java.methods.any { it.name == "invoke" && !Modifier.isStatic(it.modifiers) }
            }
        } catch (e: Throwable) {
            if (e is VirtualMachineError) throw e
            // e.g. a member referring to an optional class, which is missing, so it cannot be inspected
            false
        }
    } == true
