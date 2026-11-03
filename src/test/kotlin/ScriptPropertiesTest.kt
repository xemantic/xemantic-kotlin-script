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
import `in`.xemantic.Box
import `is`
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import java.net.URLClassLoader
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

private class Hidden

internal class Internal

class Outer {
    class Box<T>(val items: List<T>)
}

class Isolated

/**
 * A property of the [Isolated] class loaded by another class loader, so the snippets see the class of the same name,
 * loaded by the class loader of the session, which its value cannot be cast to.
 */
private fun isolatedProperty(): ScriptProperty<Any> {
    val loader = URLClassLoader(
        arrayOf(Isolated::class.java.protectionDomain.codeSource.location),
        ClassLoader.getPlatformClassLoader()
    )
    val isolated = loader.loadClass(Isolated::class.java.name)
    return ScriptProperty("isolated", isolated.kotlin.createType(), isolated.getDeclaredConstructor().newInstance())
}

class ScriptPropertiesTest {

    @Test
    fun `should provide properties of inferred types`() = runTest {
        ScriptSession {
            "greeting" provides "Hello"
            "names" provides listOf("foo", "bar")
        }.use { session ->
            // when
            val result = session.execute($$"""
                "$greeting ${names.joinToString { it.uppercase() }}"
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "Hello FOO, BAR")
            }
        }
    }

    @Test
    fun `should keep properties in all snippets of the session`() = runTest {
        ScriptSession {
            "base" provides 10
        }.use { session ->
            // given
            session.execute("fun scaled(x: Int) = x * base")

            // when
            val result = session.execute("scaled(2) + base")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 30)
            }
        }
    }

    @Test
    fun `should provide properties from the list`() = runTest {
        ScriptSession(listOf(ScriptProperty("injected", typeOf<String>(), "foo"))).use { session ->
            // when
            val result = session.execute("injected.uppercase()")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "FOO")
            }
        }
    }

    @Test
    fun `should provide property of nullable type`() = runTest {
        ScriptSession {
            provide<String?>("name", null)
        }.use { session ->
            // when
            val result = session.execute("name ?: \"nobody\"")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "nobody")
            }
        }
    }

    @Test
    fun `should provide property of function type`() = runTest {
        ScriptSession {
            "twice" provides { x: Int -> x * 2 }
        }.use { session ->
            // when
            val result = session.execute("twice(21)")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 42)
            }
        }
    }

    @Test
    fun `should provide property of generic nested class`() = runTest {
        ScriptSession(listOf(property("box", Outer.Box(listOf(1, 2))))).use { session ->
            // when
            val result = session.execute("box.items.size")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 2)
            }
        }
    }

    @Test
    fun `should provide property of generic nested interface with projection`() = runTest {
        ScriptSession {
            provide<Map.Entry<String, Int>>("entry", mapOf("foo" to 42).entries.first())
            provide<MutableList<out Number>>("numbers", mutableListOf(1, 2.5))
            provide<List<*>>("anything", listOf("x"))
        }.use { session ->
            // when
            val result = session.execute("entry.key + entry.value + numbers.size + anything.size")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "foo4221")
            }
        }
    }

    @Test
    fun `should provide property as its supertype`() = runTest {
        ScriptSession {
            provide<CharSequence>("text", "foo")
        }.use { session ->
            // when
            val result = session.execute("text.uppercase()")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    snippet1.kts:1:6: error: Unresolved reference 'uppercase' on receiver of type 'CharSequence'.
                      | text.<error>uppercase</error>()
                """.trimIndent()
            }
        }
    }

