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
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
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
 * A snippet which failed in the evaluation keeps its declarations, also the properties it did not initialize,
 * which hold `null` despite their type, or zero, or `false`, if it is a primitive one,
 * and a [NullPointerException] reading such a property is reported with a hint to declare it again.
 */
public interface ScriptSession : AutoCloseable {

    /**
     * Executes the [snippet] after the previous ones, waiting for the snippets executed concurrently.
     *
     * When the caller is cancelled, or the session is [closed][close], while the snippet is evaluated,
     * the thread of the session is interrupted, and the coroutines of [ScriptScope.await] are cancelled,
     * so the snippet fails with an [ExecutionPhase.EVALUATION] error, returned to a caller which is not cancelled,
     * while a snippet which neither blocks interruptibly, nor awaits, runs to its end.
     * A caller without a [Job] in its context, like `suspend fun main`, cannot cancel the evaluation, only closing the session can.
     * A cancellation, or closing the session, while the snippet is compiled waits for the compilation to end,
     * as the compiler is never interrupted, and the snippet is evaluated then, cancelled already,
     * as the session would evaluate it before the next one anyway.
     *
     * A failure of the compilation, even an internal one of the compiler, is reported as an [ExecutionPhase.COMPILATION] error,
     * an exception thrown by the snippet, as an [ExecutionPhase.EVALUATION] error,
     * except for a [VirtualMachineError] other than [StackOverflowError], which is thrown.
     * The declarations of a snippet which threw stay visible to the next snippets, also the uninitialized properties, see [ScriptSession].
     * A failure of declaring the properties of the session, like a type the compiler rejects although it is valid,
     * is reported by every execution, in the phase in which it occurred, without executing the snippet.
     *
     * @throws IllegalStateException if the session is closed, also while the snippet waits for the ones executed concurrently.
     */
    public suspend fun execute(
        @Language("kotlin") snippet: String
    ): ScriptResult

    /**
     * Closes the session, cancelling the coroutines launched by its snippets, and the evaluation of the snippet being executed, if any,
     * like a cancellation of its caller, see [execute],
     * and releasing the compiler, the thread, and the values of the properties, once that snippet ends,
     * so the properties are declared also when the session is closed while declaring them.
     * The snippets and coroutines still running read the properties as before, as they were declared already.
     * Closing a closed session, also concurrently, has no effect.
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

/**
 * The [ScriptSession], with the thread of its snippets run by the single thread [executor] created with the given thread factory.
 */
internal class DefaultScriptSession(
    private val properties: List<ScriptProperty<*>>,
    dispatcher: CoroutineDispatcher,
    executor: (ThreadFactory) -> ExecutorService = Executors::newSingleThreadExecutor
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

    private val thread = executor { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    /**
     * Dispatches to the [thread], or, once it is shut down by [close], to [Dispatchers.IO],
     * where the block of [execute] only fails the check of a closed session,
     * unlike the dispatcher of `asCoroutineDispatcher`, which would cancel the rejected block,
     * failing the execution with a [kotlinx.coroutines.CancellationException] instead.
     */
    private val sessionDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            try {
                thread.execute(block)
            } catch (_: RejectedExecutionException) {
                Dispatchers.IO.dispatch(context, block)
            }
        }
    }

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

    /** The properties of the snippets which failed in the evaluation, `null` then, see [keepUninitializedProperties]. */
    private val uninitializedProperties = HashMap<String, UninitializedProperty>()

    private val reporter = ScriptErrorReporter(snippets, snippetClassNames, uninitializedProperties)

    private var snippetCount = 0

    /** Created on the first snippet, as resolving the classpath takes long. */
    private var repl: Repl? = null

    /** The failure of declaring the [properties], reported by every execution, as the snippets would not see them. */
    private var propertiesFailure: ScriptResult.Error? = null

    private val closed = AtomicBoolean()

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

        /** Disposed with the session, or on its own, if the properties cannot be declared, see [execute]. */
        val disposable = Disposer.newDisposable("$name-repl").also {
            Disposer.register(this@DefaultScriptSession.disposable, it)
        }

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
                if (e.isFatal) {
                    // otherwise the next snippet would see the declarations and the dependencies of this one
                    runCatching {
                        conclude(null)
                    }.exceptionOrNull()?.takeIf { it !== e }?.let(e::addSuppressed)
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
            return conclude(result)
        }

