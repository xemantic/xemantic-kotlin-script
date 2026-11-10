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
import kotlin.jvm.functions.FunctionN
import kotlin.jvm.internal.FunctionBase
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
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
 *   Nor does it tell a suspend function reference type, like `KSuspendFunction1<String, Int>`, from `KFunction1<String, Int>`,
 *   so the snippets see the former if the value is a suspend function, and the latter otherwise.
 *   Only the value of the property itself is told apart, if kotlin-reflect can resolve its function, unlike a local one,
 *   while a function reference type the value cannot tell, like a nested one, as in `List<KFunction1<String, Int>>`,
 *   is seen as `KFunction<Int>`, which a reference of either is an instance of,
 *   also `KFunction0<Int>`, which [typeOf] produces like `KFunction<Int>`.
 *   Such a widened type is safe only in a covariant position, so elsewhere the snippets see the type projected,
 *   like `MutableList<out KFunction<Int>>` of `MutableList<KFunction1<String, Int>>`, or `Function1<*, Int>` of `(KFunction1<String, Int>) -> Int`,
 *   so they cannot pass a function of another kind to the host,
 *   while a function type of more than 22 parameters, which cannot be projected, is seen as `FunctionN<R>`.
 *   The value of a function type of more than 22 parameters is checked to take as many parameters,
 *   unless they cannot be told, like of a class implementing `FunctionN` by hand without a typed `invoke` method.
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
        // a KType implemented by hand could not be rendered in the source of the session
        type.nonClassType()?.let {
            throw IllegalArgumentException(
                "Property '$name' is of type $it, which is not of a class - provide it as a class type, e.g. with typeOf"
            )
        }
        type.mismatchedArguments()?.let { (mismatched, expected) ->
            val arguments = mismatched.arguments.size
            throw IllegalArgumentException(
                "Property '$name' is of type $mismatched, which has $arguments type argument${if (arguments == 1) "" else "s"}, " +
                        "while ${(mismatched.classifier as KClass<*>).qualifiedName} takes $expected - provide it as a complete type, e.g. with typeOf"
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
            // not checked by the declaration of the property, see declaration
            if (type.isBigArityFunctionType()) {
                val parameters = type.arguments.size - 1
                (value as Function<*>).arity()?.let { arity ->
                    require(arity == parameters) {
                        "Property '$name' of type $type has a value of class ${value::class.java.name}, which takes $arity parameters, while the type takes $parameters"
                    }
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
 *
 * The value of a function type of more than 22 parameters is cast by [ScriptPropertyValues.getUnchecked]
 * to its erased class only, `FunctionN`, already validated by [ScriptProperty],
 * as a cast to the function type, like `as (Int, …, Int) -> Unit`, checks the arity of the function,
 * which a lambda of more than 22 parameters compiled by Kotlin does not implement.
 */
internal fun ScriptProperty<*>.declaration(session: Int): String {
    val type = this.type.toSource(isSuspend = isSuspendFunctionReference())
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
    val values = ScriptPropertyValues::class.qualifiedName
    val value = if (this.type.isBigArityFunctionType()) {
        "$values.getUnchecked<$type>($session, \"$name\")"
    } else {
        "$values.get($session, \"$name\") as $type"
    }
    return "${jvmName}val ${name.quoted()}: $type = $value"
}

/**
 * Whether the value of this property of a function reference type, like `KFunction1<String, Int>`, is a suspend function,
 * `false` if it is `null`, or `null` if it cannot be told, like of a local function, which kotlin-reflect cannot resolve.
 */
private fun ScriptProperty<*>.isSuspendFunctionReference(): Boolean? = when {
    !type.isFunctionReferenceType() -> null
    value == null -> false
    else -> runCatching { (value as KFunction<*>).isSuspend }.getOrNull()
}

/** The JVM name of the getter of this property, like `getName`, or `isActive` of `isActive`. */
private fun ScriptProperty<*>.getterName(): String =
    if (name.length > 2 && name.startsWith("is") && !name[2].isLowerCase()) name
    else "get" + name.replaceFirstChar { it.titlecase() }

/**
 * This type as written in the Kotlin source, with the fully qualified names, quoted in backticks where needed.
 *
 * The types of the [arityErasedClasses] are rendered with their arity, like `KFunction1<String, Int>`,
 * or a function type, like `(Int, …, Int) -> Unit` of `FunctionN`,
 * while a `KFunction` type of a single argument, the return type, is rendered as such,
 * as it is either a `KFunction<R>` or a `KFunction0<R>`, which [typeOf] produces alike.
 *
 * A function reference type which cannot be told apart, rendered as `KFunction<R>`, is a supertype of the actual one,
 * so it is rendered as such only in a covariant position, see [Position],
 * while elsewhere the type argument containing it is projected, with `out` in an invariant position, or with a star otherwise,
 * and a function type of more than 22 parameters, whose parameters cannot be projected, is rendered as `FunctionN<R>`.
 *
 * @param isSuspend whether this function reference type is of a suspend function, like `KSuspendFunction1<String, Int>`,
 *   which [typeOf] produces like `KFunction1<String, Int>`, so it can be told by the value only,
 *   or `null` if it cannot be told, like of a nested type, rendered then as `KFunction<Int>`, which a reference of either is an instance of.
 */
internal fun KType.toSource(isSuspend: Boolean? = null): String =
    // nothing fails to render in a covariant position, as it can be widened
    toSource(isSuspend, Position.OUT)!!

/**
 * The position of a type in the type of the property, which tells whether the snippets may see a supertype of it.
 *
 * The type of the property is in a covariant position, read by the snippets only,
 * while a parameter of a function type of the property is in a contravariant one, passed by the snippets to the host.
 */
private enum class Position {

    /** A position where a supertype is safe, as the snippets only read a value of it. */
    OUT,

    /** A position where only a subtype is safe, as the snippets pass a value of it to the host. */
    IN,

    /** A position where the type must be exact, like the element type of a `MutableList`. */
    INVARIANT;

    /** The position of a type argument of this [variance], or of a type parameter declared of it, nested in this position. */
    fun nested(variance: KVariance): Position = when {
        this == INVARIANT || variance == KVariance.INVARIANT -> INVARIANT
        variance == KVariance.OUT -> this
        this == OUT -> IN
        else -> OUT
    }

}

/** This type as written in the source in this [position], or `null` if it cannot be rendered exactly where it has to be. */
private fun KType.toSource(isSuspend: Boolean?, position: Position): String? {
    val kClass = classifier as KClass<*>
    val mutable = mutableCollectionType()
    val source = when {
        mutable != null -> arguments.toSource(mutable.variances, position)?.let { mutable.name.quotedQualified() + it }
        isFunctionReferenceType() -> {
            val arity = arguments.size - 1
            val variances = List(arity) { KVariance.IN } + KVariance.OUT
            when (isSuspend) {
                null -> widenedFunctionReferenceSource(position)
                true -> arguments.toSource(variances, position)?.let { "kotlin.reflect.KSuspendFunction$arity$it" }
                false -> arguments.toSource(variances, position)?.let { "kotlin.reflect.KFunction$arity$it" }
            }
        }
        // a KFunction<R> type of typeOf might be of a KFunction0<R>, or of a KSuspendFunction0<R>
        kClass == KFunction::class -> widenedFunctionReferenceSource(position)
        isBigArityFunctionType() -> {
            val parameters = arguments.dropLast(1).map { it.type!!.toSource(null, position.nested(KVariance.IN)) }
            val returnType = arguments.last().type!!.toSource(null, position.nested(KVariance.OUT))
            when {
                null !in parameters && returnType != null -> {
                    val functionType = "${parameters.joinToString(prefix = "(", postfix = ")")} -> $returnType"
                    if (isMarkedNullable) "($functionType)" else functionType
                }
                // the supertype of all the function types of more than 22 parameters
                position == Position.OUT -> "kotlin.jvm.functions.FunctionN<${arguments.last().type!!.toSource()}>"
                else -> null
            }
        }
        else -> kClass.toSource(arguments, position)
    }
    return source?.plus(if (isMarkedNullable) "?" else "")
}

/** This function reference type as `KFunction<R>`, a supertype of a reference of any arity, also a suspend one, so only in a covariant [position]. */
private fun KType.widenedFunctionReferenceSource(position: Position): String? =
    if (position == Position.OUT) {
        "kotlin.reflect.KFunction" + listOf(arguments.last()).toSource(listOf(KVariance.OUT), position)
    } else {
        null
    }

/**
 * This class with the type [arguments] as written in the source in this [position], like `Outer<String>.Inner<Int>`,
 * or `null` if they cannot be rendered.
 *
 * The arguments of a type of an inner class start with the ones of the class itself,
 * followed by the ones of its outer classes, from the enclosing one outwards, like `Inner<Int, String>` of `Outer<String>.Inner<Int>`,
 * while in the source each class takes its own arguments.
 */
private fun KClass<*>.toSource(arguments: List<KTypeProjection>, position: Position): String? {
    val own = arguments.take(typeParameters.size).toSource(typeParameters.map { it.variance }, position) ?: return null
    return if (isInner) {
        java.declaringClass.kotlin.toSource(arguments.drop(typeParameters.size), position)?.let {
            it + "." + simpleName!!.quoted() + own
        }
    } else {
        // validated to be accessible, so neither local nor anonymous
        qualifiedName!!.quotedQualified() + own
    }
}

/**
 * These type arguments of the type parameters declared of these [variances] as written in the source in this [position],
 * or `null` if any of them cannot be rendered.
 */
private fun List<KTypeProjection>.toSource(variances: List<KVariance>, position: Position): String? =
    if (isEmpty()) {
        ""
    } else {
        mapIndexed { index, argument ->
            argument.toSource(variances[index], position) ?: return null
        }.joinToString(prefix = "<", postfix = ">")
    }

/**
 * This type argument of a type parameter declared of this [variance] as written in the source in this [position],
 * projected if its type cannot be rendered exactly where it has to be, provided the projection is a supertype,
 * i.e. in a covariant position, or `null` otherwise.
 */
private fun KTypeProjection.toSource(variance: KVariance, position: Position): String? {
    val type = type ?: return "*"
    val projection = this.variance!!
    val effective = if (projection == KVariance.INVARIANT) variance else projection
    val prefix = when (projection) {
        KVariance.INVARIANT -> ""
        KVariance.IN -> "in "
        KVariance.OUT -> "out "
    }
    type.toSource(null, position.nested(effective))?.let { return prefix + it }
    return when {
        position != Position.OUT -> null
        effective == KVariance.INVARIANT -> "out " + type.toSource()
        else -> "*"
    }
}

/** Whether this is a function reference type of [typeOf], like `KFunction1<String, Int>`, with the parameter types as arguments. */
private fun KType.isFunctionReferenceType(): Boolean =
    classifier == KFunction::class && arguments.size > 1

/**
 * Whether this is a function type of more than 22 parameters of [typeOf], with the invariant parameter types and the return type as arguments,
 * while the class `FunctionN` takes only the return type.
 */
private fun KType.isBigArityFunctionType(): Boolean =
    classifier == FunctionN::class && arguments.size > 23 && arguments.all { it.variance == KVariance.INVARIANT }

/**
 * The number of the parameters of this function, or `null` if it cannot be told.
 *
 * A Kotlin lambda of more than 22 parameters does not implement `FunctionBase.getArity`,
 * but it declares an `invoke` method of its parameters, besides the one of `FunctionN` taking them as an array.
 */
private fun Function<*>.arity(): Int? = try {
    (this as? FunctionBase<*>)?.arity
} catch (_: AbstractMethodError) {
    null
} ?: javaClass.methods.filter { method ->
    method.name == "invoke"
            && !method.isBridge
            && !method.isSynthetic
            && !method.parameterTypes.contentEquals(arrayOf(Array<Any?>::class.java))
}.map { it.parameterCount }.distinct().singleOrNull()

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
 * @param variances the variances of its type parameters, which differ from the ones of its read-only counterpart,
 *   like `E` of `MutableList`, invariant, while `out` of `List`.
 * @param isInstance whether the value is of this type, and not of its read-only counterpart only,
 *   as checked by the cast of the property in the snippets.
 */
private class MutableCollectionType(
    val name: String,
    val type: KType,
    val variances: List<KVariance>,
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
    name: String,
    vararg variances: KVariance
) = MutableCollectionType(name, typeOf<T?>(), variances.toList()) { it is T }

/** The mutable collection types by the read-only collection classes, which are also their classifiers. */
private val mutableCollectionTypes: Map<KClass<*>, MutableCollectionType> = mapOf(
    Iterable::class to mutableCollectionType<MutableIterable<*>>("kotlin.collections.MutableIterable", KVariance.OUT),
    Collection::class to mutableCollectionType<MutableCollection<*>>("kotlin.collections.MutableCollection", KVariance.INVARIANT),
    List::class to mutableCollectionType<MutableList<*>>("kotlin.collections.MutableList", KVariance.INVARIANT),
    Set::class to mutableCollectionType<MutableSet<*>>("kotlin.collections.MutableSet", KVariance.INVARIANT),
    Map::class to mutableCollectionType<MutableMap<*, *>>("kotlin.collections.MutableMap", KVariance.INVARIANT, KVariance.INVARIANT),
    Map.Entry::class to mutableCollectionType<MutableMap.MutableEntry<*, *>>(
        "kotlin.collections.MutableMap.MutableEntry", KVariance.INVARIANT, KVariance.INVARIANT
    ),
    Iterator::class to mutableCollectionType<MutableIterator<*>>("kotlin.collections.MutableIterator", KVariance.OUT),
    ListIterator::class to mutableCollectionType<MutableListIterator<*>>("kotlin.collections.MutableListIterator", KVariance.INVARIANT)
)

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
 * The first of this type, or of its type arguments, whose type arguments do not match the type parameters of its class,
 * like the one of a [KType] implemented by hand, paired with the description of the expected number of them,
 * provided all their classifiers are classes, accessible to the snippets.
 *
 * The classes of the types produced by [typeOf] with the parameter types and the return type as arguments,
 * while the class takes only the return type, take more:
 * `KFunction` of a function reference type, like `KFunction1<String, Int>` or `KSuspendFunction1<String, Int>`,
 * and `FunctionN` of a function type of more than 22 parameters, which, unless the plain `FunctionN<R>`,
 * has 23 parameters at least, a receiver included, of invariant types.
 */
private fun KType.mismatchedArguments(): Pair<KType, String>? {
    val expected = when (val kClass = classifier as KClass<*>) {
        KFunction::class -> "1 or more".takeIf { arguments.isEmpty() }
        FunctionN::class -> "1, or 24 or more invariant ones of a function type".takeUnless {
            arguments.size == 1 || isBigArityFunctionType()
        }
        else -> kClass.typeParameterCount().takeIf { it != arguments.size }?.toString()
    }
    if (expected != null) return this to expected
    return arguments.firstNotNullOfOrNull { it.type?.mismatchedArguments() }
}

/**
 * The number of the type parameters of this class, and of its outer classes, if it is an inner class, as the arguments of its type.
 *
 * `KClass.createType` checks the same, but only for the type arguments of kotlin-reflect, failing on a [KType] implemented by hand,
 * like a nested type argument of one, and without telling the expected number but in its message.
 */
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
            && !isKotlinNonClass()
            && !isKotlinInternal()
            && declaringClass?.isAccessible() ?: true
}

/**
 * A Kotlin file facade, like `kotlin.collections.CollectionsKt`, or another class compiled by Kotlin not of a class declaration,
 * like a lambda, which the source cannot refer to, while it may be public in the bytecode.
 */
private fun Class<*>.isKotlinNonClass(): Boolean =
    getAnnotation(Metadata::class.java)?.let { it.kind != 1 } ?: false

/** An internal Kotlin class is public in the bytecode. */
private fun Class<*>.isKotlinInternal(): Boolean =
    isAnnotationPresent(Metadata::class.java) && kotlin.visibility == KVisibility.INTERNAL
