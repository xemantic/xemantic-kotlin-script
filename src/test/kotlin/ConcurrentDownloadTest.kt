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

import com.xemantic.kotlin.script.test.ArticleServer
import com.xemantic.kotlin.test.assert
import com.xemantic.kotlin.test.be
import com.xemantic.kotlin.test.have
import com.xemantic.kotlin.test.should
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The scenario of an agent downloading many web pages at once,
 * written like an LLM would, with the ktor client.
 */
class ConcurrentDownloadTest {

    private val titles = (1..100).map { "Article_$it" }

    private val server = ArticleServer()

    private val session = ScriptSession {
        "baseUrl" provides server.url
        "titles" provides titles
    }

    @BeforeTest
    fun declareDownload() {
        val result = runBlocking {
            session.execute(
                $$"""
                import io.ktor.client.HttpClient
                import io.ktor.client.request.get
                import io.ktor.client.statement.bodyAsText
                import io.ktor.http.HttpStatusCode
                
                val client = HttpClient()
                
                suspend fun download(title: String): String {
                    val response = client.get(baseUrl + title)
                    check(response.status == HttpStatusCode.OK) { "$title: HTTP ${response.status.value}" }
                    return response.bodyAsText()
                }
                """.trimIndent()
            )
        }
        check(result is ScriptResult.Value) { (result as ScriptResult.Error).message }
    }

    @AfterTest
    fun close() {
        session.close()
        server.close()
    }

    @Test
    fun `should download many articles concurrently and keep them for the next snippet`() = runTest {
        // given
        server.awaitedRequests = titles.size
        session.execute(
            """
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

    @Test
    fun `should report failed download among concurrent ones and continue the session`() = runTest {
        // given
        server.missing = setOf("Article_7")

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
            have("  at download(snippet1.kts:10)\n" in message)
        }
        recovery should {
            be<ScriptResult.Value>()
            have(value == 99)
        }
    }

    @Test
    fun `should download with concurrency bounded by the snippet`() = runTest {
        // when
        val result = session.execute("""
            import kotlinx.coroutines.sync.Semaphore
            import kotlinx.coroutines.sync.withPermit
            
            val permits = Semaphore(10)
            
            await {
                titles.map { title ->
                    async {
                        permits.withPermit {
                            client.get(baseUrl + title).bodyAsText()
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
