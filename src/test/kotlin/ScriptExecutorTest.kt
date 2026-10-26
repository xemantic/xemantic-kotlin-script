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

import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import java.net.URL
import java.util.Collections
import java.util.Enumeration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.ScriptEvaluator
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlin.config.JvmTarget

@RequiresOptIn
annotation class ExperimentalGadget

@ExperimentalGadget
class Gadget

class Widget(val name: String)

class Logger {
    operator fun invoke(message: String) = "dep:$message"
}

class PrivateInvoke {
    @Suppress("unused")
    private operator fun invoke() = 1
    override fun toString() = "private"
}

/** Not available to the class loader of [OptionalUser] in the test of a missing optional class. */
class OptionalFeature

class OptionalUser {
    @Suppress("unused")
    fun use(feature: OptionalFeature) = feature
    override fun toString() = "optional"
}

class DurationLogger {
    operator fun invoke(duration: kotlin.time.Duration) = "dep:$duration"
}

/** Fails with whatever the [failure] throws, instead of compiling the script. */
class FailingCompiler(
    private val failure: suspend () -> Nothing
) : JvmScriptCompiler() {
    override suspend fun invoke(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration
    ): ResultWithDiagnostics<CompiledScript> = failure()
}

class ScriptExecutorTest {

    @Test
    fun `should execute simple script returning just a String`() = runTest {
        // given
        val script = """
            "foo"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "foo")
            have(warnings == null)
        }
    }

    @Test
    fun `should execute typical script crafted by an LLM`() = runTest {
        // given
        val script = $$"""
            
            fun fibonacci(n: Int): Long {
                if (n <= 1) return n.toLong()
                
                var a = 0L
                var b = 1L
                
                for (i in 2..n) {
                    val temp = a + b
                    a = b
                    b = temp
                }
                
                return b
            }
            
            val result = fibonacci(42)
            "The 42nd Fibonacci number is: $result"
            
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "The 42nd Fibonacci number is: 267914296")
        }
    }

    @Test
    fun `should execute function defined in the script`() = runTest {
        // given
        val script = """            
            fun passThroughTestFunction(parameter: String) = parameter
            val value = "foo"
            passThroughTestFunction(value)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "foo")
        }
    }

    @Test
    fun `should handle script imports`() = runTest {
        // given
        val script = """
            import kotlin.test.Test
            
            @Test
            fun test() = "test function with annotation"
            
            test()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "test function with annotation")
        }
    }

    @Test
    fun `should be able to call a suspended function from within the script`() = runTest {
        // given
        @Suppress("RedundantSuspendModifier")
        val script = """
            suspend fun suspendFunction() = "suspend function result"
            suspendFunction()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "suspend function result")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should run script on the given dispatcher, in virtual time of the test`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.delay
            import kotlin.time.Duration.Companion.hours
            delay(1.hours)
            "done"
        """.trimIndent()
        val executor = ScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler)
        )

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "done")
        }
        assert(currentTime == 1.hours.inWholeMilliseconds)
    }

    @Test
    fun `should use the time API without opting in`() = runTest {
        // given
        val script = """
            import kotlin.time.Instant
            val now: Instant = clock.now()
            now.epochSeconds
        """.trimIndent()
        val clock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(42)
        }

        // when
        val result = ScriptExecutor().execute(
            script,
            listOf(dependency<Clock>("clock", clock))
        )

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42L)
        }
    }

    @Test
    fun `should use dependencies`() = runTest {
        // given
        val script = """
            injected.uppercase()
        """.trimIndent()
        val dependencies = listOf(
            ScriptExecutor.Dependency(
                name = "injected",
                type = typeOf<String>(),
                value = "foo"
            )
        )
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, dependencies)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "FOO")
        }
    }

    @Test
    fun `should use dependency of generic type`() = runTest {
        // given
        val script = """
            items.first().uppercase()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("items", listOf("foo"))))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "FOO")
        }
    }

    @Test
    fun `should give access to dependency of generic type in the declarations moved out of the script body`() = runTest {
        // given
        val script = """
            import kotlin.math.max
            
            enum class Measure { LENGTH }
            class Longest(val measure: Measure) {
                fun length() = items.maxOf { it.length }
            }
            max(Longest(Measure.LENGTH).length(), 0)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("items", listOf("a", "abc"))))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should report error at original line in the script using dependency of generic type`() = runTest {
        // given
        val script = $$"""
            import kotlin.math.max
            
            val first = items.first()
            error("failure of $first")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("items", listOf("foo"))))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs $$"""
                java.lang.IllegalStateException: failure of foo
                  at <script body>(script.kts:4)
                  | error("failure of $first")
            """.trimIndent()
        }
    }

    @Test
    fun `should use dependency of nullable type`() = runTest {
        // given
        val script = """
            name ?: "nobody"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<String?>("name", null)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "nobody")
            have(warnings == null)
        }
    }

    @Test
    fun `should return null`() = runTest {
        // given
        val script = """
            null
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == null)
        }
    }

    @Test
    fun `should return Unit when executing empty script`() = runTest {
        // given
        val script = ""
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value is Unit)
        }
    }

    @Test
    fun `should return Unit when executing imports only script`() = runTest {
        // given
        val script = """
            import kotlin.String
            import kotlin.Int
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value is Unit)
        }
    }

    @Test
    fun `should return non-returning function call as Unit`() = runTest {
        // given
        val script = """
            println()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value is Unit)
        }
    }

    @Test
    fun `should report multiple syntax errors`() = runTest {
        // given
        val script = """
            asdf
            println(foo)
            val number: Int = ""${'"'}
                This is a
                multiline string,
                not an Int.
            ""${'"'}; "foo"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:1: error: Unresolved reference 'asdf'.
                  | <error>asdf</error>

                script.kts:2:9: error: Unresolved reference 'foo'.
                  | println(<error>foo</error>)

                script.kts:3:17: error: Initializer type mismatch: expected 'Int', actual 'String'.
                  | val number: Int <error>=</error> ""${'"'}
            """.trimIndent()
        }
    }

    @Test
    fun `should report imports error and syntax error`() = runTest {
        // given
        val script = """
            import foo.Bar
            
            buzz
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:8: error: Unresolved reference 'foo'.
                  | import <error>foo</error>.Bar

                script.kts:3:1: error: Unresolved reference 'buzz'.
                  | <error>buzz</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report multiline syntax error`() = runTest {
        // given
        val script = """
            val message = ""${'"'}This is a long string
            that spans multiple lines
            and is missing a closing quote
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:31: error: Syntax error: Expecting '"'.
                  | and is missing a closing quote<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report script runtime exception`() = runTest {
        // given
        val script = """
            throw Exception("must fail")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.Exception: must fail
                  at <script body>(script.kts:1)
                  | throw Exception("must fail")
            """.trimIndent()
        }
    }

    @Test
    fun `should report script exception with the root cause`() = runTest {
        // given
        val script = """
            fun willThrow(value: Int) {
                String::class.java.getDeclaredField("nonExistentField")
            }
            try {
                willThrow(1)
            } catch (e: Exception) {
                throw Exception("must fail", e)
            }
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message.substringBefore("at java.lang.Class.getDeclaredField(Class.java:").trimEnd() sameAs """
                java.lang.Exception: must fail
                  at <script body>(script.kts:7)
                  |     throw Exception("must fail", e)
                Caused by: java.lang.NoSuchFieldException: nonExistentField
            """.trimIndent()
            message.substringAfter("at java.lang.Class.getDeclaredField(Class.java:").substringAfter("\n") sameAs $$"""
                |  at willThrow(script.kts:2)
                |  |     String::class.java.getDeclaredField("nonExistentField")
                |  at <script body>(script.kts:5)
                |  |     willThrow(1)
            """.trimMargin()
        }
    }

    /** A failing script does not affect the executor. */
    @Test
    fun `should run the second script even if the first one fails`() = runTest {
        // given
        val script1 = """
            throw Exception("must fail")
        """.trimIndent()
        val script2 = """
            "foo"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result1 = executor.execute(script1)
        val result2 = executor.execute(script2)

        // then
        result1 should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.Exception: must fail
                  at <script body>(script.kts:1)
                  | throw Exception("must fail")
            """.trimIndent()
        }
        result2 should {
            be<ScriptResult.Value>()
            have(value == "foo")
        }
    }

    @Test
    fun `should report runtime error of a script executed by another script`() = runTest {
        // given
        val inner = "throw Exception(\"inner failure\")"
        val outer = """
            executor.execute(inner)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(outer, listOf(
            dependency("executor", executor),
            dependency("inner", inner)
        ))

        // then
        result should {
            be<ScriptResult.Value>()
            value should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.EVALUATION)
                message sameAs """
                    java.lang.Exception: inner failure
                      at <script body>(script.kts:1)
                      | throw Exception("inner failure")
                """.trimIndent()
            }
        }
    }

    @Test
    fun `should cancel the script when its caller is cancelled`() = runTest {
        // given
        val started = Job()
        val stopped = Job()
        val script = """
            import kotlinx.coroutines.delay
            
            started.complete()
            try {
                delay(60_000)
            } finally {
                stopped.complete()
            }
            "foo"
        """.trimIndent()
        val executor = ScriptExecutor()
        val execution = launch {
            executor.execute(script, listOf(
                dependency<CompletableJob>("started", started),
                dependency<CompletableJob>("stopped", stopped)
            ))
        }
        started.join()

        // when
        execution.cancelAndJoin()

        // then
        stopped should {
            have(isCompleted)
        }
    }

    @Test
    fun `should report single unresolved reference error on line 1 without imports`() = runTest {
        // given
        val script = "undefinedVariable"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:1: error: Unresolved reference 'undefinedVariable'.
                  | <error>undefinedVariable</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report syntax error on single character script`() = runTest {
        // given
        val script = "{"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:2: error: Syntax error: Expecting '}'.
                  | {<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error on script with only whitespace and invalid token`() = runTest {
        // given
        val script = "   @"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:4: error: Syntax error: Expected annotation identifier after '@'.
                  |    <error>@</error>

                script.kts:1:5: error: Syntax error: Expecting an expression.
                  |    @<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error when script body is just an incomplete expression`() = runTest {
        // given
        val script = "val x ="
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:8: error: Syntax error: Expecting an expression.
                  | val x =<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error on script with unmatched closing brace`() = runTest {
        // given
        val script = "}"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:1: error: Syntax error: Unexpected '}' without matching '{'.
                  | <error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error on script with unmatched closing brace after valid code`() = runTest {
        // given
        val script = """
            val x = 1
            }
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:1: error: Syntax error: Unexpected '}' without matching '{'.
                  | <error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should handle script with multiple closing braces`() = runTest {
        // given
        val script = "}}}"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:1: error: Syntax error: Unexpected '}' without matching '{'.
                  | <error>}</error>}}
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at end of multiline script`() = runTest {
        // given
        val script = """
            val x = 1
            val y = 2
            undefinedVariable
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:1: error: Unresolved reference 'undefinedVariable'.
                  | <error>undefinedVariable</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error on mixed opening and closing braces`() = runTest {
        // given
        val script = "{}}"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:3: error: Syntax error: Unexpected '}' without matching '{'.
                  | {}<error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report runtime error on line other than 1`() = runTest {
        // given
        val script = """
            val x = 1
            val y = 2
            throw Exception("error on line 3")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.Exception: error on line 3
                  at <script body>(script.kts:3)
                  | throw Exception("error on line 3")
            """.trimIndent()
        }
    }

    @Test
    fun `should report error with unicode characters`() = runTest {
        // given
        val script = """
            val żółć = "Polish characters"
            val 日本語 = "Japanese characters"
            undefinedUnicode变量
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:1: error: Unresolved reference 'undefinedUnicode变量'.
                  | <error>undefinedUnicode变量</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should execute 50 scripts in a loop`() = runTest {
        // given
        val executor = ScriptExecutor()

        // when
        val results = List(50) { i ->
            val script = $$"""
                val index = $$i
                "result-$index"
            """.trimIndent()
            executor.execute(script)
        }

        // then
        results.forEachIndexed { i, result ->
            result should {
                be<ScriptResult.Value>()
                have(value == "result-$i")
            }
        }
    }


    @Test
    fun `should report NotImplementedError thrown by TODO`() = runTest {
        // given
        val script = """
            fun notYetImplemented(): Int = TODO()
            notYetImplemented()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs $$"""
                kotlin.NotImplementedError: An operation is not implemented.
                  at notYetImplemented(script.kts:1)
                  | fun notYetImplemented(): Int = TODO()
                  at <script body>(script.kts:2)
                  | notYetImplemented()
            """.trimIndent()
        }
    }

    @Test
    fun `should report StackOverflowError with collapsed recursive frames`() = runTest {
        // given
        val script = """
            fun recurse(n: Int): Int = recurse(n + 1) + 1
            recurse(0)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // the number of frames depends on the JVM settings
            message.replace(Regex("repeated \\d+ more times"), "repeated N more times") sameAs $$"""
                java.lang.StackOverflowError
                  at recurse(script.kts:1)
                  | fun recurse(n: Int): Int = recurse(n + 1) + 1
                  ... (the frame above repeated N more times)
            """.trimIndent()
        }
    }

    @Test
    fun `should collapse frames of recursion`() = runTest {
        // given
        val script = """
            fun countdown(n: Int): Int = if (n == 0) error("bottom reached") else countdown(n - 1)
            countdown(50)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs $$"""
                java.lang.IllegalStateException: bottom reached
                  at countdown(script.kts:1)
                  | fun countdown(n: Int): Int = if (n == 0) error("bottom reached") else countdown(n - 1)
                  ... (the frame above repeated 50 more times)
                  at <script body>(script.kts:2)
                  | countdown(50)
            """.trimIndent()
        }
    }

    @Test
    fun `should collapse frames of mutual recursion`() = runTest {
        // given
        val script = """
            fun ping(n: Int): Int = if (n == 0) error("bottom reached") else pong(n - 1)
            fun pong(n: Int): Int = ping(n)
            ping(10)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs $$"""
                java.lang.IllegalStateException: bottom reached
                  at ping(script.kts:1)
                  | fun ping(n: Int): Int = if (n == 0) error("bottom reached") else pong(n - 1)
                  at pong(script.kts:2)
                  | fun pong(n: Int): Int = ping(n)
                  ... (the 2 frames above repeated 9 more times)
                  at ping(script.kts:1)
                  | fun ping(n: Int): Int = if (n == 0) error("bottom reached") else pong(n - 1)
                  at <script body>(script.kts:3)
                  | ping(10)
            """.trimIndent()
        }
    }

    /**
     * The exception is not copyable by the coroutine stack trace recovery,
     * so the outcome does not depend on whether it is enabled.
     */
    @Test
    fun `should report exception caused by exception of the same class`() = runTest {
        // given
        val script = """
            class Failure(val code: Int, cause: Throwable? = null) : RuntimeException("failure ${'$'}code", cause)
            throw Failure(1, Failure(2))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs $$"""
                Failure: failure 1
                  at <script body>(script.kts:2)
                  | throw Failure(1, Failure(2))
                Caused by: Failure: failure 2
                  at <script body>(script.kts:2)
                  | throw Failure(1, Failure(2))
            """.trimIndent()
        }
    }

    @Test
    fun `should report the name of exception class declared in a function of the script`() = runTest {
        // given
        val script = """
            fun fail(): Nothing {
                class LocalFailure : Exception("local failure")
                throw LocalFailure()
            }
            fail()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs $$"""
                LocalFailure: local failure
                  at fail(script.kts:3)
                  |     throw LocalFailure()
                  at <script body>(script.kts:5)
                  | fail()
            """.trimIndent()
        }
    }

    @Test
    fun `should report enum constant not found without the name of the script class`() = runTest {
        // given
        val script = """
            enum class Color { RED, GREEN }
            Color.valueOf("BLUE")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutJdkLineNumbers() sameAs """
                java.lang.IllegalArgumentException: No enum constant Color.BLUE
                  at java.lang.Enum.valueOf(Enum.java:<line>)
                  at <script body>(script.kts:2)
                  | Color.valueOf("BLUE")
            """.trimIndent()
        }
    }

    @Test
    fun `should report enum constant not found without the name of the script class in a package`() = runTest {
        // given
        val script = """
            package com.acme
            enum class Color { RED, GREEN }
            Color.valueOf("BLUE")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutJdkLineNumbers() sameAs """
                java.lang.IllegalArgumentException: No enum constant Color.BLUE
                  at java.lang.Enum.valueOf(Enum.java:<line>)
                  at <script body>(script.kts:3)
                  | Color.valueOf("BLUE")
            """.trimIndent()
        }
    }

    @Test
    fun `should report class cast of classes declared in the script without the name of the script class`() = runTest {
        // given
        val script = """
            sealed interface Shape
            class Circle : Shape
            class Square : Shape
            val shape: Shape = Circle()
            shape as Square
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutHashCodes() sameAs """
                java.lang.ClassCastException: class Circle cannot be cast to class Square (Circle and Square are in unnamed module of loader org.jetbrains.kotlin.scripting.compiler.plugin.impl.CompiledScriptClassLoader @<hash>)
                  at <script body>(script.kts:5)
                  | shape as Square
            """.trimIndent()
        }
    }

    @Test
    fun `should report class cast of local classes of the script body without their synthetic names`() = runTest {
        // given
        val script = """
            open class Animal
            class Dog : Animal()
            val animal: Any = Animal()
            animal as Dog
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutHashCodes() sameAs """
                java.lang.ClassCastException: class Animal cannot be cast to class Dog (Animal and Dog are in unnamed module of loader org.jetbrains.kotlin.scripting.compiler.plugin.impl.CompiledScriptClassLoader @<hash>)
                  at <script body>(script.kts:4)
                  | animal as Dog
            """.trimIndent()
        }
    }

    @Test
    fun `should report default string representations of the classes declared in the script without the name of the script class`() = runTest {
        // given
        val script = $$"""
            class Box { class Lid }
            object Registry
            class Holder { companion object }
            class Note
            throw IllegalStateException("${Box.Lid()} $Registry ${Holder.Companion} ${Note()} ${Box::class}")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutHashCodes().lines().first() sameAs
                    "java.lang.IllegalStateException: Box.Lid@<hash> Registry@<hash> Holder.Companion@<hash> Note@<hash> class Box"
        }
    }

    @Test
    fun `should report the message of the cause without the name of the script class`() = runTest {
        // given
        val script = """
            enum class Color { RED }
            fun parse(name: String): Color = try {
                Color.valueOf(name)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("cannot parse", e)
            }
            parse("BLUE")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutJdkLineNumbers() sameAs """
                java.lang.IllegalStateException: cannot parse
                  at parse(script.kts:5)
                  |     throw IllegalStateException("cannot parse", e)
                  at <script body>(script.kts:7)
                  | parse("BLUE")
                Caused by: java.lang.IllegalArgumentException: No enum constant Color.BLUE
                  at java.lang.Enum.valueOf(Enum.java:<line>)
                  at parse(script.kts:3)
                  |     Color.valueOf(name)
                  at <script body>(script.kts:7)
                  | parse("BLUE")
            """.trimIndent()
        }
    }

    @Test
    fun `should keep names in exception messages which the script wrote itself`() = runTest {
        // given
        val script = """
            enum class Color { RED }
            throw IllegalStateException("_.Color _.Shape my_.Color _Color __.Color x._.Color")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs
                    "java.lang.IllegalStateException: _.Color _.Shape my_.Color _Color __.Color x._.Color"
        }
    }

    @Test
    fun `should report enum constant not found without the name of the script class written in a comment or a longer name`() = runTest {
        // given
        val script = """
            // see _.Color
            object Palette_ { enum class Color { RED } }
            enum class Color { RED }
            val red = Palette_.Color.RED
            Color.valueOf("BLUE")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalArgumentException: No enum constant Color.BLUE"
        }
    }

    @Test
    fun `should report class cast of local class declared in a function named like a class of the script`() = runTest {
        // given
        val script = """
            interface Shape
            class Square : Shape
            fun Shape(): Shape {
                class Impl : Shape
                return Impl()
            }
            Shape() as Square
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first().substringBefore(" (") sameAs
                    "java.lang.ClassCastException: class Impl cannot be cast to class Square"
        }
    }

    @Test
    fun `should report class cast of arrays of classes declared in the script without the name of the script class`() = runTest {
        // given
        val script = """
            sealed interface Shape
            class Circle : Shape
            class Square : Shape
            val shapes: Any = arrayOf(Circle())
            shapes as Array<Square>
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first().substringBefore(" (") sameAs
                    "java.lang.ClassCastException: class [LCircle; cannot be cast to class [LSquare;"
        }
    }

    @Test
    fun `should report class cast of local classes without the synthetic names named like a class of the script`() = runTest {
        // given
        val script = """
            class result
            fun make(): Any {
                open class Animal
                class Dog : Animal()
                return Animal()
            }
            make() as result
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first().substringBefore(" (") sameAs
                    "java.lang.ClassCastException: class Animal cannot be cast to class result"
        }
    }

    @Test
    fun `should report default string representations of anonymous objects and lambdas without their synthetic names`() = runTest {
        // given
        val script = $$"""
            class Box {
                val o = object {}
                val r = object : Runnable { override fun run() {} }
                fun f() = { 1 }
            }
            val o = object {}
            val f = { 1 }
            throw IllegalStateException("$o $f ${Box().o} ${Box().r} ${Box().f()}")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutHashCodes().lines().first() sameAs
                    "java.lang.IllegalStateException: <anonymous object>@<hash> <lambda>@<hash> <anonymous object>@<hash> <anonymous java.lang.Runnable>@<hash> <lambda>@<hash>"
        }
    }

    @Test
    fun `should report enum constant not found without the name of the script class also written in a string literal`() = runTest {
        // given
        val script = """
            enum class C { X }
            val note = "_.C"
            C.valueOf("Y")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalArgumentException: No enum constant C.Y"
        }
    }

    @Test
    fun `should keep names in exception messages which the script wrote itself with escaped dollars or followed by other text`() = runTest {
        // given
        val script = listOf(
            "class Box",
            "throw IllegalStateException(\"\"\"_\${'\$'}Box\"\"\" + \" _\${\"\$\"}Box \" + \"_.Box\" + \"!\")"
        ).joinToString("\n")
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs $$"java.lang.IllegalStateException: _$Box _$Box _.Box!"
        }
    }

    @Test
    fun `should report default string representations of backticked classes without the name of the script class`() = runTest {
        // given
        val script = $$"""
            class `My Box`
            class `my-box`
            throw IllegalStateException("${`My Box`()} ${`my-box`()}")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.withoutHashCodes().lines().first() sameAs
                    "java.lang.IllegalStateException: My Box@<hash> my-box@<hash>"
        }
    }

    @Test
    fun `should report class cast of local class of function named like class enclosing object literal`() = runTest {
        // given
        val script = """
            class Shape { val x = object {} }
            fun Shape(n: Int): Any {
                class Impl
                return Impl()
            }
            Shape(1) as Shape
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first().substringBefore(" (") sameAs
                    "java.lang.ClassCastException: class Impl cannot be cast to class Shape"
        }
    }

    @Test
    fun `should report lambda class name followed by other names`() = runTest {
        // given
        val script = """
            class Box
            val f = { 1 }
            throw IllegalStateException(Box::class.java.name + " vs " + f.javaClass.name)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalStateException: Box vs <lambda>"
        }
    }

    @Test
    fun `should report very long exception message of many names resembling classes of the script`() = runTest {
        // given
        val script = """
            throw IllegalStateException("_.Nope ".repeat(100_000) + (1..100_000).joinToString(" ") { "_.Nope${'$'}it" })
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalStateException: " + "_.Nope ".repeat(100_000) + (1..100_000).joinToString(" ") { "_.Nope$it" }
        }
    }

    @Test
    fun `should report exception message of many names resembling a long string literal of the script`() = runTest {
        // given
        val literal = "_\${'$'}Box ".repeat(10_000) + "Z"
        val script = """
            enum class Box { A }
            val written = "$literal"
            throw IllegalStateException(written.dropLast(1).repeat(2) + "Y")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalStateException: " + "Box ".repeat(20_000) + "Y"
        }
    }

    @Test
    fun `should report frames by the Kotlin names after exception message of many names resembling classes of the script`() = runTest {
        // given
        val script = $$"""
            enum class Box { A }
            class G { val x: Int get() = error((1..400).joinToString(" ") { Box::class.java.name + "\$Nope" + it }) }
            fun h() = G().x
            h()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().drop(1).joinToString("\n") sameAs listOf(
                "  at G.<get-x>(script.kts:2)",
                $$"""  | class G { val x: Int get() = error((1..400).joinToString(" ") { Box::class.java.name + "\$Nope" + it }) }""",
                "  at h(script.kts:3)",
                "  | fun h() = G().x",
                "  at <script body>(script.kts:4)",
                "  | h()"
            ).joinToString("\n")
        }
    }

    @Test
    fun `should keep names written by script with carriage return line breaks`() = runTest {
        // given
        val script = "// the class\renum class C { X }\rthrow IllegalStateException(\"_.C \" + C.X)"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs "java.lang.IllegalStateException: _.C X"
        }
    }

    @Test
    fun `should keep names written in string literals with unicode escapes`() = runTest {
        // given
        val script = $$"""
            enum class Box { A }
            throw IllegalStateException("_${'\u0024'}Box")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs $$"java.lang.IllegalStateException: _$Box"
        }
    }

    @Test
    fun `should report exception with a very long message resembling the name of the script class`() = runTest {
        // given
        val script = """
            error("_" + ".".repeat(100_000) + "${'$'}".repeat(100_000))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message.lines().first() sameAs
                    "java.lang.IllegalStateException: _" + ".".repeat(100_000) + "$".repeat(100_000)
        }
    }

    /** The line numbers of the frames of the JDK, like `Enum.java:293`, which differ between its versions. */
    private fun String.withoutJdkLineNumbers() = replace(Regex("(\\w+\\.java):\\d+\\)"), "$1:<line>)")

    /** Like `@1b6d3586` of the default string representation, which differs on every run. */
    private fun String.withoutHashCodes() = replace(Regex("@[0-9a-f]+\\b"), "@<hash>")

    @Test
    fun `should report suppressed exceptions`() = runTest {
        // given
        val script = """
            class Resource : AutoCloseable {
                override fun close() {
                    throw IllegalStateException("close failed")
                }
            }
            Resource().use { throw RuntimeException("body failed") }
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.RuntimeException: body failed
                  at <script body>(script.kts:6)
                  | Resource().use { throw RuntimeException("body failed") }
                  Suppressed: java.lang.IllegalStateException: close failed
                    at Resource.close(script.kts:3)
                    |         throw IllegalStateException("close failed")
                    at <script body>(script.kts:6)
                    | Resource().use { throw RuntimeException("body failed") }
            """.trimIndent()
        }
    }

    @Test
    fun `should report circular causes`() = runTest {
        // given
        val script = """
            val first = RuntimeException("first")
            val second = RuntimeException("second", first)
            first.initCause(second)
            throw first
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.RuntimeException: first
                  at <script body>(script.kts:1)
                  | val first = RuntimeException("first")
                Caused by: java.lang.RuntimeException: second
                  at <script body>(script.kts:2)
                  | val second = RuntimeException("second", first)
                Caused by: [CIRCULAR REFERENCE: java.lang.RuntimeException: first]
            """.trimIndent()
        }
    }

    @Test
    fun `should indent continuation lines of multiline diagnostic message`() = runTest {
        // given
        val script = """
            fun twice(): Int = 1
            fun twice(): Int = 2
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:1: error: Conflicting overloads:
                  fun twice(): Int
                  | <error>fun twice(): Int</error> = 1

                script.kts:2:1: error: Conflicting overloads:
                  fun twice(): Int
                  | <error>fun twice(): Int</error> = 2
            """.trimIndent()
        }
    }

    @Test
    fun `should report compiler warnings together with runtime exception`() = runTest {
        // given
        val script = """
            val text = 42 as String
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.ClassCastException: class java.lang.Integer cannot be cast to class java.lang.String (java.lang.Integer and java.lang.String are in module java.base of loader 'bootstrap')
                  at <script body>(script.kts:1)
                  | val text = 42 as String

                script.kts:1:15: warning: This cast can never succeed.
                  | val text = 42 <error>as</error> String
            """.trimIndent()
        }
    }

    @Test
    fun `should report unmatched closing brace in the middle of the script`() = runTest {
        // given
        val script = """
            val a = 1
            }
            val b = 2
            b
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:1: error: Syntax error: Unexpected '}' without matching '{'.
                  | <error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should not treat import in a multiline string as an import`() = runTest {
        // given
        val script = """
            val text = ""${'"'}
            import this is text
            ""${'"'}
            text.trim()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "import this is text")
        }
    }

    @Test
    fun `should handle indented imports, file annotations and comments`() = runTest {
        // given
        val script = """
            @file:OptIn(ExperimentalStdlibApi::class)
            // the imports
              import kotlin.math.max
            /* the other import */ import kotlin.math.min
            max(1, min(2, 3))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should report errors at original lines after file annotation and imports`() = runTest {
        // given
        val script = """
            @file:OptIn(ExperimentalStdlibApi::class)
            
            import kotlin.math.max
            
            max(1, undefined)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:5:8: error: Unresolved reference 'undefined'.
                  | max(1, <error>undefined</error>)
            """.trimIndent()
        }
    }

    @Test
    fun `should execute script declaring types which cannot be local`() = runTest {
        // given
        val script = $$"""
            enum class Color { RED, GREEN }
            interface Shape { val color: Color }
            sealed class Figure : Shape
            data class Circle(override val color: Color) : Figure()
            object Registry {
                val figures = mutableListOf<Figure>()
            }
            class Palette {
                companion object {
                    val default = Color.GREEN
                }
            }
            typealias Figures = List<Figure>
            @JvmInline
            value class Name(val value: String)
            fun interface Describer { fun describe(figure: Figure): String }
            annotation class Marker
            
            Registry.figures += Circle(Palette.default)
            val figures: Figures = Registry.figures
            val describer = Describer { figure ->
                when (figure) {
                    is Circle -> "circle"
                }
            }
            "${Name(describer.describe(figures.single())).value} ${figures.single().color}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "circle GREEN")
        }
    }

    @Test
    fun `should execute script with top-level declarations having modifiers`() = runTest {
        // given
        val script = $$"""
            import kotlinx.coroutines.delay
            
            private val greeting = "Hello"
            private suspend fun greet(name: String): String {
                delay(1)
                return "$greeting $name"
            }
            inline fun <reified T> typeName() = T::class.simpleName!!
            val String.shouted get() = uppercase() + "!"
            val answer: Int
                get() = 42
            "${greet(typeName<String>())} ${"x".shouted} $answer"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello String X! 42")
        }
    }

    @Test
    fun `should give access to dependencies in the classes declared in the script`() = runTest {
        // given
        val script = $$"""
            import kotlinx.coroutines.delay
            
            enum class Punctuation(val mark: String) { EXCLAMATION("!") }
            class Greeter(val punctuation: Punctuation) {
                suspend fun greet(): String {
                    delay(1)
                    return "Hello $name${punctuation.mark}"
                }
            }
            Greeter(Punctuation.EXCLAMATION).greet()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("name", "World")))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello World!")
        }
    }

    @Test
    fun `should report runtime error at original line of declaration moved out of the script body`() = runTest {
        // given
        val script = """
            val before = 1
            enum class Level { LOW, HIGH }
            object Checker {
                fun check(level: Level) {
                    if (level == Level.HIGH) throw IllegalArgumentException("too high")
                }
            }
            Checker.check(Level.HIGH)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalArgumentException: too high
                  at Checker.check(script.kts:5)
                  |         if (level == Level.HIGH) throw IllegalArgumentException("too high")
                  at <script body>(script.kts:8)
                  | Checker.check(Level.HIGH)
            """.trimIndent()
        }
    }

    @Test
    fun `should report compilation error at original line of declaration moved out of the script body`() = runTest {
        // given
        val script = """
            val before = 1
            data class Point(val x: Int, val y: Int)
            enum class Direction(val delta: Point) {
                UP(Point(0, missing))
            }
            Direction.UP
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:17: error: Unresolved reference 'missing'.
                  |     UP(Point(0, <error>missing</error>))
            """.trimIndent()
        }
    }

    @Test
    fun `should hint that declaration moved out of the script body cannot access its local declarations`() = runTest {
        // given
        val script = """
            val base = 10
            object Scaler {
                fun scale(x: Int) = x * base
            }
            Scaler.scale(2)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:29: error: Unresolved reference 'base'.
                  |     fun scale(x: Int) = x * <error>base</error>
                  hint: 'Scaler' is compiled outside of the script body, where 'base' (line 1) is not visible - pass it as a parameter, or declare it within 'Scaler'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at object accessing dependency`() = runTest {
        // given
        val script = $$"""
            val before = 1
            object Greeter {
                fun greet() = "Hello $name"
            }
            Greeter.greet()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("name", "World")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:8: error: Object 'Greeter' captures the script class instance. Try to use class or anonymous object instead.
                  | object <error>Greeter</error> {
                  hint: 'Greeter' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should keep local classes accessing local declarations of the script body`() = runTest {
        // given
        val script = """
            val base = 10
            class Scaler {
                fun scale(x: Int) = x * base
            }
            Scaler().scale(2)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 20)
        }
    }


    @Test
    fun `should call function declared later in the script`() = runTest {
        // given
        val script = $$"""
            val greeting = greet("World")
            fun greet(name: String) = "Hello $name"
            greeting
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello World")
        }
    }

    @Test
    fun `should call function accessing local declarations of the script body before its declaration`() = runTest {
        // given
        val script = """
            val prefix = ">".trim()
            val formatted = format("x")
            fun format(text: String) = prefix + text
            formatted
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == ">x")
        }
    }

    @Test
    fun `should hint to declare function before its use when it accesses local declarations of the script body declared after the use`() = runTest {
        // given
        val script = """
            val formatted = format("x")
            val prefix = ">".trim()
            fun format(text: String) = prefix + text
            formatted
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:17: error: Unresolved reference 'format'.
                  | val formatted = <error>format</error>("x")
                  hint: 'format' is declared at line 3, and accesses 'prefix' declared after this call, while the declarations of the script body are visible only after their declaration - move them all before line 1, or pass 'prefix' as a parameter.
            """.trimIndent()
        }
    }

    @Test
    fun `should call function accessing local declarations of the script body from function declared before it`() = runTest {
        // given
        val script = """
            val factor = 2
            fun a(x: Int) = b(x) + 1
            fun b(x: Int) = x * factor
            a(3)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 7)
        }
    }

    @Test
    fun `should call chain of functions accessing local declarations of the script body declared in reverse order`() = runTest {
        // given
        val script = $$"""
            val name = "World"
            fun greet() = "${salutation()}, ${target()}!"
            val unrelated = 1
            fun salutation() = if (target() == name) "Hello" else "Hi"
            fun target() = name
            println(greet())
            greet() + unrelated
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello, World!1")
        }
    }

    @Test
    fun `should return Unit from script ending with function accessing local declarations called before its declaration`() = runTest {
        // given
        val script = """
            val (w, h) = 3 to 4
            val area = computeArea()
            fun computeArea() = w * h
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == Unit)
        }
    }

    @Test
    fun `should report frames at lines of function accessing local declarations called before its declaration`() = runTest {
        // given
        val script = """
            val limit = 1
            fun a(x: Int) = b(x)
            fun b(x: Int): Int = if (x > limit) error("too big: ${'$'}x") else x
            a(2)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs $$"""
                java.lang.IllegalStateException: too big: 2
                  at b(script.kts:3)
                  | fun b(x: Int): Int = if (x > limit) error("too big: $x") else x
                  at a(script.kts:2)
                  | fun a(x: Int) = b(x)
                  at <script body>(script.kts:4)
                  | a(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should hint that mutually recursive functions accessing local declarations of the script body cannot call each other`() = runTest {
        // given
        val script = """
            val limit = "10".toInt()
            fun even(n: Int): Boolean = if (n >= limit) true else odd(n + 1)
            fun odd(n: Int): Boolean = if (n >= limit) false else even(n + 1)
            even(0)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:55: error: Unresolved reference 'odd'.
                  | fun even(n: Int): Boolean = if (n >= limit) true else <error>odd</error>(n + 1)
                  hint: 'odd' is declared at line 3, and calls 'even' back, while the functions of the script body accessing its local declarations are visible only after their declaration, so they cannot call each other - pass the local declarations they access as parameters instead.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function accessing local declarations before its declaration when local class declared in between calls own member of its name`() = runTest {
        // given
        val script = $$"""
            val factor = 2
            fun a() = b()
            class C { fun x() = b()
             fun b() = "inner" }
            fun b() = "local $factor"
            a() + C().x()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'b'.
                  | fun a() = <error>b</error>()
                  hint: 'b' is declared at line 5, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function accessing local declarations before its declaration when local class declared in between calls constructor property of its name`() = runTest {
        // given
        val script = $$"""
            val factor = 2
            fun a() = b()
            class C(val b: () -> String) { fun x() = b() }
            fun b() = "local $factor"
            a() + C { "inner" }.x()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'b'.
                  | fun a() = <error>b</error>()
                  hint: 'b' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function accessing local declarations before its declaration when it calls member of lambda receiver named like function declared in between`() = runTest {
        // given
        val script = $$"""
            val factor = "2".toInt()
            class Obj { fun b() = "member" }
            fun a() = c()
            fun b() = "local $factor"
            fun c() = with(Obj()) { b() }
            a()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:11: error: Unresolved reference 'c'.
                  | fun a() = <error>c</error>()
                  hint: 'c' is declared at line 5, while the functions of the script body are visible only after their declaration - move it before line 3.
            """.trimIndent()
        }
    }

    @Test
    fun `should call function accessing local declarations before its declaration from lambda with receiver without member of its name`() = runTest {
        // given
        val script = $$"""
            val factor = 2
            class Obj { fun x() = "member" }
            fun a() = b()
            val r = with(Obj()) { b() }
            fun b() = "local $factor"
            a() + r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "local 2local 2")
        }
    }

    @Test
    fun `should call function accessing local declarations before its declaration from statement sharing line ending multiline string`() = runTest {
        // given
        val script = "val factor = 2\nval text = \"\"\"\nmulti\n\"\"\"; fun a() = b() + text.length\nfun b() = factor\na()"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 9)
        }
    }

    @Test
    fun `should not call function accessing local declarations before its declaration when it calls function declared in between`() = runTest {
        // given
        val script = $$"""
            val factor = "2".toInt()
            fun main() = report()
            fun format(x: Int) = "${x * factor}"
            fun report() = format(1)
            main()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:14: error: Unresolved reference 'report'.
                  | fun main() = <error>report</error>()
                  hint: 'report' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function accessing local declarations before its declaration when it calls other function than the local one of its name declared later`() = runTest {
        // given
        val script = $$"""
            val factor = "2".toInt()
            fun a() = b()
            fun b() = print(factor)
            fun print(x: Any) = "printed $x $factor"
            a()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:11: error: 'print' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.
                  | fun b() = <error>print</error>(factor)
                  hint: 'print' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 3.

                script.kts:2:11: error: Unresolved reference 'b'.
                  | fun a() = <error>b</error>()
                  hint: 'b' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function of inferred type accessing local declarations before its declaration when it calls function within lambda with receiver`() = runTest {
        // given
        val script = $$"""
            val factor = "2".toInt()
            class Obj { fun b() = "member" }
            fun b() = "local $factor"
            fun a() = c()
            fun c() = with(Obj()) { b() } + factor
            val x = d()
            fun d() = run { with(Obj()) { b() } } + factor
            a() + x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:11: error: Unresolved reference 'c'.
                  | fun a() = <error>c</error>()
                  hint: 'c' is declared at line 5, while the functions of the script body are visible only after their declaration - move it before line 4.

                script.kts:6:9: error: Unresolved reference 'd'.
                  | val x = <error>d</error>()
                  hint: 'd' is declared at line 7, while the functions of the script body are visible only after their declaration - move it before line 6.
            """.trimIndent()
        }
    }

    @Test
    fun `should call function of declared type accessing local declarations before its declaration when it calls function within lambda with receiver`() = runTest {
        // given
        val script = $$"""
            val factor = 2
            class Obj { fun b() = "member" }
            fun b() = "local $factor"
            fun a() = c()
            fun c(): String = with(Obj()) { b() } + factor
            a()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "local 22")
        }
    }

    @Test
    fun `should not hint that function calls back the one calling it when it only binds a value of its name`() = runTest {
        // given
        val script = $$"""
            val k = "!".trim()
            fun format(x: Int) = render(x)
            fun render(x: Int) = listOf(x).joinToString { format -> "$format$k" }
            format(1)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:22: error: Unresolved reference 'render'.
                  | fun format(x: Int) = <error>render</error>(x)
                  hint: 'render' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call function accessing local variable before its declaration`() = runTest {
        // given
        val script = """
            var count = 0
            fun a() = b()
            fun b() = ++count
            a()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'b'.
                  | fun a() = <error>b</error>()
                  hint: 'b' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }


    @Test
    fun `should report compiler warnings of successfully executed script`() = runTest {
        // given
        val script = """
            val number: Int = 1
            if (number is Int) "yes" else "no"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "yes")
            warnings sameAs """
                script.kts:2:5: warning: Check for instance is always 'true'.
                  | if (<error>number is Int</error>) "yes" else "no"
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of nested local functions`() = runTest {
        // given
        val script = """
            fun outer(): Int {
                fun inner(): Int = error("deep")
                return inner()
            }
            outer()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: deep
                  at inner(script.kts:2)
                  |     fun inner(): Int = error("deep")
                  at outer(script.kts:3)
                  |     return inner()
                  at <script body>(script.kts:5)
                  | outer()
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of local class and lambda`() = runTest {
        // given
        val script = """
            class Worker {
                fun work(): Int = error("cannot work")
            }
            val task = Runnable { Worker().work() }
            task.run()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: cannot work
                  at Worker.work(script.kts:2)
                  |     fun work(): Int = error("cannot work")
                  at <lambda>(script.kts:4)
                  | val task = Runnable { Worker().work() }
                  at <script body>(script.kts:5)
                  | task.run()
            """.trimIndent()
        }
    }

    @Test
    fun `should execute script with top-level declarations having modifiers and annotations not applicable to local declarations`() = runTest {
        // given
        val script = """
            val offset = 1
            const val MAX = 10
            open fun twice(x: Int) = x * 2 + offset
            @Volatile var counter = 0
            @JvmField val step = 2
            @JvmOverloads fun add(a: Int, b: Int = step) = a + b
            counter = add(twice(MAX))
            counter
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 23)
        }
    }

    @Test
    fun `should hint that const val of the script body cannot be used as annotation argument`() = runTest {
        // given
        val script = """
            const val MSG = "old"
            @Deprecated(MSG) fun old() = 1
            old()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:13: error: Annotation argument must be a compile-time constant.
                  | @Deprecated(<error>MSG</error>) fun old() = 1
                  hint: 'MSG' (line 1) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint that properties of the script body cannot be used in constant expressions of annotations`() = runTest {
        // given
        val script = """
            val prefix = "do"
            const val NAME = "run"
            val UNUSED = "UNUSED_VARIABLE"
            @JvmName(NAME) fun start() = prefix
            @Suppress("A" + UNUSED) val x = 1
            annotation class Tag(val value: String = NAME)
            start()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:10: error: Only 'const val' can be used in constant expressions.
                  | @JvmName(<error>NAME</error>) fun start() = prefix
                  hint: 'NAME' (line 2) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.

                script.kts:6:42: error: Default value of annotation parameter must be a compile-time constant.
                  | annotation class Tag(val value: String = <error>NAME</error>)
                  hint: 'NAME' (line 2) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.

                script.kts:5:11: error: Annotation argument must be a compile-time constant.
                  | @Suppress(<error>"A" + UNUSED</error>) val x = 1
                  hint: 'UNUSED' (line 3) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at property of the script body when annotation argument refers to member property of the same name`() = runTest {
        // given
        val script = """
            const val MSG = "old"
            class Legacy {
                val MSG = "older"
                @Deprecated(MSG) fun old() = 1
            }
            Legacy().old()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:17: error: Annotation argument must be a compile-time constant.
                  |     @Deprecated(<error>MSG</error>) fun old() = 1

                script.kts:6:10: warning: 'fun old(): Int' is deprecated.
                  | Legacy().<error>old</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at property of the script body when annotation argument refers to const val of companion object`() = runTest {
        // given
        val script = """
            val MSG = "s"
            val suffix = "!"
            class Legacy {
                companion object { const val MSG = "old" }
                @Deprecated(MSG + suffix) fun old() = 1
            }
            Legacy().old()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:5:17: error: Only 'const val' can be used in constant expressions.
                  |     @Deprecated(<error>MSG + suffix</error>) fun old() = 1
                  hint: 'suffix' (line 2) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.

                script.kts:7:10: warning: 'fun old(): Int' is deprecated.
                  | Legacy().<error>old</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at property of the script body named like parameter of function in annotation argument`() = runTest {
        // given
        val script = """
            @Suppress("RedundantSuppression")
            const val NAME = "run"
            @JvmName(NAME) fun start(NAME: String) = NAME
            start("x")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:10: error: Annotation argument must be a compile-time constant.
                  | @JvmName(<error>NAME</error>) fun start(NAME: String) = NAME
                  hint: 'NAME' (line 2) is a property of the script body, which cannot be 'const', like a top-level property of a regular script - inline its value, or declare it as a 'const val' in an object, if it is a primitive or a string.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at property of the script body when annotation argument of object cannot refer to it`() = runTest {
        // given
        val script = """
            val MSG = "s"
            val suffix = "!"
            object Legacy {
                @Deprecated(MSG + suffix) fun old() = 1
            }
            Legacy.old()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:17: error: Unresolved reference 'MSG'.
                  |     @Deprecated(<error>MSG</error> + suffix) fun old() = 1
                  hint: 'Legacy' is compiled outside of the script body, where 'MSG' (line 1) is not visible - pass it as a parameter, or declare it within 'Legacy'.

                script.kts:4:17: error: Annotation argument must be a compile-time constant.
                  |     @Deprecated(<error>MSG + suffix</error>) fun old() = 1

                script.kts:4:17: error: Argument type mismatch: actual type is 'CoroutineScope', but 'String' was expected.
                  |     @Deprecated(<error>MSG + suffix</error>) fun old() = 1

                script.kts:4:23: error: Unresolved reference 'suffix'.
                  |     @Deprecated(MSG + <error>suffix</error>) fun old() = 1
                  hint: 'Legacy' is compiled outside of the script body, where 'suffix' (line 2) is not visible - pass it as a parameter, or declare it within 'Legacy'.

                script.kts:6:8: warning: 'fun old(): Int' is deprecated.
                  | Legacy.<error>old</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should execute script using const val of object as annotation argument`() = runTest {
        // given
        val script = """
            object Messages {
                const val OLD = "use new()"
            }
            @Deprecated(Messages.OLD) fun old() = 1
            @Suppress("DEPRECATION")
            old()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should not move function accessing local declarations when member of moved declaration has the same name`() = runTest {
        // given
        val script = """
            val prefix = "x"
            fun label(s: String) = prefix + s
            enum class Kind { A; fun label() = name }
            label(Kind.A.label())
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "xA")
        }
    }

    @Test
    fun `should report exception whose message cannot be read`() = runTest {
        // given
        val script = """
            class Unreadable : RuntimeException() {
                override val message: String get() = error("unreadable")
            }
            throw Unreadable()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                Unreadable: [message unavailable: java.lang.IllegalStateException: unreadable]
                  at <script body>(script.kts:4)
                  | throw Unreadable()
            """.trimIndent()
        }
    }

    /**
     * The exception is not copyable by the coroutine stack trace recovery,
     * so the outcome does not depend on whether it is enabled.
     */
    @Test
    fun `should report exception caused by exception of the same class and message`() = runTest {
        // given
        val script = """
            class Failure(val code: Int, cause: Throwable? = null) : RuntimeException("failure", cause)
            val cause = Failure(1)
            throw Failure(2, cause)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                Failure: failure
                  at <script body>(script.kts:3)
                  | throw Failure(2, cause)
                Caused by: Failure: failure
                  at <script body>(script.kts:2)
                  | val cause = Failure(1)
            """.trimIndent()
        }
    }

    @Test
    @OptIn(ExperimentalGadget::class)
    fun `should report error of dependency accessor at the dependency instead of the script line`() = runTest {
        // given
        val script = """
            gadgets.size
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("gadgets", listOf(Gadget()))))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                dependency 'gadgets': error: This declaration needs opt-in. Its usage must be marked with '@com.xemantic.kotlin.script.ExperimentalGadget' or '@OptIn(com.xemantic.kotlin.script.ExperimentalGadget::class)'

                script.kts:1:1: error: This declaration needs opt-in. Its usage must be marked with '@com.xemantic.kotlin.script.ExperimentalGadget' or '@OptIn(com.xemantic.kotlin.script.ExperimentalGadget::class)'
                  | <error>gadgets</error>.size
            """.trimIndent()
        }
    }

    @Test
    fun `should keep in the script body properties named get and set`() = runTest {
        // given
        val script = """
            val items = listOf(1, 2, 2)
            val set = items.toSet()
            val get = set.size + 1
            get
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should execute script with CR line endings`() = runTest {
        // given
        val script = "enum class E { A }\rval name = E.A.name\rname"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "A")
        }
    }

    @Test
    fun `should execute script starting with UTF-8 byte order mark`() = runTest {
        // given
        val script = "\uFEFF1 + 1"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should execute script starting with UTF-8 byte order mark followed by file annotation and package`() = runTest {
        // given
        val script = "\uFEFF@file:Suppress(\"UNUSED_VARIABLE\")\npackage foo\nval x = 1\nx + 1"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should report error column of script starting with UTF-8 byte order mark not counting it`() = runTest {
        // given
        val script = "\uFEFF1 + foo"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:5: error: Unresolved reference 'foo'.
                  | 1 + <error>foo</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should hint to declare function before its use when it accesses destructured local declarations`() = runTest {
        // given
        val script = """
            println(area())
            val (w, h) = 3 to 4
            fun area() = w * h
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:9: error: Unresolved reference 'area'.
                  | println(<error>area</error>())
                  hint: 'area' is declared at line 3, and accesses 'w', 'h' declared after this call, while the declarations of the script body are visible only after their declaration - move them all before line 1, or pass 'w', 'h' as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report unmatched closing brace closing a parenthesis`() = runTest {
        // given
        val script = """
            println(1
            }
            val x = 1
            x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:10: error: Syntax error: Expecting ')'.
                  | println(1<error></error>

                script.kts:2:1: error: Syntax error: Unexpected '}' without matching '{'.
                  | <error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report long chain of causes without overflowing the stack`() = runTest {
        // given
        val script = $$"""
            var exception: Throwable = IllegalStateException("root")
            repeat(100_000) { exception = RuntimeException("wrapper $it", exception) }
            throw exception
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // the message is not asserted as a whole, so that the failure would not report all of it
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
        }
        val lines = (result as ScriptResult.Error).message.lines()
        val lineCount = lines.size
        val firstLine = lines.first()
        val lastLine = lines.last()
        assert(lineCount < 200)
        assert(firstLine == "java.lang.RuntimeException: wrapper 99999")
        assert(lastLine == "Caused by: [FURTHER EXCEPTIONS OMITTED]")
    }

    @Test
    fun `should report many suppressed exceptions omitting the further ones`() = runTest {
        // given
        val script = """
            val exception = IllegalStateException("main")
            repeat(100_000) { exception.addSuppressed(RuntimeException("suppressed")) }
            throw exception
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // the message is not asserted as a whole, so that the failure would not report all of it
        result should {
            be<ScriptResult.Error>()
        }
        val lines = (result as ScriptResult.Error).message.lines()
        val lineCount = lines.size
        val omittedLineCount = lines.count { it.endsWith("[FURTHER EXCEPTIONS OMITTED]") }
        val lastLine = lines.last()
        assert(lineCount < 200)
        assert(omittedLineCount == 1)
        assert(lastLine == "  Suppressed: [FURTHER EXCEPTIONS OMITTED]")
    }

    @Test
    fun `should return Unit when script ends with declaration moved out of the script body`() = runTest {
        // given
        val scripts = listOf(
            "42\nenum class Color { RED }",
            "42\ninterface Shape",
            "42\nclass Point"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Value>()
                have(value is Unit)
            }
        }
    }

    @Test
    fun `should move property with accessor having annotation or modifier`() = runTest {
        // given
        val script = """
            val String.shouted: String
                @Suppress("unused") get() = uppercase() + "!"
            val answer: Int
                inline get() = 42
            "x".shouted + answer
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "X!42")
        }
    }

    @Test
    fun `should execute script with property getter reading local property`() = runTest {
        // given
        val script = """
            val items = mutableListOf(1, 2)
            val total: Int get() = items.sum()
            items += 3
            total
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6)
        }
    }

    @Test
    fun `should execute scripts with property accessors reading local properties`() = runTest {
        // given
        val scripts = listOf(
            "val items = mutableListOf(1, 2)\nvar first: Int\n    get() = items.first()\n    set(v) { items[0] = v * 2 }\nfirst = 10\nitems.toString() + first",
            "val items = mutableListOf(1, 2)\nvar first: Int\n    set(value: Int) { items[0] = value }\n    get(): Int { return items.first() }\nfirst = 10\nitems.toString() + first",
            "val items = mutableListOf(1, 2)\nval total get() = items.sum()\nval double get() = total * 2\nitems += 3\ndouble",
            "val items = mutableListOf(1, 2)\nval total: Int get() = items.sum()\nfun show() = \"total: \$total\"\nitems += 3\nshow()",
            "val items = mutableListOf(1, 2)\nprivate val total get(): Int = items.sum(); items += 3; total",
            "val items = mutableListOf(1, 2)\n/** The sum. */\n@Suppress(\"unused\")\nval total: Int get() = items.sum() // the sum\nitems += 3\ntotal",
            "import kotlin.math.max\nval items = mutableListOf(1, 5)\nval top get() = items.fold(0) { a, b -> max(a, b) } + listOf(1).size + Pair(1, 2).first + items.map { it }.size\ntop",
            // named like the parameters of the operators of the object
            "val _property = \"local\"\nval _thisRef = \"!\"\nval items = listOf(1)\nval t get() = _property + _thisRef + items.size\nt"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("[20, 2]20", "[10, 2]10", 12, "total: 6", 6, 6, 9, "local!1"))
    }

    @Test
    fun `should not resolve calls of property accessors reading local properties on the implicit receivers of the script body`() = runTest {
        // given
        val scripts = listOf(
            "val items = listOf(1, 2)\nval t get() = items.size.toString() + javaClass.name\nt",
            "val items = listOf(1, 2)\nval t get() = items.size.toString() + let { it::class.simpleName }\nt",
            "val items = listOf(1, 2)\nval t get() = items.isEmpty() || isActive\nt",
            "val items = listOf(1, 2)\nval t get() = items.size.also { launch { } }\nt",
            "import kotlinx.coroutines.launch\nval items = mutableListOf(1, 2)\nval t: Int\n    get() { launch { items += 5 }; return items.size }\nt",
            "import kotlinx.coroutines.isActive\nval items = listOf(1, 2)\nval t get() = items.isEmpty() || isActive\nt",
            "import kotlin.let\nval items = listOf(1, 2)\nval t get() = items.size.toString() + let { 1 }\nt",
            "import kotlin.jvm.javaClass\nval items = listOf(1, 2)\nval t get() = items.size.toString() + javaClass.name\nt",
            "fun let(x: Int) = x\nval items = listOf(1, 2)\nval t get() = items.size.toString() + let { it::class.simpleName }\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + isActive\nval isActive = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + javaClass\nval javaClass = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + coroutineContext\nval coroutineContext = \"mine\"\nt",
            "val items = listOf(1)\nval t get() = items.size.toString() + ensureActive()\nfun ensureActive() = \"mine\"\nt"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) } +
                executor.execute(scripts[1], listOf(dependency("let", 1)))

        // then
        assert(results.all { (it as? ScriptResult.Error)?.phase == ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should execute script with property getter reading local property and referring to names resolved by the script`() = runTest {
        // given
        val script = """
            fun double(x: Int) = x * 2
            val items = listOf(listOf(1, 2), listOf(3))
            val isActive = 10
            val total get() = maxOf(items.size, 1) + listOf(bonus).sum() + double(items.sumOf { it.count { it > 1 } }) + isActive
            total
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("bonus", 100)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2 + 100 + 4 + 10)
        }
    }

    @Test
    fun `should report exception thrown by property getter reading local property at the lines of the script`() = runTest {
        // given
        val script = """
            val items = mutableListOf<Int>()
            @Suppress("unused")
            val first: Int
                get() {
                    val offset = 1
                    return items.first() + offset
                }
            first
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        val lines = (result as ScriptResult.Error).message.lines()
        assert(lines.first() == "java.util.NoSuchElementException: List is empty.")
        assert(lines.drop(2) == listOf(
            "  at <lambda>(script.kts:6)",
            "  |         return items.first() + offset",
            "  at <lambda>(script.kts:3)",
            "  | val first: Int",
            "  at <script body>(script.kts:8)",
            "  | first"
        ))
    }

    @Test
    fun `should report error at property getter reading local property`() = runTest {
        // given
        val script = """
            val items = mutableListOf(1, 2)
            val total: String get() = items.sum()
            total
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:27: error: Return type mismatch: expected 'String', actual 'Int'.
                  | val total: String get() = <error>items.sum()</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should execute statement following star import in the script body`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.*
            delay(1)
            "done"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "done")
        }
    }

    @Test
    fun `should not treat name in string literal as reference to function declared later`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.launch
            val label = "work"
            fun work() = launch { }
            work()
            label
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "work")
        }
    }

    @Test
    fun `should not treat member access as reference to function declared later`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.launch
            val list = listOf(1)
            val count = list.size
            fun size() = launch { }
            size()
            count
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should not treat locally bound name as reference to function declared later`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.launch
            val sizes = listOf("ab").map { work -> work.length }
            var total = 0
            for (work in sizes) total += work
            fun work() = launch { }
            work()
            sizes + total
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == listOf(2, 2))
        }
    }

    @Test
    fun `should refer to class declared later by name of local function or member extension property before it`() = runTest {
        // given
        val check = "Regex::class.java.declaredMethods.any { it.name == \"mine\" }"
        val bindings = listOf(
            "val found = run {\n    fun Regex() = 1\n    $check\n}",
            "class Box {\n    fun Regex() = 2\n    val found = $check\n}\nval found = Box().found",
            "class Box {\n    val Int.Regex get() = 3\n    val found = $check\n}\nval found = Box().found"
        )
        val scripts = bindings.map { "$it\nclass Regex { fun mine() = 4 }\nfound" }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Value>()
                have(value == true)
            }
        }
    }

    @Test
    fun `should call extension function declared later in the script`() = runTest {
        // given
        val script = """
            val doubled = 21.double()
            fun Int.double() = this * 2
            doubled
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should not treat class taking sealed type as constructor parameter as its subtype`() = runTest {
        // given
        val script = """
            val factor = 2
            sealed interface Shape
            class Circle(val r: Int) : Shape
            class Scaler(val s: Shape) { fun scale() = factor }
            Scaler(Circle(1)).scale()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should return Unit when script ends with declaration moved out of the script body followed by semicolon`() = runTest {
        // given
        val script = "42\ninterface Foo;"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value is Unit)
        }
    }

    @Test
    fun `should report anonymous exception class by its superclass`() = runTest {
        // given
        val script = """
            open class Failure(message: String) : RuntimeException(message)
            fun fail(): Nothing = throw object : Failure("anonymous failure") {}
            fail()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                <anonymous Failure>: anonymous failure
                  at fail(script.kts:2)
                  | fun fail(): Nothing = throw object : Failure("anonymous failure") {}
                  at <script body>(script.kts:3)
                  | fail()
            """.trimIndent()
        }
    }

    @Test
    fun `should split statements spanning lines according to the Kotlin grammar`() = runTest {
        // given
        val script = """
            import kotlinx.coroutines.*
            val numbers = listOf(1, 2, 3)
                .map { it * 2 } /* a comment
                spanning lines */
            var sum = 0
            outer@ for (n in numbers) { if (n > 4) break@outer; sum += n }
            fun total() = launch { sum *= 2 }
            total().join()
            val result = sum *
                2
            result
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 24)
        }
    }

    @Test
    fun `should end script with unterminated block comment like regular script`() = runTest {
        // given
        val scripts = listOf(
            "val a = 2\n/* abc\na",
            "val a = 2\na /** doc",
            "val a = 2\na\n/* a /* b */ c",
            "fun f() = 2\nf()\n/* abc",
            "import kotlin.math.PI /* abc"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(Unit, 2, 2, 2, Unit))
    }

    @Test
    fun `should report unterminated block comment within braces of script where it swallows closing brace`() = runTest {
        // given
        val scripts = listOf(
            "class A {\n  /* abc\n  val x = 1\n}\nA().x",
            "class A {\n  val x = 1\n  /* abc\n}\nA().x",
            "fun f(): Int {\n  /* abc\n  return 1\n}\nf()",
            "val a = 5\n\"\${a /* x }\""
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first() } == listOf(
            "script.kts:1:10: error: Syntax error: Missing '}.",
            "script.kts:2:12: error: Syntax error: Missing '}.",
            "script.kts:1:15: error: Syntax error: Expecting '}'.",
            "script.kts:2:5: error: Syntax error: Expecting '}'."
        ))
    }

    @Test
    fun `should report construct of script left unclosed at its end like regular script`() = runTest {
        // given
        val scripts = listOf(
            "A().x\nclass A {\n  val x = 1\n  /* abc\n}",
            "f()\nfun f(): Int {\n  /* abc\n  return 1\n}",
            "A().x\nclass A {\n  val x = 1\n",
            "val a = 5\n\"\${a",
            "f()\nfun f() = \"\${1 + \n"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message } == listOf(
            "script.kts:3:12: error: Syntax error: Missing '}.\n  |   val x = 1<error></error>\n\nscript.kts:4:3: info: Unterminated block comment, running to the end of the script.\n  |   <error>/*</error> abc\n  hint: close the comment with '*/' - the tokens reported as missing might be within it.",
            "script.kts:2:15: error: Syntax error: Expecting '}'.\n  | fun f(): Int {<error></error>\n\nscript.kts:3:3: info: Unterminated block comment, running to the end of the script.\n  |   <error>/*</error> abc\n  hint: close the comment with '*/' - the tokens reported as missing might be within it.",
            "script.kts:3:12: error: Syntax error: Missing '}.\n  |   val x = 1<error></error>",
            "script.kts:2:5: error: Syntax error: Expecting '}'.\n  | \"\${a<error></error>",
            "script.kts:2:17: error: Syntax error: Expecting an element.\n  | fun f() = \"\${1 +<error></error>"
        ))
    }

    @Test
    fun `should report syntax errors of script with unmatched closing brace like regular script`() = runTest {
        // given
        val scripts = listOf(
            "val a = (1\n}",
            "foo(1 }",
            "foo(1}"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message } == listOf(
            "script.kts:1:11: error: Syntax error: Expecting ')'.\n  | val a = (1<error></error>\n\n" +
                    "script.kts:2:1: error: Syntax error: Unexpected '}' without matching '{'.\n  | <error>}</error>",
            "script.kts:1:6: error: Syntax error: Expecting ')'.\n  | foo(1<error></error> }\n\n" +
                    "script.kts:1:7: error: Syntax error: Unexpected '}' without matching '{'.\n  | foo(1 <error>}</error>",
            "script.kts:1:6: error: Syntax error: Expecting ')'.\n  | foo(1<error></error>}\n\n" +
                    "script.kts:1:6: error: Syntax error: Unexpected '}' without matching '{'.\n  | foo(1<error>}</error>"
        ))
    }

    @Test
    fun `should hint at unterminated block comment swallowing closing brace`() = runTest {
        // given
        val script = "class A {\n  val x = 1\n  /* abc\n}\nA().x"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:12: error: Syntax error: Missing '}.
                  |   val x = 1<error></error>

                script.kts:3:3: info: Unterminated block comment, running to the end of the script.
                  |   <error>/*</error> abc
                  hint: close the comment with '*/' - the tokens reported as missing might be within it.
            """.trimIndent()
        }
    }

    @Test
    fun `should report only syntax error of script left unclosed at its end referring to dependency`() = runTest {
        // given
        val script = "val y = x + 1\nfun f(): Int {\n  return y"
        val dependencies = listOf(dependency("x", 42))
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, dependencies)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:11: error: Syntax error: Expecting '}'.
                  |   return y<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should call function declared later when its own names shadow local declarations of the script body`() = runTest {
        // given
        val script = """
            val items = listOf(1, 2)
            val n = 3
            val result = total(items) + count(n) + doubled(items)
            fun total(items: List<Int>) = items.sum()
            fun count(limit: Int): Int {
                var n = 0
                for (i in 0 until limit) n++
                return n
            }
            fun doubled(list: List<Int>) = list.sumOf { items -> items * 2 }
            result
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 12)
        }
    }

    @Test
    fun `should move function referred to by declaration which cannot be local when its parameter shadows local declaration`() = runTest {
        // given
        val script = """
            sealed interface Shape
            class Square(val side: Int) : Shape {
                fun area(scale: Int) = scaled(this, scale)
            }
            fun scaled(square: Square, scale: Int) = square.side * square.side * scale
            val scale = 2
            Square(3).area(scale)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 18)
        }
    }

    @Test
    fun `should initialize properties with annotations not applicable to local declarations in the order of the script`() = runTest {
        // given
        val script = """
            log += "a"
            @JvmField val x = run { log += "b"; 1 }
            @Volatile var y = run { log += "c"; 2 }
            @property:Suppress("unused") val z = run { log += "d"; 3 }
            log += "e"
            "${'$'}log ${'$'}{x + y + z}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("log", mutableListOf<String>())))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "[a, b, c, d, e] 6")
        }
    }

    @Test
    fun `should use dependency of generic type declared in package named with keyword`() = runTest {
        // given
        val script = """
            box.value + 1
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("box", `in`.xemantic.Box(42))))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 43)
        }
    }

    @Test
    fun `should report frame of script function named lambda`() = runTest {
        // given
        val script = """
            fun lambda(): Int = error("failure")
            lambda()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: failure
                  at lambda(script.kts:1)
                  | fun lambda(): Int = error("failure")
                  at <script body>(script.kts:2)
                  | lambda()
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of property getter by the property name`() = runTest {
        // given
        val script = """
            val g: Int get() = error("boom")
            g
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at <get-g>(script.kts:1)
                  | val g: Int get() = error("boom")
                  at <script body>(script.kts:2)
                  | g
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of property setter by the property name`() = runTest {
        // given
        val script = """
            var s: Int
                get() = 0
                set(value) { error("cannot set ${'$'}value") }
            s = 42
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: cannot set 42
                  at <set-s>(script.kts:3)
                  |     set(value) { error("cannot set ${'$'}value") }
                  at <script body>(script.kts:4)
                  | s = 42
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of accessors of boolean property named with is prefix by the property name`() = runTest {
        // given
        val script = """
            var isOn: Boolean
                get() = error("cannot get")
                set(value) { isOn }
            isOn = true
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: cannot get
                  at <get-isOn>(script.kts:2)
                  |     get() = error("cannot get")
                  at <set-isOn>(script.kts:3)
                  |     set(value) { isOn }
                  at <script body>(script.kts:4)
                  | isOn = true
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of extension property getter by the property name`() = runTest {
        // given
        val script = """
            val String.shout: String get() = error("cannot shout ${'$'}this")
            "hello".shout
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: cannot shout hello
                  at <get-shout>(script.kts:1)
                  | val String.shout: String get() = error("cannot shout ${'$'}this")
                  at <script body>(script.kts:2)
                  | "hello".shout
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of class member property getter by the class and the property name`() = runTest {
        // given
        val script = """
            class Foo {
                val x: Int get() = error("no x")
            }
            Foo().x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: no x
                  at Foo.<get-x>(script.kts:2)
                  |     val x: Int get() = error("no x")
                  at <script body>(script.kts:4)
                  | Foo().x
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of delegated property getter by the property name and of the delegate by its function`() = runTest {
        // given
        val script = """
            class Boom {
                operator fun getValue(thisRef: Any?, property: Any?): Int = error("boom")
            }
            class Foo {
                val x by Boom()
            }
            Foo().x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at Boom.getValue(script.kts:2)
                  |     operator fun getValue(thisRef: Any?, property: Any?): Int = error("boom")
                  at Foo.<get-x>(script.kts:5)
                  |     val x by Boom()
                  at <script body>(script.kts:7)
                  | Foo().x
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of property getter with JVM name by the property name`() = runTest {
        // given
        val script = """
            class Foo {
                val x: Int
                    @JvmName("fetchX") get() = error("no x")
            }
            Foo().x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: no x
                  at Foo.<get-x>(script.kts:3)
                  |         @JvmName("fetchX") get() = error("no x")
                  at <script body>(script.kts:5)
                  | Foo().x
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of function named like property getter by the function name`() = runTest {
        // given
        val script = """
            val g: Int get() = 1
            fun getG(times: Int): Int = error("boom ${'$'}times")
            getG(g)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom 1
                  at getG(script.kts:2)
                  | fun getG(times: Int): Int = error("boom ${'$'}times")
                  at <script body>(script.kts:3)
                  | getG(g)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of member function named like getter of property of another class in the same line by the function name`() = runTest {
        // given
        val script = """
            class P { val x: Int get() = 1 }; class Q { val y: Int get() = 2; fun getX(): Int = error("boom") }
            Q().getX()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at Q.getX(script.kts:1)
                  | class P { val x: Int get() = 1 }; class Q { val y: Int get() = 2; fun getX(): Int = error("boom") }
                  at <script body>(script.kts:2)
                  | Q().getX()
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of function of nested class named like getter of property of nested class of the same name by the function name`() = runTest {
        // given
        val script = """
            class A { class N { val x: Int get() = 1 } }; class B { class N { fun getX(): Int = error("boom") } }
            B.N().getX()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at N.getX(script.kts:1)
                  | class A { class N { val x: Int get() = 1 } }; class B { class N { fun getX(): Int = error("boom") } }
                  at <script body>(script.kts:2)
                  | B.N().getX()
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of function of local class named like getter of property of local class of the same name by the function name`() = runTest {
        // given
        val script = """
            fun a() { class L { val x: Int get() = 1 } }; fun b(): Int { class L { fun getX(): Int = error("boom") }; return L().getX() }
            b()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at L.getX(script.kts:1)
                  | fun a() { class L { val x: Int get() = 1 } }; fun b(): Int { class L { fun getX(): Int = error("boom") }; return L().getX() }
                  at b(script.kts:1)
                  | fun a() { class L { val x: Int get() = 1 } }; fun b(): Int { class L { fun getX(): Int = error("boom") }; return L().getX() }
                  at <script body>(script.kts:2)
                  | b()
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of functions named like property getter called by the getter by the function names`() = runTest {
        // given
        val script = """
            val g: Int get() { class L { fun getG(): Int = error("boom") }; return L().getG() }
            val x: Int get() = C().getX(); class C { fun getX(): Int = g }
            x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: boom
                  at L.getG(script.kts:1)
                  | val g: Int get() { class L { fun getG(): Int = error("boom") }; return L().getG() }
                  at <get-g>(script.kts:1)
                  | val g: Int get() { class L { fun getG(): Int = error("boom") }; return L().getG() }
                  at C.getX(script.kts:2)
                  | val x: Int get() = C().getX(); class C { fun getX(): Int = g }
                  at <get-x>(script.kts:2)
                  | val x: Int get() = C().getX(); class C { fun getX(): Int = g }
                  at <script body>(script.kts:3)
                  | x
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of uninitialized lateinit property getter by the property name`() = runTest {
        // given
        val script = """
            class Foo { lateinit var x: String }
            Foo().x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            // the first frame is the one of the standard library throwing the exception
            assert(message.endsWith("""
                  at Foo.<get-x>(script.kts:1)
                  | class Foo { lateinit var x: String }
                  at <script body>(script.kts:2)
                  | Foo().x
            """.trimIndent().prependIndent("  ")))
        }
    }

    @Test
    fun `should report frames of accessors of companion object, delegated property and local class of getter by the property names`() = runTest {
        // given
        val script = """
            class C {
                companion object { val x: Int get() = O.z }
            }
            object O {
                var z: Int by mutableMapOf<String, Int>()
            }
            val g: Int get() { class M { val y: Int get() = C.x }; return M().y }
            g
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            // the first frame is the one of the standard library throwing the exception
            assert(message.endsWith("""
                  at O.<get-z>(script.kts:5)
                  |     var z: Int by mutableMapOf<String, Int>()
                  at Companion.<get-x>(script.kts:2)
                  |     companion object { val x: Int get() = O.z }
                  at M.<get-y>(script.kts:7)
                  | val g: Int get() { class M { val y: Int get() = C.x }; return M().y }
                  at <get-g>(script.kts:7)
                  | val g: Int get() { class M { val y: Int get() = C.x }; return M().y }
                  at <script body>(script.kts:8)
                  | g
            """.trimIndent().prependIndent("  ")))
        }
    }

    @Test
    fun `should report frame of generic override of property getter in the line of its class without its bridge`() = runTest {
        // given
        val script = """
            interface I<T> { val x: T }
            class C : I<Int> { override val x: Int get() = error("x") }
            (C() as I<Int>).x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.<get-x>(script.kts:2)
                  | class C : I<Int> { override val x: Int get() = error("x") }
                  at <script body>(script.kts:3)
                  | (C() as I<Int>).x
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of recursive generic override without its bridge`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class C : I<Int> {
                override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1)
            }
            (C() as I<Int>).f(2)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.f(script.kts:3)
                  |     override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1)
                  ... (the frame above repeated 2 more times)
                  at <script body>(script.kts:5)
                  | (C() as I<Int>).f(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of generic override recursing directly without dropping them as bridges`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class C : I<Int> {
                override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1)
            }
            C().f(1)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.f(script.kts:3)
                  |     override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1)
                  ... (the frame above repeated 1 more times)
                  at <script body>(script.kts:5)
                  | C().f(1)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of generic override recursing directly from another line of its body`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class C : I<Int> {
                override fun f(n: Int): Int {
                    if (n == 0) error("x")
                    return f(n - 1)
                }
            }
            C().f(1)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.f(script.kts:4)
                  |         if (n == 0) error("x")
                  at C.f(script.kts:5)
                  |         return f(n - 1)
                  at <script body>(script.kts:8)
                  | C().f(1)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of generic override in the line of its class recursing directly`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class C : I<Int> { override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1) }
            C().f(1)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.f(script.kts:2)
                  | class C : I<Int> { override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1) }
                  ... (the frame above repeated 1 more times)
                  at <script body>(script.kts:3)
                  | C().f(1)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of generic override in the line of its class called through its interface by another one without its bridge`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class B : I<Int> { override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1) }
            class A : I<Int> { override fun f(n: Int): Int = (B() as I<Int>).f(1) }
            A().f(0)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at B.f(script.kts:2)
                  | class B : I<Int> { override fun f(n: Int): Int = if (n == 0) error("x") else f(n - 1) }
                  ... (the frame above repeated 1 more times)
                  at A.f(script.kts:3)
                  | class A : I<Int> { override fun f(n: Int): Int = (B() as I<Int>).f(1) }
                  at <script body>(script.kts:4)
                  | A().f(0)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of generic override recursing through its interface without its bridges`() = runTest {
        // given
        val script = """
            interface I<T> { fun f(n: Int): T }
            class C : I<Int> {
                override fun f(n: Int): Int = if (n == 0) error("x") else (this as I<Int>).f(n - 1)
            }
            (C() as I<Int>).f(2)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at C.f(script.kts:3)
                  |     override fun f(n: Int): Int = if (n == 0) error("x") else (this as I<Int>).f(n - 1)
                  ... (the frame above repeated 2 more times)
                  at <script body>(script.kts:5)
                  | (C() as I<Int>).f(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of inherited interface getter and private getter without the compiler generated ones`() = runTest {
        // given
        val script = """
            interface I { val x: Int get() = error("x") }
            class C : I
            class Foo {
                private val y: Int get() = C().x
                companion object { fun f(foo: Foo) = foo.y }
            }
            Foo.f(Foo())
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at I.<get-x>(script.kts:1)
                  | interface I { val x: Int get() = error("x") }
                  at Foo.<get-y>(script.kts:4)
                  |     private val y: Int get() = C().x
                  at Companion.f(script.kts:5)
                  |     companion object { fun f(foo: Foo) = foo.y }
                  at <script body>(script.kts:7)
                  | Foo.f(Foo())
            """.trimIndent()
        }
    }

    @Test
    fun `should report frames of internal members by their names`() = runTest {
        // given
        val script = """
            internal val y: Int get() = Foo().f()
            class Foo {
                internal val x: Int get() = error("x")
                internal fun f(): Int = x
            }
            y
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: x
                  at Foo.<get-x>(script.kts:3)
                  |     internal val x: Int get() = error("x")
                  at Foo.f(script.kts:4)
                  |     internal fun f(): Int = x
                  at <get-y>(script.kts:1)
                  | internal val y: Int get() = Foo().f()
                  at <script body>(script.kts:6)
                  | y
            """.trimIndent()
        }
    }

    @Test
    fun `should report frame of property getter throwing in inlined function, and of getter with JVM name, by the property names`() = runTest {
        // given
        val script = """
            class C {
                @get:JvmName("ex") val x: Int get() = y
                val y: Int get() = listOf<Int>().first { it > 0 }
            }
            C().x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.util.NoSuchElementException: Collection contains no element matching the predicate.
                  at C.<get-y>(script.kts)
                  at C.<get-x>(script.kts:2)
                  |     @get:JvmName("ex") val x: Int get() = y
                  at <script body>(script.kts:5)
                  | C().x
            """.trimIndent()
        }
    }

    @Test
    fun `should use class declared later in the script`() = runTest {
        // given
        val script = """
            val p = Point(1, 2)
            data class Point(val x: Int, val y: Int)
            p.x + p.y
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should use class declared later in the script from function declared before it`() = runTest {
        // given
        val script = """
            fun box() = Box(1)
            class Box(val v: Int)
            box().v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should use classes referring to each other`() = runTest {
        // given
        val script = """
            class A { fun b() = B() }
            class B { fun a() = A() }
            A().b().a()::class.simpleName
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "A")
        }
    }

    @Test
    fun `should hint to declare class before its use when it accesses local declarations of the script body`() = runTest {
        // given
        val script = """
            val base = "10".toInt()
            val scaled = Scaler().scale(2)
            class Scaler { fun scale(x: Int) = x * base }
            scaled
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:14: error: Unresolved reference 'Scaler'.
                  | val scaled = <error>Scaler</error>().scale(2)
                  hint: 'Scaler' is declared at line 3, while the classes of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should report script too deeply nested to be parsed`() = runTest {
        // given
        val depth = 3000
        val script = "run {\n".repeat(depth) + "42\n" + "}\n".repeat(depth)
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script is nested too deeply to be parsed.
            """.trimIndent()
        }
    }

    @Test
    fun `should report internal failure of the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler { throw IllegalStateException("compiler crashed") }
        )

        // when
        val result = executor.execute("42")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: compiler crashed
            """.trimIndent()
        }
    }

    @Test
    fun `should report the root cause of the doubly wrapped internal failure of the compiler`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler {
                throw IllegalStateException("Could not compile", RuntimeException(IllegalArgumentException("root cause")))
            }
        )

        // when
        val result = executor.execute("42")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: Could not compile
                  Caused by: java.lang.IllegalArgumentException: root cause
            """.trimIndent()
        }
    }

    @Test
    fun `should truncate the long message of the internal failure of the compiler`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            // like a dump of the bytecode
            compiler = FakeCompiler { throw IllegalStateException("x".repeat(100_000)) }
        )

        // when
        val result = executor.execute("42")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: ${"x".repeat(500)}... [TRUNCATED]
            """.trimIndent()
        }
    }

    @Test
    fun `should report StackOverflowError of the compiler as internal failure, not as the script nested too deeply`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler { throw StackOverflowError() }
        )

        // when
        val result = executor.execute("42")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.StackOverflowError
            """.trimIndent()
        }
    }

    @Test
    fun `should report the crash of the compiler at the position in the script`() = runTest {
        // given
        val script = """
            class Cfg
            operator fun Cfg.getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Int = 42
            val cfg: Cfg? = Cfg()
            val v: Int by cfg?.let { it }
            v
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // a bug of the compiler (2.4), crashing on a regular script as well
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:15: error: The compiler failed internally: While analysing script.kts:4:15: org.jetbrains.kotlin.utils.exceptions.KotlinIllegalArgumentExceptionWithAttachments: Exception in expression checkers
                  Caused by: org.jetbrains.kotlin.utils.exceptions.KotlinIllegalArgumentExceptionWithAttachments: Expected expression 'FirAnonymousFunctionExpressionImpl' to be resolved
                  | val v: Int by <error></error>cfg?.let { it }
                  hint: it is a failure of the compiler itself - express this code differently.
            """.trimIndent()
        }
    }

    @Test
    fun `should throw InterruptedException of the compiler restoring the interrupt status`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            // compiling on the thread of the test, read before the test loop would fail on its interrupt status
            dispatcher = Dispatchers.Unconfined,
            compiler = FakeCompiler { throw InterruptedException("compiler interrupted") }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        val interrupted = Thread.interrupted()
        assert(failure is InterruptedException && failure.message == "compiler interrupted")
        assert(interrupted)
    }

    @Test
    fun `should throw InterruptedException reported by the compiler restoring the interrupt status`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            dispatcher = Dispatchers.Unconfined,
            compiler = FakeCompiler {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "compiler interrupted",
                        exception = InterruptedException("compiler interrupted")
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        val interrupted = Thread.interrupted()
        assert(failure is InterruptedException && failure.message == "compiler interrupted")
        assert(interrupted)
    }

    @Test
    fun `should report InterruptedException of another thread causing the failure of the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            dispatcher = Dispatchers.Unconfined,
            compiler = FakeCompiler {
                throw IllegalStateException("failed", ExecutionException(InterruptedException("worker thread interrupted")))
            }
        )

        // when
        val result = executor.execute("42")

        // then
        val interrupted = Thread.interrupted()
        assert(!interrupted)
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: failed
                  Caused by: java.lang.InterruptedException: worker thread interrupted
            """.trimIndent()
        }
    }

    @Test
    fun `should report InterruptedException causing the exception reported by the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            dispatcher = Dispatchers.Unconfined,
            compiler = FakeCompiler {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "Could not read file",
                        exception = IllegalStateException("Could not read file", InterruptedException("worker thread interrupted"))
                    )
                )
            }
        )

        // when
        val result = executor.execute("42")

        // then
        val interrupted = Thread.interrupted()
        assert(!interrupted)
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The compiler failed internally: Could not read file
                  Caused by: java.lang.InterruptedException: worker thread interrupted
                  hint: it is a failure of the compiler itself - express the script differently.
            """.trimIndent()
        }
    }

    @Test
    fun `should not report VirtualMachineError causing the exception thrown by the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler { throw RuntimeException(OutOfMemoryError("compiler out of memory")) }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "compiler out of memory")
    }

    @Test
    fun `should report internal failure of the compiler at the position in the script instead of the generated one`() = runTest {
        // given
        val script = """
            val a = 1
            val b = a + 1
            b
        """.trimIndent()
        val executor = DefaultScriptExecutor(
            compiler = object : JvmScriptCompiler() {
                override suspend fun invoke(
                    script: SourceCode,
                    scriptCompilationConfiguration: ScriptCompilationConfiguration
                ): ResultWithDiagnostics<CompiledScript> {
                    val source = script as ScriptSource
                    val line = (1..source.text.lines().size).first { source.toScriptLine(it) == 1 }
                    throw IllegalStateException("While analysing ${source.locationId}:$line:9: boom")
                }
            }
        )

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: While analysing script.kts:2:9: boom
            """.trimIndent()
        }
    }

    @Test
    fun `should report compilation cancelled by the compiler while the caller is active as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler { throw CancellationException("compiler cancelled") }
        )

        // when
        val result = executor.execute("42")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: The compilation of the script was cancelled
                  Caused by: java.util.concurrent.CancellationException: compiler cancelled
            """.trimIndent()
        }
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should report internal failure of the compiler as compilation error to all the callers waiting for the cached compilation`() = runTest {
        // given
        val failing = CompletableDeferred<Unit>()
        val executor = DefaultScriptExecutor(
            // so the waiter is waiting in the cache before the compiler fails
            dispatcher = StandardTestDispatcher(testScheduler),
            compilationCacheSize = 10,
            compiler = FakeCompiler {
                failing.await()
                throw IllegalStateException("compiler crashed")
            }
        )
        val owner = async { executor.execute("42") }
        runCurrent()
        val waiter = async { executor.execute("42") }
        runCurrent()

        // when
        failing.complete(Unit)
        val results = listOf(owner.await(), waiter.await())

        // then
        val (ownerResult, waiterResult) = results
        assert(ownerResult is ScriptResult.Error && ownerResult.phase == ExecutionPhase.COMPILATION)
        assert(waiterResult is ScriptResult.Error && waiterResult.phase == ExecutionPhase.COMPILATION)
        assert(ownerResult.message == "error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: compiler crashed")
        assert(waiterResult.message == ownerResult.message)
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should not report VirtualMachineError of the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler { throw OutOfMemoryError("compiler out of memory") }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "compiler out of memory")
    }

    @Test
    fun `should not report VirtualMachineError reported by the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            // like the compiler reporting the exception it caught as a diagnostic
            compiler = FakeCompiler {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "Java heap space",
                        exception = OutOfMemoryError("Java heap space")
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Java heap space")
    }

    @Test
    fun `should not report VirtualMachineError causing the exception reported by the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "Could not read file",
                        exception = IllegalStateException("Could not read file", OutOfMemoryError("Java heap space"))
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Java heap space")
    }

    @Test
    fun `should not report VirtualMachineError causing the cause of the exception reported by the compiler as compilation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            compiler = FakeCompiler {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "Could not read file",
                        exception = RuntimeException(
                            IllegalStateException("Could not read file", OutOfMemoryError("Java heap space"))
                        )
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Java heap space")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should bound the number of concurrent compilations`() = runTest {
        // given
        val compiled = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        val executor = DefaultScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler),
            maxConcurrentCompilations = 2,
            compiler = FakeCompiler {
                active++
                maxActive = maxOf(maxActive, active)
                try {
                    compiled.await()
                } finally {
                    active--
                }
                ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "not compiled"))
            }
        )
        val executions = listOf("1", "2", "3").map { script ->
            async { executor.execute(script) }
        }

        // when
        runCurrent()
        val activeBeforeCompiled = active
        compiled.complete(Unit)
        val results = executions.map { it.await() }

        // then
        assert(activeBeforeCompiled == 2)
        assert(maxActive == 2)
        assert(executor.compilationCount == 3)
        assert(results.all { it is ScriptResult.Error && it.phase == ExecutionPhase.COMPILATION })
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should not hold the compilation permit while waiting for the cached compilation`() = runTest {
        // given
        val compiled = CompletableDeferred<Unit>()
        var active = 0
        val executor = DefaultScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler),
            compilationCacheSize = 10,
            maxConcurrentCompilations = 2,
            compiler = FakeCompiler {
                active++
                try {
                    compiled.await()
                } finally {
                    active--
                }
                ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "not compiled"))
            }
        )
        val owner = async { executor.execute("1") }
        runCurrent()
        val waiter = async { executor.execute("1") }
        runCurrent()

        // when
        val other = async { executor.execute("2") }
        runCurrent()
        val activeWithWaiter = active
        compiled.complete(Unit)
        listOf(owner, waiter, other).awaitAll()

        // then
        assert(activeWithWaiter == 2)
        assert(executor.compilationCount == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should not compile for the caller cancelled while waiting for a compilation permit`() = runTest {
        // given
        val compiled = CompletableDeferred<Unit>()
        val executor = DefaultScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler),
            maxConcurrentCompilations = 1,
            compiler = FakeCompiler {
                compiled.await()
                ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "not compiled"))
            }
        )
        val compiling = async { executor.execute("1") }
        runCurrent()
        val queued = launch { executor.execute("2") }
        runCurrent()

        // when
        queued.cancelAndJoin()
        compiled.complete(Unit)
        val compilingResult = compiling.await()
        val laterResult = executor.execute("3")

        // then
        assert(queued.isCancelled)
        assert(compilingResult is ScriptResult.Error && compilingResult.phase == ExecutionPhase.COMPILATION)
        assert(laterResult is ScriptResult.Error && laterResult.phase == ExecutionPhase.COMPILATION)
        // the cancelled caller never invoked the compiler
        assert(executor.compilationCount == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should compile again for the waiting caller when the caching owner is cancelled while waiting for a permit`() = runTest {
        // given
        val compiled = CompletableDeferred<Unit>()
        val executor = DefaultScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler),
            compilationCacheSize = 10,
            maxConcurrentCompilations = 1,
            compiler = FakeCompiler {
                compiled.await()
                ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "not compiled"))
            }
        )
        val compiling = async { executor.execute("1") }
        runCurrent()
        val owner = launch { executor.execute("2") }
        runCurrent()
        val waiter = async { executor.execute("2") }
        runCurrent()

        // when
        owner.cancelAndJoin()
        compiled.complete(Unit)
        val compilingResult = compiling.await()
        val waiterResult = waiter.await()

        // then
        assert(owner.isCancelled)
        assert(compilingResult is ScriptResult.Error && compilingResult.phase == ExecutionPhase.COMPILATION)
        assert(waiterResult is ScriptResult.Error && waiterResult.phase == ExecutionPhase.COMPILATION)
        assert(executor.compilationCount == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should not invoke the compiler again for the caller cancelled during the compilation`() = runTest {
        // given
        // probing the type of the property, before confirming the references
        val script = """
            fun printer(): (Any?) -> Unit = { }
            val print = printer()
            sealed class Shape { fun show() = print("shape") }
            fun show() = x
            val x = 2
            show()
        """.trimIndent()
        lateinit var caller: Job
        val executor = DefaultScriptExecutor(
            dispatcher = StandardTestDispatcher(testScheduler),
            compiler = FakeCompiler {
                // like while the compiler is running, which does not check the cancellation
                caller.cancel()
                ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, "not compiled"))
            }
        )

        // when
        caller = launch { executor.execute(script, listOf(dependency("x", 1))) }
        caller.join()

        // then
        assert(caller.isCancelled)
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should report internal failure of the confirming compilation at the position in the script, without caching it`() = runTest {
        // given
        val script = """
            fun show() = x
            val x = 2
            cancel()
            fun cancel() {}
            show()
        """.trimIndent()
        val dependencies = listOf(dependency("x", 1))
        val executor = DefaultScriptExecutor(
            compilationCacheSize = 10,
            compiler = object : JvmScriptCompiler() {
                override suspend fun invoke(
                    script: SourceCode,
                    scriptCompilationConfiguration: ScriptCompilationConfiguration
                ): ResultWithDiagnostics<CompiledScript> = if (LATER_DECLARED_DEPENDENCY in script.text) {
                    // like the compiler crashing on the confirming sources only, at `x` of `show`
                    val line = script.text.lines().indexOfFirst { it.startsWith("fun show() = x") } + 1
                    ResultWithDiagnostics.Failure(
                        ScriptDiagnostic(
                            code = ScriptDiagnostic.unspecifiedError,
                            message = "Exception in expression checkers",
                            location = SourceCode.Location(SourceCode.Position(line, 14), SourceCode.Position(line, 15)),
                            exception = IllegalStateException("Exception in expression checkers")
                        )
                    )
                } else {
                    super.invoke(script, scriptCompilationConfiguration)
                }
            }
        )

        // when
        val results = List(2) { executor.execute(script, dependencies) }

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    script.kts:1:14: error: The compiler failed internally: Exception in expression checkers
                      | fun show() = <error>x</error>
                      hint: it is a failure of the compiler itself - express this code differently.
                """.trimIndent()
            }
        }
        // the compilation and the first confirming one, without the confirming copies of the scope functions, for each execution
        assert(executor.compilationCount == 4)
    }

    @Test
    fun `should not report VirtualMachineError causing the exception thrown by the script as evaluation error`() = runTest {
        // given
        val script = """
            throw IllegalStateException("failed", OutOfMemoryError("script out of memory"))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val failure = runCatching { executor.execute(script) }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "script out of memory")
    }

    @Test
    fun `should not report VirtualMachineError reported by the evaluator as evaluation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            // like the evaluator reporting the exception it caught, e.g. while loading the script class
            evaluator = FakeEvaluator {
                ResultWithDiagnostics.Failure(
                    ScriptDiagnostic(
                        code = ScriptDiagnostic.unspecifiedError,
                        message = "Java heap space",
                        exception = OutOfMemoryError("Java heap space")
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Java heap space")
    }

    @Test
    fun `should not report VirtualMachineError causing the error of the script value as evaluation error`() = runTest {
        // given
        val executor = DefaultScriptExecutor(
            evaluator = FakeEvaluator {
                ResultWithDiagnostics.Success(
                    EvaluationResult(
                        ResultValue.Error(ExceptionInInitializerError(OutOfMemoryError("Java heap space"))),
                        ScriptEvaluationConfiguration()
                    )
                )
            }
        )

        // when
        val failure = runCatching { executor.execute("42") }.exceptionOrNull()

        // then
        assert(failure is OutOfMemoryError && failure.message == "Java heap space")
    }

    @Test
    fun `should create executors with the factory functions visible to Java`() = runTest {
        // given
        val executors = listOf(
            JavaClient.executor(),
            JavaClient.executor(Dispatchers.IO),
            JavaClient.executor(Dispatchers.IO, 10),
            JavaClient.executor(Dispatchers.IO, 10, 2)
        )

        // when
        val results = executors.map { it.execute("1 + 1") }

        // then
        assert(results.map { it.valueOrMessage } == listOf(2, 2, 2, 2))
    }

    @Test
    fun `should create executor with the default arguments of the factory function of 0_1_0`() = runTest {
        // given
        // called by the Kotlin clients compiled against 0.1.0 omitting the arguments, like `ScriptExecutor()`
        val defaults = Class.forName("com.xemantic.kotlin.script.ScriptExecutorKt").getMethod(
            $$"ScriptExecutor$default",
            CoroutineDispatcher::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Any::class.java
        )
        val executor = defaults.invoke(null, null, 0, 0b11, null) as ScriptExecutor

        // when
        val result = executor.execute("1 + 1")

        // then
        assert(result.valueOrMessage == 2)
    }

    @Test
    fun `should reject non-positive number of concurrent compilations`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor(maxConcurrentCompilations = 0)
        }

        // then
        assert(exception.message == "maxConcurrentCompilations must be positive, but was: 0")
    }

    @Test
    fun `should run script body with the class loader of the script as the context class loader`() = runTest {
        // given
        val script = """
            class Foo
            kotlinx.coroutines.delay(1)
            Thread.currentThread().contextClassLoader.loadClass(Foo::class.java.name) == Foo::class.java
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == true)
        }
    }

    @Test
    fun `should evaluate script with the class loader of the executor whatever the context class loader of the dispatcher thread`() = runTest {
        // given
        val isolated = object : ClassLoader(null) {}
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply { contextClassLoader = isolated }
        }.asCoroutineDispatcher()
        val executor = ScriptExecutor(dispatcher = dispatcher)

        // when
        val result = try {
            executor.execute("widget.name", listOf(dependency("widget", Widget("foo"))))
        } finally {
            dispatcher.close()
        }

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "foo")
        }
    }

    @Test
    fun `should report error at enum class accessing dependency`() = runTest {
        // given
        val script = """
            enum class Mood { HAPPY; fun greet() = greeting }
            Mood.HAPPY.greet()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("greeting", "Hello")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:12: error: Enum class 'Mood' captures the script class instance. Try to use class or anonymous object instead.
                  | enum class <error>Mood</error> { HAPPY; fun greet() = greeting }
                  hint: 'Mood' is compiled outside of the script body, where an enum class cannot access the dependencies, nor the functions of the script - pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at nested class accessing dependency`() = runTest {
        // given
        val script = """
            class Outer {
                class Nested { fun greet() = greeting }
            }
            Outer.Nested().greet()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("greeting", "Hello")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:11: error: Nested class 'Nested' captures the script class instance. Try to use the explicit 'inner' modifier for both the nested 'Nested' and outer 'Outer'.
                  |     class <error>Nested</error> { fun greet() = greeting }
                  hint: 'Nested' is compiled outside of the script body, where a nested class cannot access the dependencies, nor the functions of the script - pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at callable reference of moved class to function of script crashing compiler`() = runTest {
        // given
        val script = """
            fun log(m: String) = m
            sealed interface S
            class T : S {
                fun f() = listOf("a").map(::log)
                fun g() = listOf("b").map(::log)
            }
            class U : S { fun h() = listOf("c").map(::log) }
            T().f() + U().h()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:31: error: The compiler crashed generating the callable reference '::log'.
                  |     fun f() = listOf("a").map(<error>::log</error>)
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'log' of the script crashes the compiler - replace it with a lambda using 'log'.

                script.kts:5:31: error: The compiler crashed generating the callable reference '::log'.
                  |     fun g() = listOf("b").map(<error>::log</error>)
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'log' of the script crashes the compiler - replace it with a lambda using 'log'.

                script.kts:7:41: error: The compiler crashed generating the callable reference '::log'.
                  | class U : S { fun h() = listOf("c").map(<error>::log</error>) }
                  hint: 'U' is compiled outside of the script body, where a callable reference to 'log' of the script crashes the compiler - replace it with a lambda using 'log'.
            """.trimIndent()
        }
    }

    @Test
    fun `should not report callable reference of moved class to its own member as crashing compiler`() = runTest {
        // given
        val script = """
            fun log(m: String) = m
            fun greet(s: String) = s
            sealed interface S
            class T(val log: String) : S {
                fun f() = ::log
                fun g() = listOf("a").map(::greet)
            }
            T("x").f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:6:31: error: The compiler crashed generating the callable reference '::greet'.
                  |     fun g() = listOf("a").map(<error>::greet</error>)
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'greet' of the script crashes the compiler - replace it with a lambda using 'greet'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at callable reference of moved class to extension function of script crashing compiler`() = runTest {
        // given
        val script = """
            fun String.up() = uppercase()
            sealed interface S
            class T : S { fun f() = listOf("a").map(String::up) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:41: error: The compiler crashed generating the callable reference 'String::up'.
                  | class T : S { fun f() = listOf("a").map(<error>String::up</error>) }
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'up' of the script crashes the compiler - replace it with a lambda using 'up'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at callable reference of moved class crashing compiler in script with package`() = runTest {
        // given
        val script = """
            package foo
            fun log(m: String) = m
            sealed interface S
            class T : S { fun f() = listOf("a").map(::log) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:41: error: The compiler crashed generating the callable reference '::log'.
                  | class T : S { fun f() = listOf("a").map(<error>::log</error>) }
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'log' of the script crashes the compiler - replace it with a lambda using 'log'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at bound callable reference of moved class to extension function of script crashing compiler`() = runTest {
        // given
        val script = """
            fun String.up() = uppercase()
            sealed interface S
            class T : S { fun f() = listOf("a").map { "a"::up } }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:43: error: The compiler crashed generating the callable reference '"a"::up'.
                  | class T : S { fun f() = listOf("a").map { <error>"a"::up</error> } }
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'up' of the script crashes the compiler - replace it with a lambda using 'up'.
            """.trimIndent()
        }
    }

    /**
     * Pins the wording of the crash of the compiler backend, recognized by the reporter, which might change with the compiler version,
     * as otherwise the crash is reported without a location.
     */
    @Test
    fun `should report compiler crash not explained by callable reference at nested class without intermediate representation`() = runTest {
        // given
        val script = """
            fun log(m: String) = m
            sealed interface S
            class T : S {
                class T { fun f() = listOf("a").map(::log) }
            }
            T.T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:11: error: Exception while generating code for public final fun f (): kotlin.collections.List<kotlin.String> declared in <root>._.T.T
                  Caused by: java.lang.AssertionError: Non-mapped local declaration: VALUE_PARAMETER INSTANCE_RECEIVER kind:DispatchReceiver name:<this> type:<root>._
                  |     class <error>T</error> { fun f() = listOf("a").map(::log) }
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at callable reference split across lines crashing compiler`() = runTest {
        // given
        val script = """
            fun String.up() = uppercase()
            sealed interface S
            class T : S {
                fun f() = listOf("a").map(String
                    ::up)
            }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:31: error: The compiler crashed generating the callable reference 'String::up'.
                  |     fun f() = listOf("a").map(<error>String
                  |         ::up</error>)
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'up' of the script crashes the compiler - replace it with a lambda using 'up'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at callable reference of moved class to dependency crashing compiler`() = runTest {
        // given
        val script = """
            sealed interface S
            class T : S { fun f() = ::greeting }
            T().f().get()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("greeting", "Hello")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:25: error: The compiler crashed generating the callable reference '::greeting'.
                  | class T : S { fun f() = <error>::greeting</error> }
                  hint: 'T' is compiled outside of the script body, where a callable reference to 'greeting' of the script crashes the compiler - replace it with a lambda using 'greeting'.
            """.trimIndent()
        }
    }

    @Test
    fun `should initialize property with accessor modifier in the order of the script`() = runTest {
        // given
        val script = """
            log += "a"
            var count = run { log += "b"; 0 }
                private set
            count++
            "${'$'}log ${'$'}count"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("log", mutableListOf<String>())))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "[a, b] 1")
        }
    }

    @Test
    fun `should execute script with property accessors on the lines after it without indentation`() = runTest {
        // given
        val scripts = listOf(
            "val size: Int\nget() = 3\nvar count = 0\nprivate set\ncount += size\ncount",
            "val size: Int\nget() { return 3 }\nsize",
            "var size = 2\nset\nget() = field + 1\nsize"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == List(scripts.size) { 3 })
    }

    @Test
    fun `should report only syntax error of accessor following valid accessor without indentation`() = runTest {
        // given
        val script = "var v = 0\nget() = field + 1\nset(3)\nv"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:5: error: Syntax error: Expecting parameter name.
                  | set(<error>3</error>)

                script.kts:3:7: error: Syntax error: Expecting function body.
                  | set(3)<error></error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report only syntax error in body of getter without indentation`() = runTest {
        // given
        val script = "val size: Int\nget() {\n  return listOf(1, 2).sum(\n}\nsize"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:1: error: Syntax error: Expecting an argument.
                  | <error></error>}
            """.trimIndent()
        }
    }

    @Test
    fun `should execute bare setter following getter without indentation as part of property`() = runTest {
        // given
        val script = "var v = 0\nget() = field + 1\nset\nv"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should execute call with trailing lambda spelled like setter as setter of preceding property`() = runTest {
        // given
        val script = "fun set(i: Int, b: () -> Int) = b()\nval v = 2\nvar x = 1\nset(v) { 6 }"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == Unit)
        }
    }

    @Test
    fun `should execute statements following function with expression body and semicolon on the same line`() = runTest {
        // given
        val scripts = listOf(
            "fun a() = 1; val z = 5\na() + z",
            "val a = 6; fun b() = a; b()",
            "fun a() = 1; fun b() = 5; a() + b()",
            "val f = fun() = 1; val z = 5\nf() + z",
            "fun a() = 1 /* c */; /* d */ val z = 5\na() + z",
            "fun a() = b(); val z = 4\nfun b() = 2\na() + z",
            "inline fun a() = 1; val z = 5\na() + z",
            "enum class E { A }; fun a() = 1; E.A.ordinal + a() + 5",
            "fun a() = 1;\na() + 5",
            "class C; val z = 6\nz",
            "fun a() = 1; /* multi\nline */ val z = 5\na() + z"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == List(scripts.size) { 6 })
    }

    @Test
    fun `should report error at its column of line split after function with expression body and semicolon`() = runTest {
        // given
        val script = "fun a() = 1; val z = q; fun b() = r; b()"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:22: error: Unresolved reference 'q'.
                  | fun a() = 1; val z = <error>q</error>; fun b() = r; b()

                script.kts:1:35: error: Unresolved reference 'r'.
                  | fun a() = 1; val z = q; fun b() = <error>r</error>; b()
            """.trimIndent()
        }
    }

    @Test
    fun `should report exception thrown on line split after function with expression body and semicolon`() = runTest {
        // given
        val script = """
            fun a(): Int = TODO(); val z = 1
            z + a()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs $$"""
                kotlin.NotImplementedError: An operation is not implemented.
                  at a(script.kts:1)
                  | fun a(): Int = TODO(); val z = 1
                  at <script body>(script.kts:2)
                  | z + a()
            """.trimIndent()
        }
    }

    @Test
    fun `should execute calls of get and set on the lines after properties without indentation`() = runTest {
        // given
        val scripts = listOf(
            "fun get(i: Int) = i * 2\nval x = 1\nget(3)",
            "fun set(v: Int) = v + 1\nvar y = 1\nset(5)",
            "val get = { 6 }\nval x = 1\nget()",
            "fun get() = 5\nval x = 1\nget() + 1",
            "val get = 6\nval x = 1\n// note\nget",
            "fun get(p: String, b: () -> Int) = b()\nval x = 1\nget(\"/\") { 6 }",
            "fun get(b: () -> Int) = b()\nval x = 1\nget { 6 }",
            "fun set(b: () -> Int) = b()\nvar y = 1\nset { 6 }",
            "fun get(i: Int) = i\nval x = 1\n@Suppress(\"UNUSED\") get(6)"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == List(scripts.size) { 6 })
    }

    @Test
    fun `should report unmatched closing brace following unterminated string`() = runTest {
        // given
        val script = """
            val s = "abc
            println(s) }
            s
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:13: error: Syntax error: Expecting '"'.
                  | val s = "abc<error></error>

                script.kts:2:12: error: Syntax error: Unexpected '}' without matching '{'.
                  | println(s) <error>}</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should launch coroutine without importing launch`() = runTest {
        // given
        val script = """
            val log = mutableListOf<Int>()
            launch { log += 1 }.join()
            log
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == listOf(1))
        }
    }

    @Test
    fun `should cap JVM target of the script at the highest one supported by the compiler`() {
        // when
        val targets = listOf("17", "21", "999").map { scriptJvmTarget(it) }

        // then
        assert(targets == listOf("17", "21", JvmTarget.supportedValues().last().description))
    }

    @Test
    fun `should return Unit when script ends with declaration moved out of the script body while Unit is shadowed`() = runTest {
        // given
        val scripts = listOf(
            "val Unit = 7\nenum class Color { RED }" to emptyList(),
            "42\nenum class Color { RED }" to listOf(dependency("Unit", 42))
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Value>()
                have(value === Unit)
            }
        }
    }

    @Test
    fun `should hint to declare property before its use`() = runTest {
        // given
        val script = """
            println(x)
            val x = 1
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:9: error: Unresolved reference 'x'.
                  | println(<error>x</error>)
                  hint: 'x' is declared at line 2, while the properties of the script body are visible only after their declaration - move it before line 1.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body referring to dependency shadowed by local property`() = runTest {
        // given
        val script = """
            val limit = 5
            sealed interface Check
            class Below : Check { fun ok(v: Int) = v < limit }
            Below().ok(7)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("limit", 10)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:44: error: Unresolved reference 'limit'.
                  | class Below : Check { fun ok(v: Int) = v < <error>limit</error> }
                  hint: 'Below' is compiled outside of the script body, where 'limit' (line 1) is not visible - pass it as a parameter, or declare it within 'Below'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at property accessor referring to dependency shadowed by local property`() = runTest {
        // given
        val script = $$"""
            val prefix = "local"
            val Int.greeting get() = "$prefix!"
            1.greeting
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("prefix", "dep")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs $$"""
                script.kts:2:28: error: Unresolved reference 'prefix'.
                  | val Int.greeting get() = "$<error>prefix</error>!"
                  hint: 'greeting' is compiled outside of the script body, where 'prefix' (line 1) is not visible - pass it as a parameter, or declare it within 'greeting'.
            """.trimIndent()
        }
    }

    @Test
    fun `should execute script with property accessor reading local property shadowing dependency`() = runTest {
        // given
        val script = $$"""
            val prefix = "local"
            val greeting get() = "$prefix!"
            greeting
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("prefix", "dep")))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "local!")
        }
    }

    @Test
    fun `should resolve members of implicit receivers before dependencies of the same names`() = runTest {
        // given
        val scripts = listOf(
            "listOf(1, 2, 3).run { size }",
            "class Numbers : ArrayList<Int>() { fun count() = size }\nNumbers().count()",
            "buildString { append(\"abc\"); append(length) }"
        )
        val dependencies = listOf(dependency("size", 42), dependency("length", 42))
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(3, 0, "abc3"))
    }

    @Test
    fun `should report error at declaration moved out of the script body referring to dependency shadowed by local function`() = runTest {
        // given
        val script = """
            val prefix = "local:".trim()
            fun log(message: String) = prefix + message
            sealed interface Check
            class Below : Check { fun ok() = log("hi") }
            Below().ok()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(String) -> String>("log") { "dep:$it" }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:34: error: 'log' does not refer to the function of the script body declared at line 2, which is not visible in 'Below'.
                  | class Below : Check { fun ok() = <error>log</error>("hi") }
                  hint: 'Below' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'Below'.
            """.trimIndent()
        }
    }

    @Test
    fun `should not resolve the classpath when creating the executor, but when compiling on the dispatcher thread`() = runTest {
        // given
        val thread = Thread.currentThread()
        val contextClassLoader = thread.contextClassLoader
        val recording = ClasspathRecordingClassLoader(contextClassLoader)
        val executorService = Executors.newSingleThreadExecutor()
        val dispatcherThread = executorService.submit<Thread> { Thread.currentThread() }.get()
        val dispatcher = executorService.asCoroutineDispatcher()
        thread.contextClassLoader = recording
        val executor = try {
            ScriptExecutor(dispatcher = dispatcher)
        } finally {
            thread.contextClassLoader = contextClassLoader
        }
        val threadsAfterCreation = recording.threads.toList()

        // when
        val result = try {
            executor.execute("1 + 1")
        } finally {
            dispatcher.close()
        }

        // then
        assert(threadsAfterCreation.isEmpty())
        assert(recording.threads.isNotEmpty() && recording.threads.all { it == dispatcherThread })
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should report failure of resolving the classpath as compilation error, and resolve it again on the next execution`() = runTest {
        // given
        val thread = Thread.currentThread()
        val contextClassLoader = thread.contextClassLoader
        thread.contextClassLoader = ClasspathRecordingClassLoader(contextClassLoader, failures = 1)
        val executor = try {
            ScriptExecutor()
        } finally {
            thread.contextClassLoader = contextClassLoader
        }

        // when
        val failed = executor.execute("1 + 1")
        val retried = executor.execute("1 + 1")

        // then
        failed should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: classpath unavailable
            """.trimIndent()
        }
        retried should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should not block the dispatcher thread while waiting for the classpath resolved by another caller`() = runTest {
        // given
        val resolving = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executorService = Executors.newFixedThreadPool(2)
        val dispatcher = executorService.asCoroutineDispatcher()
        val executor = withContextClassLoader(
            ClasspathRecordingClassLoader(Thread.currentThread().contextClassLoader, resolving = resolving, released = released)
        ) {
            ScriptExecutor(dispatcher = dispatcher)
        }

        // when
        val results = try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { executor.execute("1 + 1") }
            resolving.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { executor.execute("2 + 2") }
            // on the other thread, unless the second caller blocks it, failing the test on its timeout
            executorService.onOtherThread { released.countDown() }
            listOf(first.await(), second.await())
        } finally {
            released.countDown()
            dispatcher.close()
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(2, 4))
    }

    @Test
    fun `should not compile for the caller cancelled while waiting for the classpath resolved by another caller`() = runTest {
        // given
        val resolving = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executorService = Executors.newFixedThreadPool(2)
        val dispatcher = executorService.asCoroutineDispatcher()
        val executor = withContextClassLoader(
            ClasspathRecordingClassLoader(Thread.currentThread().contextClassLoader, resolving = resolving, released = released)
        ) {
            DefaultScriptExecutor(dispatcher = dispatcher)
        }

        // when
        val (result, cancelled) = try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { executor.execute("1 + 1") }
            resolving.await()
            val second = launch(start = CoroutineStart.UNDISPATCHED) { executor.execute("2 + 2") }
            executorService.onOtherThread {
                second.cancel()
                released.countDown()
            }
            second.join()
            first.await() to second.isCancelled
        } finally {
            released.countDown()
            dispatcher.close()
        }

        // then
        assert(result.valueOrMessage == 2)
        assert(cancelled)
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should report failure of resolving the classpath to all the callers waiting for it`() = runTest {
        // given
        val resolving = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executorService = Executors.newFixedThreadPool(2)
        val dispatcher = executorService.asCoroutineDispatcher()
        val recording = ClasspathRecordingClassLoader(
            Thread.currentThread().contextClassLoader, failures = 1, resolving = resolving, released = released
        )
        val executor = withContextClassLoader(recording) {
            DefaultScriptExecutor(dispatcher = dispatcher)
        }

        // when
        val results = try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { executor.execute("1 + 1") }
            resolving.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { executor.execute("2 + 2") }
            executorService.onOtherThread { released.countDown() }
            listOf(first.await(), second.await())
        } finally {
            released.countDown()
            dispatcher.close()
        }

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    error: The script could not be compiled because of an internal failure: java.lang.IllegalStateException: classpath unavailable
                """.trimIndent()
            }
        }
        // resolved once, failing on the first resource
        assert(recording.threads.size == 1)
        assert(executor.compilationCount == 0)
    }

    @Test
    fun `should resolve the classpath again for the waiting caller when the resolution fails because of a fatal error`() = runTest {
        // given
        val resolving = CountDownLatch(1)
        val released = CountDownLatch(1)
        val executorService = Executors.newFixedThreadPool(2)
        val dispatcher = executorService.asCoroutineDispatcher()
        val executor = withContextClassLoader(
            ClasspathRecordingClassLoader(
                Thread.currentThread().contextClassLoader,
                failures = 1,
                resolving = resolving,
                released = released,
                failure = { IllegalStateException("classpath unavailable", OutOfMemoryError("classpath out of memory")) }
            )
        ) {
            ScriptExecutor(dispatcher = dispatcher)
        }

        // when
        val (firstFailure, second) = try {
            // not failing the test process, if the error is shared
            val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { executor.execute("1 + 1") } }
            resolving.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { executor.execute("2 + 2") } }
            executorService.onOtherThread { released.countDown() }
            first.await().exceptionOrNull() to second.await().getOrNull()
        } finally {
            released.countDown()
            dispatcher.close()
        }

        // then
        assert(firstFailure is OutOfMemoryError && firstFailure.message == "classpath out of memory")
        assert(second?.valueOrMessage == 4)
    }

    @Test
    fun `should create executor on thread without context class loader`() = runTest {
        // given
        val thread = Thread.currentThread()
        val contextClassLoader = thread.contextClassLoader
        thread.contextClassLoader = null
        val executor = try {
            ScriptExecutor()
        } finally {
            thread.contextClassLoader = contextClassLoader
        }

        // when
        val result = executor.execute("1 + 1")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should smart cast dependencies`() = runTest {
        // given
        val script = """
            val length = if (text != null) text.length else -1
            if (data is String) length + data.length else length
        """.trimIndent()
        val dependencies = listOf(dependency<String?>("text", "abc"), dependency<Any>("data", "ab"))
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, dependencies)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 5)
        }
    }

    @Test
    fun `should resolve members of supertypes and receivers in declarations moved out of the script body before dependencies shadowed by local declarations`() = runTest {
        // given
        val scripts = listOf(
            "val name = \"local\"\nenum class Color { RED; fun label() = name.lowercase() }\nColor.RED.label()",
            "val message = \"local\"\nsealed class Failure(m: String) : Exception(m)\nclass Timeout : Failure(\"timeout\") { fun describe() = \"failed: \" + message }\nTimeout().describe()",
            "val length = 1\nsealed interface S\nclass T : S { fun f() = buildString { append(\"ab\"); append(length) } }\nT().f()",
            "val cap = 3\nfun limit(x: Int) = minOf(x, cap)\nsealed interface S\nclass T : S { fun f() = limit + 1 }\nT().f() + limit(5)"
        )
        val dependencies = listOf(
            dependency("name", "dep"),
            dependency("message", "dep"),
            dependency("length", 42),
            dependency("limit", 10)
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("red", "failed: timeout", "ab2", 14))
    }

    @Test
    fun `should report error at declaration moved out of the script body having the name of a dependency`() = runTest {
        // given
        val script = """
            val total get() = 5
            total
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("total", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:5: error: Conflicting declaration of 'total', which is also the name of a dependency.
                  | val <error>total</error> get() = 5
                  hint: 'total' is compiled outside of the script body, together with the dependencies - rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should use dependency in the script body before the local property shadowing it without warnings`() = runTest {
        // given
        val script = """
            val name = name.uppercase()
            enum class Color { RED; fun label() = name.lowercase() }
            name + Color.RED.label()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("name", "dep")))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "DEPred")
            have(warnings == null)
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling dependency shadowed by local function named like local property`() = runTest {
        // given
        val script = """
            val limit = 1
            fun limit() = limit + 1
            sealed interface S
            class T : S { fun f() = limit() }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<() -> Int>("limit") { 3 }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:25: error: Unresolved reference 'limit'.
                  | class T : S { fun f() = <error>limit</error>() }
                  hint: 'T' is compiled outside of the script body, where 'limit' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should use extension property moved out of the script body having the name of a dependency`() = runTest {
        // given
        val script = """
            val String.size get() = length
            "ab".size + size
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("size", 42)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 44)
        }
    }

    @Test
    fun `should use function moved out of the script body named like the getter of a dependency`() = runTest {
        // given
        val script = """
            val r = getFoo()
            fun getFoo() = 2
            r + foo
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("foo", 40)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
            have(warnings == null)
        }
    }

    @Test
    fun `should use function moved out of the script body named like the getter of a dependency starting with is`() = runTest {
        // given
        val script = """
            val r = isReady()
            fun isReady() = !isReady
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("isReady", true)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == false)
            have(warnings == null)
        }
    }

    @Test
    fun `should use function moved out of the script body named like the hidden name of a dependency`() = runTest {
        // given
        val script = """
            val r = _dependency_foo()
            fun _dependency_foo() = 2
            r + foo
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("foo", 40)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
            have(warnings == null)
        }
    }

    @Test
    fun `should use function moved out of the script body named like the getter of a shadowed dependency`() = runTest {
        // given
        val script = """
            val r = getFoo()
            fun getFoo() = 2
            val foo = 1
            r + foo
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("foo", 40)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
            have(warnings == null)
        }
    }

    @Test
    fun `should report dependency used before the local property shadowing it next to function moved out of the script body named like its getter`() = runTest {
        // given
        val script = """
            val r = getFoo() + foo
            fun getFoo() = 2
            val foo = 1
            r + foo
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("foo", 40)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:20: error: 'foo' refers to the dependency, as the property 'foo' of the script body is declared later, at line 3.
                  | val r = getFoo() + <error>foo</error>
                  hint: declare the property before line 1, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should use function moved out of the script body named like the getter of a dependency of value class type`() = runTest {
        // given
        val script = """
            val r = getTimeout()
            fun getTimeout() = 2L
            r + timeout.inWholeHours
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("timeout", 5.hours)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 7L)
            have(warnings == null)
        }
    }

    @Test
    fun `should recognize declarations of the script named with backticks`() = runTest {
        // given
        val scripts = listOf(
            "val `limit` = 5\nsealed interface S\nclass T : S { fun f() = limit }\nT().f()",
            "val total = `compute total`() + 1\nfun `compute total`() = 42\ntotal"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency("limit", 10))) }

        // then
        results[0] should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:3:25: error: Unresolved reference 'limit'.
                  | class T : S { fun f() = <error>limit</error> }
                  hint: 'T' is compiled outside of the script body, where 'limit' (line 1) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
        results[1] should {
            be<ScriptResult.Value>()
            have(value == 43)
        }
    }

    @Test
    fun `should report unresolved reference indexed or iterated before its declaration`() = runTest {
        // given
        val scripts = listOf(
            """
            val k = "a".length
            val x = arr[0]
            val arr = listOf(k)
            x
            """,
            """
            val k = "a".length
            for (x in xs) println(x)
            val xs = listOf(k)
            """,
            """
            val limit = listOf("a".length)
            sealed interface Check
            class Below : Check { fun ok() = limit[0] }
            Below().ok()
            """,
            """
            val limit = listOf("a".length)
            sealed interface Check
            class Below : Check { fun ok() { for (x in limit) println(x) } }
            Below().ok()
            """
        ).map { it.trimIndent() }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        // followed by the failures of the operators of the unresolved reference, depending on the compiler version
        results[0] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:2:9: error: Unresolved reference 'arr'.
                  | val x = <error>arr</error>[0]
                  hint: 'arr' is declared at line 3, while the properties of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent() in message)
        }
        results[1] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:2:11: error: Unresolved reference 'xs'.
                  | for (x in <error>xs</error>) println(x)
                  hint: 'xs' is declared at line 3, while the properties of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent() in message)
        }
        results[2] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:3:34: error: Unresolved reference 'limit'.
                  | class Below : Check { fun ok() = <error>limit</error>[0] }
                  hint: 'Below' is compiled outside of the script body, where 'limit' (line 1) is not visible - pass it as a parameter, or declare it within 'Below'.
            """.trimIndent() in message)
        }
        results[3] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:3:44: error: Unresolved reference 'limit'.
                  | class Below : Check { fun ok() { for (x in <error>limit</error>) println(x) } }
                  hint: 'Below' is compiled outside of the script body, where 'limit' (line 1) is not visible - pass it as a parameter, or declare it within 'Below'.
            """.trimIndent() in message)
        }
    }

    @Test
    fun `should report unresolved reference indexed or iterated before its declaration, named like its operator`() = runTest {
        // given
        val scripts = listOf(
            """
            val k = "a".length
            val x = get[0]
            val get = listOf(k)
            x
            """,
            """
            val k = "a".length
            for (x in next) println(x)
            val next = listOf(k)
            """
        ).map { it.trimIndent() }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        results[0] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:2:9: error: Unresolved reference 'get'.
                  | val x = <error>get</error>[0]
                  hint: 'get' is declared at line 3, while the properties of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent() in message)
        }
        results[1] should {
            be<ScriptResult.Error>()
            have("""
                script.kts:2:11: error: Unresolved reference 'next'.
                  | for (x in <error>next</error>) println(x)
                  hint: 'next' is declared at line 3, while the properties of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent() in message)
        }
    }

    @Test
    fun `should report unresolved infix or qualified call without hint at later function which is not an extension`() = runTest {
        // given
        val scripts = listOf(
            """
            val k = "a".length
            val x = 1 bar 2
            fun bar(o: Int) = k
            x
            """,
            """
            val k = "a".length
            val x = 1.bar(2)
            fun bar(o: Int) = k
            x
            """
        ).map { it.trimIndent() }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        results[0] should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'bar'.
                  | val x = 1 <error>bar</error> 2
            """.trimIndent()
        }
        results[1] should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'bar'.
                  | val x = 1.<error>bar</error>(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should report unresolved infix call before its declaration as extension function`() = runTest {
        // given
        val script = """
            val k = "a".length
            val x = 1 bar 2
            infix fun Int.bar(o: Int) = k
            x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:2:11: error: Unresolved reference 'bar'.
                  | val x = 1 <error>bar</error> 2
                  hint: 'bar' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should report unresolved qualified type without hint at later local class of its name`() = runTest {
        // given
        val script = """
            val k = "a".length
            class Config
            val m: Config.Mode? = null
            class Mode { val v = k }
            m
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:3:15: error: Unresolved reference 'Mode'.
                  | val m: Config.<error>Mode</error>? = null
            """.trimIndent()
        }
    }

    @Test
    fun `should report unresolved type declared later as local class named like preceding local function`() = runTest {
        // given
        val scripts = listOf(
            """
            val k = "a".length
            fun Base(x: Int) = k
            val b: Base? = null
            open class Base { val v = k }
            b
            """,
            """
            val k = "a".length
            fun Base(x: Int) = k
            val b = listOf<Base>()
            open class Base { val v = k }
            b
            """,
            """
            val k = "a".length
            fun Base(x: Int) = k
            class Derived : Base() { val w = k }
            open class Base { val v = k }
            Derived().w
            """
        ).map { it.trimIndent() }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        results[0] should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:8: error: Unresolved reference 'Base'.
                  | val b: <error>Base</error>? = null
                  hint: 'Base' is declared at line 4, while the classes of the script body are visible only after their declaration - move it before line 3.
            """.trimIndent()
        }
        results[1] should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:16: error: Unresolved reference 'Base'.
                  | val b = listOf<<error>Base</error>>()
                  hint: 'Base' is declared at line 4, while the classes of the script body are visible only after their declaration - move it before line 3.
            """.trimIndent()
        }
        results[2] should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:17: error: Unresolved reference 'Base'.
                  | class Derived : <error>Base</error>() { val w = k }
                  hint: 'Base' is declared at line 4, while the classes of the script body are visible only after their declaration - move it before line 3.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling dependency with invoke operator shadowed by local function`() = runTest {
        // given
        val script = """
            val prefix = ">".trim()
            fun log(message: String) = prefix + message
            sealed interface S
            class T : S { fun f() = log("hi") }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("log", Logger())))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:25: error: 'log' does not refer to the function of the script body declared at line 2, which is not visible in 'T'.
                  | class T : S { fun f() = <error>log</error>("hi") }
                  hint: 'T' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should refer to callable dependency as value from declaration moved out of the script body when local function has its name`() = runTest {
        // given
        val script = """
            val k = 1
            fun log() = k
            sealed interface S
            class T : S { fun f() = listOf("a").map(log) }
            T().f() + log()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(String) -> String>("log") { "dep:$it" }))

        // then
        assert(result.valueOrMessage == listOf("dep:a", 1))
    }

    @Test
    fun `should call callable dependency from declaration moved out of the script body when local function of its name does not apply`() = runTest {
        // given
        val script = """
            val k = 1
            fun log() = k
            sealed interface S
            class T : S { fun f() = log("x") }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(String) -> String>("log") { "dep:$it" }))

        // then
        assert(result.valueOrMessage == "dep:x")
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function instead of callable dependency of its name`() = runTest {
        // given
        val script = """
            val k = "1".toInt()
            fun log() = k
            sealed interface S
            class T : S { fun f() = log() }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(String) -> String>("log") { "dep:$it" }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:25: error: 'log' does not refer to the function of the script body declared at line 2, which is not visible in 'T'.
                  | class T : S { fun f() = <error>log</error>() }
                  hint: 'T' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should not call callable dependency instead of local function from declaration moved out of the script body binding its name or referring to type alias`() = runTest {
        // given
        val local = "val k = \"1\".toInt()\nfun log(s: String) = \"local:\$s\$k\"\nsealed interface S\n"
        val scripts = listOf(
            // the calls which the inner declarations do not apply to call the local function in the script
            local + "class T : S { fun log(i: Int) = i; fun f() = log(\"x\") }\nT().f()",
            local + "class T : S { val log = 1; fun f() = log(\"x\") }\nT().f()",
            local + "class T : S { fun f(log: Int) = log(\"x\") }\nT().f(1)",
            local + "class T : S { fun f(): String { val log = 1; return log(\"x\") } }\nT().f()",
            local + "class T : S { fun f() = listOf(1).map { log -> log(\"x\") } }\nT().f()",
            // the copies of the local functions referring to the type aliases follow them
            "val k = \"1\".toInt()\ntypealias N = String\nfun log(n: N) = \"local:\$n\$k\"\nsealed interface S\nclass T : S { fun f() = log(\"x\") }\nT().f()",
            "val k = \"1\".toInt()\ntypealias L<X> = List<X>\nfun log(l: L<String>) = \"local:\$l\$k\"\nsealed interface S\nclass T : S { fun f() = log(listOf(\"x\")) }\nT().f()",
            "val k = \"1\".toInt()\ntypealias Fn = () -> String\nfun log(f: Fn) = \"local:\${f()}\$k\"\nsealed interface S\nclass T : S { fun f() = log { \"x\" } }\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(Any?) -> String>("log") { "dep:$it" })) }

        // then
        assert(results.map { it.valueOrMessage.toString() }.none { "dep:" in it })
        assert(results.all { it is ScriptResult.Error })
        assert(results.map { it.valueOrMessage.toString().lines().first() } == listOf(
            "script.kts:4:50: error: Argument type mismatch: actual type is 'String', but 'Int' was expected.",
            "script.kts:4:38: error: Cannot infer type for type parameter 'T'. Specify it explicitly.",
            "script.kts:4:33: error: Cannot infer type for type parameter 'T'. Specify it explicitly.",
            "script.kts:4:53: error: Cannot infer type for type parameter 'T'. Specify it explicitly.",
            "script.kts:4:35: error: Cannot infer type for type parameter 'R'. Specify it explicitly.",
            "script.kts:5:25: error: 'log' does not refer to the function of the script body declared at line 3, which is not visible in 'T'.",
            "script.kts:5:25: error: 'log' does not refer to the function of the script body declared at line 3, which is not visible in 'T'.",
            "script.kts:5:25: error: 'log' does not refer to the function of the script body declared at line 3, which is not visible in 'T'."
        ))
        assert(results.takeLast(3).all { "hint: 'T' is compiled outside of the script body, where 'log' (line 3) is not visible" in it.valueOrMessage.toString() })
    }

    @Test
    fun `should not resolve callable dependency from declaration moved out of the script body when local function of its name cannot be confirmed`() = runTest {
        // given
        val scripts = listOf(
            // referred to as a function
            "val k = \"1\".toInt()\nfun log() = k\nsealed interface S\nclass T : S { fun f(): () -> Int = ::log }\nT().f()()",
            // referring to the local class, so it cannot be copied
            "val k = \"1\".toInt()\nclass L { val v = k }\nfun log(l: L) = l.v\nsealed interface S\nclass T : S { fun f() = listOf(\"a\").map(log) }\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(String) -> String>("log") { "dep:$it" })) }

        // then
        assert(results.all { result ->
            result is ScriptResult.Error && "Unresolved reference 'log'" in result.message
                    && "where 'log' (line" in result.message
        })
    }

    @Test
    fun `should call dependency from declaration moved out of the script body when local extension function has its name`() = runTest {
        // given
        val script = """
            val k = 1
            fun Int.log() = this + k
            sealed interface S
            class T : S { fun f() = log("x") }
            T().f() + 1.log()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(String) -> String>("log") { "dep:$it" }))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "dep:x2")
        }
    }

    @Test
    fun `should run script body with the coroutine of its caller as the receiver`() = runTest {
        // given
        val script = """
            this is Job && coroutineContext[Job] === this
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("limit", 1)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == true)
        }
    }

    @Test
    fun `should refer to the coroutine scope of the script body by the script block label without warnings`() = runTest {
        // given
        val script = """
            val nested = listOf(1).map { this@_script.isActive }.single()
            this@_script is Job && this@_script === coroutineContext[Job] && this@_script.isActive && nested
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == true)
            have(warnings == null)
        }
    }

    @Test
    fun `should report this in local function referring to the coroutine scope of the script body`() = runTest {
        // given
        val script = """
            val log = mutableListOf<String>()
            fun tag() = "${'$'}this".substringBefore("@") + "/" + log.size
            fun hash() = run { this.hashCode() }
            fun size() = with(log) { this.size }
            "${'$'}{tag()} ${'$'}{hash()} ${'$'}{size()}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:15: error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.
                  | fun tag() = "${'$'}<error>this</error>".substringBefore("@") + "/" + log.size
                  hint: refer to the coroutine scope as 'this@_script' instead.

                script.kts:3:20: error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.
                  | fun hash() = run { <error>this</error>.hashCode() }
                  hint: refer to the coroutine scope as 'this@_script' instead.
            """.trimIndent()
        }
    }

    @Test
    fun `should report this in local function outside of the body of local class referring to the coroutine scope`() = runTest {
        // given
        val script = """
            open class B(val v: Any)
            fun f(): Any { class C(val s: Any = this); return C().s }
            fun g() = object : B(this) { }.v
            "${'$'}{f()} ${'$'}{g()}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:37: error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.
                  | fun f(): Any { class C(val s: Any = <error>this</error>); return C().s }
                  hint: refer to the coroutine scope as 'this@_script' instead.

                script.kts:3:22: error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.
                  | fun g() = object : B(<error>this</error>) { }.v
                  hint: refer to the coroutine scope as 'this@_script' instead.
            """.trimIndent()
        }
    }

    @Test
    fun `should report members of Any called in local function without receiver on the coroutine scope`() = runTest {
        // given
        val script = """
            fun name() = toString()
            fun copy() = apply { }
            fun self() = with(this) { this }
            fun length() = with("abc") { toString().length }
            fun active() = isActive && launch { }.isActive
            "${'$'}{name()} ${'$'}{copy()} ${'$'}{self()} ${'$'}{length()} ${'$'}{active()}"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:14: error: 'toString' is resolved on the coroutine scope of the script body, while in a function of a regular script it is resolved on the script instance.
                  | fun name() = <error>toString</error>()
                  hint: refer to the coroutine scope as 'this@_script' instead.

                script.kts:2:14: error: 'apply' is resolved on the coroutine scope of the script body, while in a function of a regular script it is resolved on the script instance.
                  | fun copy() = <error>apply</error> { }
                  hint: refer to the coroutine scope as 'this@_script' instead.

                script.kts:3:19: error: 'this' refers to the coroutine scope of the script body, while in a function of a regular script it refers to the script instance.
                  | fun self() = with(<error>this</error>) { this }
                  hint: refer to the coroutine scope as 'this@_script' instead.
            """.trimIndent()
        }
    }

    @Test
    fun `should execute this in local function referring to other receivers`() = runTest {
        // given
        val script = """
            fun a() = with(5) { this + 1 }
            fun Int.b() = this + 1
            fun c() = object { val v = 1; fun d() = this.v }.d()
            fun e(): Int { class F(val v: Int) { fun g() = this.v }; return F(2).g() }
            fun h() = this@_script.isActive
            val i = fun Int.() = this + 1
            a() + 5.b() + c() + e() + (if (h()) 1 else 0) + 2.i() + "${'$'}this".length * 0
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6 + 6 + 1 + 2 + 1 + 3)
        }
    }

    @Test
    fun `should return from the script body by the script block label`() = runTest {
        // given
        val script = """
            if (limit > 0) return@_script "early"
            "late"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("limit", 1)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "early")
            have(warnings == null)
        }
    }

    @Test
    fun `should not resolve the label of the outer lambda of the script block`() = runTest {
        // given
        val script = """
            this@_dependencies
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:5: error: Unresolved label.
                  | this<error>@_dependencies</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should call function moved out of the script body instead of callable dependency of the same name`() = runTest {
        // given
        val script = """
            sealed interface S
            class T : S { fun f() = g() }
            fun g(): Int = 1
            g() + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<() -> Int>("g") { 2 }))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call function of the script instead of callable dependency of the same name like a regular script with the dependencies as members of its base class`() = runTest {
        // given
        val scripts = listOf(
            "fun x(): Int = 10\nx()",
            "val r = x()\nfun x(): Int = 10\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<() -> Int>("x") { 7 })) }

        // then
        // a regular script receiving the dependency as a provided property would return 7 instead (#101)
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(10, 10))
    }

    @Test
    fun `should report error at declaration moved out of the script body calling dependency with invoke operator of value class parameter shadowed by local function`() = runTest {
        // given
        val script = """
            import kotlin.time.Duration
            import kotlin.time.Duration.Companion.seconds
            val prefix = ">".trim()
            fun log(duration: Duration) = prefix + duration
            sealed interface S
            class T : S { fun f() = log(1.seconds) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("log", DurationLogger())))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:6:25: error: 'log' does not refer to the function of the script body declared at line 4, which is not visible in 'T'.
                  | class T : S { fun f() = <error>log</error>(1.seconds) }
                  hint: 'T' is compiled outside of the script body, where 'log' (line 4) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint to declare function before its use when called after elvis operator`() = runTest {
        // given
        val script = """
            val base = "1".toIntOrNull()
            val r = base ?: compute()
            val compute = 2
            fun compute() = base
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:17: error: Unresolved reference 'compute'.
                  | val r = base ?: <error>compute</error>()
                  hint: 'compute' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should call function moved out of the script body instead of callable dependency shadowed by local property`() = runTest {
        // given
        val script = """
            val r = g()
            val g = 1
            fun g(): Int = 10
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<() -> Int>("g") { 2 }))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 10)
        }
    }

    @Test
    fun `should report error at reference to dependency before local property of the same name declared later`() = runTest {
        // given
        val script = $$"""
            fun show() = "v=$x"
            val x = 2
            show()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("x", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs $$"""
                script.kts:1:18: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 2.
                  | fun show() = "v=$<error>x</error>"
                  hint: declare the property before line 1, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should not treat dependency with private invoke operator as callable`() = runTest {
        // given
        val script = $$"""
            val p = ">"
            fun d() = p
            sealed interface S
            class T : S { fun f() = "$d" }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("d", PrivateInvoke())))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "private")
        }
    }

    @Test
    fun `should execute script with dependency of class referring to missing optional class`() = runTest {
        // given
        val testClassLoader = OptionalUser::class.java.classLoader
        val classLoader = object : ClassLoader(testClassLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                when (name) {
                    OptionalFeature::class.java.name -> throw ClassNotFoundException(name)
                    OptionalUser::class.java.name -> findLoadedClass(name) ?: testClassLoader
                        .getResourceAsStream(name.replace('.', '/') + ".class")!!
                        .use { it.readBytes() }
                        .let { defineClass(name, it, 0, it.size) }
                    else -> super.loadClass(name, resolve)
                }
            }
        }
        val type = classLoader.loadClass(OptionalUser::class.java.name).kotlin
        val dependency = ScriptExecutor.Dependency(
            name = "user",
            type = type.createType(),
            value = type.java.getDeclaredConstructor().newInstance()
        )
        val executor = ScriptExecutor()

        // when
        val result = executor.execute("1", listOf(dependency))

        // then
        // evaluated, while the script is compiled against the class of the other class loader
        assert(result is ScriptResult.Error && result.phase == ExecutionPhase.EVALUATION)
    }

    @Test
    fun `should resolve references preceding local properties named like dependencies to other declarations`() = runTest {
        // given
        val scripts = listOf(
            "print(\"a\")\nval print = 3\nprint" to listOf(dependency("print", 1)),
            "require(true)\nval require = 1\nrequire" to listOf(dependency("require", "r")),
            "val n = with(listOf(1, 2)) { size }\nval size = 3\nn + size" to listOf(dependency("size", 1))
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(3, 1, 5))
    }

    @Test
    fun `should report error at reference to dependency before annotated local property of the same name declared later`() = runTest {
        // given
        val script = """
            fun show() = x
            @Suppress("UNUSED")
            val x = 2
            show()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("x", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:14: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 3.
                  | fun show() = <error>x</error>
                  hint: declare the property before line 1, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at reference to dependency before local property of the same name declared later also when resolvable otherwise`() = runTest {
        // given
        val scripts = listOf(
            "fun show() = error(\"a\")\nval error = 1\nshow()" to listOf(dependency<(String) -> String>("error") { "dep:$it" }),
            "fun show() = print\nval print = 2\nshow()" to listOf(dependency("print", 1))
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first() } == listOf(
            "script.kts:1:14: error: 'error' refers to the dependency, as the property 'error' of the script body is declared later, at line 2.",
            "script.kts:1:14: error: 'print' refers to the dependency, as the property 'print' of the script body is declared later, at line 2."
        ))
    }

    @Test
    fun `should report error at call of dependency before local function of the same name declared later`() = runTest {
        // given
        val script = """
            val p = "1".toInt()
            val r = x()
            fun x() = p + 10
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<() -> Int>("x") { 2 }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:9: error: 'x' refers to the dependency, as the function 'x' of the script body is declared later, at line 3.
                  | val r = <error>x</error>()
                  hint: declare the function before line 2, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should call dependency within lambdas of scope functions of script instance before local function of the same name`() = runTest {
        // given
        // the script instance is the receiver of these lambdas in a regular script, declaring the dependency, which takes precedence there
        val scripts = listOf(
            "val p = \"!\"\nval a = f(1)\nclass P\nval b = run { f(null) }\nfun f(x: P?) = \"local\" + p\na + b",
            "val p = \"!\"\nval b = run { f(null) }\nfun f(x: String?) = \"local\" + p\nb",
            "val p = \"!\"\nclass P\nval b = run { f(null) }\nfun f(x: P?) = \"local\" + p\nb",
            "val p = \"!\"\nval b = apply { f(null) }.let { runCatching { f(null) }.getOrThrow() } + with(this) { f(null) }\nfun f(x: String?) = \"local\" + p\nb",
            "val p = \"!\"\nfun g() = run { f(null) }\nfun f(x: String?) = \"local\" + p\ng()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(Any?) -> String>("f") { "dep" })) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("depdep", "dep", "dep", "depdep", "dep"))
    }

    @Test
    fun `should report calls of dependency within lambdas of scope functions of other receivers before local function of the same name`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\"\nclass Bar { fun m() = run { f(null) } }\nfun f(x: String?) = \"local\" + p\nBar().m()",
            "val p = \"!\"\nfun Int.h() = run { f(null) }\nfun f(x: String?) = \"local\" + p\n2.h()",
            "val p = \"!\"\nval b = with(\"a\") { run { f(null) } }\nfun f(x: String?) = \"local\" + p\nb",
            "val p = \"!\"\nval b = run<String> { f(null) }\nfun f(x: String?) = \"local\" + p\nb",
            "val p = \"!\"\nval b = listOf(1).map { f(null) }\nfun f(x: String?) = \"local\" + p\nb"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(Any?) -> String>("f") { "dep" })) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first()?.substringAfter("error: ") } == List(scripts.size) {
            "'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 3."
        })
    }

    @Test
    fun `should report error at call preceding local function named like library function`() = runTest {
        // given
        val script = """
            val prefix = ">".trim()
            print("raw\n")
            fun print(x: Any) = kotlin.io.print("${'$'}prefix${'$'}x")
            print("1\n")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:1: error: 'print' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.
                  | <error>print</error>("raw\n")
                  hint: 'print' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at operator preceding local operator function instead of resolving it to coroutine scope`() = runTest {
        // given
        val script = """
            val p = "0".toInt()
            data class V(val x: Int)
            val r = V(1) + V(2)
            operator fun V.plus(o: V) = V(x + o.x + p)
            r.x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:14: error: 'plus' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.
                  | val r = V(1) <error>+</error> V(2)
                  hint: 'plus' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 3.

                script.kts:5:3: error: Unresolved reference 'x' on receiver of type 'CoroutineScope'.
                  | r.<error>x</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at call preceding local function referring to local class declared after preceding call of its name`() = runTest {
        // given
        val script = """
            val p = "0".toInt()
            val a = maxOf(1, 2)
            class W(val x: Int)
            val r = maxOf(W(1), W(2))
            fun maxOf(a: W, b: W) = W(a.x + b.x + p)
            r.x + a
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // without the errors of the library function the call is resolved to instead, like the argument type mismatches
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:9: error: 'maxOf' does not refer to the function of the script body declared at line 5, which is not visible before its declaration.
                  | val r = <error>maxOf</error>(W(1), W(2))
                  hint: 'maxOf' is declared at line 5, while the functions of the script body are visible only after their declaration - move it before line 4.

                script.kts:6:3: error: Unresolved reference 'x'.
                  | r.<error>x</error> + a
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at call preceding local function with arguments of wrong types only once`() = runTest {
        // given
        val script = """
            val p = "0".toInt()
            fun show(x: Int) = "int" + p
            val r = show(listOf("a").first())
            fun show(x: String) = "string" + p
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:9: error: 'show' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.
                  | val r = <error>show</error>(listOf("a").first())
                  hint: 'show' is declared at line 4, while the functions of the script body are visible only after their declaration - move it before line 3.
            """.trimIndent()
        }
    }

    @Test
    fun `should keep errors of arguments of call preceding local function independent of function called`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"0\".toInt()\nabstract class A\nprint(A())\nfun print(x: Any) = kotlin.io.print(\"${'$'}p${'$'}x\")",
            "val p = \"0\".toInt()\nval u: Int\nprint(u)\nfun print(x: Any) = kotlin.io.print(\"${'$'}p${'$'}x\")",
            "val p = \"0\".toInt()\nabstract class A\ndata class V(val x: Int)\nval r = V(1) + A()\noperator fun V.plus(o: Any) = V(x + p)\nr",
            // the errors of the calls as arguments, which do not apply themselves
            "val p = \"0\".toInt()\nprint(maxOf(\"a\", 2))\nfun print(x: Any) = kotlin.io.print(\"${'$'}p${'$'}x\")",
            "val p = \"0\".toInt()\nprint(Pair(1))\nfun print(x: Any) = kotlin.io.print(\"${'$'}p${'$'}x\")"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { result -> (result as? ScriptResult.Error)?.message?.lines()?.filter { it.startsWith("script.kts:") } } == listOf(
            listOf(
                "script.kts:3:1: error: 'print' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.",
                "script.kts:3:7: error: Cannot create an instance of an abstract class."
            ),
            listOf(
                "script.kts:3:1: error: 'print' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.",
                "script.kts:3:7: error: Variable 'u' must be initialized."
            ),
            listOf(
                "script.kts:4:14: error: 'plus' does not refer to the function of the script body declared at line 5, which is not visible before its declaration.",
                "script.kts:4:16: error: Cannot create an instance of an abstract class."
            ),
            listOf(
                "script.kts:2:1: error: 'print' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
                "script.kts:2:7: error: None of the following candidates is applicable:"
            ),
            listOf(
                "script.kts:2:1: error: 'print' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
                "script.kts:2:7: error: Cannot infer type for type parameter 'B'. Specify it explicitly.",
                "script.kts:2:7: error: No value passed for parameter 'second'."
            )
        ))
    }

    @Test
    fun `should report error at nested array access preceding local operator function only at the innermost one`() = runTest {
        // given
        val script = """
            val p = "0".toInt()
            data class V(val x: Int)
            val l = listOf(10, 20)
            val r = l[V(1)[0]]
            operator fun V.get(i: Int) = x + i + p
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:11: error: 'get' does not refer to the function of the script body declared at line 5, which is not visible before its declaration.
                  | val r = l[<error>V(1)[0]</error>]
                  hint: 'get' is declared at line 5, while the functions of the script body are visible only after their declaration - move it before line 4.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at calls preceding local functions named like library functions`() = runTest {
        // given
        val scripts = listOf(
            // returning another value
            "val p = \"9\".toInt()\nval r = listOf(1)\nfun listOf(x: Int) = kotlin.collections.listOf(x, p)\nr",
            // an extension function, called on a receiver
            "val p = \"0\".toInt()\nval r = listOf(1, 2).sum()\nfun List<Int>.sum() = 100 + p\nr",
            // within a lambda without a receiver
            "val p = \"0\".toInt()\nval r = listOf(1, 2).map { maxOf(it, 5) }\nfun maxOf(a: Int, b: Int) = a + b + p\nr",
            // within a lambda with a receiver, which does not declare it
            "val p = \"9\".toInt()\nval r = run { listOf(3) }\nfun listOf(x: Int) = kotlin.collections.listOf(x, p)\nr",
            // within a local class
            "val p = \"0\".toInt()\nclass Foo { fun bar() = maxOf(1, 2) }\nval r = Foo().bar()\nfun maxOf(a: Int, b: Int) = a + b + p\nr",
            // within a local function
            "val p = \"9\".toInt()\nfun f() = listOf(1)\nval r = f()\nfun listOf(x: Int) = kotlin.collections.listOf(x, p)\nr",
            // an operator of a local class, failing within a lambda
            "val p = \"0\".toInt()\ndata class V(val x: Int)\nval r = run { V(1) + V(2) }\noperator fun V.plus(o: V) = V(x + o.x + p)\nr.x",
            // an operator of a local class, preceded by another operator of its name, where the class is not visible yet
            "val p = \"0\".toInt()\nval s = 1 + 2\ndata class V(val x: Int)\nval r = V(1) + V(2)\noperator fun V.plus(o: V) = V(x + o.x + p)\nr.x"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first() } == listOf(
            "script.kts:2:9: error: 'listOf' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
            "script.kts:2:22: error: 'sum' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
            "script.kts:2:28: error: 'maxOf' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
            "script.kts:2:15: error: 'listOf' does not refer to the function of the script body declared at line 3, which is not visible before its declaration.",
            "script.kts:2:25: error: 'maxOf' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.",
            "script.kts:2:11: error: 'listOf' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.",
            "script.kts:3:20: error: 'plus' does not refer to the function of the script body declared at line 4, which is not visible before its declaration.",
            "script.kts:4:14: error: 'plus' does not refer to the function of the script body declared at line 5, which is not visible before its declaration."
        ))
    }

    @Test
    fun `should execute calls preceding local functions like regular script when they do not apply or receivers resolve them`() = runTest {
        // given
        val scripts = listOf(
            // the member of the receiver of the lambda takes precedence
            "val p = \"1\".toInt()\nval r = buildString { append(\"x\") }\nfun append(s: String): StringBuilder = throw Exception(\"local ${'$'}p\")\nr",
            // the local function does not apply
            "val p = \"1\".toInt()\nval r = listOf(\"a\")\nfun listOf(x: Int) = kotlin.collections.listOf(x, p)\nr",
            // the member of a local class takes precedence over the extension operator
            "val p = \"0\".toInt()\ndata class V(val x: Int) { operator fun plus(o: V) = V(x * o.x) }\nval r = V(2) + V(3)\noperator fun V.plus(o: V) = V(x + o.x + p)\nr.x",
            // the members of the receivers take precedence over the local functions of inferred types as well
            "val p = \"1\".toInt()\nclass S { fun pick(x: Int) = x * 2 }\nval r = with(S()) { pick(1) }\nfun pick(x: Int) = \"s\" + p\nr",
            "val p = \"1\".toInt()\nval r = buildList { add(1) }\nfun add(x: Int) = p\nr",
            "val p = \"1\".toInt()\nclass S { fun pick(x: Int) = x * 2 }\nfun S.go() = pick(1)\nval r = S().go()\nfun pick(x: Int) = \"s\" + p\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("x", listOf("a"), 6, 2, listOf(1), 2))
    }

    @Test
    fun `should report error at reference to dependency before local property of the same name despite suppressed deprecation error`() = runTest {
        // given
        val scripts = listOf(
            "@Suppress(\"DEPRECATION_ERROR\") fun show() = x\nval x = 2\nshow()",
            "@file:Suppress(\"DEPRECATION_ERROR\")\nfun show() = x\nval x = 2\nshow()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency("x", 1))) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first() } == listOf(
            "script.kts:1:45: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 2.",
            "script.kts:2:14: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 3."
        ))
    }

    @Test
    fun `should explain compilation error caused by reference to dependency before local property of the same name`() = runTest {
        // given
        val script = """
            fun show(): String = x
            val x = "s"
            show()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("x", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:22: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 2.
                  | fun show(): String = <error>x</error>
                  hint: declare the property before line 1, or rename it.

                script.kts:1:22: error: Return type mismatch: expected 'String', actual 'Int'.
                  | fun show(): String = <error>x</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should call dependency before local function of the same name which does not apply`() = runTest {
        // given
        val script = """
            val p = "!"
            val r = f(1)
            fun f(s: String) = s + p
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(Int) -> Int>("f") { it * 2 }))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should report error at call of dependency before local function of the same name referring to local class declared later`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            val r = f(emptyList())
            class P
            fun f(xs: List<P>) = "local" + p
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(List<Any?>) -> String>("f") { "dep" }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 4.
                  | val r = <error>f</error>(emptyList())
                  hint: declare the function before line 2, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should report errors at calls of dependencies around local classes declared later which local function of the same name refers to`() = runTest {
        // given
        val scripts = listOf(
            // within the supertype of the class
            "val p = \"!\".trim()\nval a = f(1)\nabstract class S { fun g() = f(null) }\nclass P : S() { val q = p }\nfun f(x: P?) = \"local\" + p\na + P().g()",
            // following the class, preceded by a call the function does not apply to
            "val p = \"!\".trim()\nval a = f(1)\nclass P\nval c = f(emptyList())\nfun f(xs: List<P>) = \"local\" + p\na + c",
            "val p = \"!\".trim()\nval a = f(listOf(1))\nclass P\nval r = f(null)\nfun f(x: P?) = \"local\" + p\na + r",
            "val p = \"!\".trim()\nval a = f(listOf(1))\nclass P\nval r = f(mutableListOf(P()))\nfun f(xs: MutableList<P>) = \"local\" + p\na + r",
            // preceding and following it, also another class
            "val p = \"!\".trim()\nval a = f(null)\nclass P\nval b = f(null)\nval c = f(P())\nfun f(x: P?) = \"local\" + p\na + b + c",
            "val p = \"!\".trim()\nval a = f(null)\nclass X { val q = p }\nval b = f(null)\nclass P { val q = p }\nfun f(x: P?) = \"local\" + p\na + b",
            "val p = \"!\".trim()\nval a = f(1)\nclass P { val q = p }\nval b = f(P(), null)\nclass R { val q = p }\nfun f(x: P?, y: R?) = \"local\" + p\na + b",
            // applying through the supertypes of the class, declared before the call, or later
            "val p = \"!\".trim()\nval c = Comparator<Runnable> { _, _ -> 0 }\nval r = f(c)\nclass P(val v: Int) : Runnable { override fun run() {} }\nfun f(c: Comparator<in P>) = \"local\" + p\nr",
            "val p = \"!\".trim()\nopen class Base\nval c = Comparator<Base> { _, _ -> 0 }\nval a = f(c)\nclass P : Base() { val q = p }\nfun f(c: Comparator<in P>) = \"local\" + p\na",
            "val p = \"!\".trim()\nval c = Comparator<Runnable> { _, _ -> 0 }\nval a = f(c)\nabstract class S : Runnable\nclass P : S() { override fun run() {} }\nfun f(c: Comparator<in P>) = \"local\" + p\na",
            // of the type parameters, or the bound of the type parameter of the function
            "val p = \"!\".trim()\nval a = f(emptyList())\nclass P<out T : Number>(val t: T)\nfun f(xs: List<P<Int>>) = \"local\" + p\na",
            "val p = \"!\".trim()\nval a = f(null)\nclass P { val q = p }\nfun <T : P> f(x: T?) = \"local\" + p\na",
            // the overloads declared before the class compete with the ones following it, as in the script
            "val p = \"!\".trim()\nfun h(x: Int): Int = 1 + p.length\nval a = f(\"x\")\nclass P { val q = p }\nfun h(x: Any): String = \"s\" + p\nval b = f(h(1))\nfun f(xs: List<P>) = \"localP\" + p\nfun f(n: Int) = \"localInt\" + p\na + b",
            // with a multiline literal
            "val p = \"!\".trim()\nval r = f(null)\nclass P(val s: String = \"\"\"a\nb\"\"\") { val t = p }\nval c = x\nfun f(x: P?) = \"local\" + p\nval x = \"localx\"\nr + c",
            // within the class, or following it on the same line, without a value of the class
            "val p = \"!\".trim()\nval a = f(1)\nclass P { val q = p; fun g() = f(null) }\nfun f(x: P?) = \"local\" + p\na + P().g()",
            "val p = \"!\".trim()\nval a = f(1)\nclass P { val q = p }; val b = f(null)\nfun f(x: P?) = \"local\" + p\na + b"
        )
        val dependencies = listOf(
            dependency<(Any?) -> String>("f") { "dep" }, dependency<(Any?) -> String>("g") { "depg" }, dependency("x", "depx")
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, dependencies) }

        // then
        assert(results.map { result -> (result as? ScriptResult.Error)?.message?.lines()?.filter { it.startsWith("script.kts") } } == listOf(
            listOf("script.kts:3:30: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 5."),
            // calling the dependency, as the confirmed calls are not explained
            listOf(
                "script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 5.",
                "script.kts:4:11: error: Cannot infer type for type parameter 'T'. Specify it explicitly."
            ),
            listOf("script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 5."),
            listOf("script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 5."),
            listOf(
                "script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6.",
                "script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6.",
                "script.kts:5:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6."
            ),
            listOf(
                "script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6.",
                "script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6."
            ),
            listOf(
                "script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6.",
                "script.kts:4:16: error: Too many arguments for 'fun invoke(p1: Any?): String'."
            ),
            listOf("script.kts:3:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 5."),
            listOf("script.kts:4:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6."),
            listOf("script.kts:3:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6."),
            listOf(
                "script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 4.",
                "script.kts:2:11: error: Cannot infer type for type parameter 'T'. Specify it explicitly."
            ),
            listOf("script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 4."),
            listOf("script.kts:6:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 7."),
            listOf(
                "script.kts:2:9: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 6.",
                "script.kts:5:9: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 7."
            ),
            listOf("script.kts:3:32: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 4."),
            listOf("script.kts:3:32: error: 'f' refers to the dependency, as the function 'f' of the script body is declared later, at line 4.")
        ))
    }

    @Test
    fun `should call dependency within local class which local function of the same name declared later refers to when passing value of the class`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nval a = f(1)\nclass P { val q = p; fun g() = f(P()) }\nfun f(x: P) = \"local\" + p\na + P().g()",
            "val p = \"!\".trim()\nval a = f(1)\nclass P { val q = p }; val b = f(P())\nfun f(x: P) = \"local\" + p\na + b"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(Any?) -> String>("f") { "dep" })) }

        // then
        // unlike in a regular script, calling the local function, see README
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("depdep", "depdep"))
    }

    @Test
    fun `should call dependencies around local classes declared later which local function of the same name does not apply to`() = runTest {
        // given
        val scripts = listOf(
            // the local declarations between the call and the class, and the calls of the other dependencies within the class
            "val p = \"!\"\nval a = f(1)\nclass R { fun f(x: Int) = \"recv\" }\nclass P { val q = p; fun g() = with(R()) { f(2) } }\nfun f(x: P?) = \"local\" + p\na + P().g()",
            "val p = \"!\"\nval a = f(1)\nclass P { val q = p; fun h() = g(2) }\nfun f(x: P?) = \"local\" + p\nfun g(s: String) = \"localg\" + p\na + P().h()",
            "val p = \"!\"\nval r = f(listOf(1))\nclass P\nfun f(xs: List<P>) = \"local\" + p\nr",
            "val p = \"!\"\nval a = f(listOf(1))\nclass P\nval r = f(listOf(2))\nfun f(xs: List<P>) = \"local\" + p\na + r",
            "val p = \"!\"\nval c = Comparator<Runnable> { _, _ -> 0 }\nval r = f(c)\nclass P(val v: Int)\nfun f(c: Comparator<in P>) = \"local\" + p\nr",
            // within the classes, which the function refers to through the other ones, or not at all, or following them on another line
            "val p = \"!\"\nval a = f(1)\nclass Q { val q = p; fun h() = f(2) }\nclass P(val q: Q)\nfun f(xs: List<P>) = \"local\" + p\na + Q().h()",
            "val p = \"!\"\nval a = f(1)\nabstract class S { fun g() = f(listOf(2)) }\nclass P : S() { val q = p }\nfun f(x: P?) = \"local\" + p\na + P().g()",
            "val p = \"!\"\nval a = f(\"s\")\nclass P { val q = p; fun g() = f(\"t\") }\nfun <P : Number> f(x: P) = \"local\" + p\na + P().g()",
            "val p = \"!\"\nval a = f(1)\nclass P { val q = p }; val b = listOf(\n  1); val c = f(3)\nfun f(xs: List<P>) = \"local\" + p\na + c",
            // referring also to the local classes declared before the calls
            "val p = \"!\"\nclass Q { val q = p }\nval a = f(\"s\")\nclass P { val q = p }\nfun f(q: Q?, xs: List<P>? = null) = \"local\" + p\na",
            "val p = \"!\"\nclass Q { val q = p }\nval a = f(1)\nclass P { val q = p }\nfun f(q: Q, xs: List<P>? = null) = \"local\" + p\na",
            "val p = \"!\"\nclass Q { val q = p }\nval a = f(listOf(\"s\"))\nclass P { val q = p }\nfun f(xs: List<Q>, ys: List<P>? = null) = \"local\" + p\na",
            "val p = \"!\"\nclass Q { val q = p }\nval a = f(1)\nclass P { val q = p }\nfun f(q: Q) = \"localQ\" + p\nfun f(x: P) = \"localP\" + p\na",
            // a qualified type named like a local class, and a call within the class resolved to its member
            "val p = \"!\"\nval a = f(1)\nclass Pair { val q = p; fun g() = f(2) }\nfun f(x: kotlin.Pair<Int, Int>) = \"local\" + p\na + Pair().g()",
            "val p = \"!\"\nval a = f(1)\nabstract class S { fun f(x: Int) = \"member\" }\nclass P : S() { val q = p; fun g() = f(2) }\nfun f(xs: List<P>) = \"local\" + p\na + P().g()",
            // within the lambdas of the scope functions of the script instance, declaring the dependency
            "val p = \"!\"\nval a = run { f(null) }\nclass P\nfun f(x: P?) = \"local\" + p\na",
            "val p = \"!\"\nval b = run { f(null) }\nclass P\nval c = run { f(null) }\nfun f(x: P?) = \"local\" + p\nb + c"
        )
        val dependencies = listOf(
            dependency<(Any?) -> String>("f") { "dep" }, dependency<(Any?) -> String>("g") { "depg" }, dependency("x", "depx")
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(
            "deprecv", "depdepg", "dep", "depdep", "dep", "depdep", "depdep", "depdep", "depdep", "dep", "dep", "dep", "dep", "depdep", "depmember",
            "dep", "depdep"
        ))
    }

    @Test
    fun `should report error at call of dependency before local class of the same name declared later`() = runTest {
        // given
        val script = """
            val p = "1".toInt()
            val r = Foo(2)
            class Foo(val v: Int) { fun g() = v + p }
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(Int) -> Int>("Foo") { it * 100 }))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:9: error: 'Foo' refers to the dependency, as the class 'Foo' of the script body is declared later, at line 3.
                  | val r = <error>Foo</error>(2)
                  hint: declare the class before line 2, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should report calls of dependency before constructors of local class of the same name declared later`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo(val s: String) { constructor(i: Int) : this(\"i\$i\"); fun g() = s + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo { val v: Int; constructor(i: Int) { v = i }; fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo()\nclass Foo { fun g() = p }\nr",
            "val p = \"1\".toInt()\nval r = Foo<Int>(2)\nclass Foo<T>(val v: T) { fun g() = p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\ndata class Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo(val v: Int, val w: Int = 3) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo(vararg val v: Int) { fun g() = p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nabstract class Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r: Any = Foo(v = 2)\nclass Foo(val v: Int) { constructor(o: Foo) : this(o.v); fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval f = { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nf()",
            "val p = \"1\".toInt()\nfun h() = listOf(1).map { Foo(it) }\nclass Foo(val v: Int) { fun g() = v + p }\nh()",
            "val p = \"1\".toInt()\nval r = \"a\".run { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nclass Bar { fun make() = Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nBar().make()",
            // within the lambdas of the scope functions, the receivers of which are not the script instance in a regular script
            "val p = \"1\".toInt()\nclass Bar { fun m() = run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nBar().m()",
            "val p = \"1\".toInt()\nclass Bar { val x = run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nBar().x",
            "val p = \"1\".toInt()\nclass Bar { fun m() = apply { Foo(2) }.let { with(this) { Foo(3) } } }\nclass Foo(val v: Int) { fun g() = v + p }\nBar().m()",
            "val p = \"1\".toInt()\nclass Bar { fun m() = this@Bar.run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nBar().m()",
            "val p = \"1\".toInt()\nfun Int.h() = run { Foo(this) }\nclass Foo(val v: Int) { fun g() = v + p }\n2.h()",
            "val p = \"1\".toInt()\nval r = buildString { append(run { Foo(2) }) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = with(\"a\") { run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = \"a\".run { this.run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = object { val x = run { Foo(2) } }.x\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = kotlinx.coroutines.coroutineScope { run { Foo(2) } }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            // with the parameters of the type of the class itself
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo(val v: Int, val next: Foo? = null) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo(val v: Int, val children: List<Foo> = emptyList()) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = Foo(2)\nclass Foo<T>(val v: T, val next: Foo<T>? = null) { fun g() = p }\nr",
            "val p = \"1\".toInt()\nval r = Foo<Nothing?>(2)\nclass Foo<T : Foo<T>?>(val v: Int) { fun g() = v + p }\nr",
            // within the lambdas of the scope functions, of other receivers than the script instance, or without receivers
            "val p = \"1\".toInt()\nval r = run<Any> { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = runCatching<Any> { Foo(2) }.getOrThrow()\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = run<Any, Any> { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = \"1\".toInt()\nval r = with<Any, Any>(this) { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency<(Int) -> Int>("Foo") { it * 100 })) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first()?.substringAfter("error: ") } == List(scripts.size) {
            "'Foo' refers to the dependency, as the class 'Foo' of the script body is declared later, at line 3."
        })
    }

    @Test
    fun `should report later call of dependency before local class of the same name whose constructor refers to local class declared after first call`() = runTest {
        // given
        val script = """
            val p = "1".toInt()
            val a = Foo(null)
            class Bar { fun g() = p }
            val r = Foo(Bar())
            class Foo(val b: Bar?) { fun g() = p }
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(Any?) -> Int>("Foo") { 7 }))

        // then
        // unlike the first call, preceding the class of the parameter, see README
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:9: error: 'Foo' refers to the dependency, as the class 'Foo' of the script body is declared later, at line 5.
                  | val r = <error>Foo</error>(Bar())
                  hint: declare the class before line 4, or rename it.
            """.trimIndent()
        }
    }

    @Test
    fun `should call dependency before local class of the same name whose constructors do not apply`() = runTest {
        // given
        val scripts = listOf(
            // the arguments of other types, or of the bounds of the type parameters
            "val p = 1\nval r = Foo(\"x\")\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = 1\nval r = Foo(2)\nclass Foo<T : CharSequence>(val v: T) { fun g() = p }\nr",
            // not visible to the script body
            "val p = 1\nval r = Foo(2)\nclass Foo private constructor(val v: Int) { fun g() = v + p }\nr",
            // within the lambda of the receiver, which is the script instance declaring the dependency in a regular script
            "val p = 1\nval r = run { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = 1\nval r = with(this) { Foo(2) } + this.run { Foo(1) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = 1\nval r = listOf(1).map { run { Foo(it) } }.single() + run { \"a\".run { Foo(1) } }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            // the members of the receivers, which take precedence over the constructors
            "val p = 1\nclass M { fun Foo(i: Int) = i + 198 }\nval r = with(M()) { Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = 1\nopen class B { fun Foo(i: Int) = i + 198 }\nclass C : B() { fun make() = Foo(2) }\nclass Foo(val v: Int) { fun g() = v + p }\nC().make()",
            // a value of the local class declared later cannot precede it
            "val p = 1\nval r = Foo(2)\nclass Foo(val next: Foo?) { fun g() = p }\nr",
            // within the lambdas of the scope functions of the script instance
            "val p = 1\nvar y = 0\napply { y = Foo(1) }\nval r = runCatching { Foo(2) }.getOrThrow() + y\nclass Foo(val v: Int) { fun g() = v + p }\nr",
            "val p = 1\nval r = Foo(2)\nclass Bar(val q: Int) { fun g() = q + p }\nclass Foo(val b: Bar) { fun g() = p }\nr",
            // followed by a call of the constructor
            "val p = 1\nval r = Foo(\"x\")\nclass Foo(val v: Int) { fun g() = v + p }\nclass Foo2(val v: Int) { fun g() = Foo(v).g() }\nr + Foo2(1).g()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { script ->
            executor.execute(script, listOf(if ("\"x\"" in script) dependency<(String) -> Int>("Foo") { 100 } else dependency<(Int) -> Int>("Foo") { it * 100 }))
        }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(100, 200, 200, 200, 300, 200, 200, 200, 200, 300, 200, 102))
    }

    @Test
    fun `should provide dependency of nullable function type named like function of the coroutine scope`() = runTest {
        // given
        val script = """
            let?.invoke() ?: 3
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(() -> Int)?>("let", null)))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should call function of the coroutine scope named like dependency of nullable function type`() = runTest {
        // given
        val script = """
            listOf(launch?.invoke(), launch { }.let { 1 })
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val results = listOf<(() -> Int)?>({ 2 }, null).map { value ->
            executor.execute(script, listOf(dependency<(() -> Int)?>("launch", value)))
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(listOf(2, 1), listOf(null, 1)))
    }

    @Test
    fun `should resolve value of dependency of nullable function type named like local function`() = runTest {
        // given
        val script = """
            fun f() = 1
            listOf(f?.invoke(), (f ?: { 3 })(), f())
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val results = listOf<(() -> Int)?>({ 2 }, null).map { value ->
            executor.execute(script, listOf(dependency<(() -> Int)?>("f", value)))
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(listOf(2, 2, 1), listOf(null, 3, 1)))
    }

    @Test
    fun `should resolve value of dependency of nullable function type from declaration moved out of the script body declaring function of its name`() = runTest {
        // given
        val script = """
            val offset = 10
            fun f() = offset
            sealed interface S
            class T : S { fun g() = listOf(f?.invoke(), (f ?: { 3 })()) }
            T().g()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val results = listOf<(() -> Int)?>({ 2 }, null).map { value ->
            executor.execute(script, listOf(dependency<(() -> Int)?>("f", value)))
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(listOf(2, 2), listOf(null, 3)))
    }

    @Test
    fun `should resolve value of dependency of nullable type with invoke operator named like local function`() = runTest {
        // given
        val script = """
            fun logger() = 1
            listOf(logger?.invoke("a"), logger ?: "none", logger())
        """.trimIndent()
        val logger = Logger()
        val executor = ScriptExecutor()

        // when
        val results = listOf(logger, null).map { value ->
            executor.execute(script, listOf(dependency<Logger?>("logger", value)))
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(listOf("dep:a", logger, 1), listOf(null, "none", 1)))
    }

    @Test
    fun `should resolve value of dependency of nullable type with invoke operator from declaration moved out of the script body declaring function of its name`() = runTest {
        // given
        val script = """
            val prefix = "local:"
            fun logger(message: String) = prefix + message
            sealed interface S
            class T : S { fun g() = logger?.invoke("a") ?: "none" }
            listOf(T().g(), logger("b"))
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val results = listOf(Logger(), null).map { value ->
            executor.execute(script, listOf(dependency<Logger?>("logger", value)))
        }

        // then
        assert(results.map { it.valueOrMessage } == listOf(listOf("dep:a", "local:b"), listOf("none", "local:b")))
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function named like library function`() = runTest {
        // given
        val script = """
            val strict = "true".toBoolean()
            fun check(flag: Boolean) { if (strict && !flag) throw IllegalStateException("strict") }
            sealed interface S
            class T : S { fun f() = check(false) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:4:25: error: 'check' does not refer to the function of the script body declared at line 2, which is not visible in 'T'.
                  | class T : S { fun f() = <error>check</error>(false) }
                  hint: 'T' is compiled outside of the script body, where 'check' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function referring to type alias named like library function`() = runTest {
        // given
        // the type alias is moved out of the script body, and visible only to the declarations following it there
        val script = """
            typealias N = Boolean
            val k = "1".toInt()
            fun check(n: N) = "local:${'$'}n${'$'}k"
            sealed interface S
            class T : S { fun f() = check(true) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:5:25: error: 'check' does not refer to the function of the script body declared at line 3, which is not visible in 'T'.
                  | class T : S { fun f() = <error>check</error>(true) }
                  hint: 'T' is compiled outside of the script body, where 'check' (line 3) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function referring to type aliases of type arguments or function types`() = runTest {
        // given
        val scripts = listOf(
            "val k = \"1\".toInt()\ntypealias L<X> = List<X>\ntypealias N = Boolean\nfun check(l: L<N>) = \"local:\$l\$k\"\nsealed interface S\nclass T : S { fun f() = check(listOf(true)) }\nT().f()",
            "val k = \"1\".toInt()\ntypealias Fn = () -> Boolean\nfun check(f: Fn): String = \"local:\${f()}\$k\"\nsealed interface S\nclass T : S { fun f() = check { true } }\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.firstOrNull() } == listOf(
            "script.kts:6:25: error: 'check' does not refer to the function of the script body declared at line 4, which is not visible in 'T'.",
            "script.kts:5:25: error: 'check' does not refer to the function of the script body declared at line 3, which is not visible in 'T'."
        ))
    }

    @Test
    fun `should call library function from declaration moved out of the script body like a regular script when local function refers to type alias declared later`() = runTest {
        // given
        // the type alias is not visible to the local function preceding it in a regular script, which calls the library function then
        val script = "val k = 1\nfun check(n: N) = \"local:\$n\$k\"\nsealed interface S\nclass T : S { fun f() = check(true) }\ntypealias N = Boolean\nT().f()"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == Unit)
        }
    }

    @Test
    fun `should call callable dependency from declaration moved out of the script body like a regular script when local function of its name refers to type alias declared later`() = runTest {
        // given
        // the copy of the local function precedes the type alias, so it does not apply, like the local function in a regular script
        val script = "val k = 1\nfun log(n: N) = \"local:\$n\$k\"\nsealed interface S\nclass T : S { fun f() = log(\"x\") }\ntypealias N = String\nT().f()"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(Any?) -> String>("log") { "dep:$it" }))

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "dep:x")
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function with dollar in name named like imported function`() = runTest {
        // given
        // the name is pasted into the string literal of the deprecation message of its copy
        val script = """
            import com.xemantic.kotlin.script.names.*
            val strict = "true".toBoolean()
            fun `say${'$'}hi`(flag: Boolean) { if (strict && !flag) throw IllegalStateException("strict") }
            sealed interface S
            class T : S { fun f() = `say${'$'}hi`(false) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:5:25: error: 'say${'$'}hi' does not refer to the function of the script body declared at line 3, which is not visible in 'T'.
                  | class T : S { fun f() = <error>`say${'$'}hi`</error>(false) }
                  hint: 'T' is compiled outside of the script body, where 'say${'$'}hi' (line 3) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function with quotes in name named like imported function`() = runTest {
        // given
        val script = """
            import com.xemantic.kotlin.script.names.*
            val strict = "true".toBoolean()
            fun `say"hi"`(flag: Boolean) { if (strict && !flag) throw IllegalStateException("strict") }
            sealed interface S
            class T : S { fun f() = `say"hi"`(false) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:5:25: error: 'say"hi"' does not refer to the function of the script body declared at line 3, which is not visible in 'T'.
                  | class T : S { fun f() = <error>`say"hi"`</error>(false) }
                  hint: 'T' is compiled outside of the script body, where 'say"hi"' (line 3) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should refer to local property from declaration moved out of the script body when function of companion object has its name`() = runTest {
        // given
        val script = """
            val k = "s"
            sealed interface S
            class T : S {
                companion object { fun k(x: Int) = x }
                fun f(): String = k
            }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "s")
        }
    }

    @Test
    fun `should report error at declaration moved out of the script body calling local function named like function of companion object`() = runTest {
        // given
        val script = """
            val k = "1".toInt()
            fun log(msg: String) = msg + k
            sealed interface S
            class T : S {
                companion object { fun log(x: Int) = x }
                fun f() = log("a")
            }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:6:15: error: 'log' does not refer to the function of the script body declared at line 2, which is not visible in 'T'.
                  |     fun f() = <error>log</error>("a")
                  hint: 'T' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.

                script.kts:6:19: error: Argument type mismatch: actual type is 'String', but 'Int' was expected.
                  |     fun f() = log(<error>"a"</error>)
            """.trimIndent()
        }
    }

    @Test
    fun `should call member of supertype from declaration moved out of the script body when local function has its name`() = runTest {
        // given
        val script = """
            val strict = true
            fun check(flag: Boolean) { if (strict && !flag) throw IllegalStateException("strict") }
            abstract class Base { fun check(flag: Boolean) = "base:${'$'}flag" }
            sealed interface S
            class T : Base(), S { fun f() = check(false) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "base:false")
        }
    }

    @Test
    fun `should report wrongly resolved references despite suppressions, multiline types and inapplicable overloads`() = runTest {
        // given
        val scripts = listOf(
            // suppressed in another letter case
            "@Suppress(\"deprecation_error\") fun show() = x\nval x = 2\nshow()" to listOf(dependency("x", 1)),
            // parameter type spanning lines
            "val strict = \"true\".toBoolean()\nfun check(flag: Boolean, cb: (\n String\n) -> Unit = {}) { if (strict && !flag) throw IllegalStateException() }\nsealed interface S\nclass T : S { fun f() = check(false) }\nT().f()" to emptyList(),
            // local function which does not apply
            "fun greet(n: Int) = n\nval r = greet(\"Joe\")\nval greet = { s: String -> \"Hi ${'$'}s\" }\nr" to listOf(dependency<(String) -> String>("greet") { "DEP ${'$'}it" }),
            // local property of function type
            "val log = mutableListOf<String>()\nval print = { m: Any? -> log += m.toString() }\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            // local extension function
            "val factor = \"10\".toInt()\nfun List<Int>.sum(): Int = fold(0) { a, b -> a + b * factor }\nsealed class Shape { fun total() = listOf(1, 2).sum() }\nclass Circle : Shape()\nCircle().total()" to emptyList()
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        assert(results.all { result ->
            (result as ScriptResult.Error).message.lines().first().let {
                "refers to the dependency" in it || "does not refer to the" in it
            }
        })
    }

    @Test
    fun `should call library functions from declarations moved out of the script body when local properties of their names cannot be called`() = runTest {
        // given
        val scripts = listOf(
            "val delay = 10L\nsealed class Task { suspend fun go(): String { delay(1); return \"done\" } }\nclass Job1 : Task()\nJob1().go() + delay" to emptyList(),
            "val check = true\nsealed class C { fun f() { check(1 > 0) } }\nclass D : C()\nD().f()\ncheck" to emptyList(),
            "import kotlin.Suppress as Q\nx + 1" to listOf(dependency("x", 1))
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("done10", true, 2))
    }

    @Test
    fun `should report wrongly resolved references of callable references, infix calls, trailing lambdas, aliased suppressions and constrained type parameters`() = runTest {
        // given
        val scripts = listOf(
            "val log = mutableListOf<Any?>()\nfun println(x: Any?) { log += x }\nsealed interface S\nclass T : S { fun f() = listOf(1, 2).forEach(::println) }\nT().f()\nlog" to emptyList(),
            "val k = \"0\".toInt()\ninfix fun Int.until(to: Int) = listOf(this, to, k)\nsealed class S { fun f() = 0 until 20 }\nclass T : S()\nT().f()" to emptyList(),
            "val log = mutableListOf<String>()\nval repeat = { n: Int, block: (Int) -> Unit -> log += \"local\"; block(n) }\nsealed class Shape { fun show() = repeat(2) { } }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            "val suffix = \"!\"\nval trim: String.() -> String = { \"local\" + suffix }\nsealed class Shape { fun t() = \" a \".trim() }\nclass Circle : Shape()\nCircle().t()" to emptyList(),
            "typealias Q = Suppress\n@Q(\"DEPRECATION_ERROR\") fun show() = x\nval x = 2\nshow()" to listOf(dependency("x", 1)),
            "@[Suppress(\"UNUSED\")] fun show() = x\nval x = 2\nshow()" to listOf(dependency("x", 1)),
            "val p = \"!\".trim()\nfun <T> greet(t: T): String where T : CharSequence, T : Comparable<T> = \"local\" + t + p\nsealed interface S\nclass Q : S { fun f() = greet(StringBuilder(\"x\")) }\nfun greet(t: Any) = \"moved\" + t\nQ().f()" to emptyList(),
            "val f = { x: Int -> \"lambda\" }\nsealed class S\nclass T : S() { fun h() = f(1) }\nfun f(x: Int) = \"function\"\nf(1)" to emptyList()
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should execute valid scripts with local declarations named like other declarations which do not apply`() = runTest {
        // given
        val scripts = listOf(
            "val r = size(listOf(1, 2))\nval size = 10\nval q = size(listOf(1))\nfun size(xs: List<Int>) = xs.size\nr + q + size" to emptyList(),
            "val greet = { s: String -> s }\nval r = greet(2)\nfun greet(n: Int) = n * 10\nr" to emptyList(),
            "val p = 1\nclass P(val v: Int)\nval r = greet(\"hello\")\nfun greet(q: P) = q.v + p\nr" to listOf(dependency<(String) -> Int>("greet") { it.length })
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(13, 20, 5))
    }

    @Test
    fun `should report wrongly resolved callable references and resolve the moved constants`() = runTest {
        // given
        val callableReference = "val p = \"1\".toInt()\nval g = ::x\nfun x() = p + 10\ng()"
        val constants = listOf(
            "import kotlin.math.*\nval E = 2.0\nsealed interface S\nclass T : S { fun f() = E }\nT().f()",
            "import kotlin.math.PI\nval PI = 3.0\nsealed interface S\nclass T : S { fun f() = PI }\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(callableReference, listOf(dependency<() -> Int>("x") { 2 }))
        val results = constants.map { executor.execute(it) }

        // then
        assert(result is ScriptResult.Error && result.phase == ExecutionPhase.COMPILATION)
        // moved together with the declarations referring to them, the constants are resolved before the imports, like in the script
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(2.0, 3.0))
    }

    @Test
    fun `should report error of type parameter without name`() = runTest {
        // given
        val script = """
            fun <in> f() = 1
            f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && result.phase == ExecutionPhase.COMPILATION)
    }

    @Test
    fun `should report wrongly resolved repeated calls, split overloads, untyped function properties and later declared classes`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nval a = x(1)\nval b = x(\"a\")\nfun x(s: String) = \"local\" + s + p\na + b" to listOf(dependency<(Any) -> String>("x") { "dep$it" }),
            "val f = { s: String -> \"prop\" }\nval a = { f(1) }\nval b = f(\"a\")\nfun f(x: Int) = \"fun-int\"\nfun f(s: String) = \"fun-str\"\nb" to emptyList(),
            "val p = \"!\".trim()\nval r = show(1)\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nr + show(1)" to emptyList(),
            "val log = mutableListOf<String>()\nval print = fun(m: Any?) { log += m.toString() }\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            "val log = mutableListOf<String>()\nfun rec(m: Any?) { log += m.toString() }\nval print = ::rec\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList()
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should report calls of declarations moved out of the script body to local properties of function types inferred by the compiler`() = runTest {
        // given
        val scripts = listOf(
            "val log = mutableListOf<String>()\nfun printer(): (Any?) -> Unit = { log += it.toString() }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog",
            "val log = mutableListOf<String>()\nfun printer(): (Any?) -> Unit = { log += it.toString() }\nval (print, n) = printer() to 1\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog",
            "val suffix = \"!\"\nfun trimmer(): String.() -> String = { \"local\" + suffix }\nval trim = trimmer()\nsealed class Shape { fun t() = \" a \".trim() }\nclass Circle : Shape()\nCircle().t()",
            // of a class moved out of the script body, with the invoke operator
            "val log = mutableListOf<String>()\nsealed class Base { operator fun invoke(m: Any?) { (this as Printer).log += m.toString() } }\nval print = Printer(log)\nclass Printer(val log: MutableList<String>) : Base()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        assert(results.all { "does not refer to the property of the script body declared at line 3" in (it as ScriptResult.Error).message })
    }

    @Test
    fun `should report calls of declarations moved out of the script body to local properties of literals made callable by imports`() = runTest {
        // given
        val scripts = listOf(
            "import com.xemantic.kotlin.script.literal.invoke\nval print = 1 + 2\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()",
            "import com.xemantic.kotlin.script.literal.*\nval print = 1 + 2\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        assert(results.all { "does not refer to the property of the script body declared at line 2" in (it as ScriptResult.Error).message })
    }

    @Test
    fun `should report calls of declarations moved out of the script body to local properties made callable by local invoke operators`() = runTest {
        // given
        val invoke = "operator fun Int.invoke(x: Any?): String = \"int:${'$'}x\"\n"
        val scripts = listOf(
            // failing as resolved to `kotlin.io.print` instead
            "${invoke}val print = 1 + 2\nsealed class S { fun show(): String = print(\"\") }\nclass T : S()\nT().show()",
            "${invoke}val print = three()\nfun three() = 3\nsealed class S { fun show(): String = print(\"\") }\nclass T : S()\nT().show()",
            "${invoke}val print: Int = listOf(3).first()\nsealed class S { fun show(): String = print(\"\") }\nclass T : S()\nT().show()",
            // returning `Unit` of `kotlin.io.print` instead
            "${invoke}val print = 1 + 2\nsealed class S { fun show(): Any = print(\"\") }\nclass T : S()\nT().show()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        assert(results.all { "'print' does not refer to the property of the script body declared at line 2" in (it as ScriptResult.Error).message })
    }

    @Test
    fun `should move local invoke operator called on value by declaration moved out of the script body`() = runTest {
        // given
        val script = """
            operator fun Int.invoke(x: Any?): String = "int:${'$'}x"
            val print: Int = 3
            sealed class S { fun show(): String = print("") }
            class T : S()
            T().show()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "int:")
        }
    }

    @Test
    fun `should report unresolved value called by declaration moved out of the script body once`() = runTest {
        // given
        val script = """
            val p = listOf("!").first()
            operator fun Int.invoke(x: Any?): String = "int:${'$'}x${'$'}p"
            val q = listOf(1).first()
            sealed class S { fun g() = q(2) }
            class T : S()
            T().g()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && result.message == """
            script.kts:4:28: error: Unresolved reference 'q'.
              | sealed class S { fun g() = <error>q</error>(2) }
              hint: 'S' is compiled outside of the script body, where 'q' (line 3) is not visible - pass it as a parameter, or declare it within 'S'.
        """.trimIndent())
    }

    @Test
    fun `should move extension overloads not referred to by the script body despite overloads accessing local properties`() = runTest {
        // given
        val scripts = listOf(
            "val p = listOf(\"!\").first()\nfun String.f(): String = \"s\" + p\nfun Int.f(): String = \"int\"\nsealed class S { fun g() = 1.f() }\nclass T : S()\nT().g()",
            "class V(val n: Int)\nval p = listOf(1).first()\noperator fun String.plus(v: V): String = this + v.n + p\noperator fun V.plus(v: V): V = V(n + v.n)\n" +
                    "sealed class S { fun g() = (V(1) + V(2)).n }\nclass T : S()\nT().g()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("int", 3))
    }

    @Test
    fun `should move local invoke operator called on implicit lambda parameter by declaration moved out of the script body`() = runTest {
        // given
        val script = """
            operator fun Int.invoke(x: Any?): String = "int:${'$'}x"
            sealed class S { fun g() = listOf(3).map { it("x") } }
            class T : S()
            T().g()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == listOf("int:x"))
        }
    }

    @Test
    fun `should not move local invoke operator overloading one accessing local properties`() = runTest {
        // given
        val script = """
            val p = listOf("!").first()
            operator fun Any.invoke(x: Any?): String = "any:${'$'}x${'$'}p"
            operator fun Int.invoke(x: Any?): String = "int:${'$'}x"
            sealed class S { fun g() = listOf(1).size }
            class T : S()
            T().g().toString() + 3("x")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "1int:x")
        }
    }

    @Test
    fun `should report calls of moved declarations to local overloads not moved with the overloads accessing local properties`() = runTest {
        // given
        val overloads = "val p = listOf(\"!\").first()\n"
        val scripts = listOf(
            "${overloads}fun Any.f(x: Any?): String = \"any:${'$'}x${'$'}p\"\nfun Int.f(x: Any?): String = \"int:${'$'}x\"\n" +
                    "sealed class S { fun g() = 1.f(2) }\nclass T : S()\nT().g() + 3.f(\"x\")" to "where 'f' (line ",
            "${overloads}operator fun Any.invoke(x: Any?): String = \"any:${'$'}x${'$'}p\"\noperator fun Int.invoke(x: Any?): String = \"int:${'$'}x\"\n" +
                    "sealed class S { fun g(n: Int) = n(2) }\nclass T : S()\nT().g(1) + 3(\"x\")" to
                    // the overload called in the script
                    "'invoke' does not refer to the function of the script body declared at line 3"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script) -> executor.execute(script) }

        // then
        assert(results.zip(scripts).all { (result, script) ->
            result is ScriptResult.Error && script.second in result.message
        })
    }

    @Test
    fun `should report calls of declarations moved out of the script body on values made callable by hidden local invoke operator`() = runTest {
        // given
        val invoke = "val p = \"!\".trim()\noperator fun Int.invoke(x: Any?): String = \"int:${'$'}x${'$'}p\"\n"
        val scripts = listOf(
            // failing as resolved to `kotlin.io.print` instead, of the constant moved with the class
            "${invoke}val print: Int = 3\nsealed class S { fun show(): String = print(\"\") }\nclass T : S()\nT().show()",
            // returning `Unit` of `kotlin.io.print` instead
            "${invoke}val print: Int = 3\nsealed class S { fun show(): Any = print(\"\") }\nclass T : S()\nT().show()",
            "${invoke}sealed class S { fun show(n: Int): Any = n(\"\") }\nclass T : S()\nT().show(4)"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        assert(results.all { "'invoke' does not refer to the function of the script body declared at line 2" in (it as ScriptResult.Error).message })
    }

    @Test
    fun `should report call of declaration moved out of the script body failing as resolved to another function than the local one`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun print(x: Int): String = "n${'$'}x${'$'}p"
            sealed class S { fun f(): String = print(1) }
            class T : S()
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && result.message == """
            script.kts:3:36: error: 'print' does not refer to the function of the script body declared at line 2, which is not visible in 'S'.
              | sealed class S { fun f(): String = <error>print</error>(1) }
              hint: 'S' is compiled outside of the script body, where 'print' (line 2) is not visible - pass it as a parameter, or declare it within 'S'.

            script.kts:3:36: error: Return type mismatch: expected 'String', actual 'Unit'.
              | sealed class S { fun f(): String = <error>print(1)</error> }
        """.trimIndent())
    }

    @Test
    fun `should report calls of declarations moved out of the script body to local properties of inferred types on receivers named like packages`() = runTest {
        // given
        val scripts = listOf(
            // inherited from a library class
            "fun trimmer(): String.() -> String = { \"local\" }\nval trim = trimmer()\nsealed class S : Thread(\" a \") { fun f() = name.trim() }\nclass T : S()\nT().f()",
            // the member of the receiver of a scope function
            "fun trimmer(): String.() -> String = { \"local\" }\nval trim = trimmer()\nsealed class S { fun f() = with(\" a \" to 1) { first.trim() } }\nclass T : S()\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should report call of dependency before local class of the same name together with calls of local properties of inferred types named as invalid types`() = runTest {
        // given
        val script = "val p = \"1\".toInt()\nval delay = java.util.Collections.emptyList<String>()\nsealed class Task { suspend fun go(): String { delay(1); return \"done\" } }\nclass Job1 : Task()\nval r = Foo(2)\nclass Foo(val v: Int) { fun g() = v + p }\nJob1().go() + r"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency<(Int) -> Int>("Foo") { it * 100 }))

        // then
        assert(result is ScriptResult.Error && "'Foo' refers to the dependency, as the class 'Foo' of the script body is declared later" in result.message)
    }

    @Test
    fun `should report calls of declarations moved out of the script body to local properties of inferred types despite suppressions of the probes`() = runTest {
        // given
        val script = "@file:Suppress(\"INITIALIZER_TYPE_MISMATCH\")\nval log = mutableListOf<String>()\nfun printer(): (Any?) -> Unit = { log += it.toString() }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && "does not refer to the property of the script body declared at line 4" in result.message)
    }

    @Test
    fun `should resolve calls of declarations moved out of the script body to local properties of inferred types the same with CRLF line terminators`() = runTest {
        // given
        val scripts = listOf(
            "val log = mutableListOf<String>()\nfun printer(): (Any?) -> Unit = { log += it.toString() }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog",
            "val log = mutableListOf<String>()\nfun printer(): (Int) -> Unit = { }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog"
        )
        val dependencies = listOf(dependency<(Any?) -> Unit>("print") { })
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, dependencies) }
        val crlfResults = scripts.map { executor.execute(it.replace("\n", "\r\n"), dependencies) }

        // then
        assert(results.map { it::class } == listOf(ScriptResult.Error::class, ScriptResult.Value::class))
        assert(crlfResults.map { it::class } == results.map { it::class })
        assert((crlfResults[1] as ScriptResult.Value).value == (results[1] as ScriptResult.Value).value)
    }

    @Test
    fun `should call library functions from declarations moved out of the script body when local properties of inferred types do not apply`() = runTest {
        // given
        val scripts = listOf(
            "val log = mutableListOf<String>()\nfun printer(): (Int) -> Unit = { log += it.toString() }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog",
            "val log = mutableListOf<String>()\nfun printer(): ((Any?) -> Unit)? = { log += it.toString() }\nval print = printer()\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog",
            // of the type the compiler names as an invalid one, `(Mutable)List<String!>!`
            "val delay = java.util.Collections.emptyList<String>()\nsealed class Task { suspend fun go(): String { delay(1); return \"done\" } }\nclass Job1 : Task()\nJob1().go() + delay"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf<Any>(emptyList<String>(), emptyList<String>(), "done[]"))
    }

    @Test
    fun `should report wrongly resolved references after comments, of aliased function types and destructured names`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nval log = mutableListOf<String>()\n/* note\n */ log += x(\"a\")\nfun x(s: String) = \"local\" + s + p\nlog" to listOf(dependency<(Any) -> String>("x") { "dep$it" }),
            "val log = mutableListOf<String>()\ntypealias Printer = (Any?) -> Unit\nval print: Printer = { log += it.toString() }\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            "import kotlin.math.*\nval (E, F) = 2.0 to 3.0\nsealed interface S\nclass T : S { fun f() = E }\nT().f()" to emptyList()
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should execute valid scripts with overloads, classes and properties named like other declarations`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\"\nfun show(x: Int) = \"int\" + p\ninline fun show(s: String) = \"str\" + s\nshow(1) + show(\"a\")" to emptyList(),
            "val p = 1\nval r = Foo(\"x\")\nclass Foo(val v: Int) { fun g() = v + p }\nr" to listOf(dependency<(String) -> Int>("Foo") { 100 }),
            "val delay = 10L * 2\nsealed class Task { suspend fun go(): String { delay(1); return \"done\" } }\nclass Job1 : Task()\nJob1().go() + delay" to emptyList(),
            "val items = listOf(1, 2)\nval size = items.size\nsealed interface Shape { fun size(): Int = 3 }\nclass Box : Shape { fun twice() = 2 * size() }\nBox().twice() + size" to emptyList()
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("int!stra", 100, "done20", 8))
    }

    @Test
    fun `should execute declarations moved out of the script body reading constant properties`() = runTest {
        // given
        val scripts = listOf(
            "val taxRate = 0.2\nclass Order(val amount: Double) { fun total() = amount * (1 + taxRate) + FEE; companion object { const val FEE = 1.0 } }\nOrder(10.0).total()",
            "val unit = \"cm\"\nsealed class Shape\nclass Sq(val s: Int) : Shape() { fun d() = \"${'$'}s${'$'}unit\" }\nSq(2).d()",
            "private const val TAX = 0.5\nsealed class Item { abstract val price: Double; fun taxed() = price * (1 + TAX) }\nclass Book(override val price: Double) : Item()\nBook(10.0).taxed()",
            // the member of the receiver, not the local property of the same name
            "val name = \"script\"\ndata class User(val name: String)\ninline fun User.describe() = \"user \" + name\nUser(\"alice\").describe()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(13.0, "2cm", 15.0, "user alice"))
    }

    @Test
    fun `should execute function reading only constants called by declarations moved out of the script body`() = runTest {
        // given
        val script = """
            val RATE = 0.2
            fun tax(x: Double) = x * RATE
            sealed class Item { abstract val price: Double; fun total() = price + tax(price) }
            class Book(override val price: Double) : Item()
            Book(10.0).total()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Value)?.value == 12.0)
    }

    @Test
    fun `should execute functions and classes reading only constants called before their declaration, or by declarations moved out of the script body, like in the script`() = runTest {
        // given
        val scripts = listOf(
            "val prefix = \">\"\nval formatted = format(\"x\")\nfun format(text: String) = prefix + text\nformatted" to emptyList(),
            "val base = 10\nval scaled = Scaler().scale(2)\nclass Scaler { fun scale(x: Int) = x * base }\nscaled" to emptyList(),
            "val base: Int? = 1\nval r = base ?: compute()\nval compute = 2\nfun compute() = base\nr" to emptyList(),
            "val p = \"!\"\nval log = mutableListOf<String>()\nlog += x(\"a\")\nfun x(s: String) = \"local\" + s + p\nlog" to listOf(dependency<(Any) -> String>("x") { "dep$it" }),
            "val p = 1\nval g = ::x\nfun x() = p + 10\ng()" to listOf(dependency<() -> Int>("x") { 2 }),
            "val prefix = \"local:\"\nfun log(message: String) = prefix + message\nsealed interface Check\nclass Below : Check { fun ok() = log(\"hi\") }\nBelow().ok()" to listOf(dependency<(String) -> String>("log") { "dep:$it" }),
            "val prefix = \">\"\nfun log(message: String) = prefix + message\nsealed interface S\nclass T : S { fun f() = log(\"hi\") }\nT().f()" to listOf(dependency("log", Logger())),
            "val factor = 10\nfun List<Int>.sum(): Int = fold(0) { a, b -> a + b * factor }\nsealed class Shape { fun total() = listOf(1, 2).sum() }\nclass Circle : Shape()\nCircle().total()" to emptyList(),
            "val k = 0\ninfix fun Int.until(to: Int) = listOf(this, to, k)\nsealed class S { fun f() = 0 until 20 }\nclass T : S()\nT().f()" to emptyList()
        )
        val libraryFunction = "val strict = true\nfun check(flag: Boolean) { if (strict && !flag) throw IllegalStateException(\"strict\") }\nsealed interface S\nclass T : S { fun f() = check(false) }\nT().f()"
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script, dependencies) -> executor.execute(script, dependencies) }
        val libraryFunctionResult = executor.execute(libraryFunction)

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(">x", 20, 1, listOf("locala!"), 11, "local:hi", ">hi", 30, listOf(0, 20, 0)))
        // the function of the script, not the library one
        assert((libraryFunctionResult as? ScriptResult.Error)?.message?.lines()?.first() == "java.lang.IllegalStateException: strict")
    }

    @Test
    fun `should resolve overloads called before their declaration, also reading a constant, differently than the script`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\"\nval r = show(1)\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nr + show(1)",
            "val r = show(1)\nfun show(x: Any) = \"any\"\nfun show(x: Int) = \"int\"\nr + show(1)"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        // the overloads called before their declaration are moved, also when reading a constant, like without it,
        // while a regular script returns "intany!" and "intany"
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("intint", "intint"))
    }

    @Test
    fun `should execute declarations moved out of the script body reading constants of types of local classes moved as well`() = runTest {
        // given
        val scripts = listOf(
            "class Foo\nval x: Foo? = null\nsealed interface S\nclass T : S { fun f() = x; fun g() = Foo() }\nT().f()",
            "class Foo\nval x: Foo? = null\nsealed class S { fun g(): Foo? = x }\nclass T : S()\nT().g()",
            "data class Foo(val a: Int)\nval x: Foo? = null\nsealed class S { fun g() = \"g\" + x + h() }\nclass T : S()\nfun h() = Foo(1)\nT().g()",
            "class Foo\ntypealias F = Foo\nval x: F? = null\nsealed class S { fun g(): F? = x }\nclass T : S()\nT().g()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value.toString() } == listOf("null", "null", "gnullFoo(a=1)", "null"))
        assert(results.all { it is ScriptResult.Value })
    }

    @Test
    fun `should declare function reading constant of type of local class preceding statement calling it`() = runTest {
        // given
        val script = """
            class Foo
            val x: Foo? = null
            val s = describe()
            fun describe() = "d" + x
            s
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // staying local with the constant, as the class is not visible to them otherwise, so hoisted, see ScriptSource.Parsed.hoisted
        result should {
            be<ScriptResult.Value>()
            have(value == "dnull")
        }
    }

    @Test
    fun `should resolve calls preceding function reading constant on receivers of lambdas like in the script`() = runTest {
        // given
        val scripts = listOf(
            "val k = \"!\"\nval s = buildString { append(\"x\") }\nfun append(t: String) = t + k\nappend(s)",
            "val k = 10\nval r = with(listOf(1, 2)) { sum() }\nfun sum() = k\nr",
            "val k = \"!\"\nval sb = StringBuilder().apply { append(\"x\") }\nfun append(t: String) = t + k\nsb.toString()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("x!", 3, "x"))
    }

    @Test
    fun `should keep function reading constant local when called before its declaration where receiver might resolve it, also when called by declarations moved out of the script body`() = runTest {
        // given
        val script = """
            val k = "!"
            val s = buildString { append("x") }
            fun append(t: String) = t + k
            sealed class S { fun f() = append("y") }
            class T : S()
            s + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // not silently resolving `append("x")` to the function, as a regular script returns "xy!"
        assert((result as? ScriptResult.Error)?.phase == ExecutionPhase.COMPILATION)
    }

    @Test
    fun `should execute function reading constant called before its declaration by declarations moved out of the script body`() = runTest {
        // given
        val scripts = listOf(
            "val k = \"!\"\nsealed class S { fun f() = tax(1) }\nclass T : S()\nfun tax(x: Int) = x.toString() + k\nT().f()",
            "val k = \"!\"\nsealed class S { fun f() = buildString { append(\"x\") } }\nclass T : S()\nfun append(t: String) = t + k\nT().f()",
            "val k = \"!\"\nopen class Base { fun tag(t: String) = \"base\" + t }\nsealed class S : Base() { fun f() = tag(\"x\") }\nclass T : S()\nfun tag(t: String) = t + k\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        // like in the script, the receivers resolve the calls preceding the declarations
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("1!", "x", "basex"))
    }

    @Test
    fun `should execute function reading constant of type of local class`() = runTest {
        // given
        val script = """
            class Foo
            val x: Foo? = null
            val s = buildString { append("x") }
            fun append(t: String) = t + x
            append(s)
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Value)?.value == "xnull")
    }

    @Test
    fun `should not move function reading variable reassigned by the script body`() = runTest {
        // given
        val script = """
            var rate = 0.2
            fun tax(x: Double) = x * rate
            sealed class Item { fun total() = tax(10.0) }
            class Book : Item()
            rate = 0.5
            Book().total()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Error)?.message?.lines()?.first() == "script.kts:3:35: error: Unresolved reference 'tax'.")
    }

    @Test
    fun `should report error at companion object capturing the script class instance`() = runTest {
        // given
        val script = """
            class User(val id: Int) { companion object { fun create() = User(input.length) } }
            User.create().id
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:27: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class User(val id: Int) { <error>companion</error> object { fun create() = User(input.length) } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error only at companion objects capturing the script class instance`() = runTest {
        // given
        val script = """
            class Plain(val id: Int) { companion object { fun create() = Plain(1) } }
            class User(val id: Int) { companion object { fun create() = User(input.length) } }
            class Admin(val id: Int) {
                companion object Factory { fun create() = Admin(input.length) }
            }
            User.create().id + Admin.create().id + Plain.create().id
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:27: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class User(val id: Int) { <error>companion</error> object { fun create() = User(input.length) } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:4:22: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  |     companion object <error>Factory</error> { fun create() = Admin(input.length) }
                  hint: 'Factory' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at each companion object capturing the script class instance`() = runTest {
        // given
        val script = """
            class User(val id: Int) { companion object { fun create() = User(input.length) } }
            class Admin(val id: Int) {
                companion object { fun create() = Admin(input.length) }
            }
            User.create().id + Admin.create().id
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:27: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class User(val id: Int) { <error>companion</error> object { fun create() = User(input.length) } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:3:5: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  |     <error>companion</error> object { fun create() = Admin(input.length) }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at object named like a companion object, both capturing the script class instance`() = runTest {
        // given
        val script = """
            class A { companion object Factory { fun f() = input.length } }
            object Factory { fun f() = input.length }
            Factory.f() + A.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:28: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class A { companion object <error>Factory</error> { fun f() = input.length } }
                  hint: 'Factory' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:2:8: error: Object 'Factory' captures the script class instance. Try to use class or anonymous object instead.
                  | object <error>Factory</error> { fun f() = input.length }
                  hint: 'Factory' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at companion object capturing the script class instance, not at the one containing a capturing object`() = runTest {
        // given
        val script = """
            class A { companion object { object Inner { fun f() = input.length } } }
            class B { companion object { fun f() = input.length } }
            A.Companion.Inner.f() + B.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:37: error: Object 'Inner' captures the script class instance. Try to use class or anonymous object instead.
                  | class A { companion object { object <error>Inner</error> { fun f() = input.length } } }
                  hint: 'Inner' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:2:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class B { <error>companion</error> object { fun f() = input.length } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at each companion object capturing the script class instance, also when calling own member named like script function`() = runTest {
        // given
        val script = """
            fun helper() = 1
            class A { companion object { fun helper() = 2; fun f() = helper() } }
            class B { companion object { fun f() = input.length } }
            class C { companion object { fun f() = 3 } }
            A.f() + B.f() + C.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class A { <error>companion</error> object { fun helper() = 2; fun f() = helper() } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:3:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.
                  | class B { <error>companion</error> object { fun f() = input.length } }
                  hint: 'Companion' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at companion objects capturing the script class instance, not at the one referring to member inherited from class of the script`() = runTest {
        // given
        val script = """
            open class Base { val input = "z" }
            class A { companion object : Base() { fun f() = input.length } }
            class B { companion object { fun f() = input.length } }
            class C { companion object { fun f() = input.length } }
            A.f() + B.f() + C.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            assert(message.lines().filter { "captures the script class instance" in it } == listOf(
                "script.kts:3:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.",
                "script.kts:4:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead."
            ))
        }
    }

    @Test
    fun `should report error at distinct companion objects, when more of them refer to names of the script class members than capture it`() = runTest {
        // given
        val script = """
            class A { companion object : java.util.ArrayList<String>() { fun f() = size } }
            class B { companion object { fun f() = size } }
            class C { companion object { fun f() = size } }
            A.f() + B.f() + C.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("size", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            // the member inherited from a class of a library is not known, so they are told by their order
            assert(message.lines().filter { "captures the script class instance" in it } == listOf(
                "script.kts:1:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.",
                "script.kts:2:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead."
            ))
        }
    }

    @Test
    fun `should report error at companion objects capturing the script class instance, also when referring to name of private member of their supertype`() = runTest {
        // given
        val script = """
            open class Base { private val input = "z" }
            class Z { companion object { fun f() = 1 } }
            class A { companion object : Base() { fun f() = input.length } }
            class B { companion object { fun f() = input.length } }
            A.f() + B.f() + Z.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            assert(message.lines().filter { "captures the script class instance" in it } == listOf(
                "script.kts:3:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead.",
                "script.kts:4:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead."
            ))
        }
    }

    @Test
    fun `should report error at companion object capturing the script class instance in supertype constructor argument named like inherited member`() = runTest {
        // given
        val script = """
            open class Base(val s: String) { val input = "z" }
            class Z { companion object { fun f() = 1 } }
            class A { companion object : Base(input) { fun f() = 2 } }
            A.f() + Z.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            assert(message.lines().filter { "captures the script class instance" in it } == listOf(
                "script.kts:3:11: error: Object 'Companion' captures the script class instance. Try to use class or anonymous object instead."
            ))
        }
    }

    @Test
    fun `should report error at nested classes capturing the script class instance, also by qualified call of script extension named like parameter`() = runTest {
        // given
        val script = """
            fun String.up() = this + input
            class P { class N { fun h() = 1 } }
            class A { class N { fun f(up: Int) = "x".up() } }
            class B { class N { fun g() = input } }
            P.N().h().toString() + A.N().f(1) + B.N().g()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            assert(message.lines().filter { "captures the script class instance" in it }.map { it.substringBefore(" error:") } == listOf(
                "script.kts:3:17:",
                "script.kts:4:17:"
            ))
        }
    }

    @Test
    fun `should report error at enum entry capturing the script class instance, and at its enum class`() = runTest {
        // given
        val script = """
            enum class E { X { override fun f() = input }; abstract fun f(): String }
            E.X.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:12: error: Enum class 'E' captures the script class instance. Try to use class or anonymous object instead.
                  | enum class <error>E</error> { X { override fun f() = input }; abstract fun f(): String }
                  hint: 'E' is compiled outside of the script body, where an enum class cannot access the dependencies, nor the functions of the script - pass them as parameters.

                script.kts:1:16: error: Enum entry 'X' captures the script class instance. Try to use class or anonymous object instead.
                  | enum class E { <error>X</error> { override fun f() = input }; abstract fun f(): String }
                  hint: 'X' is compiled outside of the script body, where an enum entry cannot access the dependencies, nor the functions of the script - pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at nested class capturing the script class instance, not at the one with parameter named like script extension`() = runTest {
        // given
        val script = """
            fun String.up() = this + input
            class A { class N { fun f(up: Int) = up } }
            class B { class N { fun g() = input } }
            A.N().f(1).toString() + B.N().g()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("input", "abc")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:17: error: Nested class 'N' captures the script class instance. Try to use the explicit 'inner' modifier for both the nested 'N' and outer 'B'.
                  | class B { class <error>N</error> { fun g() = input } }
                  hint: 'N' is compiled outside of the script body, where a nested class cannot access the dependencies, nor the functions of the script - pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at each class capturing the script class instance named like one of another kind`() = runTest {
        // given
        val scripts = listOf(
            """
            class A { class Factory { fun f() = input } }
            object Factory { fun g() = input }
            A.Factory().f() + Factory.g()
            """,
            """
            class P { object A { fun f() = input } }
            class Q { class A { fun g() = input } }
            P.A.f() + Q.A().g()
            """
        ).map { it.trimIndent() }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it, listOf(dependency("input", "abc"))) }

        // then
        results[0] should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:17: error: Nested class 'Factory' captures the script class instance. Try to use the explicit 'inner' modifier for both the nested 'Factory' and outer 'A'.
                  | class A { class <error>Factory</error> { fun f() = input } }
                  hint: 'Factory' is compiled outside of the script body, where a nested class cannot access the dependencies, nor the functions of the script - pass them as parameters.

                script.kts:2:8: error: Object 'Factory' captures the script class instance. Try to use class or anonymous object instead.
                  | object <error>Factory</error> { fun g() = input }
                  hint: 'Factory' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.
            """.trimIndent()
        }
        results[1] should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:1:18: error: Object 'A' captures the script class instance. Try to use class or anonymous object instead.
                  | class P { object <error>A</error> { fun f() = input } }
                  hint: 'A' is compiled outside of the script body, where an object cannot access the dependencies, nor the functions of the script - declare it as a class, or pass them as parameters.

                script.kts:2:17: error: Nested class 'A' captures the script class instance. Try to use the explicit 'inner' modifier for both the nested 'A' and outer 'Q'.
                  | class Q { class <error>A</error> { fun g() = input } }
                  hint: 'A' is compiled outside of the script body, where a nested class cannot access the dependencies, nor the functions of the script - pass them as parameters.
            """.trimIndent()
        }
    }

    @Test
    fun `should report references to declarations moved out of the script body resolved to its coroutine scope instead`() = runTest {
        // given
        val scripts = listOf(
            "cancel()\nfun cancel() { println(\"order cancelled\") }\n\"done\"",
            "ensureActive()\nfun ensureActive() { println(\"mine\") }\n1",
            "val isActive get() = false\nisActive",
            "val coroutineContext get() = \"mine\"\ncoroutineContext"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should call declarations moved out of the script body named like functions of its coroutine scope when they apply, and not move constants named like them`() = runTest {
        // given
        val scripts = listOf(
            "val r = cancel(\"too late\")\nfun cancel(reason: String) = \"cancelled: \" + reason\nr",
            "val isActive = false\ninline fun <reified T> status() = if (isActive) \"on\" else \"off\"\nstatus<Int>() + isActive",
            "val isActive = false\nval status get() = if (isActive) \"on\" else \"off\"\nstatus + isActive",
            "val isActive = false\ninline fun status() = if (isActive) \"on\" else \"off\"\nstatus() + isActive"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert((results[0] as? ScriptResult.Value)?.value == "cancelled: too late")
        // not moved, unlike other constants, as the script body would refer to its coroutine scope instead, so not visible to the getter
        assert("script.kts:2:39: error: Unresolved reference 'isActive'." in (results[1] as ScriptResult.Error).message.lines())
        // unless the property, or the inline function, stays local, see `should execute scripts with property accessors reading local properties`
        assert(results.drop(2).map { (it as? ScriptResult.Value)?.value } == listOf("offfalse", "offfalse"))
    }

    @Test
    fun `should execute script with inline function reading local property`() = runTest {
        // given
        val script = """
            val log = mutableListOf<String>()
            inline fun logged(block: () -> Unit) { log += "start"; block() }
            logged { }
            log
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == listOf("start"))
        }
    }

    @Test
    fun `should execute scripts with inline functions reading local properties`() = runTest {
        // given
        val scripts = listOf(
            "val log = mutableListOf<String>()\nfun helper() { log += \"h\" }\ninline fun logged(block: () -> Unit) { helper(); block() }\nlogged { }\nlog",
            "val log = mutableListOf<String>()\ninline fun <T> logged(name: String, crossinline block: () -> T, noinline after: () -> Unit = { }): T {\n    log += name\n    return block().also { after() }\n}\n" +
                    "val r = logged(\"a\", { for (i in 1..5) { if (i == 2) break }; if (log.size > 5) return@logged 0; 7 }) { log += \"after\" }\nr.toString() + log",
            "val items = mutableListOf(1, 2)\n@Suppress(\"NOTHING_TO_INLINE\") private inline fun total() = items.sum()\nitems += 3\ntotal()",
            "val log = mutableListOf<String>()\nsuspend inline fun step(block: suspend () -> Unit) { log += \"s\"; block() }\nstep { kotlinx.coroutines.delay(1) }\nlog"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(listOf("h"), "7[a, after]", 6, listOf("s")))
    }

    @Test
    fun `should report inline functions reading local property which would be resolved differently if local`() = runTest {
        // given
        val scripts = listOf(
            // `this` of the script block, the coroutine
            "val log = mutableListOf<String>()\ninline fun tag() = \"\$this\" + log.size\ntag()",
            // the overload staying local would hide the moved one
            "val log = mutableListOf<String>()\ninline fun f(x: Any): String { log += \"any\"; return \"any\" }\ninline fun f(x: String): String = \"string\"\nf(\"a\") + log",
            // called before its declaration, resolved to the function of the coroutine scope instead
            "val log = mutableListOf<String>()\nval r = run { \"std\" }\ninline fun run(block: () -> String): String { log += \"mine\"; return block() }\nr + log",
            // called where a receiver resolves it in the script, like `run` of the class
            "val log = mutableListOf<String>()\ninline fun run(block: () -> String): String { log += \"mine\"; return block() }\nclass A { fun go() = run { \"a\" } }\nA().go() + log",
            // an inline function cannot be recursive
            "val log = mutableListOf<String>()\ninline fun count(n: Int): Int = if (n == 0) log.size else count(n - 1)\ncount(2)"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
    }

    @Test
    fun `should report inline function reading local property which cannot be local`() = runTest {
        // given
        val script = """
            val log = mutableListOf<String>()
            inline fun logged(block: () -> Unit) { log += "start"; block() }
            fun outer(): Int { logged { return 5 }; return 1 }
            outer()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:40: error: Unresolved reference 'log'.
                  | inline fun logged(block: () -> Unit) { <error>log</error> += "start"; block() }
                  hint: 'logged' is an inline function compiled outside of the script body, where 'log' (line 1) is not visible - pass it as a parameter, or declare it within 'logged'.
            """.trimIndent()
        }
    }

    @Test
    fun `should report suspending call in lambda of inline function reading local property`() = runTest {
        // given
        val script = """
            val log = mutableListOf<String>()
            inline fun logged(block: () -> Unit) { log += "start"; block() }
            logged { kotlinx.coroutines.delay(1) }
            log
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:3:29: error: Suspension functions can only be called within coroutine body.
                  | logged { kotlinx.coroutines.<error>delay</error>(1) }
                  hint: 'logged' is compiled without 'inline', as it reads the local values of the script body, so its lambdas cannot suspend - pass the local values to 'logged' as parameters to keep it inline.
            """.trimIndent()
        }
    }

    @Test
    fun `should execute scripts calling functions of the coroutine scope next to moved declarations of their names which do not apply`() = runTest {
        // given
        val scripts = listOf(
            "val a = run(3)\nval b = run { 5 }\nfun run(steps: Int) = steps * 2\na + b",
            "class Robot(val n: String)\nval r = Robot(\"r\")\nval x = r.launch()\nval job = launch { }\njob.join()\nfun Robot.launch() = \"go \" + n\nx"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(11, "go r"))
    }

    @Test
    fun `should move operator functions referred to by operators`() = runTest {
        // given
        val scripts = listOf(
            "val p = Point(1, 2) + Point(3, 4)\ndata class Point(val x: Int, val y: Int)\noperator fun Point.plus(o: Point) = Point(x + o.x, y + o.y)\np.toString()",
            "data class Money(val c: Int)\noperator fun Money.plus(o: Money) = Money(c + o.c)\nval r = sum(Money(1), Money(2))\nfun sum(a: Money, b: Money) = a + b\nr.toString()",
            "class Grid\nval g = Grid()\nval v = g[1]\noperator fun Grid.get(i: Int) = i * 10\nv",
            "val l = mutableListOf<Int>()\nfor (i in Countdown(3)) l += i\nclass Countdown(val n: Int)\noperator fun Countdown.iterator() = (n downTo 1).iterator()\nl"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("Point(x=4, y=6)", "Money(c=3)", 10, listOf(3, 2, 1)))
    }

    @Test
    fun `should report call of local property shadowing moved function named like function of the coroutine scope once`() = runTest {
        // given
        val script = """
            val cancel = { r: String -> "prop " + r }
            val x = cancel("a")
            fun cancel(reason: String) = "fun " + reason
            x
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:2:9: error: 'cancel' refers to the property of the script body declared at line 1, instead of the function declared at line 3, which a regular script calls.
                  | val x = <error>cancel</error>("a")
                  hint: rename the property or the function.
            """.trimIndent()
        }
    }

    @Test
    fun `should keep extensions named like operators in the script body, and the nested coroutine scopes`() = runTest {
        // given
        val scripts = listOf(
            "val xs = listOf(1, 2, 3)\nval first = xs[0]\nclass Grid\nfun Grid.get(i: Int) = async { i * 10 }\nGrid().get(2).await() + first",
            "var total = 0\nfor (i in 1..3) total += i\nclass Sim\nfun Sim.next() = if (isActive) 1 else 0\ntotal + Sim().next()",
            "val found = 2 in listOf(1, 2)\nclass Area\nfun Area.contains(x: Int): Boolean { launch { }; return x > 0 }\nfound && Area().contains(1)",
            "coroutineScope { launch { println(\"inner\") } }\nfun launch(block: () -> Unit) { block() }\n\"done\""
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(21, 7, true, "done"))
    }

    @Test
    fun `should report references within lambdas resolved to the coroutine scope of the script body instead of moved declarations`() = runTest {
        // given
        val scripts = listOf(
            "val r = run { launch { } }\nfun launch(block: () -> Unit): String = \"mine\"\nr",
            "val r = listOf(1).map { cancel() }\nfun cancel(): String = \"mine\"\nr",
            "val r = \"x\".let { isActive }\nval isActive get() = \"mine\"\nr",
            "val r = with(this) { launch { } }\nfun launch(block: () -> Unit): String = \"mine\"\nr",
            // the type alias declared after the copies would not be visible to them
            "val r = launch { }\ntypealias Block = () -> Unit\nfun launch(block: Block): String = \"mine\"\nr",
            "val r = run { launch { } }\ntypealias Block = () -> Unit\nfun launch(block: Block): String = \"mine\"\nr",
            "val r = run { launch { } }\nval launch get() = { b: () -> Unit -> \"mine\" }\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.phase } == List(scripts.size) { ExecutionPhase.COMPILATION })
        results[0] should {
            be<ScriptResult.Error>()
            message sameAs """
                script.kts:1:15: error: 'launch' refers to the coroutine scope of the script body, instead of the function declared at line 2, which is compiled outside of the script body.
                  | val r = run { <error>launch</error> { } }
                  hint: rename the function.
            """.trimIndent()
        }
    }

    @Test
    fun `should execute references within nested coroutine scopes next to moved declarations of their names`() = runTest {
        // given
        val scripts = listOf(
            "val r = coroutineScope { launch { } }\nfun launch(block: () -> Unit): String = \"mine\"\nr is Job",
            "val r = withContext(Dispatchers.Default) { async { 1 } }.await()\nfun async(block: () -> Int): Int = 2\nr",
            "val r = supervisorScope { run { isActive } }\nval isActive get() = \"mine\"\nr",
            "val r = Job().apply { cancel() }.isCancelled\nfun cancel(): String = \"mine\"\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(true, 1, true, true))
    }

    @Test
    fun `should execute references within anonymous functions with receivers next to moved declarations of their names`() = runTest {
        // given
        val scripts = listOf(
            "val r = with(Job(), fun Job.(): Any = isActive)\nval isActive get() = \"mine\"\nr",
            "val r = (fun CoroutineScope.(): Any = isActive)(CoroutineScope(Job()))\nval isActive get() = \"mine\"\nr",
            "val r = Job().run(fun Job.(): Any { cancel(); return isCancelled })\nfun cancel(): String = \"mine\"\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(true, true, true))
    }

    @Test
    fun `should report references within anonymous functions resolved to the coroutine scope of the script body instead of moved declarations`() = runTest {
        // given
        val scripts = listOf(
            "val r = with(\"a\", fun String.(): Any = isActive)\nval isActive get() = \"mine\"\nr",
            "val r = (fun(): Any = isActive)()\nval isActive get() = \"mine\"\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first()?.substringAfter("error: ") } == List(scripts.size) {
            "'isActive' refers to the coroutine scope of the script body, instead of the property declared at line 2, which is compiled outside of the script body."
        })
    }

    @Test
    fun `should report assignments resolved to the coroutine scope of the script body instead of moved properties`() = runTest {
        // given
        val scripts = listOf(
            "isActive = \"b\"\nvar isActive: String get() = \"a\"; set(v) {}\n1",
            "val r = listOf(1).map { isActive = \"b\"; 1 }\nvar isActive: String get() = \"a\"; set(v) {}\nr",
            "val r = listOf(1).map { isActive += \"b\"; 1 }\nvar isActive: String get() = \"a\"; set(v) {}\nr",
            // the increments and decrements, also within lambdas
            "isActive++\nvar isActive: Int get() = 1; set(v) {}\n1",
            "val r = listOf(1).map { --isActive; 1 }\nvar isActive: Int get() = 1; set(v) {}\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        // without the errors of the assignments to the property of the coroutine scope, like `'val' cannot be reassigned`
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.filter { line -> "error: " in line } } == listOf(1, 25, 25, 1, 27).map { column ->
            listOf(
                "script.kts:1:$column: error: 'isActive' refers to the coroutine scope of the script body, " +
                        "instead of the property declared at line 2, which is compiled outside of the script body."
            )
        })
    }

    @Test
    fun `should report assignment within lambda to value of its receiver next to moved property of its name as regular script`() = runTest {
        // given
        val script = "val r = with(object { val isActive = 1 }) { isActive = 2; isActive }\nvar isActive: String get() = \"a\"; set(v) {}\nr"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Error)?.message?.lines()?.first() == "script.kts:1:45: error: 'val' cannot be reassigned.")
    }

    @Test
    fun `should report references within lambdas of functions imported by the names of coroutine scope builders`() = runTest {
        // given
        val scripts = listOf(
            "import kotlin.run as launch\nval r = launch { isActive }\nval isActive get() = \"mine\"\nr",
            "import kotlin.run as coroutineScope\nval r = coroutineScope { isActive }\nval isActive get() = \"mine\"\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.first()?.substringAfter("error: ") } == List(scripts.size) {
            "'isActive' refers to the coroutine scope of the script body, instead of the property declared at line 3, which is compiled outside of the script body."
        })
    }

    @Test
    fun `should execute assignments within lambdas to properties of their receivers next to moved properties of their names`() = runTest {
        // given
        val script = "val r = with(object { var isActive = 1 }) { isActive = 2; isActive++; isActive }\nvar isActive: String get() = \"a\"; set(v) {}\nr"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Value)?.value == 3)
    }

    @Test
    fun `should report call within lambda resolved to the coroutine scope of the script body given the moved member of any of other return type`() = runTest {
        // given
        val script = """
            val r = listOf(1).map { cancel(toString()) }
            fun toString(): Int = 1
            fun cancel(x: Int): String = "mine"
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // `cancel(toString())` resolves to `cancel(message: String)` of the coroutine scope, while a regular script calls `cancel(x: Int)`
        assert((result as? ScriptResult.Error)?.message?.lines()?.filter { "error: " in it }?.map { it.substringBefore(" refers") } == listOf(
            "script.kts:1:25: error: 'cancel'",
            "script.kts:1:32: error: 'toString'"
        ))
    }

    @Test
    fun `should report calls resolved to the coroutine scope after comments and shadowed functions in the order of the script`() = runTest {
        // given
        val scripts = listOf(
            "/* intro\n */ launch { println(\"x\") }\nfun launch(block: () -> Unit) { println(\"mine\"); block() }\n\"done\"",
            "val a = cancel(\"z\")\nval cancel = { r: String -> \"prop \" + r }\nval x = cancel(\"a\")\nfun cancel(reason: String) = \"fun \" + reason\nx"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        val errorLines = results.map { result ->
            (result as? ScriptResult.Error)?.message?.lines()?.filter { it.startsWith("script.kts:") }?.map { it.substringBefore(": error") }
        }
        // line 1 of the second script calls the moved function already, unlike the coroutine scope, while line 3 calls the property
        assert(errorLines == listOf(listOf("script.kts:2:5"), listOf("script.kts:3:9")))
    }

    @Test
    fun `should report only the operator functions called by the operator`() = runTest {
        // given
        val script = """
            val scale = listOf(10).first()
            class Grid
            operator fun Grid.get(i: Int) = i * scale
            operator fun Grid.set(i: Int, v: Int) { }
            enum class E { A; fun f() = Grid()[1] }
            E.A.f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && "'get'" in result.message && "'set'" !in result.message)
    }

    @Test
    fun `should move operator functions of compound assignments, increments and for loops`() = runTest {
        // given
        val scripts = listOf(
            "class Grid { val cells = IntArray(4) }\nval grid = Grid()\ngrid[0] += 5\ngrid[1]++\nval r = grid[0] + grid[1]\noperator fun Grid.get(i: Int) = cells[i]\noperator fun Grid.set(i: Int, v: Int) { cells[i] = v }\nr",
            "class Counter(var n: Int)\nclass It(val c: Counter)\nvar s = 0\nfor (x in Counter(3)) s += x\noperator fun Counter.iterator() = It(this)\noperator fun It.hasNext() = c.n > 0\noperator fun It.next() = c.n--\ns"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(6, 6))
    }

    @Test
    fun `should hint at operator function of array access not visible in declaration moved out of the script body`() = runTest {
        // given
        val script = """
            val scale = listOf(10).first()
            class Grid
            operator fun Grid.get(i: Int) = i * scale
            val g get() = Grid().let { it[1] }
            g
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(
            (result as? ScriptResult.Error)?.message?.lines()?.first() ==
                    "script.kts:4:28: error: 'get' does not refer to the function of the script body declared at line 3, which is not visible in 'g'."
        )
    }

    @Test
    fun `should move functions using array access and loops next to operator functions accessing local properties`() = runTest {
        // given
        val scripts = listOf(
            "class Grid { val cells = IntArray(4) }\nval log = mutableListOf<Int>()\nval g = Grid()\nval r = first(g)\nfun first(g: Grid) = g[0] + 1\noperator fun Grid.get(i: Int) = cells[i]\noperator fun Grid.set(i: Int, v: Int) { log += i; cells[i] = v }\nr",
            "class Gen(var n: Int)\nval log = mutableListOf<Int>()\nval r = sum(listOf(1, 2, 3))\nfun sum(xs: List<Int>): Int { var t = 0; for (x in xs) t += x; return t }\noperator fun Gen.iterator() = this\noperator fun Gen.hasNext() = n > 0\noperator fun Gen.next(): Int { log += n; return n-- }\nr"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(1, 6))
    }

    @Test
    fun `should report each invisible operator function of array access once`() = runTest {
        // given
        val scripts = listOf(
            "val scale = listOf(10).first()\nclass Grid\noperator fun Grid.get(i: Int) = i * scale\noperator fun Grid.set(i: Int, v: Int) { println(scale) }\nval g get() = Grid().let { it[1] + 0 }\ng",
            "val scale = listOf(10).first()\nclass Grid\noperator fun Grid.get(i: Int) = i * scale\nval g get() = listOf(Grid()).let { it[0][1] }\ng"
        )
        val executor = ScriptExecutor()

        // when
        val messages = scripts.map { (executor.execute(it) as ScriptResult.Error).message }

        // then
        assert(messages.map { message -> message.lines().count { "does not refer to the function" in it } } == listOf(1, 1))
        assert(messages.all { "'set'" !in it })
    }

    @Test
    fun `should not hint at member operator of array access enclosing unresolved plain call of the same name`() = runTest {
        // given
        val script = """
            val scale = listOf(10).first()
            class M { operator fun get(i: Int) = i }
            class Grid
            operator fun Grid.get(i: Int) = i * scale
            val g get() = Grid().run { M()[get(1)] }
            g
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && "does not refer to the function" !in result.message)
    }

    @Test
    fun `should report invisible operator functions of delegates, typed destructuring entries and binary operators`() = runTest {
        // given
        val scripts = listOf(
            "import kotlin.reflect.KProperty\nval scale = listOf(10).first()\nclass Cfg : HashMap<String, Int>()\noperator fun Cfg.getValue(t: Any?, p: KProperty<*>): Int = scale\nenum class E { A; fun f(): Int { val v by Cfg().apply { put(\"v\", 1) }; return v } }\nE.A.f()" to "getValue",
            "class Row : ArrayList<Int>()\nval scale = listOf(10).first()\noperator fun Row.component1() = scale\nenum class E { A; fun f(): Int { val (a: Int, b) = Row().apply { add(1); add(2) }; return a + b } }\nE.A.f()" to "component1",
            "val scale = listOf(10).first()\nclass Grid(var n: Int = 1)\noperator fun Grid.plus(o: Int) = Grid(n + o + scale)\nenum class E { A; fun f() = (Grid() + 1).n }\nE.A.f()" to "plus"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script) -> executor.execute(script) }

        // then
        assert(results.zip(scripts).all { (result, script) ->
            result is ScriptResult.Error && "'${script.second}' does not refer to the function of the script body" in result.message
        })
    }

    @Test
    fun `should report invisible delegate conventions of any delegate expression`() = runTest {
        // given
        val members = "operator fun plus(o: Cfg) = this; operator fun unaryMinus() = this; operator fun not() = this; " +
                "infix fun with(o: Cfg) = this; operator fun rangeTo(o: Cfg) = this; operator fun invoke() = this"
        val declarations = "import kotlin.reflect.KProperty\nval scale = listOf(10).first()\nclass Cfg { $members }\n" +
                "class D(val n: Int) { operator fun getValue(t: Any?, p: KProperty<*>) = n }\nfun mk(block: () -> Unit) = Cfg()\nfun mkf(c: Cfg) = { c }\n"
        val operators = mapOf(
            "getValue" to "operator fun Cfg.getValue(t: Any?, p: KProperty<*>): Int = scale\n",
            "provideDelegate" to "operator fun Cfg.provideDelegate(t: Any?, p: KProperty<*>) = D(scale)\n"
        )
        val delegates = listOf(
            "cfg", "Cfg()", "(Cfg())", "Cfg().let { it }", "listOf(Cfg()).first()", "if (cfg is Cfg) Cfg() else cfg", "mk { }",
            "Cfg()!!", "cfg as Cfg", "null ?: cfg", "when { cfg is Cfg -> cfg else -> Cfg() }", "@Deprecated(\"x\") cfg", "l@ cfg", "mkf(cfg)()",
            "cfg + cfg", "-cfg", "!cfg", "cfg with cfg", "cfg..cfg", "(cfg)()", "(mkf(cfg))()", "@Suppress(\"x\") Cfg()",
            // the call of the same name, bound by name to the member of the enum class
            "mapOf(1 to cfg).getValue(1)", "l@ (mapOf(1 to cfg)).getValue(1)",
            // starting with the local of the same name, where the compiler reports the delegate convention as well
            "getValue[0]"
        )
        // the local named like the convention, only where the delegate expression starts with it
        fun locals(delegate: String) = if (delegate.startsWith("getValue")) "val getValue = listOf(cfg); " else ""
        val scripts = operators.flatMap { (name, operator) ->
            delegates.map { delegate ->
                "$declarations${operator}enum class E { A; fun getValue() = 0; fun f(): Int { val cfg = Cfg(); ${locals(delegate)}val v: Int by $delegate; return v } }\nE.A.f()" to name
            }
        } + delegates.map { delegate ->
            val variable = "class Cfg { $members; operator fun getValue(t: Any?, p: KProperty<*>) = 1 }\n" +
                    "operator fun Cfg.setValue(t: Any?, p: KProperty<*>, v: Int) { println(scale) }\n"
            "${declarations.replace("class Cfg { $members }\n", variable)}enum class E { A; fun getValue() = 0; fun f(): Int { val cfg = Cfg(); ${locals(delegate)}var v: Int by $delegate; v = 2; return v } }\nE.A.f()" to "setValue"
        }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { (script) -> executor.execute(script) }

        // then
        assert(results.zip(scripts).filterNot { (result, script) ->
            result is ScriptResult.Error && "'${script.second}' does not refer to the function of the script body" in result.message
        }.map { (_, script) -> script.second to script.first.substringAfter(" by ").substringBefore(";") }.isEmpty())
    }

    @Test
    fun `should not hint at delegate convention enclosing unresolved call of the same name`() = runTest {
        // given
        val call = "lazy { \"a\".getValue(null, Foo::v) }"
        val delegates = listOf(
            call, "$call!!", "$call as Lazy<Int>", "null ?: $call", "($call)", "if (scale > 0) $call else lazy { 1 }",
            "when { scale > 0 -> $call else -> lazy { 1 } }", "@Deprecated(\"x\") $call", "l@ $call", "mk(\"a\".getValue(null, Foo::v))()"
        )
        val scripts = delegates.map { delegate ->
            """
                import kotlin.reflect.KProperty
                val scale = listOf(10).first()
                operator fun String.getValue(t: Any?, p: KProperty<*>): Int = scale
                fun mk(n: Int) = { lazy { n } }
                object Foo {
                    fun getValue(n: Int) = n
                    val v: Int by $delegate
                }
                Foo.v
            """.trimIndent()
        }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.all { it is ScriptResult.Error && "does not refer to the function" !in it.message })
    }

    @Test
    fun `should report invisible delegate convention reported at callee of the same name`() = runTest {
        // given
        val script = """
            import kotlin.reflect.KProperty
            val scale = listOf(10).first()
            class Cfg
            operator fun Cfg.getValue(t: Any?, p: KProperty<*>): Int = scale
            fun getValue(c: Cfg): Cfg = c
            sealed class E { fun f(): Int { val cfg = Cfg(); val v: Int by getValue(cfg); return v } }
            class F : E()
            F().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && result.message.startsWith(
            "script.kts:6:61: error: 'getValue' does not refer to the function of the script body declared at line 4, which is not visible in 'E'.\n" +
                    "  | sealed class E { fun f(): Int { val cfg = Cfg(); val v: Int <error>by getValue(cfg)</error>; return v } }\n"
        ))
    }

    @Test
    fun `should move operator extensions of the iterator returned by a member`() = runTest {
        // given
        val script = """
            class Counter(var n: Int) { operator fun iterator() = this }
            operator fun Counter.hasNext() = n > 0
            operator fun Counter.next() = n--
            val r = total(Counter(3))
            fun total(c: Counter): Int { var s = 0; for (x in c) s += x; return s }
            r
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6)
        }
    }

    @Test
    fun `should report call of the script body resolved to local overload hiding the overload moved out of the script body`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: Any) = "any${'$'}p"
            fun show(x: Int) = "int"
            sealed interface S
            class T : S { fun f() = show(1) }
            show(1) + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:6:1: error: 'show' refers to the function of the script body declared at line 2, instead of its overload declared at line 3, which a regular script calls.
                  | <error>show</error>(1) + T().f()
                  hint: 'show' (line 3) is compiled outside of the script body, unlike its overload declared at line 2, which hides it there - rename one of them.
            """.trimIndent()
        }
    }

    @Test
    fun `should report calls resolved to local overloads hiding the overloads moved out of the script body within lambdas, local functions and callable references`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nsealed interface S\nclass T : S { fun f() = show(1) }\nval g = { show(1) }\ng() + T().f()",
            "val p = \"!\".trim()\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nsealed interface S\nclass T : S { fun f() = show(1) }\nfun h() = show(1)\nh() + T().f()",
            "val p = \"!\".trim()\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nsealed interface S\nclass T : S { fun f() = show(1) }\nlistOf(1).map(::show).toString() + T().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.firstOrNull() } == listOf(
            "script.kts:6:11: error: 'show' refers to the function of the script body declared at line 2, instead of its overload declared at line 3, which a regular script calls.",
            "script.kts:6:11: error: 'show' refers to the function of the script body declared at line 2, instead of its overload declared at line 3, which a regular script calls.",
            "script.kts:6:17: error: 'show' refers to the function of the script body declared at line 2, instead of its overload declared at line 3, which a regular script calls."
        ))
    }

    @Test
    fun `should report call of declaration moved out of the script body resolved to its overload instead of the more specific local one`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: Int) = "int${'$'}p"
            fun show(x: Any) = "any"
            sealed interface S
            class T : S { fun f() = show(1) }
            T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:5:25: error: 'show' does not refer to the function of the script body declared at line 2, which is not visible in 'T'.
                  | class T : S { fun f() = <error>show</error>(1) }
                  hint: 'T' is compiled outside of the script body, where 'show' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should execute calls of overloads split by moving some of them out of the script body when they resolve as in a regular script`() = runTest {
        // given
        val scripts = listOf(
            // the local overload does not apply
            "val p = \"!\".trim()\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nsealed interface S\nclass T : S { fun f() = show(1) }\nshow(\"a\") + T().f()",
            // the local overload is the most specific one
            "val p = \"!\".trim()\nfun show(x: Int) = \"int${'$'}p\"\nfun show(x: Any) = \"any\"\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nshow(1) + show(\"a\") + T().f()",
            // the moved overload is the most specific one for the moved declaration
            "val p = \"!\".trim()\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nsealed interface S\nclass T : S { fun f() = show(1) }\nT().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("any!int", "int!anyany", "int"))
    }

    @Test
    fun `should execute calls of local overloads of inferred types which a regular script prefers to the moved overloads of declared types`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nfun h() = show(1)\nh() + T().f()",
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nval r = show(1)\nr + T().f()",
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nshow(1) + T().f()",
            "val p = \"!\".trim()\nfun show(x: Int): Int = 1\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1).toString() }\nshow(\"a\") + show(2).toString() + T().f()",
            // the local overload of a declared type within a declaration of a declared type
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int${'$'}p\"\nfun show(x: Any) = \"any\"\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nfun h(): String = show(1)\nh() + T().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("any!int", "any!int", "any!int", "any!any!1", "int!any"))
    }

    @Test
    fun `should report calls of local overloads where a regular script prefers the moved overloads within declarations of declared or inferred types`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nval r: String = show(1)\nr + T().f()",
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nfun h(): String = show(1)\nh() + T().f()",
            // the moved overload of an inferred type within declarations of inferred types
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int${'$'}p\"\nfun show(x: Any) = \"any\"\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nfun h() = show(1)\nh() + T().f()",
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int${'$'}p\"\nfun show(x: Any) = \"any\"\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nclass U { fun k() = show(1) }\nU().k() + T().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.firstOrNull()?.substringBefore(": error") } == listOf(
            "script.kts:6:17", "script.kts:6:19", "script.kts:6:11", "script.kts:6:21"
        ))
    }

    @Test
    fun `should confirm calls of local overloads referring to type aliases within declarations of inferred types`() = runTest {
        // given
        val scripts = listOf(
            // the moved overload of an inferred type, which a regular script prefers
            "val p = \"!\".trim()\ntypealias N = Int\nfun show(x: Any) = \"any\"\nfun show(x: N): String = \"int\$p\"\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nfun h() = show(1)\nh() + T().f()",
            // the local overload of an inferred type, which a regular script prefers
            "val p = \"!\".trim()\ntypealias N = Int\nfun show(x: N): String = \"int\"\nfun show(x: Any) = \"any\$p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\nfun h() = show(1)\nh() + T().f()"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { it.valueOrMessage.toString().lines().first() } == listOf(
            "script.kts:7:11: error: 'show' refers to the function of the script body declared at line 4, instead of its overload declared at line 3, which a regular script calls.",
            "any!int"
        ))
    }

    @Test
    fun `should report calls of local overloads referring to type aliases declared later within declarations of inferred types`() = runTest {
        // given
        // the local overload, which the type alias declared after it is visible to only in the script block, does not apply in a regular script
        val script = "val p = \"!\".trim()\nfun show(x: Any) = \"any\"\nfun show(x: N): String = \"int\$p\"\ntypealias N = Int\nsealed interface S\nclass T : S { fun f() = show(\"a\") }\nfun h() = show(1)\nh() + T().f()"
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Error)?.message?.lines()?.firstOrNull() ==
                "script.kts:7:11: error: 'show' refers to the function of the script body declared at line 3, instead of its overload declared at line 2, which a regular script calls.")
    }

    @Test
    fun `should report calls of local overloads within init blocks, constructors and default values where a regular script calls the moved overloads`() = runTest {
        // given
        val declarations = listOf(
            "class U { val v: String; init { v = show(1) } }\nU().v",
            "class U(val v: String = show(1))\nU().v",
            "class U { val v: String; constructor() { v = show(1) } }\nU().v"
        )
        val scripts = declarations.map { declaration ->
            "val p = \"!\".trim()\nfun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any${'$'}p\"\nsealed interface S\nclass T : S { fun f(): String = show(1) }\n$declaration + T().f()"
        }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Error)?.message?.lines()?.firstOrNull()?.substringBefore(": error") } == listOf(
            "script.kts:6:37", "script.kts:6:25", "script.kts:6:46"
        ))
    }

    @Test
    fun `should call the most specific of local overloads, while a regular script prefers the one of inferred type`() = runTest {
        // given
        val calls = listOf(
            "fun h() = show(1)\nh()",
            "val r = show(1)\nr",
            "show(1)",
            "listOf(1).map { show(it) }.single()"
        )
        val scripts = calls.map { call ->
            "fun show(x: Int): String = \"int\"\nfun show(x: Any) = \"any\"\n$call"
        }
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        // a known difference, a regular script returns "any" (#100)
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf("int", "int", "int", "int"))
    }

    @Test
    fun `should call local overload from default value of parameter of function of inferred type, while a regular script calls the most specific one`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: Int): String = "int"
            fun show(x: Any) = "any${'$'}p"
            sealed interface S
            class T : S { fun f(): String = show(1) }
            fun h(x: String = show(1)) = x
            h() + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        // a known difference, a regular script returns "intint" (#102)
        assert((result as? ScriptResult.Value)?.value == "any!int")
    }

    @Test
    fun `should report call resolved to local overload naming only the overload of the script body it calls`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: String) = "str${'$'}p"
            fun show(x: Any) = "any${'$'}p"
            fun show(x: Int): String = "int"
            sealed interface S
            class T : S { fun f(): String = show(1) }
            fun h(): String = show(1)
            h() + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:7:19: error: 'show' refers to the function of the script body declared at line 3, instead of its overload declared at line 4, which a regular script calls.
                  | fun h(): String = <error>show</error>(1)
                  hint: 'show' (line 4) is compiled outside of the script body, unlike its overload declared at line 3, which hides it there - rename one of them.
            """.trimIndent()
        }
    }

    @Test
    fun `should report call resolved to local overload naming all the overloads of the script body when the one it calls is not known`() = runTest {
        // given
        val script = """
            val p = "!".trim(); fun show(x: String) = "str${'$'}p"
            fun show(x: Int): String = "int"
            1; fun show(x: Any) = "any${'$'}p"
            sealed interface S
            class T : S { fun f(): String = show(1) }
            fun h(): String = show(1)
            h() + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert((result as? ScriptResult.Error)?.message?.lines()?.firstOrNull() ==
                "script.kts:6:19: error: 'show' refers to one of the functions of the script body declared at lines 1, 3, instead of its overload declared at line 2, which a regular script calls.")
    }

    @Test
    fun `should report call resolved to local overload naming the overload of the script body it calls and the moved one a regular script calls`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun show(x: String) = "str${'$'}p"
            fun show(x: Double) = "dbl"
            fun show(x: Any) = "any${'$'}p"
            fun show(x: Int) = "int"
            sealed interface S
            class T : S { fun f() = show(1) + show(1.0) }
            show(1) + T().f()
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                script.kts:8:1: error: 'show' refers to the function of the script body declared at line 4, instead of its overload declared at line 5, which a regular script calls.
                  | <error>show</error>(1) + T().f()
                  hint: 'show' (line 5) is compiled outside of the script body, unlike its overload declared at line 4, which hides it there - rename one of them.
            """.trimIndent()
        }
    }

    @Test
    fun `should report call resolved to the coroutine scope instead of moved function overloaded by local function once`() = runTest {
        // given
        val script = """
            val p = "!".trim()
            fun launch(block: () -> Unit): String = "mine"
            fun launch(x: Int) = "${'$'}p"
            sealed interface S
            class T : S { fun f() = launch { } }
            launch { }
            "done"
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        assert(result is ScriptResult.Error && result.message.lines().filter { it.startsWith("script.kts:") } == listOf(
            "script.kts:6:1: error: 'launch' refers to the coroutine scope of the script body, instead of the function declared at line 2, which is compiled outside of the script body."
        ))
    }

}