    @Test
    fun `should provide properties of value class types`() = runTest {
        ScriptSession {
            "timeout" provides 5.seconds
            "count" provides 5u
            provide<Duration?>("delay", 2.seconds)
            "isEnabled" provides 1u
        }.use { session ->
            // when
            val result = session.execute($$"""
                "${timeout.inWholeSeconds} ${count + 1u} ${delay?.inWholeSeconds} $isEnabled"
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "5 6 2 1")
            }
        }
    }

    @Test
    fun `should provide properties of value class types to functions of later snippets`() = runTest {
        ScriptSession {
            "timeout" provides 5.seconds
        }.use { session ->
            // given
            session.execute("fun doubled(): Long = timeout.inWholeSeconds * 2")

            // when
            val result = session.execute("doubled() + timeout.inWholeSeconds")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 15L)
            }
        }
    }

    @Test
    fun `should provide properties of types named like keywords and in the root package`() = runTest {
        ScriptSession {
            "box" provides Box(1)
            "config" provides RootConfig.Entry(RootConfig(2))
            "keyword" provides `is`(3)
        }.use { session ->
            // when
            val result = session.execute("box.value + config.config.v + keyword.v")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 6)
            }
        }
    }

    @Test
    fun `should provide property named like hard keyword in backticks`() = runTest {
        ScriptSession {
            "get" provides 1
            "żółw" provides 2
        }.use { session ->
            // when
            val result = session.execute("get + żółw")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 3)
            }
        }
    }

    @Test
    fun `should let snippet redeclare property`() = runTest {
        ScriptSession {
            "name" provides "property"
        }.use { session ->
            // given
            session.execute("val name = \"snippet\"")

            // when
            val result = session.execute("name")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "snippet")
            }
        }
    }

    @Test
    fun `should report property which fails to compile`() = runTest {
        // given
        // validated up front, while the compiler rejects the type argument out of the bounds of Enum<E : Enum<E>>
        val type = Enum::class.createType(listOf(KTypeProjection.invariant(typeOf<String>())))
        ScriptSession(listOf(ScriptProperty("level", type, DeprecationLevel.WARNING))).use { session ->
            // when
            val result = session.execute("42")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                have(message.startsWith("The properties cannot be declared:\nproperties.kts:1:"))
                have("kotlin.Enum<kotlin.String>" in message)
            }
        }
    }

    @Test
    fun `should report property which fails to evaluate`() = runTest {
        ScriptSession(listOf(isolatedProperty())).use { session ->
            // when
            val result = session.execute("42")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.EVALUATION)
                have(message.startsWith(
                    "The properties cannot be declared:\n" +
                            "java.lang.ClassCastException: class com.xemantic.kotlin.script.Isolated cannot be cast to class com.xemantic.kotlin.script.Isolated"
                ))
            }
        }
    }

    @Test
    fun `should report failed declaration of properties on every execution`() = runTest {
        ScriptSession(listOf(isolatedProperty())).use { session ->
            // given
            val first = session.execute("isolated")

            // when
            val next = session.execute("isolated")

            // then
            first should {
                be<ScriptResult.Error>()
                have(message.startsWith("The properties cannot be declared:\n"))
            }
            next should {
                be<ScriptResult.Error>()
                have(phase == (first as ScriptResult.Error).phase)
                have(message == first.message)
            }
        }
    }

    @Test
    fun `should reject duplicate property name`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptSession {
                "foo" provides 1
                "foo" provides 2
            }
        }

        // then
        assert(exception.message == "Duplicate property name: foo")
    }

    @Test
    fun `should reject property names of the members of the script scope`() {
        // when
        val exceptions = listOf("coroutineContext", "await").map { name ->
            assertFailsWith<IllegalArgumentException> {
                ScriptSession(listOf(property(name, 1)))
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf("coroutineContext", "await").map {
            "Reserved property name: $it - it is a member of the ScriptScope receiving the snippets"
        })
    }

    @Test
    fun `should reject property name which is not an identifier`() {
        // when
        val exceptions = listOf("foo`bar", "foo bar", "1foo", "").map { name ->
            assertFailsWith<IllegalArgumentException> {
                property(name, listOf(1))
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Property name is not a Kotlin identifier: 'foo`bar'",
            "Property name is not a Kotlin identifier: 'foo bar'",
            "Property name is not a Kotlin identifier: '1foo'",
            "Property name is not a Kotlin identifier: ''"
        ))
    }

    @Test
    fun `should reject property name which is a hard keyword`() {
        // when
        val exceptions = listOf("in", "class", "null").map { name ->
            assertFailsWith<IllegalArgumentException> {
                property(name, 1)
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Property name is a Kotlin keyword: 'in'",
            "Property name is a Kotlin keyword: 'class'",
            "Property name is a Kotlin keyword: 'null'"
        ))
    }

    @Test
    fun `should reject property of local class`() {
        // given
        class Local

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            property("foo", listOf(Local()))
        }

        // then
        assert(exception.message == "Property 'foo' is of type ${Local::class.java.name}, which is not accessible to the snippets - provide it as a public type")
    }

    @Test
    fun `should reject property of private class`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            property("foo", Hidden())
        }

        // then
        assert(exception.message == "Property 'foo' is of type com.xemantic.kotlin.script.Hidden, which is not accessible to the snippets - provide it as a public type")
    }

    @Test
    fun `should reject property of internal class`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            property("foo", listOf(Internal()))
        }

        // then
        assert(exception.message == "Property 'foo' is of type com.xemantic.kotlin.script.Internal, which is not accessible to the snippets - provide it as a public type")
    }

    @Test
    fun `should reject null property of non-nullable type`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("foo", typeOf<String>(), null)
        }

        // then
        assert(exception.message == "Property 'foo' is null, while its type is not nullable: kotlin.String")
    }

    @Test
    fun `should reject property value which is not of its type`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("foo", typeOf<String>(), 42)
        }

        // then
        assert(exception.message == "Property 'foo' of type kotlin.String has a value of class java.lang.Integer")
    }

    @Test
    fun `should reject property of type parameter`() {
        // given
        val type = Pair<*, *>::first.returnType

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("foo", type, "bar")
        }

        // then
        assert(exception.message == "Property 'foo' is of type A, which is a type parameter - provide it as a concrete type")
    }

    @Test
    fun `should reject property of platform type`() {
        // given
        val type = Thread::getAllStackTraces.returnType

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("foo", type, Thread.getAllStackTraces())
        }

        // then
        assert(exception.message == "Property 'foo' is of platform type $type, which the snippets cannot refer to - provide it as a Kotlin type, e.g. with typeOf")
    }

}
