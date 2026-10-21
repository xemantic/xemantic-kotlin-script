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
import `in`
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
    fun `should provide dependency of root package type shadowed by script class`() = runTest {
        // given
        val script = """
            enum class RootConfig { A }
            configs.first().v + RootConfig.A.ordinal
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "configs" provides listOf(RootConfig(42))
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type shadowed by script class to script referring to other root package class`() = runTest {
        // given
        val script = """
            interface RootConfig
            RootLabel("x").text + cfg.v
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
    fun `should provide dependency of root package type shadowed by script class after file annotation and import`() = runTest {
        // given
        val script = """
            @file:Suppress("UNUSED_VARIABLE") import kotlin.math.abs
            object RootConfig
            abs(-cfg.v)
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
    fun `should provide dependency of root package type shadowed by script class to script naming file facade of root package`() = runTest {
        // given
        val script = """
            // see RootPackageKt
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
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of root package type named like keyword shadowed by script class`() = runTest {
        // given
        val script = """
            object RootConfig
            box.v + cfg.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "box" provides `in`(1)
            "cfg" provides RootConfig(41)
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should report compilation error of script with class shadowing root package type of dependency`() = runTest {
        // given
        val script = """
            typealias RootConfig = String
            val x: RootConfig = cfg
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
                script.kts:2:19: error: Initializer type mismatch: expected 'String', actual 'RootConfig'.
                  | val x: RootConfig <error>=</error> cfg
            """.trimIndent()
        }
    }

    @Test
    fun `should report exception of script with class shadowing root package type of dependency`() = runTest {
        // given
        val script = """
            enum class RootConfig { A }
            class Failure(val code: Int) : RuntimeException("failure ${'$'}code")
            fun fail(): Nothing = throw Failure(cfg.v)
            fail()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "cfg" provides RootConfig(42)
        }

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                Failure: failure 42
                  at fail(script.kts:3)
                  | fun fail(): Nothing = throw Failure(cfg.v)
                  at <script body>(script.kts:4)
                  | fail()
            """.trimIndent()
        }
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
    fun `should provide dependency of root package type to script importing other class under its alias`() = runTest {
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
    fun `should provide dependency of root package type shadowed by script class in package`() = runTest {
        // given
        val script = """
            package foo

            enum class RootConfig { A }
            cfg.v + RootConfig.A.ordinal
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
    fun `should provide dependency of root package type to script with package directive`() = runTest {
        // given
        val script = """
            package foo

            import RootConfig
import `in`

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
    fun `should provide dependency of nullable root package type`() = runTest {
        // given
        val script = """
            package foo

            object RootConfig
            cfg?.v ?: 0
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            provide<RootConfig?>("cfg", RootConfig(42))
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependency of generic type with root package type arguments`() = runTest {
        // given
        val script = """
            package foo

            interface RootConfig
            configs.getValue("a").sumOf { it?.v ?: 0 } + entries.single().config.v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "configs" provides mapOf("a" to listOf(RootConfig(40), null, RootConfig(1)))
            "entries" provides arrayOf(RootConfig.Entry(RootConfig(1)))
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should provide dependencies of types whose packages are named like script declarations`() = runTest {
        // given
        val script = """
            class com
            object kotlin
            box.items.size + text.length
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script) {
            "box" provides Outer.Box(listOf(1, 2))
            "text" provides "foo"
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 5)
        }
    }

}