/** The value, or the message of the error, so that an assertion on the values shows why the script failed. */
private val ScriptResult.valueOrMessage: Any?
    get() = when (this) {
        is ScriptResult.Value -> value
        is ScriptResult.Error -> message
    }

/** Returns whatever the [compile] returns, or fails with whatever it throws, instead of compiling the script. */
class FakeCompiler(
    private val compile: suspend () -> ResultWithDiagnostics<CompiledScript>
) : JvmScriptCompiler() {
    override suspend fun invoke(
        script: SourceCode,
        scriptCompilationConfiguration: ScriptCompilationConfiguration
    ): ResultWithDiagnostics<CompiledScript> = compile()
}

/** Returns whatever the [evaluate] returns, instead of evaluating the script. */
class FakeEvaluator(
    private val evaluate: suspend () -> ResultWithDiagnostics<EvaluationResult>
) : ScriptEvaluator {
    override suspend fun invoke(
        compiledScript: CompiledScript,
        scriptEvaluationConfiguration: ScriptEvaluationConfiguration
    ): ResultWithDiagnostics<EvaluationResult> = evaluate()
}

/**
 * Records the threads resolving the classpath through it, like the scripting host does with `getResources`,
 * failing the first [failures] times with the [failure],
 * and counting down the [resolving] latch, before waiting for the [released] one.
 */
