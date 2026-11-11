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
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KVariance
import kotlin.test.Test

/** Whether the last property of this [name] declared by a snippet is accessible by a later snippet, for which a hint at declaring the type is given. */
private fun SnippetDeclarations.isProperty(name: String): Boolean = property(name)?.kind == SnippetProperty.Kind.ACCESSIBLE

class SnippetDeclarationsTest {

    companion object {

        /**
         * The class of a snippet compiled by the K2 REPL compiler, declaring each kind of the members in each visibility,
         * as its metadata differs from the one of a regular class, which reads every member as public.
         */
        private val snippetClass: Class<*> by lazy {
            ScriptSession().use { session ->
                val result = runBlocking { session.execute($$"""
                    val publicProperty = 1
                    private val privateProperty = 1
                    internal val internalProperty = 1
                    const val constProperty = 1
                    @JvmField val jvmFieldProperty = 1
                    @JvmField var jvmFieldVariable = 1
                    lateinit var lateinitProperty: String
                    fun publicFunction() = 1
                    private fun privateFunction() = 1
                    internal fun internalFunction() = 1
                    @JvmName("jvm\$name") fun functionOfJvmNameWithDollarSign() = 1
                    fun `function$withDollarSign`() = 1
                    val String.publicExtensionProperty get() = 1
                    private val String.privateExtensionProperty get() = 1
                    internal val String.internalExtensionProperty get() = 1
                    fun String.publicExtensionFunction() = 1
                    private fun String.privateExtensionFunction() = 1
                    internal fun String.internalExtensionFunction() = 1
                    fun String?.nullableExtensionFunction() = 1
                    fun <T> T.genericExtensionFunction() = 1
                    fun <T : Number> T.boundedExtensionFunction() = 1
                    fun ((Int) -> Int).functionTypeExtensionFunction() = 1
                    fun <T> ((T) -> Unit).genericFunctionTypeExtensionFunction() = 1
                    fun MutableList<Int>.mutableListExtensionFunction() = 1
                    val <T> List<T>.genericListExtensionProperty get() = 1
                    fun Map.Entry<Int, String>.entryExtensionFunction() = 1
                    fun PublicClass.classExtensionFunction() = 1
                    fun List<*>.starListExtensionFunction() = 1
                    typealias Name = String
                    fun Name.aliasExtensionFunction() = 1
                    fun List<Name>.aliasListExtensionFunction() = 1
                    val StringBuilder.builderExtensionProperty get() = 1
                    fun MutableList<out Number>.outMutableListExtensionFunction() = 1
                    fun (List<Int>.() -> Pair<String, Int>).receiverFunctionTypeExtensionFunction() = 1
                    class PublicClass
                    private class PrivateClass
                    internal class InternalClass
                    internal var internalVariable = 1
                    class ClassOfInternalMember { internal val internalMember = 1 }
                    var internalSetterProperty = 1
                        internal set
                    internal fun internalFunctionWithDefault(n: Int = 1) = n
                    open class OpenClass
                    abstract class AbstractClass
                    interface Interface { fun method() = 1 }
                    object Object
                    class ClassWithCompanion { companion object }
                    enum class EnumClass { ENTRY }
                    Snippet1::class.java
                """.trimIndent()) }
                (result as ScriptResult.Value).value as Class<*>
            }
        }

        /**
         * The classes of two snippets, the second declaring again each name of the first in another kind or visibility,
         * which a later snippet resolves to the second one, but a `private` property or function, and an overload.
         */
        private val redeclaringSnippetClasses: List<Class<*>> by lazy {
            ScriptSession().use { session ->
                listOf(
                    """
                        val constLater = 1
                        val jvmFieldLater = 1
                        val internalLater = 1
                        const val publicLater = 1
                        @JvmField val publicLaterJvmField = 1
                        val privateLater = 1
                        fun internalFunctionLater() = 1
                        fun overloadedLater() = 1
                        fun privateFunctionLater() = 1
                        fun <T> genericLater(x: T) = x
                        internal fun <T : Number> boundedLater(x: T) = 1
                        fun <T : Number> T.boundedExtensionLater() = 1
                        internal fun defaultLater(n: String) = n
                        val String.internalExtensionLater get() = 1
                        val String.overloadedExtensionLater get() = 1
                        fun String.internalExtensionFunctionLater() = 1
                        class PrivateClassLater
                        Snippet1::class.java
                    """,
                    """
                        const val constLater = 2
                        @JvmField val jvmFieldLater = 2
                        internal val internalLater = 2
                        val publicLater = 2
                        val publicLaterJvmField = 2
                        private val privateLater = 2
                        internal fun internalFunctionLater() = 2
                        internal fun overloadedLater(x: Int) = x
                        private fun privateFunctionLater() = 2
                        internal fun <R> genericLater(x: R) = x
                        fun <R : CharSequence> boundedLater(x: R) = 2
                        fun <R : CharSequence> R.boundedExtensionLater() = 2
                        fun defaultLater(n: String = "x") = n
                        internal val String.internalExtensionLater get() = 2
                        internal val Int.overloadedExtensionLater get() = 2
                        internal fun String.internalExtensionFunctionLater() = 2
                        private class PrivateClassLater
                        Snippet2::class.java
                    """
                ).map { snippet ->
                    val result = runBlocking { session.execute(snippet.trimIndent()) }
                    (result as ScriptResult.Value).value as Class<*>
                }
            }
        }

    }

