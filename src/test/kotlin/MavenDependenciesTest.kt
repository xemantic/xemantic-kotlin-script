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

import com.xemantic.kotlin.script.test.MavenRepository
import com.xemantic.kotlin.script.test.MavenRepositoryServer
import com.xemantic.kotlin.script.test.collectGarbageUntilCleared
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.sameAs
import com.xemantic.kotlin.test.should
import java.lang.ref.WeakReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

class MavenDependenciesTest {

    private val repository = "@file:Repository(\"${MavenRepository.url}\")"

    private val session = ScriptSession()

    @AfterTest
    fun close() {
        session.close()
    }

    @Test
    fun `should resolve dependency declared by snippet`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter:1.0")
            import fixture.greeter.Greeter
            Greeter.greet("Alice")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello Alice")
            have(resolvedVersions.isEmpty())
        }
    }

    @Test
    fun `should resolve transitive dependencies`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeting-card:1.0")
            fixture.card.GreetingCard.write("Alice") + fixture.greeter.Greeter.greet("Bob")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "[Hello Alice]Hello Bob")
        }
    }

    @Test
    fun `should keep resolved dependency in later snippets`() = runTest {
        // given
        session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter:1.0")
            import fixture.greeter.Greeter
            val greeting = Greeter.greet("Alice")
        """.trimIndent())

        // when
        val result = session.execute("""
            fun shout(name: String): String = Greeter.greet(name).uppercase()
            greeting + ", " + shout("Bob")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello Alice, HELLO BOB")
        }
    }

    @Test
    fun `should load dependency resolved by snippet which failed to compile`() = runTest {
        // given
        session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter:1.0")
            undefinedVariable
        """.trimIndent())

        // when
        val result = session.execute("fixture.greeter.Greeter.greet(\"Alice\")")

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hello Alice")
        }
    }

    @Test
    fun `should report unresolved dependency`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:missing:1.0")
            42
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            // Maven prints file:/path URLs
            val url = MavenRepository.url.replace("file:///", "file:/")
            message sameAs """
                snippet1.kts:2:1: error: Unresolved dependency 'com.xemantic.fixture:missing:1.0': Could not find artifact com.xemantic.fixture:missing:jar:1.0 in $url
                  | <error>@file:DependsOn("com.xemantic.fixture:missing:1.0")</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should resolve latest stable version of dependency declared without version`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter")
            fixture.greeter.Greeter.greet("Alice")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hi Alice")
            have(resolvedVersions == mapOf("com.xemantic.fixture:greeter" to "1.10"))
            have(warnings == null)
        }
    }

    @Test
    fun `should resolve latest pre-release version of dependency declared without version which has no stable one`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:preview")
            fixture.preview.Preview.version()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "0.1-beta")
            have(resolvedVersions == mapOf("com.xemantic.fixture:preview" to "0.1-beta"))
            have(warnings == null)
        }
    }

    @Test
    fun `should report resolved version of dependency declared without version with failed compilation`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter")
            fixture.greeter.Greeter.wave("Alice")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet1.kts:2:1: warning: Resolved 'com.xemantic.fixture:greeter' to its latest stable version '1.10'
                  | <error>@file:DependsOn("com.xemantic.fixture:greeter")</error>

                snippet1.kts:3:25: error: Unresolved reference 'wave'.
                  | fixture.greeter.Greeter.<error>wave</error>("Alice")
            """.trimIndent()
        }
    }

    @Test
    fun `should report resolved version of pre-release dependency declared without version with failed compilation`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:preview")
            fixture.preview.Preview.release()
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet1.kts:2:1: warning: Resolved 'com.xemantic.fixture:preview' to its latest pre-release version '0.1-beta', as it has no stable one
                  | <error>@file:DependsOn("com.xemantic.fixture:preview")</error>

                snippet1.kts:3:25: error: Unresolved reference 'release'.
                  | fixture.preview.Preview.<error>release</error>()
            """.trimIndent()
        }
    }

    @Test
    fun `should report resolved version of dependency declared without version with failed evaluation`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter")
            error(fixture.greeter.Greeter.greet("Alice"))
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.EVALUATION)
            message sameAs """
                java.lang.IllegalStateException: Hi Alice
                  at <snippet body>(snippet1.kts:3)
                  | error(fixture.greeter.Greeter.greet("Alice"))

                snippet1.kts:2:1: warning: Resolved 'com.xemantic.fixture:greeter' to its latest stable version '1.10'
                  | <error>@file:DependsOn("com.xemantic.fixture:greeter")</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should keep resolved version of dependency declared without version in later snippets`() = runTest {
        // given
        session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter")
        """.trimIndent())

        // when
        val result = session.execute("""
            @file:DependsOn("com.xemantic.fixture:greeter")
            fixture.greeter.Greeter.greet("Alice")
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Value>()
            have(value == "Hi Alice")
            have(resolvedVersions == mapOf("com.xemantic.fixture:greeter" to "1.10"))
        }
    }

    @Test
    fun `should resolve latest version of dependency declared without version with repository credentials`() = runTest {
        // given
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // when
            val result = session.execute("""
                @file:Repository("${server.url}", options = ["username=alice", "password=secret"])
                @file:DependsOn("com.xemantic.fixture:greeter")
                fixture.greeter.Greeter.greet("Alice")
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "Hi Alice")
                have(resolvedVersions == mapOf("com.xemantic.fixture:greeter" to "1.10"))
            }
        }
    }

    @Test
    fun `should look up dependency declared without version once in repository declared again`() = runTest {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val declaration = """@file:Repository("${server.url}", options = ["username=alice", "password=secret"])"""
            session.execute(declaration)

            // when
            val result = session.execute("""
                $declaration
                @file:DependsOn("com.xemantic.fixture:greeter")
                fixture.greeter.Greeter.greet("Alice")
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "Hi Alice")
            }
            assert(server.metadataRequests == 1)
        }
    }

    @Test
    fun `should look up dependency declared without version with corrected credentials of repository declared again`() = runTest {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val wrong = session.execute("""
                @file:Repository("${server.url}", options = ["username=alice", "password=wrong"])
                @file:DependsOn("com.xemantic.fixture:greeter")
                42
            """.trimIndent())
            assert(wrong is ScriptResult.Error)

            // when
            val result = session.execute("""
                @file:Repository("${server.url}", options = ["username=alice", "password=secret"])
                @file:DependsOn("com.xemantic.fixture:greeter")
                fixture.greeter.Greeter.greet("Alice")
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Value>()
                have(value == "Hi Alice")
            }
        }
    }

    @Test
    fun `should report unresolved dependency declared without version in repository without its credentials`() = runTest {
        // given
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // when
            val result = session.execute("""
                @file:Repository("${server.url}")
                @file:DependsOn("com.xemantic.fixture:greeter")
                42
            """.trimIndent())

            // then
            result should {
                be<ScriptResult.Error>()
                have(phase == ExecutionPhase.COMPILATION)
                message sameAs """
                    snippet1.kts:2:1: error: Unresolved dependency 'com.xemantic.fixture:greeter': Could not transfer metadata com.xemantic.fixture:greeter/maven-metadata.xml from/to ${server.url}: status code: 401, reason phrase: Unauthorized (401)
                      | <error>@file:DependsOn("com.xemantic.fixture:greeter")</error>
                """.trimIndent()
            }
        }
    }

    @Test
    fun `should report unresolved dependency declared without version`() = runTest {
        // when
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:missing")
            42
        """.trimIndent())

        // then
        result should {
            be<ScriptResult.Error>()
            have(phase == ExecutionPhase.COMPILATION)
            message sameAs """
                snippet1.kts:2:1: error: Unresolved dependency 'com.xemantic.fixture:missing': Could not find any version of it in ${MavenRepository.url}
                  | <error>@file:DependsOn("com.xemantic.fixture:missing")</error>
            """.trimIndent()
        }
    }

    @Test
    fun `should release closed session with its dependencies`() {
        // given
        val (session, dependencyClassLoader) = closedSessionWithDependency()

        // when
        collectGarbageUntilCleared(dependencyClassLoader)

        // then
        assert(session.get() == null && dependencyClassLoader.get() == null)
    }

    /**
     * The weak references to a closed session, and to the class loader of its dependency,
     * created outside of `runTest`, as a frame of its coroutine can keep a stale reference to the session.
     */
    private fun closedSessionWithDependency(): Pair<WeakReference<Any>, WeakReference<Any>> = runBlocking {
        val session = ScriptSession()
        val result = session.execute("""
            $repository
            @file:DependsOn("com.xemantic.fixture:greeter:1.0")
            fixture.greeter.Greeter::class.java.classLoader
        """.trimIndent())
        session.close()
        val classLoader = (result as ScriptResult.Value).value!!
        WeakReference<Any>(session) to WeakReference(classLoader)
    }

}
