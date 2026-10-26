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
    fun `should declare local functions preceding the statements calling them`() {
        // given
        val script = """
            val factor = 2
            val y = 1; fun a() = b() + y
            fun b() = c() * factor
            fun c() = factor
            a()
        """.trimIndent()
        val parsed = ScriptSource.Parsed(script)

        // when
        val hoisted = parsed.hoisted(parsed.source(), setOf(ScriptPosition(1, 21) to "b", ScriptPosition(2, 10) to "c"))

        // then
        val block = hoisted?.source()?.text?.substringAfter(SCRIPT_BLOCK_START)?.substringBefore(SCRIPT_BLOCK_END)
        // each following the ones it calls, separated from the statements, keeping the columns, while the originals are blanked out
        assert(block?.lines()?.map { it.trimEnd() } == listOf(
            "",
            "val factor = 2",
            ";",
            "fun c() = factor",
            "fun b() = c() * factor",
            ";",
            // split after the `;`, see ScriptStructure.lineSplits
            "val y = 1;",
            " ".repeat(11) + "fun a() = b() + y",
            "",
            "",
            "a()",
            ""
        ))
    }

    @Test
    fun `should not declare local function preceding the statement calling it when it refers to declaration in between`() {
        // given
        val script = """
            val factor = 2
            fun a() = c()
            val d = factor + 1
            fun c() = d
            a()
        """.trimIndent()
        val parsed = ScriptSource.Parsed(script)

        // when
        val hoisted = parsed.hoisted(parsed.source(), setOf(ScriptPosition(1, 10) to "c"))

        // then
        assert(hoisted == null)
    }

    @Test
    fun `should declare long chain of local functions preceding the statements calling them`() {
        // given
        val count = 2000
        val script = buildString {
            appendLine("val x = 1")
            (0 until count - 1).forEach { appendLine("fun f$it() = f${it + 1}() + x") }
            appendLine("fun f${count - 1}() = x")
            append("f0()")
        }
        val parsed = ScriptSource.Parsed(script)
        val unresolved = (0 until count - 1).mapTo(HashSet()) { ScriptPosition(it + 1, "fun f$it() = ".length) to "f${it + 1}" }

        // when
        val hoisted = parsed.hoisted(parsed.source(), unresolved)

        // then
        val block = hoisted?.source()?.text?.substringAfter(SCRIPT_BLOCK_START)?.lines()
        assert(block?.subList(1, 5) == listOf("val x = 1", ";", "fun f${count - 1}() = x", "fun f${count - 2}() = f${count - 1}() + x"))
    }

    @Test
    fun `should recognize calls preceding local functions of their names as candidates`() {
        // given
        val script = """
            val p = "1".toInt()
            fun early(x: Int) = early(x - 1) + p
            early(1)
            print("a")
            "a".shout()
            log("a")
            ::later
            data class V(val x: Int)
            val r = run { V(1) + V(2) }
            fun print(x: Any) = kotlin.io.print(x.toString() + p)
            fun String.shout() = uppercase() + p
            fun log(x: Any) = print(x)
            fun later() = p
            operator fun V.plus(o: V) = V(x + o.x + p)
        """.trimIndent()

        // when
        val source = ScriptSource(script, dependencies = listOf(PreambleDependency("log", { _, _ -> "val log: Any = 1" }, isCallable = true)))

        // then
        // unlike the calls following them, the ones named like the dependencies, and the callable references
        assert(source.laterDeclaredFunctionCandidates.map { "${it.reference.name}:${it.reference.start.line}:${it.line}:${it.isConfirmedOnlyWhenFailing}" } == listOf(
            // the operators recognized by their names only, like `+` of `Int`, which the confirming compilation tells apart
            "plus:1:13:false",
            "print:3:9:false",
            "shout:4:10:false",
            // within a lambda, referring to a local class
            "plus:8:13:true",
            "plus:9:13:false",
            // within an extension function
            "plus:10:13:true"
        ))
    }

    @Test
    fun `should probe the types of local properties called by moved declarations on the lines following them`() {
        // given
        val script = """
            val print = printer() // trailing
            val trim = trimmer(); val x = 1
            val sum = (10L * 2) + 1
            val (log, n) = logger() to 1
            sealed class S { fun f() = print(1) + " a ".trim() + log(n) + listOf(1, 2).sum() }
        """.trimIndent()

        // when
        val sources = listOf(script, script.replace("\n", "\r\n")).map { ScriptSource(it, propertyTypeProbes = true) }

        // then
        // unless another statement follows on the same line, or initialized with a literal, which cannot be called
        assert(sources.all { it.untypedProperties == setOf("print", "log") })
        // also following the lines terminated by `\r\n`
        assert(sources.all { source ->
            source.text.substringAfter(SCRIPT_BLOCK_START).lines().filter { it.isNotBlank() }.map { it.trimEnd() } == listOf(
                "val print = printer() // trailing",
                "val `_typeProbe_0`: _ScriptTypeProbe = `print`",
                // split after the `;`, see ScriptStructure.lineSplits
                "val trim = trimmer();",
                " ".repeat(22) + "val x = 1",
                "val sum = (10L * 2) + 1",
                "val (log, n) = logger() to 1",
                "val `_typeProbe_1`: _ScriptTypeProbe = `log`",
                // returned instead of the value of the last statement preceding the moved declaration
                "null",
                "} }"
            )
        })
    }

    @Test
    fun `should declare the copies of the local functions with the return types inferred only if inferred in the script`() {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: Int) = "int${'$'}p"
            fun show(x: Long): String = "long${'$'}p"
            fun show(x: Short) { println(x.toString() + p) }
            fun show(x: Any) = "any"
            sealed interface S
            class T : S { fun f() = show(1) }
            T().f()
        """.trimIndent()

        // when
        val source = ScriptSource(script, confirming = true)

        // then
        val copies = source.text.lines().filter { "fun `show`" in it }.map { it.substringAfter(") fun ") }
        assert(copies == listOf(
            "`show`(`x`: Int) = kotlin.TODO()",
            "`show`(`x`: Long): String = kotlin.TODO()",
            "`show`(`x`: Short): Nothing = kotlin.TODO()"
        ))
    }

    @Test
    fun `should replace in copies only local classes declared later which their parameter types refer to`() {
        // given
        val script = """
            val p = 1
            val a = f(null)
            class P { val q = p }
            class R { val q = p }
            fun f(x: P?, y: Any = emptyList<R>()) = p
            a
        """.trimIndent()

        // when
        val source = ScriptSource(script, listOf(PreambleDependency("f", { _, _ -> "val `f`: (Any?) -> Int = TODO()" }, isCallable = true)), confirming = true)

        // then
        val copies = source.text.lines().filter { "_InvisibleClass_" in it }.map { it.substringAfter("` ") }
        assert(copies == listOf(
            "abstract class `_InvisibleClass_2_P`",
            "fun kotlinx.coroutines.CoroutineScope.`f`(`x`: `_InvisibleClass_2_P`?, `y`: Any = kotlin.TODO()) = kotlin.TODO()"
        ))
    }

    @Test
    fun `should derive the sources of all the compilations from the script parsed once`() {
        // given
        val script = """
            val log = mutableListOf<String>()
            fun printer(): (Any?) -> Unit = { log += it.toString() }
            val print = printer()
            @Suppress("DEPRECATION") fun show() = x
            val x = 2
            val f = { s: String -> "prop" }
            val a = { f(1) }
            val b = f("a")
            val r = run { launch { } + Foo(1) }
            fun launch(block: () -> Unit): String = "mine"
            class Foo(val v: Int)
            sealed class Shape { fun show() = print("shape") }
            class Circle : Shape()
            fun f(x: Int) = "fun-int"
            fun f(s: String) = "fun-str"
        """.trimIndent()
        val dependencies = listOf("x", "Foo").map { PreambleDependency(it, { _, _ -> "val `$it`: Int = TODO()" }) }
        val sources = listOf<ScriptSource.Parsed.() -> ScriptSource>(
            { source() },
            { source(propertyTypeProbes = true) },
            { source(confirming = true, propertyTypes = mapOf("print" to "(Any?) -> Unit")) },
            { source(confirming = true, shadowedFunctionCopies = true) },
            { source(confirming = true, overloadingFunctionCopies = true) },
            { source(confirming = true, coroutineScopeCopies = true) }
        )
        val parsed = ScriptSource.Parsed(script, dependencies)

        // when
        val derived = sources.map { parsed.it() }

        // then
        val separate = sources.map { ScriptSource.Parsed(script, dependencies).it() }
        assert(derived.map { it.text } == separate.map { it.text })
        assert(derived.map { it.text }.distinct().size == sources.size)
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

    @Test
    fun `should refer to hidden local invoke operator only the calls of values`() {
        // given
        val script = """
            val p = listOf("!").first()
            operator fun Int.invoke(x: Any?): String = "int:${'$'}x${'$'}p"
            val q: Int = listOf(1).first()
            sealed class S { fun g(n: Int) = listOf(1).joinToString { "a" } + println("x") + n(1) + q(2) + this.toString() }
            class T : S()
            T().g(1)
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.hiddenLocalFunctionCandidates.filter { it.reference.name == "invoke" }.map { it.reference.start.column } == listOf(81, 88))
    }

    @Test
    fun `should override the members of Any by the copies of the moved functions of their signatures`() {
        // given
        val script = """
            val r = listOf(1).map { toString() + hashCode() + equals(null) + equals("x") }
            fun toString(): String = "mine"
            fun hashCode() = 1
            fun equals(other: Any?): Boolean = true
            fun equals(other: String): Boolean = true
            r
        """.trimIndent()

        // when
        val source = ScriptSource(script, confirming = true, coroutineScopeCopies = true)

        // then
        val copies = source.text.lines().filter { "_OptIn_coroutine_scope_function` " in it }.map { it.substringAfter("` ") }
        assert(
            copies == listOf(
                "override fun toString(): kotlin.String = kotlin.TODO()",
                "override fun hashCode(): kotlin.Int = kotlin.TODO()",
                "override fun equals(other: kotlin.Any?): kotlin.Boolean = kotlin.TODO()",
                "fun `equals`(`other`: String): Boolean = kotlin.TODO()"
            )
        )
    }

}
