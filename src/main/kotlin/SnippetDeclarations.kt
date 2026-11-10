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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Member
import java.lang.reflect.Modifier
import kotlin.reflect.KCallable
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberExtensionProperties
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.extensionReceiverParameter
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod

private val logger = KotlinLogging.logger {}

/** A character the compiler replaces with `_` in the name of a module, like `.` of `snippet1.kts`. */
private val nonIdentifierCharacter = Regex("[^\\p{L}\\p{N}_]")

/**
 * The names of the declarations of the evaluated snippets which a later snippet can access,
 * for the hint at the K2 REPL compiler not resolving them within a declaration of an inferred type.
 *
 * A `private` declaration is left out, as it cannot be accessed by a later snippet at all,
 * and so is an `internal` property or function, as a later snippet calls it by a name it is not compiled to,
 * failing at runtime, and so is a `const val` or a `@JvmField` property, failing in any declaration,
 * see [isAccessibleByLaterSnippet].
 *
 * The classes of the snippets are kept, and their members read by kotlin-reflect only when a name is looked up,
 * which is after a compilation error, as reading them takes long.
 * Accessed on the thread of the session only, like the snippets.
 */
internal class SnippetDeclarations {

    /** The classes of the snippets not read yet, with the names of the snippets. */
    private val pending = ArrayDeque<Pair<Class<*>, String>>()

    private val classes = HashSet<String>()

    private val properties = HashSet<String>()

    private val functions = HashSet<String>()

    private val extensionProperties = HashMap<String, MutableSet<ExtensionReceiver>>()

    private val extensionFunctions = HashMap<String, MutableSet<ExtensionReceiver>>()

    /** Keeps the [scriptClass] of the snippet of this [name], to be read once a name is looked up. */
    fun keep(scriptClass: Class<*>, name: String) {
        pending += scriptClass to name
    }

    /** Whether a class of this simple [name] is declared by a snippet, and is not `private`. */
    fun isClass(name: String): Boolean = name in read().classes

    /** Whether a property of this [name] is declared by a snippet, not an extension, also a provided one. */
    fun isProperty(name: String): Boolean = name in read().properties

    /** Whether a function of this [name] is declared by a snippet, not an extension. */
    fun isFunction(name: String): Boolean = name in read().functions

    /** The receivers of the extension properties of this [name] declared by the snippets, in the order of the declarations. */
    fun extensionPropertyReceivers(name: String): Set<ExtensionReceiver> = read().extensionProperties[name] ?: emptySet()

    /** The receivers of the extension functions of this [name] declared by the snippets, in the order of the declarations. */
    fun extensionFunctionReceivers(name: String): Set<ExtensionReceiver> = read().extensionFunctions[name] ?: emptySet()

    /**
     * Reads the [pending] classes, each kind of the members of a class, and each member, on its own,
     * so a member kotlin-reflect fails on loses only itself, or only its kind of the members of the class,
     * no input reproducing such a failure is known.
     * A class is removed only once read, so it is read again after a fatal error.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    private fun read(): SnippetDeclarations {
        while (pending.isNotEmpty()) {
            val (scriptClass, name) = pending.first()
            read(scriptClass, name)
            pending.removeFirst()
        }
        return this
    }

    private fun read(scriptClass: Class<*>, snippet: String) {
        logger.readOr(Unit, { "$snippet: cannot read the classes of the snippet" }) {
            scriptClass.declaredClasses.forEach { declared ->
                // the InnerClasses attribute keeps the Kotlin visibility of a nested class, but internal, which is public
                if (!Modifier.isPrivate(declared.modifiers)) classes += declared.simpleName
            }
        }
        val kClass = scriptClass.kotlin
        readAccessible(snippet, "properties", { kClass.declaredMemberProperties }) { properties += it.name }
        readAccessible(snippet, "functions", { kClass.declaredMemberFunctions }) { functions += it.name }
        readAccessible(snippet, "extension properties", { kClass.declaredMemberExtensionProperties }) {
            extensionProperties.addReceiver(it, snippet)
        }
        readAccessible(snippet, "extension functions", { kClass.declaredMemberExtensionFunctions }) {
            extensionFunctions.addReceiver(it, snippet)
        }
    }

    /** Reads the [members] of a kind of the [snippet], passing the ones accessible by a later snippet to [add]. */
    private inline fun readAccessible(
        snippet: String,
        kind: String,
        members: () -> Collection<KCallable<*>>,
        add: (KCallable<*>) -> Unit
    ) {
        logger.readOr(emptyList(), { "$snippet: cannot read the $kind of the snippet" }) { members() }.forEach { member ->
            // kept if unknown, as the hint was given before the accessibility was checked
            val accessible = logger.readOr(true, { "$snippet: cannot read the JVM member of ${member.name} of the snippet" }) {
                member.isAccessibleByLaterSnippet(snippet)
            }
            if (accessible) add(member)
        }
    }

