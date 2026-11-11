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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KProperty1
import kotlin.reflect.full.declaredMemberProperties
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.jvm.javaField
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.typeOf

private val logger = KotlinLogging.logger {}

/** The suffix of the field of a delegated property, like `s$delegate` of `val s by lazy { … }`. */
private const val DELEGATE_SUFFIX = "\$delegate"

/** The name of the property of the value of the last expression of a snippet, like `res0`, declared by the REPL, not by the snippet. */
private val resultProperty = Regex("res\\d+")

/**
 * The member read as `null` in the message of a [NullPointerException] of the JVM,
 * like `Snippet1.getS()` of `Cannot invoke "String.length()" because the return value of "Snippet1.getS()" is null`.
 */
private val nullMember = Regex("because (?:the return value of )?\"([^\"]+)\" is null")

/** The type of `null`, a subtype of every type which takes `null`, also of a platform type, like `String!` of `System.getProperty`. */
private val nullType = typeOf<Nothing?>()

/**
 * The property of this [name], declared by the [snippet], like `snippet3.kts`, which failed in the evaluation
 * before initializing it, so it is `null`, despite its type.
 *
 * @property className the name of the class of the [snippet], like `Snippet3`.
 * @property getter the name of the getter of the property, like `getS`, if it is declared.
 * @property mutable whether it is a `var`, which can be assigned, instead of declaring it again.
 */
internal class UninitializedProperty(
    val name: String,
    val snippet: String,
    val className: String,
    val getter: String?,
    val mutable: Boolean
) {

    /** Whether this [frame] is of the [getter] of this property, reading the delegate of a delegated one. */
    fun isGetter(frame: StackTraceElement): Boolean =
        getter != null && frame.className == className && frame.methodName == getter

}

/**
 * The properties of the snippets which failed in the evaluation before initializing them, `null` despite their type,
 * while the later snippets still see them.
 *
 * The properties of a snippet are the static fields of its class, read by the field, or by the getter,
 * like `Snippet1.s` or `Snippet1.getS()` in the message of a [NullPointerException] of the JVM,
 * while a delegated property fails in its getter, reading the `null` delegate, like `Snippet1.s$delegate`, to a local variable.
 */
internal class UninitializedProperties {

    /** The properties by the members reading them, like `Snippet1.s` or `Snippet1.getS()`. */
    private val byMember = HashMap<String, UninitializedProperty>()

    /** The delegated properties by their getters, like `Snippet1.getS`. */
    private val byDelegateGetter = HashMap<String, UninitializedProperty>()

    /**
     * Keeps, and returns, the properties of the [scriptClass] of the [snippet] which failed in the evaluation,
     * which are left uninitialized: `null`, although their type does not take `null`, read by kotlin-reflect,
     * as the K2 REPL compiler does not annotate the nullability, or delegated with a `null` delegate, whatever their type.
     * A property of a nullable type, or of a platform type, like of `System.getProperty`, is `null` on purpose,
     * a `lateinit` one throws when uninitialized, and the property of the result of the snippet, like `res0`,
     * which is `null` as well, is not declared by the snippet,
     * while a property of a primitive type is left zero, or `false`, which a value cannot be told from.
     * A `null` property of a type kotlin-reflect fails on is kept, for the hint at reading it, while not returned, as it might take `null`.
     * In the order of the fields of the class, the one of the declarations on HotSpot, as kotlin-reflect sorts the properties by their names.
     * Empty if the properties cannot be read.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun keep(
        scriptClass: Class<*>,
        snippet: String
    ): List<UninitializedProperty> = logger.runOr(emptyList(), { "$snippet: cannot read the properties of the snippet which failed" }) {
        // a property of a custom getter has no field; read per property, so a property kotlin-reflect fails on loses only itself,
        // no input reproducing such a failure is known
        val properties = scriptClass.kotlin.declaredMemberProperties.mapNotNull { property ->
            logger.runOr(null, { "$snippet: cannot read the field of the property ${property.name} of the snippet which failed" }) {
                property.javaField?.let { it.name to property }
            }
        }.toMap()
        scriptClass.declaredFields.mapNotNull { field ->
            properties[field.name]?.let { property ->
                logger.runOr(null, { "$snippet: cannot read the property ${property.name} of the snippet which failed" }) {
                    uninitialized(property, field, scriptClass, snippet)
                }
            }
        }
    }

    private fun uninitialized(
        property: KProperty1<*, *>,
        field: Field,
        scriptClass: Class<*>,
        snippet: String
    ): UninitializedProperty? {
        // a property of the snippet is static
        if (!Modifier.isStatic(field.modifiers)) return null
        if (
            property.isLateinit
            || field.type.isPrimitive
            // the result property is the only one with a public field, unless annotated with @JvmField
            || (Modifier.isPublic(field.modifiers) && field.name.matches(resultProperty))
        ) return null
        val delegated = field.name.endsWith(DELEGATE_SUFFIX)
        // whether the type takes null, unknown if kotlin-reflect fails on the type; no input reproducing such a failure is known
        val nullable = if (delegated) false else logger.runOr(null, { "$snippet: cannot read the type of the property ${property.name}" }) {
            nullType.isSubtypeOf(property.returnType)
        }
        if (nullable == true) return null
        if (!field.trySetAccessible() || field.get(null) != null) return null
        val getter = property.javaGetter?.name
        val uninitialized = UninitializedProperty(property.name, snippet, scriptClass.name, getter, property is KMutableProperty<*>)
        byMember["${scriptClass.name}.${field.name}"] = uninitialized
        if (getter != null) {
            if (delegated) {
                byDelegateGetter["${scriptClass.name}.$getter"] = uninitialized
            } else {
                byMember["${scriptClass.name}.$getter()"] = uninitialized
            }
        }
        return if (nullable == null) null else uninitialized
    }

    /** The property read as `null` by the [exception] thrown at the [stackTrace], if it is one of these properties. */
    fun readBy(
        exception: NullPointerException,
        stackTrace: List<StackTraceElement>
    ): UninitializedProperty? = exception.read(null) { message }
        ?.let { nullMember.find(it) }?.groupValues?.get(1)?.let(byMember::get)
        ?: stackTrace.firstOrNull()?.let { byDelegateGetter["${it.className}.${it.methodName}"] }

}
