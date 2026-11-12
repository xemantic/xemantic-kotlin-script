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

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplCompiler
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.K2ReplEvaluator
import org.jetbrains.kotlin.scripting.compiler.plugin.impl.ScriptDiagnosticsMessageCollector
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.jvm.internal.CoroutineStackFrame
import kotlin.coroutines.resume
import kotlin.coroutines.startCoroutine
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
import kotlin.script.experimental.api.compilerOptions
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

/** The message of the [IllegalStateException] thrown by [ScriptSession.execute] of a closed session. */
private const val SESSION_CLOSED_MESSAGE = "The session is closed"

/**
 * The stack size of the thread of a session, in bytes.
 *
 * The K2 compiler recurses deeply, in the parser, PSI and FIR building,
 * so the default stack overflows at a few hundred to a few thousand nesting levels of an expression, depending on the JVM,
 * like nested parentheses, or a chain of `+`, which a legitimate snippet, especially a generated one, can exceed,
 * while 16 MB takes tens of thousands of levels, and lets an evaluated snippet recurse deeper as well,
 * with the overflow of an even deeper snippet still reported as a compilation failure, in milliseconds.
 * The stack of a thread is reserved virtually and committed lazily, so the size costs little for the single thread of a session,
 * although the memory committed by a snippet which overflowed the stack stays resident until the session is closed.
 */
internal const val SESSION_THREAD_STACK_SIZE: Long = 16L * 1024 * 1024

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
 * The thread is given a 16 MB stack, larger than the default one,
 * so the compiler takes a deeply nested expression, of thousands of nesting levels, and a snippet can recurse deeper,
 * while a [StackOverflowError] of the compiler on an even deeper one is reported as a compilation failure.
 *
 * A failure of a snippet is reported as [ScriptResult.Error], with a message pointing to its offending lines,
 * and the session continues, without the declarations of a snippet which failed to compile.
 * A snippet which failed in the evaluation keeps its declarations, also the properties it did not initialize,
 * which hold `null` despite their type, or zero, or `false`, if it is a primitive one,
 * while the functions and classes compiled against such a property keep reading it, also after it is redeclared.
 * The error of the snippet names the properties it left `null`, with a hint to declare them again,
 * together with the declarations reading them, or to assign a `var`, which all of them read,
 * and a [NullPointerException] of the JVM naming such a property later gets the hint as well,
 * with the declarations reading it, and calling them, found in the stack trace.
 * Neither hint covers a property of a primitive type,
 * and the later one needs the JVM to name the property, which it does not for a value copied from it before the failing read,
 * for the property passed as an argument rejected by the check of a non-null parameter,
 * or without the helpful messages of the [NullPointerException], unless it is a delegated property, found by its getter.
 */
public interface ScriptSession : AutoCloseable {

