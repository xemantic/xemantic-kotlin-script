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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.reflect.KType
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultWithDiagnostics

/**
 * The least recently used compilation results of scripts,
 * where the concurrent compilations of the same script are done only once.
 */
internal class ScriptCompilationCache(
    private val maxSize: Int
) {

    init {
        require(maxSize > 0) { "maxSize must be positive, but was: $maxSize" }
    }

    /**
     * Everything the compilation depends on, the values of the dependencies are provided only to the evaluation.
     */
    data class Key(
        val script: String,
        val dependencies: List<Pair<String, KType>>
    )

    private val entries = object : LinkedHashMap<
        Key, CompletableDeferred<ResultWithDiagnostics<CompiledScript>>
    >(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Key, CompletableDeferred<ResultWithDiagnostics<CompiledScript>>>
        ) = size > maxSize
    }

    suspend fun getOrCompile(
        key: Key,
        compile: suspend () -> ResultWithDiagnostics<CompiledScript>
    ): ResultWithDiagnostics<CompiledScript> {
        while (true) {
            var owner = false
            val entry = synchronized(entries) {
                entries.getOrPut(key) {
                    owner = true
                    CompletableDeferred()
                }
            }
            if (!owner) {
                try {
                    return entry.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    // the compiling caller was cancelled, compile again
                    continue
                }
            }
            try {
                val result = compile()
                if (result is ResultWithDiagnostics.Failure) remove(key, entry)
                entry.complete(result)
                return result
            } catch (e: Throwable) {
                remove(key, entry)
                entry.completeExceptionally(e)
                throw e
            }
        }
    }

    private fun remove(
        key: Key,
        entry: CompletableDeferred<ResultWithDiagnostics<CompiledScript>>
    ) {
        synchronized(entries) {
            entries.remove(key, entry)
        }
    }

}
