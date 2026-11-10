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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
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
     * A caller with a dispatcher in its context resumes where its dispatcher resumes it,
     * like the main thread for `Dispatchers.Main.immediate`, which does not dispatch on the main thread only,
     * while a caller which would resume in place, on the thread of the session, blocking the next snippet with whatever blocks the caller then,
     * or, rejected by the session closed while it waited for the snippet of another caller, on the thread of that caller,
     * is resumed from [Dispatchers.Default] instead, and runs there:
     * a caller whose dispatcher does not dispatch, like [Dispatchers.Unconfined],
     * and one without a dispatcher, like `suspend fun main` as well, which has no thread to return to,
     * while a caller with a continuation interceptor which is not a [CoroutineDispatcher] is taken over there by its interceptor,
     * so one posting it to its own thread resumes on that thread.
     * The same goes for a caller whose dispatcher throws when asked on the thread of the session whether to dispatch,
     * while one whose dispatcher always throws then cannot be resumed at all, as it throws again on [Dispatchers.Default],
     * leaving the session waiting for it forever, as the next snippets wait for the caller to end its turn.
     * A caller waiting for the snippet of another caller, if it would resume in place, is resumed on the thread of that caller,
     * which submits its snippet to the thread of the session,
     * and if the caller started on that same thread, like a caller on [Dispatchers.Unconfined] started by the other caller,
     * it is also rejected there, by the session closed meanwhile, as on its own thread.
     * Such a caller needs a free worker of [Dispatchers.Default] to resume,
     * which the coroutines launched by the snippets share by default, see [ScriptScope],
     * so a snippet blocking all its workers, like `repeat(n) { launch { latch.await() } }`, keeps such a caller waiting until one is free,
     * and such a caller should not block its thread for long.
     * A dispatcher which reports a dispatch as needed, but runs the task on the dispatching thread,
     * like `Executor(Runnable::run).asCoroutineDispatcher()`, cannot be told apart, so it resumes its caller on the thread of the session.
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

/** The names of the members of the [ScriptScope], which the properties would shadow. */
private val reservedNames = setOf("coroutineContext", "await")

/**
 * The [ScriptSession], with the thread of its snippets run by the single thread [executor] created with the given thread factory,
 * and a caller which would resume in place, on that thread, resumed from the [resumeExecutor] instead, see [ScriptSession.execute].
 */
