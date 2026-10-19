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
 * The phase of the script execution, in which an error occurred.
 */
public enum class ExecutionPhase {
    COMPILATION,
    EVALUATION
}

/**
 * The result of [ScriptExecutor.execute].
 */
public sealed interface ScriptResult {

    /**
     * The script failed to compile, or threw an exception.
     *
     * @property message the LLM-friendly description of the failure,
     *   pointing to the offending lines of the script.
     * @property phase the phase of the execution, in which the failure occurred.
     */
    public class Error(
        public val message: String,
        public val phase: ExecutionPhase
    ) : ScriptResult

    /**
     * The script was executed.
     *
     * @property value the value of the last expression of the script,
     *   or [Unit] if the script does not end with an expression.
     * @property warnings the compiler warnings, pointing to the offending lines of the script,
     *   or `null` if there are none. They might reveal a bug, even if the script succeeded.
     */
    public class Value(
        public val value: Any?,
        public val warnings: String? = null
    ) : ScriptResult

}
