/*
 * xemantic-kotlin-script - Kotlin script execution with LLM-friendly diagnostics
 * Copyright (C) 2026  Kazimierz Pogoda / Xemantic
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplEvaluator
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.CompiledSnippet
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.repl
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import org.jetbrains.kotlin.scripting.compiler.plugin.services.firReplHistoryProvider
import org.jetbrains.kotlin.scripting.compiler.plugin.services.isReplSnippetSource
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.api.refineConfiguration
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.valueOrNull
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.dependenciesFromClassloader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.jvmTarget
import kotlin.script.experimental.jvm.KJvmEvaluatedSnippet
import kotlin.script.experimental.util.LinkedSnippet

private val logger = KotlinLogging.logger {}

private val sessionIds = AtomicInteger()

/**
 * A stateful Kotlin scripting session, like a Kotlin notebook:
 * the declarations of an executed snippet, like `val`, `var`, `fun`, or `class`, and its imports,
 * stay visible to the snippets executed after it.
 *
 * Each snippet is a regular Kotlin script, compiled by the K2 REPL compiler,
 * with a [ScriptScope] implicit receiver, which lets the snippet launch coroutines living as long as the session,
 * and call suspending functions with [ScriptScope.await].
 * All the snippets of a session are compiled and evaluated on its own thread, one after another,
 * so a snippet can use an API bound to the thread which created it.
 *
 * A failure of a snippet is reported as [ScriptResult.Error], with a message pointing to its offending lines,
 * and the session continues, without the declarations of a snippet which failed to compile.
 */
public interface ScriptSession : AutoCloseable {

    /**
     * Executes the [snippet] after the previous ones, waiting for the snippets executed concurrently.
     *
     * When the caller is cancelled while the snippet is evaluated, the thread of the session is interrupted,
     * and the coroutines of [ScriptScope.await] are cancelled,
     * while a snippet which neither blocks interruptibly, nor awaits, runs to its end.
     * A caller without a [Job] in its context, like `suspend fun main`, cannot cancel the evaluation.
     * A cancellation while the snippet is compiled waits for the compilation to end,
     * and it is evaluated then, as the session would evaluate it before the next one anyway.
     *
     * A failure of the compilation, even an internal one of the compiler, is reported as an [ExecutionPhase.COMPILATION] error,
     * an exception thrown by the snippet, as an [ExecutionPhase.EVALUATION] error,
     * except for a [VirtualMachineError] other than [StackOverflowError], which is thrown.
     *
     * @throws IllegalStateException if the session is closed.
     */
    public suspend fun execute(
        @Language("kotlin") snippet: String
    ): ScriptResult

    /**
     * Closes the session, cancelling the coroutines launched by its snippets,
     * and releasing the compiler and the thread, once the snippet being executed, if any, ends.
     */
    override fun close()

}

/**
 * Creates a [ScriptSession].
 *
 * @param properties the values provided to the snippets as the properties of the session.
 * @param dispatcher the dispatcher of the coroutines launched by the snippets, see [ScriptScope].
 * @throws IllegalArgumentException if the [properties] have duplicate, or reserved, names.
 */
public fun ScriptSession(
    properties: List<ScriptProperty<*>> = emptyList(),
    dispatcher: CoroutineDispatcher = Dispatchers.Default
): ScriptSession = DefaultScriptSession(properties, dispatcher)

/** The names of the members of the [ScriptScope], which the properties would shadow. */
private val reservedNames = setOf("coroutineContext", "await")

