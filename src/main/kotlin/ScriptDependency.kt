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

import org.jetbrains.kotlin.codegen.state.InfoForMangling
import org.jetbrains.kotlin.codegen.state.collectFunctionSignatureForManglingSuffix
import org.jetbrains.kotlin.codegen.state.md5base64
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.lexer.KtTokens
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KTypeProjection
import kotlin.reflect.KVariance
import kotlin.reflect.KVisibility
import kotlin.reflect.typeOf

/**
 * A value provided to the snippets of a [ScriptSession], as a property of the session.
 *
 * @param name the name of the property, a Kotlin identifier, which is not a hard keyword like `in`.
 * @param type the type of the property as seen by the snippets,
 *   including its type arguments and nullability,
 *   where all the classes must be accessible to the snippets, i.e. public and not local.
 *   It cannot be a type parameter, or a platform type, like the types of the Java members obtained by reflection.
 *   Note that [typeOf] does not retain the receiver of a function type,
 *   so a dependency of type `Int.() -> Int` is seen by the snippets as `(Int) -> Int`.
 * @param value the value of the property, of the [type].
 */
public class ScriptDependency<T>(
    public val name: String,
    public val type: KType,
    public val value: T
) {

    init {
        require(name.matches(identifier) && name.any { it != '_' }) {
            "Dependency name is not a Kotlin identifier: '$name'"
        }
        require(name !in hardKeywords) {
            "Dependency name is a Kotlin keyword: '$name'"
        }
        type.typeParameter()?.let {
            throw IllegalArgumentException(
                "Dependency '$name' is of type ${it.name}, which is a type parameter - provide it as a concrete type"
            )
        }
        // rendered in the source of the session, where it would not compile, like `(Mutable)List<String!>!`
        require('!' !in type.toString()) {
            "Dependency '$name' is of platform type $type, which the snippets cannot refer to - provide it as a Kotlin type, e.g. with typeOf"
        }
        type.inaccessibleClass()?.let {
            throw IllegalArgumentException(
                "Dependency '$name' is of type ${it.name}, which is not accessible to the snippets - provide it as a public type"
            )
        }
        if (value == null) {
            require(type.isMarkedNullable) {
                "Dependency '$name' is null, while its type is not nullable: $type"
            }
        } else {
            require((type.classifier as? KClass<*>)?.isInstance(value) ?: true) {
                "Dependency '$name' of type $type has a value of class ${value::class.java.name}"
            }
        }
    }

}

/**
 * Creates a [ScriptDependency] of the reified type [T].
 */
public inline fun <reified T> dependency(
    name: String,
    value: T
): ScriptDependency<T> = ScriptDependency(
    name, typeOf<T>(), value
)

/**
 * The declaration of this dependency in the snippet preceding the ones of the [session].
 *
 * The getter of a property of a value class type is named explicitly, like `getTimeout-UwyO8pc`,
 * as the K2 REPL compiler calls such a property declared in another snippet by the mangled name of its getter,
 * while it compiles the getter without mangling it, like `getTimeout`.
 */
internal fun ScriptDependency<*>.declaration(session: Int): String {
    val type = this.type.toSource()
    val kClass = this.type.classifier as KClass<*>
    val jvmName = if (kClass.isValue) {
        val suffix = md5base64(
            // a getter has no parameters, and is mangled for its return type
            collectFunctionSignatureForManglingSuffix(
                false,
                false,
                emptyList(),
                InfoForMangling(FqNameUnsafe(kClass.qualifiedName!!), isValue = true, isNullable = this.type.isMarkedNullable)
            )!!
        )
        "@get:JvmName(\"${getterName()}-$suffix\") "
    } else {
        ""
    }
    return "${jvmName}val ${name.quoted()}: $type = ${ScriptDependencyValues::class.qualifiedName}.get($session, \"$name\") as $type"
}

/** The JVM name of the getter of the property of this dependency, like `getName`, or `isActive` of `isActive`. */
private fun ScriptDependency<*>.getterName(): String =
    if (name.length > 2 && name.startsWith("is") && !name[2].isLowerCase()) name
    else "get" + name.replaceFirstChar { it.titlecase() }

/** This type as written in the Kotlin source, with the fully qualified names, quoted in backticks where needed. */
internal fun KType.toSource(): String {
    val kClass = classifier as KClass<*>
    // validated to be accessible, so neither local nor anonymous
    val name = kClass.qualifiedName!!.split('.').joinToString(".") { it.quoted() }
    val arguments = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">") { it.toSource() }
    return name + arguments + if (isMarkedNullable) "?" else ""
}

private fun KTypeProjection.toSource(): String = when (variance) {
    null -> "*"
    KVariance.INVARIANT -> type!!.toSource()
    KVariance.IN -> "in ${type!!.toSource()}"
    KVariance.OUT -> "out ${type!!.toSource()}"
}

/** This name, quoted in backticks, if it is not an identifier, or it is a hard keyword. */
private fun String.quoted(): String =
    if (matches(identifier) && this !in hardKeywords) this else "`$this`"

private val identifier = Regex("[\\p{L}\\p{Nl}_][\\p{L}\\p{Nl}\\p{Nd}_]*")

/** The keywords, like `in` or `class`, which a snippet could not use as a name, unless quoted in backticks. */
private val hardKeywords = KtTokens.KEYWORDS.types.mapTo(HashSet()) { (it as KtKeywordToken).value }

/** The first type parameter of this type, or of its type arguments, like `T` of a generic function. */
private fun KType.typeParameter(): KTypeParameter? =
    classifier as? KTypeParameter ?: arguments.firstNotNullOfOrNull { it.type?.typeParameter() }

/** The first class of this type, or of its type arguments, which the snippets cannot refer to. */
private fun KType.inaccessibleClass(): Class<*>? =
    (classifier as? KClass<*>)?.java?.takeUnless { it.isAccessible() }
        ?: arguments.firstNotNullOfOrNull { it.type?.inaccessibleClass() }

private fun Class<*>.isAccessible(): Boolean = if (isArray) {
    componentType.isAccessible()
} else {
    !isLocalClass
            && !isAnonymousClass
            && Modifier.isPublic(modifiers)
            && !isKotlinInternal()
            && declaringClass?.isAccessible() ?: true
}

/** An internal Kotlin class is public in the bytecode. */
private fun Class<*>.isKotlinInternal(): Boolean =
    isAnnotationPresent(Metadata::class.java) && kotlin.visibility == KVisibility.INTERNAL