internal class DefaultScriptSession(
    private val properties: List<ScriptProperty<*>>,
    dispatcher: CoroutineDispatcher,
    private val resumeExecutor: Executor = Dispatchers.Default.asExecutor(),
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
        Thread(null, runnable, name, SESSION_THREAD_STACK_SIZE).apply { isDaemon = true }
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

    internal val dependencyResolver = SnippetDependencyResolver(classLoader)

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
                    dependencies.warnings + dependencies.errors.ifEmpty { result?.reports.orEmpty() }
                ),
                dependencies
            )
        }

        val evaluator = K2ReplEvaluator()

    }

    override suspend fun execute(
        snippet: String
    ): ScriptResult {
        val callerThread = Thread.currentThread()
        checkNotClosed()
        // a caller waiting on the mutex, which would resume in place, is resumed by the unlocking caller, on its thread,
        // and submits its snippet from there, as leaving that thread first would need a free worker of Dispatchers.Default
        // before the snippet, which might be the one freeing the workers, blocked by the coroutines of the earlier snippets
        return mutex.withLock {
            // a caller without a job, like `suspend fun main`, cannot cancel the evaluation
            val callerJob = currentCoroutineContext()[Job] ?: Job()
            val result = onSessionThread(callerThread) {
                // checked on the thread, as the session could be closed before the block was submitted to it,
                // with the disposal of the compiler queued before the block then,
                // while a session closed later disposes the compiler only after this block, queued after it on the thread
                checkNotClosed()
                propertiesFailure ?: run {
                    val repl = repl ?: createRepl()
                    // a snippet of the session closed while the properties were declared would be evaluated cancelled already,
                    // so it is rejected instead, like one executed after closing, while the properties are declared, see close
                    checkNotClosed()
                    // null only if declaring the properties failed just now, with their failure kept, see createRepl
                    repl?.compileAndEvaluate(snippet, "snippet${++snippetCount}.kts", callerJob) ?: propertiesFailure!!
                }
            }
            currentCoroutineContext().ensureActive()
            result
        }
    }

    /**
     * Runs the [block] on the [thread], and returns its result, or throws its exception.
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
     * The caller which would resume in place, on the thread, is resumed from the [resumeExecutor] instead, see [resumeCaller],
     * and so is the caller rejected by the session closed with the thread shut down,
     * if it would resume in place on the current thread, other than the [callerThread] it called [execute] on,
     * like the thread of the caller unlocking the mutex it waited on.
     * The caller waits for the block also when it is cancelled,
     * as a cancelled compilation would leave the compiled snippet to be evaluated before the next one.
     *
     * @throws IllegalStateException if the session is closed, with the thread shut down, before the block is submitted.
     */
    private suspend fun <T> onSessionThread(
        callerThread: Thread,
        block: suspend CoroutineScope.() -> T
    ): T {
        val callerContext = currentCoroutineContext()
        val context = callerContext.minusKey(Job).minusKey(ContinuationInterceptor) + CoroutineName(name)
        // always suspended, unless thrown, as a caller resumed before suspendCoroutine returns would continue on the current thread
        return suspendCoroutineUninterceptedOrReturn { uninterceptedContinuation ->
            val continuation = uninterceptedContinuation.intercepted()
            try {
                thread.execute {
                    resumeCaller(continuation, callerContext, runCatching { runBlocking(context, block) })
                }
                COROUTINE_SUSPENDED
            } catch (_: RejectedExecutionException) {
                val closed = IllegalStateException(SESSION_CLOSED_MESSAGE)
                if (Thread.currentThread() !== callerThread && resumeOffIfInPlace(continuation, callerContext, Result.failure(closed))) {
                    COROUTINE_SUSPENDED
                } else {
                    throw closed
                }
            }
        }
    }

    /**
     * Resumes the [continuation] of the caller of the [callerContext] with the [result], from the [thread],
     * by the [resumeExecutor] if the caller would resume in place, see [resumeOffIfInPlace], or in place otherwise.
     */
    private fun <T> resumeCaller(
        continuation: Continuation<T>,
        callerContext: CoroutineContext,
        result: Result<T>
    ) {
        if (!resumeOffIfInPlace(continuation, callerContext, result)) {
            continuation.resumeWith(result)
        }
    }

    /**
     * Resumes the [continuation] of the caller of the [callerContext] with the [result] by the [resumeExecutor],
     * if the caller would resume in place, on the current thread, see [execute],
     * also if its dispatcher fails to tell, unless with a fatal error, see [isFatal],
     * as resuming the caller asks it again, then on another thread.
     * It is asked on the current thread, as `Dispatchers.Main.immediate` dispatches off the main thread only.
     *
     * @return `false` if the caller would not resume in place,
     * or if the [resumeExecutor] rejects it, like [Dispatchers.Default] after `Dispatchers.shutdown()`,
     * so it is resumed in place rather than never, holding the mutex.
     */
    private fun <T> resumeOffIfInPlace(
        continuation: Continuation<T>,
        callerContext: CoroutineContext,
        result: Result<T>
    ): Boolean {
        val dispatcher = callerContext[ContinuationInterceptor] as? CoroutineDispatcher
        val inPlace = dispatcher == null || try {
            !dispatcher.isDispatchNeeded(callerContext)
        } catch (e: Throwable) {
            if (e.isFatal) throw e
            true
        }
        if (!inPlace) return false
        return try {
            resumeExecutor.execute { continuation.resumeWith(result) }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
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
        val disposable = Disposer.newDisposable("$name-repl").also {
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
            // so it is only logged; not tested, as no input makes a disposable of the compiler throw deterministically
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
        // released after the last evaluation on the thread, which might be the declaration of the properties reading them,
        // and before the disposal, so its failure cannot keep them
        thread.execute {
            // each one released even if an earlier one fails, which is logged, as no caller awaits the release,
            // while a fatal error is thrown, to the uncaught exception handler of the thread
            try {
                releaseAll(
                    { ScriptPropertyValues.unregister(id) },
                    { Disposer.dispose(disposable) },
                    dependencyResolver::close
                )
            } catch (e: Throwable) {
                if (e.isFatal) throw e
                logger.warn(e) { "$name: releasing the session failed" }
            }
        }
        thread.shutdown()
    }

}

/** The name of the snippet declaring the properties of the session, preceding the ones of the caller. */
private const val PROPERTIES_SNIPPET = "properties.kts"

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

/**
 * The result of the [read], or the [fallback], if it throws, logged by this logger with the [message] at the debug level,
 * except for a fatal error, which is thrown, see [isFatal].
 */
internal inline fun <T> KLogger.readOr(
    fallback: T,
    crossinline message: () -> String,
    read: () -> T
): T = try {
    read()
} catch (e: Throwable) {
    if (e.isFatal) throw e
    debug(e) { message() }
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
