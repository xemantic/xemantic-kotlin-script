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
import java.lang.reflect.Modifier

private val logger = KotlinLogging.logger {}

/** The suffix of the field of a delegated property, like `s$delegate` of `val s by lazy { … }`. */
private const val DELEGATE_SUFFIX = "\$delegate"

/**
 * The member read as `null` in the message of a [NullPointerException] of the JVM,
 * like `Snippet1.getS()` of `Cannot invoke "String.length()" because the return value of "Snippet1.getS()" is null`.
 */
private val nullMember = Regex("because (?:the return value of )?\"([^\"]+)\" is null")

/**
 * The property of this [name], declared by the [snippet], like `snippet3.kts`, which failed in the evaluation,
 * while the property was `null`, most likely never initialized, despite its type.
 *
 * @property className the name of the class of the [snippet], like `Snippet3`.
 * @property getter the name of the getter of the property, like `getS`, if it is declared.
 */
internal class UninitializedProperty(
    val name: String,
    val snippet: String,
    val className: String,
    val getter: String?
) {

    /** Whether this [frame] is of the [getter] of this property, reading the delegate of a delegated one. */
    fun isGetter(frame: StackTraceElement): Boolean =
        getter != null && frame.className == className && frame.methodName == getter

}

/**
 * The properties of the snippets which failed in the evaluation, which were `null` then, despite their type,
 * most likely as the snippets failed before initializing them, while the later snippets still see them.
 *
 * The properties of a snippet are the static fields of its class, read by the field, or by the getter,
 * like `Snippet1.s` or `Snippet1.getS()` in the message of a [NullPointerException] of the JVM,
 * while a delegated property fails in its getter, reading the `null` delegate, like `Snippet1.s$delegate`, to a local variable.
 * A property of a primitive type is never `null`, it is left zero, or `false`, instead.
 */
internal class UninitializedProperties {

    /** The properties by the members reading them, like `Snippet1.s` or `Snippet1.getS()`. */
    private val byMember = HashMap<String, UninitializedProperty>()

    /** The delegated properties by their getters, like `Snippet1.getS`. */
    private val byDelegateGetter = HashMap<String, UninitializedProperty>()

    /**
     * Keeps the properties of the [scriptClass] of the [snippet] which failed in the evaluation, which are `null`.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    fun keep(
        scriptClass: Class<*>,
        snippet: String
    ) {
        try {
            val methods by lazy {
                scriptClass.declaredMethods.filter { it.parameterCount == 0 }.mapTo(HashSet()) { it.name }
            }
            scriptClass.declaredFields.filter { field ->
                Modifier.isStatic(field.modifiers)
                    && !field.type.isPrimitive
                    // like the INSTANCE of the snippet
                    && field.type != scriptClass
                    // like the implicit receiver
                    && '$' !in field.name.removeSuffix(DELEGATE_SUFFIX)
                    && field.trySetAccessible()
                    && field.get(null) == null
            }.forEach { field ->
                val delegated = field.name.endsWith(DELEGATE_SUFFIX)
                val name = field.name.removeSuffix(DELEGATE_SUFFIX)
                val getter = getterName(name).takeIf { it in methods }
                val property = UninitializedProperty(name, snippet, scriptClass.name, getter)
                byMember["${scriptClass.name}.${field.name}"] = property
                if (getter != null) {
                    if (delegated) {
                        byDelegateGetter["${scriptClass.name}.$getter"] = property
                    } else {
                        byMember["${scriptClass.name}.$getter()"] = property
                    }
                }
            }
        } catch (e: Throwable) {
            if (e.isFatal) throw e
            logger.debug(e) { "$snippet: cannot read the properties of the snippet which failed" }
        }
    }

    /** The property read as `null` by the [exception] thrown at the [stackTrace], if it is one of these properties. */
    fun readBy(
        exception: NullPointerException,
        stackTrace: List<StackTraceElement>
    ): UninitializedProperty? = exception.read(null) { message }
        ?.let { nullMember.find(it) }?.groupValues?.get(1)?.let(byMember::get)
        ?: stackTrace.firstOrNull()?.let { byDelegateGetter["${it.className}.${it.methodName}"] }

}

/**
 * The name of the JVM getter of the Kotlin property of this name, like `getTimeout` of `timeout`,
 * or the name itself, if it starts with `is` followed by a character other than an ASCII lowercase letter, like `isOk`,
 * with only an ASCII first letter capitalized, like `getä` of `ä`, as the Kotlin compiler does.
 */
internal fun getterName(
    property: String
): String = if (property.length > 2 && property.startsWith("is") && property[2] !in 'a'..'z') {
    property
} else {
    "get" + property.replaceFirstChar { if (it in 'a'..'z') it.uppercaseChar() else it }
}
