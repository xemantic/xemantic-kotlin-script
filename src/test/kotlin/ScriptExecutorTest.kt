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
import kotlin.time.Duration.Companion.hours

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
        val script = """
            import kotlin.math.max
            
            val first = items.first()
            error("failure of ${'$'}first")
        """.trimIndent()
        val executor = ScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("items", listOf("foo"))))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: failure of foo
                  at <script body>(script.kts:4)
                  | error("failure of ${'$'}first")
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
                  hint: 'Greeter' is compiled outside of the script body, where an object cannot access the dependencies - declare it as a class, or pass the dependencies as parameters.
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
                  hint: 'Mood' is compiled outside of the script body, where an enum class cannot access the dependencies - pass the dependencies as parameters.
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
                  hint: 'Nested' is compiled outside of the script body, where a nested class cannot access the dependencies - pass the dependencies as parameters.
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

}
