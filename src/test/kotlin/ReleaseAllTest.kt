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

/** The release of several resources, each one released even if releasing an earlier one fails, see [releaseAll]. */
class ReleaseAllTest {

    @Test
    fun `should run every release`() {
        // given
        val released = mutableListOf<String>()

        // when
        releaseAll({ released += "first" }, { released += "second" })

        // then
        assert(released == listOf("first", "second"))
    }

    @Test
    fun `should run every release even if earlier ones fail and throw first failure with next ones suppressed`() {
        // given
        val released = mutableListOf<String>()

        // when
        val failure = runCatching {
            releaseAll(
                { released += "first"; error("First failed") },
                { released += "second" },
                { released += "third"; error("Third failed") }
            )
        }.exceptionOrNull()

        // then
        assert(released == listOf("first", "second", "third"))
        assert(failure is IllegalStateException && failure.message == "First failed")
        assert(failure.suppressed.map { it.message } == listOf("Third failed"))
    }

    @Test
    fun `should throw fatal failure of release over earlier failure`() {
        // given
        val released = mutableListOf<String>()

        // when
        val failure = runCatching {
            releaseAll(
                { released += "first"; error("First failed") },
                { released += "second"; throw OutOfMemoryError("Second failed") },
                { released += "third" }
            )
        }.exceptionOrNull()

        // then
        assert(released == listOf("first", "second", "third"))
        assert(failure is OutOfMemoryError && failure.message == "Second failed")
        assert(failure.suppressed.map { it.message } == listOf("First failed"))
    }

}