class ClasspathRecordingClassLoader(
    parent: ClassLoader,
    private var failures: Int = 0,
    private val resolving: CountDownLatch = CountDownLatch(0),
    private val released: CountDownLatch = CountDownLatch(0),
    private val failure: () -> Throwable = { IllegalStateException("classpath unavailable") }
) : ClassLoader(parent) {

    val threads: MutableList<Thread> = Collections.synchronizedList(mutableListOf())

    override fun getResources(name: String): Enumeration<URL> {
        threads += Thread.currentThread()
        resolving.countDown()
        released.await()
        synchronized(this) {
            if (failures > 0) {
                failures--
                throw failure()
            }
        }
        return super.getResources(name)
    }

}

/** Runs the [block] creating the executor with the [classLoader] as the context class loader. */
private fun <T> withContextClassLoader(
    classLoader: ClassLoader,
    block: () -> T
): T {
    val thread = Thread.currentThread()
    val contextClassLoader = thread.contextClassLoader
    thread.contextClassLoader = classLoader
    return try {
        block()
    } finally {
        thread.contextClassLoader = contextClassLoader
    }
}

/**
 * Runs the [block] on a thread of this executor, which is the other one, if the first one is busy,
 * awaiting it cancellably, unlike `withContext`, which would wait for the queued block to run.
 */
private suspend fun ExecutorService.onOtherThread(
    block: () -> Unit
) {
    val done = CompletableDeferred<Unit>()
    execute {
        block()
        done.complete(Unit)
    }
    done.await()
}