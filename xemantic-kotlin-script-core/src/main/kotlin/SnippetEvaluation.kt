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
 * The evaluation of a snippet, cancelled when the job of its caller is cancelled, or the session of the [sessionJob] is closed.
 *
 * The cancellation is cooperative only: the snippet sees the cancellation of the [job] at its next suspension point,
 * and by `isActive` and `ensureActive()` of the [ScriptScope],
 * while the thread of the session is never interrupted, so a snippet blocked in a call which does not suspend runs until the call returns.
 */
internal class SnippetEvaluation(
    callerJob: Job,
    sessionJob: Job
) {

    /**
     * The job of the evaluation, a child of the job of the caller, so it is cancelled with the caller,
     * while its own cancellation, by closing the session, does not cancel the caller.
     */
    val job = Job(callerJob)

    // completed by closing the session, also when it is closed already, and by the end of the evaluation
    private val sessionWatcher = CoroutineScope(sessionJob + Dispatchers.Unconfined).launch {
        awaitCancellation()
    }

    init {
        sessionWatcher.invokeOnCompletion {
            job.cancel(CancellationException("The session is closed"))
        }
    }

    /** Ends the evaluation. */
    fun end() {
        sessionWatcher.cancel()
        job.cancel()
    }

}
