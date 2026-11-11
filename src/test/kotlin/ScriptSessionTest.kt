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

import ch.qos.logback.classic.Level
import com.xemantic.kotlin.script.test.LogCollector
import com.xemantic.kotlin.script.test.collectGarbageUntilCleared
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

/** The hint at the compiler failing on `const val LIMIT` of `snippet1.kts`. */
private const val CONST_LIMIT_HINT = "  hint: 'LIMIT' is declared const in snippet1.kts, which a later snippet cannot read, as the K2 REPL compiler fails on it - declare it again public, without the const modifier."

class ScriptSessionTest {

    private val session = ScriptSession()

    @AfterTest
    fun close() {
        session.close()
    }

    @Test
    fun `should execute snippet returning a String`() = runTest {
        // when
        val result = session.execute("\"foo\"")

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
        val snippet = $$"""
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

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "The 42nd Fibonacci number is: 267914296")
        }
    }

    @Test
    fun `should serialize data class declared as Serializable by snippet`() = runTest {
        // given
        val snippet = """
            import kotlinx.serialization.Serializable
            import kotlinx.serialization.json.Json

            @Serializable
            data class Answer(val question: String, val answer: Int)

            val answer = Json { ignoreUnknownKeys = true }.decodeFromString<Answer>(
                "{\"question\": \"ultimate\", \"answer\": 42, \"unknown\": true}"
            )
            Json.encodeToString(answer.copy(answer = answer.answer * 2))
        """.trimIndent()

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == """{"question":"ultimate","answer":84}""")
        }
    }

    @Test
    fun `should serialize data class declared as Serializable by earlier snippet`() = runTest {
        // given
        session.execute("""
            import kotlinx.serialization.Serializable

            @Serializable
            data class Answer(val question: String, val answer: Int)
        """.trimIndent())

        // when
        val result = session.execute("""
            import kotlinx.serialization.json.Json

            Json.encodeToString(Answer("ultimate", 42))
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == """{"question":"ultimate","answer":42}""")
        }
    }

    @Test
    fun `should keep declarations between snippets`() = runTest {
        // given
        session.execute("""
            val base = 10
            fun scaled(x: Int) = x * base
            class Point(val x: Int, val y: Int)
        """.trimIndent())

        // when
        val result = session.execute("scaled(Point(1, 2).y)")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 20)
        }
    }

    @Test
    fun `should keep mutable state between snippets`() = runTest {
        // given
        session.execute("var counter = 0")
        session.execute("counter++")
        session.execute("counter += 5")

        // when
        val result = session.execute("counter")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6)
        }
    }

    @Test
    fun `should keep state of objects between snippets`() = runTest {
        // given
        session.execute("""
            object Registry { val items = mutableListOf<String>() }
            Registry.items += "a"
        """.trimIndent())
        session.execute("Registry.items += \"b\"")

        // when
        val result = session.execute("Registry.items.joinToString()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "a, b")
        }
    }

    @Test
    fun `should redeclare property in later snippet`() = runTest {
        // given
        session.execute("val x = 1")
        session.execute("val x = \"one\"")

        // when
        val result = session.execute("x")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "one")
        }
    }

    @Test
    fun `should keep imports between snippets`() = runTest {
        // given
        session.execute("import kotlin.math.sqrt")

        // when
        val result = session.execute("sqrt(16.0)")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 4.0)
        }
    }

    @Test
    fun `should call function declared later in the same snippet`() = runTest {
        // given
        val snippet = """
            fun a(x: Int) = b(x) + 1
            val factor = 3
            fun b(x: Int) = x * factor
            a(2)
        """.trimIndent()

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 7)
        }
    }

    @Test
    fun `should declare enum and sealed types`() = runTest {
        // given
        session.execute("""
            enum class Color { RED, GREEN }
            sealed interface Shape
            class Circle(val r: Int) : Shape
            class Square(val a: Int) : Shape
        """.trimIndent())

        // when
        val result = session.execute(
            $$"""
            fun describe(shape: Shape): String = when (shape) {
                is Circle -> "circle ${shape.r}"
                is Square -> "square ${shape.a}"
            }
            describe(Circle(2)) + " " + Color.GREEN
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "circle 2 GREEN")
        }
    }

    @Test
    fun `should return Unit for snippet without expression`() = runTest {
        // when
        val result = session.execute("val x = 1")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == Unit)
        }
    }

    @Test
    fun `should return Unit for empty snippet`() = runTest {
        // when
        val result = session.execute("")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == Unit)
        }
    }

    @Test
    fun `should return null`() = runTest {
        // when
        val result = session.execute("null")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == null)
        }
    }

    @Test
    fun `should report unresolved reference`() = runTest {
        // when
        val result = session.execute("undefinedVariable")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet1.kts:1:1: error: Unresolved reference 'undefinedVariable'.
                  | <error>undefinedVariable</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report error at the line of the snippet named by its number`() = runTest {
        // given
        session.execute("val x = 1")

        // when
        val result = session.execute("""
            val y = x + 1
            val z: String = y
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:15: error: Initializer type mismatch: expected 'String', actual 'Int'.
                  | val z: String <error>=</error> y
            """.trimIndent()
        }
    }

    @Test
    fun `should report multiline error`() = runTest {
        // when
        val result = session.execute(
            $$"""
            fun greet(name: String) = "Hello $name"
            greet(
                42
            )
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                snippet1.kts:3:5: error: Argument type mismatch: actual type is 'Int', but 'String' was expected.
                  |     <error>42</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report syntax error`() = runTest {
        // when
        val result = session.execute("val x = ")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("snippet1.kts:1:"))
            have("error: Syntax error" in message || "error: Expecting" in message)
        }
    }

    @Test
    fun `should not keep declarations of snippet which failed to compile`() = runTest {
        // given
        session.execute("""
            val good = 1
            val bad: String = 2
        """.trimIndent())

        // when
        val result = session.execute("good")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:1: error: Unresolved reference 'good'.
                  | <error>good</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should not keep declarations of snippet which failed to compile in a function`() = runTest {
        // given
        session.execute("""
            fun good() = 1
            val bad: String = 2
        """.trimIndent())
        session.execute("fun good() = 2")

        // when
        val result = session.execute("good()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should report internal failure of the compiler and continue the session`() = runTest {
        // given
        session.execute("val x = 41")

        // when
        // the backend crashes on an inline getter reading the coroutineContext of the script scope
        val result = session.execute("val context: Int inline get() = coroutineContext.hashCode()")
        val next = session.execute("x + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(
                message.lineSequence().first().startsWith(
                    "error: The compiler failed internally: java.lang.RuntimeException: Exception while generating code for "
                )
            )
            // the exception is the headline, not repeated as its own cause
            have(message.lines().count { "java.lang.RuntimeException" in it } == 1)
            // the IR dump of the message is indented to the column of the frames of the headline exception, 4 spaces,
            // so it does not break the structure of the report, where a nested exception starts at 2 spaces
            val irDump = message.lines().drop(1).takeWhile { !it.trimStart().startsWith("at ") }
            have(irDump.isNotEmpty())
            have(irDump.all { it.isEmpty() || it.startsWith("    ") })
        }
        next should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should report StackOverflowError of the compiler as a compilation failure and continue the session`() = runTest {
        // given
        session.execute("val x = 41")
        // nested deep enough to overflow the 16 MB stack of the session thread in milliseconds,
        // which the parser overflows at around 40 thousand levels, see SESSION_THREAD_STACK_SIZE
        val depth = 200_000
        val snippet = "val bad = 1\n" + "(".repeat(depth) + "1" + ")".repeat(depth)

        // when
        // a regression lets the StackOverflowError escape, which would abort the Gradle test executor
        val result = runCatching { session.execute(snippet) }
        val bad = session.execute("bad")
        val next = session.execute("x + 1")

        // then
        assert(result.exceptionOrNull() == null)
        result.getOrThrow() should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("error: The compiler failed internally: java.lang.StackOverflowError\n"))
            have(message.lines().count { "java.lang.StackOverflowError" in it } == 1)
        }
        bad should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:1: error: Unresolved reference 'bad'.
                  | <error>bad</error>
            """.trimIndent()
        }
        next should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should compile expression nested deeper than the default thread stack takes`() = runTest {
        // given
        // nested deep enough to overflow the default 1 MB thread stack, which the parser overflows at around 600 levels,
        // and shallow enough for the stack of the session thread, which takes tens of thousands of levels
        val depth = 5_000
        val snippet = "(".repeat(depth) + "1" + ")".repeat(depth)

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should hint declaring the type when class of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("class Plain")

        // when
        val result = session.execute("""
            fun isPlain(x: Any) = x is Plain
            isPlain(1)
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:28: error: Unresolved reference 'Plain'.
                  | fun isPlain(x: Any) = x is <error>Plain</error>
                  hint: 'Plain' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type, like `fun f(x: Any) = x is Plain` - declare the type, like `fun f(x: Any): Boolean = x is Plain`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val name = \"x\"")

        // when
        val result = session.execute("""
            fun getGreeting() = name.uppercase()
            getGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:21: error: Unresolved reference 'name'.
                  | fun getGreeting() = <error>name</error>.uppercase()
                  hint: 'name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = name.toString()` - declare the type, like `fun f(): String = name.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun greet(who: String) = \"hi \$who\"")

        // when
        val result = session.execute("""
            fun loudGreeting() = greet("x").uppercase()
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:22: error: Unresolved reference 'greet'.
                  | fun loudGreeting() = <error>greet</error>("x").uppercase()
                  hint: 'greet' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = greet(...).toString()` - declare the type, like `fun f(): String = greet(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of snippet which failed in the evaluation is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("""
            val name = "x"
            error("boom")
        """.trimIndent())

        // when
        val result = session.execute("""
            fun getGreeting() = name.uppercase()
            getGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:21: error: Unresolved reference 'name'.
                  | fun getGreeting() = <error>name</error>.uppercase()
                  hint: 'name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = name.toString()` - declare the type, like `fun f(): String = name.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun loudGreeting() = "hi".shout()
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:27: error: Unresolved reference 'shout' on receiver of type 'String'.
                  | fun loudGreeting() = "hi".<error>shout</error>()
                  hint: 'shout' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.shout(...).toString()` - declare the type, like `fun f(): String = receiver.shout(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val String.loud get() = uppercase()")

        // when
        val result = session.execute("""
            fun loudGreeting() = "hi".loud
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:27: error: Unresolved reference 'loud' on receiver of type 'String'.
                  | fun loudGreeting() = "hi".<error>loud</error>
                  hint: 'loud' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.loud.toString()` - declare the type, like `fun f(): String = receiver.loud.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on implicit receiver in declaration of inferred type`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun String.loudly() = shout()
            "hi".loudly()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:23: error: Unresolved reference 'shout'.
                  | fun String.loudly() = <error>shout</error>()
                  hint: 'shout' is an extension of String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun String.f() = shout(...).toString()` - declare the type, like `fun String.f(): String = shout(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when private property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("private val secret = 1")

        // when
        val result = session.execute("""
            fun next() = secret + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'secret'.
                  | fun next() = <error>secret</error> + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when private function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("private fun secret() = 1")

        // when
        val result = session.execute("""
            fun next() = secret() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'secret'.
                  | fun next() = <error>secret</error>() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when private extension property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("private val String.secret get() = length")

        // when
        val result = session.execute("""
            fun next() = "a".secret + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:18: error: Unresolved reference 'secret' on receiver of type 'String'.
                  | fun next() = "a".<error>secret</error> + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when private class of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("private class Secret")

        // when
        val result = session.execute("""
            fun isSecret(x: Any) = x is Secret
            isSecret(1)
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:29: error: Unresolved reference 'Secret'.
                  | fun isSecret(x: Any) = x is <error>Secret</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when internal function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("internal fun secret() = 1")

        // when
        val result = session.execute("""
            fun next() = secret() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'secret'.
                  | fun next() = <error>secret</error>() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet called in declaration of declared type`() = runTest {
        // given
        session.execute("internal fun secret() = 1")

        // when
        val result = session.execute("""
            fun next(): Int = secret() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int secret()'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at next(snippet2.kts:1)
                  | fun next(): Int = secret() + 1
                  at <snippet body>(snippet2.kts:2)
                  | next()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint conditionally when extension function of earlier snippet is unresolved without receiver in declaration of inferred type`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun loudGreeting() = shout()
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:22: error: Unresolved reference 'shout'.
                  | fun loudGreeting() = <error>shout</error>()
                  hint: 'shout' is an extension of String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun String.f() = shout(...).toString()` - declare the type, like `fun String.f(): String = shout(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet is unresolved on receiver of another type`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun loudNumbers() = listOf(1).shout()
            loudNumbers()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:31: error: Unresolved reference 'shout' on receiver of type 'List<Int>'.
                  | fun loudNumbers() = listOf(1).<error>shout</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet is unresolved on nullable receiver`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            val maybe: String? = null
            fun loudMaybe() = maybe.shout()
            loudMaybe()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:25: error: Unresolved reference 'shout' on receiver of type 'String?'.
                  | fun loudMaybe() = maybe.<error>shout</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when generic extension function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun <T> T.describe() = \"[\$this]\"")

        // when
        val result = session.execute("""
            fun described() = listOf(1).describe()
            described()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:29: error: Unresolved reference 'describe' on receiver of type 'List<Int>'.
                  | fun described() = listOf(1).<error>describe</error>()
                  hint: 'describe' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.describe(...).toString()` - declare the type, like `fun f(): String = receiver.describe(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should report receiver type mismatch when extension function of earlier snippet is called without receiver in declaration of declared type`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun loudGreeting(): String = shout()
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:30: error: Candidate 'fun String.shout(): String' is inapplicable because of a receiver type mismatch.
                  | fun loudGreeting(): String = <error>shout</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on integer literal in declaration of inferred type`() = runTest {
        // given
        session.execute("fun Any.tagged() = \"<\$this>\"")

        // when
        val result = session.execute("""
            fun tag() = 1.tagged()
            tag()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:15: error: Unresolved reference 'tagged'.
                  | fun tag() = 1.<error>tagged</error>()
                  hint: 'tagged' is an extension of Any declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.tagged(...).toString()` - declare the type, like `fun f(): String = receiver.tagged(...).toString()`, and the compiler tells if the receiver is not of this type.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet on integer literal in declaration of declared type`() = runTest {
        // given
        session.execute("fun Any.tagged() = \"<\$this>\"")

        // when
        val result = session.execute("""
            fun tag(): String = 1.tagged().toString()
            tag()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "<1>")
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet sharing name with extension property is unresolved on integer literal`() = runTest {
        // given
        session.execute("val String.weight get() = length")
        session.execute("fun Int.weight() = this * 2")

        // when
        val result = session.execute("""
            fun heavy() = 5.weight()
            heavy()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:17: error: Unresolved reference 'weight'.
                  | fun heavy() = 5.<error>weight</error>()
                  hint: 'weight' is an extension of Int declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.weight(...).toString()` - declare the type, like `fun f(): String = receiver.weight(...).toString()`, and the compiler tells if the receiver is not of this type.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet sharing name with extension property in declaration of declared type`() = runTest {
        // given
        session.execute("val String.weight get() = length")
        session.execute("fun Int.weight() = this * 2")

        // when
        val result = session.execute("""
            fun heavy(): String = 5.weight().toString()
            heavy()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "10")
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet sharing name with extension property is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("val String.weight get() = length")
        session.execute("fun Int.weight() = this * 2")

        // when
        val result = session.execute("""
            fun Int.heavy() = weight()
            5.heavy()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:19: error: Unresolved reference 'weight'.
                  | fun Int.heavy() = <error>weight</error>()
                  hint: 'weight' is an extension of Int or String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of one of these types, like `fun Int.f() = weight(...).toString()` - declare the type, like `fun Int.f(): String = weight(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet sharing name with extension property on implicit receiver in declaration of declared type`() = runTest {
        // given
        session.execute("val String.weight get() = length")
        session.execute("fun Int.weight() = this * 2")

        // when
        val result = session.execute("""
            fun Int.f(): String = weight().toString()
            5.f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "10")
        }
    }

    @Test
    fun `should hint declaring the type when extension property of earlier snippet sharing name with extension function is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("val String.weight get() = length")
        session.execute("fun Int.weight() = this * 2")

        // when
        val result = session.execute("""
            fun String.heavy() = weight
            "abc".heavy()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:22: error: Unresolved reference 'weight'.
                  | fun String.heavy() = <error>weight</error>
                  hint: 'weight' is an extension of String or Int declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of one of these types, like `fun String.f() = weight.toString()` - declare the type, like `fun String.f(): String = weight.toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type with placeholder when extension property of earlier snippet of generic receiver is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("val <T> List<T>.middle get() = this[size / 2]")

        // when
        val result = session.execute("""
            fun List<Int>.center() = middle
            listOf(1, 2, 3).center()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:26: error: Unresolved reference 'middle'.
                  | fun List<Int>.center() = <error>middle</error>
                  hint: 'middle' is an extension of List<*> declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun Receiver.f() = middle.toString()` - declare the type, like `fun Receiver.f(): String = middle.toString()`, Receiver being the type of the receiver, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should read extension property of earlier snippet of generic receiver in declaration of declared type`() = runTest {
        // given
        session.execute("val <T> List<T>.middle get() = this[size / 2]")

        // when
        val result = session.execute("""
            fun List<Int>.f(): String = middle.toString()
            listOf(1, 2, 3).f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "2")
        }
    }

    @Test
    fun `should hint declaring the type of nested class when extension function of earlier snippet is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("fun Map.Entry<String, Int>.label() = \"\$key=\$value\"")

        // when
        val result = session.execute("""
            fun Map.Entry<String, Int>.labelled() = label()
            mapOf("a" to 1).entries.first().labelled()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:41: error: Unresolved reference 'label'.
                  | fun Map.Entry<String, Int>.labelled() = <error>label</error>()
                  hint: 'label' is an extension of Map.Entry<String, Int> declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun Map.Entry<String, Int>.f() = label(...).toString()` - declare the type, like `fun Map.Entry<String, Int>.f(): String = label(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet on implicit receiver of nested class in declaration of declared type`() = runTest {
        // given
        session.execute("fun Map.Entry<String, Int>.label() = \"\$key=\$value\"")

        // when
        val result = session.execute("""
            fun Map.Entry<String, Int>.f(): String = label().toString()
            mapOf("a" to 1).entries.first().f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "a=1")
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on receiver of subtype in declaration of inferred type`() = runTest {
        // given
        session.execute("fun Collection<Int>.total() = sum()")

        // when
        val result = session.execute("""
            fun f() = listOf(1, 2).total()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:24: error: Unresolved reference 'total' on receiver of type 'List<Int>'.
                  | fun f() = listOf(1, 2).<error>total</error>()
                  hint: 'total' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.total(...).toString()` - declare the type, like `fun f(): String = receiver.total(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet on receiver of subtype in declaration of declared type`() = runTest {
        // given
        session.execute("fun Collection<Int>.total() = sum()")

        // when
        val result = session.execute("""
            fun f(): String = listOf(1, 2).total().toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "3")
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet of mutable receiver is unresolved on read-only receiver`() = runTest {
        // given
        session.execute("fun MutableList<Int>.grow() = add(1)")

        // when
        val result = session.execute("""
            fun f() = listOf(1).grow()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:21: error: Unresolved reference 'grow' on receiver of type 'List<Int>'.
                  | fun f() = listOf(1).<error>grow</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet of mutable receiver is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun MutableList<Int>.grow() = add(1)")

        // when
        val result = session.execute("""
            fun f() = mutableListOf(1).grow()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:28: error: Unresolved reference 'grow' on receiver of type 'MutableList<Int>'.
                  | fun f() = mutableListOf(1).<error>grow</error>()
                  hint: 'grow' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.grow(...).toString()` - declare the type, like `fun f(): String = receiver.grow(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extensions of earlier snippet of covariant type argument of supertype are unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("""
            fun Map<String, Any>.toJson() = entries.joinToString { "${'$'}{it.key}=${'$'}{it.value}" }
            fun List<out Number>.total() = sumOf { it.toInt() }
            fun List<Any>.count2() = size
            fun Pair<Any, Any>.both() = "${'$'}first${'$'}second"
        """.trimIndent())

        // when
        val results = listOf(
            "fun a() = mapOf(\"a\" to 1).toJson()\na()",
            "fun b() = listOf(1).total()\nb()",
            "fun c() = listOf(1, \"a\").count2()\nc()",
            "fun d() = (1 to \"a\").both()\nd()"
        ).map { session.execute(it) }

        // then
        results.joinToString("\n\n") { (it as ScriptResult.Error).message } sameAs """
                snippet2.kts:1:27: error: Unresolved reference 'toJson' on receiver of type 'Map<String, Int>'.
                  | fun a() = mapOf("a" to 1).<error>toJson</error>()
                  hint: 'toJson' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.toJson(...).toString()` - declare the type, like `fun f(): String = receiver.toJson(...).toString()`.

                snippet3.kts:1:21: error: Unresolved reference 'total' on receiver of type 'List<Int>'.
                  | fun b() = listOf(1).<error>total</error>()
                  hint: 'total' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.total(...).toString()` - declare the type, like `fun f(): String = receiver.total(...).toString()`.

                snippet4.kts:1:26: error: Unresolved reference 'count2' on receiver of type 'List<Comparable<*> & Serializable>'.
                  | fun c() = listOf(1, "a").<error>count2</error>()
                  hint: 'count2' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.count2(...).toString()` - declare the type, like `fun f(): String = receiver.count2(...).toString()`.

                snippet5.kts:1:22: error: Unresolved reference 'both' on receiver of type 'Pair<Int, String>'.
                  | fun d() = (1 to "a").<error>both</error>()
                  hint: 'both' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.both(...).toString()` - declare the type, like `fun f(): String = receiver.both(...).toString()`.
            """.trimIndent()
    }

    @Test
    fun `should call extensions of earlier snippet of covariant type argument of supertype in declaration of declared type`() = runTest {
        // given
        session.execute("""
            fun Map<String, Any>.toJson() = entries.joinToString { "${'$'}{it.key}=${'$'}{it.value}" }
            fun List<out Number>.total() = sumOf { it.toInt() }
            fun List<Any>.count2() = size
            fun Pair<Any, Any>.both() = "${'$'}first${'$'}second"
        """.trimIndent())

        // when
        val result = session.execute("""
            fun f(): String = listOf(mapOf("a" to 1).toJson(), listOf(1).total(), listOf(1, "a").count2(), (1 to "a").both()).toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "[a=1, 1, 2, 1a]")
        }
    }

    @Test
    fun `should hint conditionally when extension function of earlier snippet of covariant type argument is unresolved on receiver of type argument not known`() = runTest {
        // given
        session.execute("fun List<CharSequence>.joined() = joinToString(\"\")")

        // when
        val result = session.execute("""
            class Text : CharSequence by "x"
            fun f() = listOf(Text()).joined()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:26: error: Unresolved reference 'joined' on receiver of type 'List<Snippet2.Text>'.
                  | fun f() = listOf(Text()).<error>joined</error>()
                  hint: 'joined' is an extension of List<CharSequence> declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.joined(...).toString()` - declare the type, like `fun f(): String = receiver.joined(...).toString()`, and the compiler tells if the receiver is not of this type.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet of covariant type argument on receiver of type argument not known in declaration of declared type`() = runTest {
        // given
        session.execute("fun List<CharSequence>.joined() = joinToString(\"\")")

        // when
        val result = session.execute("""
            class Text : CharSequence by "x"
            fun f(): String = listOf(Text()).joined().toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "x")
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet of covariant type argument is unresolved on receiver of final type argument of other supertypes`() = runTest {
        // given
        session.execute("fun List<Number>.total() = sumOf { it.toInt() }")

        // when
        val result = session.execute("""
            fun f() = listOf("a").total()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:23: error: Unresolved reference 'total' on receiver of type 'List<String>'.
                  | fun f() = listOf("a").<error>total</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extensions of earlier snippet are unresolved on receiver of well-known concrete subtype`() = runTest {
        // given
        session.execute("""
            fun Collection<Int>.total() = sum()
            val CharSequence.size2 get() = length
        """.trimIndent())

        // when
        val results = listOf(
            "fun a() = arrayListOf(1).total()\na()",
            "fun b() = StringBuilder(\"ab\").size2\nb()"
        ).map { session.execute(it) }

        // then
        results.joinToString("\n\n") { (it as ScriptResult.Error).message } sameAs """
                snippet2.kts:1:26: error: Unresolved reference 'total' on receiver of type 'ArrayList<Int>'.
                  | fun a() = arrayListOf(1).<error>total</error>()
                  hint: 'total' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.total(...).toString()` - declare the type, like `fun f(): String = receiver.total(...).toString()`.

                snippet3.kts:1:31: error: Unresolved reference 'size2' on receiver of type 'StringBuilder'.
                  | fun b() = StringBuilder("ab").<error>size2</error>
                  hint: 'size2' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.size2.toString()` - declare the type, like `fun f(): String = receiver.size2.toString()`.
            """.trimIndent()
    }

    @Test
    fun `should call extensions of earlier snippet on receiver of well-known concrete subtype in declaration of declared type`() = runTest {
        // given
        session.execute("""
            fun Collection<Int>.total() = sum()
            val CharSequence.size2 get() = length
        """.trimIndent())

        // when
        val result = session.execute("""
            fun f(): String = listOf(arrayListOf(1).total(), StringBuilder("ab").size2).toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "[1, 2]")
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet of other receiver is unresolved on integer literal`() = runTest {
        // given
        session.execute("fun String.big() = uppercase()")

        // when
        val result = session.execute("""
            fun f() = 1.big()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:13: error: Unresolved reference 'big'.
                  | fun f() = 1.<error>big</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet of function type with receiver is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("fun (List<Int>.() -> Pair<String, Int>).run2() = listOf(1).this().second")

        // when
        val result = session.execute("""
            fun (List<Int>.() -> Pair<String, Int>).f() = run2()
            val pairOf: List<Int>.() -> Pair<String, Int> = { "a" to size }
            pairOf.f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:47: error: Unresolved reference 'run2'.
                  | fun (List<Int>.() -> Pair<String, Int>).f() = <error>run2</error>()
                  hint: 'run2' is an extension of List<Int>.() -> Pair<String, Int> declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun (List<Int>.() -> Pair<String, Int>).f() = run2(...).toString()` - declare the type, like `fun (List<Int>.() -> Pair<String, Int>).f(): String = run2(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet of function type with receiver on implicit receiver in declaration of declared type`() = runTest {
        // given
        session.execute("fun (List<Int>.() -> Pair<String, Int>).run2() = listOf(1).this().second")

        // when
        val result = session.execute("""
            fun (List<Int>.() -> Pair<String, Int>).f(): String = run2().toString()
            val pairOf: List<Int>.() -> Pair<String, Int> = { "a" to size }
            pairOf.f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "1")
        }
    }

    @Test
    fun `should not hint declaring the type when property of earlier snippet is unresolved on integer literal`() = runTest {
        // given
        session.execute("val greeting = \"hi\"")

        // when
        val result = session.execute("""
            fun f() = 1.greeting
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:13: error: Unresolved reference 'greeting'.
                  | fun f() = 1.<error>greeting</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved as end of range in declaration of inferred type`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() = (0..count).toList()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:15: error: Unresolved reference 'count'.
                  | fun f() = (0..<error>count</error>).toList()
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved as end of open range in declaration of inferred type`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() = (0..<count).toList()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:16: error: Unresolved reference 'count'.
                  | fun f() = (0..<<error>count</error>).toList()
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved after comment ending with period`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() =
                // the count.
                count
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:3:5: error: Unresolved reference 'count'.
                  |     <error>count</error>
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint implicit receiver when extension function of earlier snippet is unresolved after comment ending with period`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun f() = with("a") {
                // Shout it.
                shout()
            }
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:11: error: Cannot infer type for type parameter 'R'. Specify it explicitly.
                  | fun f() = <error>with</error>("a") {

                snippet2.kts:3:5: error: Unresolved reference 'shout'.
                  |     <error>shout</error>()
                  hint: 'shout' is an extension of String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun String.f() = shout(...).toString()` - declare the type, like `fun String.f(): String = shout(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when extension function of earlier snippet is unresolved in callable reference`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun f() = listOf("a").map(String::shout)
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:23: error: Cannot infer type for type parameter 'R'. Specify it explicitly.
                  | fun f() = listOf("a").<error>map</error>(String::shout)

                snippet2.kts:1:35: error: Unresolved reference 'shout'.
                  | fun f() = listOf("a").map(String::<error>shout</error>)
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension property of function type of earlier snippet is called on explicit receiver in declaration of inferred type`() = runTest {
        // given
        session.execute("val String.onClick: () -> String get() = { \"clicked \$this\" }")

        // when
        val result = session.execute("""
            fun f() = "a".onClick()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:15: error: Unresolved reference 'onClick' on receiver of type 'String'.
                  | fun f() = "a".<error>onClick</error>()
                  hint: 'onClick' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.onClick(...).toString()` - declare the type, like `fun f(): String = receiver.onClick(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension property of function type of earlier snippet on explicit receiver in declaration of declared type`() = runTest {
        // given
        session.execute("val String.onClick: () -> String get() = { \"clicked \$this\" }")

        // when
        val result = session.execute("""
            fun f(): String = "a".onClick().toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "clicked a")
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved with type arguments in declaration of inferred type`() = runTest {
        // given
        session.execute("fun <T> List<T>.second() = this[1]")

        // when
        val result = session.execute("""
            fun f() = listOf(1, 2).second<Int>()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:24: error: Unresolved reference 'second' on receiver of type 'List<Int>'.
                  | fun f() = listOf(1, 2).<error>second</error><Int>()
                  hint: 'second' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.second(...).toString()` - declare the type, like `fun f(): String = receiver.second(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should call extension function of earlier snippet with type arguments in declaration of declared type`() = runTest {
        // given
        session.execute("fun <T> List<T>.second() = this[1]")

        // when
        val result = session.execute("""
            fun f(): String = listOf(1, 2).second<Int>().toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "2")
        }
    }

    @Test
    fun `should call infix extension function of earlier snippet in declaration of declared type`() = runTest {
        // given
        session.execute("infix fun String.and(other: String) = this + other")

        // when
        val result = session.execute("""
            fun f(): String = ("a" and "b").toString()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "ab")
        }
    }

    @Test
    fun `should hint declaring the type when infix extension function of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("infix fun String.and(other: String) = this + other")

        // when
        val result = session.execute("""
            fun f() = "a" and "b"
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:15: error: Unresolved reference 'and' on receiver of type 'String'.
                  | fun f() = "a" <error>and</error> "b"
                  hint: 'and' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.and(...).toString()` - declare the type, like `fun f(): String = receiver.and(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on receiver of class of earlier snippet`() = runTest {
        // given
        session.execute("class Point(val x: Int)")
        session.execute("fun Point.norm() = x")

        // when
        val result = session.execute("""
            val p = Point(1)
            fun f() = p.norm()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:2:13: error: Unresolved reference 'norm' on receiver of type 'Snippet1.Point'.
                  | fun f() = p.<error>norm</error>()
                  hint: 'norm' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.norm(...).toString()` - declare the type, like `fun f(): String = receiver.norm(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on receiver of generic class of earlier snippet`() = runTest {
        // given
        session.execute("class Box<T>(val v: T)")
        session.execute("fun Box<Int>.twice() = v * 2")

        // when
        val result = session.execute("""
            val b = Box(1)
            fun f() = b.twice()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:2:13: error: Unresolved reference 'twice' on receiver of type 'Snippet1.Box<Int>'.
                  | fun f() = b.<error>twice</error>()
                  hint: 'twice' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.twice(...).toString()` - declare the type, like `fun f(): String = receiver.twice(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet is unresolved on receiver of nested class of earlier snippet`() = runTest {
        // given
        session.execute("class Box { class Inner }")
        session.execute("fun Box.Inner.name() = \"inner\"")

        // when
        val result = session.execute("""
            val i = Box.Inner()
            fun f() = i.name()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:2:13: error: Unresolved reference 'name' on receiver of type 'Snippet1.Box.Inner'.
                  | fun f() = i.<error>name</error>()
                  hint: 'name' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.name(...).toString()` - declare the type, like `fun f(): String = receiver.name(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension property of earlier snippet of type alias receiver is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val StringBuilder.size2 get() = length")

        // when
        val result = session.execute("""
            val sb = StringBuilder("ab")
            fun f() = sb.size2
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:14: error: Unresolved reference 'size2' on receiver of type 'StringBuilder'.
                  | fun f() = sb.<error>size2</error>
                  hint: 'size2' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.size2.toString()` - declare the type, like `fun f(): String = receiver.size2.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet of Exception receiver is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun Exception.brief() = message")

        // when
        val result = session.execute("""
            val e = Exception("x")
            fun f() = e.brief()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:13: error: Unresolved reference 'brief' on receiver of type 'Exception'.
                  | fun f() = e.<error>brief</error>()
                  hint: 'brief' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.brief(...).toString()` - declare the type, like `fun f(): String = receiver.brief(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension function of earlier snippet of type alias of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("typealias Name = String")
        session.execute("fun Name.greet() = \"hi \$this\"")

        // when
        val result = session.execute("""
            fun f() = "a".greet()
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:15: error: Unresolved reference 'greet' on receiver of type 'String'.
                  | fun f() = "a".<error>greet</error>()
                  hint: 'greet' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.greet(...).toString()` - declare the type, like `fun f(): String = receiver.greet(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint expansion of type alias when extension property of earlier snippet is unresolved on implicit receiver`() = runTest {
        // given
        session.execute("val StringBuilder.size2 get() = length")

        // when
        val result = session.execute("""
            fun StringBuilder.f() = size2
            StringBuilder("ab").f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:25: error: Unresolved reference 'size2'.
                  | fun StringBuilder.f() = <error>size2</error>
                  hint: 'size2' is an extension of StringBuilder declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun StringBuilder.f() = size2.toString()` - declare the type, like `fun StringBuilder.f(): String = size2.toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when function of earlier snippet is unresolved in callable reference`() = runTest {
        // given
        session.execute("fun double(x: Int) = x * 2")

        // when
        val result = session.execute("""
            fun f() = listOf(1).map(::double)
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:21: error: Cannot infer type for type parameter 'R'. Specify it explicitly.
                  | fun f() = listOf(1).<error>map</error>(::double)

                snippet2.kts:1:27: error: Unresolved reference 'double'.
                  | fun f() = listOf(1).map(::<error>double</error>)
                  hint: 'double' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = double(...).toString()` - declare the type, like `fun f(): String = double(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved in callable reference`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() = ::count
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:13: error: Unresolved reference 'count'.
                  | fun f() = ::<error>count</error>
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved after line ending with comment ending with period`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() = // the count.
                count
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:2:5: error: Unresolved reference 'count'.
                  |     <error>count</error>
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when function of earlier snippet is unresolved after line ending with comment ending with period`() = runTest {
        // given
        session.execute("fun greet() = \"hi\"")

        // when
        val result = session.execute("""
            fun loud() = run { // make it loud.
                greet()
            }
            loud()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Cannot infer type for type parameter 'R'. Specify it explicitly.
                  | fun loud() = <error>run</error> { // make it loud.

                snippet2.kts:2:5: error: Unresolved reference 'greet'.
                  |     <error>greet</error>()
                  hint: 'greet' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = greet(...).toString()` - declare the type, like `fun f(): String = greet(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint implicit receiver when extension function of earlier snippet is unresolved after line ending with comment ending with period`() = runTest {
        // given
        session.execute("fun String.shout() = uppercase()")

        // when
        val result = session.execute("""
            fun f() = with("a") { // shout it.
                shout()
            }
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:11: error: Cannot infer type for type parameter 'R'. Specify it explicitly.
                  | fun f() = <error>with</error>("a") { // shout it.

                snippet2.kts:2:5: error: Unresolved reference 'shout'.
                  |     <error>shout</error>()
                  hint: 'shout' is an extension of String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun String.f() = shout(...).toString()` - declare the type, like `fun String.f(): String = shout(...).toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when property of earlier snippet is unresolved after block comment ending with period`() = runTest {
        // given
        session.execute("val count = 3")

        // when
        val result = session.execute("""
            fun f() =
                /*
                the count.
                */
                count
            f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:5:5: error: Unresolved reference 'count'.
                  |     <error>count</error>
                  hint: 'count' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = count.toString()` - declare the type, like `fun f(): String = count.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension property of earlier snippet is unresolved in comparison on explicit receiver`() = runTest {
        // given
        session.execute("val String.size2 get() = length")

        // when
        val result = session.execute("""
            fun f(s: String) = when { s.size2 < 3 -> { "short" } else -> "long" }
            f("a")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:29: error: Unresolved reference 'size2' on receiver of type 'String'.
                  | fun f(s: String) = when { s.<error>size2</error> < 3 -> { "short" } else -> "long" }
                  hint: 'size2' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = receiver.size2.toString()` - declare the type, like `fun f(): String = receiver.size2.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when extension property of earlier snippet is unresolved in comparison on implicit receiver`() = runTest {
        // given
        session.execute("val String.size2 get() = length")

        // when
        val result = session.execute("""
            fun String.f() = when { size2 < 3 -> { 1 } else -> 2 }
            "a".f()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:25: error: Unresolved reference 'size2'.
                  | fun String.f() = when { <error>size2</error> < 3 -> { 1 } else -> 2 }
                  hint: 'size2' is an extension of String declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, even on an implicit receiver of this type, like `fun String.f() = size2.toString()` - declare the type, like `fun String.f(): String = size2.toString()`, and the compiler tells if there is no such receiver.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when function of earlier snippet of JVM name with dollar sign is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("@JvmName(\"greet\\\$jvm\") fun greet() = \"hi\"")

        // when
        val result = session.execute("""
            fun loudGreeting() = greet().uppercase()
            loudGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:22: error: Unresolved reference 'greet'.
                  | fun loudGreeting() = <error>greet</error>().uppercase()
                  hint: 'greet' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = greet(...).toString()` - declare the type, like `fun f(): String = greet(...).toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when internal property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("internal val secret = 1")

        // when
        val result = session.execute("""
            fun next() = secret + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'secret'.
                  | fun next() = <error>secret</error> + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal property of earlier snippet read in declaration of declared type`() = runTest {
        // given
        session.execute("internal val secret = 1")

        // when
        val result = session.execute("""
            fun next(): Int = secret + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int getSecret${'$'}main()'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at next(snippet2.kts:1)
                  | fun next(): Int = secret + 1
                  at <snippet body>(snippet2.kts:2)
                  | next()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet taking parameters`() = runTest {
        // given
        session.execute("internal fun secret(n: Int, label: String) = n")

        // when
        val result = session.execute("secret(1, \"a\") + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int secret(int, java.lang.String)'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at <snippet body>(snippet2.kts:1)
                  | secret(1, "a") + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal variable of earlier snippet assigned by later snippet`() = runTest {
        // given
        session.execute("internal var secret = 1")

        // when
        val result = session.execute("secret = 2")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'void setSecret${'$'}main(int)'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at <snippet body>(snippet2.kts:1)
                  | secret = 2
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal member of class of earlier snippet`() = runTest {
        // given
        session.execute("class Box { internal fun open() = 1 }")

        // when
        val result = session.execute("Box().open()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int Box.open${'$'}main()'
                  hint: 'Box.open' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones of the class 'Box', or holding a lambda calling it, as they keep calling the internal one.
                  at <snippet body>(snippet2.kts:1)
                  | Box().open()
            """.trimIndent()
        }
    }

    @Test
    fun `should call internal function of earlier snippet once declared public together with its callers as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun next(): Int = secret() + 1")
        val failure = session.execute("""
            fun outer(): Int = next() * 2
            outer()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.NoSuchMethodError: 'int secret()'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'next' of snippet2.kts and 'outer' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at next(snippet2.kts:1)
                  | fun next(): Int = secret() + 1
                  at outer(snippet3.kts:1)
                  | fun outer(): Int = next() * 2
                  at <snippet body>(snippet3.kts:2)
                  | outer()
            """.trimIndent()
        }
        session.execute("""
            fun secret() = 1
            fun next(): Int = secret() + 1
            fun outer(): Int = next() * 2
        """.trimIndent())

        // when
        val result = session.execute("outer()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 4)
        }
    }

    @Test
    fun `should hint declaring public internal setter of public property of earlier snippet`() = runTest {
        // given
        session.execute("""
            var count = 0
                internal set
        """.trimIndent())

        // when
        val result = session.execute("count = 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'void setCount${'$'}main(int)'
                  hint: the setter of 'count' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare the setter public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at <snippet body>(snippet2.kts:1)
                  | count = 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet called with default arguments`() = runTest {
        // given
        session.execute("internal fun secret(n: Int = 1) = n")

        // when
        val result = session.execute("secret() + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int secret${'$'}default(Snippet1, int, int, java.lang.Object)'
                  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.
                  at <snippet body>(snippet2.kts:1)
                  | secret() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda passed to function of earlier snippet once declared public as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun call(f: () -> Int) = f()")
        val failure = session.execute("call { secret() }")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'call' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("fun secret() = 1")

        // when
        val result = session.execute("call { secret() }")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal member of class of earlier snippet once declared public and its values created again as hinted`() = runTest {
        // given
        session.execute("class Box { internal fun open() = 1 }")
        session.execute("val box = Box()")
        val failure = session.execute("box.open()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'Box.open' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones of the class 'Box', or holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("class Box { fun open() = 1 }")
        val failureOfValueCreatedBefore = session.execute("box.open()")
        failureOfValueCreatedBefore should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'Box.open' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - it is declared again by a later snippet, so declare again the code calling it, directly or not, and create again the values depending on it, like the ones of the class 'Box', or holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("val box = Box()")

        // when
        val result = session.execute("box.open()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should hint declaring again only the callers of internal function of earlier snippet declared again public`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun next(): Int = secret() + 1")
        session.execute("fun secret() = 2")
        val failure = session.execute("next()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - it is declared again by a later snippet, so declare again the code calling it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("fun next(): Int = secret() + 1")

        // when
        val result = session.execute("next()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should call internal function of earlier snippet declared again public and then internal once declared public with its callers as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun call(): Int = secret()")
        session.execute("fun secret() = 2")
        session.execute("internal fun secret() = 3")
        val failure = session.execute("call()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'call' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 3
            fun call(): Int = secret()
        """.trimIndent())

        // when
        val result = session.execute("call()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet shadowing public one of earlier snippet`() = runTest {
        // given
        session.execute("fun secret() = 0")
        session.execute("internal fun secret() = 1")

        // when
        val result = session.execute("secret()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet2.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda kept by value of its caller once declared public together with the value as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("var cache: (() -> Int)? = null")
        session.execute("fun g(): Int { if (cache == null) cache = { secret() }; return cache!!() }")
        val failure = session.execute("g()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'g' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            var cache: (() -> Int)? = null
            fun g(): Int { if (cache == null) cache = { secret() }; return cache!!() }
        """.trimIndent())

        // when
        val result = session.execute("g()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet once declared public together with class calling it and its values created again as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("class C { fun g() = secret() }")
        session.execute("val c = C()")
        val failure = session.execute("c.g()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'C.g' of snippet2.kts, and create again the values depending on it, like the ones of the class 'C', or holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            class C { fun g() = secret() }
        """.trimIndent())
        session.execute("val c = C()")

        // when
        val result = session.execute("c.g()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda of function once declared public together with the function as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun g(): Int = sequenceOf(1).map { secret() }.first()")
        val failure = session.execute("g()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'g' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            fun g(): Int = sequenceOf(1).map { secret() }.first()
        """.trimIndent())

        // when
        val result = session.execute("g()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lazy property once declared public together with the property as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("val p: Int by lazy { secret() }")
        val failure = session.execute("p")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'p' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            val p: Int by lazy { secret() }
        """.trimIndent())

        // when
        val result = session.execute("p")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lazy property of class once declared public together with the class and its values created again as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("class D { val p: Int by lazy { secret() } }")
        session.execute("val d = D()")
        val failure = session.execute("d.p")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'D.p' of snippet2.kts, and create again the values depending on it, like the ones of the class 'D', or holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            class D { val p: Int by lazy { secret() } }
        """.trimIndent())
        session.execute("val d = D()")

        // when
        val result = session.execute("d.p")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda run by other function of its snippet once declared public together with the code creating it as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("""
            fun make(): () -> Int = { secret() }
            fun call(f: () -> Int) = f()
        """.trimIndent())
        session.execute("val f = make()")
        val failure = session.execute("call(f)")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'call' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            fun make(): () -> Int = { secret() }
        """.trimIndent())
        session.execute("val f = make()")

        // when
        val result = session.execute("call(f)")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from open class run on subclass value once declared public together with the code depending on it as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("open class A { fun g() = secret() }")
        session.execute("class B : A()")
        session.execute("val b = B()")
        val failure = session.execute("b.g()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'A.g' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            open class A { fun g() = secret() }
            class B : A()
        """.trimIndent())
        session.execute("val b = B()")

        // when
        val result = session.execute("b.g()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from default method of interface once declared public together with the code depending on it as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("interface I { fun g() = secret() }")
        session.execute("class C : I")
        session.execute("val c = C()")
        val failure = session.execute("c.g()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'I.g' of snippet2.kts and 'C.g' of snippet3.kts, and create again the values depending on it, like the ones of the class 'C', or holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            interface I { fun g() = secret() }
            class C : I
        """.trimIndent())
        session.execute("val c = C()")

        // when
        val result = session.execute("c.g()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda passed by its creator to other function once the code depending on it is declared again as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("""
            fun call(f: () -> Int) = f()
            fun g() = call { secret() }
        """.trimIndent())
        session.execute("fun h() = g()")
        val failure = session.execute("h()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'call' of snippet2.kts, 'g' of snippet2.kts and 'h' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            fun call(f: () -> Int) = f()
            fun g() = call { secret() }
        """.trimIndent())
        val failureOfCodeNotDeclaredAgain = session.execute("h()")
        failureOfCodeNotDeclaredAgain should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - it is declared again by a later snippet, so declare again the code calling it, directly or not, like 'call' of snippet2.kts, 'g' of snippet2.kts and 'h' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("fun h() = g()")

        // when
        val result = session.execute("h()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from suspend lambda once declared public together with the code depending on it as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("suspend fun g(): Int = coroutineScope { yield(); secret() }")
        session.execute("fun h(): Int = runBlocking { g() }")
        val failure = session.execute("h()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, like 'h' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
            have(message.lines().count { it.trim().startsWith("hint:") } == 1)
        }
        session.execute("""
            fun secret() = 1
            suspend fun g(): Int = coroutineScope { yield(); secret() }
            fun h(): Int = runBlocking { g() }
        """.trimIndent())

        // when
        val result = session.execute("h()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet with default argument redeclared without it`() = runTest {
        // given
        session.execute("internal fun secret(n: Int = 1) = n")
        session.execute("fun secret(n: Int) = n")
        val failure = session.execute("secret()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("fun secret(n: Int = 1) = n")

        // when
        val result = session.execute("secret()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should hint declaring public internal setter of earlier snippet redeclared as val`() = runTest {
        // given
        session.execute("""
            var count = 0
                internal set
        """.trimIndent())
        session.execute("fun reset() { count = 0 }")
        session.execute("val count = 5")

        // when
        val result = session.execute("reset()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: the setter of 'count' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare the setter public, without the internal modifier, and declare again the code calling it, directly or not, like 'reset' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
    }

    @Test
    fun `should not hint creating again values of object calling internal function of earlier snippet`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("object O { fun g() = secret() }")

        // when
        val result = session.execute("O.g()")

        // then
        result should {
            be<ScriptResult.Error>()
            have("values of 'O'" !in message)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda returned by function once declared public together with the code creating it as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun make(): () -> Int = { secret() }")
        session.execute("val f = make()")
        val failure = session.execute("f()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            fun make(): () -> Int = { secret() }
        """.trimIndent())
        session.execute("val f = make()")

        // when
        val result = session.execute("f()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should call internal function of earlier snippet from lambda value of earlier snippet once declared public together with the value as hinted`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("val f = { secret() }")
        val failure = session.execute("f()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("""
            fun secret() = 1
            val f = { secret() }
        """.trimIndent())

        // when
        val result = session.execute("f()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should hint declaring public internal function of earlier snippet overloaded by later public function`() = runTest {
        // given
        session.execute("internal fun secret() = 1")
        session.execute("fun secret(x: Int) = x")
        val failure = session.execute("secret()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'secret' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and declare again the code calling it, directly or not, and create again the values depending on it, like the ones holding a lambda calling it, as they keep calling the internal one.")
        }
        session.execute("fun secret() = 1")

        // when
        val result = session.execute("secret()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should not hint declaring public when missing method is not internal member of earlier snippet`() = runTest {
        // given
        session.execute("internal val secret = 1")

        // when
        val result = session.execute("throw java.lang.NoSuchMethodError(\"'int Snippet1.getOther\\\$main()'\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'int getOther${'$'}main()'
                  at <snippet body>(snippet2.kts:1)
                  | throw java.lang.NoSuchMethodError("'int Snippet1.getOther\${'$'}main()'")
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without const when const property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("const val LIMIT = 1")

        // when
        val result = session.execute("""
            fun next() = LIMIT + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'LIMIT'.
                  | fun next() = <error>LIMIT</error> + 1
                  hint: 'LIMIT' is declared const in snippet1.kts, which a later snippet cannot read, as the K2 REPL compiler fails on it - declare it again public, without the const modifier, and declare the type of a declaration of a later snippet reading it, like `fun f(): String = LIMIT.toString()`, as the K2 REPL compiler does not resolve a property of an earlier snippet within a declaration of an inferred type used in the same snippet.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without const when compiler fails on const property of earlier snippet read in declaration of declared type`() = runTest {
        // given
        session.execute("const val LIMIT = 1")

        // when
        val result = session.execute("""
            fun next(): Int = LIMIT + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("error: The compiler failed internally"))
            have("InterpreterMethodNotFoundError: Unknown function: LIMIT(Snippet1)" in message)
            have(message.lines().last() == CONST_LIMIT_HINT)
        }
    }

    @Test
    fun `should hint redeclaring without const when compiler fails on const property of earlier snippet read in top-level expression`() = runTest {
        // given
        session.execute("const val LIMIT = 1")

        // when
        val result = session.execute("LIMIT + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("error: The compiler failed internally"))
            have("InterpreterMethodNotFoundError: Unknown function: LIMIT(Snippet1)" in message)
            have(message.lines().last() == CONST_LIMIT_HINT)
        }
    }

    @Test
    fun `should hint redeclaring without const when compiler fails on const property of earlier snippet read in property initializer`() = runTest {
        // given
        session.execute("const val LIMIT = 1")

        // when
        val result = session.execute("val next = LIMIT + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("error: The compiler failed internally"))
            have("InterpreterMethodNotFoundError: Unknown function: LIMIT(Snippet1)" in message)
            have(message.lines().last() == CONST_LIMIT_HINT)
        }
    }

    @Test
    fun `should read const property of earlier snippet once declared again without const as hinted`() = runTest {
        // given
        session.execute("const val LIMIT = 1")
        val failure = session.execute("LIMIT + 1")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == CONST_LIMIT_HINT)
        }
        session.execute("val LIMIT = 1")

        // when
        val result = session.execute("""
            fun next(): Int = LIMIT + 1
            next() + LIMIT
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should not hint at const property of earlier snippet when compiler fails on const property of object`() = runTest {
        // given
        session.execute("const val X = 1")
        session.execute("object O { const val X = 1 }")

        // when
        val result = session.execute("O.X + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have("InterpreterMethodNotFoundError: Unknown function: X(O)" in message)
            have("hint:" !in message)
        }
    }

    @Test
    fun `should hint redeclaring without const when property declared again as const is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val LIMIT = 1")
        session.execute("const val LIMIT = 2")

        // when
        val result = session.execute("""
            fun next() = LIMIT + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'LIMIT'.
                  | fun next() = <error>LIMIT</error> + 1
                  hint: 'LIMIT' is declared const in snippet2.kts, which a later snippet cannot read, as the K2 REPL compiler fails on it - declare it again public, without the const modifier, and declare the type of a declaration of a later snippet reading it, like `fun f(): String = LIMIT.toString()`, as the K2 REPL compiler does not resolve a property of an earlier snippet within a declaration of an inferred type used in the same snippet.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint declaring the type when const property declared again without const is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("const val LIMIT = 1")
        session.execute("val LIMIT = 2")

        // when
        val result = session.execute("""
            fun next() = LIMIT + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'LIMIT'.
                  | fun next() = <error>LIMIT</error> + 1
                  hint: 'LIMIT' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = LIMIT.toString()` - declare the type, like `fun f(): String = LIMIT.toString()`.
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when property declared again internal is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val limit = 1")
        session.execute("internal val limit = 2")

        // when
        val result = session.execute("""
            fun next() = limit + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error> + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when function declared again internal is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("fun limit() = 1")
        session.execute("internal fun limit() = 2")

        // when
        val result = session.execute("""
            fun next() = limit() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error>() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when function of earlier snippet is overloaded by later internal function`() = runTest {
        // given
        session.execute("fun limit() = 1")
        session.execute("internal fun limit(x: Int) = x")

        // when
        val result = session.execute("""
            fun next() = limit(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error>(1) + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when internal function of earlier snippet is overloaded by later public function`() = runTest {
        // given
        session.execute("internal fun limit() = 1")
        session.execute("fun limit(x: Int) = x")

        // when
        val result = session.execute("""
            fun next() = limit() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error>() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when generic function is declared again internal with another type parameter name`() = runTest {
        // given
        session.execute("fun <T> echo(x: T) = x")
        session.execute("internal fun <R> echo(x: R) = x")

        // when
        val result = session.execute("""
            fun next() = echo(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'echo'.
                  | fun next() = <error>echo</error>(1) + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should call generic function of earlier snippet with type arguments once its type is declared as hinted`() = runTest {
        // given
        session.execute("fun <T> echo(x: T) = x")
        val failure = session.execute("""
            fun next() = echo<Int>(1) + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'echo' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = echo(...).toString()` - declare the type, like `fun f(): String = echo(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = echo<Int>(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call generic function of earlier snippet with type arguments rather than internal property of the name once its type is declared as hinted`() = runTest {
        // given
        session.execute("fun <T> echo(x: T) = x")
        session.execute("internal val echo = 5")
        val failure = session.execute("""
            fun next() = echo <Int>(1) + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'echo' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = echo(...).toString()` - declare the type, like `fun f(): String = echo(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = echo <Int>(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call generic function of earlier snippet with type arguments and trailing lambda rather than internal property of the name once its type is declared as hinted`() = runTest {
        // given
        session.execute("fun <T> run3(block: () -> T) = block()")
        session.execute("internal val run3 = 5")
        val failure = session.execute("""
            fun next() = run3<Int> { 1 } + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'run3' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = run3(...).toString()` - declare the type, like `fun f(): String = run3(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = run3<Int> { 1 } + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call generic function of earlier snippet with type arguments rather than property of the name once its type is declared as hinted`() = runTest {
        // given
        session.execute("val limit = 1")
        session.execute("fun <T> limit(x: T) = x")
        val failure = session.execute("""
            fun next() = limit<Int>(1) + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'limit' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = limit(...).toString()` - declare the type, like `fun f(): String = limit(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = limit<Int>(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call generic function of earlier snippet with type arguments rather than const property of the name once its type is declared as hinted`() = runTest {
        // given
        session.execute("const val echo = 5")
        session.execute("fun <T> echo(x: T) = x")
        val failure = session.execute("""
            fun next() = echo<Int>(1) + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'echo' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = echo(...).toString()` - declare the type, like `fun f(): String = echo(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = echo<Int>(1) + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should hint at property of earlier snippet compared rather than generic function of the name`() = runTest {
        // given
        session.execute("val limit = 1")
        session.execute("fun <T> limit(x: T) = x")

        // when
        val result = session.execute("""
            fun next() = limit < 3
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.lines().last() == "  hint: 'limit' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = limit.toString()` - declare the type, like `fun f(): String = limit.toString()`.")
        }
    }

    @Test
    fun `should not hint at property of function type of earlier snippet called when function of the name is internal`() = runTest {
        // given
        session.execute("internal fun limit() = 2")
        session.execute("val limit: () -> Int = { 1 }")

        // when
        val result = session.execute("""
            fun next() = limit() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error>() + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at const property rather than function of the same name of earlier snippet`() = runTest {
        // given
        session.execute("const val LIMIT = 1")
        session.execute("fun LIMIT() = 2")

        // when
        val result = session.execute("""
            fun next() = LIMIT + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.lines().last().startsWith("  hint: 'LIMIT' is declared const in snippet1.kts"))
        }
    }

    @Test
    fun `should call function of earlier snippet of the name of const property once its type is declared as hinted`() = runTest {
        // given
        session.execute("const val LIMIT = 1")
        session.execute("fun LIMIT() = 2")
        val failure = session.execute("""
            fun next() = LIMIT() + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'LIMIT' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = LIMIT(...).toString()` - declare the type, like `fun f(): String = LIMIT(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = LIMIT() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should call function of earlier snippet of the name of JvmField property once its type is declared as hinted`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun limit() = 2")
        val failure = session.execute("""
            fun next() = limit() + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'limit' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = limit(...).toString()` - declare the type, like `fun f(): String = limit(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = limit() + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should call internal function of earlier snippet overloaded by later function of other type parameter bound once declared public as hinted`() = runTest {
        // given
        session.execute("internal fun <T : Number> kind(x: T) = 1")
        session.execute("fun <R : CharSequence> kind(x: R) = 2")
        val failure = session.execute("kind(1)")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1].startsWith("  hint: 'kind' is declared internal in snippet1.kts, which a later snippet cannot call, as the K2 REPL compiler calls it by a JVM name it is not compiled to - declare it public, without the internal modifier, and "))
        }
        session.execute("fun <T : Number> kind(x: T) = 1")

        // when
        val result = session.execute("kind(1) + kind(\"a\")")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should hint at extensions of earlier snippets of other type parameter bounds`() = runTest {
        // given
        session.execute("fun <T : Number> T.dimension() = 1")
        session.execute("fun <R : CharSequence> R.dimension() = 2")
        val failure = session.execute("""
            fun Int.next() = dimension() + 1
            5.next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last().startsWith("  hint: 'dimension' is an extension of Number or CharSequence declared in an earlier snippet"))
        }

        // when
        val result = session.execute("""
            fun Int.next(): Int = dimension() + 1
            5.next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should call function of earlier snippet declared again public with default argument once its type is declared as hinted`() = runTest {
        // given
        session.execute("internal fun greet(name: String) = name")
        session.execute("fun greet(name: String = \"x\") = name")
        val failure = session.execute("""
            fun hello() = greet("a")
            hello()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'greet' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = greet(...).toString()` - declare the type, like `fun f(): String = greet(...).toString()`.")
        }

        // when
        val result = session.execute("""
            fun hello(): String = greet("a")
            hello()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "a")
        }
    }

    @Test
    fun `should read internal const property of earlier snippet once declared again public without const as hinted`() = runTest {
        // given
        session.execute("internal const val LIMIT = 1")
        val failure = session.execute("LIMIT + 1")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == CONST_LIMIT_HINT)
        }
        session.execute("val LIMIT = 1")

        // when
        val result = session.execute("LIMIT + 1")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should access internal JvmField property of earlier snippet once declared again public without JvmField as hinted`() = runTest {
        // given
        session.execute("@JvmField internal val limit = 1")
        val failure = session.execute("limit + 1")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.")
        }
        session.execute("val limit = 1")

        // when
        val result = session.execute("limit + 1")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should access JvmField property of earlier snippet unresolved in declaration of inferred type once declared again together with earlier code accessing it as hinted`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        val failure = session.execute("""
            fun f() = limit + 1
            f()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have("declare again the code of earlier snippets accessing it, directly or not" in message.lines().last())
        }
        session.execute("""
            val limit = 1
            fun next(): Int = limit + 1
        """.trimIndent())

        // when
        val result = session.execute("""
            fun f(): Int = limit + 1
            f() + next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 4)
        }
    }

    @Test
    fun `should hint declaring public JvmField property of earlier snippet declared again internal`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        session.execute("internal val limit = 2")

        // when
        val result = session.execute("next()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have(message.lines()[1] == "  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - it is declared again internal by a later snippet, so declare it again public, without the internal modifier, and declare again the code accessing it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.")
        }
    }

    @Test
    fun `should access JvmField property of earlier snippet declared again internal once declared again public with the code accessing it as hinted`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        session.execute("internal val limit = 2")
        val failure = session.execute("next()")
        failure should {
            be<ScriptResult.Error>()
            have("declare it again public, without the internal modifier" in message)
        }
        session.execute("""
            val limit = 2
            fun next(): Int = limit + 1
        """.trimIndent())

        // when
        val result = session.execute("next()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should access JvmField property of earlier snippet declared again const once declared again public with the code accessing it as hinted`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        session.execute("const val limit = 2")
        val failure = session.execute("next()")
        failure should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have(message.lines()[1] == "  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - it is declared again const by a later snippet, so declare it again public, without the const modifier, and declare again the code accessing it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.")
        }
        session.execute("""
            val limit = 2
            fun next(): Int = limit + 1
        """.trimIndent())

        // when
        val result = session.execute("next()")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 3)
        }
    }

    @Test
    fun `should not hint at JvmField property when snippet named by the error does not declare it JvmField`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("val limit = 2")

        // when
        val result = session.execute("throw IncompatibleClassChangeError(\"Expected non-static field Snippet2.limit\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  at <snippet body>(snippet3.kts:1)
                  | throw IncompatibleClassChangeError("Expected non-static field Snippet2.limit")
            """.trimIndent()
        }
    }

    @Test
    fun `should read property of earlier snippet declared again private by later snippet once its type is declared as hinted`() = runTest {
        // given
        session.execute("val limit = 1")
        session.execute("private val limit = 2")
        val failure = session.execute("""
            fun next() = limit + 1
            next()
        """.trimIndent())
        failure should {
            be<ScriptResult.Error>()
            have(message.lines().last() == "  hint: 'limit' is declared in an earlier snippet, which the K2 REPL compiler does not resolve within a declaration of an inferred type used in the same snippet, like `fun f() = limit.toString()` - declare the type, like `fun f(): String = limit.toString()`.")
        }

        // when
        val result = session.execute("""
            fun next(): Int = limit + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should not hint declaring the type when class declared again private is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("class Box")
        session.execute("private class Box")

        // when
        val result = session.execute("""
            fun isBox(x: Any) = x is Box
            isBox(1)
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet3.kts:1:26: error: Unresolved reference 'Box'.
                  | fun isBox(x: Any) = x is <error>Box</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without JvmField when JvmField property of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")

        // when
        val result = session.execute("""
            fun next() = limit + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:14: error: Unresolved reference 'limit'.
                  | fun next() = <error>limit</error> + 1
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, declare again the code of earlier snippets accessing it, directly or not, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one, and declare the type of a declaration of a later snippet reading it, like `fun f(): String = limit.toString()`, as the K2 REPL compiler does not resolve a property of an earlier snippet within a declaration of an inferred type used in the same snippet.
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without JvmField when JvmField property of earlier snippet is read in declaration of declared type`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")

        // when
        val result = session.execute("""
            fun next(): Int = limit + 1
            next()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, like 'next' of snippet2.kts, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.
                  at next(snippet2.kts:1)
                  | fun next(): Int = limit + 1
                  at <snippet body>(snippet2.kts:2)
                  | next()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without JvmField when JvmField property of earlier snippet is read in top-level expression`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")

        // when
        val result = session.execute("limit + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.
                  at <snippet body>(snippet2.kts:1)
                  | limit + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without JvmField when JvmField property of earlier snippet is read in property initializer`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")

        // when
        val result = session.execute("val next = limit + 1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.
                  at <snippet body>(snippet2.kts:1)
                  | val next = limit + 1
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring without JvmField when JvmField variable of earlier snippet is assigned`() = runTest {
        // given
        session.execute("@JvmField var limit = 1")

        // when
        val result = session.execute("limit = 2")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.
                  at <snippet body>(snippet2.kts:1)
                  | limit = 2
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring the code accessing JvmField property of earlier snippet declared again without JvmField`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        session.execute("fun twice(): Int = next() * 2")
        session.execute("val limit = 1")

        // when
        val result = session.execute("twice()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field limit
                  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - it is declared again by a later snippet, so declare again the code accessing it, directly or not, like 'next' of snippet2.kts and 'twice' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.
                  at next(snippet2.kts:1)
                  | fun next(): Int = limit + 1
                  at twice(snippet3.kts:1)
                  | fun twice(): Int = next() * 2
                  at <snippet body>(snippet5.kts:1)
                  | twice()
            """.trimIndent()
        }
    }

    @Test
    fun `should access JvmField property of earlier snippet once declared again without JvmField together with the code accessing it as hinted`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("fun next(): Int = limit + 1")
        session.execute("fun twice(): Int = next() * 2")
        val failure = session.execute("twice()")
        failure should {
            be<ScriptResult.Error>()
            have(message.lines()[1] == "  hint: 'limit' is declared @JvmField in snippet1.kts, which a later snippet cannot access, as the K2 REPL compiler accesses it as an instance field, while it is static - declare it again public, without the @JvmField annotation, and declare again the code accessing it, directly or not, like 'next' of snippet2.kts and 'twice' of snippet3.kts, and create again the values depending on it, like the ones holding a lambda accessing it, as they keep accessing the @JvmField one.")
        }
        session.execute("""
            val limit = 1
            fun next(): Int = limit + 1
            fun twice(): Int = next() * 2
        """.trimIndent())

        // when
        val result = session.execute("twice() + limit")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 5)
        }
    }

    @Test
    fun `should not hint at JvmField property when property of earlier snippet not declared JvmField is accessed as non-static`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")
        session.execute("val other = 1")

        // when
        val result = session.execute("throw IncompatibleClassChangeError(\"Expected non-static field Snippet2.other\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field other
                  at <snippet body>(snippet3.kts:1)
                  | throw IncompatibleClassChangeError("Expected non-static field Snippet2.other")
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at JvmField property of earlier snippet when field of the same name of another class is accessed as non-static`() = runTest {
        // given
        session.execute("@JvmField val limit = 1")

        // when
        val result = session.execute("throw IncompatibleClassChangeError(\"Expected non-static field com.example.Other.limit\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IncompatibleClassChangeError: Expected non-static field com.example.Other.limit
                  at <snippet body>(snippet2.kts:1)
                  | throw IncompatibleClassChangeError("Expected non-static field com.example.Other.limit")
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when misspelled name is unresolved in declaration of inferred type`() = runTest {
        // given
        session.execute("val name = \"x\"")

        // when
        val result = session.execute("""
            fun getGreeting() = nmae.uppercase()
            getGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:21: error: Unresolved reference 'nmae'.
                  | fun getGreeting() = <error>nmae</error>.uppercase()
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint declaring the type when property of snippet which failed to compile is unresolved`() = runTest {
        // given
        session.execute("val name: String = undefinedThing")

        // when
        val result = session.execute("""
            fun getGreeting(): String = name.uppercase()
            getGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet2.kts:1:29: error: Unresolved reference 'name'.
                  | fun getGreeting(): String = <error>name</error>.uppercase()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint redeclaring value class property of earlier snippet`() = runTest {
        // given
        session.execute("val timeout = kotlin.time.Duration.parse(\"5s\")")

        // when
        val result = session.execute("timeout.inWholeSeconds")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NoSuchMethodError: 'long getTimeout-UwyO8pc()'
                  hint: a property or a function of a value class type, like Duration, declared in an earlier snippet, cannot be called from another snippet, as the K2 REPL compiler mangles its name only when calling it - declare it in the same snippet, or wrap the value in a regular class.
                  at <snippet body>(snippet2.kts:1)
                  | timeout.inWholeSeconds
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("val s: String = error(\"boom\")")

        // when
        val result = session.execute("s.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getS()" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.
                  at <snippet body>(snippet2.kts:1)
                  | s.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property read by function of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("""
            val name: String = error("boom")
            fun greet() = "Hello, ${'$'}{name.uppercase()}"
        """.trimIndent())

        // when
        val result = session.execute("greet()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.toUpperCase(java.util.Locale)" because "name" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'greet' of snippet1.kts, as they keep reading the uninitialized one.
                  at greet(snippet1.kts:2)
                  | fun greet() = "Hello, ${'$'}{name.uppercase()}"
                  at <snippet body>(snippet2.kts:1)
                  | greet()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property read by object of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("""
            val s: String = error("boom")
            object Config { val length = s.length }
        """.trimIndent())

        // when
        val result = session.execute("Config.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.ExceptionInInitializerError
                  at <snippet body>(snippet2.kts:1)
                  | Config.length
                Caused by: java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getS()" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'Config' of snippet1.kts, as they keep reading the uninitialized one.
                  at Config.<clinit>(snippet1.kts:2)
                  | object Config { val length = s.length }
                  at <snippet body>(snippet2.kts:1)
                  | Config.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property read by function of snippet which failed in the evaluation also after redeclaring the property`() = runTest {
        // given
        session.execute("""
            val name: String = error("boom")
            fun greet() = "Hello, ${'$'}{name.uppercase()}"
        """.trimIndent())
        session.execute("val name = \"World\"")

        // when
        val result = session.execute("greet()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.toUpperCase(java.util.Locale)" because "name" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'greet' of snippet1.kts, as they keep reading the uninitialized one.
                  at greet(snippet1.kts:2)
                  | fun greet() = "Hello, ${'$'}{name.uppercase()}"
                  at <snippet body>(snippet3.kts:1)
                  | greet()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property read by function of later snippet`() = runTest {
        // given
        session.execute("val s: String = error(\"boom\")")
        session.execute("fun size(): Int = s.length")

        // when
        val result = session.execute("size()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getS()" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'size' of snippet2.kts, as they keep reading the uninitialized one.
                  at size(snippet2.kts:1)
                  | fun size(): Int = s.length
                  at <snippet body>(snippet3.kts:1)
                  | size()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized delegated property of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("""
            error("boom")
            val s: String by lazy { "lazy" }
        """.trimIndent())

        // when
        val result = session.execute("s.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "kotlin.Lazy.getValue()" because "<local1>" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.
                  at getS(snippet1.kts:2)
                  | val s: String by lazy { "lazy" }
                  at <snippet body>(snippet2.kts:1)
                  | s.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property of any getter name`() = runTest {
        // given
        session.execute("""
            error("boom")
            val issue: String = "a"
            val isOk: String = "b"
            val ä: String = "c"
            val isä: String = "d"
        """.trimIndent())

        // when
        val messages = listOf("issue", "isOk", "ä", "isä").associateWith { property ->
            (session.execute("$property.length") as ScriptResult.Error).message
        }

        // then
        messages.forEach { (property, message) ->
            assert("hint: '$property' is declared in snippet1.kts" in message)
        }
    }

    @Test
    fun `should not hint at null property of platform type of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("""
            val p = System.getProperty("no.such.property")
            error("boom")
        """.trimIndent())

        // when
        val result = session.execute("p.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getP()" is null
                  at <snippet body>(snippet2.kts:1)
                  | p.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized delegated property of nullable type`() = runTest {
        // given
        session.execute("""
            error("boom")
            val s: String? by lazy { null }
        """.trimIndent())

        // when
        val result = session.execute("s?.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have(message.lines()[1] == "  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.")
        }
    }

    @Test
    fun `should name function reading uninitialized property in lambda`() = runTest {
        // given
        session.execute("""
            val name: String = error("boom")
            fun greet() = lazy { name.uppercase() }.value
        """.trimIndent())

        // when
        val result = session.execute("greet()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.toUpperCase(java.util.Locale)" because "name" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'greet' of snippet1.kts, as they keep reading the uninitialized one.
                  at <lambda>(snippet1.kts:2)
                  | fun greet() = lazy { name.uppercase() }.value
                  at greet(snippet1.kts:2)
                  | fun greet() = lazy { name.uppercase() }.value
                  at <snippet body>(snippet2.kts:1)
                  | greet()
            """.trimIndent()
        }
    }

    @Test
    fun `should name declarations calling the one reading uninitialized property`() = runTest {
        // given
        session.execute("""
            val name: String = error("boom")
            fun greet() = "Hello, ${'$'}{name.uppercase()}"
        """.trimIndent())
        session.execute("fun wrapper() = greet()")
        session.execute("""
            val name = "World"
            fun greet() = "Hello, ${'$'}{name.uppercase()}"
        """.trimIndent())

        // when
        val result = session.execute("wrapper()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.toUpperCase(java.util.Locale)" because "name" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 'greet' of snippet1.kts, and the ones calling them, like 'wrapper' of snippet2.kts, as they keep reading the uninitialized one.
                  at greet(snippet1.kts:2)
                  | fun greet() = "Hello, ${'$'}{name.uppercase()}"
                  at wrapper(snippet2.kts:1)
                  | fun wrapper() = greet()
                  at <snippet body>(snippet4.kts:1)
                  | wrapper()
            """.trimIndent()
        }
    }

    @Test
    fun `should name property of custom getter reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            val t: Int get() = name.length
            t
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getName()" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, like 't' of snippet2.kts, as they keep reading the uninitialized one.
                  at getT(snippet2.kts:1)
                  | val t: Int get() = name.length
                  at <snippet body>(snippet2.kts:2)
                  | t
            """.trimIndent()
        }
    }

    @Test
    fun `should name function named like getter reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            fun getGreeting(): String = name.uppercase()
            getGreeting()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 'getGreeting' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name method of class named like getter reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            class Bar {
                fun getLabel(): String = name.uppercase()
            }
            Bar().getLabel()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 'Bar.getLabel' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name property of setter starting with is reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            var isOk: String = ""
                set(value) { field = value + name.length }
            isOk = "x"
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 'isOk' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name property of accessor of custom JVM name reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            val t: Int @JvmName("size") get() = name.length
            t
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 't' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name extension property reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            val String.foo: Int get() = name.length
            "x".foo
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 'foo' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name inherited property of object reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            interface Foo { val label: Int get() = name.length }
            object Impl : Foo
            Impl.label
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("like 'Foo.label' of snippet2.kts, and the ones calling them, like 'Impl.label' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should name function named like getter of property reading uninitialized property`() = runTest {
        // given
        session.execute("val name: String = error(\"boom\")")

        // when
        val result = session.execute("""
            val t: Int = 1
            fun getT(n: Int): Int = name.length + n
            getT(1)
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // a frame does not tell the function from the getter of t, so neither is renamed
            have("like 'getT' of snippet2.kts," in message)
        }
    }

    @Test
    fun `should hint at uninitialized property of snippet which threw fatal error`() = runTest {
        // given
        // a regression lets the OutOfMemoryError escape, which would abort the Gradle test executor
        val failure = runCatching { session.execute("val s: String = throw OutOfMemoryError(\"fake\")") }

        // when
        val result = session.execute("s.length")

        // then
        assert(failure.exceptionOrNull() is OutOfMemoryError)
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getS()" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.
                  at <snippet body>(snippet2.kts:1)
                  | s.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized property of getter of custom JVM name`() = runTest {
        // given
        session.execute("@get:JvmName(\"text\") val s: String = error(\"boom\")")

        // when
        val result = session.execute("s.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "text()" is null
                  hint: 's' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.
                  at <snippet body>(snippet2.kts:1)
                  | s.length
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at assigning uninitialized var of snippet which failed in the evaluation`() = runTest {
        // given
        session.execute("""
            var name: String = error("boom")
            fun greet() = name.uppercase()
        """.trimIndent())

        // when
        val result = session.execute("greet()")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.toUpperCase(java.util.Locale)" because "name" is null
                  hint: 'name' is declared in snippet1.kts, which failed in the evaluation, so it might be left uninitialized, null despite its type - assign it, instead of declaring it again, as the declarations reading it, like 'greet' of snippet1.kts, keep reading the same one.
                  at greet(snippet1.kts:2)
                  | fun greet() = name.uppercase()
                  at <snippet body>(snippet2.kts:1)
                  | greet()
            """.trimIndent()
        }
    }

    @Test
    fun `should hint at uninitialized properties in the error of the snippet which failed in the evaluation`() = runTest {
        // when
        val result = session.execute("""
            class Foo
            val a: String = "a"
            val b: String = error("boom")
            val c: Foo = Foo()
            val d: String by lazy { "d" }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: boom
                  hint: 'b', 'c' and 'd' are left uninitialized, null despite their type, as the snippet failed before initializing them, while they stay declared - declare them again, together with the declarations reading them, as they keep reading the uninitialized ones.
                  at <snippet body>(snippet1.kts:3)
                  | val b: String = error("boom")
            """.trimIndent()
        }
    }

    @Test
    fun `should list first uninitialized properties in declaration order in the error of the snippet which failed in the evaluation`() = runTest {
        // when
        val result = session.execute(buildString {
            append("error(\"boom\")\n")
            (1..12).forEach { append("val z$it: String = \"\"\n") }
        })

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("hint: 'z1', 'z2', 'z3', 'z4', 'z5' and 7 more are left uninitialized," in message)
        }
    }

    @Test
    fun `should hint at assigning uninitialized var in the error of the snippet which failed in the evaluation`() = runTest {
        // when
        val result = session.execute("""
            var a: String = error("boom")
            val b: String = "b"
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: boom
                  hint: 'b' is left uninitialized, null despite its type, as the snippet failed before initializing it, while it stays declared - declare it again, together with the declarations reading it, as they keep reading the uninitialized one.
                  hint: 'a' is left uninitialized, null despite its type, as the snippet failed before initializing it, while it stays declared - assign it, instead of declaring it again, as the declarations reading it keep reading the same one.
                  at <snippet body>(snippet1.kts:1)
                  | var a: String = error("boom")
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at properties which can be null or zero in the error of the snippet which failed in the evaluation`() = runTest {
        // when
        val result = session.execute("""
            val p = System.getProperty("no.such.property")
            val n: String? = "n"
            val i: Int = 1
            lateinit var l: String
            val t: Int get() = i
            error("boom")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: boom
                  at <snippet body>(snippet1.kts:6)
                  | error("boom")
            """.trimIndent()
        }
    }

    @Test
    fun `should not hint at null property of snippet which did not fail in the evaluation`() = runTest {
        // given
        session.execute("val s: String = error(\"boom\")")
        session.execute("val missing = System.getProperty(\"no.such.property\")")

        // when
        val result = session.execute("missing.length")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.NullPointerException: Cannot invoke "String.length()" because the return value of "getMissing()" is null
                  at <snippet body>(snippet3.kts:1)
                  | missing.length
            """.trimIndent()
        }
    }

    @Test
    fun `should continue the session after a failing snippet`() = runTest {
        // given
        session.execute("val x = 41")
        session.execute("throw Exception(\"must fail\")")

        // when
        val result = session.execute("x + 1")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    @Test
    fun `should keep declarations of snippet preceding the exception it threw`() = runTest {
        // given
        session.execute("""
            val before = 1
            fun twice(x: Int) = x * 2
            class Box(val v: Int)
            error("failed midway")
        """.trimIndent())

        // when
        val result = session.execute("twice(Box(before).v)")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    @Test
    fun `should keep declarations of many snippets earlier, also after failing snippets in between`() = runTest {
        // given
        session.execute("""
            val base = 10
            fun scaled(x: Int) = x * base
            class Point(val x: Int, val y: Int)
        """.trimIndent())
        session.execute("val unrelated = 1")
        session.execute("val broken: String = 1")
        session.execute("throw Exception(\"must fail\")")
        session.execute("unresolvedReference")
        session.execute("val another = 2")

        // when
        val result = session.execute("scaled(Point(1, 2).y) + unrelated + another")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 23)
        }
    }

    @Test
    fun `should redeclare function and class in later snippet`() = runTest {
        // given
        session.execute("""
            fun greet() = "hello"
            class Box(val v: Int)
        """.trimIndent())
        session.execute("""
            fun greet() = "hi"
            class Box(val v: String)
        """.trimIndent())

        // when
        val result = session.execute("greet() + Box(\"!\").v")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "hi!")
        }
    }

    @Test
    fun `should keep calling the redeclared function from function declared before the redeclaration`() = runTest {
        // given
        session.execute("fun greet() = \"hello\"")
        session.execute("fun shout() = greet().uppercase()")
        session.execute("fun greet() = \"hi\"")

        // when
        val result = session.execute("shout() + greet()")

        // then
        result should {
            be<ScriptResult.Value>()
            // the function compiled earlier keeps calling the function it was compiled against, like in a notebook
            have(value == "HELLOhi")
        }
    }

    @Test
    fun `should report warnings of successful snippet`() = runTest {
        // when
        val result = session.execute("""
            val s: String = "a"
            s!!
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "a")
            warnings sameAs """
                snippet1.kts:2:2: warning: Unnecessary non-null assertion (!!) on a non-null receiver of type 'String'.
                  | s<error>!!</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should report runtime exception`() = runTest {
        // when
        val result = session.execute("throw Exception(\"must fail\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.Exception: must fail
                  at <snippet body>(snippet1.kts:1)
                  | throw Exception("must fail")
            """.trimIndent()
        }
    }

    @Test
    fun `should report runtime exception with multi-line message indented`() = runTest {
        // when
        val result = session.execute("throw IllegalStateException(\"a\\nb\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: a
                  b
                  at <snippet body>(snippet1.kts:1)
                  | throw IllegalStateException("a\nb")
            """.trimIndent()
        }
    }

    @Test
    fun `should report runtime exception thrown by function declared in earlier snippet`() = runTest {
        // given
        session.execute("""
            fun check(value: Int) {
                require(value > 0) { "value must be positive" }
            }
        """.trimIndent())

        // when
        val result = session.execute("""
            val values = listOf(1, -1)
            values.forEach { check(it) }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalArgumentException: value must be positive
                  at check(snippet1.kts:2)
                  |     require(value > 0) { "value must be positive" }
                  at <snippet body>(snippet2.kts:2)
                  | values.forEach { check(it) }
            """.trimIndent()
        }
    }

    @Test
    fun `should report exception with the root cause`() = runTest {
        // given
        val snippet = """
            fun willThrow() {
                String::class.java.getDeclaredField("nonExistentField")
            }
            try {
                willThrow()
            } catch (e: Exception) {
                throw Exception("must fail", e)
            }
        """.trimIndent()

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Error>()
            message.replace(Regex("Class\\.java:\\d+"), "Class.java:N") sameAs """
                java.lang.Exception: must fail
                  at <snippet body>(snippet1.kts:7)
                  |     throw Exception("must fail", e)
                Caused by: java.lang.NoSuchFieldException: nonExistentField
                  at java.lang.Class.getDeclaredField(Class.java:N)
                  at willThrow(snippet1.kts:2)
                  |     String::class.java.getDeclaredField("nonExistentField")
                  at <snippet body>(snippet1.kts:5)
                  |     willThrow()
            """.trimIndent()
        }
    }

    @Test
    fun `should report method of class declared in snippet`() = runTest {
        // given
        session.execute("""
            class Checker {
                fun check(): Int = error("broken")
            }
        """.trimIndent())

        // when
        val result = session.execute("Checker().check()")

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: broken
                  at Checker.check(snippet1.kts:2)
                  |     fun check(): Int = error("broken")
                  at <snippet body>(snippet2.kts:1)
                  | Checker().check()
            """.trimIndent()
        }
    }

    @Test
    fun `should report exception class declared in snippet without the snippet class name`() = runTest {
        // given
        session.execute("class Failure(val code: Int) : RuntimeException(\"failure ${'$'}code\")")

        // when
        val result = session.execute("throw Failure(1)")

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                Failure: failure 1
                  at <snippet body>(snippet2.kts:1)
                  | throw Failure(1)
            """.trimIndent()
        }
    }

    @Test
    fun `should report enum constant not found without the snippet class name`() = runTest {
        // given
        session.execute("enum class Color { RED }")

        // when
        val result = session.execute("Color.valueOf(\"BLUE\")")

        // then
        result should {
            be<ScriptResult.Error>()
            have(message.startsWith("java.lang.IllegalArgumentException: No enum constant Color.BLUE\n"))
        }
    }

    @Test
    fun `should collapse frames of recursion`() = runTest {
        // given
        val snippet = """
            fun countdown(n: Int): Int = if (n == 0) error("bottom reached") else countdown(n - 1)
            countdown(50)
        """.trimIndent()

        // when
        val result = session.execute(snippet)

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                java.lang.IllegalStateException: bottom reached
                  at countdown(snippet1.kts:1)
                  | fun countdown(n: Int): Int = if (n == 0) error("bottom reached") else countdown(n - 1)
                  ... (the frame above repeated 50 more times)
                  at <snippet body>(snippet1.kts:2)
                  | countdown(50)
            """.trimIndent()
        }
    }

    @Test
    fun `should report StackOverflowError with collapsed recursive frames`() = runTest {
        // when
        val result = session.execute("""
            fun recurse(n: Int): Int = recurse(n + 1) + 1
            recurse(0)
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // the number of frames depends on the JVM settings
            message.replace(Regex("repeated \\d+ more times"), "repeated N more times") sameAs """
                java.lang.StackOverflowError
                  at recurse(snippet1.kts:1)
                  | fun recurse(n: Int): Int = recurse(n + 1) + 1
                  ... (the frame above repeated N more times)
            """.trimIndent()
        }
    }

    @Test
    fun `should report lambda frames`() = runTest {
        // when
        val result = session.execute("""
            listOf(1).map {
                error("in lambda")
            }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            // the frame of map, of the standard library, is not reported, as it is not the frame throwing
            message sameAs """
                java.lang.IllegalStateException: in lambda
                  at <lambda>(snippet1.kts:2)
                  |     error("in lambda")
                  at <snippet body>(snippet1.kts:1)
                  | listOf(1).map {
            """.trimIndent()
        }
    }

    @Test
    fun `should report lines of inline function declared in the snippet`() = runTest {
        // when
        val result = session.execute("""
            inline fun ensure(condition: Boolean, message: () -> String) {
                if (!condition) {
                    val text = message()
                    throw IllegalStateException(text)
                }
            }
            val a = 1
            ensure(a > 1) { "a must be greater than 1" }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: a must be greater than 1
                  at ensure(snippet1.kts:4)
                  |         throw IllegalStateException(text)
                  at <snippet body>(snippet1.kts:8)
                  | ensure(a > 1) { "a must be greater than 1" }
            """.trimIndent()
        }
    }

    @Test
    fun `should report lambda of inline function declared in the snippet`() = runTest {
        // when
        val result = session.execute("""
            inline fun check(list: List<Int>, message: () -> String) {
                list.forEach {
                    if (it > 2) throw IllegalStateException(message())
                }
            }
            val numbers = listOf(1, 2, 3)
            check(numbers) { "too big" }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: too big
                  at <lambda>(snippet1.kts:3)
                  |         if (it > 2) throw IllegalStateException(message())
                  at check(snippet1.kts:2)
                  |     list.forEach {
                  at <snippet body>(snippet1.kts:7)
                  | check(numbers) { "too big" }
            """.trimIndent()
        }
    }

    @Test
    fun `should report inline function of throw in the body of lambda passed to it`() = runTest {
        // when
        val result = session.execute("""
            inline fun call(block: () -> Unit) {
                block()
            }
            call {
                throw IllegalStateException("in lambda")
            }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: in lambda
                  at <lambda>(snippet1.kts:5)
                  |     throw IllegalStateException("in lambda")
                  at call(snippet1.kts:2)
                  |     block()
                  at <snippet body>(snippet1.kts:4)
                  | call {
            """.trimIndent()
        }
    }

    @Test
    fun `should report inline function of error in the body of lambda passed to it`() = runTest {
        // when
        val result = session.execute("""
            inline fun pre(block: () -> Unit) {
                println("before")
                block()
            }
            pre {
                error("in lambda")
            }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: in lambda
                  at <lambda>(snippet1.kts:6)
                  |     error("in lambda")
                  at pre(snippet1.kts:3)
                  |     block()
                  at <snippet body>(snippet1.kts:5)
                  | pre {
            """.trimIndent()
        }
    }

    @Test
    fun `should report inline function of error in the body of lambda passed to it without a line before calling it`() = runTest {
        // when
        val result = session.execute("""
            inline fun call(block: () -> Unit) {
                block()
            }
            call {
                error("in lambda")
            }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: in lambda
                  at <lambda>(snippet1.kts:5)
                  |     error("in lambda")
                  at call(snippet1.kts:2)
                  |     block()
                  at <snippet body>(snippet1.kts:4)
                  | call {
            """.trimIndent()
        }
    }

    @Test
    fun `should name frame of inline property accessor like frame of property accessor`() = runTest {
        // when
        val result = session.execute("""
            val x: Int inline get() = error("x")
            x
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // like getT of a property accessor which is not inline, see should name property of custom getter reading uninitialized property
            message sameAs """
                java.lang.IllegalStateException: x
                  at getX(snippet1.kts:1)
                  | val x: Int inline get() = error("x")
                  at <snippet body>(snippet1.kts:2)
                  | x
            """.trimIndent()
        }
    }

    @Test
    fun `should report lines of nested inline functions declared in the snippet`() = runTest {
        // when
        val result = session.execute("""
            inline fun inner() { throw IllegalStateException("x") }
            inline fun outer() { inner() }
            outer()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message.substringBefore("\n\n") sameAs """
                java.lang.IllegalStateException: x
                  at inner(snippet1.kts:1)
                  | inline fun inner() { throw IllegalStateException("x") }
                  at outer(snippet1.kts:2)
                  | inline fun outer() { inner() }
                  at <snippet body>(snippet1.kts:3)
                  | outer()
            """.trimIndent()
        }
    }

    @Test
    fun `should omit frame of inline function calling the same inline function twice`() = runTest {
        // when
        val result = session.execute("""
            inline fun inner(x: Int) { if (x == 2) throw IllegalStateException("x=" + x) }
            inline fun outer() {
                inner(1)
                inner(2)
            }
            outer()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // both copies of inner share the lines, so the one throwing is unknown, and outer is omitted rather than reported at a wrong line
            message.substringBefore("\n\n") sameAs """
                java.lang.IllegalStateException: x=2
                  at inner(snippet1.kts:1)
                  | inline fun inner(x: Int) { if (x == 2) throw IllegalStateException("x=" + x) }
                  at <snippet body>(snippet1.kts:6)
                  | outer()
            """.trimIndent()
        }
    }

    @Test
    fun `should report lines of three levels of nested inline functions declared in the snippet`() = runTest {
        // when
        val result = session.execute("""
            inline fun a(message: () -> String) { throw IllegalStateException(message()) }
            inline fun b(message: () -> String) { a(message) }
            inline fun c(message: () -> String) { b(message) }
            c { "x" }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: x
                  at a(snippet1.kts:1)
                  | inline fun a(message: () -> String) { throw IllegalStateException(message()) }
                  at b(snippet1.kts:2)
                  | inline fun b(message: () -> String) { a(message) }
                  at c(snippet1.kts:3)
                  | inline fun c(message: () -> String) { b(message) }
                  at <snippet body>(snippet1.kts:4)
                  | c { "x" }
            """.trimIndent()
        }
    }

    @Test
    fun `should report line of inline function calling inline function of the standard library`() = runTest {
        // when
        val result = session.execute("""
            inline fun firstBig(list: List<Int>, predicate: (Int) -> Boolean) = list.first { it > 5 && predicate(it) }
            firstBig(listOf(1)) { true }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // the line of the inlined code of the standard library depends on its version
            message.replace(Regex("_Collections\\.kt:\\d+"), "_Collections.kt:N") sameAs """
                java.util.NoSuchElementException: Collection contains no element matching the predicate.
                  at kotlin.collections.CollectionsKt___CollectionsKt.first(_Collections.kt:N)
                  at firstBig(snippet1.kts:1)
                  | inline fun firstBig(list: List<Int>, predicate: (Int) -> Boolean) = list.first { it > 5 && predicate(it) }
                  at <snippet body>(snippet1.kts:2)
                  | firstBig(listOf(1)) { true }
            """.trimIndent()
        }
    }

    @Test
    fun `should report lambda calling inline function within inline function declared in the snippet`() = runTest {
        // when
        val result = session.execute("""
            inline fun ensure(condition: Boolean, message: () -> String) {
                if (!condition) throw IllegalStateException(message())
            }
            inline fun check(list: List<Int>, message: () -> String) {
                list.forEach {
                    ensure(it <= 2, message)
                }
            }
            check(listOf(1, 2, 3)) { "too big" }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: too big
                  at ensure(snippet1.kts:2)
                  |     if (!condition) throw IllegalStateException(message())
                  at <lambda>(snippet1.kts:6)
                  |         ensure(it <= 2, message)
                  at check(snippet1.kts:5)
                  |     list.forEach {
                  at <snippet body>(snippet1.kts:9)
                  | check(listOf(1, 2, 3)) { "too big" }
            """.trimIndent()
        }
    }

    @Test
    fun `should report lines of nested inline functions inlined into method of class declared in earlier snippet`() = runTest {
        // given
        session.execute("""
            inline fun ensure(condition: Boolean, message: () -> String) {
                if (!condition) throw IllegalStateException(message())
            }
            inline fun ensurePositive(value: Int, message: () -> String) = ensure(value > 0, message)
            class Account(val balance: Int) {
                fun withdraw(amount: Int) = ensurePositive(balance - amount) { "insufficient balance" }
            }
        """.trimIndent())

        // when
        val result = session.execute("Account(1).withdraw(2)")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: insufficient balance
                  at ensure(snippet1.kts:2)
                  |     if (!condition) throw IllegalStateException(message())
                  at ensurePositive(snippet1.kts:4)
                  | inline fun ensurePositive(value: Int, message: () -> String) = ensure(value > 0, message)
                  at Account.withdraw(snippet1.kts:6)
                  |     fun withdraw(amount: Int) = ensurePositive(balance - amount) { "insufficient balance" }
                  at <snippet body>(snippet2.kts:1)
                  | Account(1).withdraw(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should report lines of inline function inlined into method of class declared in earlier snippet`() = runTest {
        // given
        session.execute("""
            inline fun ensure(condition: Boolean, message: () -> String) {
                if (!condition) {
                    val text = message()
                    throw IllegalStateException(text)
                }
            }
            class Account(val balance: Int) {
                fun withdraw(amount: Int) = ensure(amount <= balance) { "insufficient balance" }
            }
        """.trimIndent())

        // when
        val result = session.execute("Account(1).withdraw(2)")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: insufficient balance
                  at ensure(snippet1.kts:4)
                  |         throw IllegalStateException(text)
                  at Account.withdraw(snippet1.kts:8)
                  |     fun withdraw(amount: Int) = ensure(amount <= balance) { "insufficient balance" }
                  at <snippet body>(snippet2.kts:1)
                  | Account(1).withdraw(2)
            """.trimIndent()
        }
    }

    @Test
    fun `should report line of the call of inline function of the standard library`() = runTest {
        // when
        val result = session.execute("""
            val numbers = listOf(1, 2, 3)
            numbers.first { it > 5 }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            // the line of the inlined code of the standard library depends on its version
            message.replace(Regex("_Collections\\.kt:\\d+"), "_Collections.kt:N") sameAs """
                java.util.NoSuchElementException: Collection contains no element matching the predicate.
                  at kotlin.collections.CollectionsKt___CollectionsKt.first(_Collections.kt:N)
                  at <snippet body>(snippet1.kts:2)
                  | numbers.first { it > 5 }
            """.trimIndent()
        }
    }

    @Test
    fun `should report unicode characters`() = runTest {
        // when
        val result = session.execute("val π = \"żółć\"\nπ + ł")

        // then
        result should {
            be<ScriptResult.Error>()
            message sameAs """
                snippet1.kts:2:5: error: Unresolved reference 'ł'.
                  | π + <error>ł</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should use the time API without opting in`() = runTest {
        // given
        val clock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(42)
        }

        ScriptSession(listOf(property<Clock>("clock", clock))).use { session ->
            // when
            val result = session.execute("""
                import kotlin.time.Instant
                val now: Instant = clock.now()
                now.epochSeconds
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 42L)
            }
        }
    }

    @Test
    fun `should await suspending function`() = runTest {
        // when
        val result = session.execute("""
            suspend fun fetch(): String {
                delay(10)
                return "fetched"
            }
            await { fetch() }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "fetched")
        }
    }

    @Test
    fun `should await concurrent coroutines`() = runTest {
        // when
        val result = session.execute("""
            await {
                (1..3).map { i -> async { delay(10L * i); i } }.awaitAll().sum()
            }
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 6)
        }
    }

    @Test
    fun `should report exception thrown by awaited block`() = runTest {
        // when
        val result = session.execute("""
            await { error("failed in await") }
        """.trimIndent())
        val next = session.execute("1")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have(message.startsWith("java.lang.IllegalStateException: failed in await\n"))
        }
        next should {
            be<ScriptResult.Value>()
            have(value == 1)
        }
    }

    @Test
    fun `should report suspending call without await`() = runTest {
        // when
        val result = session.execute("delay(1)")

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have("can only be called from a coroutine or another suspend function" in message)
        }
    }

    @Test
    fun `should keep coroutine launched by snippet running after it`() = runTest {
        // given
        session.execute("""
            val ticks = java.util.concurrent.atomic.AtomicInteger()
            val ticker = launch {
                while (true) {
                    ticks.incrementAndGet()
                    delay(1)
                }
            }
        """.trimIndent())

        // when
        val result = session.execute("""
            await { while (ticks.get() < 3) delay(1) }
            ticker.isActive
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == true)
        }
    }

    @Test
    fun `should cancel coroutines launched by snippets when the session is closed`() = runTest {
        // given
        val started = Job()
        val stopped = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
            provide<CompletableJob>("stopped", stopped)
        }
        session.execute("""
            launch {
                started.complete()
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete()
                }
            }
        """.trimIndent())
        // signalled from within the coroutine, as one cancelled before it started would never run its finally
        started.join()

        // when
        session.close()

        // then
        stopped.join()
        assert(stopped.isCompleted)
    }

    @Test
    fun `should cancel awaited block when the caller is cancelled`() = runTest {
        // given
        val started = Job()
        val stopped = Job()
        ScriptSession {
            provide<CompletableJob>("started", started)
            provide<CompletableJob>("stopped", stopped)
        }.use { session ->
            val execution = launch(kotlinx.coroutines.Dispatchers.Default) {
                session.execute("""
                    await {
                        started.complete()
                        try {
                            delay(60_000)
                        } finally {
                            stopped.complete()
                        }
                    }
                """.trimIndent())
            }
            started.join()

            // when
            execution.cancelAndJoin()

            // then
            assert(stopped.isCompleted)
        }
    }

    @Test
    fun `should interrupt blocking snippet when the caller is cancelled`() = runTest {
        // given
        val sleeping = CompletableDeferred<Thread>()
        ScriptSession {
            provide<CompletableDeferred<Thread>>("sleeping", sleeping)
        }.use { session ->
            val execution = launch(kotlinx.coroutines.Dispatchers.Default) {
                session.execute("""
                    sleeping.complete(Thread.currentThread())
                    Thread.sleep(60_000)
                """.trimIndent())
            }
            sleeping.await().awaitSleeping()

            // when
            execution.cancelAndJoin()
            val next = session.execute("\"still alive\"")

            // then
            next should {
                be<ScriptResult.Value>()
                have(value == "still alive")
            }
        }
    }

    /**
     * The compiled snippet is evaluated nevertheless, as the session would evaluate it before the next one anyway,
     * while its caller, cancelled already, interrupts the thread of the session immediately.
     */
    @Test
    fun `should evaluate compiled snippet of caller cancelled before the evaluation`() = runTest {
        // given
        val evaluated = Job()
        ScriptSession {
            provide<CompletableJob>("evaluated", evaluated)
        }.use { session ->
            val execution = launch {
                cancel()
                session.execute("evaluated.complete()")
            }

            // when
            execution.join()

            // then
            assert(evaluated.isCompleted)
        }
    }

    /** Like `suspend fun main`, or any coroutine started with `startCoroutine`, see #135. */
    @Test
    fun `should execute snippet of caller without a Job`() {
        // given
        val execution = CompletableFuture<Result<ScriptResult>>()

        // when
        suspend {
            session.execute("await { 42 }")
        }.startCoroutine(Continuation(EmptyCoroutineContext) {
            execution.complete(it)
        })

        // then
        // bounded, so a deadlock fails the test instead of hanging the build
        execution.get(1, TimeUnit.MINUTES).getOrThrow() should {
            be<ScriptResult.Value>()
            have(value == 42)
        }
    }

    /**
     * A caller without a dispatcher has no thread to return to, so it would resume on the thread of the session,
     * blocking the next snippet with whatever blocks the caller then, see #147.
     */
    @Test
    fun `should not resume caller without a dispatcher on the session thread`() {
        // given
        val threads = CompletableFuture<Result<Pair<Any?, Thread>>>()

        // when
        suspend {
            val result = session.execute("Thread.currentThread()")
            assert(result is ScriptResult.Value)
            result.value to Thread.currentThread()
        }.startCoroutine(Continuation(EmptyCoroutineContext) {
            threads.complete(it)
        })

        // then
        // bounded, so a hang fails the test instead of hanging the build
        val (sessionThread, callerThread) = threads.get(1, TimeUnit.MINUTES).getOrThrow()
        assert(callerThread !== sessionThread)
    }

    /**
     * A caller on [Dispatchers.Unconfined] would resume in place, on the thread of the session,
     * blocking the next snippet with whatever blocks the caller then, like #147, see #172.
     */
    @Test
    fun `should not resume caller on Dispatchers Unconfined on the session thread`() {
        // when
        val (sessionThread, callerThread) = runBlocking(Dispatchers.Unconfined) {
            val result = session.execute("Thread.currentThread()")
            assert(result is ScriptResult.Value)
            result.value to Thread.currentThread()
        }

        // then
        assert(callerThread !== sessionThread)
    }

    /**
     * An [UnconfinedTestDispatcher] does not dispatch either, so its caller would resume on the thread of the session, see #172.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `should not resume caller on UnconfinedTestDispatcher on the session thread`() = runTest(UnconfinedTestDispatcher()) {
        // when
        val result = session.execute("Thread.currentThread()")
        val callerThread = Thread.currentThread()

        // then
        assert(result is ScriptResult.Value)
        assert(callerThread !== result.value)
    }

    /**
     * A dispatcher which does not dispatch on its own thread only, like `Dispatchers.Main.immediate`,
     * resumes its caller on its own thread, as a UI caller must, without a hop through [Dispatchers.Default],
     * as it is asked on the thread of the session, not on its own, where it would not dispatch, see #172 and #201.
     */
    @Test
    fun `should resume caller of immediate dispatcher on its own thread without Dispatchers Default`() {
        // given
        val hops = AtomicInteger()
        val session = DefaultScriptSession(emptyList(), Dispatchers.Default, resumeExecutor = countingHops(hops))
        val executor = Executors.newSingleThreadExecutor()
        val dispatcherThread = CompletableFuture.supplyAsync({ Thread.currentThread() }, executor).get()
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== dispatcherThread
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        }

        try {
            // when
            val callerThread = runBlocking {
                withContext(immediate) {
                    val result = session.execute("42")
                    assert(result is ScriptResult.Value)
                    Thread.currentThread()
                }
            }

            // then
            assert(callerThread === dispatcherThread)
            assert(hops.get() == 0)
        } finally {
            session.close()
            executor.shutdown()
        }
    }

    /**
     * A caller with its own dispatcher is resumed by it directly, not through [Dispatchers.Default],
     * so it does not need a free worker of [Dispatchers.Default], which the snippets might all block, see #172 and #201.
     */
    @Test
    fun `should resume caller with a dispatcher without Dispatchers Default`() {
        // given
        val hops = AtomicInteger()
        val session = DefaultScriptSession(emptyList(), Dispatchers.Default, resumeExecutor = countingHops(hops))

        session.use {
            // when
            val result = runBlocking {
                session.execute("42")
            }

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == 42)
            }
            assert(hops.get() == 0)
        }
    }

    /**
     * A dispatcher failing to tell whether it dispatches, on the thread of the session,
     * would leave its caller unresumed, holding the lock of the session,
     * so the caller is resumed through [Dispatchers.Default] instead, where its dispatcher is asked again, see #201.
     */
    @Test
    fun `should resume caller of dispatcher failing on the session thread`() {
        // given
        val executor = Executors.newSingleThreadExecutor()
        val failing = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean {
                check(!Thread.currentThread().name.startsWith("script-session-")) { "Not on the session thread" }
                return true
            }
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        }

        try {
            // when
            val results = CompletableFuture.supplyAsync {
                runBlocking {
                    withContext(failing) {
                        session.execute("41") to session.execute("42")
                    }
                }
            }

            // then
            // bounded, so a hang fails the test instead of hanging the build
            val (first, second) = results.get(1, TimeUnit.MINUTES)
            first should {
                be<ScriptResult.Value>()
                have(value == 41)
            }
            second should {
                be<ScriptResult.Value>()
                have(value == 42)
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * A caller without a dispatcher waiting for the snippet of another caller would be resumed by the unlocking of that caller,
     * in place, on its thread, like the main thread of a UI, rejected there by the session closed meanwhile, see #147, #172 and #201.
     */
    @Test
    fun `should not resume waiting caller without a dispatcher on the thread of the unlocking caller`() {
        // when
        val (waitingThread, unlockingThread) = waitingCallerAndUnlockingThreads(EmptyCoroutineContext)

        // then
        assert(waitingThread !== unlockingThread)
    }

    /**
     * A caller on [Dispatchers.Unconfined] waiting for the snippet of another caller would be resumed by the unlocking of that caller,
     * in place, on its thread, like the main thread of a UI, rejected there by the session closed meanwhile, see #201.
     */
    @Test
    fun `should not resume waiting caller on Dispatchers Unconfined on the thread of the unlocking caller`() {
        // when
        val (waitingThread, unlockingThread) = waitingCallerAndUnlockingThreads(Dispatchers.Unconfined)

        // then
        assert(waitingThread !== unlockingThread)
    }

    /**
     * A caller of a dispatcher which does not dispatch on its own thread only, like `Dispatchers.Main.immediate`,
     * waiting for the snippet of another caller, is resumed on its own thread by its dispatcher,
     * so it is rejected by the session closed meanwhile there, without a hop through [Dispatchers.Default],
     * which the snippets might all block, see #201.
     */
    @Test
    fun `should reject waiting caller of immediate dispatcher without Dispatchers Default`() {
        // given
        val hops = AtomicInteger()
        val started = CountDownLatch(1)
        val session = DefaultScriptSession(
            listOf(ScriptProperty("started", typeOf<CountDownLatch>(), started)),
            Dispatchers.Default,
            resumeExecutor = countingHops(hops)
        )
        val unlockingExecutor = Executors.newSingleThreadExecutor()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcherThread = CompletableFuture.supplyAsync({ Thread.currentThread() }, executor).get()
        val immediate = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext) = Thread.currentThread() !== dispatcherThread
            override fun dispatch(context: CoroutineContext, block: Runnable) = executor.execute(block)
        }
        try {
            CoroutineScope(unlockingExecutor.asCoroutineDispatcher()).launch {
                session.execute("started.countDown(); Thread.sleep(Long.MAX_VALUE)")
            }
            started.await()
            val waitingResult = CompletableFuture<Result<ScriptResult>>()
            // started in place on the thread of the dispatcher, so suspended on the lock held by the unlocking caller when it returns
            val suspended = CompletableFuture.supplyAsync({
                suspend {
                    session.execute("42")
                }.startCoroutine(Continuation(immediate) {
                    waitingResult.complete(it)
                })
                !waitingResult.isDone
            }, executor).get()
            assert(suspended)
            // the unlocking caller resumes only after the session is closed, with its thread shut down
            val gate = CountDownLatch(1)
            unlockingExecutor.execute { gate.await() }

            // when
            session.close()
            gate.countDown()

            // then
            // bounded, so a hang fails the test instead of hanging the build
            assert(waitingResult.get(1, TimeUnit.MINUTES).exceptionOrNull() is IllegalStateException)
            assert(hops.get() == 0)
        } finally {
            session.close()
            unlockingExecutor.shutdownNow()
            executor.shutdown()
        }
    }

    /**
     * A caller on [Dispatchers.Unconfined] waiting for the snippet of another caller, resumed in place by the unlocking of that caller,
     * runs its snippet without a free worker of [Dispatchers.Default], needed only to resume it after the snippet,
     * as the snippet might be the one freeing the workers, blocked by the coroutines of the earlier snippets, see #201.
     */
    @Test
    fun `should run snippet of waiting caller on Dispatchers Unconfined without Dispatchers Default`() {
        // when
        val (ran, result) = waitingCallerWithDispatchersDefaultSaturated(Dispatchers.Unconfined)

        // then
        assert(ran)
        result.getOrThrow() should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    /**
     * A caller without a dispatcher waiting for the snippet of another caller, resumed in place by the unlocking of that caller,
     * runs its snippet without a free worker of [Dispatchers.Default], needed only to resume it after the snippet,
     * as the snippet might be the one freeing the workers, blocked by the coroutines of the earlier snippets, see #201 and #224.
     */
    @Test
    fun `should run snippet of waiting caller without a dispatcher without Dispatchers Default`() {
        // when
        val (ran, result) = waitingCallerWithDispatchersDefaultSaturated(EmptyCoroutineContext)

        // then
        assert(ran)
        result.getOrThrow() should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    /**
     * A caller without a dispatcher resumes from [Dispatchers.Default], which the coroutines of the snippets might all block,
     * so the turn of the session is released before the caller resumes,
     * and the next caller, whose snippet would free the workers, does not wait for it, see #224.
     */
    @Test
    fun `should run snippet of next caller while caller without a dispatcher waits for Dispatchers Default`() {
        // given
        val started = CountDownLatch(1)
        // stands in for Dispatchers.Default with all its workers blocked until freed
        val freed = CountDownLatch(1)
        val saturated = Executors.newSingleThreadExecutor()
        saturated.execute { freed.await() }
        val session = DefaultScriptSession(
            listOf(
                ScriptProperty("started", typeOf<CountDownLatch>(), started),
                ScriptProperty("freed", typeOf<CountDownLatch>(), freed)
            ),
            Dispatchers.Default,
            resumeExecutor = saturated
        )
        try {
            val holderResult = CompletableFuture<Result<ScriptResult>>()
            suspend {
                session.execute("started.countDown(); 1")
            }.startCoroutine(Continuation(EmptyCoroutineContext) {
                holderResult.complete(it)
            })
            started.await()

            // when
            val next = CompletableFuture.supplyAsync {
                runBlocking {
                    session.execute("freed.countDown(); 2")
                }
            }

            // then
            // bounded, so a deadlock fails the test instead of hanging the build
            next.get(1, TimeUnit.MINUTES) should {
                be<ScriptResult.Value>()
                have(value == 2)
            }
            holderResult.get(1, TimeUnit.MINUTES).getOrThrow() should {
                be<ScriptResult.Value>()
                have(value == 1)
            }
        } finally {
            freed.countDown()
            session.close()
            saturated.shutdown()
        }
    }

    /**
     * A caller on [Dispatchers.Unconfined] resumed from [Dispatchers.Default] runs in the unconfined event loop of kotlinx.coroutines there,
     * which would queue the resumption of a caller on [Dispatchers.Unconfined] waiting for it, if it were resumed by its unlocking,
     * until the resumed caller suspends, so a resumed caller blocking its thread on the waiting one would hang both, see #224.
     */
    @Test
    fun `should resume waiting caller on Dispatchers Unconfined while caller on Dispatchers Unconfined blocks on it`() {
        // given
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val session = ScriptSession {
            provide<CountDownLatch>("started", started)
            provide<CountDownLatch>("proceed", proceed)
        }
        try {
            val waitingResult = CompletableFuture<Result<ScriptResult>>()
            val holderResult = CompletableFuture<Result<Result<ScriptResult>>>()
            suspend {
                session.execute("started.countDown(); proceed.await(); 1")
                // bounded, so a hang fails the test instead of hanging the build
                waitingResult.get(1, TimeUnit.MINUTES)
            }.startCoroutine(Continuation(Dispatchers.Unconfined) {
                holderResult.complete(it)
            })
            started.await()
            suspend {
                session.execute("2")
            }.startCoroutine(Continuation(Dispatchers.Unconfined) {
                waitingResult.complete(it)
            })
            // started in place, and not completed, so suspended on the lock held by the other caller,
            // as execute suspends only there before submitting the snippet
            assert(!waitingResult.isDone)

            // when
            proceed.countDown()

            // then
            holderResult.get(2, TimeUnit.MINUTES).getOrThrow().getOrThrow() should {
                be<ScriptResult.Value>()
                have(value == 2)
            }
        } finally {
            session.close()
        }
    }

    /**
     * A caller without a dispatcher waiting for the snippet of another caller, started on the thread of that caller,
     * would be rejected by the session closed meanwhile in place, on that thread, nested in the unlocking of the other caller,
     * as if it were back on its own thread, see #224.
     */
    @Test
    fun `should not reject waiting caller without a dispatcher on the thread of the unlocking caller it started on`() {
        // when
        val (waitingThread, unlockingThread) = waitingCallerAndUnlockingThreads(
            EmptyCoroutineContext,
            startedOnUnlockingThread = true
        )

        // then
        assert(waitingThread !== unlockingThread)
    }

    /**
     * A caller on [Dispatchers.Unconfined] waiting for the snippet of another caller, started on the thread of that caller,
     * would be rejected by the session closed meanwhile in place, on that thread, nested in the unlocking of the other caller,
     * as if it were back on its own thread, see #224.
     */
    @Test
    fun `should not reject waiting caller on Dispatchers Unconfined on the thread of the unlocking caller it started on`() {
        // when
        val (waitingThread, unlockingThread) = waitingCallerAndUnlockingThreads(
            Dispatchers.Unconfined,
            startedOnUnlockingThread = true
        )

        // then
        assert(waitingThread !== unlockingThread)
    }

    /**
     * A caller without a dispatcher, started on the thread of a caller rejected by the session closed before taking its snippet,
     * and waiting for that caller, is resumed by its unlocking in place, on that thread, also its own,
     * so it is rejected off that thread, not nested in the rejection of the other caller, see #224.
     */
    @Test
    fun `should not reject waiting caller without a dispatcher nested in the rejection of the caller it waited for`() {
        // given
        val unlockingThread = Thread.currentThread()
        val waitingThread = CompletableFuture<Thread>()
        val waitingResult = CompletableFuture<Result<ScriptResult>>()
        val rejections = AtomicInteger(2)
        lateinit var session: ScriptSession
        session = DefaultScriptSession(
            emptyList(),
            Dispatchers.Default,
            executor = { threadFactory ->
                val delegate = Executors.newSingleThreadExecutor(threadFactory)
                // stands in for the session closed before taking the snippets of both callers
                object : ExecutorService by delegate {
                    override fun execute(command: Runnable) {
                        when (rejections.getAndDecrement()) {
                            2 -> {
                                // the first caller holds the turn, so the second one waits for it, suspended
                                suspend {
                                    session.execute("2")
                                }.startCoroutine(Continuation(EmptyCoroutineContext) {
                                    waitingThread.complete(Thread.currentThread())
                                    waitingResult.complete(it)
                                })
                                throw RejectedExecutionException()
                            }
                            1 -> throw RejectedExecutionException()
                            else -> delegate.execute(command)
                        }
                    }
                }
            }
        )
        try {
            // when
            val unlockingResult = runCatching {
                runBlocking {
                    session.execute("1")
                }
            }

            // then
            assert(unlockingResult.exceptionOrNull() is IllegalStateException)
            // bounded, so a hang fails the test instead of hanging the build
            assert(waitingResult.get(1, TimeUnit.MINUTES).exceptionOrNull() is IllegalStateException)
            assert(waitingThread.get() !== unlockingThread)
        } finally {
            session.close()
        }
    }

    /**
     * A waiting caller without a dispatcher, rejected by the session closed before taking its snippet,
     * is resumed from the resume executor, which might fail otherwise than by rejecting it,
     * like by an [OutOfMemoryError] of creating a worker, so it is rejected in place then,
     * and would keep its turn forever otherwise, so every next caller would wait for it, see #224.
     */
    @Test
    fun `should release turn of waiting caller rejected while the resume executor fails`() {
        // given
        val waitingResult = CompletableFuture<Result<ScriptResult>>()
        val rejections = AtomicInteger(2)
        lateinit var session: ScriptSession
        session = DefaultScriptSession(
            emptyList(),
            Dispatchers.Default,
            resumeExecutor = { throw IllegalStateException("resumeExecutor failed") },
            executor = { threadFactory ->
                val delegate = Executors.newSingleThreadExecutor(threadFactory)
                // stands in for the session closed before taking the snippets of both callers
                object : ExecutorService by delegate {
                    override fun execute(command: Runnable) {
                        when (rejections.getAndDecrement()) {
                            2 -> {
                                // the first caller holds the turn, so the second one waits for it, suspended
                                suspend {
                                    session.execute("2")
                                }.startCoroutine(Continuation(EmptyCoroutineContext) {
                                    waitingResult.complete(it)
                                })
                                throw RejectedExecutionException()
                            }
                            1 -> throw RejectedExecutionException()
                            else -> delegate.execute(command)
                        }
                    }
                }
            }
        )
        try {
            val unlockingResult = runCatching {
                runBlocking {
                    session.execute("1")
                }
            }
            assert(unlockingResult.exceptionOrNull() is IllegalStateException)
            // bounded, so a hang fails the test instead of hanging the build
            assert(waitingResult.get(1, TimeUnit.MINUTES).exceptionOrNull() is IllegalStateException)

            // when
            val next = CompletableFuture.supplyAsync {
                runBlocking {
                    session.execute("3")
                }
            }

            // then
            // bounded, so a hang fails the test instead of hanging the build
            next.get(1, TimeUnit.MINUTES) should {
                be<ScriptResult.Value>()
                have(value == 3)
            }
        } finally {
            session.close()
        }
    }

    /**
     * A dispatcher always failing to tell whether it dispatches would leave its caller unresumed, holding the lock of the session,
     * so the caller is resumed without it, with its failure, see #224.
     */
    @Test
    fun `should resume caller of dispatcher always failing to tell whether it dispatches`() {
        // given
        val failure = IllegalStateException("isDispatchNeeded failed")

        // when
        val (callerResult, nextResult) = callerOfFailingDispatcherAndNextResults(failure, inDispatch = false)

        // then
        assert(callerResult.exceptionOrNull()?.causes()?.any { it === failure } == true)
        nextResult should {
            be<ScriptResult.Value>()
            have(value == 43)
        }
    }

    /**
     * A dispatcher failing with a fatal error to tell whether it dispatches would leave its caller unresumed, holding the lock of the session,
     * so the caller is resumed without it, with the fatal error, like a fatal error of the snippet, see #224.
     */
    @Test
    fun `should resume caller of dispatcher failing with fatal error to tell whether it dispatches`() {
        // given
        val failure = InternalError("isDispatchNeeded failed")

        // when
        val (callerResult, nextResult) = callerOfFailingDispatcherAndNextResults(failure, inDispatch = false)

        // then
        assert(callerResult.exceptionOrNull()?.causes()?.any { it === failure } == true)
        nextResult should {
            be<ScriptResult.Value>()
            have(value == 43)
        }
    }

    /**
     * A dispatcher failing to dispatch, like one of an executor shut down, would leave its caller unresumed, holding the lock of the session,
     * so the caller is resumed without it, with its failure, see #224.
     */
    @Test
    fun `should resume caller of dispatcher failing to dispatch`() {
        // given
        val failure = RejectedExecutionException("dispatch failed")

        // when
        val (callerResult, nextResult) = callerOfFailingDispatcherAndNextResults(failure, inDispatch = true)

        // then
        assert(callerResult.exceptionOrNull()?.causes()?.any { it === failure } == true)
        nextResult should {
            be<ScriptResult.Value>()
            have(value == 43)
        }
    }

    /**
     * Executes a snippet of a caller of a dispatcher, which fails with the [failure] once the snippet is evaluated,
     * to tell whether it dispatches, or [inDispatch], to dispatch, and then a snippet of another caller.
     *
     * @return the result of the caller of the failing dispatcher, and the one of the other caller.
     */
    private fun callerOfFailingDispatcherAndNextResults(
        failure: Throwable,
        inDispatch: Boolean
    ): Pair<Result<ScriptResult>, ScriptResult> {
        val failing = AtomicBoolean()
        val session = ScriptSession {
            provide<AtomicBoolean>("failing", failing)
        }
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean {
                if (failing.get() && !inDispatch) throw failure
                return true
            }
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (failing.get() && inDispatch) throw failure
                executor.execute(block)
            }
        }
        try {
            val callerResult = CompletableFuture<Result<ScriptResult>>()
            suspend {
                session.execute("failing.set(true); 42")
            }.startCoroutine(Continuation(dispatcher) {
                callerResult.complete(it)
            })
            // bounded, so a hang fails the test instead of hanging the build
            val result = callerResult.get(1, TimeUnit.MINUTES)
            val next = CompletableFuture.supplyAsync {
                runBlocking {
                    session.execute("43")
                }
            }
            return result to next.get(1, TimeUnit.MINUTES)
        } finally {
            session.close()
            executor.shutdown()
        }
    }

    /** This throwable followed by its causes. */
    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { exception -> exception.cause }

    /**
     * Starts a caller in the [context], waiting for the snippet of another caller, with [Dispatchers.Default] saturated,
     * while its snippet would free it.
     *
     * @return whether the snippet of the waiting caller ran while [Dispatchers.Default] is saturated,
     * and the result of the caller after it is freed.
     */
    private fun waitingCallerWithDispatchersDefaultSaturated(context: CoroutineContext): Pair<Boolean, Result<ScriptResult>> {
        val started = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val ran = CountDownLatch(1)
        // stands in for Dispatchers.Default with all its workers blocked until freed
        val freed = CountDownLatch(1)
        val saturated = Executors.newSingleThreadExecutor()
        saturated.execute { freed.await() }
        val session = DefaultScriptSession(
            listOf(
                ScriptProperty("started", typeOf<CountDownLatch>(), started),
                ScriptProperty("proceed", typeOf<CountDownLatch>(), proceed),
                ScriptProperty("ran", typeOf<CountDownLatch>(), ran)
            ),
            Dispatchers.Default,
            resumeExecutor = saturated
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val unlocking = CoroutineScope(executor.asCoroutineDispatcher()).async {
                session.execute("started.countDown(); proceed.await(); 1")
            }
            started.await()
            val waitingResult = CompletableFuture<Result<ScriptResult>>()
            suspend {
                session.execute("ran.countDown(); 2")
            }.startCoroutine(Continuation(context) {
                waitingResult.complete(it)
            })
            // started in place, and not completed, so suspended on the lock held by the unlocking caller,
            // as execute suspends only there before submitting the snippet
            assert(!waitingResult.isDone)
            proceed.countDown()
            // bounded, so a hang fails the test instead of hanging the build
            val ranSaturated = ran.await(1, TimeUnit.MINUTES)
            freed.countDown()
            val result = waitingResult.get(1, TimeUnit.MINUTES)
            runBlocking { unlocking.await() } should {
                be<ScriptResult.Value>()
                have(value == 1)
            }
            return ranSaturated to result
        } finally {
            freed.countDown()
            session.close()
            executor.shutdown()
            saturated.shutdown()
        }
    }

    /**
     * Starts a caller in the [context], waiting for the snippet of a caller on the dispatcher of a single thread, the unlocking one,
     * on that thread if [startedOnUnlockingThread],
     * and closes the session, which fails the snippet of the unlocking caller, and rejects the waiting one,
     * with the thread of the session shut down before the unlocking caller resumes.
     *
     * @return the thread on which the waiting caller completed, and the thread of the unlocking caller.
     */
    private fun waitingCallerAndUnlockingThreads(
        context: CoroutineContext,
        startedOnUnlockingThread: Boolean = false
    ): Pair<Thread, Thread> {
        val executor = Executors.newSingleThreadExecutor()
        val unlockingThread = CompletableFuture.supplyAsync({ Thread.currentThread() }, executor).get()
        val started = CountDownLatch(1)
        val session = ScriptSession {
            provide<CountDownLatch>("started", started)
        }
        try {
            val unlocking = CoroutineScope(executor.asCoroutineDispatcher()).async {
                session.execute("started.countDown(); Thread.sleep(Long.MAX_VALUE)")
            }
            started.await()
            val waitingThread = CompletableFuture<Thread>()
            val waitingResult = CompletableFuture<Result<ScriptResult>>()
            val start = {
                suspend {
                    session.execute("42")
                }.startCoroutine(Continuation(context) {
                    waitingThread.complete(Thread.currentThread())
                    waitingResult.complete(it)
                })
                // started in place, and not completed, so suspended on the lock held by the unlocking caller,
                // as execute suspends only there before submitting the snippet
                !waitingResult.isDone
            }
            val suspended = if (startedOnUnlockingThread) CompletableFuture.supplyAsync({ start() }, executor).get() else start()
            assert(suspended)
            // the unlocking caller resumes only after the session is closed, with its thread shut down
            val gate = CountDownLatch(1)
            executor.execute { gate.await() }

            // rejected by the closed session, completing on the thread it was resumed on
            session.close()
            gate.countDown()

            // bounded, so a hang fails the test instead of hanging the build
            assert(waitingResult.get(1, TimeUnit.MINUTES).exceptionOrNull() is IllegalStateException)
            runBlocking { unlocking.await() } should {
                be<ScriptResult.Error>()
            }
            return waitingThread.get() to unlockingThread
        } finally {
            session.close()
            executor.shutdown()
        }
    }

    /** Runs the commands on [Dispatchers.Default], counting them by the [hops]. */
    private fun countingHops(hops: AtomicInteger) = Executor { command ->
        hops.incrementAndGet()
        Dispatchers.Default.asExecutor().execute(command)
    }

    /**
     * A caller with a continuation interceptor which is not a dispatcher would resume in place, on the thread of the session, see #172.
     */
    @Test
    fun `should not resume caller of non-dispatcher interceptor on the session thread`() {
        // given
        val interceptor = object : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
            override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> = continuation
        }
        val threads = CompletableFuture<Result<Pair<Any?, Thread>>>()

        // when
        suspend {
            val result = session.execute("Thread.currentThread()")
            assert(result is ScriptResult.Value)
            result.value to Thread.currentThread()
        }.startCoroutine(Continuation(interceptor) {
            threads.complete(it)
        })

        // then
        // bounded, so a hang fails the test instead of hanging the build
        val (sessionThread, callerThread) = threads.get(1, TimeUnit.MINUTES).getOrThrow()
        assert(callerThread !== sessionThread)
    }

    /**
     * A caller whose continuation interceptor fails to intercept its execution, after it took its turn,
     * fails, but would keep the turn forever, so every next caller would wait for it, see #224.
     */
    @Test
    fun `should release turn of caller whose interceptor fails`() {
        // given
        val failure = IllegalStateException("interceptContinuation failed")
        val interceptions = AtomicInteger()
        val interceptor = object : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
            override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
                // the first interception starts the caller, the next one is the one of its execution, holding its turn
                if (interceptions.incrementAndGet() == 2) throw failure
                return continuation
            }
        }
        val callerResult = CompletableFuture<Result<ScriptResult>>()
        suspend {
            session.execute("1")
        }.startCoroutine(Continuation(interceptor) {
            callerResult.complete(it)
        })
        assert(callerResult.get(1, TimeUnit.MINUTES).exceptionOrNull() === failure)

        // when
        val next = CompletableFuture.supplyAsync {
            runBlocking {
                session.execute("2")
            }
        }

        // then
        // bounded, so a hang fails the test instead of hanging the build
        next.get(1, TimeUnit.MINUTES) should {
            be<ScriptResult.Value>()
            have(value == 2)
        }
    }

    /**
     * A thread context element of the caller, like a thread local of a logging context as its `asContextElement`,
     * is installed on the thread of the session for the snippet, and removed after it, see #162.
     */
    @Test
    fun `should install thread context element of the caller on the session thread`() = runTest {
        // given
        val threadLocal = ThreadLocal<String>()
        ScriptSession {
            provide<ThreadLocal<String>>("threadLocal", threadLocal)
        }.use { session ->

            // when
            val withElement = withContext(threadLocal.asContextElement("caller")) {
                session.execute("threadLocal.get()")
            }
            val withoutElement = session.execute("threadLocal.get()")

            // then
            withElement should {
                be<ScriptResult.Value>()
                have(value == "caller")
            }
            withoutElement should {
                be<ScriptResult.Value>()
                have(value == null)
            }
        }
    }

    /** See #143. */
    @Test
    fun `should cancel awaited block when the session is closed`() = runTest {
        // given
        val started = Job()
        val stopped = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
            provide<CompletableJob>("stopped", stopped)
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                await {
                    started.complete()
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete()
                    }
                }
            """.trimIndent())
        }
        started.join()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("The session is closed" in message)
        }
        assert(stopped.isCompleted)
    }

    /** See #143. */
    @Test
    fun `should interrupt blocking snippet when the session is closed`() = runTest {
        // given
        val sleeping = CompletableDeferred<Thread>()
        val session = ScriptSession {
            provide<CompletableDeferred<Thread>>("sleeping", sleeping)
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                sleeping.complete(Thread.currentThread())
                Thread.sleep(600_000)
            """.trimIndent())
        }
        sleeping.await().awaitSleeping()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("InterruptedException" in message)
        }
    }

    /** The interruption of closing the session is like any other to the snippet, see #160. */
    @Test
    fun `should return value of snippet catching the interruption when the session is closed`() = runTest {
        // given
        val sleeping = CompletableDeferred<Thread>()
        val session = ScriptSession {
            provide<CompletableDeferred<Thread>>("sleeping", sleeping)
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                sleeping.complete(Thread.currentThread())
                try {
                    Thread.sleep(600_000)
                    "slept"
                } catch (e: InterruptedException) {
                    "interrupted"
                }
            """.trimIndent())
        }
        sleeping.await().awaitSleeping()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Value>()
            have(value == "interrupted")
        }
    }

    /** The cancellation of the awaited block by closing the session is like any other to the snippet, see #160. */
    @Test
    fun `should return value of snippet catching the cancellation of awaited block when the session is closed`() = runTest {
        // given
        val started = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                try {
                    await {
                        started.complete()
                        awaitCancellation()
                    }
                } catch (e: CancellationException) {
                    "cancelled"
                }
            """.trimIndent())
        }
        started.join()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Value>()
            have(value == "cancelled")
        }
    }

    /** The caller cannot cancel the evaluation, but closing the session can, see #135 and #143. */
    @Test
    fun `should cancel awaited block of caller without a Job when the session is closed`() {
        // given
        val started = Job()
        val stopped = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
            provide<CompletableJob>("stopped", stopped)
        }
        val execution = CompletableFuture<Result<ScriptResult>>()
        suspend {
            session.execute("""
                await {
                    started.complete()
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete()
                    }
                }
            """.trimIndent())
        }.startCoroutine(Continuation(EmptyCoroutineContext) {
            execution.complete(it)
        })
        runBlocking { started.join() }

        // when
        session.close()

        // then
        // bounded, so a hang fails the test instead of hanging the build
        execution.get(1, TimeUnit.MINUTES).getOrThrow() should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("The session is closed" in message)
        }
        assert(stopped.isCompleted)
    }

    /**
     * Waits until this thread of a session sleeps in a blocking call of its snippet,
     * so the interruption reaches the blocking call, not the snippet before it, like the "started" signalled from within `await`.
     */
    private suspend fun Thread.awaitSleeping() {
        while (state != Thread.State.TIMED_WAITING) yield()
    }

    /** See #143. */
    @Test
    fun `should release session closed while awaiting`() {
        // given
        val session = sessionClosedWhileAwaiting()

        // when
        collectGarbageUntilCleared(session)

        // then
        assert(session.get() == null)
    }

    /**
     * The weak reference to a session closed while its snippet awaits forever,
     * created outside of `runTest`, as a frame of its coroutine can keep a stale reference to the session.
     */
    private fun sessionClosedWhileAwaiting(): WeakReference<Any> {
        val started = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
        }
        val execution = CompletableFuture.supplyAsync {
            runBlocking {
                session.execute("""
                    await {
                        started.complete()
                        awaitCancellation()
                    }
                """.trimIndent())
            }
        }
        runBlocking { started.join() }
        session.close()
        // bounded, so a hang fails the test instead of hanging the build
        execution.get(1, TimeUnit.MINUTES)
        return WeakReference(session)
    }

    /**
     * The properties are declared, as their values are released only after the declaration,
     * while the snippet is rejected, as it would be cancelled before it ran, see #142 and #162.
     */
    @Test
    fun `should reject snippet of session closed while declaring its properties`() = runTest {
        // given
        val closedWhileDeclaring = AtomicBoolean()
        lateinit var session: DefaultScriptSession
        val stringType = typeOf<String>()
        // the type is rendered into the source of the declaration on the thread of the session,
        // so the session is closed there, past the check of a closed session, before the declaration is evaluated
        val type = object : KType by stringType {
            override val classifier: KClassifier?
                get() {
                    if (Thread.currentThread().name.startsWith("script-session-")
                        && closedWhileDeclaring.compareAndSet(false, true)
                    ) {
                        session.close()
                    }
                    return stringType.classifier
                }
            override fun toString() = stringType.toString()
        }
        session = DefaultScriptSession(listOf(ScriptProperty("greeting", type, "Hello")), Dispatchers.Default)

        // when
        val exception = assertFailsWith<IllegalStateException> {
            session.execute("greeting")
        }

        // then
        assert(closedWhileDeclaring.get())
        assert(exception.message == "The session is closed")
        assert(session.repl != null)
    }

    /** Guards the reading of a property by the snippet running when the session is closed, passes without the fix of #142. */
    @Test
    fun `should read property in snippet running when the session is closed`() = runTest {
        // given
        val started = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
            provide("greeting", "Hello")
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                started.complete()
                // spinning without checking the interruption, nor awaiting, so it runs until the session is closed, and reads the property then
                while (isActive) Thread.onSpinWait()
                greeting
            """.trimIndent())
        }
        started.join()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Value>()
            have(value == "Hello")
        }
    }

    /** Guards the reading of a property by a coroutine outliving the closing of the session, passes without the fix of #142. */
    @Test
    fun `should read property in coroutine launched by snippet when the session is closed`() = runTest {
        // given
        val started = Job()
        val read = CompletableDeferred<String>()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
            provide<CompletableDeferred<String>>("read", read)
            provide("greeting", "Hello")
        }
        session.execute("""
            launch {
                try {
                    started.complete()
                    awaitCancellation()
                } finally {
                    read.complete(greeting)
                }
            }
        """.trimIndent())
        started.join()

        // when
        session.close()

        // then
        assert(read.await() == "Hello")
    }

    /** Guards the release of the value of a property with the session, passes without the fix of #142. */
    @Test
    fun `should release property value of closed session`() {
        // given
        val value = propertyValueOfClosedSession()

        // when
        collectGarbageUntilCleared(value)

        // then
        assert(value.get() == null)
    }

    /**
     * The weak reference to the value of a property of a closed session, declared by a snippet,
     * created outside of `runTest`, as a frame of its coroutine can keep a stale reference to the session.
     */
    private fun propertyValueOfClosedSession(): WeakReference<Any> {
        val value = Any()
        val session = ScriptSession(listOf(property("value", value)))
        val result = runBlocking { session.execute("value.hashCode()") }
        assert(result is ScriptResult.Value)
        session.close()
        return WeakReference(value)
    }

    @Test
    fun `should execute all snippets of the session on the same thread`() = runTest {
        // when
        val first = session.execute("Thread.currentThread()")
        val second = session.execute("Thread.currentThread()")

        // then
        assert((first as ScriptResult.Value).value === (second as ScriptResult.Value).value)
        assert(first.value !== Thread.currentThread())
    }

    @Test
    fun `should execute sessions on separate threads with separate state`() = runTest {
        ScriptSession().use { session1 ->
            ScriptSession().use { session2 ->
                // given
                session1.execute("val name = \"one\"")
                session2.execute("val name = \"two\"")

                // when
                val result1 = session1.execute("name to Thread.currentThread()")
                val result2 = session2.execute("name to Thread.currentThread()")

                // then
                val (name1, thread1) = (result1 as ScriptResult.Value).value as Pair<*, *>
                val (name2, thread2) = (result2 as ScriptResult.Value).value as Pair<*, *>
                assert(name1 == "one" && name2 == "two" && thread1 !== thread2)
            }
        }
    }

    @Test
    fun `should execute concurrently submitted snippets one after another`() = runTest {
        // given
        session.execute("var counter = 0")

        // when
        (1..20).map {
            async(kotlinx.coroutines.Dispatchers.Default) {
                session.execute("counter++")
            }
        }.awaitAll()
        val result = session.execute("counter")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == 20)
        }
    }

    @Test
    fun `should reject execution in closed session`() = runTest {
        // given
        session.close()

        // when
        val exception = assertFailsWith<IllegalStateException> {
            session.execute("1")
        }

        // then
        assert(exception.message == "The session is closed")
    }

    /** See #141. */
    @Test
    fun `should reject snippet of session closed before dispatching it to the thread of the session`() = runTest {
        // given
        lateinit var closedSession: ScriptSession
        // closes the session after the check of a closed session in execute, before the snippet is dispatched
        closedSession = DefaultScriptSession(emptyList(), Dispatchers.Default) { threadFactory ->
            InterceptedExecutor(Executors.newSingleThreadExecutor(threadFactory)) { index, execute ->
                if (index == 0) closedSession.close()
                execute()
            }
        }

        // when
        val exception = assertFailsWith<IllegalStateException> {
            closedSession.execute("Thread.currentThread().name")
        }

        // then
        assert(exception.message == "The session is closed")
    }

    /** See #141. */
    @Test
    fun `should reject snippet of session closed before dispatching it to the thread of the session after the disposal`() = runTest {
        // given
        val disposalQueued = CompletableFuture<Unit>()
        val snippetQueued = CompletableFuture<Unit>()
        lateinit var closedSession: ScriptSession
        closedSession = DefaultScriptSession(emptyList(), Dispatchers.Default) { threadFactory ->
            InterceptedExecutor(Executors.newSingleThreadExecutor(threadFactory)) { index, execute ->
                when (index) {
                    // the snippet, past the check of a closed session, closed on another thread
                    0 -> {
                        Thread { closedSession.close() }.start()
                        disposalQueued.get(1, TimeUnit.MINUTES)
                        execute()
                        snippetQueued.complete(Unit)
                    }
                    // the disposal, queued before the snippet, while the thread is not shut down yet
                    1 -> {
                        execute()
                        disposalQueued.complete(Unit)
                        snippetQueued.get(1, TimeUnit.MINUTES)
                    }
                    else -> execute()
                }
            }
        }

        // when
        val exception = assertFailsWith<IllegalStateException> {
            closedSession.execute("Thread.currentThread().name")
        }

        // then
        assert(exception.message == "The session is closed")
    }

    /**
     * Closing the session again while it is being closed, from within the submission of its disposal to its thread,
     * past the mark of a closed session, like a concurrent closing passing the check of a closed session, see #141 and #162.
     */
    @Test
    fun `should ignore closing the session again while it is being closed`() = runTest {
        // given
        lateinit var session: ScriptSession
        var closingAgain: Result<Unit>? = null
        val submissions = AtomicInteger()
        session = DefaultScriptSession(emptyList(), Dispatchers.Default) { threadFactory ->
            InterceptedExecutor(Executors.newSingleThreadExecutor(threadFactory)) { index, execute ->
                submissions.incrementAndGet()
                if (index == 0) closingAgain = runCatching { session.close() }
                execute()
            }
        }

        // when
        session.close()

        // then
        assert(closingAgain?.isSuccess == true)
        assert(submissions.get() == 1)
        val exception = assertFailsWith<IllegalStateException> {
            session.execute("1")
        }
        assert(exception.message == "The session is closed")
    }

    /** See #209. */
    @Test
    fun `should release session closed while disposal of its compiler fails`() = runTest {
        // given a session, with a property, a dependency, and a disposable of its compiler failing to dispose
        val threads = CopyOnWriteArrayList<Thread>()
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val failingSession = DefaultScriptSession(listOf(property("name", "value")), Dispatchers.Default) { threadFactory ->
            Executors.newSingleThreadExecutor { runnable ->
                threadFactory.newThread(runnable).apply {
                    setUncaughtExceptionHandler { _, e -> uncaught += e }
                    threads += this
                }
            }
        }
        val dependencies = createTempDirectory("dependencies")
        try {
            dependencies.resolve("marker.txt").writeText("marker")
            failingSession.dependencyResolver.classLoader.add(listOf(dependencies.toFile()))
            failingSession.execute("1")
            Disposer.register(failingSession.repl!!.disposable, Disposable { error("Disposal failed") })

            // when
            val warnings = LogCollector("com.xemantic.kotlin.script").use { logs ->
                failingSession.close()
                threads.forEach { it.join(TimeUnit.MINUTES.toMillis(1)) }
                logs.events.filter { it.level == Level.WARN }
            }

            // then
            val lookup = runCatching {
                failingSession.dependencyResolver.versionLookup.latestVersion("com.xemantic.fixture", "greeter", emptyList())
            }.exceptionOrNull()
            val value = runCatching { ScriptPropertyValues.get(failingSession.id, "name") }.exceptionOrNull()
            assert(threads.none { it.isAlive })
            // the failure of the disposal reaches the release of the session, which logs it instead of throwing it
            assert(
                warnings.map { it.formattedMessage to it.throwableProxy?.message } ==
                    listOf("script-session-${failingSession.id}: releasing the session failed" to "Disposal failed")
            )
            assert(lookup is IllegalStateException && lookup.message == "The version lookup is closed")
            assert(failingSession.dependencyResolver.classLoader.getResource("marker.txt") == null)
            assert(value is IllegalStateException && value.message == "The session is closed")
            assert(uncaught.isEmpty())
        } finally {
            dependencies.toFile().deleteRecursively()
        }
    }

    /**
     * A smoke test of closing the session from many threads at once, repeatedly,
     * guarding only that no exception escapes any of the closings,
     * as it passes also with the check of a closed session read, then written, instead of the compare-and-set,
     * since the window between them is a single volatile read, see #141 and #162,
     * while the order of marking the session closed and releasing it is pinned by the test above.
     */
    @Test
    fun `should close session concurrently from many threads`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(100) {
                // given
                val session = ScriptSession()
                val barrier = CyclicBarrier(threads)

                // when
                val closings = List(threads) {
                    pool.submit<Result<Unit>> {
                        barrier.await()
                        runCatching { session.close() }
                    }
                }

                // then
                closings.forEach {
                    // bounded, so a deadlock fails the test instead of hanging the build
                    assert(it.get(1, TimeUnit.MINUTES).exceptionOrNull() == null)
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `should run snippet using session executing another snippet`() = runTest {
        ScriptSession().use { inner ->
            ScriptSession {
                "inner" provides inner
            }.use { outer ->
                // when
                val result = outer.execute("""
                    await { inner.execute("throw Exception(\"inner failure\")") }
                """.trimIndent())

                // then
                result should {
                    be<ScriptResult.Value>()
                    value should {
                        be<ScriptResult.Error>()
                        have(phase == ExecutionPhase.EVALUATION)
                        message sameAs """
                            java.lang.Exception: inner failure
                              at <snippet body>(snippet1.kts:1)
                              | throw Exception("inner failure")
                        """.trimIndent()
                    }
                }
            }
        }
    }

}

/** Calls the [interceptor] with the index of the call, and the [execute][ExecutorService.execute] of the [delegate] to proceed with. */
private class InterceptedExecutor(
    private val delegate: ExecutorService,
    private val interceptor: (index: Int, execute: () -> Unit) -> Unit
) : ExecutorService by delegate {

    private val calls = AtomicInteger()

    override fun execute(command: Runnable) {
        interceptor(calls.getAndIncrement()) { delegate.execute(command) }
    }

}
