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
import com.xemantic.kotlin.script.test.collectGarbageUntilCleared
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.reflect.KClassifier
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.script.experimental.api.ResultValue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Instant

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

    @Test
    fun `should report StackOverflowError of the compiler as a compilation failure and continue the session`() = runTest {
        // given
        session.execute("val x = 41")
        // nested deep enough to overflow the stack of the parser at any thread stack size, in milliseconds
        val depth = 100_000
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
            message sameAs """
                java.lang.IllegalStateException: in lambda
                  at <snippet body>(snippet1.kts:2)
                  |     error("in lambda")
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
                  at <snippet body>(snippet1.kts:7)
                  | check(numbers) { "too big" }
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
        val started = Job()
        val session = ScriptSession {
            provide<CompletableJob>("started", started)
        }
        val execution = async(Dispatchers.Default) {
            session.execute("""
                started.complete()
                Thread.sleep(600_000)
            """.trimIndent())
        }
        started.join()

        // when
        session.close()

        // then
        execution.await() should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            have("InterruptedException" in message)
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
        }
        assert(stopped.isCompleted)
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

    /** See #142. */
    @Test
    fun `should declare properties of session closed while declaring them`() = runTest {
        // given
        val closedWhileDeclaring = AtomicBoolean()
        lateinit var session: ScriptSession
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
        session = ScriptSession(listOf(ScriptProperty("greeting", type, "Hello")))

        // when
        val result = session.execute("greeting")

        // then
        assert(closedWhileDeclaring.get())
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello")
        }
    }

    /** See #142. */
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
                // neither interrupted, nor awaiting, so it runs until the session is closed
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

    /** See #142. */
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

    /** See #142. */
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
     * Closing the session from many threads at once, repeatedly,
     * as only the calls passing the check of a closed session together would fail, see #141.
     */
    @Test
    fun `should close session concurrently from many threads`() {
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        try {
            repeat(1000) {
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
