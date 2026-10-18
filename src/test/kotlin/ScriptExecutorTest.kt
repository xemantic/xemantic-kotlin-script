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

import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

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

    @Test
    fun `should use dependencies`() = runTest {
        // given
        val script = """
            injected.uppercase()
        """.trimIndent()
        val dependencies = listOf(
            ScriptExecutor.Dependency(
                name = "injected",
                type = String::class,
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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'asdf'.
                  at line 1
                  | <error>asdf</error>

                [ERROR] Unresolved reference 'foo'.
                  at line 2
                  | println(<error>foo</error>)

                [ERROR] Initializer type mismatch: expected 'Int', actual 'String'.
                  at line 3
                  | val number: Int <error>=</error> ""${'"'}

                </impediment>
                
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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'foo'.
                  at line 1
                  | import <error>foo</error>.Bar

                [ERROR] Unresolved reference 'buzz'.
                  at line 3
                  | <error>buzz</error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Expecting '"'.
                  at line 3
                  | and is missing a closing quote<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="EVALUATION">
                java.lang.Exception: must fail
                  at Script.invokeSuspend(script.kts:1)
                  | throw Exception("must fail")
                </impediment>

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
            message.substringBefore("at java.lang.Class.getDeclaredField(Class.java:").trimEnd() sameAs $$"""
                <impediment phase="EVALUATION">
                java.lang.Exception: must fail
                  at Script.invokeSuspend(script.kts:7)
                  |     throw Exception("must fail", e)
                Caused by: java.lang.NoSuchFieldException: nonExistentField
            """.trimIndent()
            message.substringAfter("at java.lang.Class.getDeclaredField(Class.java:").substringAfter("\n") sameAs $$"""
                |  at Script.invokeSuspend$willThrow(script.kts:2)
                |  |     String::class.java.getDeclaredField("nonExistentField")
                |  at Script.invokeSuspend(script.kts:5)
                |  |     willThrow(1)
                |</impediment>
                |
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
            message sameAs """
                <impediment phase="EVALUATION">
                java.lang.Exception: must fail
                  at Script.invokeSuspend(script.kts:1)
                  | throw Exception("must fail")
                </impediment>

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
                message sameAs """
                    <impediment phase="EVALUATION">
                    java.lang.Exception: inner failure
                      at Script.invokeSuspend(script.kts:1)
                      | throw Exception("inner failure")
                    </impediment>

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
    fun `should enclose error message in custom error tag`() = runTest {
        // given
        val script = "undefinedVariable"
        val executor = ScriptExecutor(errorTag = "golem:impediment")

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                <golem:impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'undefinedVariable'.
                  at line 1
                  | <error>undefinedVariable</error>

                </golem:impediment>

            """.trimIndent()
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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'undefinedVariable'.
                  at line 1
                  | <error>undefinedVariable</error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Expecting '}'.
                  at line 1
                  | {<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Expected annotation identifier after '@'.
                  at line 1
                  |    <error>@</error>

                [ERROR] Syntax error: Expecting an expression.
                  at line 1
                  |    @<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Expecting an expression.
                  at line 1
                  | val x =<error></error>
                
                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Unexpected symbol.
                  at line 1
                  | }<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Unexpected symbol.
                  at line 2
                  | }<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Unexpected symbol.
                  at line 1
                  | }<error>}</error>}

                [ERROR] Syntax error: Unexpected symbol.
                  at line 1
                  | }}<error>}</error>

                [ERROR] Syntax error: Unexpected symbol.
                  at line 1
                  | }}}<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'undefinedVariable'.
                  at line 3
                  | <error>undefinedVariable</error>

                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Syntax error: Unexpected symbol.
                  at line 1
                  | {}}<error></error>

                </impediment>

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
            message sameAs """
                <impediment phase="EVALUATION">
                java.lang.Exception: error on line 3
                  at Script.invokeSuspend(script.kts:3)
                  | throw Exception("error on line 3")
                </impediment>

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
            message sameAs """
                <impediment phase="COMPILATION">
                [ERROR] Unresolved reference 'undefinedUnicode变量'.
                  at line 3
                  | <error>undefinedUnicode变量</error>

                </impediment>

            """.trimIndent()
        }
    }

    @Test
    fun `should execute 50 scripts in a loop`() = runTest {
        // given
        val executor = ScriptExecutor()

        // when/then
        repeat(50) { i ->
            val script = $$"""
                val index = $$i
                "result-$index"
            """.trimIndent()

            val result = executor.execute(script)
            result should {
                be<ScriptResult.Value>()
                have(value == "result-$i")
            }
        }
    }

}
