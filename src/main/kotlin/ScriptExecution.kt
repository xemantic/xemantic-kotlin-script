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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.config.JvmTarget
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.typeOf
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.compilerOptions
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.loadDependencies
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost
import kotlin.time.measureTimedValue

/**
 * Provided to every script as `_script`, turning the script body into a block,
 * which [ScriptExecutor.execute] runs in the coroutine of its caller.
 */
public object ScriptBlock {
    public operator fun invoke(
        block: suspend CoroutineScope.() -> Any?
    ): suspend CoroutineScope.() -> Any? = block
}

/**
 * The default [ScriptExecutor], see the `ScriptExecutor(...)` factory function for the parameters.
 */
internal class DefaultScriptExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    compilationCacheSize: Int = 0
) : ScriptExecutor {

    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalAtomicApi::class)
    private val scriptIdSeq = AtomicInt(1)

    @OptIn(ExperimentalAtomicApi::class)
    private val compilations = AtomicInt(0)

    /**
     * The number of the actual compilations, not served by the cache.
     */
    @OptIn(ExperimentalAtomicApi::class)
    internal val compilationCount: Int get() = compilations.load()

    init {
        require(compilationCacheSize >= 0) {
            "compilationCacheSize must not be negative, but was: $compilationCacheSize"
        }
    }

    private val compilationCache = if (compilationCacheSize > 0) {
        ScriptCompilationCache<Compilation>(compilationCacheSize) {
            it.result is ResultWithDiagnostics.Success
        }
    } else {
        null
    }

    private val scriptingHost = BasicJvmScriptingHost()

    /**
     * The class loader the script is compiled against, also evaluating it,
     * instead of the context class loader of the dispatcher thread, which might differ.
     */
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader
        ?: DefaultScriptExecutor::class.java.classLoader


    private val compilationConfigurationTemplate = ScriptCompilationConfiguration {
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
        // TODO can be removed once the time API is stable?
        compilerOptions.append(
            "-opt-in=kotlin.time.ExperimentalTime",
            "-Xskip-prerelease-check",
        )
    }

    override suspend fun execute(
        script: String,
        dependencies: List<ScriptExecutor.Dependency<*>>,
    ): ScriptResult {

        dependencies.groupBy { it.name }.forEach { (name, sameName) ->
            require(sameName.size == 1) { "Duplicate dependency name: $name" }
            require(name != SCRIPT_BLOCK_NAME && !name.startsWith(HIDDEN_DEPENDENCY_PREFIX)) {
                "Reserved dependency name: $name"
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
        val result: ResultWithDiagnostics<CompiledScript>?
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

        suspend fun handle(): ScriptResult {
            val compilation = try {
                compile()
            } catch (_: StackOverflowError) {
                // thrown by parsing the script, while the compiler reports it as a diagnostic
                return ScriptResult.Error(
                    "error: The script is nested too deeply to be parsed.",
                    ExecutionPhase.COMPILATION
                )
            }
            source = compilation.source
            val compilationResult = compilation.result ?: return ScriptResult.Error(
                ScriptErrorReporter(source).toSourceFailureMessage(),
                ExecutionPhase.COMPILATION
            )
            val result = when (compilationResult) {
                is Success -> {
                    evaluate(
                        compiledScript = compilationResult.value,
                        warnings = compilationResult.reports.filter {
                            it.severity == ScriptDiagnostic.Severity.WARNING
                        }
                    )
                }
                is Failure -> {
                    val failureMessage = ScriptErrorReporter(source).toFailureMessage(
                        compilationResult.reports.filterNonIgnorable()
                    )
                    logger.debug {
                        "Script[$scriptId]: Compilation (1/2) failure message: $failureMessage"
                    }
                    ScriptResult.Error(failureMessage, ExecutionPhase.COMPILATION)
                }
            }

            return result
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
            val source = ScriptSource(
                script,
                preamble = allDependencies.mapNotNull { dependency ->
                    dependency.accessor?.let { dependency.name to it }
                }.toMap(),
                dependencyNames = allDependencies.mapTo(HashSet()) { it.name }
            )
            if (source.unmatchedClosingBrace != null || source.shadowedDependencyReferences.isNotEmpty()) {
                return Compilation(source, result = null)
            }
            @OptIn(ExperimentalAtomicApi::class)
            compilations.incrementAndFetch()
            val result = try {
                scriptingHost.compiler(
                    script = source,
                    scriptCompilationConfiguration = compilationConfig()
                )
            } catch (e: CancellationException) {
                // the cancellation of the caller is not a failure of the compilation,
                // otherwise it would silently cancel the active caller, and the ones waiting in the cache, instead of failing them
                currentCoroutineContext().ensureActive()
                throw IllegalStateException("The compilation of the script was cancelled", e)
            }
            return Compilation(source, result)
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
                    val failureMessage = ScriptErrorReporter(source).toFailureMessage(
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
                if (value !is Function2<*, *, *>) return earlyClosedBlockError()
                @Suppress("UNCHECKED_CAST")
                val block = value as suspend CoroutineScope.() -> Any?
                // only what the block throws is the failure of the script, not the reporting of its warnings
                val returned = try {
                    // the scripting host sets it only while constructing the script, which just returns the block
                    Result.success(withContext(ContextClassLoader(block::class.java.classLoader)) { block() })
                } catch (e: Throwable) {
                    // the cancellation of the caller is not a failure of the script
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    // unlike after the StackOverflowError, the JVM might not be able to continue
                    if (e is VirtualMachineError && e !is StackOverflowError) throw e
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
            ScriptErrorReporter(source).toFailureMessage(warnings)
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
 * and cast back to its type by an accessor declared in the preamble of the script, resolved like any member of the script.
 */

private val ScriptExecutor.Dependency<*>.isHidden: Boolean
    get() = name != SCRIPT_BLOCK_NAME

private val ScriptExecutor.Dependency<*>.providedName: String
    get() = if (isHidden) "$HIDDEN_DEPENDENCY_PREFIX$name" else name

private val ScriptExecutor.Dependency<*>.providedType: KotlinType
    get() = if (isHidden) KotlinType(typeOf<Any?>()) else KotlinType(type)

private val ScriptExecutor.Dependency<*>.accessor: String?
    get() = if (isHidden) {
        "@Suppress(\"UNCHECKED_CAST\") val `$name`: $type get() = `$providedName` as $type"
    } else {
        null
    }
