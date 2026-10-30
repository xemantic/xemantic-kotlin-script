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

import org.jetbrains.kotlin.fir.extensions.FirReplHistoryProvider
import org.jetbrains.kotlin.fir.symbols.impl.FirReplSnippetSymbol

/**
 * The snippets of a session known to the compiler, without the ones which failed to compile,
 * as the history of the K2 REPL compiler would otherwise keep their declarations,
 * which the next snippets could refer to, while they were never evaluated.
 */
internal class ReplHistory : FirReplHistoryProvider() {

    private val history = LinkedHashSet<FirReplSnippetSymbol>()

    /** The number of the snippets compiled successfully. */
    private var committed = 0

    override fun getSnippets(): Iterable<FirReplSnippetSymbol> = history.toList()

    override fun putSnippet(symbol: FirReplSnippetSymbol) {
        history.add(symbol)
    }

    override fun isFirstSnippet(symbol: FirReplSnippetSymbol): Boolean = history.firstOrNull() == symbol

    override fun getSnippetCount(): Int = history.size

    /** Keeps the snippets put since the last [commit], or [rollback]. */
    fun commit() {
        committed = history.size
    }

    /** Forgets the snippets put since the last [commit], or [rollback]. */
    fun rollback() {
        while (history.size > committed) {
            history.remove(history.last())
        }
    }

}
