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

class GetterNameTest {

    @Test
    fun `should prefix property name with get and capitalize it`() {
        // when
        val name = getterName("timeout")

        // then
        assert(name == "getTimeout")
    }

    @Test
    fun `should keep property name starting with is followed by non-lowercase character`() {
        // when
        val names = listOf("isOk", "is1", "is_ok").map { getterName(it) }

        // then
        assert(names == listOf("isOk", "is1", "is_ok"))
    }

    @Test
    fun `should prefix property name starting with is followed by lowercase character`() {
        // when
        val names = listOf("issue", "island", "isbn", "is").map { getterName(it) }

        // then
        assert(names == listOf("getIssue", "getIsland", "getIsbn", "getIs"))
    }

    @Test
    fun `should capitalize only ASCII character of property name`() {
        // when
        val names = listOf("ä", "isä", "URL", "_x").map { getterName(it) }

        // then
        assert(names == listOf("getä", "isä", "getURL", "get_x"))
    }

}