        /**
         * Takes the dependencies of the compiled snippet, and keeps it in the history only if the [result] is a success,
         * where a `null` [result] stands for a compilation which threw.
         */
        private fun conclude(
            result: ResultWithDiagnostics<LinkedSnippet<CompiledSnippet>>?
        ): Compilation {
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
                    dependencies.warnings + dependencies.errors.ifEmpty { result?.reports.orEmpty() }
                ),
                dependencies
            )
        }

        val evaluator = K2ReplEvaluator()

    }

    override suspend fun execute(
        snippet: String
    ): ScriptResult = mutex.withLock {
        check(!closed.get()) { "The session is closed" }
        // a caller without a job, like `suspend fun main`, cannot cancel the evaluation
        val callerJob = currentCoroutineContext()[Job] ?: Job()
        // a cancelled compilation would leave the compiled snippet to be evaluated before the next one
        val result = withContext(sessionDispatcher + NonCancellable) {
            // checked again on the thread, as the session could be closed before the block was dispatched to it,
            // and the block could run after the disposal of the compiler then, or on another thread, see sessionDispatcher,
            // while a session closed later disposes the compiler only after this block, queued after it on the thread
            check(!closed.get()) { "The session is closed" }
            // kept only once the properties are declared, or their failure is, so a fatal error,
            // like an OutOfMemoryError, cannot leave the session without them, while the next execution
            // declares them again in another compiler, not seeing the declaration which threw
            val repl = repl ?: Repl().also { created ->
                try {
                    propertiesFailure = declareProperties(created)
                } catch (e: Throwable) {
                    runCatching { Disposer.dispose(created.disposable) }.exceptionOrNull()?.let(e::addSuppressed)
                    throw e
                }
                repl = created
            }
            propertiesFailure ?: repl.execute(snippet, "snippet${++snippetCount}.kts", callerJob)
        }
        currentCoroutineContext().ensureActive()
        result
    }

    /** Compiles and evaluates the [snippet] of this [name], cancelled together with the [callerJob]. */
    private suspend fun Repl.execute(
        snippet: String,
        name: String,
        callerJob: Job
    ): ScriptResult {
        snippets[name] = snippet.lines()
        logger.debug { "$name: compiling" }
        val compilation = compile(snippet, name)
        return when (val result = compilation.result) {
            is ResultWithDiagnostics.Failure -> ScriptResult.Error(
                message = reporter.describe(result.reports),
                phase = ExecutionPhase.COMPILATION
            )
            is ResultWithDiagnostics.Success -> {
                logger.debug { "$name: evaluating" }
                val evaluation = scope.evaluate(callerJob) {
                    evaluator.eval(result.value, evaluationConfiguration)
                }
                evaluation.toScriptResult(compilation, name)
            }
        }
    }

    /**
     * Declares the [properties] in the snippet preceding the ones of the session,
     * returning the failure of the declaration, if any, in the phase in which it failed.
     *
     * A failed declaration is not retried, as its source is the same on every execution,
     * and a declaration which failed to evaluate stays compiled in the history of the compiler.
     */
    private suspend fun declareProperties(repl: Repl): ScriptResult.Error? {
        if (properties.isEmpty()) return null
        val source = properties.joinToString("\n") { it.declaration(id) }
        // not cancelled by the caller, so its cancellation cannot fail the declaration for all the next executions
        val result = repl.execute(source, "properties.kts", Job())
        return (result as? ScriptResult.Error)?.let {
            ScriptResult.Error("The properties cannot be declared:\n${it.message}", it.phase)
        }
    }

    /** The [ScriptResult] of evaluating the snippet of this [name]. */
    private fun ResultWithDiagnostics<*>.toScriptResult(
        compilation: Compilation,
        name: String
    ): ScriptResult {
        @Suppress("UNCHECKED_CAST")
        val result = (valueOrNull() as? LinkedSnippet<KJvmEvaluatedSnippet>)
            ?.get()?.result
        result?.scriptClass?.java?.declaredClasses?.mapTo(snippetClassNames) { it.simpleName }
        return reporter.scriptResult(result, reports, compilation.result.reports, compilation.dependencies).also {
            // after reporting it, as the snippet itself did not read them after it failed
            if (result is ResultValue.Error) {
                result.scriptClass?.java?.let { keepUninitializedProperties(it, name) }
            }
        }
    }

    /**
     * Keeps the properties of the [scriptClass] of the snippet of this [name], which failed in the evaluation, which are `null`,
     * most likely as the snippet failed before initializing them, while the later snippets still see them,
     * so that a [NullPointerException] reading them is hinted at, see [ScriptErrorReporter].
     *
     * The properties of the snippet are the static fields of its class, read by the field or by the getter,
     * like `Snippet1.s` or `Snippet1.getS()` in the message of the [NullPointerException],
     * while a property of a primitive type is never `null`, it is left zero, or `false`, instead.
     */
    private fun keepUninitializedProperties(
        scriptClass: Class<*>,
        name: String
    ) {
        try {
            val methods = scriptClass.declaredMethods.filter { it.parameterCount == 0 }.mapTo(HashSet()) { it.name }
            scriptClass.declaredFields.filter { field ->
                Modifier.isStatic(field.modifiers)
                    && !field.type.isPrimitive
                    // like the INSTANCE of the snippet, or the delegate of a property, `s$delegate`
                    && field.type != scriptClass
                    && '$' !in field.name
                    && field.trySetAccessible()
                    && field.get(null) == null
            }.forEach { field ->
                val property = UninitializedProperty(field.name, name)
                uninitializedProperties["${scriptClass.name}.${field.name}"] = property
                // like `getS`, or `isOk` of `val isOk`
                val getter = if (field.name.startsWith("is")) field.name else "get${field.name.replaceFirstChar { it.uppercaseChar() }}"
                if (getter in methods) {
                    uninitializedProperties["${scriptClass.name}.$getter()"] = property
                }
            }
        } catch (e: Exception) {
            logger.debug(e) { "$name: cannot read the properties of the snippet which failed" }
        } catch (e: LinkageError) {
            logger.debug(e) { "$name: cannot read the properties of the snippet which failed" }
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
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
        // released after the last evaluation on the thread, which might be the declaration of the properties reading them,
        // and before the disposal, so its failure cannot keep them
        thread.execute {
            ScriptPropertyValues.unregister(id)
            Disposer.dispose(disposable)
            dependencyResolver.close()
        }
        thread.shutdown()
    }

}

