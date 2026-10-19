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

import org.intellij.lang.annotations.Language

@DslMarker
public annotation class ScriptDependenciesDsl

/**
 * The builder of the dependencies provided to the script.
 *
 * The type of a dependency is the reified type argument, inferred from the value by default.
 * It can be given explicitly, to make the dependency nullable,
 * or to expose it to the script only as its supertype.
 */
@ScriptDependenciesDsl
public class ScriptDependencies @PublishedApi internal constructor() {

    @PublishedApi
    internal val dependencies: MutableList<ScriptExecutor.Dependency<*>> = mutableListOf()

    /**
     * Provides the [value] to the script as the property of the given [name] and of the type [T].
     */
    public inline fun <reified T> provide(name: String, value: T) {
        dependencies += dependency(name, value)
    }

    /**
     * Provides the [value] to the script as the property of this name and of the type [T].
     */
    public inline infix fun <reified T> String.provides(value: T) {
        provide(this, value)
    }

}

/**
 * Executes the [script] with the dependencies defined by the [dependencies] builder.
 *
 * @see ScriptExecutor.execute
 */
public suspend inline fun ScriptExecutor.execute(
    @Language("kotlin") script: String,
    dependencies: ScriptDependencies.() -> Unit
): ScriptResult = execute(
    script,
    ScriptDependencies().apply(dependencies).dependencies
)
