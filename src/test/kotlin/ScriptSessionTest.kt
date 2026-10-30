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
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

class ScriptSessionTest {

    @Test
    fun `should execute snippet returning a String`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("\"foo\"")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "foo")
                have(warnings == null)
            }
        }
    }

    @Test
    fun `should execute typical script crafted by an LLM`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep declarations between snippets`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep mutable state between snippets`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep state of objects between snippets`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should redeclare property in later snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep imports between snippets`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should call function declared later in the same snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should declare enum and sealed types`() = runTest {
        ScriptSession().use { session ->
            // given
            session.execute("""
                enum class Color { RED, GREEN }
                sealed interface Shape
                class Circle(val r: Int) : Shape
                class Square(val a: Int) : Shape
            """.trimIndent())

            // when
            val result = session.execute("""
                fun describe(shape: Shape): String = when (shape) {
                    is Circle -> "circle ${'$'}{shape.r}"
                    is Square -> "square ${'$'}{shape.a}"
                }
                describe(Circle(2)) + " " + Color.GREEN
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "circle 2 GREEN")
            }
        }
    }

    @Test
    fun `should return Unit for snippet without expression`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("val x = 1")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == Unit)
            }
        }
    }

    @Test
    fun `should return Unit for empty snippet`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == Unit)
            }
        }
    }

    @Test
    fun `should return null`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("null")

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == null)
            }
        }
    }

    @Test
    fun `should report unresolved reference`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report error at the line of the snippet named by its number`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report multiline error`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("""
                fun greet(name: String) = "Hello ${'$'}name"
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
    }

    @Test
    fun `should report syntax error`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should not keep declarations of snippet which failed to compile`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should not keep declarations of snippet which failed to compile in a function`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report internal failure of the compiler and continue the session`() = runTest {
        ScriptSession().use { session ->
            // given
            session.execute("val x = 41")

            // when
            val result = session.execute("val context: Int inline get() = coroutineContext.hashCode()")
            val next = session.execute("x + 1")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                have(message.startsWith("error: The compiler failed internally: "))
            }
            next should {
                be<ScriptResult.Value>()
                have(value == 42)
            }
        }
    }

    @Test
    fun `should hint declaring the type when class of earlier snippet is unresolved in declaration of inferred type`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should hint redeclaring value class property of earlier snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should continue the session after a failing snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep declarations of snippet preceding the exception it threw`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep declarations of many snippets earlier, also after failing snippets in between`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should redeclare function and class in later snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should keep calling the redeclared function from function declared before the redeclaration`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report warnings of successful snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report runtime exception`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report runtime exception thrown by function declared in earlier snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report exception with the root cause`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report method of class declared in snippet`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report exception class declared in snippet without the snippet class name`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report enum constant not found without the snippet class name`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should collapse frames of recursion`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report StackOverflowError with collapsed recursive frames`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report lambda frames`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("""
                listOf(1).map {
                    error("in lambda")
                }
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Error>()
                message sameAs """
                    java.lang.IllegalStateException: in lambda
                      at <snippet body>(snippet1.kts:2)
                      |     error("in lambda")
                """.trimIndent()
            }
        }
    }

    @Test
    fun `should report unicode characters`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should use the time API without opting in`() = runTest {
        // given
        val clock = object : Clock {
            override fun now() = Instant.fromEpochSeconds(42)
        }

        ScriptSession(listOf(dependency<Clock>("clock", clock))).use { session ->
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
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should await concurrent coroutines`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report exception thrown by awaited block`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should report suspending call without await`() = runTest {
        ScriptSession().use { session ->
            // when
            val result = session.execute("delay(1)")

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                have("can only be called from a coroutine or another suspend function" in message)
            }
        }
    }

    @Test
    fun `should keep coroutine launched by snippet running after it`() = runTest {
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should cancel coroutines launched by snippets when the session is closed`() = runTest {
        // given
        val stopped = Job()
        val session = ScriptSession {
            provide<CompletableJob>("stopped", stopped)
        }
        session.execute("""
            launch {
                try {
                    awaitCancellation()
                } finally {
                    stopped.complete()
                }
            }
        """.trimIndent())

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
        val started = Job()
        ScriptSession {
            provide<CompletableJob>("started", started)
        }.use { session ->
            val execution = launch(kotlinx.coroutines.Dispatchers.Default) {
                session.execute("""
                    started.complete()
                    Thread.sleep(60_000)
                """.trimIndent())
            }
            started.join()

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

    @Test
    fun `should execute all snippets of the session on the same thread`() = runTest {
        ScriptSession().use { session ->
            // when
            val first = session.execute("Thread.currentThread()")
            val second = session.execute("Thread.currentThread()")

            // then
            assert((first as ScriptResult.Value).value === (second as ScriptResult.Value).value)
            assert(first.value !== Thread.currentThread())
        }
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
        ScriptSession().use { session ->
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
    }

    @Test
    fun `should reject execution in closed session`() = runTest {
        // given
        val session = ScriptSession()
        session.close()

        // when
        val exception = assertFailsWith<IllegalStateException> {
            session.execute("1")
        }

        // then
        assert(exception.message == "The session is closed")
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

