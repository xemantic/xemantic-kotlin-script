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
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import kotlin.test.Test

/**
 * The disposal of a disposable of the compiler with its subtree, see [disposeTree],
 * relying on the logger of the compiler throwing an [AssertionError] of the failure of a disposable - re-check after every Kotlin upgrade.
 */
class DisposeTreeTest {

    @Test
    fun `should throw non-fatal failure of disposable wrapped by logger of compiler`() {
        // given
        val disposable = Disposer.newDisposable("tree")
        Disposer.register(disposable, Disposable { error("Disposal failed") })

        // when
        val failure = runCatching { disposeTree(disposable) }.exceptionOrNull()

        // then
        assert(failure is AssertionError && failure.cause?.message == "Disposal failed")
    }

    @Test
    fun `should throw fatal failure of disposable unwrapped`() {
        // given
        val disposable = Disposer.newDisposable("tree")
        Disposer.register(disposable, Disposable { throw OutOfMemoryError("Disposal failed") })

        // when
        // caught, as an OutOfMemoryError escaping the test would abort the Gradle test executor
        val failure = runCatching { disposeTree(disposable) }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Disposal failed")
    }

    /**
     * The logger of the compiler throws on the first failure of the disposables, disposed in the reverse order of their registration,
     * so a fatal failure of a disposable disposed later is lost, while every disposable is still disposed - a known limit.
     */
    @Test
    fun `should lose fatal failure of disposable disposed after another failing one`() {
        // given
        val disposed = mutableListOf<String>()
        val disposable = Disposer.newDisposable("tree")
        Disposer.register(disposable, Disposable { disposed += "fatal"; throw OutOfMemoryError("Fatal disposal failed") })
        Disposer.register(disposable, Disposable { disposed += "non-fatal"; error("Disposal failed") })

        // when
        // caught, as an OutOfMemoryError escaping the test would abort the Gradle test executor
        val failure = runCatching { disposeTree(disposable) }.exceptionOrNull()

        // then
        assert(disposed == listOf("non-fatal", "fatal"))
        assert(failure is AssertionError && failure.cause?.message == "Disposal failed")
    }

}
