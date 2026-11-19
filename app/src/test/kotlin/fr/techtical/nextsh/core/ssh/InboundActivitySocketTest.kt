// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/**
 * The socket that dates what the server sends, on a real loopback connection: the proof of life of
 * the liveness watchdog.
 */
class InboundActivitySocketTest {

    private var now = 0L

    private fun <T> withConnectedPair(block: (InboundActivitySocket, java.net.Socket) -> T): T {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val socket = InboundActivitySocket { now }
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort), 5_000)
            val peer = server.accept()
            try {
                return block(socket, peer)
            } finally {
                peer.close()
                socket.close()
            }
        }
    }

    @Test
    fun `a read of bytes dates the activity`() {
        now = 100
        withConnectedPair { socket, peer ->
            assertEquals(100, socket.lastInboundAt, "created at the time of the socket")
            peer.getOutputStream().apply { write(byteArrayOf(1, 2, 3)); flush() }
            now = 5_000

            val buffer = ByteArray(8)
            assertEquals(3, socket.getInputStream().read(buffer))

            assertEquals(5_000, socket.lastInboundAt)
        }
    }

    @Test
    fun `a single byte read dates the activity too`() {
        now = 0
        withConnectedPair { socket, peer ->
            peer.getOutputStream().apply { write(7); flush() }
            now = 42

            assertEquals(7, socket.getInputStream().read())

            assertEquals(42, socket.lastInboundAt)
        }
    }

    @Test
    fun `the end of the stream is not activity`() {
        now = 0
        withConnectedPair { socket, peer ->
            peer.shutdownOutput()
            now = 9_000

            assertEquals(-1, socket.getInputStream().read(ByteArray(4)))

            assertEquals(0, socket.lastInboundAt)
        }
    }

    @Test
    fun `a read timeout is not activity`() {
        now = 0
        withConnectedPair { socket, _ ->
            socket.soTimeout = 50
            now = 9_000

            var timedOut = false
            try {
                socket.getInputStream().read(ByteArray(4))
            } catch (e: SocketTimeoutException) {
                timedOut = true
            }

            assertTrue(timedOut)
            assertEquals(0, socket.lastInboundAt)
        }
    }

    @Test
    fun `getInputStream returns the same stream every time`() {
        withConnectedPair { socket, _ ->
            assertSame(socket.getInputStream(), socket.getInputStream())
        }
    }

    @Test
    fun `the factory makes an instrumented socket that is not connected yet`() {
        val socket = InboundActivitySocketFactory { now }.createSocket()
        try {
            assertTrue(socket is InboundActivitySocket)
            assertFalse(socket.isConnected)
        } finally {
            socket.close()
        }
    }
}
