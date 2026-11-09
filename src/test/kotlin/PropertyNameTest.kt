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

import com.xemantic.kotlin.test.assert
import kotlin.test.Test

class PropertyNameTest {

    @Test
    fun `should strip get prefix of getter and decapitalize property name`() {
        // when
        val names = listOf("getTimeout", "getX", "getä", "get_x", "get1x").map { propertyName(it) }

        // then
        assert(names == listOf("timeout", "x", "ä", "_x", "1x"))
    }

    @Test
    fun `should strip set prefix of setter`() {
        // when
        val name = propertyName("setTimeout")

        // then
        assert(name == "timeout")
    }

    @Test
    fun `should keep property name of getter starting with consecutive uppercase letters`() {
        // when
        val names = listOf("getURL", "getUX").map { propertyName(it) }

        // then
        assert(names == listOf("URL", "UX"))
    }

    @Test
    fun `should keep name of getter starting with is`() {
        // when
        val names = listOf("isOk", "is1", "isä").map { propertyName(it) }

        // then
        assert(names == listOf("isOk", "is1", "isä"))
    }

    @Test
    fun `should keep name of function which is not an accessor`() {
        // when
        val names = listOf("getter", "settle", "get", "set", "issue", "greet", "Checker").map { propertyName(it) }

        // then
        assert(names == listOf("getter", "settle", "get", "set", "issue", "greet", "Checker"))
    }

}
