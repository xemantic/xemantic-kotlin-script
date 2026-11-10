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

import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.jvm.KJvmEvaluatedSnippet
import kotlin.script.experimental.util.LinkedSnippet

/**
 * The snippets compiled in a session, with their names, until they are taken once evaluated.
 *
 * The evaluator evaluates every compiled snippet not evaluated yet before the given one,
 * so a snippet left unevaluated by its own call, like when the evaluator failed before reaching it,
 * is evaluated together with a later one, which is the only one its result returns.
 * Accessed on the thread of the session only.
 */
internal class EvaluatedSnippets {

    /** The compiled snippets not taken yet, in the order of the compilation, which is the one of the evaluation. */
    private val compiled = ArrayDeque<Pair<CompiledScript, String>>()

    /** The last evaluated snippet taken, `null` before the first one. */
    private var taken: LinkedSnippet<KJvmEvaluatedSnippet>? = null

    /** Keeps the name of the [snippet] compiled successfully, to be evaluated after the ones compiled before. */
    fun compiled(snippet: CompiledScript, name: String) {
        compiled += snippet to name
    }

    /**
     * The snippets evaluated up to the [last] one of the evaluator, which were not taken yet, with their names,
     * in the order of the evaluation, also a snippet which failed in the evaluation.
     */
    fun takeEvaluated(
        last: LinkedSnippet<KJvmEvaluatedSnippet>?
    ): List<Pair<KJvmEvaluatedSnippet, String>> {
        if (last == null) return emptyList()
        val evaluated = generateSequence(last) { it.previous }
            .takeWhile { it !== taken }
            .map { it.get() }
            .toList()
            .asReversed()
        taken = last
        return evaluated.mapNotNull { snippet ->
            val index = compiled.indexOfFirst { it.first === snippet.compiledSnippet }
            if (index < 0) return@mapNotNull null
            val name = compiled[index].second
            // with the earlier ones, if any, which cannot be evaluated anymore, as the evaluator evaluates the snippets in order
            repeat(index + 1) { compiled.removeFirst() }
            snippet to name
        }
    }

}
