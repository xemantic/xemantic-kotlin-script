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
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.uri
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * An HTTP forward proxy, like the one of a corporate firewall, which is the only way out of it:
 * it answers any request proxied through it, of a URL in an absolute form, like `GET http://host/path`,
 * with the file of the path in the [MavenRepository], whichever host is requested,
 * and records the [urls] requested.
 */
class ProxyServer : AutoCloseable {

    private val requestedUrls = CopyOnWriteArrayList<String>()

    /** The URLs requested through the proxy, in the absolute form. */
    val urls: List<String> get() = requestedUrls

    private val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
        // without the routing, which matches the path of a URL, not the URL itself
        intercept(ApplicationCallPipeline.Call) {
            val url = call.request.uri
            requestedUrls += url
            val file = MavenRepository.directory.resolve(Url(url).encodedPath.removePrefix("/")).normalize()
            if (file.startsWith(MavenRepository.directory) && file.isRegularFile()) {
                call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
            } else {
                call.respondBytes(ByteArray(0), status = HttpStatusCode.NotFound)
            }
        }
    }.start()

    /** The host of the proxy. */
    val host: String

    /** The port of the proxy. */
    val port: Int

    init {
        val connector = runBlocking { server.engine.resolvedConnectors().single() }
        host = connector.host
        port = connector.port
    }

    override fun close() {
        server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
    }

}