internal class DefaultScriptSession(
    private val properties: List<ScriptProperty<*>>,
    dispatcher: CoroutineDispatcher
) : ScriptSession {

    init {
        properties.groupBy { it.name }.forEach { (name, sameName) ->
            require(sameName.size == 1) { "Duplicate property name: $name" }
            require(name !in reservedNames) {
                "Reserved property name: $name - it is a member of the ScriptScope receiving the snippets"
            }
        }
    }

    private val id = sessionIds.incrementAndGet()

    private val name = "script-session-$id"

    private val thread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private val sessionDispatcher = thread.asCoroutineDispatcher()

    private val job = SupervisorJob()

    private val scope = ScriptScope(
        coroutineContext = job + dispatcher + CoroutineName(name)
    )

    init {
        ScriptPropertyValues.register(id, properties.associate { it.name to it.value })
    }

    private val mutex = Mutex()

    private val classLoader = ScriptSession::class.java.classLoader

    private val disposable = Disposer.newDisposable(name)

    private val dependencyResolver = SnippetDependencyResolver(classLoader)

    /** The lines of the snippets by their names, like `snippet3.kts`, accessed on the [thread] only. */
    private val snippets = HashMap<String, List<String>>()

    /** The simple names of the classes declared by the evaluated snippets. */
    private val snippetClassNames = HashSet<String>()

    private val reporter = ScriptErrorReporter(snippets, snippetClassNames)

    private var snippetCount = 0

    /** Created on the first snippet, as resolving the classpath takes long. */
    private var repl: Repl? = null

    @Volatile
    private var closed = false

    private inner class Repl {

        val compilationConfiguration = ScriptCompilationConfiguration {
            jvm {
                dependenciesFromClassloader(
                    classLoader = classLoader,
                    wholeClasspath = true
                )
                // the default 1.8 target cannot inline functions of the classpath
                jvmTarget(scriptJvmTarget(System.getProperty("java.specification.version")))
            }
            implicitReceivers(ScriptScope::class)
            // the snippets can launch coroutines, and declare dependencies, without imports
            defaultImports(DependsOn::class, Repository::class)
            defaultImports("kotlinx.coroutines.*")
            refineConfiguration {
                onAnnotations(DependsOn::class, Repository::class, handler = dependencyResolver::refine)
            }
        }

        val evaluationConfiguration = ScriptEvaluationConfiguration {
            jvm {
                baseClassLoader(dependencyResolver.classLoader)
            }
            implicitReceivers(scope)
        }

        val history = ReplHistory()

        /** Collects the warnings, which the compilation does not return with the compiled snippet. */
        val messageCollector = ScriptDiagnosticsMessageCollector(null)

        val compiler = K2ReplCompiler(
            K2ReplCompiler.createCompilationState(
                messageCollector,
                disposable,
                compilationConfiguration,
                ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
                    repl {
                        firReplHistoryProvider(history)
                        isReplSnippetSource { _, _ -> true }
                    }
                }
            )
        )

        /**
         * Compiles the [snippet] of this [name], keeping it in the history of the compiler only if it compiles,
         * with an internal failure of the compiler, also an [Error], like a [StackOverflowError] on a deeply nested expression,
         * reported as the failure of the compilation,
         * except for a [VirtualMachineError] other than [StackOverflowError], which is thrown.
         */
        suspend fun compile(
            snippet: String,
            name: String
        ): Compilation {
            val result = try {
                compiler.compile(listOf(snippet.toScriptSource(name)), compilationConfiguration)
            } catch (e: Throwable) {
                if (e is VirtualMachineError && e !is StackOverflowError) {
                    // otherwise the next snippet would see the declarations and the dependencies of this one
                    runCatching {
                        history.rollback()
                        dependencyResolver.takeDependencies()
                    }.exceptionOrNull()?.let(e::addSuppressed)
                    throw e
                }
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = e.message?.lineSequence()?.first() ?: e.javaClass.name,
                        exception = e
                    )
                )
            }
            val dependencies = dependencyResolver.takeDependencies()
            if (result is ResultWithDiagnostics.Success) {
                history.commit()
                return Compilation(
                    ResultWithDiagnostics.Success(result.value, messageCollector.diagnostics.toList()),
                    dependencies
                )
            }
            history.rollback()
            return Compilation(
                ResultWithDiagnostics.Failure(
                    dependencies.warnings + dependencies.errors.ifEmpty { result.reports }
                ),
                dependencies
            )
        }

        val evaluator = K2ReplEvaluator()

    }

    override suspend fun execute(
        snippet: String
    ): ScriptResult = mutex.withLock {
        check(!closed) { "The session is closed" }
        // a caller without a job, like `suspend fun main`, cannot cancel the evaluation
        val callerJob = currentCoroutineContext()[Job] ?: Job()
        // a cancelled compilation would leave the compiled snippet to be evaluated before the next one
        val result = withContext(sessionDispatcher + NonCancellable) {
            val repl = repl ?: Repl().also {
                repl = it
                declareProperties(it)
            }
            val name = "snippet${++snippetCount}.kts"
            snippets[name] = snippet.lines()
            logger.debug { "$name: compiling" }
            val compilation = repl.compile(snippet, name)
            when (val result = compilation.result) {
                is ResultWithDiagnostics.Failure -> ScriptResult.Error(
                    message = reporter.describe(result.reports),
                    phase = ExecutionPhase.COMPILATION
                )
                is ResultWithDiagnostics.Success -> {
                    logger.debug { "$name: evaluating" }
                    val evaluation = scope.evaluate(callerJob) {
                        repl.evaluator.eval(result.value, repl.evaluationConfiguration)
                    }
                    evaluation.toScriptResult(compilation)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        result
    }

    /** Declares the [properties] in the snippet preceding the ones of the session. */
    private suspend fun declareProperties(repl: Repl) {
        if (properties.isEmpty()) return
        val name = "properties.kts"
        val source = properties.joinToString("\n") { it.declaration(id) }
        snippets[name] = source.lines()
        val compilation = repl.compile(source, name).result
        val evaluation = compilation.valueOrNull()?.let {
            repl.evaluator.eval(it, repl.evaluationConfiguration).valueOrNull()?.get()?.result
        }
        check(evaluation != null && evaluation !is ResultValue.Error) {
            "The properties cannot be declared:\n" + if (compilation is ResultWithDiagnostics.Failure) {
                reporter.describe(compilation.reports)
            } else {
                (evaluation as? ResultValue.Error)?.let { reporter.describe(it.error) } ?: "evaluation failed"
            }
        }
    }

    private fun ResultWithDiagnostics<*>.toScriptResult(
        compilation: Compilation
    ): ScriptResult {
        val compilationReports = compilation.result.reports
        val resolvedVersions = compilation.dependencies.resolvedVersions
        @Suppress("UNCHECKED_CAST")
        val result = (valueOrNull() as? LinkedSnippet<KJvmEvaluatedSnippet>)
            ?.get()?.result
        result?.scriptClass?.java?.declaredClasses?.mapTo(snippetClassNames) { it.simpleName }
        return when (result) {
            is ResultValue.Value -> ScriptResult.Value(result.value, reporter.warnings(compilationReports), resolvedVersions)
            is ResultValue.Unit -> ScriptResult.Value(Unit, reporter.warnings(compilationReports), resolvedVersions)
            is ResultValue.Error -> {
                val error = result.error
                if (error is VirtualMachineError && error !is StackOverflowError) throw error
                ScriptResult.Error(
                    reporter.describe(error, compilation.dependencies.warnings + compilationReports),
                    ExecutionPhase.EVALUATION
                )
            }
            is ResultValue.NotEvaluated, null -> ScriptResult.Error(
                reporter.describe(reports.ifEmpty { compilation.dependencies.warnings + compilationReports }),
                ExecutionPhase.EVALUATION
            )
        }
    }

    /**
     * The [result] of compiling a snippet, with its [dependencies],
     * reported by the [result] only if it fails.
     */
    private class Compilation(
        val result: ResultWithDiagnostics<LinkedSnippet<CompiledSnippet>>,
        val dependencies: SnippetDependencyResolver.SnippetDependencies
    )

    override fun close() {
        if (closed) return
        closed = true
        job.cancel()
        ScriptPropertyValues.unregister(id)
        thread.execute {
            Disposer.dispose(disposable)
            dependencyResolver.close()
        }
        thread.shutdown()
    }

}

/**
 * The JVM target of the snippets, the one of the running JVM, or the latest one supported by the compiler, if it is newer,
 * like `21` of the `java.specification.version`.
 */
internal fun scriptJvmTarget(
    javaSpecificationVersion: String
): String = (JvmTarget.fromString(javaSpecificationVersion) ?: JvmTarget.supportedValues().last()).description

/**
 * Creates a [ScriptSession] with the properties defined by the [properties] builder.
 */
public inline fun ScriptSession(
    properties: ScriptProperties.() -> Unit
): ScriptSession = ScriptSession(
    ScriptProperties().apply(properties).properties
)
