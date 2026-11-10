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

import java.util.concurrent.ConcurrentHashMap

/**
 * The values of the properties of the open sessions,
 * read by the declarations of the properties preceding the snippets of a session, see [ScriptProperty],
 * and kept until a closed session ends its last snippet.
 */
public object ScriptPropertyValues {

    private val sessions = ConcurrentHashMap<Int, Map<String, Any?>>()

    /** The value of the property of this [name] provided to the [session]. */
    public fun get(session: Int, name: String): Any? =
        checkNotNull(sessions[session]) { "The session is closed" }[name]

    /**
     * The value of the property of this [name] provided to the [session], cast unchecked to its type [T],
     * so the caller casts it to the erased class of [T] only, like `FunctionN` of a function type of more than 22 parameters.
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T> getUnchecked(session: Int, name: String): T =
        get(session, name) as T

    internal fun register(session: Int, values: Map<String, Any?>) {
        sessions[session] = values
    }

    internal fun unregister(session: Int) {
        sessions.remove(session)
    }

}
