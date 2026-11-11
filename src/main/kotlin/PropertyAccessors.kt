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
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty
import kotlin.reflect.full.functions
import kotlin.reflect.full.memberExtensionProperties
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter
import kotlin.reflect.jvm.javaMethod
import kotlin.reflect.jvm.javaSetter

private val logger = KotlinLogging.logger {}

/**
 * The properties of the accessors of the classes of the snippets, like `t` of `Snippet2.getT`, read with kotlin-reflect,
 * as the name of a method does not tell an accessor from a function named like one, like `getGreeting`,
 * nor give the property of an accessor of a custom JVM name, like `t` of `@get:JvmName("size") val t`.
 * Also the extension properties, and the inherited ones, like `label` of `Impl.getLabel` bridging to `Foo.getLabel`,
 * while an accessor named like a function of the class, like `getT` of `val t` next to `fun getT(n: Int)`,
 * is not mapped, as a frame does not tell them apart.
 *
 * The classes are loaded by the class loader of the latest evaluated snippet, which loads the earlier ones as well.
 * Accessed on the thread of the session only, like the snippets.
 */
internal class PropertyAccessors {

    private var classLoader: ClassLoader? = null

    /** The names of the properties by the names of their accessors, by the class, read so far. */
    private val classes = HashMap<String, Map<String, String>>()

    /** Keeps the class loader of the [scriptClass] of the latest evaluated snippet. */
    fun keep(scriptClass: Class<*>) {
        classLoader = scriptClass.classLoader
    }

    /**
     * The property of the accessor of this [frame], like `t` of `getT`, or of `setT`,
     * or `null` if its method is not an accessor of a property of its class, or the class cannot be read.
     */
    fun propertyOf(frame: StackTraceElement): String? = accessors(frame.className)?.get(frame.methodName)

    /**
     * The accessors of the class of this [className] read so far, or read from the class loader of the latest snippet,
     * `null` if it cannot be read, which is not kept, as the class loader might not be known yet, or not load the class yet.
     *
     * @throws VirtualMachineError thrown by the reflection, other than [StackOverflowError].
     */
    private fun accessors(className: String): Map<String, String>? = classes[className]
        ?: logger.runOr(null, { "cannot read the accessors of the class $className of a frame" }) {
            // not initialized, as a static initializer of the class might not have run yet, or have failed
            classLoader?.let { Class.forName(className, false, it) }?.kotlin?.accessors()?.also { classes[className] = it }
        }

    /** The names of the properties of this class by the names of their accessors, but the ones named like a function of the class. */
    private fun KClass<*>.accessors(): Map<String, String> {
        val functions = functions.mapNotNullTo(HashSet()) { it.javaMethod?.name }
        return buildMap {
            (memberProperties + memberExtensionProperties).forEach { property ->
                listOfNotNull(
                    property.javaGetter,
                    (property as? KMutableProperty<*>)?.javaSetter
                ).forEach { accessor ->
                    if (accessor.name !in functions) put(accessor.name, property.name)
                }
            }
        }
    }

}
