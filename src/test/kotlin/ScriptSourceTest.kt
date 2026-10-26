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
    fun `should find body declaration of the kind of the reference`() {
        // given
        val script = """
            fun Base(x: Int) = x
            class Base
            val t: Base? = null
            val c = Base(1)
            val r = ::Base
            val v = Base
            val i = 1 Base 2
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("Base", ScriptPosition(2, 7)) == (1 to DeclarationKind.CLASS))
        assert(source.bodyDeclarationLine("Base", ScriptPosition(3, 8)) == (0 to DeclarationKind.FUNCTION))
        assert(source.bodyDeclarationLine("Base", ScriptPosition(4, 10)) == (0 to DeclarationKind.FUNCTION))
        assert(source.bodyDeclarationLine("Base", ScriptPosition(5, 8)) == (1 to DeclarationKind.CLASS))
        assert(source.bodyDeclarationLine("Base", ScriptPosition(6, 10)) == null)
    }

    @Test
    fun `should find body declaration of reference indexed or iterated`() {
        // given
        val script = """
            val arr = listOf(1)
            val x = arr[0]
            for (y in arr) println(y)
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("arr", ScriptPosition(1, 8)) == (0 to DeclarationKind.PROPERTY))
        assert(source.bodyDeclarationLine("arr", ScriptPosition(2, 10)) == (0 to DeclarationKind.PROPERTY))
    }

    @Test
    fun `should find body declaration of reference indexed or iterated named like its operator`() {
        // given
        val script = """
            val get = listOf(1)
            val next = listOf(1)
            val x = get[0]
            for (y in next) println(y)
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("get", ScriptPosition(2, 8)) == (0 to DeclarationKind.PROPERTY))
        assert(source.bodyDeclarationLine("next", ScriptPosition(3, 10)) == (1 to DeclarationKind.PROPERTY))
    }

    @Test
    fun `should find body declaration only of extension function for infix call or qualified call`() {
        // given
        val script = """
            fun bar(o: Int) = o
            infix fun Int.baz(o: Int) = o
            val x = 1 bar 2
            val y = 1.bar(2)
            val z = 1 baz 2
            val w = 1.baz(2)
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("bar", ScriptPosition(2, 10)) == null)
        assert(source.bodyDeclarationLine("bar", ScriptPosition(3, 10)) == null)
        assert(source.bodyDeclarationLine("baz", ScriptPosition(4, 10)) == (1 to DeclarationKind.FUNCTION))
        assert(source.bodyDeclarationLine("baz", ScriptPosition(5, 10)) == (1 to DeclarationKind.FUNCTION))
    }

    @Test
    fun `should find body declaration of class only for unqualified type`() {
        // given
        val script = """
            class Config
            class Mode
            val m: Config.Mode? = null
            val n: Mode? = null
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("Mode", ScriptPosition(2, 14)) == null)
        assert(source.bodyDeclarationLine("Mode", ScriptPosition(3, 7)) == (1 to DeclarationKind.CLASS))
    }

    @Test
    fun `should find body declaration only of the kinds the reference might refer to`() {
        // given
        val script = """
            val f = { 1 }
            fun g() = 1
            class C
            val plus = 0
            val a: f? = null
            val b: g? = null
            val x = f()
            val y = C()
            val z = 1 + 2
            val w = g
            val u = y.f
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.bodyDeclarationLine("f", ScriptPosition(4, 7)) == null)
        assert(source.bodyDeclarationLine("g", ScriptPosition(5, 7)) == null)
        assert(source.bodyDeclarationLine("f", ScriptPosition(6, 8)) == (0 to DeclarationKind.PROPERTY))
        assert(source.bodyDeclarationLine("C", ScriptPosition(7, 8)) == (2 to DeclarationKind.CLASS))
        assert(source.bodyDeclarationLine("plus", ScriptPosition(8, 10)) == null)
        assert(source.bodyDeclarationLine("g", ScriptPosition(9, 8)) == null)
        assert(source.bodyDeclarationLine("f", ScriptPosition(10, 10)) == null)
    }

    @Test
    fun `should find properties of the script referred to in range`() {
        // given
        val script = """
            const val A = "a"
            val (b, c) = "b" to "c"
            val A = "redeclared"
            @Suppress(A + b + A + c.length + B.c) fun f() = 1
            class B { val b = 1; @Deprecated(b) fun g() = b }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.propertiesReferredIn(ScriptPosition(3, 10), ScriptPosition(3, 36)) == listOf("A" to 2, "b" to 1, "c" to 1))
        assert(source.propertiesReferredIn(ScriptPosition(4, 33), ScriptPosition(4, 34)).isEmpty())
    }

    @Test
    fun `should find properties of the script referred to in range at the lines of their names`() {
        // given
        val script = """
            @Suppress("X")
            val A = "a"
            @Suppress(A)
            val A = "redeclared"
            @JvmName(A) fun f(A: String) = A
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.propertiesReferredIn(ScriptPosition(2, 10), ScriptPosition(2, 11)) == listOf("A" to 1))
        assert(source.propertiesReferredIn(ScriptPosition(4, 9), ScriptPosition(4, 10)) == listOf("A" to 3))
        assert(source.propertiesReferredIn(ScriptPosition(4, 9), ScriptPosition(4, 10), excluded = setOf(ScriptPosition(4, 9))).isEmpty())
    }

    @Test
    fun `should find properties of the script referred to in annotations not seeing parameters nor properties of companion objects`() {
        // given
        val script = """
            const val MSG = "m"
            annotation class Ann(val value: String)
            fun f(@Suppress(MSG) MSG: String) = MSG
            class C @Ann(MSG) constructor(val MSG: String)
            class D { companion object { fun MSG() = 1 }; @Ann(MSG) fun g() = 1 }
            class E { companion object { const val MSG = "e" }; @Ann(MSG) fun g() = 1 }
            @Suppress("X")
            val (p, q) = "p" to "q"
            @Ann(q) fun h() = 1
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.propertiesReferredIn(ScriptPosition(2, 16), ScriptPosition(2, 19)) == listOf("MSG" to 0))
        assert(source.propertiesReferredIn(ScriptPosition(3, 13), ScriptPosition(3, 16)) == listOf("MSG" to 0))
        assert(source.propertiesReferredIn(ScriptPosition(4, 51), ScriptPosition(4, 54)) == listOf("MSG" to 0))
        assert(source.propertiesReferredIn(ScriptPosition(5, 57), ScriptPosition(5, 60)).isEmpty())
        assert(source.propertiesReferredIn(ScriptPosition(8, 5), ScriptPosition(8, 6)) == listOf("q" to 7))
    }

    @Test
    fun `should find callable references to script members only of extension functions not shadowed by members of class declared in the script`() {
        // given
        val script = """
            fun List<Int>.total() = sum()
            class Order { fun total() = 3 }
            open class Base { fun total() = 4 }
            class Special : Base()
            sealed interface S
            class T : S { fun f() = Order::total; fun g() = Special::total; fun h() = List<Int>::total }
            T().f()
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.scriptMemberCallableReferences.map { it.second } == listOf("List<Int>::total"))
    }

}
