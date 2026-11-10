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
import kotlin.test.Test

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
                    class PublicClass
                    private class PrivateClass
                    internal class InternalClass
                    internal var internalVariable = 1
                    class ClassOfInternalMember { internal val internalMember = 1 }
                    var internalSetterProperty = 1
                        internal set
                    internal fun internalFunctionWithDefault(n: Int = 1) = n
                    Snippet1::class.java
                """.trimIndent()) }
                (result as ScriptResult.Value).value as Class<*>
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
        assert(declarations.extensionPropertyReceivers("publicExtensionProperty") == setOf(ExtensionReceiver("String", nullable = false)))
        assert(declarations.extensionFunctionReceivers("publicExtensionFunction") == setOf(ExtensionReceiver("String", nullable = false)))
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
        assert(declarations.extensionFunctionReceivers("functionTypeExtensionFunction") == setOf(ExtensionReceiver("Function1", nullable = false)))
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
        assert(declarations.internalMember($$"Snippet1$ClassOfInternalMember", $$"getInternalMember$main", emptyList()) == InternalMember("ClassOfInternalMember.internalMember", "snippet1.kts"))
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
    fun `should accept receiver of type of its class`() {
        // given
        val receiver = ExtensionReceiver("Entry", nullable = false)

        // when
        val accepted = receiver.accepts("Map.Entry<Int, Int>")

        // then
        assert(accepted)
    }

    @Test
    fun `should not accept receiver of another type`() {
        // given
        val receiver = ExtensionReceiver("String", nullable = false)

        // when
        val accepted = receiver.accepts("List<Int>")

        // then
        assert(!accepted)
    }

    @Test
    fun `should not accept nullable receiver by non-null one`() {
        // given
        val receiver = ExtensionReceiver("String", nullable = false)

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
    fun `should not accept receiver of subtype`() {
        // given
        val receiver = ExtensionReceiver("List", nullable = false)

        // when
        val accepted = receiver.accepts("MutableList<Int>")

        // then
        assert(!accepted)
    }

}
