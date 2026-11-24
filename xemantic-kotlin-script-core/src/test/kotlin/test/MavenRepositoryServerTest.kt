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

import com.xemantic.kotlin.test.assert
import java.net.Socket
import java.net.URI
import kotlin.io.encoding.Base64
import kotlin.test.Test

class MavenRepositoryServerTest {

    @Test
    fun `should answer HEAD request without body on keep-alive connection`() {
        MavenRepositoryServer(username = "alice", password = "secret").use { server ->
            // given
            val url = URI(server.url)
            val authorization = "Basic " + Base64.encode("alice:secret".encodeToByteArray())
            val path = "/com/xemantic/fixture/greeter/1.0/greeter-1.0.pom"
            fun request(method: String) =
                "$method $path HTTP/1.1\r\nHost: ${url.host}:${url.port}\r\nAuthorization: $authorization\r\n\r\n"

            // when
            val afterHeadResponse = Socket(url.host, url.port).use { socket ->
                // a safety net against a hang, not an assertion
                socket.soTimeout = 60_000
                // a GET request following the HEAD one on the same connection, like on a reused pooled connection
                socket.getOutputStream().write((request("HEAD") + request("GET")).encodeToByteArray())
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.count()
                input.readLine()
            }

            // then
            assert(afterHeadResponse == "HTTP/1.1 200 OK")
        }
    }

}
