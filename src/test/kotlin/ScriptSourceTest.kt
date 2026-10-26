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
    fun `should declare local functions preceding the statements calling them`() {
        // given
        val script = """
            val factor = "2".toInt()
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
            "val factor = \"2\".toInt()",
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
            appendLine("val x = \"1\".toInt()")
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
        assert(block?.subList(1, 5) == listOf("val x = \"1\".toInt()", ";", "fun f${count - 1}() = x", "fun f${count - 2}() = f${count - 1}() + x"))
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
            val p = "1".toInt()
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
    fun `should keep many properties with accessors reading local property in the script body as delegated properties`() {
        // given
        val count = 50_000
        val script = buildString {
            appendLine("var x = 1")
            (0 until count).forEach { appendLine("val p$it: Int get() = x + $it") }
            append("p0 + p${count - 1}")
        }

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("val p" !in moved)
        assert(body.lines().count { it == " by object {" } == count)
    }

    @Test
    fun `should keep long chain of properties with accessors reading local property in the script body as delegated properties`() {
        // given
        val count = 50_000
        val script = buildString {
            appendLine("var x = 1")
            appendLine("val p0: Int get() = x")
            (1 until count).forEach { appendLine("val p$it: Int get() = p${it - 1} + 1") }
            append("p${count - 1}")
        }

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("val p" !in moved)
        assert(body.lines().count { it == " by object {" } == count)
    }

    @Test
    fun `should keep property with accessor reading earlier delegated property named like receiver member as delegated property`() {
        // given
        val script = "val items = listOf(1)\nval isActive get() = items.isEmpty()\nval t get() = items.size.toString() + isActive\nt"

        // when
        val source = ScriptSource(script)

        // then
        val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
        assert("get()" !in moved)
        assert(body.lines().count { it == " by object {" } == 2)
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
            // not applicable to a local variable
            "val items = listOf(1)\n@Deprecated(\"x\")\nval total get() = items.size\ntotal",
            "@Target(AnnotationTarget.PROPERTY)\nannotation class Tag\nval items = listOf(1)\n@Tag\nval total get() = items.size\ntotal",
            "val items = listOf(1)\n@get:Suppress(\"x\")\nval total get() = items.size\ntotal",
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
            // imported under an alias
            "import kotlin.let as myLet\nval items = listOf(1, 2)\nval t get() = items.size.toString() + myLet { it::class.simpleName }\nt",
            "import kotlinx.coroutines.launch as go\nval items = mutableListOf(1, 2)\nval t: Int\n    get() { go { items += 5 }; return items.size }\nt",
            // called, while a local property declared before cannot be invoked
            "val items = listOf(1, 2)\nval let = items.size + 1\nval t get() = items.size.toString() + let { it::class.simpleName }\nt",
            // resolved on the receiver of a lambda before the local property, unlike the script
            "class User(val name: String)\nval name = \"local\"\nval items = listOf(1)\nval t get() = items.size.toString() + with(User(\"u\")) { name }\nt",
            "class User(val name: String)\nfun name() = \"local\"\nval items = listOf(1)\nval t get() = items.size.toString() + listOf(1).map { User(\"u\").run { name } }\nt",
            "class Box { val factor = 10; fun count(block: Box.() -> Int) = block() }\nval factor = listOf(2).first()\nval items = listOf(1)\nval t get() = items.size + Box().count { factor }\nt",
            "fun <T> T.twice(block: T.() -> Int) = block()\nclass Box { val factor = 10 }\nval factor = listOf(2).first()\nval items = listOf(1)\nval t get() = items.size + Box().twice { factor }\nt",
            "class Box { fun g() = \"box\" }\nfun g() = \"script\"\nval items = listOf(1)\nval t get() = items.size.toString() + with(Box()) { g() }\nt",
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
    fun `should keep property with accessor reading local properties within lambdas without receiver as delegated property`() {
        // given
        val locals = "val factor = listOf(2).first()\nval items = listOf(1, 2)\n"
        val scripts = listOf(
            locals + "val t get() = items.count { it > factor }.toString() + items.map { x -> listOf(x).sumOf { it * factor } }.sum()\nt",
            locals + "val t get() = items.zip(items) { a, b -> a + b * factor }.sum()\nt",
            locals + "val t get() = items.mapTo(HashSet()) { it * factor }.size\nt",
            locals + "val t get() = items.maxOfOrNull { it * factor }\nt",
            locals + "val t get() = items.associateWith { it }.mapValues { it.value * factor }.values.sum()\nt",
            locals + "val t get() = items.chunked(2) { it.sum() * factor }.sum()\nt",
            // labeled, or annotated
            locals + "val t get() = items.map label@{ it * factor }.sum()\nt",
            locals + "val t get() = items.map(@Suppress(\"x\") { it * factor }).sum()\nt",
            // declared by the script without a receiver of the lambda
            "fun <T> twice(block: () -> T) = listOf(block(), block())\n" + locals + "val t get() = items.size + twice { factor }.sum()\nt",
            // not a function
            "class Page(val count: Int)\n" + locals + "val t get() = items.count { it > factor }\nt",
            "fun process(map: String) = map\n" + locals + "val t get() = items.map { it * factor }.sum()\nt",
            // a moved function is resolved like in the script
            "class Box { fun g() = \"box\" }\n@JvmName(\"gg\") fun g() = \"script\"\nval items = listOf(1)\nval t get() = items.size.toString() + with(Box()) { g() }\nt"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("get()" !in moved && " by object " in body)
        }
    }

    @Test
    fun `should keep property with accessor reading local property and annotations applicable to local variable as delegated property`() {
        // given
        val scripts = listOf(
            "val items = listOf(1)\n@OptIn(ExperimentalStdlibApi::class)\nval t get() = items.size\nt",
            "@Target(AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE)\nannotation class Tag\nval items = listOf(1)\n@Tag\nval t get() = items.size\nt",
            "annotation class Tag\nval items = listOf(1)\n@Tag @Suppress(\"unused\")\nval t get() = items.size\nt"
        )

        // when
        val sources = scripts.map { ScriptSource(it) }

        // then
        sources.forEach { source ->
            val (moved, body) = source.text.split(SCRIPT_BLOCK_START)
            assert("get()" !in moved && " by object " in body)
        }
    }

    @Test
    fun `should keep moving property with accessor calling dependency named like extension applicable to any receiver`() {
        // given
        val script = "val items = listOf(1, 2)\nval t get() = items.size.toString() + let { it::class.simpleName }\nt"

        // when
        val source = ScriptSource(script, listOf(PreambleDependency("let", { _, _ -> "val let: Int = 1" })))

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
            "val log = mutableListOf<String>()\ninline fun status() = log.size.toString() + isActive\nstatus()",
            "import kotlin.let as myLet\nval log = mutableListOf<String>()\ninline fun status() = log.size.toString() + myLet { 1 }\nstatus()",
            "val log = mutableListOf<String>()\nval let = log.size + 1\ninline fun status() = log.size.toString() + let { 1 }\nstatus()"
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
        assert(sources.all { it.movedClassName("Nested class", "A", index = 0, count = 1) == null && it.laterDeclaredDependencyCandidates.isEmpty() })
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
