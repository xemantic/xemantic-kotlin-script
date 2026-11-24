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
                    const val isConstReady = true
                    @JvmField val jvmFieldProperty = 1
                    @JvmField var jvmFieldVariable = 1
                    lateinit var lateinitProperty: String
                    fun publicFunction() = 1
                    private fun privateFunction() = 1
                    internal fun internalFunction() = 1
                    @JvmName("jvm\$name") fun functionOfJvmNameWithDollarSign() = 1
                    fun `function$withDollarSign`() = 1
                    internal val String.internalExtensionProperty get() = 1
                    internal fun String.internalExtensionFunction() = 1
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
                    object ObjectOfConst { const val LIMIT = 1 }
                    class ClassWithCompanion { companion object }
                    enum class EnumClass { ENTRY }
                    open class OpenClassOfInternalMember { internal fun internalMember() = 1 }
                    class SubclassOfInternalMember : OpenClassOfInternalMember()
                    enum class EnumClassWithBody { ENTRY { override fun f() = 1 }; open fun f() = 0 }
                    class ClassOfAnonymousObject { val runnable = object : Runnable { override fun run() {} } }
                    class OuterClass { class NestedClass }
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
    }

    @Test
    fun `should not keep private declarations of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("privateProperty"))
    }

    @Test
    fun `should not keep internal properties and functions of snippet`() {
        // when
        val declarations = declarations

        // then
        assert(!declarations.isProperty("internalProperty"))
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
    fun `should find const property of snippet by the getter a later snippet reads it by`() {
        // when
        val property = declarations.constProperty("Snippet1", "getConstProperty", emptyList())

        // then
        assert(property == ConstProperty(name = "constProperty", snippet = "snippet1.kts", declaringClass = null))
    }

    @Test
    fun `should find internal const property of snippet by the getter mangled with the module of a later snippet`() {
        // when
        val property = declarations.constProperty("Snippet1", $$"getConstProperty$main", emptyList())

        // then
        assert(property?.name == "constProperty")
    }

    @Test
    fun `should find const property of snippet named like a getter by its name`() {
        // when
        val property = declarations.constProperty("Snippet1", "isConstReady", emptyList())

        // then
        assert(property?.name == "isConstReady")
    }

    @Test
    fun `should find const property of object of snippet`() {
        // when
        val property = declarations.constProperty($$"Snippet1$ObjectOfConst", "getLIMIT", emptyList())

        // then
        assert(
            property == ConstProperty(
                name = "ObjectOfConst.LIMIT",
                snippet = "snippet1.kts",
                declaringClass = DeclaredClass("ObjectOfConst", DeclaredClass.Kind.OBJECT)
            )
        )
    }

    @Test
    fun `should not find const property by the getter of a property which is not const`() {
        // when
        val property = declarations.constProperty("Snippet1", "getPublicProperty", emptyList())

        // then
        assert(property == null)
    }

    @Test
    fun `should not find const property by a getter taking parameters`() {
        // when
        val property = declarations.constProperty("Snippet1", "getConstProperty", listOf("int"))

        // then
        assert(property == null)
    }

    @Test
    fun `should not find const property of class not declared by a snippet`() {
        // when
        val property = declarations.constProperty("java.lang.Integer", "getMAX_VALUE", emptyList())

        // then
        assert(property == null)
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
        assert(declarations.internalMember($$"Snippet1$ClassOfInternalMember", $$"getInternalMember$main", emptyList()) == InternalMember("ClassOfInternalMember.internalMember", "snippet1.kts", declaringClass = DeclaredClass("ClassOfInternalMember", DeclaredClass.Kind.FINAL)))
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
    fun `should find internal member of snippet inherited by subclass`() {
        // when
        val member = declarations.internalMember($$"Snippet1$SubclassOfInternalMember", "internalMember", emptyList())

        // then
        assert(
            member == InternalMember(
                name = "OpenClassOfInternalMember.internalMember",
                snippet = "snippet1.kts",
                declaringClass = DeclaredClass("OpenClassOfInternalMember", DeclaredClass.Kind.OPEN),
                calledOn = "SubclassOfInternalMember"
            )
        )
    }

    @Test
    fun `should find internal function of snippet of 32 parameters by its stub of default arguments`() {
        // given
        val parameters = (1..32).joinToString { "p$it: Int = 0" }
        val scriptClass = ScriptSession().use { session ->
            val result = runBlocking {
                session.execute("""
                    internal fun String.extension($parameters) = 1
                    internal suspend fun suspending($parameters) = 1
                    Snippet1::class.java
                """.trimIndent())
            }
            (result as ScriptResult.Value).value as Class<*>
        }
        val declarations = SnippetDeclarations().apply { keep(scriptClass, "snippet1.kts") }
        val ints = List(32) { "int" }

        // when
        val extension = declarations.internalMember(
            "Snippet1", $$"extension$default", listOf("Snippet1", "java.lang.String") + ints + listOf("int", "java.lang.Object")
        )
        val suspending = declarations.internalMember(
            "Snippet1", $$"suspending$default", listOf("Snippet1") + ints + listOf("kotlin.coroutines.Continuation", "int", "java.lang.Object")
        )

        // then
        assert(extension == InternalMember("extension", "snippet1.kts"))
        assert(suspending == InternalMember("suspending", "snippet1.kts"))
    }

    @Test
    fun `should tell class of snippet whose values a later snippet can create again`() {
        // when
        val declarations = declarations

        // then
        assert(declarations.declaredClass("Snippet1") == null)
        assert(declarations.declaredClass($$"Snippet1$PublicClass") == DeclaredClass("PublicClass", DeclaredClass.Kind.FINAL))
        assert(declarations.declaredClass($$"Snippet1$OuterClass$NestedClass") == DeclaredClass("OuterClass.NestedClass", DeclaredClass.Kind.FINAL))
        assert(declarations.declaredClass($$"Snippet1$OpenClass") == DeclaredClass("OpenClass", DeclaredClass.Kind.OPEN))
        assert(declarations.declaredClass($$"Snippet1$AbstractClass") == DeclaredClass("AbstractClass", DeclaredClass.Kind.OPEN))
        assert(declarations.declaredClass($$"Snippet1$Object") == DeclaredClass("Object", DeclaredClass.Kind.OBJECT))
        assert(declarations.declaredClass($$"Snippet1$ClassWithCompanion$Companion") == DeclaredClass("ClassWithCompanion.Companion", DeclaredClass.Kind.COMPANION))
        assert(declarations.declaredClass($$"Snippet1$ClassOfAnonymousObject$runnable$1") == DeclaredClass("ClassOfAnonymousObject", DeclaredClass.Kind.FINAL))
        assert(declarations.declaredClass($$"Snippet1$Interface") == DeclaredClass("Interface", DeclaredClass.Kind.OTHER))
        assert(declarations.declaredClass($$"Snippet1$Interface$DefaultImpls") == DeclaredClass("Interface.DefaultImpls", DeclaredClass.Kind.OTHER))
        assert(declarations.declaredClass($$"Snippet1$EnumClass") == DeclaredClass("EnumClass", DeclaredClass.Kind.OTHER))
        assert(declarations.declaredClass($$"Snippet1$EnumClassWithBody$ENTRY") == DeclaredClass("EnumClassWithBody", DeclaredClass.Kind.OTHER))
        assert(declarations.declaredClass($$"Snippet1$Undeclared") == null)
        assert(declarations.declaredClass("java.lang.String") == null)
    }

}
