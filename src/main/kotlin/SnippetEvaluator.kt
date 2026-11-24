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

import kotlinx.coroutines.Job
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.coroutines.jvm.internal.CoroutineStackFrame
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledSnippet
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.asErrorDiagnostics
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.compilationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.refineBeforeEvaluation
import kotlin.script.experimental.api.valueOr
import kotlin.script.experimental.api.with
import kotlin.script.experimental.jvm.KJvmEvaluatedSnippet
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.lastSnippetClassLoader
import kotlin.script.experimental.util.LinkedSnippet
import kotlin.script.experimental.util.LinkedSnippetImpl

/** The function of a snippet class evaluating the body of the snippet. */
private const val EVAL_FUNCTION = "\$\$eval"

/**
 * Evaluates the snippets compiled with the `xemantic-kotlin-script-compiler-plugin`, whose bodies are suspending,
 * like the `K2ReplEvaluator`, which calls the `$$eval` function of a snippet without a continuation,
 * also every compiled snippet not evaluated yet before the given one, like the generated one declaring the properties of the session.
 *
 * It relies on the layout of a snippet class of the K2 REPL compiler, an internal detail re-checked after every Kotlin upgrade:
 * its `INSTANCE`, its `$$eval` function taking the implicit receivers, and its result field.
 */
internal class SnippetEvaluator {

    private var lastEvaluatedSnippet: LinkedSnippetImpl<KJvmEvaluatedSnippet>? = null

    /**
     * Evaluates the [snippet], and the compiled ones not evaluated before it,
     * suspended while it suspends, in the context of the caller, but with the [job] of the evaluation,
     * so its cancellation reaches the snippet at its next suspension point.
     */
    suspend fun eval(
        snippet: LinkedSnippet<out CompiledSnippet>,
        configuration: ScriptEvaluationConfiguration,
        job: Job
    ): ResultWithDiagnostics<LinkedSnippet<KJvmEvaluatedSnippet>> {
        val pending = ArrayDeque<LinkedSnippet<out CompiledSnippet>>()
        var current: LinkedSnippet<out CompiledSnippet>? = snippet
        val lastEvaluated = lastEvaluatedSnippet?.get()?.compiledSnippet
        while (current != null && current.get() !== lastEvaluated) {
            pending.addFirst(current)
            current = current.previous
        }
        if (pending.isEmpty()) {
            return lastEvaluatedSnippet?.asSuccess() ?: ResultWithDiagnostics.Failure("No snippets to evaluate".asErrorDiagnostics())
        }
        for (pendingSnippet in pending) {
            val compiledSnippet = pendingSnippet.get() as KJvmCompiledScript
            val currentConfiguration = lastEvaluatedSnippet?.let { last ->
                configuration.with {
                    jvm {
                        (last.get().result.scriptClass?.java ?: last.get().result.scriptInstance?.javaClass)?.let {
                            lastSnippetClassLoader(it.classLoader)
                        }
                    }
                }
            } ?: configuration
            val refinedConfiguration = currentConfiguration.with {
                compilationConfiguration(compiledSnippet.compilationConfiguration)
            }.refineBeforeEvaluation(compiledSnippet).valueOr {
                return ResultWithDiagnostics.Failure(it.reports)
            }
            val snippetClass = when (val classResult = compiledSnippet.getClass(refinedConfiguration)) {
                is ResultWithDiagnostics.Failure -> return classResult
                is ResultWithDiagnostics.Success -> classResult.value
            }
            val result = evalSnippet(compiledSnippet, snippetClass, refinedConfiguration, job)
            lastEvaluatedSnippet = LinkedSnippetImpl(KJvmEvaluatedSnippet(compiledSnippet, refinedConfiguration, result), lastEvaluatedSnippet)
        }
        return lastEvaluatedSnippet!!.asSuccess()
    }

    private suspend fun evalSnippet(
        compiledSnippet: KJvmCompiledScript,
        snippetClass: KClass<*>,
        configuration: ScriptEvaluationConfiguration,
        job: Job
    ): ResultValue {
        val eval = snippetClass.java.methods.single { it.name == EVAL_FUNCTION }
        val snippet = snippetClass.java.getField("INSTANCE").get(null)
        val receivers = configuration[ScriptEvaluationConfiguration.implicitReceivers].orEmpty()
        return try {
            if (eval.parameterTypes.lastOrNull() == Continuation::class.java) {
                invokeSuspending(eval, snippet, receivers, job)
            } else {
                // a snippet compiled without the plugin, not by the session
                invoke(eval, snippet, receivers)
            }
            compiledSnippet.resultField?.let { (name, type) ->
                // the field might not be emitted, like by the K2ReplEvaluator
                val field = runCatching { snippetClass.java.getDeclaredField(name) }.getOrNull()
                when {
                    field == null || type.typeName == "kotlin.Unit" -> ResultValue.Unit(snippetClass, snippet)
                    else -> ResultValue.Value(name, field.get(snippet), type.typeName, snippetClass, snippet)
                }
            } ?: ResultValue.Unit(snippetClass, snippet)
        } catch (e: Throwable) {
            // also a fatal error, which the result of the snippet throws, see scriptResult
            ResultValue.Error(e, e, snippetClass)
        }
    }

    /** Calls the suspending [eval] function of the [snippet], with a continuation of the [job], resumed by the snippet when it completes. */
    private suspend fun invokeSuspending(
        eval: Method,
        snippet: Any,
        receivers: List<Any?>,
        job: Job
    ): Any? = suspendCoroutineUninterceptedOrReturn { caller ->
        invoke(eval, snippet, receivers + SnippetContinuation(caller, job))
    }

    /** Calls the [eval] function of the [snippet], throwing what the snippet throws, not wrapped. */
    private fun invoke(eval: Method, snippet: Any, arguments: List<Any?>): Any? = try {
        eval.invoke(snippet, *arguments.toTypedArray())
    } catch (e: InvocationTargetException) {
        throw e.targetException
    }

}

/**
 * The completion of the `$$eval` function of a snippet, resuming the [caller], in its context, but with the [job] of the evaluation,
 * so the snippet sees the cancellation of the evaluation, while it suspends on the dispatcher of the caller, the event loop of the thread of the session.
 * It is a [CoroutineStackFrame], so kotlinx.coroutines walks through it to the frames of the caller,
 * not leaking a thread context element of an undispatched `withContext`, like `Turn.callerFrame`.
 */
private class SnippetContinuation(
    private val caller: Continuation<Any?>,
    job: Job
) : Continuation<Any?>, CoroutineStackFrame {

    override val context: CoroutineContext = caller.context + job

    override fun resumeWith(result: Result<Any?>) {
        caller.resumeWith(result)
    }

    override val callerFrame: CoroutineStackFrame? get() = caller as? CoroutineStackFrame

    override fun getStackTraceElement(): StackTraceElement? = null

}
