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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * The evaluation of a snippet on the current thread, cancelled when the job of its caller is cancelled,
 * or the session of the [sessionJob] is closed.
 *
 * The cancellation of the [job] interrupts the thread, also when it is cancelled already, so a blocking call of the snippet fails immediately,
 * unless it is [uninterruptible], like waiting in [ScriptScope.await], which the cancellation of the job reaches anyway.
 */
internal class SnippetEvaluation(
    callerJob: Job,
    sessionJob: Job
) {

    /** The job of the evaluation, a child of the job of the caller, so the cancellation of either does not cancel the other. */
    val job = Job(callerJob)

    private val thread = Thread.currentThread()

    private var running = true

    private var uninterruptible = 0

    // resumed, and so completed, by the cancellation of the job, in the cancelling thread
    private val watcher = CoroutineScope(job + Dispatchers.Unconfined).launch {
        awaitCancellation()
    }

    // completed by closing the session, also when it is closed already, and by the end of the evaluation
    private val sessionWatcher = CoroutineScope(sessionJob + Dispatchers.Unconfined).launch {
        awaitCancellation()
    }

    init {
        watcher.invokeOnCompletion {
            interruptIfCancelled()
        }
        sessionWatcher.invokeOnCompletion {
            job.cancel(CancellationException("The session is closed"))
        }
    }

    private fun interruptIfCancelled() {
        synchronized(this) {
            if (running && uninterruptible == 0 && job.isCancelled) thread.interrupt()
        }
    }

    /**
     * Runs the [block] without interrupting it, like the `runBlocking` of [ScriptScope.await],
     * where the interruption is redundant, as the cancellation of the job reaches its coroutines anyway.
     * The thread is interrupted after it, if the job was cancelled meanwhile.
     */
    inline fun <T> uninterruptible(block: () -> T): T {
        synchronized(this) { uninterruptible++ }
        try {
            return block()
        } finally {
            synchronized(this) { uninterruptible-- }
            interruptIfCancelled()
        }
    }

    /** Ends the evaluation, clearing the interruption, if any, so it does not affect the next snippet. */
    fun end() {
        synchronized(this) {
            running = false
        }
        sessionWatcher.cancel()
        job.cancel()
        watcher.cancel()
        Thread.interrupted()
    }

}
