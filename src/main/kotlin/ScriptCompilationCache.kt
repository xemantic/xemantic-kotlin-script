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
    /** Completes the [CompletableDeferred] of the compilation with its result, replaced only by the tests of resuming the waiting callers. */
    private val resume: (CompletableDeferred<T?>, T) -> Unit = { entry, result -> entry.complete(result) },
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

    /** The compilation of a script, shared with the callers waiting for it. */
    private class Entry<T : Any> {

        /** Completed with `null` when the compiling caller was cancelled, or interrupted, or failed fatally. */
        val result = CompletableDeferred<T?>()

        /**
         * Set while holding the lock of the [entries], once the compilation is done,
         * before the waiting callers are resumed outside the lock, so it might be evicted in the meantime.
         */
        var isCompiled = false

    }

    /**
     * The compilations in the order of access, while the ones in progress are never evicted,
     * so there might be more of them than the [maxSize].
     */
    private val entries = LinkedHashMap<Key, Entry<T>>(16, 0.75f, true)

    /**
     * @param compile throws [CancellationException] only when its caller is cancelled,
     *   as the waiting callers would be cancelled by it as well,
     *   and [InterruptedException] only when the thread of its caller is interrupted,
     *   after both of which, as well as after a fatal error, see [shareFailure], the waiting callers compile again,
     *   while any other failure is shared with them.
     */
    suspend fun getOrCompile(
        key: Key,
        compile: suspend () -> T
    ): T {
        while (true) {
            var owner = false
            val entry = synchronized(entries) {
                // evicting only once the compilation is reusable, so the failing ones do not evict the others
                entries.getOrPut(key) {
                    owner = true
                    Entry()
                }
            }
            if (!owner) {
                // the compiling caller was cancelled, or interrupted, or failed fatally, compile again
                return entry.result.await() ?: continue
            }
            val result = try {
                compile()
            } catch (e: Throwable) {
                synchronized(entries) {
                    entries.remove(key, entry)
                }
                entry.result.shareFailure(e)
                throw e
            }
            try {
                synchronized(entries) {
                    entry.isCompiled = true
                    if (isReusable(result)) evict() else entries.remove(key, entry)
                }
            } catch (e: Throwable) {
                // e.g. a fatal error, which is not the failure of the compilation, so the waiting callers compile again
                synchronized(entries) {
                    entries.remove(key, entry)
                }
                entry.result.complete(null)
                throw e
            }
            // outside the lock, as the waiting callers might be resumed in place,
            // and outside the try, so that the failure of the one resumed in place, e.g. a fatal error, does not drop the cached compilation
            resume(entry.result, result)
            return result
        }
    }

    /** Evicts the least recently used compilations, which are done, while holding the lock of the [entries]. */
    private fun evict() {
        val iterator = entries.values.iterator()
        while (entries.size > maxSize && iterator.hasNext()) {
            if (iterator.next().isCompiled) iterator.remove()
        }
    }

}
