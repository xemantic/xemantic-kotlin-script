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
import com.xemantic.kotlin.test.sameAs
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.test.Test

class ScriptErrorReporterTest {

    /**
     * Tested directly, as the coroutine stack trace recovery of the tests fails on such an exception itself.
     */
    @Test
    fun `should report exception whose stack trace contains null`() {
        // given
        class Traceless : RuntimeException("traceless") {
            override fun getStackTrace(): Array<StackTraceElement?> = arrayOf(null)
        }
        val reporter = ScriptErrorReporter(ScriptSource("throw Traceless()"))

        // when
        val message = reporter.toFailureMessage(Traceless(), warnings = emptyList())

        // then
        message sameAs "Traceless: traceless"
    }

    @Test
    fun `should report omitting further exceptions only once`() {
        // given
        val cause = IllegalStateException("cause")
        val inner = RuntimeException("inner", cause)
        repeat(40) { inner.addSuppressed(RuntimeException("suppressed $it")) }
        val outer = RuntimeException("outer")
        outer.addSuppressed(inner)
        outer.addSuppressed(RuntimeException("sibling"))
        val reporter = ScriptErrorReporter(ScriptSource("throw outer"))

        // when
        val message = reporter.toFailureMessage(outer, warnings = emptyList())

        // then
        val lines = message.lines()
        val omittedLineCount = lines.count { it.endsWith("[FURTHER EXCEPTIONS OMITTED]") }
        val lastLine = lines.last()
        assert(omittedLineCount == 1)
        assert(lastLine == "    Suppressed: [FURTHER EXCEPTIONS OMITTED]")
    }

    @Test
    fun `should report failure without diagnostics`() {
        // given
        val reporter = ScriptErrorReporter(ScriptSource("foo"))

        // when
        val message = reporter.toFailureMessage(diagnostics = emptyList())

        // then
        message sameAs "error: Failed without any diagnostic message."
    }

    @Test
    fun `should report exception of the diagnostic`() {
        // given
        val cause = NoClassDefFoundError("Foo").apply { stackTrace = emptyArray() }
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "Unable to instantiate class Script",
            exception = cause
        )
        val reporter = ScriptErrorReporter(ScriptSource("foo"))

        // when
        val message = reporter.toFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            error: Unable to instantiate class Script
              Caused by: java.lang.NoClassDefFoundError: Foo
        """.trimIndent()
    }

    @Test
    fun `should report native frame and frames without source information`() {
        // given
        fun failure(
            message: String,
            frame: StackTraceElement,
            cause: Throwable? = null
        ) = RuntimeException(message, cause).apply { stackTrace = arrayOf(frame) }
        val throwable = failure(
            "native",
            StackTraceElement("java.lang.reflect.Array", "newArray", "Array.java", -2),
            failure(
                "no line",
                StackTraceElement("Foo", "bar", "Foo.java", -1),
                failure("no file", StackTraceElement("Foo", "baz", null, -1))
            )
        )
        val reporter = ScriptErrorReporter(ScriptSource("foo"))

        // when
        val message = reporter.toFailureMessage(throwable, warnings = emptyList())

        // then
        message sameAs """
            java.lang.RuntimeException: native
              at java.lang.reflect.Array.newArray(Native Method)
            Caused by: java.lang.RuntimeException: no line
              at Foo.bar(Foo.java)
            Caused by: java.lang.RuntimeException: no file
              at Foo.baz(Unknown Source)
        """.trimIndent()
    }

    @Test
    fun `should report script frame without line number by its function name`() {
        // given
        val source = ScriptSource("throw Boom")
        val throwable = RuntimeException("b").apply {
            stackTrace = arrayOf(StackTraceElement("_\$Boom", "<clinit>", source.locationId, -1))
        }
        val reporter = ScriptErrorReporter(source, scriptClassName = "_")

        // when
        val message = reporter.toFailureMessage(throwable, warnings = emptyList())

        // then
        message sameAs """
            java.lang.RuntimeException: b
              at Boom.<clinit>(script.kts)
        """.trimIndent()
    }

}
