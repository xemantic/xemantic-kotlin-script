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
    fun `should move property with getter on the line after it without indentation`() {
        // given
        val script = "val size: Int\nget() = 3\nsize"

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("val size: Int\nget() = 3" in moved && "get()" !in body)
    }

    @Test
    fun `should keep in the script body calls of get and set on the line after property without indentation`() {
        // given
        val scripts = listOf(
            "fun get(i: Int) = i * 2\nval x = 1\nget(3)",
            "fun get() = 7\nval x = 1\nget()",
            "fun set(v: Int) = v + 1\nvar y = 1\nset(5)",
            "fun get() = 5\nval x = 1\nget() + 1",
            "val get = 7\nval x = 1\n// note\nget",
            "val set = 7\nval x = 1\n/* c */ set",
            "fun get(p: String, b: () -> Int) = b()\nval x = 1\nget(\"/\") { 6 }",
            "fun get(b: () -> Int) = b()\nval x = 1\nget { 6 }",
            "fun set(b: () -> Int) = b()\nvar y = 1\nset { 6 }",
            "fun get(i: Int) = i\nval x = 1\n@Suppress(\"UNUSED\") get(6)"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        assert(sources.zip(scripts).all { (source, script) ->
            script.substringAfterLast('\n') in source.text.substringAfter(SCRIPT_BLOCK_START)
        })
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

    @Test
    fun `should name accessor only of property of the class of the frame`() {
        // given
        val script = "class P { val x: Int get() = 1 }; class Q { fun getX(): Int = 2 }"

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getX", owner = "P", line = 0) == "<get-x>")
        assert(source.accessorName("getX", owner = "Q", line = 0) == null)
        assert(source.accessorName("getX", owner = "", line = 0) == null)
    }

    @Test
    fun `should name accessor only of property of the nested class of the frame`() {
        // given
        val script = "class A { class N { val x: Int get() = 1 } }; class B { class N { fun getX(): Int = 2 } }"

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getX", owner = "A\$N", line = 0) == "<get-x>")
        assert(source.accessorName("getX", owner = "B\$N", line = 0) == null)
    }

    @Test
    fun `should name accessor only of property of the local class of the frame`() {
        // given
        val script = """
            fun a() { class L { val x: Int get() = 1 } }; fun b() { class L { fun getX(): Int = 2 } }
            val g: Int get() { class M { val y: Int get() = 3 }; return M().y }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getX", owner = "a\$L", line = 0) == "<get-x>")
        assert(source.accessorName("getX", owner = "b\$L", line = 0) == null)
        assert(source.accessorName("getY", owner = "g\$M", line = 1) == "<get-y>")
    }

    @Test
    fun `should name accessor of top-level property only at the lines of the accessor`() {
        // given
        val script = """
            var x: Int
                get() = 1
                set(value) { }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getX", owner = "", line = 0) == null)
        assert(source.accessorName("getX", owner = "", line = 1) == "<get-x>")
        assert(source.accessorName("getX", owner = "", line = 2) == null)
        assert(source.accessorName("setX", owner = "", line = 1) == null)
        assert(source.accessorName("setX", owner = "", line = 2) == "<set-x>")
    }

    @Test
    fun `should name accessors of companion object, lateinit and delegated properties`() {
        // given
        val script = """
            class C {
                companion object { val x: Int get() = 1 }
            }
            object O {
                companion object Factory { lateinit var y: String }
                var z: Int by mutableMapOf("z" to 1)
            }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getX", owner = "C\$Companion", line = 1) == "<get-x>")
        assert(source.accessorName("getY", owner = "O\$Factory", line = 4) == "<get-y>")
        assert(source.accessorName("getZ", owner = "O", line = 5) == "<get-z>")
        assert(source.accessorName("setZ", owner = "O", line = 5) == "<set-z>")
    }

    @Test
    fun `should name accessors by their JVM names capitalized like the compiler`() {
        // given
        val script = """
            val ärger: Int get() = 1
            var isOn: Boolean get() = true; set(value) { }
            val isolated: Int get() = 2
            val y: Int @JvmName("fetchY") get() = 3
            val z: Int @JvmName("fetchZ") get() = 4
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.accessorName("getärger", owner = "", line = 0) == "<get-ärger>")
        assert(source.accessorName("isOn", owner = "", line = 1) == "<get-isOn>")
        assert(source.accessorName("setOn", owner = "", line = 1) == "<set-isOn>")
        assert(source.accessorName("getIsolated", owner = "", line = 2) == "<get-isolated>")
        assert(source.accessorName("fetchY", owner = "", line = 3) == "<get-y>")
        assert(source.accessorName("getY", owner = "", line = 3) == null)
        assert(source.accessorName("fetchZ", owner = "", line = 4) == "<get-z>")
    }

}
