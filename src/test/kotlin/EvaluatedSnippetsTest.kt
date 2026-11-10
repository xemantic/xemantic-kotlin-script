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

import com.xemantic.kotlin.test.assert
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.jvm.KJvmEvaluatedSnippet
import kotlin.script.experimental.util.LinkedSnippetImpl
import kotlin.test.Test

class EvaluatedSnippetsTest {

    /** A compiled snippet, which is never loaded, as only its identity matters. */
    private class Compiled : CompiledScript {
        override val compilationConfiguration = ScriptCompilationConfiguration()
        override suspend fun getClass(
            scriptEvaluationConfiguration: ScriptEvaluationConfiguration?
        ): ResultWithDiagnostics<KClass<*>> = error("not loaded")
    }

    /** The node of the evaluator's chain of the evaluated snippets, linked to the [previous] one. */
    private fun evaluated(
        compiled: Compiled,
        previous: LinkedSnippetImpl<KJvmEvaluatedSnippet>?
    ) = LinkedSnippetImpl(
        KJvmEvaluatedSnippet(compiled, ScriptEvaluationConfiguration(), ResultValue.NotEvaluated),
        previous
    )

    private fun List<Pair<KJvmEvaluatedSnippet, String>>.names() = map { it.second }

    @Test
    fun `should take snippet evaluated in its own call`() {
        // given
        val snippets = EvaluatedSnippets()
        val compiled = Compiled()
        snippets.compiled(compiled, "snippet1.kts")

        // when
        val taken = snippets.takeEvaluated(evaluated(compiled, previous = null))

        // then
        assert(taken.names() == listOf("snippet1.kts"))
        assert(taken.single().first.compiledSnippet === compiled)
    }

    @Test
    fun `should take earlier snippet evaluated together with later one`() {
        // given
        val snippets = EvaluatedSnippets()
        val first = Compiled()
        val second = Compiled()
        snippets.compiled(first, "snippet1.kts")
        snippets.compiled(second, "snippet2.kts")

        // when
        val taken = snippets.takeEvaluated(evaluated(second, previous = evaluated(first, previous = null)))

        // then
        assert(taken.names() == listOf("snippet1.kts", "snippet2.kts"))
        assert(taken.map { it.first.compiledSnippet } == listOf(first, second))
    }

    @Test
    fun `should take each evaluated snippet only once`() {
        // given
        val snippets = EvaluatedSnippets()
        val first = Compiled()
        val second = Compiled()
        snippets.compiled(first, "snippet1.kts")
        val firstEvaluated = evaluated(first, previous = null)
        snippets.takeEvaluated(firstEvaluated)
        snippets.compiled(second, "snippet2.kts")

        // when
        val taken = snippets.takeEvaluated(evaluated(second, previous = firstEvaluated))

        // then
        assert(taken.names() == listOf("snippet2.kts"))
    }

    @Test
    fun `should take nothing when no snippet was evaluated since the last one taken`() {
        // given
        val snippets = EvaluatedSnippets()
        val first = Compiled()
        snippets.compiled(first, "snippet1.kts")
        val firstEvaluated = evaluated(first, previous = null)
        snippets.takeEvaluated(firstEvaluated)
        // compiled, but not evaluated, like when the evaluator fails before reaching it
        snippets.compiled(Compiled(), "snippet2.kts")

        // when
        val taken = snippets.takeEvaluated(firstEvaluated)

        // then
        assert(taken.isEmpty())
    }

    @Test
    fun `should take nothing before the first evaluation`() {
        // given
        val snippets = EvaluatedSnippets()
        snippets.compiled(Compiled(), "snippet1.kts")

        // when
        val taken = snippets.takeEvaluated(null)

        // then
        assert(taken.isEmpty())
    }

    @Test
    fun `should take snippet compiled but left unevaluated once evaluated together with later one`() {
        // given
        val snippets = EvaluatedSnippets()
        val first = Compiled()
        val second = Compiled()
        val third = Compiled()
        snippets.compiled(first, "snippet1.kts")
        val firstEvaluated = evaluated(first, previous = null)
        snippets.takeEvaluated(firstEvaluated)
        snippets.compiled(second, "snippet2.kts")
        // the evaluation of the second snippet fails before reaching it
        snippets.takeEvaluated(firstEvaluated)
        snippets.compiled(third, "snippet3.kts")

        // when
        val taken = snippets.takeEvaluated(evaluated(third, previous = evaluated(second, previous = firstEvaluated)))

        // then
        assert(taken.names() == listOf("snippet2.kts", "snippet3.kts"))
    }

}