/**
 * Whether this throwable is thrown by the session instead of being reported:
 * a [VirtualMachineError], like an [OutOfMemoryError], other than a [StackOverflowError], which is reported as a failure of the snippet.
 */
private val Throwable.isFatal: Boolean
    get() = this is VirtualMachineError && this !is StackOverflowError

/**
 * The [ScriptResult] of the evaluation [result] of a snippet, `null` if the evaluation failed with the [evaluationReports],
 * where [ResultValue.NotEvaluated] is a failure as well,
 * with the [compilationReports] and the [dependencies] of the snippet.
 *
 * @throws VirtualMachineError thrown by the snippet, other than [StackOverflowError].
 */
internal fun ScriptErrorReporter.scriptResult(
    result: ResultValue?,
    evaluationReports: List<ScriptDiagnostic>,
    compilationReports: List<ScriptDiagnostic>,
    dependencies: SnippetDependencyResolver.SnippetDependencies
): ScriptResult = when (result) {
    is ResultValue.Value -> ScriptResult.Value(result.value, warnings(compilationReports), dependencies.resolvedVersions)
    is ResultValue.Unit -> ScriptResult.Value(Unit, warnings(compilationReports), dependencies.resolvedVersions)
    is ResultValue.Error -> {
        val error = result.error
        if (error.isFatal) throw error
        ScriptResult.Error(
            describe(error, dependencies.warnings + compilationReports),
            ExecutionPhase.EVALUATION
        )
    }
    is ResultValue.NotEvaluated, null -> ScriptResult.Error(
        describe(evaluationReports.ifEmpty { dependencies.warnings + compilationReports }),
        ExecutionPhase.EVALUATION
    )
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