    /** Adds the receiver of this [extension] by its name, not added if it cannot be read, as no receiver could be matched then. */
    private fun MutableMap<String, MutableSet<ExtensionReceiver>>.addReceiver(extension: KCallable<*>, snippet: String) {
        logger.readOr(null, { "$snippet: cannot read the receiver of ${extension.name} of the snippet" }) {
            extension.extensionReceiverParameter?.type?.let { ExtensionReceiver.of(it) }
        }?.let { receiver ->
            getOrPut(extension.name) { LinkedHashSet() } += receiver
        }
    }

    /**
     * Whether a later snippet can access this member of the [snippet], told by its JVM member,
     * as kotlin-reflect reads every member of a snippet as public, the K2 REPL compiler not writing its visibility to the metadata:
     * a `private` member is private, or has no accessor, like a `private val` of a field only,
     * while an `internal` one is public, but its name is mangled with the module, the [snippet], like `getCount$snippet1_kts`,
     * which a later snippet does not call, like `getCount$main`.
     * A property is told by its getter only: one of a field only, also a public `const val` or `@JvmField` one, is not accessible,
     * as a later snippet fails on it in any declaration, the compiler crashing on the former, and reading the latter as an instance field, which is static.
     */
    private fun KCallable<*>.isAccessibleByLaterSnippet(snippet: String): Boolean {
        val jvmMember: Member? = when (this) {
            is KProperty<*> -> javaGetter
            is KFunction<*> -> javaMethod
            else -> null
        }
        val internalSuffix = "$" + snippet.replace(nonIdentifierCharacter, "_")
        return jvmMember != null && !Modifier.isPrivate(jvmMember.modifiers) && !jvmMember.name.endsWith(internalSuffix)
    }

}

/**
 * The receiver of an extension declared by a snippet, of the simple [name] of its class, like `String` or `Entry`,
 * or of the upper bound of a type parameter, `Any` if unbounded, taking `null` if [nullable].
 */
internal data class ExtensionReceiver(
    val name: String,
    val nullable: Boolean
) {

    /**
     * Whether an extension of this receiver can be called on the receiver of this [type], as rendered by a compiler message,
     * like `List<Int>`, `Map.Entry<Int, Int>` or `String?`, matched by the simple name of its class only,
     * so not on a subtype, like `MutableList<Int>` of `List`, which cannot be told by the name.
     */
    fun accepts(type: String): Boolean {
        val typeNullable = type.endsWith("?")
        val typeName = type.removeSuffix("?").removeSuffix("!").substringBefore('<').substringAfterLast('.')
        return (name == "Any" || name == typeName) && (nullable || !typeNullable)
    }

    override fun toString(): String = if (nullable) "$name?" else name

    companion object {

        /** The receiver of this [type], `null` if it is not a class, nor a type parameter of a single bound of a class, like a function type. */
        fun of(type: KType): ExtensionReceiver? = when (val classifier = type.classifier) {
            is KClass<*> -> classifier.simpleName?.let { ExtensionReceiver(it, type.isMarkedNullable) }
            is KTypeParameter -> classifier.upperBounds.singleOrNull()?.let { bound ->
                of(bound)?.let { it.copy(nullable = it.nullable || type.isMarkedNullable) }
            }
            else -> null
        }

    }

}