    /**
     * Executes the [snippet] after the previous ones, waiting for the snippets executed concurrently.
     *
     * When the caller is cancelled, or the session is [closed][close], while the snippet is evaluated,
     * the thread of the session is interrupted, and the coroutines of [ScriptScope.await] are cancelled,
     * so a blocking call of the snippet, like `Thread.sleep`, throws an [InterruptedException], and its `await` a [kotlinx.coroutines.CancellationException],
     * which fail the snippet with an [ExecutionPhase.EVALUATION] error, returned to a caller which is not cancelled, unless the snippet catches them,
     * and ends without blocking, or awaiting, again, as its evaluation stays cancelled,
     * while a snippet which neither blocks interruptibly, nor awaits, runs to its end.
     * A caller without a [Job] in its context, like `suspend fun main`, cannot cancel the evaluation, only closing the session can.
     * The caller resumes where its dispatcher resumes it, like the main thread for `Dispatchers.Main.immediate`,
     * or from [Dispatchers.Default], if it would resume in place, on the thread of the session,
     * blocking the next snippet with whatever blocks the caller then,
     * like a caller without a dispatcher, like `suspend fun main` as well, or one on [Dispatchers.Unconfined],
     * so such a caller needs a free worker of [Dispatchers.Default], which the coroutines launched by the snippets share by default, see [ScriptScope],
     * while the next snippets do not wait for it, as the turn of the caller ends with its snippet.
     * A caller whose dispatcher fails to resume it, like the one of an executor shut down, resumes without it, failing with that exception.
     * A caller cancelled while its snippet waits for the ones executed concurrently resumes at once, without executing it,
     * and so does a caller cancelled already when it calls this function, unless no other snippet is executed, or waiting.
     * The README details these cases, and the ones of a caller waiting for the snippet of another caller.
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
     * which might be the declaration of the properties, so they are declared also when the session is closed while declaring them,
     * while the snippet waiting for the declaration is rejected, like one executed after closing.
     * The snippets and coroutines still running keep reading the properties from the fields of their scripts, unaffected by the release.
     * Closing a closed session, also concurrently, has no effect,
     * and returns at once, also while the first closing is still cancelling the coroutines, or queueing the release.
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

/** The path of the jar of the Kotlin serialization compiler plugin, a dependency of this library. */
private val serializationPlugin: String by lazy {
    java.io.File(
        org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationComponentRegistrar::class.java
            .protectionDomain.codeSource.location.toURI()
    ).path
}

/** The names of the members of the [ScriptScope], which the properties would shadow. */
private val reservedNames = setOf("coroutineContext", "await")

/**
 * The [ScriptSession], with the thread of its snippets run by the single thread [executor] created with the given thread factory,
 * and a caller which would resume in place, on that thread, resumed from the [resumeExecutor] instead, see [ScriptSession.execute].
 *
 * @param newReplDisposable creates the disposable of a compiler of this name, see [createRepl],
 * replaced only by the tests, to make its disposal fail.
 */
internal class DefaultScriptSession(
    private val properties: List<ScriptProperty<*>>,
    dispatcher: CoroutineDispatcher,
    private val resumeExecutor: Executor = Dispatchers.Default.asExecutor(),
    private val newReplDisposable: (name: String) -> Disposable = Disposer::newDisposable,
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

    /** The id of the session, which its property values are registered by, see [ScriptPropertyValues], exposed only for the tests. */
    internal val id = sessionIds.incrementAndGet()

    private val name = "script-session-$id"

    /** The thread run by the [thread] executor, replaced only if an exception escapes a task of it. */
    @Volatile
    private var sessionThread: Thread? = null

    private val thread = executor { runnable ->
        Thread(null, runnable, name, SESSION_THREAD_STACK_SIZE).apply {
            isDaemon = true
            sessionThread = this
        }
    }

    /** The turns whose tasks are queued, or run, on the [thread], see [Turn.submit]. */
    private val turns = AtomicInteger()

    private val job = SupervisorJob()

    private val scope = ScriptScope(
        coroutineContext = job + dispatcher + CoroutineName(name)
    )

    init {
        ScriptPropertyValues.register(id, properties.associate { it.name to it.value })
    }

    private val classLoader = ScriptSession::class.java.classLoader

    private val disposable = Disposer.newDisposable(name)

    /** The resolver of the dependencies declared by the snippets, exposed only for the tests. */
    internal val dependencyResolver = SnippetDependencyResolver(classLoader, name)

    /** The lines of the snippets by their names, like `snippet3.kts`, accessed on the [thread] only. */
    private val snippets = HashMap<String, List<String>>()

    /** The declarations of the evaluated snippets, also of the provided properties. */
    private val snippetDeclarations = SnippetDeclarations()

    /** The properties of the snippets which failed in the evaluation, `null` then. */
    private val uninitializedProperties = UninitializedProperties()

    /** The frames of the code inlined into the snippets, read from their class files. */
    private val inlinedFrames = InlinedFrames(snippets)

    /** The properties of the accessors of the classes of the snippets. */
    private val propertyAccessors = PropertyAccessors()

    private val reporter = ScriptErrorReporter(
        snippets, snippetDeclarations, uninitializedProperties, inlinedFrames, propertyAccessors
    )

    private var snippetCount = 0

    /** Created on the first snippet, as resolving the classpath takes long, see [createRepl]. */
    internal var repl: Repl? = null

    /**
     * The failure of declaring the [properties], reported by every execution, as the snippets would not see them,
     * kept instead of the [repl], as nothing can be compiled in it again.
     */
    private var propertiesFailure: ScriptResult.Error? = null

    private val closed = AtomicBoolean()

    /**
     * The K2 REPL compiler of the session, with its evaluator.
     *
     * @param disposable the disposable of the compiler, disposed with the session,
     * or on its own, if the properties cannot be declared, or the compiler cannot be created, see [createRepl].
     */
    internal inner class Repl(
        val disposable: Disposable
    ) {

        val compilationConfiguration = ScriptCompilationConfiguration {
            jvm {
                dependenciesFromClassloader(
                    classLoader = classLoader,
                    wholeClasspath = true
                )
                // the default 1.8 target cannot inline functions of the classpath
                jvmTarget(scriptJvmTarget(System.getProperty("java.specification.version")))
            }
            // the serialization plugin, so that a @Serializable class of a snippet gets its serializer
            compilerOptions("-Xplugin=$serializationPlugin")
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

        /** Compiles and evaluates the [snippet] of this [name], cancelled together with the [callerJob]. */
        suspend fun compileAndEvaluate(
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
         * Compiles the [snippet] of this [name], keeping it in the history of the compiler only if it compiles,
         * with an internal failure of the compiler, also an [Error], like a [StackOverflowError] on a deeply nested expression,
         * reported as the failure of the compilation,
         * except for a [VirtualMachineError] other than [StackOverflowError], which is thrown.
         */
        private suspend fun compile(
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
                        // the reporter renders the exception itself, this stands in for it where the exception is dropped
                        message = e.message?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: e.javaClass.name,
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
                    if (dependencies.failed) dependencies.diagnostics
                    else dependencies.warnings(succeeded = false) + result?.reports.orEmpty()
                ),
                dependencies
            )
        }

        val evaluator = K2ReplEvaluator()

    }

    override suspend fun execute(
        snippet: String
    ): ScriptResult {
        checkNotClosed()
        val callerJob = currentCoroutineContext()[Job]
        val result = onSessionThread(callerJob) {
            // checked on the thread, as the session could be closed before the block was submitted to it,
            // with the disposal of the compiler queued before the block then,
            // while a session closed later disposes the compiler only after this block, queued after it on the thread
            checkNotClosed()
            propertiesFailure ?: run {
                val repl = repl ?: createRepl()
                // a snippet of the session closed while the properties were declared would be evaluated cancelled already,
                // so it is rejected instead, like one executed after closing, while the properties are declared, see close
                checkNotClosed()
                // null only if declaring the properties failed just now, with their failure kept, see createRepl,
                // while a caller without a job, like `suspend fun main`, cannot cancel the evaluation
                repl?.compileAndEvaluate(snippet, "snippet${++snippetCount}.kts", callerJob ?: Job())
                    ?: propertiesFailure!!
            }
        }
        currentCoroutineContext().ensureActive()
        return result
    }

    /**
     * Runs the [block] on the [thread], and returns its result, or throws its exception.
     *
     * The turn of the caller is its [Turn], queued on the [thread] at once, behind the ones of the earlier callers,
     * so the queue of the single thread executor orders the callers, and the next caller runs as soon as the block ends,
     * without waiting for this caller to resume, which might need a free worker of the [resumeExecutor],
     * which the coroutines of the snippets might all block, while the snippet of the next caller might be the one freeing them.
     * No caller is resumed to take its turn, so none is resumed by another one, nested in it, or on its thread.
     *
     * The block is run by `runBlocking` in the context of the caller, without its job and dispatcher,
     * so a thread context element of the caller, like a thread local as its `asContextElement`, is installed on the thread for the block,
     * while the caller cancelled does not cancel the block, and the block stays on the thread also if it suspends,
     * which the compiler and the evaluator, suspending by their API, never do today,
     * and a session closed meanwhile disposes the compiler only after the block, queued after it on the thread,
     * so no code of the session runs on another thread, or against the disposed compiler.
     * If the evaluator ever suspended, and the interrupt of [close] arrived while `runBlocking` is parked,
     * kotlinx.coroutines 1.11 would cancel the blocking coroutine with an [InterruptedException],
     * so [execute] would throw it instead of returning an [ExecutionPhase.EVALUATION] error.
     * The caller waits for the block also when it is cancelled after the block started,
     * as a cancelled compilation would leave the compiled snippet to be evaluated before the next one,
     * while a caller cancelled before, while the block waits for the earlier ones, resumes at once, without its block,
     * like a cancelled waiter of a lock, see [Turn.submit].
     *
     * @throws IllegalStateException if the session is closed, with the thread shut down, before the block is submitted.
     * @throws CancellationException the cancellation of the caller, cancelled before the block started, see [Turn.submit].
     * @throws Throwable whatever else submitting the block to the thread throws, like an [OutOfMemoryError] of creating it,
     * or the continuation interceptor of the caller, as it is.
     */
    private suspend fun <T> onSessionThread(
        callerJob: Job?,
        block: suspend CoroutineScope.() -> T
    ): T {
        val context = currentCoroutineContext().minusKey(Job).minusKey(ContinuationInterceptor) + CoroutineName(name)
        // always suspended, unless thrown, as a caller resumed before suspendCoroutine returns would continue on the current thread
        return suspendCoroutineUninterceptedOrReturn { uninterceptedContinuation ->
            Turn(uninterceptedContinuation) { runBlocking(context, block) }.submit(callerJob)
        }
    }

    /**
     * The runnable queued on the [thread] for a [turn], keeping it only until the [thread] takes it, or the turn is dropped,
     * so a turn cancelled while it waits leaves only this runnable on the queue, holding nothing, see [Turn.onCancelled].
     */
    private class QueuedTurn(
        @Volatile var turn: Turn<*>?
    ) : Runnable {

        override fun run() {
            val turn = turn ?: return
            this.turn = null
            turn.start()
        }

    }

    /**
     * The turn of a caller of [execute], whose [task], the block of [onSessionThread], is queued on the [thread] by a [QueuedTurn],
     * resuming the [caller] exactly once, after the task, or when the caller is cancelled before it, or when it is not queued.
     *
     * A failing dispatcher, or resume executor, cannot resume the caller twice, as the ones taking the resumption, and then throwing,
     * like [Dispatchers.Default] queueing the task before it fails to start a worker for it:
     * the first resumption reaching the caller wins, and the other ones are dropped.
     * A turn cancelled while it waits is dropped by its [QueuedTurn], so nothing of its caller stays on the queue of the [thread].
     *
     * A frame of the stack of the caller, so kotlinx.coroutines restores the thread state of the caller,
     * like a thread context element installed by a `withContext` not dispatching, once its block ends.
     *
     * @param caller the continuation of the caller, intercepted by its interceptor at once, on its thread,
     * so an interceptor failing to intercept it fails the caller in place, before its task is queued.
     */
    private inner class Turn<T>(
        private var caller: Continuation<T>?,
        private var task: (() -> T)?
    ) : Continuation<T>, CoroutineStackFrame {

        private val state = AtomicReference(TurnState.NEW)

        private val resumed = AtomicBoolean()

        /** Whether the task is counted by the [turns] no more. */
        private val finished = AtomicBoolean()

        private val queued = QueuedTurn(this)

        override val context: CoroutineContext = caller!!.context

        private val interceptor = context[ContinuationInterceptor]

        /** The continuation of the first resumption of the caller, see [resumeCaller]. */
        private var intercepted: Continuation<T>? = intercept()

        /** The cancellation of the job of the caller, if it cancelled this turn, see [onCancelled]. */
        private var cancellation: CancellationException? = null

        /**
         * The waiting of the caller for the task, cancelled with the job of the caller, see [watch],
         * resumed once the task starts, or is not queued, or dropped once the caller is cancelled.
         */
        private val waiting = AtomicReference<CancellableContinuation<Unit>?>()

        /** The frame of the [caller], read anew, as it is dropped once resumed, so this turn retains nothing of a resumed caller. */
        override val callerFrame: CoroutineStackFrame?
            get() = caller as? CoroutineStackFrame

        override fun getStackTraceElement(): StackTraceElement? = null

        /** Resumes the [caller] with the [result], unless it is resumed already. */
        override fun resumeWith(result: Result<T>) {
            tryResume(result)
        }

        private fun tryResume(result: Result<T>): Boolean {
            if (!resumed.compareAndSet(false, true)) return false
            val caller = caller!!
            // dropped before resuming, as resuming might not return for long, like a caller blocking its thread
            this.caller = null
            caller.resumeWith(result)
            return true
        }

        /** This turn, intercepted by the [interceptor] of the caller, anew for each attempt, as a dispatched continuation is used once. */
        private fun intercept(): Continuation<T> = interceptor?.interceptContinuation(this) ?: this

        /**
         * Queues the task on the [thread], counted by the [turns] until it ends there,
         * watching the cancellation of the [callerJob] while it waits for the earlier ones, so the caller resumes at once,
         * without its task, like a cancelled waiter of a lock, see [onCancelled].
         * A caller cancelled already, or with its job completed, when it calls [execute] is rejected the same way, at once,
         * unless its turn is free, like the tryLock of a lock taking it, as no other task is counted:
         * then it runs its snippet, cancelled, as a cancellation while its snippet is compiled would leave it to be evaluated anyway.
         *
         * @return [COROUTINE_SUSPENDED] if the task is queued, or taken by the [thread] although it threw, which is logged then.
         * @throws Throwable the failure of the [thread] to take it, as an [IllegalStateException] if it rejected the task,
         * the session closed, or the cancellation of the caller, cancelled before the task is queued, unless it is taken.
         */
        fun submit(callerJob: Job?): Any? {
            val free = turns.getAndIncrement() == 0
            try {
                if (callerJob != null && !(free && !callerJob.isActive)) watch(callerJob)
            } catch (e: Throwable) {
                finish()
                throw e
            }
            // not queued if cancelled already, as it would be skipped
            val failure = if (state.get() == TurnState.CANCELLED) null else try {
                thread.execute(queued)
                null
            } catch (e: Throwable) {
                e
            }
            // a task not taken by the thread is dropped like a cancelled one, as it might be queued nevertheless
            if (state.compareAndSet(TurnState.NEW, if (failure == null) TurnState.QUEUED else TurnState.CANCELLED)) {
                if (failure == null) return COROUTINE_SUSPENDED
                finish()
                stopWatching()
                drop()
                throw if (failure is RejectedExecutionException) IllegalStateException(SESSION_CLOSED_MESSAGE) else failure
            }
            if (state.get() == TurnState.STARTED) {
                failure?.let { e -> logger.warn(e) { "$name: submitting the snippet failed after the thread took it" } }
                return COROUTINE_SUSPENDED
            }
            // cancelled before the task is queued, or while it is submitted, which dropped it from the queued runnable,
            // also when the thread rejects it, as the cancellation of the caller came first,
            // unless the thread failed fatally, which is never swallowed, while another failure is logged,
            // rather than suppressed by the cancellation, shared by the job of the caller
            finish()
            failure?.fatalError?.let { throw it }
            if (failure != null && failure !is RejectedExecutionException) {
                logger.warn(failure) { "$name: submitting the snippet of the cancelled caller failed" }
            }
            throw cancellation!!
        }

        /**
         * Watches the cancellation of the [callerJob], calling [onCancelled] with it, in place, on the cancelling thread,
         * at once if it is cancelled, or completed, already,
         * by a coroutine of the [callerJob], without an interceptor, [waiting] cancellably until [stopWatching],
         * so the job of the caller gets no child, which its `cancelChildren` would cancel, while the caller is active,
         * and which a completed job of the caller would wait for.
         */
        private fun watch(callerJob: Job) {
            suspend {
                try {
                    suspendCancellableCoroutine { waiting.set(it) }
                } catch (e: CancellationException) {
                    // the cancellation of the job, or one telling it completed, see `Job.getCancellationException`
                    onCancelled(e)
                }
            }.startCoroutine(Continuation(callerJob) { it.getOrThrow() })
        }

        /** Stops the [watch] of the job of the caller, so it keeps nothing of this turn. */
        private fun stopWatching() {
            waiting.getAndSet(null)?.resume(Unit)
        }

        /** Stops counting the task by the [turns], once. */
        private fun finish() {
            if (finished.compareAndSet(false, true)) turns.decrementAndGet()
        }

        /** Drops the task, and what refers to the caller, but the [caller] itself, dropped once resumed, see [tryResume]. */
        private fun drop() {
            queued.turn = null
            task = null
            intercepted = null
            waiting.set(null)
        }

        /** Runs the task on the [thread], unless the caller was cancelled before, and resumes the caller with its result. */
        fun start() {
            if (!state.compareAndSet(TurnState.NEW, TurnState.STARTED)
                && !state.compareAndSet(TurnState.QUEUED, TurnState.STARTED)
            ) {
                // finished by the one which cancelled it
                return
            }
            stopWatching()
            val task = task!!
            this.task = null
            val result = runCatching(task)
            finish()
            resumeCaller(result)
        }

        /**
         * Resumes the caller cancelled while its task waits, with the [cancellation] of its job,
         * on the cancelling thread, unless it is the [thread], where it is resumed like after its task, see [resumeCaller],
         * dropping the task, so the turn left on the queue of the [thread] keeps nothing of the caller once it is resumed.
         */
        private fun onCancelled(cancellation: CancellationException) {
            // written before the state, which publishes it
            this.cancellation = cancellation
            val waited = when {
                state.compareAndSet(TurnState.NEW, TurnState.CANCELLED) -> false
                state.compareAndSet(TurnState.QUEUED, TurnState.CANCELLED) -> true
                else -> return
            }
            drop()
            // still submitting otherwise, which throws the cancellation then, see submit
            if (!waited) return
            finish()
            val result = Result.failure<T>(cancellation)
            if (Thread.currentThread() === sessionThread) resumeCaller(result) else resumeOrFail(result)
        }

        /**
         * Resumes the caller with the [result], from the [thread],
         * by its dispatcher, if it does not resume in place, on the [thread], see [execute],
         * or else from the [resumeExecutor], and also if its dispatcher fails to resume it here,
         * where it is resumed again, then without its dispatcher, if that fails as well, see [resumeOrFail].
         * A fatal failure of the dispatcher, see [isFatal], is not retried, but passed to the caller from the [resumeExecutor] at once,
         * unwrapped, like a fatal error of the snippet, so it is never swallowed by a retry succeeding, while the caller is never left unresumed.
         * The caller is resumed in place only if the [resumeExecutor] fails to take it, see [resumeOff], rather than never.
         *
         * The dispatcher is asked whether it dispatches here, on the [thread], as `Dispatchers.Main.immediate` dispatches off the main thread only,
         * and then once again by the dispatched continuation of kotlinx.coroutines, resuming the caller on its dispatcher.
         */
        private fun resumeCaller(result: Result<T>) {
            val failure = try {
                val dispatcher = interceptor as? CoroutineDispatcher
                if (dispatcher != null && dispatcher.isDispatchNeeded(context)) {
                    val continuation = intercepted ?: intercept()
                    intercepted = null
                    continuation.resumeWith(result)
                    return
                }
                null
            } catch (e: Throwable) {
                e
            }
            intercepted = null
            val fatal = failure?.fatalError
            val resume: () -> Unit = if (fatal != null) {
                // thrown if the dispatcher resumed the caller already, and then threw, so it is never swallowed
                { if (!tryResume(Result.failure(fatal))) throw fatal }
            } else {
                { resumeOrFail(result) }
            }
            if (!resumeOff(resume)) resume()
        }

        /**
         * Resumes the caller with the [result] by its interceptor, intercepting it anew,
         * or, if that throws, like a dispatcher of an executor shut down, or one always failing to tell whether it dispatches,
         * without the interceptor, in place, with that failure, as resuming by it again would fail again,
         * while left unresumed, the caller would never return.
         * A fatal error, see [isFatal], is passed unwrapped from the `DispatchException` of kotlinx.coroutines.
         * A failure thrown when the caller is resumed already is thrown, if it is fatal, or thrown by the caller itself, resumed in place, without an interceptor wrapping it,
         * while a failure of an interceptor which resumed the caller and then threw is logged.
         */
        private fun resumeOrFail(result: Result<T>) {
            var continuation: Continuation<T>? = null
            try {
                continuation = intercept()
                continuation.resumeWith(result)
            } catch (e: Throwable) {
                val fatal = e.fatalError
                if (!tryResume(Result.failure(fatal ?: e))) {
                    // also with an interceptor returning the turn it intercepts, as then the caller itself threw
                    if (fatal != null || continuation === this) throw fatal ?: e
                    logger.warn(e) { "$name: resuming the caller failed after it was resumed" }
                }
            }
        }

    }

    /**
     * Runs the [resume] of a caller by the [resumeExecutor].
     *
     * @return `false` if the [resumeExecutor] does not take it, rejecting it, like [Dispatchers.Default] after `Dispatchers.shutdown()`,
     * or failing otherwise, like by an [OutOfMemoryError] of creating a worker, also a fatal one, which is logged then,
     * so the caller is resumed in place instead, rather than never, and only once, also if the [resumeExecutor] queued it, see [Turn].
     */
    private fun resumeOff(
        resume: () -> Unit
    ): Boolean = try {
        resumeExecutor.execute(resume)
        true
    } catch (e: Throwable) {
        if (e !is RejectedExecutionException) {
            logger.warn(e) { "$name: resuming the caller off the thread failed, it is resumed in place" }
        }
        false
    }

    /** @throws IllegalStateException if the session is closed. */
    private fun checkNotClosed() {
        check(!closed.get()) { SESSION_CLOSED_MESSAGE }
    }

    /**
     * Creates the [Repl] with the [properties] declared, and keeps it as the [repl],
     * or disposes it, if they cannot be declared, as nothing can be compiled in it again,
     * keeping only their failure as the [propertiesFailure], and returning `null`.
     *
     * The compiler is disposed also if creating it, or declaring the properties, throws, like a fatal [OutOfMemoryError],
     * so the session is left without either, and the next execution declares them again in another compiler,
     * not seeing the declaration which threw.
     */
    private suspend fun createRepl(): Repl? {
        val disposable = newReplDisposable("$name-repl").also {
            Disposer.register(this.disposable, it)
        }
        val created = try {
            Repl(disposable).also { propertiesFailure = declareProperties(it) }
        } catch (e: Throwable) {
            runCatching { Disposer.dispose(disposable) }.exceptionOrNull()?.takeIf { it !== e }?.let(e::addSuppressed)
            throw e
        }
        if (propertiesFailure != null) {
            // the disposer disposes the whole subtree, and then rethrows an exception thrown by any of its disposables,
            // which would make this first result differ from the kept failure returned by every later execution,
            // so it is only logged
            runCatching { Disposer.dispose(disposable) }.onFailure { e ->
                logger.warn(e) { "$name: disposing the compiler of the failed declaration of the properties failed" }
            }
            return null
        }
        repl = created
        return created
    }

    /**
     * Declares the [properties] in the snippet preceding the ones of the session,
     * returning the failure of the declaration, if any, in the phase in which it failed.
     *
     * A failed declaration is not retried, as its source is the same on every execution.
     */
    private suspend fun declareProperties(repl: Repl): ScriptResult.Error? {
        if (properties.isEmpty()) return null
        val source = properties.joinToString("\n") { it.declaration(id) }
        // with its own job, so a cancellation of the caller cannot fail the declaration for all the next executions,
        // while closing the session cancels it harmlessly, as it neither blocks, nor awaits, and the snippet waiting for it is rejected then, see execute
        val result = repl.compileAndEvaluate(source, PROPERTIES_SNIPPET, Job())
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
        result?.scriptClass?.java?.let { scriptClass ->
            snippetDeclarations.keep(scriptClass, name)
            inlinedFrames.keep(scriptClass)
            propertyAccessors.keep(scriptClass)
        }
        // before reporting it, as the report throws a fatal error, while the declarations of the snippet stay in the history,
        // except for the properties of the session, which the caller cannot declare again, while the compiler is disposed anyway
        val uninitialized = (result as? ResultValue.Error)?.scriptClass?.java?.takeIf { name != PROPERTIES_SNIPPET }?.let {
            uninitializedProperties.keep(it, name)
        } ?: emptyList()
        return reporter.scriptResult(result, reports, compilation.result.reports, compilation.dependencies, uninitialized)
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
        // released after the last evaluation on the thread, which might be the declaration of the properties reading them
        thread.execute {
            // each one released even if an earlier one fails, which is logged, as no caller awaits the release,
            // while a fatal error is thrown, to the uncaught exception handler of the thread
            logger.runOr(Unit, Level.WARN, { "$name: releasing the session failed" }) {
                releaseAll(
                    { ScriptPropertyValues.unregister(id) },
                    { Disposer.dispose(disposable) },
                    dependencyResolver::close
                )
            }
        }
        thread.shutdown()
    }

}

/** The name of the snippet declaring the properties of the session, preceding the ones of the caller. */
private const val PROPERTIES_SNIPPET = "properties.kts"

/** The states of a turn of a caller of [ScriptSession.execute], see [DefaultScriptSession.onSessionThread]. */
private enum class TurnState {
    /** Its task is being submitted to the thread of the session. */
    NEW,
    /** Its task waits on the thread of the session for the ones of the earlier callers. */
    QUEUED,
    /** Its task is run, and its caller is resumed after it. */
    STARTED,
    /** Its caller was cancelled before its task started, or its task was not taken by the thread, so it is skipped. */
    CANCELLED
}

/**
 * This throwable, or its cause, if fatal, see [isFatal],
 * like a fatal failure of a dispatcher wrapped by kotlinx.coroutines in its `DispatchException`.
 */
private val Throwable.fatalError: Throwable?
    get() = takeIf { it.isFatal } ?: cause?.takeIf { it.isFatal }

/**
 * Whether this throwable is thrown by the session instead of being reported:
 * a [VirtualMachineError], like an [OutOfMemoryError], other than a [StackOverflowError], which is reported as a failure of the snippet.
 */
internal val Throwable.isFatal: Boolean
    get() = this is VirtualMachineError && this !is StackOverflowError

/**
 * Runs each of these [releases], even if an earlier one throws,
 * then throws the first fatal failure, see [isFatal], or else the first failure, if any, with the other ones suppressed.
 */
internal fun releaseAll(vararg releases: () -> Unit) {
    val failures = releases.mapNotNull { release -> runCatching(release).exceptionOrNull() }.distinct()
    val thrown = failures.firstOrNull { it.isFatal } ?: failures.firstOrNull() ?: return
    failures.filter { it !== thrown }.forEach(thrown::addSuppressed)
    throw thrown
}

/** The result of the [read], or the [fallback], if it throws, logged by this logger with the [message] at the debug level, see [runOr]. */
internal inline fun <T> KLogger.readOr(
    fallback: T,
    crossinline message: () -> String,
    read: () -> T
): T = runOr(fallback, Level.DEBUG, message, read)

/**
 * The result of the [block], or the [fallback], if it throws, logged by this logger with the [message] at the [level],
 * except for a fatal error, which is thrown, see [isFatal].
 */
internal inline fun <T> KLogger.runOr(
    fallback: T,
    level: Level,
    crossinline message: () -> String,
    block: () -> T
): T = try {
    block()
} catch (e: Throwable) {
    if (e.isFatal) throw e
    at(level) {
        this.message = message()
        cause = e
    }
    fallback
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
