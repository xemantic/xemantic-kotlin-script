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

import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlin.script.experimental.api.ResultValue
import kotlin.test.Test

class ScriptErrorReporterTest {

    @Test
    fun `should report snippet which was not evaluated as evaluation error`() {
        // given
        // never returned by the K2 REPL evaluator so far, so not reproducible by a snippet,
        // while the declaration of the properties would be taken for evaluated otherwise
        val reporter = ScriptErrorReporter(emptyMap())

        // when
        val result = reporter.scriptResult(
            result = ResultValue.NotEvaluated,
            evaluationReports = emptyList(),
            compilationReports = emptyList(),
            dependencies = SnippetDependencyResolver.SnippetDependencies()
        )

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
        }
    }

}
