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

@Suppress("unused")
class SnippetDeclarationsTest {

    /** Declares each kind of the members of a snippet class, in each visibility. */
    private class Snippet1 {
        val publicProperty = 1
        private val privateProperty = 1
        internal val internalProperty = 1
        fun publicFunction() = 1
        private fun privateFunction() = 1
        internal fun internalFunction() = 1
        val String.publicExtensionProperty get() = 1
        private val String.privateExtensionProperty get() = 1
        internal val String.internalExtensionProperty get() = 1
        fun String.publicExtensionFunction() = 1
        private fun String.privateExtensionFunction() = 1
        internal fun String.internalExtensionFunction() = 1
        class PublicClass
        private class PrivateClass
        internal class InternalClass
    }

    @Test
    fun `should keep declarations of snippet accessible by later snippet`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        declarations.keep(Snippet1::class.java, "snippet1.kts")

        // then
        assert(declarations.isProperty("publicProperty"))
        assert(declarations.isFunction("publicFunction"))
        assert(declarations.isExtensionProperty("publicExtensionProperty"))
        assert(declarations.isExtensionFunction("publicExtensionFunction"))
        assert(declarations.isClass("PublicClass"))
        assert(declarations.isClass("InternalClass"))
    }

    @Test
    fun `should not keep private declarations of snippet`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        declarations.keep(Snippet1::class.java, "snippet1.kts")

        // then
        assert(!declarations.isProperty("privateProperty"))
        assert(!declarations.isFunction("privateFunction"))
        assert(!declarations.isExtensionProperty("privateExtensionProperty"))
        assert(!declarations.isExtensionFunction("privateExtensionFunction"))
        assert(!declarations.isClass("PrivateClass"))
    }

    @Test
    fun `should not keep internal properties and functions of snippet`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        declarations.keep(Snippet1::class.java, "snippet1.kts")

        // then
        assert(!declarations.isProperty("internalProperty"))
        assert(!declarations.isFunction("internalFunction"))
        assert(!declarations.isExtensionProperty("internalExtensionProperty"))
        assert(!declarations.isExtensionFunction("internalExtensionFunction"))
    }

    @Test
    fun `should tell extensions from other members`() {
        // given
        val declarations = SnippetDeclarations()

        // when
        declarations.keep(Snippet1::class.java, "snippet1.kts")

        // then
        assert(!declarations.isProperty("publicExtensionProperty"))
        assert(!declarations.isFunction("publicExtensionFunction"))
        assert(!declarations.isExtensionProperty("publicProperty"))
        assert(!declarations.isExtensionFunction("publicFunction"))
    }

    @Test
    fun `should keep declarations of snippet kept after the earlier ones were read`() {
        // given
        val declarations = SnippetDeclarations()
        declarations.keep(String::class.java, "snippet1.kts")
        declarations.isProperty("publicProperty")

        // when
        declarations.keep(Snippet1::class.java, "snippet2.kts")

        // then
        assert(declarations.isProperty("publicProperty"))
    }

}
