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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.SourceCode
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
 * Creates a [ScriptExecutor.Dependency] of the reified type [T].
 */
public inline fun <reified T : Any> dependency(
    name: String,
    value: T?
): ScriptExecutor.Dependency<T> = ScriptExecutor.Dependency(
    name, T::class, value
)

/**
 * Executes Kotlin scripts, typically written by an LLM.
 *
 * Failures are reported as [ScriptResult.Error], with a message enclosed in the [errorTag],
 * which points to the offending lines of the script.
 *
 * @param errorTag the name of the tag enclosing error messages.
 */
public class ScriptExecutor(
    private val errorTag: String = "impediment"
) {

    /**
     * A property provided to the script.
     */
    public class Dependency<T : Any>(
        public val name: String,
        public val type: KClass<T>,
        public val value: T?
    )

    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalAtomicApi::class)
    private val scriptIdSeq = AtomicInt(1)

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

    /**
     * Executes the [script] in the coroutine of the caller, with a [CoroutineScope] receiver,
     * so the caller awaits all the coroutines it launched, and its cancellation cancels them.
     * The script body is evaluated on [Dispatchers.IO], as it might block.
     */
    public suspend fun execute(
        @Language("kotlin") script: String,
        dependencies: List<Dependency<*>> = emptyList(),
    ): ScriptResult {

        @OptIn(ExperimentalAtomicApi::class)
        val scriptId = scriptIdSeq.fetchAndIncrement()
        logger.debug { "Script[$scriptId]: Executing" }

        val (result, duration) = measureTimedValue {
            ScriptHandler(
                scriptId,
                dependencies,
                script
            ).handle()
        }

        logger.debug { "Script[$scriptId]: Execution took $duration in total" }

        return result
    }

    private inner class ScriptHandler(
        private val scriptId: Int,
        dependencies: List<Dependency<*>>,
        script: String
    ) {

        private val allDependencies = dependencies + Dependency(
            name = "_script",
            type = ScriptBlock::class,
            value = ScriptBlock
        )

        private val source = SuspendableScriptSource(script)

        suspend fun handle(): ScriptResult {
            val result = when (val compilationResult = compile()) {
                is ResultWithDiagnostics.Success -> {
                    val compiledScript = compilationResult.value
                    evaluate(compiledScript)
                }
                is ResultWithDiagnostics.Failure -> {
                    val failureMessage = errorReporter().toFailureMessage(
                        phase = ExecutionPhase.COMPILATION,
                        failure = compilationResult
                    )
                    logger.debug {
                        "Script[$scriptId]: Compilation (1/2) failure message: $failureMessage"
                    }
                    ScriptResult.Error(failureMessage)
                }
            }

            return result
        }

        private suspend fun compile(): ResultWithDiagnostics<CompiledScript>  {

            logger.debug { "Script[$scriptId]: Compiling (1/2)" }

            val (compilationResult, duration) = measureTimedValue {
                scriptingHost.compiler(
                    script = source,
                    scriptCompilationConfiguration = compilationConfig()
                )
            }

            when (compilationResult) {
                is ResultWithDiagnostics.Success -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) succeeded after $duration"
                }
                is ResultWithDiagnostics.Failure -> logger.debug {
                    "Script[$scriptId]: Compilation (1/2) failed after $duration"
                }
            }

            return compilationResult
        }

        private suspend fun evaluate(
            compiledScript: CompiledScript
        ): ScriptResult {

            logger.debug { "Script[$scriptId]: Evaluating (2/2)" }

            val (evaluationResult, duration) = measureTimedValue {
                scriptingHost.evaluator(
                    compiledScript = compiledScript,
                    scriptEvaluationConfiguration = evaluationConfig()
                )
            }

            val result = when (evaluationResult) {
                is ResultWithDiagnostics.Success<EvaluationResult> -> {
                    evaluationResult.value.returnValue.toScriptResult()
                }
                is ResultWithDiagnostics.Failure -> {
                    // in practice, this never happens
                    val failureMessage = errorReporter().toFailureMessage(
                        phase = ExecutionPhase.EVALUATION,
                        failure = evaluationResult
                    )
                    ScriptResult.Error(failureMessage)
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

        private suspend fun ResultValue.toScriptResult() = when (this) {
            is ResultValue.Value -> {
                @Suppress("UNCHECKED_CAST")
                val block = value as suspend CoroutineScope.() -> Any?
                try {
                    ScriptResult.Value(
                        withContext(Dispatchers.IO) { block() }
                    )
                } catch (e: Exception) {
                    // the cancellation of the caller is not a failure of the script
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    logger.debug(e) {
                        "Script[$scriptId]: Evaluation failed with error"
                    }
                    val cause = e.cause
                    val throwable = if (
                        (cause != null)
                        && (cause::class == e::class)
                        && (cause.message == cause.message)
                    ) {
                        cause
                    } else {
                        e
                    }
                    val failureMessage = errorReporter().toFailureMessage(throwable)
                    ScriptResult.Error(
                        failureMessage
                    )
                }
            }
            is ResultValue.Unit -> ScriptResult.Value(Unit)
            is ResultValue.Error -> {
                val failureMessage = errorReporter().toFailureMessage(error)
                ScriptResult.Error(
                    message = failureMessage
                )
            }
            is ResultValue.NotEvaluated -> throw IllegalStateException(
                "Should never happen"
            )
        }

        private fun evaluationConfig() = ScriptEvaluationConfiguration {
            providedProperties(
                *(allDependencies.map { it.name to it.value }.toTypedArray())
            )
        }

        private fun compilationConfig() = ScriptCompilationConfiguration(compilationConfigurationTemplate) {
            providedProperties(
                *(allDependencies.map { it.name to KotlinType(it.type)}.toTypedArray())
            )
        }

        private fun errorReporter() = ScriptErrorReporter(
            errorTag = errorTag,
            scriptFileName = source.locationId,
            scriptLines = source.lines,
            lastImportLine = source.lastImportLine
        )

    }

}

private fun Iterable<ScriptDiagnostic>.filterNonIgnorable() = filterNot { diagnostic ->
    diagnostic.severity == ScriptDiagnostic.Severity.DEBUG && listOf(
        "Using JDK home inferred from java.home",
        "Loading modules:"
    ).any { diagnostic.message.startsWith(it) }
}

private class ScriptErrorReporter(
    private val errorTag: String,
    private val scriptFileName: String,
    private val scriptLines: List<String>,
    private val lastImportLine: Int
) {

    fun toFailureMessage(
        phase: ExecutionPhase,
        failure: ResultWithDiagnostics.Failure
    ) = buildString {
        append("<$errorTag phase=\"${phase.name}\">\n")
        failure.reports.filterNonIgnorable().forEach {
            appendScriptDiagnostic(it)
        }
        append("</$errorTag>\n")
    }

    fun toFailureMessage(
        throwable: Throwable
    ) = buildString {
        append("<$errorTag phase=\"${ExecutionPhase.EVALUATION.name}\">\n")
        appendException(error = throwable)
        append("</$errorTag>\n")
    }

    private fun StringBuilder.appendException(
        prefix: String = "",
        error: Throwable
    ) {
        append(prefix)
        append(error::class.qualifiedName)
        error.message?.let {
            append(": ")
            append(it)
        }
        append("\n")

        error.stackTrace.forEachIndexed { index, trace ->

            // frames of other scripts, e.g. the one executing this script, are not this script's;
            // synthetic frames of the script block carry no line number
            val isScriptTrace = trace.fileName == scriptFileName && trace.lineNumber > 0

            if (isScriptTrace || index == 0) {
                val className = if (isScriptTrace) {
                    "Script"
                } else {
                    trace.className
                }

                val fileName = if (isScriptTrace) {
                    "script.kts"
                } else {
                    trace.fileName
                }

                val lineNumber = if (isScriptTrace) {
                    trace.lineNumber - 1
                } else {
                    trace.lineNumber
                }

                append("  at ")
                append(className)
                append(".")
                append(trace.methodName)
                append("(")
                append(fileName)
                append(":")
                append(lineNumber)
                append(")\n")
                if (isScriptTrace) {
                    append("  | ")
                    append(scriptLines[lineNumber])
                    append("\n")
                }
            }

        }

        error.cause?.let {
            appendException(
                prefix = "Caused by: ",
                error = it
            )
        }
    }

    private fun StringBuilder.appendScriptDiagnostic(
        diagnostic: ScriptDiagnostic,
    ) {
        append("[${diagnostic.severity.name}] ${diagnostic.message}\n")
        diagnostic.location?.let {
            appendLocation(location = it)
        }
        append("\n")
    }

    private fun adjustLineNumber(
        line: Int
    ): Int = when {
        line <= lastImportLine -> line
        line == scriptLines.size -> maxOf(1, line - 2)
        else -> maxOf(1, line - 1)
    }

    private fun StringBuilder.appendLocation(
        location: SourceCode.Location,
    ) {

        val startLine = adjustLineNumber(location.start.line)
        val endLine = adjustLineNumber(location.end?.line ?: startLine)

        append("  at line")
        if (startLine == endLine) {
            append(" ")
            append(startLine)
        } else {
            append("s ")
            append(startLine)
            append("-")
            append(endLine)
        }
        append("\n")

        (startLine..endLine).forEach { line ->
            append("  | ")
            val lineIndexOffset = if ((line <= lastImportLine) || (startLine == (scriptLines.size - 1))) 1 else 0
            val lineIndex = line - lineIndexOffset
            // Guard against out of bounds access
            if (lineIndex < 0 || lineIndex >= scriptLines.size) {
                append("<error>Unable to display source line</error>\n")
                return@forEach
            }
            val scriptLine = scriptLines[lineIndex]
            val lineBuilder = StringBuilder(scriptLine)
            if (location.start.line == scriptLines.size) {
                lineBuilder.append("<error></error>")
            } else {
                if (line == startLine) {
                    val startCol = (location.start.col - 1).coerceIn(0, lineBuilder.length)
                    lineBuilder.insert(startCol, "<error>")
                    if ((line == endLine) && (location.end != null)) {
                        val endCol = (location.end!!.col + 6).coerceIn(0, lineBuilder.length)
                        lineBuilder.insert(endCol, "</error>")
                    }
                }
                if ((line != startLine) && (line == endLine)) { // TODO should it be else without first expression?
                    val endCol = (location.end!!.col - 1).coerceIn(0, lineBuilder.length)
                    lineBuilder.insert(endCol, "</error>")
                }
            }

            append(lineBuilder)
            append("\n")
        }
    }

}

private class SuspendableScriptSource(
    script: String
) : SourceCode {

    private val parts = splitScriptImportsAndBody(script)

    override val text: String = buildString {
        parts.imports.forEach {
            append(it)
            append('\n')
        }
        append("_script {\n")
        append(parts.body.joinToString(separator = "\n"))
        append("\n}")
    }

    override val name: String? = null

    override val locationId: String = "\$${Integer.toHexString(text.hashCode())}.kts"

    val lines: List<String> = text.lines()

    val lastImportLine: Int = parts.imports.size
}

private fun splitScriptImportsAndBody(
    script: String
): ScriptParts {

    val lines = script.lines()

    val lastImportIndex = lines.indexOfLast {
        it.startsWith("import ")
    }

    return if (lastImportIndex == -1) {
        ScriptParts(
            imports = emptyList(),
            body = script.lines()
        )
    } else {
        ScriptParts(
            imports = lines.subList(0, lastImportIndex + 1),
            body = lines.subList(lastImportIndex + 1, lines.size)
        )
    }
}

private data class ScriptParts(
    val imports: List<String>,
    val body: List<String>
)
