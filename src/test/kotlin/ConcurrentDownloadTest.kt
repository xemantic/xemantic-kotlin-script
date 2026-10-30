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
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The scenario of an agent downloading many web pages at once,
 * written like an LLM would, with the JDK HTTP client instead of ktor, to avoid the dependency,
 * awaited the same way, as a suspending call.
 */
class ConcurrentDownloadTest {

    private val titles = (1..100).map { "Article_$it" }

    @Test
    fun `should download many articles concurrently and keep them for the next snippet`() = runTest {
        ArticleServer(awaitedRequests = titles.size).use { server ->
            ScriptSession {
                "baseUrl" provides server.url
                "titles" provides titles
            }.use { session ->
                // given
                session.execute(
                    $$"""
                    import java.net.URI
                    import java.net.http.HttpClient
                    import java.net.http.HttpRequest
                    import java.net.http.HttpResponse
                    import kotlinx.coroutines.future.await
                    
                    val client = HttpClient.newHttpClient()
                    
                    suspend fun download(title: String): String {
                        val request = HttpRequest.newBuilder(URI.create(baseUrl + title)).build()
                        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                        check(response.statusCode() == 200) { "$title: HTTP ${response.statusCode()}" }
                        return response.body()
                    }
                    
                    val articles = await {
                        titles.map { title -> async { title to download(title) } }.awaitAll().toMap()
                    }
                """.trimIndent())

                // when
                val result = session.execute("""
                    articles.size to articles.getValue("Article_42")
                """.trimIndent())

                // then
                result should {
                    be<ScriptResult.Value>()
                    have(value == (100 to "<h1>Article_42</h1><p>The article about Article_42.</p>"))
                }
                assert(server.maxInFlight == titles.size)
            }
        }
    }

    @Test
    fun `should report failed download among concurrent ones and continue the session`() = runTest {
        ArticleServer(missing = setOf("Article_7")).use { server ->
            ScriptSession {
                "baseUrl" provides server.url
                "titles" provides titles
            }.use { session ->
                // given
                session.execute(
                    $$"""
                    import java.net.URI
                    import java.net.http.HttpClient
                    import java.net.http.HttpRequest
                    import java.net.http.HttpResponse
                    import kotlinx.coroutines.future.await
                    
                    val client = HttpClient.newHttpClient()
                    
                    suspend fun download(title: String): String {
                        val request = HttpRequest.newBuilder(URI.create(baseUrl + title)).build()
                        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                        check(response.statusCode() == 200) { "$title: HTTP ${response.statusCode()}" }
                        return response.body()
                    }
                """.trimIndent())

                // when
                val failure = session.execute("""
                    await {
                        titles.map { title -> async { download(title) } }.awaitAll()
                    }
                """.trimIndent())
                val recovery = session.execute("""
                    val downloaded = await {
                        titles.map { title -> async { runCatching { download(title) } } }.awaitAll()
                    }
                    downloaded.count { it.isSuccess }
                """.trimIndent())

                // then
                failure should {
                    be<ScriptResult.Error>()
                    have(phase == ExecutionPhase.EVALUATION)
                    have(message.startsWith("java.lang.IllegalStateException: Article_7: HTTP 404\n"))
                    have("  at download(snippet1.kts:12)\n" in message)
                }
                recovery should {
                    be<ScriptResult.Value>()
                    have(value == 99)
                }
            }
        }
    }

    @Test
    fun `should download with concurrency bounded by the snippet`() = runTest {
        ArticleServer().use { server ->
            ScriptSession {
                "baseUrl" provides server.url
                "titles" provides titles
            }.use { session ->
                // when
                val result = session.execute("""
                    import java.net.URI
                    import java.net.http.HttpClient
                    import java.net.http.HttpRequest
                    import java.net.http.HttpResponse
                    import kotlinx.coroutines.future.await
                    import kotlinx.coroutines.sync.Semaphore
                    import kotlinx.coroutines.sync.withPermit
                    
                    val client = HttpClient.newHttpClient()
                    val permits = Semaphore(10)
                    
                    await {
                        titles.map { title ->
                            async {
                                permits.withPermit {
                                    val request = HttpRequest.newBuilder(URI.create(baseUrl + title)).build()
                                    client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await().body()
                                }
                            }
                        }.awaitAll().size
                    }
                """.trimIndent())

                // then
                result should {
                    be<ScriptResult.Value>()
                    have(value == 100)
                }
                assert(server.requests == 100 && server.maxInFlight <= 10)
            }
        }
    }

}
