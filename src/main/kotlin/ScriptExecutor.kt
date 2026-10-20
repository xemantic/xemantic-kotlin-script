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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.intellij.lang.annotations.Language
import org.jetbrains.kotlin.lexer.KtKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeParameter
import kotlin.reflect.KVisibility
import kotlin.reflect.typeOf

/**
 * Executes Kotlin scripts, typically written by an LLM.
 *
 * Failures are reported as [ScriptResult.Error], with a message
 * which points to the offending lines of the script.
 */
public interface ScriptExecutor {

    /**
     * A property provided to the script.
     *
     * @param name the name of the property, a Kotlin identifier, which is not a hard keyword like `in`.
     * @param type the type of the property as seen by the script,
     *   including its type arguments and nullability,
     *   where all the classes must be accessible to the script, i.e. public and not local.
     *   It cannot be a type parameter, or a platform type, like the types of the Java members obtained by reflection.
     *   Note that [typeOf] does not retain the receiver of a function type,
     *   so a dependency of type `Int.() -> Int` is seen by the script as `(Int) -> Int`.
     * @param value the value of the property, of the [type].
     */
    public class Dependency<T>(
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
            // rendered in the source of the script, where it would not compile, like `(Mutable)List<String!>!`
            require('!' !in type.toString()) {
                "Dependency '$name' is of platform type $type, which the script cannot refer to - provide it as a Kotlin type, e.g. with typeOf"
            }
            type.inaccessibleClass()?.let {
                throw IllegalArgumentException(
                    "Dependency '$name' is of type ${it.name}, which is not accessible to the script - provide it as a public type"
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
     * Executes the [script] in the coroutine of the caller, with a [CoroutineScope] receiver,
     * so the caller awaits all the coroutines it launched, and its cancellation cancels them.
     * Note that cancellation cannot interrupt a blocking call of the script, like `Thread.sleep`.
     */
    public suspend fun execute(
        @Language("kotlin") script: String,
        dependencies: List<Dependency<*>> = emptyList(),
    ): ScriptResult

}

/**
 * Creates the default [ScriptExecutor].
 *
 * @param dispatcher the dispatcher of the compilation and of the script body,
 *   [Dispatchers.IO] by default, as both might block.
 *   The coroutines launched by the script inherit it.
 * @param compilationCacheSize the maximal number of compiled scripts kept for reuse,
 *   evicting the least recently used one, `0` by default, which disables the cache, must not be negative.
 *   A compiled script is reused when the script text is the same,
 *   and so are the names and types of its dependencies, while their values might differ.
 *   A script which failed to compile is not cached.
 */
public fun ScriptExecutor(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    compilationCacheSize: Int = 0
): ScriptExecutor = DefaultScriptExecutor(
    dispatcher,
    compilationCacheSize
)

/**
 * Creates a [ScriptExecutor.Dependency] of the reified type [T].
 */
public inline fun <reified T> dependency(
    name: String,
    value: T
): ScriptExecutor.Dependency<T> = ScriptExecutor.Dependency(
    name, typeOf<T>(), value
)

private val identifier = Regex("[\\p{L}\\p{Nl}_][\\p{L}\\p{Nl}\\p{Nd}_]*")

/** The keywords, like `in` or `class`, which the script could not use as a name, unless quoted in backticks. */
private val hardKeywords = KtTokens.KEYWORDS.types.mapTo(HashSet()) { (it as KtKeywordToken).value }

/** The first type parameter of this type, or of its type arguments, like `T` of a generic function. */
private fun KType.typeParameter(): KTypeParameter? =
    classifier as? KTypeParameter ?: arguments.firstNotNullOfOrNull { it.type?.typeParameter() }

/** The first class of this type, or of its type arguments, which the script cannot refer to. */
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
