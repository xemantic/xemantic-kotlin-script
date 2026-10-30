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

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A local HTTP server of articles, like Wikipedia, at `/wiki/<title>`, answering `404` for the [missing] titles.
 *
 * When [awaitedRequests] is given, every response waits until that many requests are in flight at the same time,
 * which proves the client sends them concurrently, without measuring time.
 * Sequential requests would never fill the barrier, so once it times out, all the requests fail fast with `503`,
 * failing the test instead of hanging it.
 */
class ArticleServer(
    private val awaitedRequests: Int? = null,
    private val missing: Set<String> = emptySet()
) : AutoCloseable {

    private val threads = Executors.newCachedThreadPool()

    private val barrier = awaitedRequests?.let { CountDownLatch(it) }

    private val barrierBroken = AtomicBoolean(false)

    private val inFlight = AtomicInteger()

    private val maxInFlightCounter = AtomicInteger()

    private val requestCounter = AtomicInteger()

    /** The highest number of the requests handled at the same time. */
    val maxInFlight: Int get() = maxInFlightCounter.get()

    val requests: Int get() = requestCounter.get()

    private val server = HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
        // all the concurrent connections are accepted, not refused
        1000
    ).apply {
        executor = threads
        createContext("/wiki/") { exchange ->
            exchange.use {
                requestCounter.incrementAndGet()
                val current = inFlight.incrementAndGet()
                maxInFlightCounter.accumulateAndGet(current, ::maxOf)
                try {
                    val title = exchange.requestURI.path.removePrefix("/wiki/")
                    val ready = barrier?.let {
                        it.countDown()
                        !barrierBroken.get() && it.await(30, TimeUnit.SECONDS)
                    } ?: true
                    if (!ready) barrierBroken.set(true)
                    val (status, body) = when {
                        !ready -> 503 to "requests were not concurrent"
                        title in missing -> 404 to "no article: $title"
                        else -> 200 to "<h1>$title</h1><p>The article about $title.</p>"
                    }
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.write(bytes)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }
        start()
    }

    val url: String = "http://${server.address.hostString}:${server.address.port}/wiki/"

    override fun close() {
        server.stop(0)
        threads.shutdownNow()
    }

}
