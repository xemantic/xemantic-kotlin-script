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

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A TCP relay, at [url], of the connections to the server of the [target] URL,
 * counting the [connections] of the clients, and the ones the clients keep [open][awaitOpenConnections],
 * like the keep-alive connections of an HTTP connection pool which is not shut down.
 */
class ConnectionRelay(target: String) : AutoCloseable {

    private val targetUri = URI(target)

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    private val sockets = CopyOnWriteArrayList<Socket>()

    private val lock = Object()

    private var accepted = 0

    private var open = 0

    @Suppress("HttpUrlsUsage")
    val url: String = "http://127.0.0.1:${serverSocket.localPort}/"

    /** The number of the connections accepted from the clients. */
    val connections: Int get() = synchronized(lock) { accepted }

    init {
        thread(isDaemon = true, name = "connection-relay") {
            while (true) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                val upstream = Socket(targetUri.host, targetUri.port)
                sockets += client
                sockets += upstream
                synchronized(lock) {
                    accepted++
                    open++
                }
                thread(isDaemon = true) {
                    // until the client closes the connection
                    pump(client, upstream)
                    synchronized(lock) {
                        open--
                        lock.notifyAll()
                    }
                    upstream.close()
                    client.close()
                }
                thread(isDaemon = true) {
                    pump(upstream, client)
                }
            }
        }
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            from.getInputStream().copyTo(to.getOutputStream())
        } catch (_: IOException) {
            // either side closed
        }
    }

    /**
     * The number of the connections the clients keep open,
     * awaiting, only while some is open, up to the [timeoutMillis], the ones closed already to be noticed.
     * It bounds only the wait of a connection left open, so it does not delay a test of connections released.
     */
    fun awaitOpenConnections(timeoutMillis: Long = 5_000): Int = synchronized(lock) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (open > 0) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            lock.wait(remaining)
        }
        open
    }

    override fun close() {
        serverSocket.close()
        sockets.forEach { it.close() }
    }

}
