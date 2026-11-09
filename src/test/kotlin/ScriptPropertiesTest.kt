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
import kotlin.reflect.KClassifier
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction23
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
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

typealias MutableItems = MutableList<String>

typealias Items<T> = MutableList<T>

typealias Transform = (Int) -> Int

class IndexHolder {
    val index: MutableMap<String, List<Int>> = mutableMapOf()
}

/** The types of the members keep the typealiases, unlike the ones of [typeOf]. */
class AliasHolder {
    val items: MutableItems = mutableListOf()
    val nested: List<MutableItems> = listOf()
    val nullable: MutableItems? = null
    val generic: Items<Transform> = mutableListOf()
    val map: Map<Transform, MutableItems> = mapOf()
}

/** The type arguments of an inner class and of its generic outer classes are rendered after their own class names. */
class Generic<T>(val value: T) {

    inner class Inner<U>(val extra: U) {

        val pair: Pair<T, U> get() = value to extra

        inner class Deep<V>(val more: V) {
            val triple: Triple<T, U, V> get() = Triple(value, extra, more)
        }

    }

    inner class Plain {
        val outerValue: T get() = value
    }

    class Nested<U>(val item: U)

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
    fun `should hint declaring the type when property is unresolved in declaration of inferred type`() = runTest {
        ScriptSession {
            "base" provides 10
        }.use { session ->
            // when
            val result = session.execute("""
                fun doubled() = base * 2
                doubled()
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    snippet1.kts:1:17: error: Unresolved reference 'base'.
                      | fun doubled() = <error>base</error> * 2
                      hint: 'base' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = base.toString()` - declare the type, like `fun f(): String = base.toString()`.
                """.trimIndent()
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
    fun `should provide properties of mutable collection types`() = runTest {
        ScriptSession {
            provide<MutableList<String>>("items", mutableListOf())
            provide<MutableMap<String, MutableSet<Int>>>("index", mutableMapOf())
        }.use { session ->
            // when
            val result = session.execute($$"""
                items.add("foo")
                index.getOrPut("foo") { mutableSetOf() }.add(42)
                "$items $index"
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "[foo] {foo=[42]}")
            }
        }
    }

    @Test
    fun `should render mutable collection types in source`() {
        // given
        val types = listOf(
            typeOf<MutableIterable<String>>(),
            typeOf<MutableCollection<String>>(),
            typeOf<MutableList<String>>(),
            typeOf<MutableSet<String>>(),
            typeOf<MutableMap<String, Int>>(),
            typeOf<MutableMap.MutableEntry<String, Int>>(),
            typeOf<MutableIterator<String>>(),
            typeOf<MutableListIterator<String>>(),
            typeOf<MutableList<*>?>(),
            typeOf<Map<String, MutableList<out Number>?>>(),
            typeOf<(MutableSet<Int>) -> MutableIterator<Int>>(),
            typeOf<MutableItems>(),
            IndexHolder::index.returnType
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        assert(sources == listOf(
            "kotlin.collections.MutableIterable<kotlin.String>",
            "kotlin.collections.MutableCollection<kotlin.String>",
            "kotlin.collections.MutableList<kotlin.String>",
            "kotlin.collections.MutableSet<kotlin.String>",
            "kotlin.collections.MutableMap<kotlin.String, kotlin.Int>",
            "kotlin.collections.MutableMap.MutableEntry<kotlin.String, kotlin.Int>",
            "kotlin.collections.MutableIterator<kotlin.String>",
            "kotlin.collections.MutableListIterator<kotlin.String>",
            "kotlin.collections.MutableList<*>?",
            "kotlin.collections.Map<kotlin.String, kotlin.collections.MutableList<out kotlin.Number>?>",
            "kotlin.Function1<kotlin.collections.MutableSet<kotlin.Int>, kotlin.collections.MutableIterator<kotlin.Int>>",
            "kotlin.collections.MutableList<kotlin.String>",
            "kotlin.collections.MutableMap<kotlin.String, kotlin.collections.List<kotlin.Int>>"
        ))
    }

    @Test
    fun `should provide property of typealiased mutable collection type`() = runTest {
        ScriptSession(listOf(ScriptProperty("items", AliasHolder::items.returnType, mutableListOf<String>()))).use { session ->
            // when
            val result = session.execute("""
                items.add("foo")
                items
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == listOf("foo"))
            }
        }
    }

    @Test
    fun `should render typealiased mutable collection types in source`() {
        // given
        val types = listOf(
            AliasHolder::items.returnType,
            AliasHolder::nested.returnType,
            AliasHolder::nullable.returnType,
            AliasHolder::generic.returnType,
            AliasHolder::map.returnType
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        assert(sources == listOf(
            "kotlin.collections.MutableList<kotlin.String>",
            "kotlin.collections.List<kotlin.collections.MutableList<kotlin.String>>",
            "kotlin.collections.MutableList<kotlin.String>?",
            "kotlin.collections.MutableList<kotlin.Function1<kotlin.Int, kotlin.Int>>",
            "kotlin.collections.Map<kotlin.Function1<kotlin.Int, kotlin.Int>, kotlin.collections.MutableList<kotlin.String>>"
        ))
    }

    @Test
    fun `should render read-only collection types in source`() {
        // given
        val types = listOf(
            typeOf<Iterable<String>>(),
            typeOf<Collection<String>>(),
            typeOf<List<String>>(),
            typeOf<Set<String>>(),
            typeOf<Map<String, Int>>(),
            typeOf<Map.Entry<String, Int>>(),
            typeOf<Iterator<String>>(),
            typeOf<ListIterator<String>>(),
            typeOf<java.util.ArrayList<String>>()
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        assert(sources == listOf(
            "kotlin.collections.Iterable<kotlin.String>",
            "kotlin.collections.Collection<kotlin.String>",
            "kotlin.collections.List<kotlin.String>",
            "kotlin.collections.Set<kotlin.String>",
            "kotlin.collections.Map<kotlin.String, kotlin.Int>",
            "kotlin.collections.Map.Entry<kotlin.String, kotlin.Int>",
            "kotlin.collections.Iterator<kotlin.String>",
            "kotlin.collections.ListIterator<kotlin.String>",
            "java.util.ArrayList<kotlin.String>"
        ))
    }

    @Test
    fun `should render type arguments of inner class of generic class in source`() {
        // given
        val types = listOf(
            typeOf<Generic<String>.Inner<Int>>(),
            typeOf<Generic<String>.Inner<Int>?>(),
            typeOf<Generic<List<String>>.Inner<Int?>>(),
            typeOf<Generic<String>.Inner<Int>.Deep<Boolean>>(),
            typeOf<Generic<String>.Plain>(),
            typeOf<Generic<String>.Plain?>(),
            typeOf<Generic.Nested<Int>>(),
            typeOf<Generic<*>.Inner<out Number>>()
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        assert(sources == listOf(
            "com.xemantic.kotlin.script.Generic<kotlin.String>.Inner<kotlin.Int>",
            "com.xemantic.kotlin.script.Generic<kotlin.String>.Inner<kotlin.Int>?",
            "com.xemantic.kotlin.script.Generic<kotlin.collections.List<kotlin.String>>.Inner<kotlin.Int?>",
            "com.xemantic.kotlin.script.Generic<kotlin.String>.Inner<kotlin.Int>.Deep<kotlin.Boolean>",
            "com.xemantic.kotlin.script.Generic<kotlin.String>.Plain",
            "com.xemantic.kotlin.script.Generic<kotlin.String>.Plain?",
            "com.xemantic.kotlin.script.Generic.Nested<kotlin.Int>",
            "com.xemantic.kotlin.script.Generic<*>.Inner<out kotlin.Number>"
        ))
    }

    @Test
    fun `should provide properties of inner classes of generic class`() = runTest {
        // given
        val generic = Generic("foo")
        ScriptSession {
            "inner" provides generic.Inner(42)
            "deep" provides generic.Inner(42).Deep(true)
            "plain" provides generic.Plain()
            provide<Generic<String>.Inner<Int>?>("missing", null)
        }.use { session ->
            // when
            val result = session.execute("""
                listOf(inner.pair, deep.triple, plain.outerValue, missing?.pair)
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == listOf("foo" to 42, Triple("foo", 42, true), "foo", null))
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
        ScriptSession(listOf(property("first", "x"), isolatedProperty(), property("last", "y"))).use { session ->
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
                // the properties left uninitialized by the failed declaration are the ones of the host, which the caller cannot redeclare
                have(message.lines().none { it.trimStart().startsWith("hint:") })
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

    /** See #157. */
    @Test
    fun `should release compiler of failed declaration of properties`() = runTest {
        DefaultScriptSession(listOf(isolatedProperty()), Dispatchers.Default).use { session ->
            // when
            val result = session.execute("42")

            // then
            // nothing can be compiled in the compiler of the failed declaration, so only its failure is kept
            assert(result is ScriptResult.Error && session.repl == null)
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
    fun `should reject read-only collection value of mutable collection type`() {
        // given
        val properties = listOf(
            Triple("list", typeOf<MutableList<String>>(), emptyList<String>()),
            Triple("map", typeOf<MutableMap<String, Int>>(), emptyMap<String, Int>()),
            Triple("collection", typeOf<MutableCollection<String>>(), emptySet<String>()),
            Triple("aliased", AliasHolder::items.returnType, emptyList<String>())
        )

        // when
        val messages = properties.map { (name, type, value) ->
            assertFailsWith<IllegalArgumentException> {
                ScriptProperty(name, type, value)
            }.message
        }

        // then
        assert(messages == listOf(
            "Property 'list' of type kotlin.collections.MutableList<kotlin.String> has a value of class kotlin.collections.EmptyList, which does not implement kotlin.collections.MutableList",
            "Property 'map' of type kotlin.collections.MutableMap<kotlin.String, kotlin.Int> has a value of class kotlin.collections.EmptyMap, which does not implement kotlin.collections.MutableMap",
            "Property 'collection' of type kotlin.collections.MutableCollection<kotlin.String> has a value of class kotlin.collections.EmptySet, which does not implement kotlin.collections.MutableCollection",
            "Property 'aliased' of type com.xemantic.kotlin.script.MutableItems /* = kotlin.collections.MutableList<kotlin.String> */ has a value of class kotlin.collections.EmptyList, which does not implement kotlin.collections.MutableList"
        ))
    }

    /** The list of `listOf("a", "b")` is a `java.util.Arrays.ArrayList`, which cannot grow, but can be modified in place. */
    @Test
    fun `should accept Java collection value of mutable collection type`() = runTest {
        ScriptSession(listOf(ScriptProperty("items", typeOf<MutableList<String>>(), listOf("a", "b")))).use { session ->
            // when
            val result = session.execute("""
                items[0] = "c"
                items
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == listOf("c", "b"))
            }
        }
    }

    /** The result of `buildList` implements `MutableList`, but is frozen, which is not detected. */
    @Test
    fun `should accept frozen collection value of mutable collection type`() = runTest {
        ScriptSession(listOf(ScriptProperty("items", typeOf<MutableList<String>>(), buildList { add("a") }))).use { session ->
            // when
            val result = session.execute("""
                items.add("b")
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.EVALUATION)
                have(UnsupportedOperationException::class.qualifiedName!! in message)
            }
        }
    }

    /** The type arguments are erased at runtime, so only the class of the value is checked, not the ones of its elements. */
    @Test
    fun `should accept nested read-only collection value of nested mutable collection type`() = runTest {
        ScriptSession(listOf(ScriptProperty("index", typeOf<MutableMap<String, MutableSet<Int>>>(), mutableMapOf("a" to emptySet<Int>())))).use { session ->
            // when
            val result = session.execute("""
                index["a"]!!.add(1)
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.EVALUATION)
                have(UnsupportedOperationException::class.qualifiedName!! in message)
            }
        }
    }

    /** The type implemented by hand is seen by the members of the [KType] interface only, so it is read-only. */
    @Test
    fun `should accept property of hand-written collection type`() {
        // given
        val type = object : KType {
            override val classifier = List::class
            override val arguments = listOf(KTypeProjection.invariant(typeOf<String>()))
            override val isMarkedNullable = false
            override val annotations = emptyList<Annotation>()
            override fun toString() = "kotlin.collections.List<kotlin.String>"
        }

        // when
        val property = ScriptProperty("items", type, emptyList<String>())

        // then
        assert(property.type.toSource() == "kotlin.collections.List<kotlin.String>")
    }

    @Test
    fun `should reject property of hand-written type without class`() {
        // given
        val classless = handWrittenType(classifier = null, name = "Classless")
        val types = listOf(
            classless,
            handWrittenType(List::class, listOf(KTypeProjection.covariant(classless)), "kotlin.collections.List<out Classless>")
        )

        // when
        val exceptions = types.map { type ->
            assertFailsWith<IllegalArgumentException> {
                ScriptProperty("foo", type, "bar")
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Property 'foo' is of type Classless, which is not of a class - provide it as a class type, e.g. with typeOf",
            "Property 'foo' is of type Classless, which is not of a class - provide it as a class type, e.g. with typeOf"
        ))
    }

    @Test
    fun `should reject property of hand-written type with type arguments not matching its class`() {
        // given
        val int = KTypeProjection.invariant(typeOf<Int>())
        val inner = handWrittenType(Generic.Inner::class, listOf(int), "Generic.Inner<kotlin.Int>")
        val types = listOf(
            inner,
            handWrittenType(List::class, emptyList(), "kotlin.collections.List"),
            handWrittenType(String::class, listOf(int), "kotlin.String<kotlin.Int>"),
            handWrittenType(List::class, listOf(KTypeProjection.invariant(inner)), "kotlin.collections.List<Generic.Inner<kotlin.Int>>")
        )

        // when
        val exceptions = types.map { type ->
            assertFailsWith<IllegalArgumentException> {
                ScriptProperty("foo", type, null)
            }
        }

        // then
        assert(exceptions.map { it.message } == listOf(
            "Property 'foo' is of type Generic.Inner<kotlin.Int>, which has 1 type argument, while com.xemantic.kotlin.script.Generic.Inner takes 2 - provide it as a complete type, e.g. with typeOf",
            "Property 'foo' is of type kotlin.collections.List, which has 0 type arguments, while kotlin.collections.List takes 1 - provide it as a complete type, e.g. with typeOf",
            "Property 'foo' is of type kotlin.String<kotlin.Int>, which has 1 type argument, while kotlin.String takes 0 - provide it as a complete type, e.g. with typeOf",
            "Property 'foo' is of type Generic.Inner<kotlin.Int>, which has 1 type argument, while com.xemantic.kotlin.script.Generic.Inner takes 2 - provide it as a complete type, e.g. with typeOf"
        ))
    }

    /** The classifier of a function reference type, like `KFunction1<String, Int>`, is `KFunction`, which takes only the return type. */
    @Test
    fun `should accept properties of function reference types`() {
        // given
        val types = listOf(
            typeOf<KFunction1<String, Int>>(),
            typeOf<KSuspendFunction1<String, Int>>(),
            typeOf<KFunction23<Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Unit>>()
        )

        // when
        val properties = types.map { ScriptProperty("f", it, ::twice) }

        // then
        assert(properties.map { it.type } == types)
    }

    /** The classifier of a function type of more than 22 parameters is `FunctionN`, which takes only the return type. */
    @Test
    fun `should accept property of function type of more than 22 parameters`() {
        // given
        val type = typeOf<((Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit)?>()

        // when
        val property = ScriptProperty("f", type, null)

        // then
        assert(property.type == type)
    }

    /** The accessibility is checked first, as the reflection of a synthetic class, like the one of a lambda, may fail. */
    @Test
    fun `should reject property of hand-written type of lambda class before its type arguments`() {
        // given
        val lambda = suspend { 42 }
        val type = handWrittenType(lambda::class, listOf(KTypeProjection.invariant(typeOf<Int>())), "Lambda<kotlin.Int>")

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("foo", type, lambda)
        }

        // then
        assert(exception.message == "Property 'foo' is of type ${lambda::class.java.name}, which is not accessible to the snippets - provide it as a public type")
    }

    @Test
    fun `should accept property of hand-written type of inner class with type arguments of its outer classes`() {
        // given
        val type = handWrittenType(
            Generic.Inner.Deep::class,
            listOf(typeOf<Boolean>(), typeOf<Int>(), typeOf<String>()).map { KTypeProjection.invariant(it) },
            "Generic<kotlin.String>.Inner<kotlin.Int>.Deep<kotlin.Boolean>"
        )

        // when
        val property = ScriptProperty("deep", type, null)

        // then
        assert(property.type.toSource() == "com.xemantic.kotlin.script.Generic<kotlin.String>.Inner<kotlin.Int>.Deep<kotlin.Boolean>?")
    }

    /** `MutableList::class` is `List::class`, so only [typeOf] and the types of the members keep the mutability. */
    @Test
    fun `should render type created from mutable collection class as read-only`() {
        // when
        val type = MutableList::class.createType(listOf(KTypeProjection.invariant(typeOf<String>())))

        // then
        assert(type.toSource() == "kotlin.collections.List<kotlin.String>")
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

@Suppress("unused") // referenced as a value of function reference types
private fun twice(x: String): Int = x.length * 2

/** A nullable [KType] implemented by hand, seen by the members of the interface only. */
private fun handWrittenType(
    classifier: KClassifier?,
    arguments: List<KTypeProjection> = emptyList(),
    name: String
): KType = object : KType {
    override val classifier = classifier
    override val arguments = arguments
    override val isMarkedNullable = true
    override val annotations = emptyList<Annotation>()
    override fun toString() = name
}
