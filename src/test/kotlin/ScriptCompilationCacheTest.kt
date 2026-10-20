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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

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
    fun `should report unmatched closing brace of the script without compiling it`() = runTest {
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
        assert(executor.compilationCount == 0)
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

}
