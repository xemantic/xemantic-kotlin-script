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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext

/**
 * The implicit receiver of every snippet of a [ScriptSession].
 *
 * The body of a snippet is suspending, so a snippet calls a suspend function directly, like `client.get(url)`,
 * in the coroutine of the caller executing it, on the thread of the session, cancelled with the caller, or when the session is closed.
 *
 * Its [coroutineContext] lives as long as the session, with a supervisor job,
 * so the coroutines launched by a snippet, like `launch { }`, keep running after the snippet,
 * until they complete, or the session is closed, and the failure of one of them fails neither the snippet, nor the session.
 * The [isActive] and [ensureActive] of the scope tell about the snippet being evaluated, though,
 * like the ones of a coroutine, while `currentCoroutineContext()` is the context of the snippet.
 */
public class ScriptScope internal constructor(
    override val coroutineContext: CoroutineContext
) : CoroutineScope {

    /** The evaluation of the snippet of the session, at most one at a time, see [evaluate]. */
    @Volatile
    private var evaluation: SnippetEvaluation? = null

    /** The job of the snippet being evaluated, or of the session, if none is. */
    private val job: Job get() = evaluation?.job ?: coroutineContext.job

    /**
     * Whether the snippet being evaluated is active, `false` once its caller is cancelled, or the session is closed,
     * like `isActive` of a coroutine, so a snippet computing without suspending can stop then, like `while (isActive) step()`,
     * also read by a function of a snippet called while one is evaluated,
     * and, when none is, like by a function called by a coroutine launched by a snippet, whether the session is not closed.
     * Within `launch { }`, or another coroutine builder, `isActive` is the one of that coroutine.
     */
    public val isActive: Boolean get() = job.isActive

    /**
     * Throws the [kotlinx.coroutines.CancellationException] of the snippet being evaluated, if it is cancelled, see [isActive],
     * like `ensureActive()` of a coroutine.
     */
    public fun ensureActive() {
        job.ensureActive()
    }

    /**
     * Evaluates the snippet [block], given the job of the evaluation,
     * cancelled together with the [callerJob], or when the session is closed.
     */
    internal inline fun <T> evaluate(callerJob: Job, block: (Job) -> T): T {
        val evaluation = SnippetEvaluation(callerJob, coroutineContext.job)
        this.evaluation = evaluation
        try {
            return block(evaluation.job)
        } finally {
            this.evaluation = null
            evaluation.end()
        }
    }

}