    private val declarations = SnippetDeclarations().apply {
        keep(snippetClass, "snippet1.kts")
    }

    @Test
    fun `should keep declarations of snippet accessible by later snippet`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.isProperty("publicProperty"))
        assert(declarations.isProperty("lateinitProperty"))
        assert(declarations.isFunction("publicFunction"))
        assert(declarations.isFunction("functionOfJvmNameWithDollarSign"))
        assert(declarations.isFunction($$"function$withDollarSign"))
        assert(declarations.extensionPropertyReceivers("publicExtensionProperty") == setOf(ExtensionReceiver("String")))
        assert(declarations.extensionFunctionReceivers("publicExtensionFunction") == setOf(ExtensionReceiver("String")))
        assert(declarations.isClass("PublicClass"))
        assert(declarations.isClass("InternalClass"))
    }

    @Test
    fun `should not keep private declarations of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("privateProperty"))
        assert(!declarations.isFunction("privateFunction"))
        assert(declarations.extensionPropertyReceivers("privateExtensionProperty").isEmpty())
        assert(declarations.extensionFunctionReceivers("privateExtensionFunction").isEmpty())
        assert(!declarations.isClass("PrivateClass"))
    }

    @Test
    fun `should not keep internal properties and functions of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("internalProperty"))
        assert(!declarations.isFunction("internalFunction"))
        assert(declarations.extensionPropertyReceivers("internalExtensionProperty").isEmpty())
        assert(declarations.extensionFunctionReceivers("internalExtensionFunction").isEmpty())
    }

    @Test
    fun `should not keep const and JvmField properties of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("constProperty"))
        assert(!declarations.isProperty("jvmFieldProperty"))
        assert(!declarations.isProperty("jvmFieldVariable"))
    }

    @Test
    fun `should tell const and JvmField properties of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.property("constProperty") == SnippetProperty(SnippetProperty.Kind.CONST, "snippet1.kts"))
        assert(declarations.property("jvmFieldProperty") == SnippetProperty(SnippetProperty.Kind.JVM_FIELD, "snippet1.kts"))
        assert(declarations.property("jvmFieldVariable") == SnippetProperty(SnippetProperty.Kind.JVM_FIELD, "snippet1.kts"))
        assert(declarations.property("publicProperty") == SnippetProperty(SnippetProperty.Kind.ACCESSIBLE, "snippet1.kts"))
        assert(declarations.property("internalProperty") == SnippetProperty(SnippetProperty.Kind.INTERNAL, "snippet1.kts"))
        assert(declarations.property("privateProperty") == null)
        assert(declarations.isJvmFieldProperty("jvmFieldProperty", "snippet1.kts"))
        assert(!declarations.isJvmFieldProperty("publicProperty", "snippet1.kts"))
    }

    @Test
    fun `should keep the declaration of a name declared again by later snippet instead of the earlier one`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        redeclaringSnippetClasses.forEachIndexed { index, snippetClass ->
            declarations.keep(snippetClass, "snippet${index + 1}.kts")
        }

        // then
        assert(!declarations.isProperty("constLater"))
        assert(declarations.property("constLater") == SnippetProperty(SnippetProperty.Kind.CONST, "snippet2.kts"))
        assert(!declarations.isProperty("jvmFieldLater"))
        assert(declarations.property("jvmFieldLater") == SnippetProperty(SnippetProperty.Kind.JVM_FIELD, "snippet2.kts"))
        assert(declarations.isJvmFieldProperty("jvmFieldLater", "snippet2.kts"))
        assert(!declarations.isProperty("internalLater"))
        assert(declarations.isProperty("publicLater"))
        assert(declarations.isProperty("publicLaterJvmField"))
        assert(declarations.isJvmFieldProperty("publicLaterJvmField", "snippet1.kts"))
        assert(!declarations.isJvmFieldProperty("publicLaterJvmField", "snippet2.kts"))
        assert(!declarations.isFunction("internalFunctionLater"))
        assert(!declarations.isFunction("genericLater"))
        assert(declarations.extensionPropertyReceivers("internalExtensionLater").isEmpty())
        assert(declarations.extensionFunctionReceivers("internalExtensionFunctionLater").isEmpty())
        assert(!declarations.isClass("PrivateClassLater"))
    }

    @Test
    fun `should keep the declaration of a name not replaced by private declaration or extension of another receiver of later snippet`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        redeclaringSnippetClasses.forEachIndexed { index, snippetClass ->
            declarations.keep(snippetClass, "snippet${index + 1}.kts")
        }

        // then
        assert(declarations.isProperty("privateLater"))
        assert(declarations.isFunction("privateFunctionLater"))
        assert(declarations.extensionPropertyReceivers("overloadedExtensionLater") == setOf(ExtensionReceiver("String", nullable = false)))
    }

    @Test
    fun `should not tell function accessible when an overload of it is internal`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        redeclaringSnippetClasses.forEachIndexed { index, snippetClass ->
            declarations.keep(snippetClass, "snippet${index + 1}.kts")
        }

        // then
        assert(!declarations.isFunction("overloadedLater"))
        assert(!declarations.isFunction("boundedLater"))
    }

    @Test
    fun `should keep overloads of other type parameter bounds`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        redeclaringSnippetClasses.forEachIndexed { index, snippetClass ->
            declarations.keep(snippetClass, "snippet${index + 1}.kts")
        }

        // then
        assert(declarations.extensionFunctionReceivers("boundedExtensionLater") == setOf(
            ExtensionReceiver("Number", nullable = false),
            ExtensionReceiver("CharSequence", nullable = false)
        ))
    }

    @Test
    fun `should replace function by later one of the same parameters also with default argument`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        redeclaringSnippetClasses.forEachIndexed { index, snippetClass ->
            declarations.keep(snippetClass, "snippet${index + 1}.kts")
        }

        // then
        assert(declarations.isFunction("defaultLater"))
    }

    @Test
    fun `should tell extensions from other members`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("publicExtensionProperty"))
        assert(!declarations.isFunction("publicExtensionFunction"))
        assert(declarations.extensionPropertyReceivers("publicProperty").isEmpty())
        assert(declarations.extensionFunctionReceivers("publicFunction").isEmpty())
    }

    @Test
    fun `should keep receivers of extensions by their classes`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.extensionFunctionReceivers("nullableExtensionFunction") == setOf(ExtensionReceiver("String", nullable = true)))
        assert(declarations.extensionFunctionReceivers("genericExtensionFunction") == setOf(ExtensionReceiver("Any", nullable = true)))
        assert(declarations.extensionFunctionReceivers("boundedExtensionFunction") == setOf(ExtensionReceiver("Number", nullable = false)))
        assert(declarations.extensionFunctionReceivers("functionTypeExtensionFunction") == setOf(ExtensionReceiver("(Int) -> Int")))
    }

    @Test
    fun `should keep receivers of extensions by their types without packages`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.extensionFunctionReceivers("mutableListExtensionFunction") == setOf(ExtensionReceiver("MutableList", listOf("Int"), variances = listOf(KVariance.INVARIANT))))
        assert(declarations.extensionPropertyReceivers("genericListExtensionProperty") == setOf(ExtensionReceiver("List", listOf(null), variances = listOf(KVariance.OUT))))
        assert(declarations.extensionFunctionReceivers("entryExtensionFunction") == setOf(ExtensionReceiver("Map.Entry", listOf("Int", "String"), variances = listOf(KVariance.OUT, KVariance.OUT))))
        assert(declarations.extensionFunctionReceivers("classExtensionFunction") == setOf(ExtensionReceiver("PublicClass")))
    }

    @Test
    fun `should not keep receiver of function type of type parameter`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.extensionFunctionReceivers("genericFunctionTypeExtensionFunction").isEmpty())
    }

    @Test
    fun `should keep declarations of snippet kept after the earlier ones were read`() {
        // given
        val declarations = SnippetDeclarations()
        declarations.keep(String::class.java, "snippet1.kts")
        declarations.isProperty("publicProperty")

        // when
        declarations.keep(snippetClass, "snippet1.kts")

        // then
        assert(declarations.isProperty("publicProperty"))
    }

    @Test
    fun `should find internal member of snippet by the JVM name a later snippet calls it by`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.internalMember("Snippet1", $$"getInternalProperty$main", emptyList()) == InternalMember("internalProperty", "snippet1.kts"))
        assert(declarations.internalMember("Snippet1", $$"setInternalVariable$main", listOf("int")) == InternalMember("internalVariable", "snippet1.kts"))
        assert(declarations.internalMember("Snippet1", "internalFunction", emptyList()) == InternalMember("internalFunction", "snippet1.kts"))
        assert(declarations.internalMember("Snippet1", $$"getInternalExtensionProperty$main", listOf("java.lang.String")) == InternalMember("internalExtensionProperty", "snippet1.kts"))
        assert(declarations.internalMember("Snippet1", "internalExtensionFunction", listOf("java.lang.String")) == InternalMember("internalExtensionFunction", "snippet1.kts"))
        assert(declarations.internalMember($$"Snippet1$ClassOfInternalMember", $$"getInternalMember$main", emptyList()) == InternalMember("ClassOfInternalMember.internalMember", "snippet1.kts", declaringClass = "ClassOfInternalMember"))
    }

    @Test
    fun `should find internal setter of public property of snippet`() {
        // when
        val member = declarations.internalMember("Snippet1", $$"setInternalSetterProperty$main", listOf("int"))

        // then
        assert(member == InternalMember("internalSetterProperty", "snippet1.kts", internalSetter = true))
    }

    @Test
    fun `should find internal function of snippet by its stub of default arguments`() {
        // when
        val member = declarations.internalMember(
            "Snippet1", $$"internalFunctionWithDefault$default", listOf("Snippet1", "int", "int", "java.lang.Object")
        )

        // then
        assert(member == InternalMember("internalFunctionWithDefault", "snippet1.kts"))
    }

    @Test
    fun `should not find internal function of snippet by stub of default arguments of other parameters`() {
        // when
        val member = declarations.internalMember(
            "Snippet1", $$"internalFunctionWithDefault$default", listOf("Snippet1", "long", "int", "java.lang.Object")
        )

        // then
        assert(member == null)
    }

    @Test
    fun `should not find internal member of snippet by a JVM name of another member`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.internalMember("Snippet1", $$"getPublicProperty$main", emptyList()) == null)
        assert(declarations.internalMember("Snippet1", "publicFunction", emptyList()) == null)
        assert(declarations.internalMember("Snippet1", "privateFunction", emptyList()) == null)
        assert(declarations.internalMember("Snippet1", "internalFunction", listOf("int")) == null)
        assert(declarations.internalMember("Snippet1", $$"getUndeclared$main", emptyList()) == null)
        assert(declarations.internalMember($$"Snippet1$Undeclared", $$"getInternalMember$main", emptyList()) == null)
        assert(declarations.internalMember("Snippet2", "internalFunction", emptyList()) == null)
        assert(declarations.internalMember("java.lang.String", "internalFunction", emptyList()) == null)
    }

    @Test
    fun `should tell final class of snippet whose values a later snippet can create again`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.isFinalClass("Snippet1"))
        assert(declarations.isFinalClass($$"Snippet1$PublicClass"))
        assert(!declarations.isFinalClass($$"Snippet1$OpenClass"))
        assert(!declarations.isFinalClass($$"Snippet1$AbstractClass"))
        assert(!declarations.isFinalClass($$"Snippet1$Interface"))
        assert(!declarations.isFinalClass($$"Snippet1$Interface$DefaultImpls"))
        assert(!declarations.isFinalClass($$"Snippet1$Object"))
        assert(!declarations.isFinalClass($$"Snippet1$ClassWithCompanion$Companion"))
        assert(!declarations.isFinalClass($$"Snippet1$EnumClass"))
        assert(!declarations.isFinalClass($$"Snippet1$Undeclared"))
        assert(!declarations.isFinalClass("java.lang.String"))
    }

    @Test
    fun `should accept receiver of type of its class`() {
        // given
        val receiver = ExtensionReceiver("Map.Entry", listOf("Int", "String"))

        // when
        val accepted = receiver.accepts("Map.Entry<Int, String>")

        // then
        assert(accepted)
    }

    @Test
    fun `should not accept receiver of another type`() {
        // given
        val receiver = ExtensionReceiver("String")

        // when
        val accepted = receiver.accepts("List<Int>")

        // then
        assert(!accepted)
    }

    @Test
    fun `should not accept receiver of nested class of another outer class`() {
        // given
        val receiver = ExtensionReceiver("Map.Entry", listOf("Int", "Int"))

        // when
        val accepted = receiver.accepts("Other.Entry<Int, Int>")

        // then
        assert(!accepted)
    }

    @Test
    fun `should not accept receiver of other type arguments`() {
        // given
        val receiver = ExtensionReceiver("List", listOf("String"))

        // when
        val accepted = receiver.accepts("List<Int>")

        // then
        assert(!accepted)
    }

    @Test
    fun `should accept receiver of any type argument by type parameter`() {
        // given
        val receiver = ExtensionReceiver("Map.Entry", listOf(null, "String"))

        // when
        val accepted = receiver.accepts("Map.Entry<List<Int>, String>")

        // then
        assert(accepted)
    }

    @Test
    fun `should accept receiver of function type`() {
        // given
        val receiver = ExtensionReceiver("(Int) -> Int")

        // when
        val accepted = receiver.accepts("(Int) -> Int")

        // then
        assert(accepted)
    }

    @Test
    fun `should not accept nullable receiver by non-null one`() {
        // given
        val receiver = ExtensionReceiver("String")

        // when
        val accepted = receiver.accepts("String?")

        // then
        assert(!accepted)
    }

    @Test
    fun `should accept non-null receiver by nullable one`() {
        // given
        val receiver = ExtensionReceiver("String", nullable = true)

        // when
        val accepted = receiver.accepts("String")

        // then
        assert(accepted)
    }

    @Test
    fun `should accept receiver of any type by receiver of Any`() {
        // given
        val receiver = ExtensionReceiver("Any", nullable = true)

        // when
        val accepted = receiver.accepts("List<Int>?")

        // then
        assert(accepted)
    }

    @Test
    fun `should accept receiver of well-known subtype`() {
        // given
        val collection = ExtensionReceiver("Collection", listOf("Int"))
        val charSequence = ExtensionReceiver("CharSequence")
        val entry = ExtensionReceiver("Map.Entry", listOf("Int", "String"))

        // when
        val accepted = listOf(
            collection.accepts("MutableList<Int>"),
            charSequence.accepts("String"),
            entry.accepts("MutableMap.MutableEntry<Int, String>")
        )

        // then
        assert(accepted == listOf(true, true, true))
    }

    @Test
    fun `should accept receiver of subtype of other type arity by type parameters only`() {
        // given
        val generic = ExtensionReceiver("Comparable", listOf(null))
        val concrete = ExtensionReceiver("Comparable", listOf("String"))

        // when
        val accepted = listOf(generic.accepts("String"), concrete.accepts("String"))

        // then
        assert(accepted == listOf(true, false))
    }

    @Test
    fun `should not accept read-only type by receiver of mutable one`() {
        // given
        val receiver = ExtensionReceiver("MutableList", listOf("Int"))

        // when
        val accepted = receiver.accepts("List<Int>")

        // then
        assert(!accepted)
    }

    @Test
    fun `should not accept receiver of subtype not known`() {
        // given
        val receiver = ExtensionReceiver("Shape")

        // when
        val accepted = receiver.accepts("Circle")

        // then
        assert(!accepted)
    }

    @Test
    fun `should keep star projection of receiver as declared`() {
        // when
        val receivers = declarations.extensionFunctionReceivers("starListExtensionFunction")

        // then
        assert(receivers == setOf(ExtensionReceiver("List", listOf("*"), variances = listOf(null))))
        assert(receivers.single().declaration == "List<*>")
    }

    @Test
    fun `should accept receiver of any type argument by star projection`() {
        // given
        val receiver = ExtensionReceiver("List", listOf("*"))

        // when
        val accepted = receiver.accepts("List<Int>")

        // then
        assert(accepted)
    }

    @Test
    fun `should accept receiver of function type of nullable result`() {
        // given
        val receiver = ExtensionReceiver("(Int) -> String?")

        // when
        val accepted = listOf(receiver.accepts("(Int) -> String?"), receiver.accepts("((Int) -> String?)?"))

        // then
        assert(accepted == listOf(true, false))
    }

    @Test
    fun `should keep receivers of type aliases by their expansions`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.extensionFunctionReceivers("aliasExtensionFunction") == setOf(ExtensionReceiver("String")))
        assert(declarations.extensionFunctionReceivers("aliasListExtensionFunction") == setOf(ExtensionReceiver("List", listOf("String"), variances = listOf(KVariance.OUT))))
        assert(declarations.extensionPropertyReceivers("builderExtensionProperty") == setOf(ExtensionReceiver("StringBuilder")))
    }

    @Test
    fun `should accept receiver of class of snippet named by its snippet`() {
        // given
        val point = ExtensionReceiver("Point")
        val inner = ExtensionReceiver("Box.Inner")
        val list = ExtensionReceiver("List", listOf("Point"))

        // when
        val accepted = listOf(
            point.accepts("Snippet1.Point"),
            inner.accepts("Snippet1.Box.Inner"),
            list.accepts("List<Snippet1.Point>")
        )

        // then
        assert(accepted == listOf(true, true, true))
    }

    @Test
    fun `should keep variance of type arguments of receivers`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.extensionFunctionReceivers("mutableListExtensionFunction").single().variances == listOf(KVariance.INVARIANT))
        assert(declarations.extensionFunctionReceivers("outMutableListExtensionFunction").single().variances == listOf(KVariance.OUT))
        assert(declarations.extensionFunctionReceivers("entryExtensionFunction").single().variances == listOf(KVariance.OUT, KVariance.OUT))
        assert(declarations.extensionFunctionReceivers("aliasListExtensionFunction").single().variances == listOf(KVariance.OUT))
    }

    @Test
    fun `should keep receiver of function type with receiver as declared`() {
        // when
        val receivers = declarations.extensionFunctionReceivers("receiverFunctionTypeExtensionFunction")

        // then
        assert(receivers == setOf(ExtensionReceiver("List<Int>.() -> Pair<String, Int>")))
        assert(receivers.single().declaration == "(List<Int>.() -> Pair<String, Int>)")
    }

    @Test
    fun `should accept receiver of function type with receiver`() {
        // given
        val receiver = ExtensionReceiver("List<Int>.() -> Pair<String, Int>")

        // when
        val accepted = listOf(
            receiver.accepts("List<Int>.() -> Pair<String, Int>"),
            receiver.accepts("List<String>.() -> Pair<String, Int>")
        )

        // then
        assert(accepted == listOf(true, false))
    }

    @Test
    fun `should accept receiver of covariant type argument of supertype`() {
        // given
        val map = ExtensionReceiver("Map", listOf("String", "Any"), variances = listOf(KVariance.INVARIANT, KVariance.OUT))
        val numbers = ExtensionReceiver("List", listOf("Number"), variances = listOf(KVariance.OUT))
        val projected = ExtensionReceiver("MutableList", listOf("out Number"), variances = listOf(KVariance.OUT))
        val anything = ExtensionReceiver("List", listOf("Any"), variances = listOf(KVariance.OUT))
        val pair = ExtensionReceiver("Pair", listOf("Any", "Any"), variances = listOf(KVariance.OUT, KVariance.OUT))
        val texts = ExtensionReceiver("Collection", listOf("CharSequence"), variances = listOf(KVariance.OUT))

        // when
        val accepted = listOf(
            map.accepts("Map<String, Int>"),
            numbers.accepts("List<Int>"),
            projected.accepts("MutableList<Int>"),
            anything.accepts("List<Comparable<*> & Serializable>"),
            pair.accepts("Pair<Int, String>"),
            texts.accepts("List<StringBuilder>")
        )

        // then
        assert(accepted == listOf(true, true, true, true, true, true))
    }

    @Test
    fun `should not accept receiver of covariant type argument of non-null supertype by nullable one`() {
        // given
        val receiver = ExtensionReceiver("List", listOf("Any"), variances = listOf(KVariance.OUT))

        // when
        val acceptance = receiver.acceptance("List<Int?>")

        // then
        assert(acceptance == Acceptance.REJECTED)
    }

    @Test
    fun `should not accept receiver of invariant type argument of supertype`() {
        // given
        val receiver = ExtensionReceiver("MutableList", listOf("Number"), variances = listOf(KVariance.INVARIANT))

        // when
        val acceptance = receiver.acceptance("MutableList<Int>")

        // then
        assert(acceptance == Acceptance.REJECTED)
    }

    @Test
    fun `should not accept receiver of covariant type argument of well-known final type of other supertypes`() {
        // given
        val receiver = ExtensionReceiver("List", listOf("Number"), variances = listOf(KVariance.OUT))

        // when
        val acceptance = receiver.acceptance("List<String>")

        // then
        assert(acceptance == Acceptance.REJECTED)
    }

    @Test
    fun `should possibly accept receiver of covariant type argument of type not known`() {
        // given
        val texts = ExtensionReceiver("List", listOf("CharSequence"), variances = listOf(KVariance.OUT))
        val lists = ExtensionReceiver("List", listOf("Collection<Int>"), variances = listOf(KVariance.OUT))
        val comparables = ExtensionReceiver("List", listOf("Comparable<Int>"), variances = listOf(KVariance.OUT))

        // when
        val acceptances = listOf(
            texts.acceptance("List<Text>"),
            lists.acceptance("List<List<Number>>"),
            comparables.acceptance("List<Int>")
        )

        // then
        assert(acceptances == listOf(Acceptance.POSSIBLE, Acceptance.POSSIBLE, Acceptance.POSSIBLE))
    }

    @Test
    fun `should accept receiver of well-known concrete subtype`() {
        // given
        val collection = ExtensionReceiver("Collection", listOf("Int"), variances = listOf(KVariance.OUT))
        val map = ExtensionReceiver("Map", listOf("String", "Int"), variances = listOf(KVariance.INVARIANT, KVariance.OUT))
        val charSequence = ExtensionReceiver("CharSequence")

        // when
        val accepted = listOf(
            collection.accepts("ArrayList<Int>"),
            collection.accepts("HashSet<Int>"),
            collection.accepts("LinkedHashSet<Int>"),
            map.accepts("HashMap<String, Int>"),
            map.accepts("LinkedHashMap<String, Int>"),
            charSequence.accepts("StringBuilder")
        )

        // then
        assert(accepted == listOf(true, true, true, true, true, true))
    }

    @Test
    fun `should accept receiver of intersection type by any of its types`() {
        // given
        val comparable = ExtensionReceiver("Comparable", listOf("*"))
        val number = ExtensionReceiver("Number")

        // when
        val acceptances = listOf(
            comparable.acceptance("Comparable<*> & Serializable"),
            number.acceptance("Comparable<*> & Serializable")
        )

        // then
        assert(acceptances == listOf(Acceptance.ACCEPTED, Acceptance.REJECTED))
    }

}
