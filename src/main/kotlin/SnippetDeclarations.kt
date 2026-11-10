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
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty
import kotlin.reflect.full.declaredMemberExtensionFunctions
import kotlin.reflect.full.declaredMemberExtensionProperties
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod

private val logger = KotlinLogging.logger {}

/**
 * The names of the declarations of the evaluated snippets which a later snippet can access,
 * for the hint at the K2 REPL compiler not resolving them within a declaration of an inferred type.
 *
 * A `private` declaration is left out, as it cannot be accessed by a later snippet at all,
 * and so is an `internal` property or function, as a later snippet calls it by a name it is not compiled to,
 * failing at runtime, see [isAccessible].
 *
 * The classes of the snippets are kept, and their members read by kotlin-reflect only when a name is looked up,
 * which is after a compilation error, as reading them takes long.
 * Accessed on the thread of the session only, like the snippets.
 */
internal class SnippetDeclarations {

    /** The classes of the snippets not read yet, with the names of the snippets. */
    private val pending = ArrayList<Pair<Class<*>, String>>()

    private val classes = HashSet<String>()

    private val properties = HashSet<String>()

    private val functions = HashSet<String>()

    private val extensionProperties = HashSet<String>()

    private val extensionFunctions = HashSet<String>()

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

    /** Whether an extension property of this [name] is declared by a snippet. */
    fun isExtensionProperty(name: String): Boolean = name in read().extensionProperties

    /** Whether an extension function of this [name] is declared by a snippet. */
    fun isExtensionFunction(name: String): Boolean = name in read().extensionFunctions

    /**
     * Reads the [pending] classes, each kind of the members of a class, and each member, on its own,
     * so a member kotlin-reflect fails on loses only itself, or only its kind of the members of the class,
     * no input reproducing such a failure is known.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    private fun read(): SnippetDeclarations {
        if (pending.isEmpty()) return this
        val read = pending.toList()
        pending.clear()
        read.forEach { (scriptClass, name) ->
            logger.readOr(Unit, { "$name: cannot read the classes of the snippet" }) {
                scriptClass.declaredClasses.forEach { declared ->
                    // the InnerClasses attribute keeps the Kotlin visibility of a nested class, but internal, which is public
                    if (!Modifier.isPrivate(declared.modifiers)) classes += declared.simpleName
                }
            }
            val kClass = scriptClass.kotlin
            properties.addAccessible(name, "properties") { kClass.declaredMemberProperties }
            functions.addAccessible(name, "functions") { kClass.declaredMemberFunctions }
            extensionProperties.addAccessible(name, "extension properties") { kClass.declaredMemberExtensionProperties }
            extensionFunctions.addAccessible(name, "extension functions") { kClass.declaredMemberExtensionFunctions }
        }
        return this
    }

    private inline fun MutableSet<String>.addAccessible(
        snippet: String,
        kind: String,
        crossinline members: () -> Collection<KCallable<*>>
    ) {
        logger.readOr(emptyList(), { "$snippet: cannot read the $kind of the snippet" }) { members() }.forEach { member ->
            // kept if unknown, as the hint was given before the accessibility was checked
            val accessible = logger.readOr(true, { "$snippet: cannot read the JVM member of ${member.name} of the snippet" }) {
                member.isAccessible
            }
            if (accessible) add(member.name)
        }
    }

    /**
     * Whether a later snippet can access this member of a snippet, told by its JVM member,
     * as kotlin-reflect reads every member of a snippet as public, the K2 REPL compiler not writing its visibility to the metadata:
     * a `private` member is private, or has no accessor, like a `private val` of a field only,
     * while an `internal` one is public, but its name is mangled with the name of the snippet, like `getCount$snippet1_kts`,
     * which a later snippet does not call, like `getCount$main`.
     */
    private val KCallable<*>.isAccessible: Boolean
        get() {
            val jvmMember: Member? = when (this) {
                is KProperty<*> -> javaGetter ?: javaField
                else -> (this as? KFunction<*>)?.javaMethod
            }
            return jvmMember != null && !Modifier.isPrivate(jvmMember.modifiers) && '$' !in jvmMember.name
        }

}
