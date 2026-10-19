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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
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
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
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

    private val compilationCache = if (compilationCacheSize > 0) {
        ScriptCompilationCache(compilationCacheSize)
    } else {
        null
    }

    private val scriptingHost = BasicJvmScriptingHost()

    private val compilationConfigurationTemplate = ScriptCompilationConfiguration {
        jvm {
            dependenciesFromCurrentContext(
                wholeClasspath = true
            )
            // the default 1.8 target cannot inline functions of the classpath, e.g. `dependency(...)`
            jvmTarget(System.getProperty("java.specification.version"))
        }
        // Add helpful default imports
        defaultImports(
            "kotlinx.coroutines.async"
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
            require(name != SCRIPT_BLOCK_NAME && !name.startsWith(GENERIC_DEPENDENCY_PREFIX)) {
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

    private inner class ScriptHandler(
        private val scriptId: Int,
        dependencies: List<ScriptExecutor.Dependency<*>>,
        script: String
    ) {

        private val allDependencies = dependencies + ScriptExecutor.Dependency(
            name = SCRIPT_BLOCK_NAME,
            type = typeOf<ScriptBlock>(),
            value = ScriptBlock
        )

        private val source = ScriptSource(
            script,
            preamble = allDependencies.mapNotNull { dependency ->
                dependency.accessor?.let { dependency.name to it }
            }.toMap()
        )

        suspend fun handle(): ScriptResult {
            source.unmatchedClosingBrace?.let {
                // the brace would close the script block, misplacing all the errors
                return ScriptResult.Error(
                    ScriptErrorReporter(source).toUnmatchedClosingBraceMessage(it),
                    ExecutionPhase.COMPILATION
                )
            }
            val result = when (val compilationResult = compile()) {
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

        private suspend fun compile(): ResultWithDiagnostics<CompiledScript>  {

            logger.debug { "Script[$scriptId]: Compiling (1/2)" }

            val (compilationResult, duration) = measureTimedValue {
                compilationCache?.getOrCompile(
                    ScriptCompilationCache.Key(
                        script = source.text,
                        dependencies = allDependencies.map { it.name to it.type }
                    ),
                    ::doCompile
                ) ?: doCompile()
            }

            when (compilationResult) {
                is Success -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) succeeded after $duration"
                }
                is Failure -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) failed after $duration"
                }
            }

            return compilationResult
        }

        private suspend fun doCompile(): ResultWithDiagnostics<CompiledScript> {
            @OptIn(ExperimentalAtomicApi::class)
            compilations.incrementAndFetch()
            return scriptingHost.compiler(
                script = source,
                scriptCompilationConfiguration = compilationConfig()
            )
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
                    "Script[$scriptId]: Evaluation (2/2) failed after $duration"
                }
            }

            return result
        }

        private suspend fun ResultValue.toScriptResult(
            warnings: List<ScriptDiagnostic>
        ): ScriptResult = when (this) {
            is ResultValue.Value -> {
                @Suppress("UNCHECKED_CAST")
                val block = value as suspend CoroutineScope.() -> Any?
                try {
                    ScriptResult.Value(
                        value = coroutineScope { block() },
                        warnings = warningsMessage(warnings)
                    )
                } catch (e: Throwable) {
                    // the cancellation of the caller is not a failure of the script
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    // unlike after the StackOverflowError, the JVM might not be able to continue
                    if (e is VirtualMachineError && e !is StackOverflowError) throw e
                    logger.debug(e) {
                        "Script[$scriptId]: Evaluation failed with error"
                    }
                    failure(e.unwrapRecoveredCopy(), warnings, block::class.java.name)
                }
            }
            is ResultValue.Unit -> ScriptResult.Value(Unit, warningsMessage(warnings))
            is ResultValue.Error -> failure(error, warnings, blockClassName = null)
            is ResultValue.NotEvaluated -> throw IllegalStateException(
                "Should never happen"
            )
        }

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
            providedProperties(
                *(allDependencies.map { it.providedName to it.value }.toTypedArray())
            )
        }

        private fun compilationConfig() = ScriptCompilationConfiguration(compilationConfigurationTemplate) {
            providedProperties(
                *(allDependencies.map { it.providedName to KotlinType(it.type) }.toTypedArray())
            )
        }

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
        && read(emptyArray()) { stackTrace }.any { it.className.startsWith("_COROUTINE.") }
    ) {
        cause
    } else {
        this
    }
}

private const val SCRIPT_BLOCK_NAME = "_script"

private const val GENERIC_DEPENDENCY_PREFIX = "_dependency_"

/*
 * The scripting host erases the type arguments of a provided property,
 * so a dependency of generic type is provided under a hidden name,
 * and cast back to its type by an accessor declared in the preamble of the script.
 */

private val ScriptExecutor.Dependency<*>.isGeneric: Boolean
    get() = type.arguments.isNotEmpty()

private val ScriptExecutor.Dependency<*>.providedName: String
    get() = if (isGeneric) "$GENERIC_DEPENDENCY_PREFIX$name" else name

private val ScriptExecutor.Dependency<*>.accessor: String?
    get() = if (isGeneric) {
        "@Suppress(\"UNCHECKED_CAST\", \"USELESS_CAST\") val `$name`: $type get() = `$providedName` as $type"
    } else {
        null
    }
