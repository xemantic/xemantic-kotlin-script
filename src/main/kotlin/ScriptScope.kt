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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * The implicit receiver of every snippet of a [ScriptSession].
 *
 * Its [coroutineContext] lives as long as the session,
 * so the coroutines launched by a snippet, like `launch { }`, keep running after the snippet,
 * until they complete, or the session is closed.
 */
public class ScriptScope internal constructor(
    override val coroutineContext: CoroutineContext
) : CoroutineScope {

    /** The evaluation of the snippet on the current thread, see [evaluate]. */
    private val evaluation = ThreadLocal<SnippetEvaluation?>()

    /**
     * Runs the suspending [block] and returns its result, blocking the thread of the snippet until it completes,
     * like `await { client.get(url) }`.
     *
     * The [block] runs on the thread of the snippet,
     * and it is cancelled when the caller executing the snippet is cancelled,
     * or, outside of a snippet, like in a launched coroutine, when the session is closed.
     */
    public fun <T> await(
        block: suspend CoroutineScope.() -> T
    ): T {
        val evaluation = evaluation.get()
        // so the failure of the block, thrown from here, does not cancel the caller as well
        val job = SupervisorJob(evaluation?.job ?: coroutineContext.job)
        try {
            // the event loop of runBlocking keeps the block on the thread of the snippet
            val context = coroutineContext.minusKey(ContinuationInterceptor) + job
            return if (evaluation != null) {
                evaluation.uninterruptible { runBlocking(context, block) }
            } else {
                runBlocking(context, block)
            }
        } finally {
            job.cancel()
        }
    }

    /** Evaluates the snippet [block] on the current thread, cancelled together with the [job] of its caller. */
    internal inline fun <T> evaluate(job: Job, block: () -> T): T {
        val evaluation = SnippetEvaluation(job)
        this.evaluation.set(evaluation)
        try {
            return block()
        } finally {
            this.evaluation.remove()
            evaluation.end()
        }
    }

}
