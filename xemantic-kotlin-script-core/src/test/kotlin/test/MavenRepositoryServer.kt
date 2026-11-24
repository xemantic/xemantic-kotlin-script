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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * A local HTTP server of the [MavenRepository], at [url],
 * answering `401` to a request without the basic authentication of the [username] and [password],
 * or of one of the [otherUsers], by their passwords,
 * the [statuses] of the users given one, and `500` to the next [failingRequests] authenticated ones.
 */
class MavenRepositoryServer(
    private val username: String,
    password: String,
    otherUsers: Map<String, String> = emptyMap()
) : AutoCloseable {

    /** The users, by the basic authentication of their credentials. */
    private val users = (mapOf(username to password) + otherUsers).entries.associate { (user, secret) ->
        "Basic " + Base64.encode("$user:$secret".encodeToByteArray()) to user
    }

    /** The status answered to every authenticated request of a user, by the user, like `403` or `500`. */
    val statuses: MutableMap<String, HttpStatusCode> = ConcurrentHashMap()

    private val failures = AtomicInteger()

    private val metadataRequestCounter = AtomicInteger()

    private val wronglyAuthenticatedRequestCounter = AtomicInteger()

    /** The number of the next authenticated requests answered with `500`. */
    var failingRequests: Int
        get() = failures.get()
        set(value) = failures.set(value)

    /** The number of the requests of a `maven-metadata.xml` authenticated as the [username]. */
    val metadataRequests: Int get() = metadataRequestCounter.get()

    /** The number of the requests, of any file, with the basic authentication of unknown credentials. */
    val wronglyAuthenticatedRequests: Int get() = wronglyAuthenticatedRequestCounter.get()

    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        // answers HEAD, which Maven sends to verify an artifact in the local repository downloaded from another one, without the body
        install(AutoHeadResponse)
        routing {
            get("/{path...}") {
                val path = call.parameters.getAll("path")!!.joinToString("/")
                val file = MavenRepository.directory.resolve(path).normalize()
                val authorization = call.request.header(HttpHeaders.Authorization)
                val user = users[authorization]
                if (user == username && path.endsWith("/maven-metadata.xml")) metadataRequestCounter.incrementAndGet()
                if (authorization != null && user == null) wronglyAuthenticatedRequestCounter.incrementAndGet()
                val status = user?.let { statuses[it] }
                when {
                    user == null -> {
                        call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"fixture\"")
                        call.respondBytes(ByteArray(0), status = HttpStatusCode.Unauthorized)
                    }
                    status != null -> call.respondBytes(ByteArray(0), status = status)
                    failures.getAndUpdate { maxOf(it - 1, 0) } > 0 -> call.respondBytes(
                        ByteArray(0), status = HttpStatusCode.InternalServerError
                    )
                    !file.startsWith(MavenRepository.directory) || !file.isRegularFile() -> call.respondBytes(
                        ByteArray(0), status = HttpStatusCode.NotFound
                    )
                    else -> call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                }
            }
        }
    }.start()

    @Suppress("HttpUrlsUsage")
    val url: String = runBlocking {
        val connector = server.engine.resolvedConnectors().single()
        "http://${connector.host}:${connector.port}/"
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

}
