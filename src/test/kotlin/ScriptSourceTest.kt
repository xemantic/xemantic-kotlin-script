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
    fun `should move many classes accessing local property referred to by declaration which cannot be local`() {
        // given
        val count = 50_000
        val script = buildString {
            appendLine("var x = 1")
            appendLine("enum class All { A; fun all() = listOf(${(0 until count).joinToString { "C$it()" }}) }")
            (0 until count).forEach { appendLine("class C$it { fun v() = x }") }
            append("All.A.all()")
        }

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("class C0 { fun v() = x }" in moved && "class C${count - 1} { fun v() = x }" in moved)
        assert(body.removeSuffix(SCRIPT_BLOCK_END).lines().filter { it.isNotBlank() } == listOf("var x = 1", "All.A.all()"))
    }

    @Test
    fun `should move function referring to class accessing local property once the class is moved`() {
        // given
        val script = """
            var x = 1
            enum class All { A; fun all() = describe() + Counter().v() }
            fun describe() = "c" + Counter().v()
            class Counter { fun v() = x }
            All.A.all()
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("fun describe()" in moved && "class Counter" in moved)
        assert(body.removeSuffix(SCRIPT_BLOCK_END).lines().filter { it.isNotBlank() } == listOf("var x = 1", "All.A.all()"))
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
    fun `should not move function referred to before its declaration only by locally bound names`() {
        // given
        val scripts = listOf(
            "val m = listOf(1).map { format -> format }\nfun format() = 7\nm",
            "val m = listOf(1 to 2).map { (format, b) -> format + b }\nfun format() = 7\nm",
            "fun twice(format: Int) = format * 2\nfun format() = 7\ntwice(format())",
            "val m = run { val format = 1; format }\nfun format() = 7\nm",
            "var s = 0\nfor (format in listOf(1, 2)) s += format\nfun format() = 7\ns",
            "val m = try { error(\"x\") } catch (format: Exception) { format.message }\nfun format() = 7\nm",
            "val m = when (val format = 1) { else -> format }\nfun format() = 7\nm",
            "class Box { val format = 1; fun get() = format }\nfun format() = 7\nBox().get()"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("fun format()" !in moved && "fun format()" in body)
        }
    }

    @Test
    fun `should move declaration referred to before its declaration by locally bound name called, naming type, or bound by function only`() {
        // given
        val scripts = listOf(
            "val m = listOf(1).map { format -> format() }\nfun format() = 7\nm" to "fun format()",
            "val m = listOf(1).map { format -> ::format }\nfun format() = 7\nm" to "fun format()",
            "val m = listOf(1).map { Foo -> val f: Foo? = null; f }\nclass Foo\nm" to "class Foo",
            "val m = listOf(1).map { Foo -> Foo() }\nclass Foo\nm" to "class Foo",
            "val m = run {\n    fun Regex() = 1\n    Regex::class.qualifiedName\n}\nclass Regex\nm" to "class Regex",
            "class Box { fun Regex() = 1; val k = Regex::class.qualifiedName }\nclass Regex\nBox().k" to "class Regex",
            "class Box {\n    val Int.Regex get() = 1\n    val k = Regex::class.qualifiedName\n}\nclass Regex\nBox().k" to "class Regex",
            "val m = run {\n    fun Foo() = 1\n    Foo::toString\n}\nclass Foo\nm" to "class Foo"
        )

        // when
        val sources = scripts.map { (script, declaration) -> ScriptSource(script) to declaration }

        // then
        sources.forEach { (source, declaration) ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert(declaration in moved && declaration !in body)
        }
    }

    @Test
    fun `should keep property with accessors reading local property in the script body as delegated property`() {
        // given
        val script = """
            val items = mutableListOf(1, 2)
            var first: Int
                get() = items.first()
                set(v) { items[0] = v }
            val total: Int get() = items.sum(); total + first
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("items" !in moved)
        val lines = body.lines()
        // the accessors retain their columns, like the copies of the types, mapped to their lines
        assert(" ".repeat(10) + "= items.first()" in lines && " ".repeat(8) + "v" in lines && " ".repeat(9) + ") { items[0] = v }" in lines)
        assert(lines.count { it == " ".repeat(9) + ": Int" } == 3)
        // the accessor ends with the semicolon
        assert(" ".repeat(21) + "= items.sum();" in lines && " ".repeat(36) + "total + first" in lines)
        assert(source.text.lines().indices.mapNotNull { source.toScriptLine(it + 1) } == listOf(0, 1, 1, 2, 1, 2, 3, 3, 1, 3, 3, 4, 4, 4, 4, 4, 4, 4))
    }

    @Test
    fun `should keep moving property with accessors which cannot be delegated or do not read local properties`() {
        // given
        val scripts = listOf(
            "val items = listOf(1)\nvar c = 0\n    set(value) { field = value + items.size }\nc",
            "val items = listOf(1)\nval Int.plusSize get() = this + items.size\n1.plusSize",
            "val items = listOf(1)\nval described get() = items.size.toString() + toString()\ndescribed",
            "val items = listOf(1)\nval self get() = this.hashCode() + items.size\nself",
            "val items = listOf(1)\nval total: Int\n    @Suppress(\"unused\") get() = items.sum()\ntotal",
            "val items = listOf(1)\nvar total\n    get() = items.sum()\n    set(v) { }\ntotal",
            "fun Any.describe() = this::class.simpleName\nval items = listOf(1)\nval d get() = items.size.toString() + describe()\nd",
            "val total get() = 42\ntotal",
            "class User(val name: String)\nval name = \"script\"\nval greeting get() = User(\"user\").run { name }\ngreeting",
            // resolved on the implicit receivers of the object, or of the script block, unlike in the script
            "val items = listOf(1)\nval t get() = items.size.toString() + javaClass.name\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + let { 1 }\nt",
            "val items = listOf(1)\nval t get() = items.isEmpty() || isActive\nt",
            "val items = listOf(1)\nval t get() = items.size.also { launch { } }\nt",
            // a local property is not visible in its own accessors, nor can be referred to by a callable reference
            "val items = listOf(1)\nval countdown: Int get() = if (items.isEmpty()) 0 else items.size + countdown\ncountdown",
            "val items = listOf(1)\nval total get() = items.size\nval r = ::total\nr.get()",
            // resolved on the implicit receivers, even if imported or declared, unless declared before as a local property
            "import kotlinx.coroutines.launch\nval items = mutableListOf(1, 2)\nval t: Int\n    get() { launch { items += 5 }; return items.size }\nt",
            "import kotlinx.coroutines.isActive\nval items = listOf(1, 2)\nval t get() = items.isEmpty() || isActive\nt",
            "import kotlin.let\nval items = listOf(1, 2)\nval t get() = items.size.toString() + let { 1 }\nt",
            "import kotlin.jvm.javaClass\nval items = listOf(1, 2)\nval t get() = items.size.toString() + javaClass.name\nt",
            "fun let(x: Int) = x\nval items = listOf(1, 2)\nval t get() = items.size.toString() + let { it::class.simpleName }\nt",
            // not visible in the accessors, if declared after them, unlike in the script
            "val items = listOf(1)\nval t get() = items.size.toString() + isActive\nval isActive = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + javaClass\nval javaClass = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + coroutineContext\nval coroutineContext = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + ensureActive()\nfun ensureActive() = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + suffix\nval suffix = \"mine\"\nt"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("get()" in moved || "set(value)" in moved)
            assert(" by object " !in body)
        }
    }

    @Test
    fun `should keep moving property with accessor calling dependency named like extension applicable to any receiver`() {
        // given
        val script = "val items = listOf(1, 2)\nval t get() = items.size.toString() + let { it::class.simpleName }\nt"

        // when
        val source = ScriptSource(script, listOf(PreambleDependency("let", "val let: Int = 1")))

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("get()" in moved && " by object " !in body)
    }

    @Test
    fun `should keep inline function reading local property in the script body without the inline modifiers`() {
        // given
        val script = """
            val log = mutableListOf<String>()
            inline fun logged(crossinline block: () -> Unit, noinline after: () -> Unit) { log += "start"; block(); after() }
            logged({ }) { }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("logged" !in moved)
        // the rest keeps its columns
        assert(" ".repeat(7) + "fun logged(" + " ".repeat(12) + "block: () -> Unit, " + " ".repeat(9) + "after: () -> Unit)" in body)
    }

    @Test
    fun `should recognize lambdas passed to inline function kept local only in calls without receiver`() {
        // given
        val script = """
            val log = mutableListOf<String>()
            inline fun logged(block: () -> Unit) { log += "start"; block() }
            class Other { fun logged(block: () -> Unit) = block() }
            logged { }
            Other().logged { }
        """.trimIndent()

        // when
        val source = ScriptSource(script)

        // then
        assert(source.localInlineFunctionArguments.map { it.start.line } == listOf(3))
    }

    @Test
    fun `should keep moving inline function which cannot be local or does not read local properties`() {
        // given
        val scripts = listOf(
            "val log = mutableListOf<String>()\ninline fun f() = 1\nf()",
            // the non-local control flow of the lambdas of its calls
            "val log = mutableListOf<String>()\ninline fun logged(block: () -> Unit) { log += \"a\"; block() }\nfun outer(): Int { logged { return 5 }; return 1 }\nouter()",
            "val log = mutableListOf<String>()\ninline fun logged(block: () -> Unit) { log += \"a\"; block() }\nfun outer(): Int { logged({ return@outer 5 }); return 1 }\nouter()",
            "val log = mutableListOf<String>()\ninline fun logged(block: () -> Unit) { log += \"a\"; block() }\nfor (i in 1..3) { logged { if (i == 2) break } }\nlog",
            "val log = mutableListOf<String>()\ninline fun logged(block: () -> Unit) { log += \"a\"; block() }\nouter@ for (i in 1..3) { for (j in 1..3) { logged { if (j == 2) continue@outer } } }\nlog",
            "val log = mutableListOf<String>()\ninline fun logged(block: () -> Unit) { log += \"a\"; block() }\nlistOf(1).forEach { logged { return@forEach } }\nlog",
            // which cannot be local, or might resolve the names on the receivers
            "val log = mutableListOf<String>()\ninline fun <reified T> logged() { log += T::class.simpleName!! }\nlogged<Int>()\nlog",
            "val log = mutableListOf<String>()\ninline fun String.logged() { log += this }\n\"a\".logged()\nlog",
            "val log = mutableListOf<String>()\n@JvmName(\"lg\") inline fun logged() { log += \"a\" }\nlogged()\nlog",
            "class User(val name: String)\nval name = \"script\"\ninline fun greeting() = User(\"user\").run { name }\ngreeting()",
            // not visible in the local function, if declared after it, unlike in the script
            "val log = mutableListOf<String>()\ninline fun status() = log.size.toString() + isActive\nval isActive = false\nstatus()",
            "val log = mutableListOf<String>()\ninline fun status() = log.size.toString() + suffix\nval suffix = \"!\"\nstatus()",
            "val log = mutableListOf<String>()\ninline fun logged() { helper() }\nfun helper() { log += \"h\" }\nlogged()\nlog",
            "class User(val name: String)\nval name = \"script\"\nval log = mutableListOf<String>()\ninline fun g() = log.size.toString() + User(\"u\").run { name }\ng()",
            "val log = mutableListOf<String>()\ninline fun status() = log.size.toString() + isActive\nstatus()"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, _) = source.text.split(SCRIPT_BLOCK_START)
            assert("inline fun" in moved)
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
