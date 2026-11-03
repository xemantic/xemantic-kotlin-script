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
import kotlin.reflect.full.isSubtypeOf
import kotlin.reflect.full.withNullability
import kotlin.reflect.typeOf

/**
 * A value provided to the snippets of a [ScriptSession], as a property of the session.
 *
 * A mutable collection type, like `MutableList<String>`, is seen by the snippets as such,
 * so they can modify the collection shared with the host,
 * while the same collection provided as a read-only type, like `List<String>`, cannot be modified by the snippets.
 * Note that the type inferred by [property] from a value of `mutableListOf()` is `MutableList`.
 * A read-only implementation of the Kotlin standard library, like `emptyList()`, cannot be provided as a mutable collection type,
 * while a Java collection is accepted, even if it is read-only, like the one of `listOf("a", "b")` or `Collections.unmodifiableList`,
 * as it cannot be told apart from a mutable one.
 *
 * @param name the name of the property, a Kotlin identifier, which is not a hard keyword like `in`.
 * @param type the type of the property as seen by the snippets,
 *   including its type arguments and nullability,
 *   where all the classes must be accessible to the snippets, i.e. public and not local.
 *   It cannot be a type parameter, or a platform type, like the types of the Java members obtained by reflection.
 *   Note that [typeOf] does not retain the receiver of a function type,
 *   so a property of type `Int.() -> Int` is seen by the snippets as `(Int) -> Int`.
 * @param value the value of the property, of the [type].
 */
public class ScriptProperty<T>(
    public val name: String,
    public val type: KType,
    public val value: T
) {

    init {
        require(name.matches(identifier) && name.any { it != '_' }) {
            "Property name is not a Kotlin identifier: '$name'"
        }
        require(name !in hardKeywords) {
            "Property name is a Kotlin keyword: '$name'"
        }
        type.typeParameter()?.let {
            throw IllegalArgumentException(
                "Property '$name' is of type ${it.name}, which is a type parameter - provide it as a concrete type"
            )
        }
        // rendered in the source of the session, where it would not compile, like `(Mutable)List<String!>!`
        require('!' !in type.toString()) {
            "Property '$name' is of platform type $type, which the snippets cannot refer to - provide it as a Kotlin type, e.g. with typeOf"
        }
        type.inaccessibleClass()?.let {
            throw IllegalArgumentException(
                "Property '$name' is of type ${it.name}, which is not accessible to the snippets - provide it as a public type"
            )
        }
        if (value == null) {
            require(type.isMarkedNullable) {
                "Property '$name' is null, while its type is not nullable: $type"
            }
        } else {
            require((type.classifier as? KClass<*>)?.isInstance(value) ?: true) {
                "Property '$name' of type $type has a value of class ${value::class.java.name}"
            }
            // a read-only Kotlin collection, like emptyList(), is a java.util.List as well
            require(type.mutableCollectionType()?.isInstance?.invoke(value) ?: true) {
                "Property '$name' of type $type has a value of class ${value::class.java.name}, which is a read-only collection"
            }
        }
    }

}

/**
 * Creates a [ScriptProperty] of the reified type [T].
 */
public inline fun <reified T> property(
    name: String,
    value: T
): ScriptProperty<T> = ScriptProperty(
    name, typeOf<T>(), value
)

/**
 * The declaration of this property in the snippet preceding the ones of the [session].
 *
 * The getter of a property of a value class type is named explicitly, like `getTimeout-UwyO8pc`,
 * as the K2 REPL compiler calls such a property declared in another snippet by the mangled name of its getter,
 * while it compiles the getter without mangling it, like `getTimeout`.
 */
internal fun ScriptProperty<*>.declaration(session: Int): String {
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
    return "${jvmName}val ${name.quoted()}: $type = ${ScriptPropertyValues::class.qualifiedName}.get($session, \"$name\") as $type"
}

/** The JVM name of the getter of this property, like `getName`, or `isActive` of `isActive`. */
private fun ScriptProperty<*>.getterName(): String =
    if (name.length > 2 && name.startsWith("is") && !name[2].isLowerCase()) name
    else "get" + name.replaceFirstChar { it.titlecase() }

/** This type as written in the Kotlin source, with the fully qualified names, quoted in backticks where needed. */
internal fun KType.toSource(): String {
    val kClass = classifier as KClass<*>
    // validated to be accessible, so neither local nor anonymous
    val name = (mutableCollectionType()?.name ?: kClass.qualifiedName!!).split('.').joinToString(".") { it.quoted() }
    val arguments = if (arguments.isEmpty()) "" else arguments.joinToString(prefix = "<", postfix = ">") { it.toSource() }
    return name + arguments + if (isMarkedNullable) "?" else ""
}

/**
 * The mutable collection type, like `kotlin.collections.MutableList`, if this is one.
 *
 * A mutable collection type and its read-only counterpart share the classifier, like `List::class`,
 * but only the mutable one is a subtype of the mutable type, also when abbreviated by a typealias.
 * The classifier selects the most specific mutable type, like `MutableList`, not its supertype `MutableCollection`.
 */
private fun KType.mutableCollectionType(): MutableCollectionType? =
    mutableCollectionTypes[classifier]?.takeIf { withNullability(false).isSubtypeOf(it.type) }

/**
 * A mutable collection type.
 *
 * @param name the qualified name, like `kotlin.collections.MutableList`.
 * @param type the type, like `MutableList<*>`, a supertype of all its parameterizations.
 * @param isInstance whether the value is of this type, and not of its read-only counterpart only,
 *   as checked by the cast of the property in the snippets.
 */
private class MutableCollectionType(
    val name: String,
    val type: KType,
    val isInstance: (Any) -> Boolean
)

private inline fun <reified T : Any> mutableCollectionType(
    name: String
) = MutableCollectionType(name, typeOf<T>()) { it is T }

/** The mutable collection types by the read-only collection classes, which are also their classifiers. */
private val mutableCollectionTypes: Map<KClass<*>, MutableCollectionType> = mapOf(
    Iterable::class to mutableCollectionType<MutableIterable<*>>("kotlin.collections.MutableIterable"),
    Collection::class to mutableCollectionType<MutableCollection<*>>("kotlin.collections.MutableCollection"),
    List::class to mutableCollectionType<MutableList<*>>("kotlin.collections.MutableList"),
    Set::class to mutableCollectionType<MutableSet<*>>("kotlin.collections.MutableSet"),
    Map::class to mutableCollectionType<MutableMap<*, *>>("kotlin.collections.MutableMap"),
    Map.Entry::class to mutableCollectionType<MutableMap.MutableEntry<*, *>>("kotlin.collections.MutableMap.MutableEntry"),
    Iterator::class to mutableCollectionType<MutableIterator<*>>("kotlin.collections.MutableIterator"),
    ListIterator::class to mutableCollectionType<MutableListIterator<*>>("kotlin.collections.MutableListIterator")
)

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
