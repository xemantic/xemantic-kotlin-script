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
import kotlin.reflect.typeOf

/**
 * A value provided to the snippets of a [ScriptSession], as a property of the session.
 *
 * A mutable collection type, like `MutableList<String>`, is seen by the snippets as such,
 * so they can modify the collection shared with the host,
 * while the same collection provided as a read-only type, like `List<String>`, is seen as read-only by the snippets.
 * Note that the type inferred by [property] from a value of `mutableListOf()` is `MutableList`.
 * A read-only collection of the Kotlin standard library, like `emptyList()`, is rejected as a value of a mutable collection type,
 * as it does not implement the mutable interface,
 * while a frozen collection implementing it, like the result of `buildList`, or a Java collection, like `Collections.unmodifiableList`,
 * is not detected, and fails when mutated.
 * Only the class of the value is checked against the type, as the type arguments are erased at runtime,
 * so a nested collection of another mutability than declared, like an `emptySet()` value in a `MutableMap<String, MutableSet<Int>>`,
 * is not detected either, and fails when mutated.
 * Only [typeOf] and the types of the members keep the mutability,
 * while `MutableList::class.createType(...)` yields a read-only `List` type, as `MutableList::class` is the same class as `List::class`.
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
        // a KType implemented by hand could not be rendered in the source of the session
        type.nonClassType()?.let {
            throw IllegalArgumentException(
                "Property '$name' is of type $it, which is not of a class - provide it as a class type, e.g. with typeOf"
            )
        }
        type.mismatchedArgumentsType()?.let {
            val kClass = it.classifier as KClass<*>
            throw IllegalArgumentException(
                "Property '$name' is of type $it, which has ${it.arguments.size} type arguments, " +
                        "while ${kClass.qualifiedName} takes ${kClass.typeParameterCount()} - provide it as a complete type, e.g. with typeOf"
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
            require((type.classifier as KClass<*>).isInstance(value)) {
                "Property '$name' of type $type has a value of class ${value::class.java.name}"
            }
            // a read-only Kotlin collection, like emptyList(), is a java.util.List as well
            type.mutableCollectionType()?.let {
                require(it.isInstance(value)) {
                    "Property '$name' of type $type has a value of class ${value::class.java.name}, which does not implement ${it.name}"
                }
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
    val mutable = mutableCollectionType()
    val source = if (mutable != null) {
        mutable.name.quotedQualified() + arguments.toSource()
    } else {
        (classifier as KClass<*>).toSource(arguments)
    }
    return source + if (isMarkedNullable) "?" else ""
}

/**
 * This class with the type [arguments] as written in the source, like `Outer<String>.Inner<Int>`.
 *
 * The arguments of a type of an inner class start with the ones of the class itself,
 * followed by the ones of its outer classes, from the enclosing one outwards, like `Inner<Int, String>` of `Outer<String>.Inner<Int>`,
 * while in the source each class takes its own arguments.
 */
private fun KClass<*>.toSource(arguments: List<KTypeProjection>): String {
    val own = arguments.take(typeParameters.size).toSource()
    return if (isInner) {
        java.declaringClass.kotlin.toSource(arguments.drop(typeParameters.size)) + "." + simpleName!!.quoted() + own
    } else {
        // validated to be accessible, so neither local nor anonymous
        qualifiedName!!.quotedQualified() + own
    }
}

private fun List<KTypeProjection>.toSource(): String =
    if (isEmpty()) "" else joinToString(prefix = "<", postfix = ">") { it.toSource() }

/** This qualified name with each of its segments quoted in backticks where needed. */
private fun String.quotedQualified(): String =
    split('.').joinToString(".") { it.quoted() }

/**
 * The mutable collection type, like `kotlin.collections.MutableList`, if this is one.
 *
 * A mutable collection type and its read-only counterpart share the classifier, like `List::class`,
 * but only the mutable one is a subtype of the mutable type, also when abbreviated by a typealias.
 * The classifier selects the most specific mutable type, like `MutableList`, not its supertype `MutableCollection`.
 */
private fun KType.mutableCollectionType(): MutableCollectionType? =
    mutableCollectionTypes[classifier]?.takeIf { it.isSupertypeOf(this) }

/**
 * A mutable collection type.
 *
 * @param name the qualified name, like `kotlin.collections.MutableList`.
 * @param type the nullable type, like `MutableList<*>?`, a supertype of all its parameterizations, also the nullable ones,
 *   while a [KType] not produced by kotlin-reflect is seen as the read-only type.
 * @param isInstance whether the value is of this type, and not of its read-only counterpart only,
 *   as checked by the cast of the property in the snippets.
 */
private class MutableCollectionType(
    val name: String,
    val type: KType,
    val isInstance: (Any) -> Boolean
) {

    /**
     * Whether the [other] type is a parameterization of this type, or `false` if it cannot be told.
     *
     * [isSubtypeOf] casts both the types to the implementation of kotlin-reflect,
     * so it fails for a [KType] implemented by hand, which is seen by the members of the interface only,
     * where a mutable collection type is indistinguishable from its read-only counterpart.
     */
    fun isSupertypeOf(other: KType): Boolean = try {
        other.isSubtypeOf(type)
    } catch (e: ClassCastException) {
        false
    }

}

private inline fun <reified T : Any> mutableCollectionType(
    name: String
) = MutableCollectionType(name, typeOf<T?>()) { it is T }

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

/** The first of this type, or of its type arguments, whose classifier is not a class, like the one of a [KType] implemented by hand. */
private fun KType.nonClassType(): KType? =
    takeUnless { classifier is KClass<*> } ?: arguments.firstNotNullOfOrNull { it.type?.nonClassType() }

/**
 * The first of this type, or of its type arguments, whose number of type arguments differs from the type parameters of its class,
 * like the one of a [KType] implemented by hand, provided all their classifiers are classes.
 */
private fun KType.mismatchedArgumentsType(): KType? =
    takeUnless { arguments.size == (classifier as KClass<*>).typeParameterCount() }
        ?: arguments.firstNotNullOfOrNull { it.type?.mismatchedArgumentsType() }

/** The number of the type parameters of this class, and of its outer classes, if it is an inner class, as the arguments of its type. */
private fun KClass<*>.typeParameterCount(): Int =
    typeParameters.size + if (isInner) java.declaringClass?.kotlin?.typeParameterCount() ?: 0 else 0

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
