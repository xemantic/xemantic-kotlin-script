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
import java.lang.management.ManagementFactory
import java.lang.management.MonitorInfo
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ScriptCompilationCacheTest {

    @Test
    fun `should compile the script only once when executed again with other dependency values`() = runTest {
        // given
        val script = """
            greeting.uppercase()
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script, listOf(dependency("greeting", "foo")))
        val result2 = executor.execute(script, listOf(dependency("greeting", "bar")))

        // then
        result1 should {
            be<ScriptResult.Value>()
            have(value == "FOO")
        }
        result2 should {
            be<ScriptResult.Value>()
            have(value == "BAR")
        }
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should compile the script only once when dependencies are given in another order`() = runTest {
        // given
        val script = """
            greeting.repeat(times)
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script, listOf(dependency("greeting", "foo"), dependency("times", 2)))
        val result2 = executor.execute(script, listOf(dependency("times", 3), dependency("greeting", "bar")))

        // then
        result1 should {
            be<ScriptResult.Value>()
            have(value == "foofoo")
        }
        result2 should {
            be<ScriptResult.Value>()
            have(value == "barbarbar")
        }
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should compile the script again when dependency types differ`() = runTest {
        // given
        val script = $$"""
            "$value"
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script, listOf(dependency("value", "foo")))
        val result2 = executor.execute(script, listOf(dependency("value", 42)))

        // then
        result1 should {
            be<ScriptResult.Value>()
            have(value == "foo")
        }
        result2 should {
            be<ScriptResult.Value>()
            have(value == "42")
        }
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should compile the script again when type arguments of dependency differ`() = runTest {
        // given
        val script = """
            "${'$'}items"
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script, listOf(dependency("items", listOf("foo"))))
        val result2 = executor.execute(script, listOf(dependency("items", listOf(42))))

        // then
        result1 should {
            be<ScriptResult.Value>()
            have(value == "[foo]")
        }
        result2 should {
            be<ScriptResult.Value>()
            have(value == "[42]")
        }
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should compile the script calling functions before their declarations within lambdas, classes and extension functions once`() = runTest {
        // given
        val scripts = listOf(
            // without referring to a local property within a lambda, which a receiver might declare, see ScriptSource.receiverMemberCandidates
            "listOf(1, 2).forEach { println(show(it)) }\nfun show(i: Int) = \"n\$i\"\n1",
            "val r = listOf(1, 2).map { format(it) }.filter { valid(it) }\nfun format(i: Int) = \"#\$i\"\nfun valid(s: String) = s.isNotEmpty()\nr",
            "val s = buildString { append(\"x\") }\nfun append(t: String) = t + \"!\"\nappend(s)",
            "class A { fun f() = describe(1) }\nfun describe(i: Int) = \"n\$i\"\nA().f()",
            "fun Int.twice() = double(this)\nfun double(i: Int) = i * 2\n3.twice()"
        )
        val executor = DefaultScriptExecutor()

        // when
        scripts.forEach { executor.execute(it) }

        // then
        assert(executor.compilationCount == scripts.size)
    }

    @Test
    fun `should compile the script on every execution by default`() = runTest {
        // given
        val script = """
            "foo"
        """.trimIndent()
        val executor = DefaultScriptExecutor()

        // when
        executor.execute(script)
        executor.execute(script)

        // then
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should not cache the script which failed to compile`() = runTest {
        // given
        val script = """
            undefinedVariable
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script)
        val result2 = executor.execute(script)

        // then
        listOf(result1, result2).forEach { result ->
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    script.kts:1:1: error: Unresolved reference 'undefinedVariable'.
                      | <error>undefinedVariable</error>
                """.trimIndent()
            }
        }
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should not confirm references within nested coroutine scopes next to moved declarations of their names`() = runTest {
        // given
        val scripts = listOf(
            "val r = supervisorScope { run { isActive } }\nval isActive get() = \"mine\"\nr",
            "val r = coroutineScope { launch { } }\nfun launch(block: () -> Unit): String = \"mine\"\nr is Job",
            "import kotlinx.coroutines.supervisorScope\nval r = supervisorScope { isActive }\nval isActive get() = \"mine\"\nr"
        )
        val executor = DefaultScriptExecutor()

        // when
        val results = scripts.map { executor.execute(it) }

        // then
        assert(results.map { (it as? ScriptResult.Value)?.value } == listOf(true, true, true))
        assert(executor.compilationCount == scripts.size)
    }

    @Test
    fun `should count the compilation confirming the reference to later declared dependency`() = runTest {
        // given
        val script = """
            fun show() = x
            val x = 2
            show()
        """.trimIndent()
        val executor = DefaultScriptExecutor()

        // when
        val result = executor.execute(script, listOf(dependency("x", 1)))

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
        }
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should serve the compiled script rejected for referring to later declared dependency from the cache`() = runTest {
        // given
        val script = """
            fun show() = x
            val x = 2
            show()
        """.trimIndent()
        val dependencies = listOf(dependency("x", 1))
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script, dependencies)
        val result2 = executor.execute(script, dependencies)

        // then
        listOf(result1, result2).forEach { result ->
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    script.kts:1:14: error: 'x' refers to the dependency, as the property 'x' of the script body is declared later, at line 2.
                      | fun show() = <error>x</error>
                      hint: declare the property before line 1, or rename it.
                """.trimIndent()
            }
        }
        // the compilation and the confirming one, only for the first execution
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should serve the compiled scripts rejected for referring to wrong declarations from the cache`() = runTest {
        // given
        val scripts = listOf(
            // hidden local function
            "val strict = \"true\".toBoolean()\nfun check(flag: Boolean) { if (strict && !flag) throw IllegalStateException(\"strict\") }\nsealed interface S\nclass T : S { fun f() = check(false) }\nT().f()",
            // resolved to the coroutine scope
            "cancel()\nfun cancel() { println(\"order cancelled\") }\n\"done\"",
            "val isActive get() = false\nisActive",
            // shadowed function
            "val cancel = { r: String -> \"prop \" + r }\nval x = cancel(\"a\")\nfun cancel(reason: String) = \"fun \" + reason\nx"
        )
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)
        val results1 = scripts.map { executor.execute(it) }
        val compilationCount = executor.compilationCount

        // when
        val results2 = scripts.map { executor.execute(it) }

        // then
        assert(results1.all { it is ScriptResult.Error && it.phase == ExecutionPhase.COMPILATION })
        assert(results2.map { (it as? ScriptResult.Error)?.message } == results1.map { (it as? ScriptResult.Error)?.message })
        assert(executor.compilationCount == compilationCount)
    }

    @Test
    fun `should not cache the script rejected for referring to later declared dependency which also failed to compile`() = runTest {
        // given
        val script = """
            fun show(): String = x
            val x = "a"
            show()
        """.trimIndent()
        val dependencies = listOf(dependency("x", 1))
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)
        val result1 = executor.execute(script, dependencies)
        val compilationCount = executor.compilationCount

        // when
        val result2 = executor.execute(script, dependencies)

        // then
        result1 should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            have(message.startsWith("script.kts:1:22: error: 'x' refers to the dependency"))
        }
        assert(result2 is ScriptResult.Error && result2.message == (result1 as ScriptResult.Error).message)
        // the compilation and the confirming one, for each execution
        assert(compilationCount == 2)
        assert(executor.compilationCount == 4)
    }

    @Test
    fun `should report unmatched closing brace of the script without caching its failed compilation`() = runTest {
        // given
        val script = """
            42 }
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script)
        val result2 = executor.execute(script)

        // then
        listOf(result1, result2).forEach { result ->
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    script.kts:1:4: error: Syntax error: Unexpected '}' without matching '{'.
                      | 42 <error>}</error>
                """.trimIndent()
            }
        }
        assert(executor.compilationCount == 2)
    }

    @Test
    fun `should evict the least recently used script`() = runTest {
        // given
        val script1 = """
            "foo"
        """.trimIndent()
        val script2 = """
            "bar"
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 1)

        // when
        executor.execute(script1)
        executor.execute(script2)
        executor.execute(script1)

        // then
        assert(executor.compilationCount == 3)
    }

    @Test
    fun `should report warnings and runtime exception of the cached script`() = runTest {
        // given
        val script = """
            val text = 42 as String
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val result1 = executor.execute(script)
        val result2 = executor.execute(script)

        // then
        listOf(result1, result2).forEach { result ->
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
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should compile the script only once when executed concurrently`() = runTest {
        // given
        val script = """
            "foo"
        """.trimIndent()
        val executor = DefaultScriptExecutor(compilationCacheSize = 10)

        // when
        val results = List(4) {
            async { executor.execute(script) }
        }.awaitAll()

        // then
        results.forEach { result ->
            result should {
                be<ScriptResult.Value>()
                have(value == "foo")
            }
        }
        assert(executor.compilationCount == 1)
    }

    @Test
    fun `should fail the compiling and concurrent callers when the compilation fails`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 10) { true }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0
        val compile: suspend () -> String = {
            compilations++
            delay(1.milliseconds)
            throw IllegalStateException("compiler failed")
        }

        // when
        val results = List(4) {
            async { runCatching { cache.getOrCompile(key, compile) } }
        }.awaitAll()

        // then
        assert(compilations == 1)
        results.forEach { result ->
            val failure = result.exceptionOrNull()
            assert(failure is IllegalStateException && failure.message == "compiler failed")
        }
    }

    @Test
    fun `should reject negative compilation cache size`() {
        // when
        val exception = assertFailsWith<IllegalArgumentException> {
            ScriptExecutor(compilationCacheSize = -1)
        }

        // then
        assert(exception.message == "compilationCacheSize must not be negative, but was: -1")
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should compile again when the compiling caller is cancelled`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 10) { it != "failure" }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0
        val compile: suspend () -> String = {
            compilations++
            delay(1.milliseconds)
            "failure"
        }
        val owner = launch { cache.getOrCompile(key, compile) }
        val waiter = async { cache.getOrCompile(key, compile) }
        runCurrent()

        // when
        owner.cancel()
        val result = waiter.await()

        // then
        assert(result == "failure")
        assert(compilations == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should compile again when the compiling caller is interrupted`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 10) { it != "failure" }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0
        val compile: suspend () -> String = {
            compilations++
            delay(1.milliseconds)
            if (compilations == 1) throw InterruptedException("owner interrupted")
            "failure"
        }
        val owner = async { runCatching { cache.getOrCompile(key, compile) } }
        val waiter = async { cache.getOrCompile(key, compile) }
        runCurrent()

        // when
        val ownerFailure = owner.await().exceptionOrNull()
        val result = waiter.await()

        // then
        assert(ownerFailure is InterruptedException)
        assert(result == "failure")
        assert(compilations == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should not evict the script still being compiled`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 1) { true }
        val key1 = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        val key2 = ScriptCompilationCache.Key(script = "bar", dependencies = emptyList())
        val compiled = CompletableDeferred<Unit>()
        var compilations = 0
        val compile1: suspend () -> String = {
            compilations++
            compiled.await()
            "foo"
        }
        val owner = async { cache.getOrCompile(key1, compile1) }
        runCurrent()
        cache.getOrCompile(key2) {
            compilations++
            "bar"
        }

        // when
        val waiter = async { cache.getOrCompile(key1, compile1) }
        runCurrent()
        compiled.complete(Unit)
        val results = listOf(owner.await(), waiter.await())

        // then
        assert(results == listOf("foo", "foo"))
        assert(compilations == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should resume the waiting caller without holding the lock of the compilations`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 10) { true }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        val compiled = CompletableDeferred<Unit>()
        val owner = async {
            cache.getOrCompile(key) {
                compiled.await()
                "foo"
            }
        }
        runCurrent()
        var monitorsHeldByWaiter: List<MonitorInfo>? = null
        // resumed inline by the completion of the compilation, as no dispatch is needed
        launch(Dispatchers.Unconfined) {
            cache.getOrCompile(key) { "bar" }
            monitorsHeldByWaiter = ManagementFactory.getThreadMXBean().getThreadInfo(
                longArrayOf(Thread.currentThread().id), true, false
            ).single().lockedMonitors.toList()
        }

        // when
        compiled.complete(Unit)
        owner.await()

        // then
        assert(monitorsHeldByWaiter == emptyList<MonitorInfo>())
    }

    @Test
    fun `should evict the compiled script before resuming the callers waiting for it`() = runTest {
        // given
        val resuming = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val cache = ScriptCompilationCache<String>(
            maxSize = 1,
            resume = { entry, result ->
                if (result == "foo") {
                    resuming.countDown()
                    resumed.await()
                }
                entry.complete(result)
            }
        ) { true }
        val key1 = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        val key2 = ScriptCompilationCache.Key(script = "bar", dependencies = emptyList())
        val compilations = Collections.synchronizedList(mutableListOf<String>())
        val owner = thread {
            runBlocking { cache.getOrCompile(key1) { compilations += "foo"; "foo" } }
        }
        resuming.await()

        // when
        cache.getOrCompile(key2) { compilations += "bar"; "bar" }
        resumed.countDown()
        owner.join()
        val results = listOf(
            cache.getOrCompile(key2) { compilations += "bar"; "bar" },
            cache.getOrCompile(key1) { compilations += "foo"; "foo" }
        )

        // then
        assert(results == listOf("bar", "foo"))
        // the first one evicted, as the cache keeps one script
        assert(compilations == listOf("foo", "bar", "foo"))
    }

    @Test
    fun `should keep the compiled script cached when resuming the waiting callers fails`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(
            maxSize = 10,
            // like a fatal error of a waiting caller resumed inline
            resume = { entry, result ->
                entry.complete(result)
                if (result == "foo") throw OutOfMemoryError("waiter out of memory")
            }
        ) { true }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0

        // when
        val failure = runCatching {
            cache.getOrCompile(key) { compilations++; "foo" }
        }.exceptionOrNull()
        val result = cache.getOrCompile(key) { compilations++; "bar" }

        // then
        assert(failure is OutOfMemoryError && failure.message == "waiter out of memory")
        assert(result == "foo")
        assert(compilations == 1)
    }

    @Test
    fun `should compile again when storing the compilation fails`() = runTest {
        // given
        var failing = true
        val cache = ScriptCompilationCache<String>(maxSize = 10) {
            if (failing) throw IllegalStateException("not stored") else true
        }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0
        val failure = runCatching {
            cache.getOrCompile(key) { compilations++; "foo" }
        }.exceptionOrNull()
        failing = false

        // when
        val result = cache.getOrCompile(key) { compilations++; "bar" }

        // then
        assert(failure is IllegalStateException && failure.message == "not stored")
        assert(result == "bar")
        assert(compilations == 2)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `should compile again for the waiting caller when the compilation fails fatally`() = runTest {
        // given
        val cache = ScriptCompilationCache<String>(maxSize = 10) { true }
        val key = ScriptCompilationCache.Key(script = "foo", dependencies = emptyList())
        var compilations = 0
        val compile: suspend () -> String = {
            compilations++
            delay(1.milliseconds)
            if (compilations == 1) throw OutOfMemoryError("owner out of memory")
            "foo"
        }
        val owner = async { runCatching { cache.getOrCompile(key, compile) } }
        // not failing the test process, if the error is shared
        val waiter = async { runCatching { cache.getOrCompile(key, compile) } }
        runCurrent()

        // when
        val ownerFailure = owner.await().exceptionOrNull()
        val result = waiter.await().getOrNull()

        // then
        assert(ownerFailure is OutOfMemoryError && ownerFailure.message == "owner out of memory")
        assert(result == "foo")
        assert(compilations == 2)
    }

    @Test
    fun `should not cache compilation of script referring to shadowed dependency`() = runTest {
        // given
        val valid = "limit + 1"
        val shadowing = "val limit = 5\nsealed interface S\nclass T : S { fun f() = limit }\nT().f()"
        val dependencies = listOf(dependency("limit", 10))
        val executor = DefaultScriptExecutor(compilationCacheSize = 1)

        // when
        val results = listOf(valid, shadowing).map { executor.execute(it, dependencies) }
        val compilationCount = executor.compilationCount
        val result = executor.execute(valid, dependencies)

        // then
        assert(results.map { it is ScriptResult.Value } == listOf(true, false))
        assert(result is ScriptResult.Value)
        // not evicted by the shadowing script
        assert(executor.compilationCount == compilationCount)
    }

    @Test
    fun `should not confirm this in local function referring to the receiver of a known lambda with receiver`() = runTest {
        // given
        val script = """
            val log = mutableListOf("a")
            fun size() = with(log) { this.size }
            fun text() = buildString { this.append(size()) }
            fun list() = log.apply { this.add("b") }
            text() + list()
        """.trimIndent()
        val executor = DefaultScriptExecutor()

        // when
        val result = executor.execute(script)

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "1[a, b]")
        }
        assert(executor.compilationCount == 1)
    }

}
