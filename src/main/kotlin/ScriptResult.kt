/*
 * xemantic-kotlin-script - Kotlin script execution with LLM-friendly diagnostics
 * Copyright (C) 2025-2026  Kazimierz Pogoda / Xemantic
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

/**
 * The phase of the snippet execution, in which an error occurred.
 */
public enum class ExecutionPhase {
    COMPILATION,
    EVALUATION
}

/**
 * The result of [ScriptSession.execute].
 */
public sealed interface ScriptResult {

    /**
     * The snippet failed to compile, or threw an exception.
     *
     * @property message the LLM-friendly description of the failure,
     *   pointing to the offending lines of the snippets.
     * @property phase the phase of the execution, in which the failure occurred.
     */
    public class Error(
        public val message: String,
        public val phase: ExecutionPhase
    ) : ScriptResult

    /**
     * The snippet was executed.
     *
     * @property value the value of the last expression of the snippet,
     *   or [Unit] if the snippet does not end with an expression.
     * @property warnings the warnings of the dependencies, other than the [resolvedVersions], in the order of their lines,
     *   followed by the warnings of the compiler, pointing to the offending lines of the snippet, or `null` if there are none.
     *   They might reveal a bug, even if the snippet succeeded.
     *   They also report a repository declaration with credentials refused, superseded by another declaration of it,
     *   which a later dependency, served only to these credentials, would fail without,
     *   naming the line of the superseded declaration, possibly in an earlier snippet.
     * @property resolvedVersions the versions resolved for the dependencies declared by the snippet without version,
     *   like `org.jsoup:jsoup` resolved to `1.24.1`, by their coordinates, as an LLM might not know their API.
     *   Unlike the [warnings], they are worth showing to the LLM always.
     *   If the snippet fails, they are reported as warnings in the [Error.message] instead.
     */
    public class Value(
        public val value: Any?,
        public val warnings: String? = null,
        public val resolvedVersions: Map<String, String> = emptyMap()
    ) : ScriptResult

}
