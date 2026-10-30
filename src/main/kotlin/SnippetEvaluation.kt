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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * The evaluation of a snippet on the current thread, interrupting it when the [job] of the caller is cancelled,
 * also when it already is, so a blocking call of the snippet fails immediately,
 * unless it is [uninterruptible], like waiting in [ScriptScope.await], which the cancellation of the job reaches anyway.
 */
internal class SnippetEvaluation(
    val job: Job
) {

    private val thread = Thread.currentThread()

    private var running = true

    private var uninterruptible = 0

    // resumed, and so completed, by the cancellation of the job, in the cancelling thread
    private val watcher = CoroutineScope(job + Dispatchers.Unconfined).launch {
        awaitCancellation()
    }

    init {
        watcher.invokeOnCompletion {
            interruptIfCancelled()
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
        watcher.cancel()
        Thread.interrupted()
    }

}
