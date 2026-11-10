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
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * A TCP relay, at [url], of the connections to the server of the [target] URL,
 * counting the [connections] of the clients, and the ones the clients keep [open][awaitOpenConnections],
 * like the keep-alive connections of an HTTP connection pool which is not shut down.
 * A client connection, which cannot be relayed, as the server cannot be connected to, is closed, and not counted.
 */
class ConnectionRelay(target: String) : AutoCloseable {

    private val targetUri = URI(target)

    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** Guards the [sockets], the counters, and [closed]. */
    private val lock = ReentrantLock()

    /** Signalled when a connection is closed. */
    private val connectionClosed = lock.newCondition()

    private val sockets = mutableListOf<Socket>()

    private var closed = false

    private var accepted = 0

    private var open = 0

    @Suppress("HttpUrlsUsage")
    val url: String = "http://127.0.0.1:${serverSocket.localPort}/"

    /** The number of the connections accepted from the clients. */
    val connections: Int get() = lock.withLock { accepted }

    init {
        thread(isDaemon = true, name = "connection-relay") {
            while (true) {
                val client = try {
                    serverSocket.accept()
                } catch (_: IOException) {
                    break
                }
                val upstream = try {
                    Socket(targetUri.host, targetUri.port)
                } catch (_: IOException) {
                    client.close()
                    continue
                }
                val relayed = lock.withLock {
                    // a connection accepted right before closing the relay is closed at once, not to outlive it
                    if (closed) return@withLock false
                    sockets += client
                    sockets += upstream
                    accepted++
                    open++
                    true
                }
                if (!relayed) {
                    upstream.close()
                    client.close()
                    break
                }
                thread(isDaemon = true) {
                    // until the client closes the connection
                    pump(client, upstream)
                    lock.withLock {
                        open--
                        connectionClosed.signalAll()
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
    fun awaitOpenConnections(timeoutMillis: Long = 5_000): Int = lock.withLock {
        var remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (open > 0 && remaining > 0) {
            remaining = connectionClosed.awaitNanos(remaining)
        }
        open
    }

    override fun close() {
        val relayed = lock.withLock {
            closed = true
            sockets.toList()
        }
        serverSocket.close()
        relayed.forEach { it.close() }
    }

}
