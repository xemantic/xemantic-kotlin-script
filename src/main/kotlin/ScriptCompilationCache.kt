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
import kotlin.reflect.KType

/**
 * The least recently used compilations of scripts,
 * where the concurrent compilations of the same script are done only once.
 *
 * @param isReusable whether the compilation is kept, e.g. not the failed one.
 */
internal class ScriptCompilationCache<T : Any>(
    private val maxSize: Int,
    private val isReusable: (T) -> Boolean
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

    /**
     * The compilations, completed with `null` when the compiling caller was cancelled,
     * in the order of access, while the ones in progress are never evicted,
     * so there might be more of them than the [maxSize].
     */
    private val entries = LinkedHashMap<Key, CompletableDeferred<T?>>(16, 0.75f, true)

    /**
     * @param compile throws [CancellationException] only when its caller is cancelled,
     *   as the waiting callers would be cancelled by it as well.
     */
    suspend fun getOrCompile(
        key: Key,
        compile: suspend () -> T
    ): T {
        while (true) {
            var owner = false
            val entry = synchronized(entries) {
                entries.getOrPut(key) {
                    owner = true
                    CompletableDeferred()
                }.also { evict() }
            }
            if (!owner) {
                // the compiling caller was cancelled, compile again
                return entry.await() ?: continue
            }
            try {
                val result = compile()
                synchronized(entries) {
                    if (!isReusable(result)) entries.remove(key, entry)
                    entry.complete(result)
                    evict()
                }
                return result
            } catch (e: Throwable) {
                remove(key, entry)
                // unlike the cancellation of the caller, the failure of the compilation is shared
                if (e is CancellationException) entry.complete(null) else entry.completeExceptionally(e)
                throw e
            }
        }
    }

    /** Evicts the least recently used compilations, which are completed, while holding the lock of the [entries]. */
    private fun evict() {
        val iterator = entries.values.iterator()
        while (entries.size > maxSize && iterator.hasNext()) {
            if (iterator.next().isCompleted) iterator.remove()
        }
    }

    private fun remove(
        key: Key,
        entry: CompletableDeferred<T?>
    ) {
        synchronized(entries) {
            entries.remove(key, entry)
        }
    }

}
