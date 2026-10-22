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
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.jetbrains.kotlin.config.JvmTarget
import java.util.concurrent.Executors
import kotlin.reflect.full.createType
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

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
    fun `should hint to declare function before its use when it accesses local declarations of the script body`() = runTest {
        // given
        val script = """
            val prefix = ">"
            val formatted = format("x")
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
                script.kts:2:17: error: Unresolved reference 'format'.
                  | val formatted = <error>format</error>("x")
                  hint: 'format' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
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
    fun `should hint to declare function before its use when it accesses destructured local declarations`() = runTest {
        // given
        val script = """
            val (w, h) = 3 to 4
            println(area())
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
                script.kts:2:9: error: Unresolved reference 'area'.
                  | println(<error>area</error>())
                  hint: 'area' is declared at line 3, while the functions of the script body are visible only after their declaration - move it before line 2.
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
            val base = 10
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
            val greeting get() = "$prefix!"
            greeting
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("prefix", "dep")))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs $$"""
                script.kts:2:24: error: Unresolved reference 'prefix'.
                  | val greeting get() = "$<error>prefix</error>!"
                  hint: 'greeting' is compiled outside of the script body, where 'prefix' (line 1) is not visible - pass it as a parameter, or declare it within 'greeting'.
            """.trimIndent()
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
            val prefix = "local:"
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
                script.kts:4:34: error: Unresolved reference 'log'.
                  | class Below : Check { fun ok() = <error>log</error>("hi") }
                  hint: 'Below' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'Below'.
            """.trimIndent()
        }
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
            val prefix = ">"
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
                script.kts:4:25: error: Unresolved reference 'log'.
                  | class T : S { fun f() = <error>log</error>("hi") }
                  hint: 'T' is compiled outside of the script body, where 'log' (line 2) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
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
    fun `should report error at declaration moved out of the script body calling dependency with invoke operator of value class parameter shadowed by local function`() = runTest {
        // given
        val script = """
            import kotlin.time.Duration
            import kotlin.time.Duration.Companion.seconds
            val prefix = ">"
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
                script.kts:6:25: error: Unresolved reference 'log'.
                  | class T : S { fun f() = <error>log</error>(1.seconds) }
                  hint: 'T' is compiled outside of the script body, where 'log' (line 4) is not visible - pass it as a parameter, or declare it within 'T'.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint to declare function before its use when called after elvis operator`() = runTest {
        // given
        val script = """
            val base: Int? = 1
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
            val p = 1
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
    fun `should report error at declaration moved out of the script body calling local function named like library function`() = runTest {
        // given
        val script = """
            val strict = true
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
            val k = 1
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
            "val strict = true\nfun check(flag: Boolean, cb: (\n String\n) -> Unit = {}) { if (strict && !flag) throw IllegalStateException() }\nsealed interface S\nclass T : S { fun f() = check(false) }\nT().f()" to emptyList(),
            // local function which does not apply
            "fun greet(n: Int) = n\nval r = greet(\"Joe\")\nval greet = { s: String -> \"Hi ${'$'}s\" }\nr" to listOf(dependency<(String) -> String>("greet") { "DEP ${'$'}it" }),
            // local property of function type
            "val log = mutableListOf<String>()\nval print = { m: Any? -> log += m.toString() }\nsealed class Shape { fun show() = print(\"shape\") }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            // local extension function
            "val factor = 10\nfun List<Int>.sum(): Int = fold(0) { a, b -> a + b * factor }\nsealed class Shape { fun total() = listOf(1, 2).sum() }\nclass Circle : Shape()\nCircle().total()" to emptyList()
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
            "val k = 0\ninfix fun Int.until(to: Int) = listOf(this, to, k)\nsealed class S { fun f() = 0 until 20 }\nclass T : S()\nT().f()" to emptyList(),
            "val log = mutableListOf<String>()\nval repeat = { n: Int, block: (Int) -> Unit -> log += \"local\"; block(n) }\nsealed class Shape { fun show() = repeat(2) { } }\nclass Circle : Shape()\nCircle().show()\nlog" to emptyList(),
            "val suffix = \"!\"\nval trim: String.() -> String = { \"local\" + suffix }\nsealed class Shape { fun t() = \" a \".trim() }\nclass Circle : Shape()\nCircle().t()" to emptyList(),
            "typealias Q = Suppress\n@Q(\"DEPRECATION_ERROR\") fun show() = x\nval x = 2\nshow()" to listOf(dependency("x", 1)),
            "@[Suppress(\"UNUSED\")] fun show() = x\nval x = 2\nshow()" to listOf(dependency("x", 1)),
            "val p = \"!\"\nfun <T> greet(t: T): String where T : CharSequence, T : Comparable<T> = \"local\" + t + p\nsealed interface S\nclass Q : S { fun f() = greet(StringBuilder(\"x\")) }\nfun greet(t: Any) = \"moved\" + t\nQ().f()" to emptyList(),
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
        val callableReference = "val p = 1\nval g = ::x\nfun x() = p + 10\ng()"
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
            "val p = \"!\"\nval a = x(1)\nval b = x(\"a\")\nfun x(s: String) = \"local\" + s + p\na + b" to listOf(dependency<(Any) -> String>("x") { "dep$it" }),
            "val f = { s: String -> \"prop\" }\nval a = { f(1) }\nval b = f(\"a\")\nfun f(x: Int) = \"fun-int\"\nfun f(s: String) = \"fun-str\"\nb" to emptyList(),
            "val p = \"!\"\nval r = show(1)\nfun show(x: Any) = \"any${'$'}p\"\nfun show(x: Int) = \"int\"\nr + show(1)" to emptyList(),
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
    fun `should report wrongly resolved references after comments, of aliased function types and destructured names`() = runTest {
        // given
        val scripts = listOf(
            "val p = \"!\"\nval log = mutableListOf<String>()\n/* note\n */ log += x(\"a\")\nfun x(s: String) = \"local\" + s + p\nlog" to listOf(dependency<(Any) -> String>("x") { "dep$it" }),
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
            "val isActive = false\nval status get() = if (isActive) \"on\" else \"off\"\nstatus + isActive"
        )
        val executor = ScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert((results[0] as? ScriptResult.Value)?.value == "cancelled: too late")
        // not moved, unlike other constants, as the script body would refer to its coroutine scope instead, so not visible to the getter
        assert((results[1] as? ScriptResult.Error)?.message?.lines()?.first() == "script.kts:2:24: error: Unresolved reference 'isActive'.")
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

}
