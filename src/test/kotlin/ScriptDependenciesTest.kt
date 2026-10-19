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
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.test.runTest
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
        val exceptions = listOf("_script", "_dependency_foo").map { name ->
            assertFailsWith<IllegalArgumentException> {
                executor.execute("foo", listOf(dependency(name, 1)))
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Reserved dependency name: _script",
            "Reserved dependency name: _dependency_foo"
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

}
