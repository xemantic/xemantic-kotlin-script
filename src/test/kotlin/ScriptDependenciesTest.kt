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

import RootConfig
import `is`
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.test.runTest
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private class Hidden

internal class Internal

class Outer {
    class Box<T>(val items: List<T>)
}

class ScriptDependenciesTest {

    @Test
    fun `should provide dependencies of inferred types`() = runTest {
        // given
        val script = $$"""
            "$greeting ${names.joinToString { it.uppercase() }}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "greeting" provides "Hello"
            "names" provides listOf("foo", "bar")
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello FOO, BAR")
        }
    }

    @Test
    fun `should provide dependency of nullable type`() = runTest {
        // given
        val script = """
            name ?: "nobody"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<String?>("name", null)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "nobody")
            have(warnings == null)
        }
    }

    @Test
    fun `should provide dependency of function type`() = runTest {
        // given
        val script = """
            twice(21)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "twice" provides { x: Int -> x * 2 }
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of generic nested class`() = runTest {
        // given
        val script = """
            box.items.size
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("box", Outer.Box(listOf(1, 2)))))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should provide dependency of generic nested interface`() = runTest {
        // given
        val script = """
            entry.key + entry.value
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<Map.Entry<String, Int>>("entry", mapOf("foo" to 42).entries.first())))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "foo42")
        }
    }

    @Test
    fun `should provide dependency as its supertype`() = runTest {
        // given
        val script = """
            text.uppercase()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<CharSequence>("text", "foo")
        }

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:6: error: Unresolved reference 'uppercase' on receiver of type 'CharSequence'.
                  | text.<error>uppercase</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should not report warnings of dependency accessor of type with redundant projection`() = runTest {
        // given
        val script = """
            c.compareTo("a")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<Comparable<in String>>("c", "b")
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
            have(warnings == null)
        }
    }

    @Test
    fun `should not report warnings of dependency accessor shadowed by local property`() = runTest {
        // given
        val script = """
            val numbers = numbers.size
            numbers
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<List<out Number>>("numbers", listOf(1, 2.0))
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
            have(warnings == null)
        }
    }

    @Test
    fun `should not report warnings of dependency accessor in compilation error`() = runTest {
        // given
        val script = """
            c.compareTo(42)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<Comparable<in String>>("c", "b")
        }

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:13: error: Argument type mismatch: actual type is 'Int', but 'CapturedType(in String)' was expected.
                  | c.compareTo(<error>42</error>)
            """.trimIndent()
        }
    }

    @Test
    fun `should not report warnings of dependency accessor together with runtime exception`() = runTest {
        // given
        val script = """
            val text = c as String
            error(text)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<Comparable<in String>>("c", "b")
        }

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: b
                  at <script body>(script.kts:2)
                  | error(text)
            """.trimIndent()
        }
    }

    @Test
    fun `should report warnings of header and declarations moved out of the script body together with dependency accessors`() = runTest {
        // given
        val script = """
            @file:OptIn()
            fun twice(x: Int) = x as Int * 2
            twice(c.compareTo("a"))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<Comparable<in String>>("c", "b")
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
            warnings sameAs """
                script.kts:1:1: warning: '@OptIn' without any arguments has no effect.
                  | <error>@file:OptIn()</error>

                script.kts:2:23: warning: Cast is redundant.
                  | fun twice(x: Int) = x <error>as Int</error> * 2
            """.trimIndent()
        }
    }

    @Test
    fun `should reject duplicate dependency name`() = runTest {
        // given
        val executor = ScriptExecutor()

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            executor.execute("foo") {
                "foo" provides 1
                "foo" provides 2
            }
        }

        // then
        assert(exception.message == "Duplicate dependency name: foo")
    }

    @Test
    fun `should reject duplicate dependency name in the list`() = runTest {
        // given
        val executor = ScriptExecutor()

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            executor.execute("foo", listOf(dependency("foo", 1), dependency("foo", 2)))
        }

        // then
        assert(exception.message == "Duplicate dependency name: foo")
    }

    @Test
    fun `should reject reserved dependency names`() = runTest {
        // given
        val executor = ScriptExecutor()

        // when
        val exceptions = listOf("_script", "_dependency_foo", "_ScriptDependencies").map { name ->
            assertFailsWith<IllegalArgumentException> {
                executor.execute("foo", listOf(dependency(name, 1)))
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Reserved dependency name: _script",
            "Reserved dependency name: _dependency_foo",
            "Reserved dependency name: _ScriptDependencies"
        ))
    }

    @Test
    fun `should reject dependency name which is not an identifier`() {
        // when
        val exceptions = listOf("foo`bar", "foo bar", "1foo", "").map { name ->
            assertFailsWith<IllegalArgumentException> {
                dependency(name, listOf(1))
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Dependency name is not a Kotlin identifier: 'foo`bar'",
            "Dependency name is not a Kotlin identifier: 'foo bar'",
            "Dependency name is not a Kotlin identifier: '1foo'",
            "Dependency name is not a Kotlin identifier: ''"
        ))
    }

    @Test
    fun `should reject dependency name which is a hard keyword`() {
        // when
        val exceptions = listOf("in", "class", "null").map { name ->
            assertFailsWith<IllegalArgumentException> {
                dependency(name, 1)
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Dependency name is a Kotlin keyword: 'in'",
            "Dependency name is a Kotlin keyword: 'class'",
            "Dependency name is a Kotlin keyword: 'null'"
        ))
    }

    @Test
    fun `should accept dependency names which are Kotlin identifiers`() {
        // when
        val dependencies = listOf("foo", "_foo", "fooBar1", "żółw", "value", "get").map { name ->
            dependency(name, 1)
        }

        // then
        assert(dependencies.map { it.name } == listOf("foo", "_foo", "fooBar1", "żółw", "value", "get"))
    }

    @Test
    fun `should reject dependency of local class`() {
        // given
        class Local

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            dependency("foo", listOf(Local()))
        }

        // then
        assert(exception.message == "Dependency 'foo' is of type ${Local::class.java.name}, which is not accessible to the script - provide it as a public type")
    }

    @Test
    fun `should reject dependency of private class`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            dependency("foo", Hidden())
        }

        // then
        assert(exception.message == "Dependency 'foo' is of type com.xemantic.kotlin.script.Hidden, which is not accessible to the script - provide it as a public type")
    }

    @Test
    fun `should reject dependency of internal class`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            dependency("foo", listOf(Internal()))
        }

        // then
        assert(exception.message == "Dependency 'foo' is of type com.xemantic.kotlin.script.Internal, which is not accessible to the script - provide it as a public type")
    }

    @Test
    fun `should reject null dependency of non-nullable type`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor.Dependency("foo", typeOf<String>(), null)
        }

        // then
        assert(exception.message == "Dependency 'foo' is null, while its type is not nullable: kotlin.String")
    }

    @Test
    fun `should reject dependency value which is not of its type`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor.Dependency("foo", typeOf<String>(), 42)
        }

        // then
        assert(exception.message == "Dependency 'foo' of type kotlin.String has a value of class java.lang.Integer")
    }

    @Test
    fun `should provide dependencies of value class types`() = runTest {
        // given
        val script = $$"""
            "${timeout.inWholeSeconds} ${count + 1u} ${delay?.inWholeSeconds}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "timeout" provides 5.seconds
            "count" provides 5u
            provide<Duration?>("delay", 2.seconds)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "5 6 2")
        }
    }

    @Test
    fun `should reject dependency of type parameter`() {
        // given
        val type = Pair<*, *>::first.returnType

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor.Dependency("foo", type, "bar")
        }

        // then
        assert(exception.message == "Dependency 'foo' is of type A, which is a type parameter - provide it as a concrete type")
    }

    @Test
    fun `should reject dependency of platform type`() {
        // given
        val type = Thread::getAllStackTraces.returnType

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor.Dependency("foo", type, Thread.getAllStackTraces())
        }

        // then
        assert(exception.message == "Dependency 'foo' is of platform type $type, which the script cannot refer to - provide it as a Kotlin type, e.g. with typeOf")
    }

    @Test
    fun `should reject dependency names resolved to the coroutine scope of the script body instead`() = runTest {
        // given
        val propertyNames = listOf("coroutineContext", "isActive", "javaClass")
        val functionNames = listOf(
            "launch", "async", "cancel", "ensureActive", "plus", "newCoroutineContext",
            "let", "run", "also", "apply", "takeIf", "takeUnless", "to", "runCatching", "toString", "hashCode", "equals"
        )
        val executor = ScriptExecutor()

        // when
        val exceptions = propertyNames.map { name ->
            assertFailsWith<IllegalArgumentException> {
                executor.execute("foo", listOf(dependency(name, 1)))
            }
        } + functionNames.map { name ->
            assertFailsWith<IllegalArgumentException> {
                executor.execute("foo", listOf(dependency<() -> Int>(name) { 1 }))
            }
        }

        // then
        assert(exceptions.map { it.message } == (propertyNames + functionNames).map {
            "Reserved dependency name: $it - it would be resolved to the CoroutineScope of the script body"
        })
    }

    @Test
    fun `should provide dependency named like function of the coroutine scope when it cannot be called`() = runTest {
        // given
        val script = $$"""
            "mail $to"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "to" provides "alice"
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "mail alice")
        }
    }

    @Test
    fun `should provide dependency of root package type to script referring to it`() = runTest {
        // given
        val script = """
            RootConfig(1).v + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(41)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependencies of root package types to script with package directive`() = runTest {
        // given
        val script = """
            package foo

            cfg.v + configs.getValue("a").sumOf { it?.v ?: 0 } + entries.single().config.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(1)
            "configs" provides mapOf("a" to listOf(RootConfig(39), null, RootConfig(1)))
            "entries" provides arrayOf(RootConfig.Entry(RootConfig(1)))
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type to script with package directive declaring class of the same name`() = runTest {
        // given
        val script = """
            package foo

            enum class RootConfig { A, B }
            cfg.v + RootConfig.B.ordinal
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(41)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type named like keyword in projected type`() = runTest {
        // given
        val script = """
            package foo

            sink.add(item)
            sink.size + item.v
        """.trimIndent()
        val executor = ScriptExecutor()
        val sink = mutableListOf<Any>()

        // when
        val result = executor.execute(script) {
            provide<MutableList<in `is`>>("sink", sink)
            "item" provides `is`(41)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
        assert(sink.single() is `is`)
    }

    @Test
    fun `should provide dependency of root package type to script importing class of the same name`() = runTest {
        // given
        val script = """
            import `in`.xemantic.RootConfig

            RootConfig("x").name + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "x42")
        }
    }

    @Test
    fun `should provide dependency of root package type to script importing it under alias`() = runTest {
        // given
        val script = """
            import RootConfig as Config

            Config(1).v + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(41)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type to script importing function under its name`() = runTest {
        // given
        val script = """
            import kotlin.collections.listOf as RootConfig

            val x: RootConfig = cfg
            RootConfig(x).single().v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type to script importing built-in class under its name`() = runTest {
        // given
        val script = """
            import kotlin.String as RootConfig

            val text: RootConfig = "x"
            text + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "x42")
        }
    }

    @Test
    fun `should provide dependency of root package type to script naming its import alias`() = runTest {
        // given
        val script = """
            package foo

            import kotlin.String as _root_RootConfig

            val text: _root_RootConfig = "x"
            text + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "x42")
        }
    }

    @Test
    fun `should run script declaring class shadowing root package type of dependency it does not refer to`() = runTest {
        // given
        val script = """
            enum class RootConfig { A, B }
            RootConfig.B.ordinal
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should report dependency of root package type shadowed by script class like regular script`() = runTest {
        // given
        val script = """
            enum class RootConfig { A }
            cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:5: error: Unresolved reference 'v' on receiver of type 'RootConfig'.
                  | cfg.<error>v</error>
            """.trimIndent()
        }
    }

}
