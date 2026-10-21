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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test

class ScriptSourceTest {

    @Test
    fun `should move long chain of functions referred to by declaration which cannot be local`() {
        // given
        val count = 2000
        val script = buildString {
            appendLine("enum class Start { A; fun first() = f0() }")
            (0 until count - 1).forEach { appendLine("fun f$it() = f${it + 1}()") }
            appendLine("fun f${count - 1}() = 42")
            append("Start.A.first()")
        }

        // when
        val source = ScriptSource(script)

        // then
        assert(source.text.substringAfter(SCRIPT_BLOCK_START).removeSuffix(SCRIPT_BLOCK_END).trim() == "Start.A.first()")
    }

    @Test
    fun `should move many functions referred to before their declaration among many accessing local property`() {
        // given
        val count = 50_000
        val script = buildString {
            appendLine("val x = 1")
            (0 until count).forEach { appendLine("b$it()") }
            (0 until count).forEach { appendLine("fun a$it() = x") }
            (0 until count).forEach { appendLine("fun b$it() = $it") }
        }

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("fun b${count - 1}() = ${count - 1}" in moved && "fun a0() = x" in body)
    }

    @Test
    fun `should move function reading only constants together with them when called by declaration which cannot be local`() {
        // given
        val script = """
            val RATE = 0.2
            fun tax(x: Double) = x * RATE
            sealed class Item { abstract val price: Double; fun total() = price + tax(price) }
            class Book(override val price: Double) : Item()
            Book(10.0).total()
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("val RATE = 0.2" in moved && "fun tax(x: Double) = x * RATE" in moved)
        assert(body.removeSuffix(SCRIPT_BLOCK_END).trim() == "Book(10.0).total()")
    }

    @Test
    fun `should not move function reading property which is not constant`() {
        // given
        val scripts = listOf(
            "var RATE = 0.2\nfun tax(x: Double) = x * RATE\nsealed class Item { fun total() = tax(1.0) }\nRATE = 0.5",
            "val RATE = 0.2\nval BASE = RATE * 2\nfun tax(x: Double) = x * RATE + BASE\nsealed class Item { fun total() = tax(1.0) }\n1"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("fun tax(x: Double)" !in moved && "fun tax(x: Double)" in body)
        }
    }

    @Test
    fun `should not move constant of type of local class together with function reading it`() {
        // given
        val script = """
            class Foo
            val x: Foo? = null
            val s = describe()
            fun describe() = "d" + x
            s
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("class Foo" in body && "val x: Foo? = null" in body && "fun describe()" in body)
        assert("class Foo" !in moved && "val x: Foo? = null" !in moved && "fun describe()" !in moved)
    }

    @Test
    fun `should move constant of type of local class moved together with function reading it`() {
        // given
        val scripts = listOf(
            "class Foo\nval x: Foo? = null\nsealed interface S\nclass T : S { fun f() = x; fun g() = Foo() }\nT().f()",
            "data class Foo(val a: Int)\nval x: Foo? = null\nsealed class S { fun g() = \"g\" + x + h() }\nclass T : S()\nfun h() = Foo(1)\nT().g()"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("class Foo" in moved && "val x: Foo? = null" in moved && "val x" !in body)
        }
    }

    @Test
    fun `should not move function reading constant called before its declaration only where receiver might resolve it`() {
        // given
        val scripts = listOf(
            "val k = \"!\"\nval s = buildString { append(\"x\") }\nfun append(t: String) = t + k\nappend(s)",
            "val k = 10\nval r = with(listOf(1, 2)) { sum() }\nfun sum() = k\nr",
            "val k = \"!\"\nval o = object { fun f() = describe(1) }\nfun describe(i: Int) = \"n${'$'}i${'$'}k\"\no.f()",
            "val k = 2\nclass Box(val n: Int)\nfun Box.twice() = scale(n)\nfun scale(n: Int) = n * k\nBox(1).twice()"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("val k" !in moved && "val k" in body)
        }
    }

    @Test
    fun `should give distinct location ids to scripts of the same hash code`() {
        // given
        val scripts = listOf("\"Aa\"", "\"BB\"")

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        assert(sources[0].text.hashCode() == sources[1].text.hashCode())
        assert(sources[0].locationId != sources[1].locationId)
    }

    @Test
    fun `should parse scripts concurrently`() = runTest {
        // given
        val scripts = (0 until 200).map { "fun f$it() = E$it.A\nenum class E$it { A }\nf$it()" }

        // when
        val texts = withContext(Dispatchers.Default) {
            scripts.map { async { ScriptSource(it).text } }.awaitAll()
        }

        // then
        assert(texts == scripts.map { ScriptSource(it).text })
        assert(texts.withIndex().all { (index, text) -> "enum class E$index" !in text.substringAfter(SCRIPT_BLOCK_START) })
    }

    @Test
    fun `should parse long function body and class body`() {
        // given
        val count = 100_000
        val script = buildString {
            appendLine("fun f(): Int {")
            appendLine("    val v0 = 0")
            (1 until count).forEach { appendLine("    val v$it = v${it - 1}") }
            appendLine("    return v${count - 1}")
            appendLine("}")
            appendLine("class C {")
            appendLine("    val w0 = 0")
            (1 until count).forEach { appendLine("    val w$it = w${it - 1}") }
            appendLine("}")
            append("f() + C().w${count - 1}")
        }

        // when
        val source = ScriptSource(script)

        // then
        val body = source.text.substringAfter(SCRIPT_BLOCK_START)
        assert("fun f(): Int {" in body && "class C {" in body)
    }

}
