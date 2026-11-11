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
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.test.Test

class ScriptErrorReporterTest {

    private val reporter = ScriptErrorReporter(
        snippets = mapOf(
            "snippet1.kts" to listOf("throw IllegalStateException(\"a\\nb\")")
        )
    )

    /** This exception as thrown by the body of the first line of `snippet1.kts`. */
    private fun <T : Throwable> T.thrownBySnippet(): T = apply {
        stackTrace = arrayOf(StackTraceElement("Snippet1", "\$\$eval", "snippet1.kts", 1))
    }

    @Test
    fun `should report snippet which was not evaluated as evaluation error`() {
        // given
        // never returned by the K2 REPL evaluator so far, so not reproducible by a snippet,
        // while the declaration of the properties would be taken for evaluated otherwise

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

    @Test
    fun `should indent later lines of multi-line message`() {
        // given
        val exception = IllegalStateException("a\nb").thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: a
              b
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
        """.trimIndent()
    }

    @Test
    fun `should indent later lines of multi-line message of the cause`() {
        // given
        val exception = IllegalStateException(
            "outer",
            IllegalArgumentException("a\nb").thrownBySnippet()
        ).thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: outer
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
            Caused by: java.lang.IllegalArgumentException: a
              b
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
        """.trimIndent()
    }

    @Test
    fun `should indent later lines of multi-line message of the suppressed exception`() {
        // given
        val exception = IllegalStateException("outer").thrownBySnippet().apply {
            addSuppressed(IllegalArgumentException("a\nb").thrownBySnippet())
        }

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: outer
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
              Suppressed: java.lang.IllegalArgumentException: a
                b
                at <snippet body>(snippet1.kts:1)
                | throw IllegalStateException("a\nb")
        """.trimIndent()
    }

    @Test
    fun `should drop trailing line breaks of message and indent its lines broken by CRLF`() {
        // given
        val exception = IllegalStateException("a\r\n\r\nb\r\n").thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: a

              b
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
        """.trimIndent()
    }

    @Test
    fun `should drop trailing whitespace of message and keep its blank lines empty`() {
        // given
        val exception = IllegalStateException("a  \n  \nb\n  ").thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        assert(
            report == "java.lang.IllegalStateException: a\n" +
                "\n" +
                "  b\n" +
                "  at <snippet body>(snippet1.kts:1)\n" +
                "  | throw IllegalStateException(\"a\\nb\")"
        )
    }

    @Test
    fun `should indent later lines of multi-line diagnostic message and keep its blank lines empty`() {
        // given
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "a  \n  \nb\n  ",
            severity = ScriptDiagnostic.Severity.ERROR
        )

        // when
        val report = reporter.describe(listOf(diagnostic))

        // then
        assert(report == "error: a\n\n  b")
    }

    @Test
    fun `should report exception with empty message`() {
        // given
        val exception = IllegalStateException("").thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        // the trailing space after the colon is deliberate, mirroring Throwable.toString() of an empty message
        assert(
            report == "java.lang.IllegalStateException: \n" +
                "  at <snippet body>(snippet1.kts:1)\n" +
                "  | throw IllegalStateException(\"a\\nb\")"
        )
    }

    @Test
    fun `should report suppressed exception with its cause before the cause`() {
        // given
        val exception = IllegalStateException(
            "outer",
            RuntimeException("cause").thrownBySnippet()
        ).thrownBySnippet().apply {
            addSuppressed(
                IllegalArgumentException(
                    "suppressed",
                    ArithmeticException("suppressed cause").thrownBySnippet()
                ).thrownBySnippet()
            )
        }

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: outer
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
              Suppressed: java.lang.IllegalArgumentException: suppressed
                at <snippet body>(snippet1.kts:1)
                | throw IllegalStateException("a\nb")
              Caused by: java.lang.ArithmeticException: suppressed cause
                at <snippet body>(snippet1.kts:1)
                | throw IllegalStateException("a\nb")
            Caused by: java.lang.RuntimeException: cause
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
        """.trimIndent()
    }

    @Test
    fun `should report circular reference to the exception reported already`() {
        // given
        val exception = IllegalStateException("a\nb").thrownBySnippet()
        val cause = RuntimeException("cause", exception).thrownBySnippet()
        exception.initCause(cause)

        // when
        val report = reporter.describe(exception)

        // then
        report sameAs """
            java.lang.IllegalStateException: a
              b
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
            Caused by: java.lang.RuntimeException: cause
              at <snippet body>(snippet1.kts:1)
              | throw IllegalStateException("a\nb")
            Caused by: [CIRCULAR REFERENCE: java.lang.IllegalStateException: a
              b]
        """.trimIndent()
    }

    @Test
    fun `should omit the causes beyond the reported limit`() {
        // given
        val exception = (1..40).fold(RuntimeException("0").thrownBySnippet()) { cause, index ->
            RuntimeException("$index", cause).thrownBySnippet()
        }

        // when
        val report = reporter.describe(exception)

        // then
        val expected = (40 downTo 9).joinToString("\n") { index ->
            val prefix = if (index == 40) "" else "Caused by: "
            "${prefix}java.lang.RuntimeException: $index\n" +
                "  at <snippet body>(snippet1.kts:1)\n" +
                "  | throw IllegalStateException(\"a\\nb\")"
        } + "\nCaused by: [FURTHER EXCEPTIONS OMITTED]"
        report sameAs expected
    }

    @Test
    fun `should report NoSuchMethodError of long message resembling missing method in linear time`() {
        // given
        // a quadratic backtracking of matching the missing method would take minutes, not milliseconds
        val exception = NoSuchMethodError("'int " + "a.b(".repeat(100_000)).thrownBySnippet()

        // when
        val report = reporter.describe(exception)

        // then
        assert(report.startsWith("java.lang.NoSuchMethodError: 'int a.b("))
    }

}
