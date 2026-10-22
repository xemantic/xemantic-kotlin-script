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
    fun `should move with property any accessor following its accessor on the line after it without indentation`() {
        // given
        val scripts = listOf(
            "var v = 0\nget() = field + 1\nset(3)\nv",
            "var v = 0\nget() = field + 1\nset\nv",
            "var v = 0\nget(3)\nset(value) { field = value }\nv"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        assert(sources.all { source ->
            source.text.substringAfter(SCRIPT_BLOCK_START).let { body -> "get" !in body && "set" !in body }
        })
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
    fun `should strip UTF-8 byte order mark starting script`() {
        // given
        val scripts = listOf(
            "\uFEFF1 + 1",
            "\uFEFF@file:Suppress(\"UNUSED\")\n1 + 1",
            "\uFEFFpackage foo\n1 + 1"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        assert(sources.none { '\uFEFF' in it.text || '\uFEFF' in it.scriptLines.first() })
        assert(sources.zip(scripts).all { (source, script) ->
            source.scriptLines.first() == script.lines().first().removePrefix("\uFEFF")
        })
    }

    @Test
    fun `should blank out unterminated block comment ending script only at top level of script body`() {
        // given
        val topLevel = listOf(
            "val a = 1\n/* abc\na",
            "1 + 1\n/** abc",
            "1 + 1\n/* a /* b */ abc",
            "fun f() = 3\nf() /* abc",
            "fun f() = 3 /* abc",
            "import kotlin.math.PI\n/* abc",
            "import kotlin.math.PI /* abc",
            "/* abc"
        )
        val nested = listOf(
            "class A {\n  /* abc\n  val x = 1\n}\nA().x",
            "fun f(): Int {\n  /* abc\n  return 1\n}\nf()",
            "listOf(1,\n/* abc",
            "val a = 5\n\"\${a /* abc }\""
        )

        // when
        val topLevelSources = topLevel.map { ScriptSource(it) }
        val nestedSources = nested.map { ScriptSource(it) }

        // then
        assert(topLevelSources.none { "abc" in it.text })
        assert(nestedSources.all { "abc" in it.text })
        assert((topLevel + nested).zip(topLevelSources + nestedSources).all { (script, source) ->
            script.substringBefore("/*").lines().map { it.trim() }.filter { it.isNotEmpty() }.all { it in source.text }
        })
    }

    @Test
    fun `should not analyze structure of script which is not wrapped`() {
        // given
        val scripts = listOf(
            "x\nA().y\nclass A {\n  val y = 1\n",
            "x\nA().y\nclass A {\n  val y = 1\n}\n}\nval x = 2"
        )
        val dependencies = listOf(PreambleDependency("x", { _, _ -> "val x: Int = 42" }))

        // when
        val sources = scripts.map { ScriptSource(it, dependencies) }

        // then
        assert(sources.none { it.isWrapped })
        assert(sources.all { it.movedDeclarationName("A") == null && it.laterDeclaredDependencyCandidates.isEmpty() })
    }

    @Test
    fun `should not wrap script left unclosed at its end, which would swallow or be closed by generated code`() {
        // given
        val scripts = listOf(
            "A().x\nclass A {\n  val x = 1\n  /* abc\n}",
            "f()\nfun f(): Int {\n  /* abc\n  return 1\n}",
            "val a = 5\n\"\${a",
            "val a = 5\r\nval s = \"\"\"abc",
            "listOf(1,\n/* abc"
        )
        val dependencies = listOf(PreambleDependency("x", { _, _ -> "val x: Int = 42" }))

        // when
        val sources = scripts.map { ScriptSource(it, dependencies) }

        // then
        assert(sources.zip(scripts).all { (source, script) -> source.text == script.replace("\r\n", "\n") })
        assert(sources.none { source -> (1..source.scriptLines.size).any { source.isGeneratedLine(it) } })
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
