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

package com.xemantic.kotlin.script.test

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * A local HTTP server of articles, like Wikipedia, at `/wiki/<title>`, answering `404` for the [missing] titles.
 *
 * It is configured after it is started, before the requests,
 * so that its [url] can be given to a session created together with it.
 */
class ArticleServer : AutoCloseable {

    /** The titles answered with `404`. */
    @Volatile
    var missing: Set<String> = emptySet()

    /**
     * When set, every response waits until that many requests are in flight at the same time,
     * which proves the client sends them concurrently, without measuring time.
     * Sequential requests would never fill the barrier, so once it times out, all the requests fail fast with `503`,
     * failing the test instead of hanging it.
     */
    @Volatile
    var awaitedRequests: Int? = null
        set(value) {
            barrier = value?.let { Barrier(it) }
            field = value
        }

    @Volatile
    private var barrier: Barrier? = null

    private val inFlight = AtomicInteger()

    private val maxInFlightCounter = AtomicInteger()

    private val requestCounter = AtomicInteger()

    /** The highest number of the requests handled at the same time. */
    val maxInFlight: Int get() = maxInFlightCounter.get()

    val requests: Int get() = requestCounter.get()

    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        routing {
            get("/wiki/{title}") {
                val title = call.parameters["title"]!!
                requestCounter.incrementAndGet()
                maxInFlightCounter.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                try {
                    when {
                        barrier?.await() == false -> call.respondText(
                            "requests were not concurrent", status = HttpStatusCode.ServiceUnavailable
                        )
                        title in missing -> call.respondText(
                            "no article: $title", status = HttpStatusCode.NotFound
                        )
                        else -> call.respondText(
                            "<h1>$title</h1><p>The article about $title.</p>", ContentType.Text.Html
                        )
                    }
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
    }.start()

    @Suppress("HttpUrlsUsage")
    val url: String = runBlocking {
        val connector = server.engine.resolvedConnectors().single()
        "http://${connector.host}:${connector.port}/wiki/"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

    /**
     * Passes the requests once [count] of them are waiting,
     * or fails them all, the waiting and the later ones, if it does not happen in time.
     */
    private class Barrier(count: Int) {

        private val remaining = AtomicInteger(count)

        private val passed = CompletableDeferred<Boolean>()

        suspend fun await(): Boolean {
            if (remaining.decrementAndGet() == 0) passed.complete(true)
            withTimeoutOrNull(30.seconds) { passed.await() } ?: passed.complete(false)
            return passed.await()
        }

    }

}
