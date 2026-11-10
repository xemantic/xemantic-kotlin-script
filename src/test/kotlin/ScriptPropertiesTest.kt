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
import kotlin.jvm.functions.FunctionN
import kotlin.reflect.KClassifier
import kotlin.reflect.KFunction
import kotlin.reflect.KFunction0
import kotlin.reflect.KFunction1
import kotlin.reflect.KFunction2
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

        val values = listOf(::twice, ::suspendTwice, ::ignore23)

        // when
        val properties = types.zip(values) { type, value -> ScriptProperty("f", type, value) }

        // then
        assert(properties.map { it.type } == types)
    }

    /**
     * `KFunction` takes at least the return type, and `FunctionN` either only the return type, as `FunctionN<R>`,
     * or the invariant parameter types and the return type of a function type of more than 22 parameters.
     */
    @Test
    fun `should reject property of hand-written type of function reference or function type class with wrong arguments`() {
        // given
        val int = KTypeProjection.invariant(typeOf<Int>())
        val kFunction = handWrittenType(KFunction::class, emptyList(), "kotlin.reflect.KFunction")
        val types = listOf(
            kFunction,
            handWrittenType(List::class, listOf(KTypeProjection.covariant(kFunction)), "kotlin.collections.List<out kotlin.reflect.KFunction>"),
            handWrittenType(FunctionN::class, emptyList(), "kotlin.jvm.functions.FunctionN"),
            handWrittenType(FunctionN::class, List(3) { int }, "kotlin.jvm.functions.FunctionN<kotlin.Int, kotlin.Int, kotlin.Int>"),
            handWrittenType(FunctionN::class, List(23) { KTypeProjection.contravariant(typeOf<Int>()) } + int, "FunctionN<in kotlin.Int, …, kotlin.Int>")
        )

        // when
        val exceptions = types.map { type ->
            assertFailsWith<IllegalArgumentException> {
                ScriptProperty("foo", type, null)
            }
        }

        // then
        val kFunctionTakes = "while kotlin.reflect.KFunction takes 1 or more - provide it as a complete type, e.g. with typeOf"
        val functionNTakes = "while kotlin.jvm.functions.FunctionN takes 1, or 24 or more invariant ones of a function type - provide it as a complete type, e.g. with typeOf"
        assert(exceptions.map { it.message } == listOf(
            "Property 'foo' is of type kotlin.reflect.KFunction, which has 0 type arguments, $kFunctionTakes",
            "Property 'foo' is of type kotlin.reflect.KFunction, which has 0 type arguments, $kFunctionTakes",
            "Property 'foo' is of type kotlin.jvm.functions.FunctionN, which has 0 type arguments, $functionNTakes",
            "Property 'foo' is of type kotlin.jvm.functions.FunctionN<kotlin.Int, kotlin.Int, kotlin.Int>, which has 3 type arguments, $functionNTakes",
            "Property 'foo' is of type FunctionN<in kotlin.Int, …, kotlin.Int>, which has 24 type arguments, $functionNTakes"
        ))
    }

    @Test
    fun `should provide property of FunctionN type of the return type only`() = runTest {
        // given
        val type = typeOf<FunctionN<Int>?>()
        ScriptSession(listOf(ScriptProperty("f", type, null))).use { session ->
            // when
            val result = session.execute("f?.arity")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == null)
            }
        }
    }

    /** A file facade, like `CollectionsKt`, is public in the bytecode, but not a class the source could refer to. */
    @Test
    fun `should reject property of hand-written type of file facade`() {
        // given
        val facades = listOf(
            Class.forName("kotlin.collections.CollectionsKt"),
            Class.forName("com.xemantic.kotlin.script.ScriptPropertiesTestKt")
        )

        // when
        val exceptions = facades.map { facade ->
            assertFailsWith<IllegalArgumentException> {
                ScriptProperty("foo", handWrittenType(facade.kotlin, emptyList(), facade.name), null)
            }
        }

        // then
        assert(exceptions.map { it.message } == facades.map {
            "Property 'foo' is of type ${it.name}, which is not accessible to the snippets - provide it as a public type"
        })
    }

    @Test
    fun `should reject value of function type of more than 22 parameters of another arity`() {
        // given
        val type = typeOf<(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit>()
        val value: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit =
            { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }

        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptProperty("f", type, value)
        }

        // then
        assert(exception.message == "Property 'f' of type $type has a value of class ${value::class.java.name}, which takes 24 parameters, while the type takes 23")
    }

    /** The arity of a lambda of more than 22 parameters is told by its `invoke` method, of a function reference by `getArity`. */
    @Test
    fun `should accept value of function type of more than 22 parameters of the same arity`() {
        // given
        val lambda: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit =
            { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }
        val reference: (Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit =
            ::ignore23

        // when
        val properties = listOf(property("lambda", lambda), property("reference", reference))

        // then
        assert(properties.map { it.value } == listOf(lambda, reference))
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

    /** A function reference type is told apart from a suspend one by the value of the property, see [ScriptProperty.type]. */
    @Test
    fun `should render function reference types told apart by the value in source`() {
        // given
        val types = listOf(
            typeOf<KFunction1<String, Int>>() to false,
            typeOf<KFunction2<String, List<Int>, Int>?>() to false,
            typeOf<KFunction23<Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Unit>>() to false,
            typeOf<KSuspendFunction1<String, Int>>() to true,
            typeOf<KSuspendFunction1<String, Int>?>() to true
        )

        // when
        val sources = types.map { (type, isSuspend) -> type.toSource(isSuspend) }

        // then
        assert(sources == listOf(
            "kotlin.reflect.KFunction1<kotlin.String, kotlin.Int>",
            "kotlin.reflect.KFunction2<kotlin.String, kotlin.collections.List<kotlin.Int>, kotlin.Int>?",
            "kotlin.reflect.KFunction23<${List(22) { "kotlin.Int" }.joinToString()}, kotlin.Int, kotlin.Unit>",
            "kotlin.reflect.KSuspendFunction1<kotlin.String, kotlin.Int>",
            "kotlin.reflect.KSuspendFunction1<kotlin.String, kotlin.Int>?"
        ))
    }

    /**
     * A function reference type not told apart from a suspend one by the value, like a nested one, is rendered as `KFunction<R>`,
     * which a reference of either is an instance of,
     * also `KFunction0<Int>`, indistinguishable from `KFunction<Int>`.
     */
    @Test
    fun `should render function reference types not told apart by the value as KFunction`() {
        // given
        val types = listOf(
            typeOf<KFunction1<String, Int>>(),
            typeOf<KSuspendFunction1<String, Int>?>(),
            typeOf<List<KFunction1<String, Int>>>(),
            typeOf<Map<String, KSuspendFunction1<String, out Number>?>>(),
            typeOf<KFunction0<Int>>(),
            typeOf<KFunction<Int>>()
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        assert(sources == listOf(
            "kotlin.reflect.KFunction<kotlin.Int>",
            "kotlin.reflect.KFunction<kotlin.Int>?",
            "kotlin.collections.List<kotlin.reflect.KFunction<kotlin.Int>>",
            "kotlin.collections.Map<kotlin.String, kotlin.reflect.KFunction<out kotlin.Number>?>",
            "kotlin.reflect.KFunction<kotlin.Int>",
            "kotlin.reflect.KFunction<kotlin.Int>"
        ))
    }

    /**
     * A function reference type not told apart from a suspend one is widened to `KFunction<R>` only in a covariant position,
     * while elsewhere the type is projected, with `out` in an invariant position, or with a star in a contravariant one,
     * so the snippets cannot pass a function of another kind to the host.
     */
    @Test
    fun `should render function reference types not told apart by the value only where widening is safe`() {
        // given
        val types = listOf(
            typeOf<(KFunction1<String, Int>) -> Int>(),
            typeOf<(KFunction<Int>) -> Int>(),
            typeOf<((KFunction1<String, Int>) -> Unit) -> Unit>(),
            typeOf<(MutableList<KFunction1<String, Int>>) -> Unit>(),
            typeOf<MutableList<KFunction1<String, Int>>>(),
            typeOf<MutableList<MutableList<KFunction<Int>>>>(),
            typeOf<Map<KFunction1<String, Int>, Int>>(),
            typeOf<MutableList<in KFunction1<String, Int>>>(),
            typeOf<(KFunction1<String, Int>, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit>(),
            typeOf<List<(KFunction1<String, Int>, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit>>(),
            typeOf<(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> KFunction1<String, Int>>()
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        val ints22 = List(22) { "kotlin.Int" }.joinToString()
        assert(sources == listOf(
            "kotlin.Function1<*, kotlin.Int>",
            "kotlin.Function1<*, kotlin.Int>",
            "kotlin.Function1<kotlin.Function1<kotlin.reflect.KFunction<kotlin.Int>, kotlin.Unit>, kotlin.Unit>",
            "kotlin.Function1<*, kotlin.Unit>",
            "kotlin.collections.MutableList<out kotlin.reflect.KFunction<kotlin.Int>>",
            "kotlin.collections.MutableList<out kotlin.collections.MutableList<out kotlin.reflect.KFunction<kotlin.Int>>>",
            "kotlin.collections.Map<out kotlin.reflect.KFunction<kotlin.Int>, kotlin.Int>",
            "kotlin.collections.MutableList<*>",
            "kotlin.jvm.functions.FunctionN<kotlin.Unit>",
            "kotlin.collections.List<kotlin.jvm.functions.FunctionN<kotlin.Unit>>",
            "($ints22, kotlin.Int) -> kotlin.reflect.KFunction<kotlin.Int>"
        ))
    }

    /** Only the value of the property itself tells its function reference type apart, not a nested one. */
    @Test
    fun `should render nested function reference type in parameter of told apart function reference type as star`() {
        // when
        val source = typeOf<KFunction1<KFunction1<String, Int>, Int>>().toSource(isSuspend = false)

        // then
        assert(source == "kotlin.reflect.KFunction1<*, kotlin.Int>")
    }

    @Test
    fun `should not let snippet pass function of another kind to host of nested function reference type`() = runTest {
        // given
        val handler = { f: KFunction1<String, Int> -> f("x") }
        ScriptSession(listOf(property("handler", handler), property("fs", mutableListOf(::twice)))).use { session ->
            // when
            val results = listOf(
                session.execute("fun z(): Int = 1; handler(::z)"),
                session.execute("fs.add(::z)"),
                session.execute("fs[0].name")
            )

            // then
            results[0] should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
            }
            results[1] should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
            }
            results[2] should {
                be<ScriptResult.Value>()
                have(value == "twice")
            }
        }
    }

    @Test
    fun `should render function types of more than 22 parameters in source`() {
        // given
        val types = listOf(
            typeOf<(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> String?>(),
            typeOf<((Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit)?>(),
            typeOf<List<(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, (Int) -> Int) -> Unit>>(),
            typeOf<String.(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit>()
        )

        // when
        val sources = types.map { it.toSource() }

        // then
        val ints22 = List(22) { "kotlin.Int" }.joinToString()
        assert(sources == listOf(
            "($ints22, kotlin.Int) -> kotlin.String?",
            "(($ints22, kotlin.Int) -> kotlin.Unit)?",
            "kotlin.collections.List<($ints22, kotlin.Function1<kotlin.Int, kotlin.Int>) -> kotlin.Unit>",
            "(kotlin.String, $ints22) -> kotlin.Unit"
        ))
    }

    @Test
    fun `should provide property of function reference`() = runTest {
        ScriptSession(listOf(property("f", ::twice))).use { session ->
            // when
            val result = session.execute("""f.name + " " + f.call("foo") + " " + f("ab")""")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "twice 6 4")
            }
        }
    }

    @Test
    fun `should provide property of suspend function reference`() = runTest {
        ScriptSession(listOf(property("f", ::suspendTwice))).use { session ->
            // when
            val result = session.execute("""f.name + " " + f.isSuspend + " " + await { f("ab") }""")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "suspendTwice true 4")
            }
        }
    }

    @Test
    fun `should provide property of nullable function reference of type of suspend function reference`() = runTest {
        ScriptSession(listOf(property<KSuspendFunction1<String, Int>?>("f", null))).use { session ->
            // when
            val result = session.execute("f?.name")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == null)
            }
        }
    }

    @Test
    fun `should provide property of function type of more than 22 parameters`() = runTest {
        // given
        val sum: ((Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Int)? =
            { a, b, c, d, e, f, g, h, i, j, k, l, m, n, o, p, q, r, s, t, u, v, w ->
                a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q + r + s + t + u + v + w
            }
        ScriptSession(listOf(property("sum", sum))).use { session ->
            // when
            // FunctionN<Int>, as declared before, takes vararg arguments of any type, but is not a function type
            val result = session.execute("""
                val typed: ((${List(23) { "Int" }.joinToString()}) -> Int)? = sum
                typed?.invoke(${(1..23).joinToString()})
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 276)
            }
        }
    }

    /** kotlin-reflect cannot resolve a local function, so it cannot tell whether it is a suspend one. */
    @Test
    fun `should provide properties of local function reference`() = runTest {
        // given
        fun local(x: String): Int = x.length
        ScriptSession(listOf(property<Any>("any", ::local), property("f", ::local))).use { session ->
            // when
            val result = session.execute("(any as kotlin.reflect.KFunction<*>).name + \" \" + f.name")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "local local")
            }
        }
    }

    /** A nested function reference type is declared as `KFunction<R>`, as the value cannot tell it from a suspend one. */
    @Test
    fun `should not let snippet call nested suspend function reference as non-suspend function`() = runTest {
        ScriptSession(listOf(property("fs", listOf(::suspendTwice)))).use { session ->
            // when
            val result = session.execute("""fs[0]("ab")""")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
            }
        }
    }

    /**
     * The snippets check the value by the cast to its type, like the arity of a function, `as (String) -> Int`,
     * unless the cast cannot pass, as of a function type of more than 22 parameters, see [declaration].
     */
    @Test
    fun `should declare property by cast to its type unless of function type of more than 22 parameters`() {
        // given
        val big: ((Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int, Int) -> Unit)? = null
        val properties = listOf(
            property("transform", { x: String -> x.length }),
            property("f", ::twice),
            property("items", mutableListOf("foo")),
            property("big", big)
        )

        // when
        val declarations = properties.map { it.declaration(1) }

        // then
        val values = "com.xemantic.kotlin.script.ScriptPropertyValues"
        val bigType = "((${List(23) { "kotlin.Int" }.joinToString()}) -> kotlin.Unit)?"
        assert(declarations == listOf(
            "val transform: kotlin.Function1<kotlin.String, kotlin.Int> = $values.get(1, \"transform\") as kotlin.Function1<kotlin.String, kotlin.Int>",
            "val f: kotlin.reflect.KFunction1<kotlin.String, kotlin.Int> = $values.get(1, \"f\") as kotlin.reflect.KFunction1<kotlin.String, kotlin.Int>",
            "val items: kotlin.collections.MutableList<kotlin.String> = $values.get(1, \"items\") as kotlin.collections.MutableList<kotlin.String>",
            "val big: $bigType = $values.getUnchecked<$bigType>(1, \"big\")"
        ))
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

/** Public, so it can be called by reflection, like `f.call("foo")` of a property of its reference. */
fun twice(x: String): Int = x.length * 2

suspend fun suspendTwice(x: String): Int = x.length * 2

/** A function of more than 22 parameters, whose reference is a `KFunction23`. */
@Suppress("unused")
fun ignore23(
    a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int, k: Int, l: Int,
    m: Int, n: Int, o: Int, p: Int, q: Int, r: Int, s: Int, t: Int, u: Int, v: Int, w: Int
) {}

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
