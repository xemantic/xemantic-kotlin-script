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
import kotlin.script.experimental.api.SourceCode
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

    /**
     * Tested directly, as a script which is not wrapped is never valid, failing to compile.
     */
    @Test
    fun `should report script which is not wrapped by unclosed or unmatched token`() {
        // given
        val reporters = listOf("listOf(1,\n  \"\${2", "listOf(1 }").map { ScriptErrorReporter(ScriptSource(it)) }

        // when
        val messages = reporters.map { it.toSourceFailureMessage() }

        // then
        assert(messages == listOf(
            "script.kts:2:4: error: Syntax error: '\${' is not closed.\n  |   \"<error>\${</error>2",
            "script.kts:1:10: error: Syntax error: Unexpected '}' without matching '{'.\n  | listOf(1 <error>}</error>"
        ))
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
    fun `should report compiler backend crash at nested class without intermediate representation`() {
        // given
        val reporter = ScriptErrorReporter(
            ScriptSource("sealed interface S\nclass A : S { inner class I { fun f() = 1 } }\nclass T : S { inner class I { fun f() = 1 } }\nT().I().f()")
        )

        // when
        val message = reporter.toFailureMessage(listOf(backendCrash("public final fun f (): kotlin.Int declared in <root>._.T.I")))

        // then
        message sameAs """
            script.kts:3:27: error: Exception while generating code for public final fun f (): kotlin.Int declared in <root>._.T.I
              Caused by: java.lang.AssertionError: Non-mapped local declaration
              | class T : S { inner class <error>I</error> { fun f() = 1 } }
        """.trimIndent()
    }

    @Test
    fun `should report compiler backend crash at function of moved class rather than at moved function of its name`() {
        // given
        val reporter = ScriptErrorReporter(
            ScriptSource("inline fun f() = 1\nsealed interface S\nclass T : S { fun f() = object { fun g() = 1 } }\nT().f()")
        )

        // when
        val message = reporter.toFailureMessage(listOf(backendCrash("public final fun g (): kotlin.Int declared in <root>._.T.f.<no name provided>")))

        // then
        message sameAs """
            script.kts:3:19: error: Exception while generating code for public final fun g (): kotlin.Int declared in <root>._.T.f.<no name provided>
              Caused by: java.lang.AssertionError: Non-mapped local declaration
              | class T : S { fun <error>f</error>() = object { fun g() = 1 } }
        """.trimIndent()
    }

    @Test
    fun `should report compiler backend crash at moved function without intermediate representation`() {
        // given
        val reporter = ScriptErrorReporter(ScriptSource("inline fun g() = 1\ng()"))

        // when
        val message = reporter.toFailureMessage(listOf(backendCrash("public final fun g (): kotlin.Int declared in <root>._")))

        // then
        message sameAs """
            script.kts:1:12: error: Exception while generating code for public final fun g (): kotlin.Int declared in <root>._
              Caused by: java.lang.AssertionError: Non-mapped local declaration
              | inline fun <error>g</error>() = 1
        """.trimIndent()
    }

    /** Like the one reported by the compiler, followed by the intermediate representation of the [code]. */
    private fun backendCrash(
        code: String
    ) = ScriptDiagnostic(
        code = ScriptDiagnostic.unspecifiedError,
        message = """
            Exception while generating code for $code:
            FUN name:f visibility:public modality:FINAL returnType:kotlin.Int
              BLOCK_BODY
        """.trimIndent(),
        exception = RuntimeException(
            "Exception while generating code for $code:\nFUN name:f",
            AssertionError("Non-mapped local declaration\n\n in FUN name:f")
        )
    )

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

    @Test
    fun `should report crash of the compiler in generated code without position and generated names`() {
        // given
        val source = ScriptSource("foo")
        val location = "${source.locationId}:1:1"
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "While analysing $location: java.lang.IllegalStateException: Exception in expression checkers",
            exception = RuntimeException(
                "While analysing $location: java.lang.IllegalStateException: Exception in expression checkers",
                IllegalStateException("Exception in expression checkers", IllegalArgumentException("Unexpected element at $location"))
            )
        )
        val reporter = ScriptErrorReporter(source)

        // when
        val message = reporter.toCompilationFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            error: The compiler failed internally: While analysing script.kts: java.lang.IllegalStateException: Exception in expression checkers
              Caused by: java.lang.IllegalArgumentException: Unexpected element at script.kts
              hint: it is a failure of the compiler itself - express the script differently.
        """.trimIndent()
    }

    @Test
    fun `should report limited number of causes of internal failure`() {
        // given
        val throwable = (1..20).fold(null as Throwable?) { cause, index ->
            RuntimeException("failure ${21 - index}", cause)
        }!!

        // when
        val message = internalFailureMessage(throwable)

        // then
        message sameAs """
            error: The script could not be compiled because of an internal failure: java.lang.RuntimeException: failure 1
              Caused by: java.lang.RuntimeException: failure 2
              Caused by: java.lang.RuntimeException: failure 3
              Caused by: java.lang.RuntimeException: failure 4
              Caused by: java.lang.RuntimeException: failure 5
              Caused by: java.lang.RuntimeException: failure 6
              Caused by: java.lang.RuntimeException: failure 7
              Caused by: java.lang.RuntimeException: failure 8
              [FURTHER CAUSES OMITTED]
        """.trimIndent()
    }

    @Test
    fun `should report circular causes of internal failure once`() {
        // given
        val first = RuntimeException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        // when
        val message = internalFailureMessage(first)

        // then
        message sameAs """
            error: The script could not be compiled because of an internal failure: java.lang.RuntimeException: first
              Caused by: java.lang.IllegalStateException: second
        """.trimIndent()
    }

    @Test
    fun `should report internal failure with multiline message indented`() {
        // given
        val throwable = IllegalStateException("Back-end failed\nFile being compiled: (1,1)")

        // when
        val message = internalFailureMessage(throwable)

        // then
        message sameAs """
            error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: Back-end failed
              File being compiled: (1,1)
        """.trimIndent()
    }

    @Test
    fun `should report crash of the compiler with the message of its diagnostic`() {
        // given
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "Could not read classpath entry /x.jar",
            exception = java.io.IOException()
        )
        val reporter = ScriptErrorReporter(ScriptSource("foo"))

        // when
        val message = reporter.toCompilationFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            error: The compiler failed internally: Could not read classpath entry /x.jar
              Caused by: java.io.IOException
              hint: it is a failure of the compiler itself - express the script differently.
        """.trimIndent()
    }

    @Test
    fun `should report crash of the compiler in the accessor of the dependency`() {
        // given
        val source = ScriptSource("x", listOf(PreambleDependency("x", { _, _ -> "val `x`: Int = TODO()" })))
        val line = source.text.lines().indexOfFirst { it.startsWith("val `x`") } + 1
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "Exception in expression checkers",
            location = SourceCode.Location(SourceCode.Position(line, 1)),
            exception = IllegalStateException("Exception in expression checkers")
        )
        val reporter = ScriptErrorReporter(source)

        // when
        val message = reporter.toCompilationFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            dependency 'x': error: The compiler failed internally: Exception in expression checkers
              hint: it is a failure of the compiler itself - express the script differently.
        """.trimIndent()
    }

    @Test
    fun `should report crash of the compiler marking the range of its location`() {
        // given
        val source = ScriptSource("val a = 1\nval b = a + 1\nb")
        val line = (1..source.text.lines().size).first { source.toScriptLine(it) == 1 }
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "Exception in expression checkers",
            location = SourceCode.Location(SourceCode.Position(line, 9), SourceCode.Position(line, 14)),
            exception = IllegalStateException("Exception in expression checkers")
        )
        val reporter = ScriptErrorReporter(source)

        // when
        val message = reporter.toCompilationFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            script.kts:2:9: error: The compiler failed internally: Exception in expression checkers
              | val b = <error>a + 1</error>
              hint: it is a failure of the compiler itself - express this code differently.
        """.trimIndent()
    }

    @Test
    fun `should report crash of the compiler at oversized line without position`() {
        // given
        val source = ScriptSource("foo")
        val diagnostic = ScriptDiagnostic(
            code = ScriptDiagnostic.unspecifiedError,
            message = "While analysing ${source.locationId}:99999999999:1: boom",
            exception = IllegalStateException("boom")
        )
        val reporter = ScriptErrorReporter(source)

        // when
        val message = reporter.toCompilationFailureMessage(listOf(diagnostic))

        // then
        message sameAs """
            error: The compiler failed internally: While analysing script.kts: boom
              Caused by: java.lang.IllegalStateException: boom
              hint: it is a failure of the compiler itself - express the script differently.
        """.trimIndent()
    }

    @Test
    fun `should rewrite generated locations of the script`() {
        // given
        val source = ScriptSource("val a = 1\nval b = a + 1\nb")
        val line = (1..source.text.lines().size).first { source.toScriptLine(it) == 1 }
        val generated = (1..source.text.lines().size).first { source.toScriptLine(it) == null }
        val message = "at ${source.locationId}:$line, ($line,9) in /${source.locationId}, " +
                "${source.locationId}:$generated:1, ($generated,1) in /${source.locationId}, ${source.locationId}"

        // when
        val rewritten = message.toScriptLocations(listOf(source))

        // then
        assert(rewritten == "at script.kts:2, script.kts:2:9, script.kts, script.kts, script.kts")
    }

    @Test
    fun `should report wrapper of internal failure mentioning class of the cause without message`() {
        // given
        val throwable = IllegalStateException(
            "Couldn't inline method call at line 3 of script: java.lang.NullPointerException",
            NullPointerException()
        )

        // when
        val message = internalFailureMessage(throwable)

        // then
        message sameAs """
            error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: Couldn't inline method call at line 3 of script: java.lang.NullPointerException
              Caused by: java.lang.NullPointerException
        """.trimIndent()
    }

}
