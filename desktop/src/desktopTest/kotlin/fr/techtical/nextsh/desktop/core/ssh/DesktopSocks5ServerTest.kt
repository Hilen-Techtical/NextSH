// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import io.mockk.mockk
import net.schmizz.sshj.SSHClient
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests of the lifecycle of [DesktopSocks5Server] : `start` binds, `startAccepting` accepts and reports
 * every end of the loop that is not a `stop`. The listening socket is a [GatedSocket] handed out through
 * the `bindServerSocket` seam.
 */
class DesktopSocks5ServerTest {

    /**
     * Loopback socket whose `accept()` waits for [gate] then throws what [failure] builds, or accepts for
     * real when there is no [failure]. [accepts] counts the entries into `accept()`.
     */
    private class GatedSocket(
        private val gate: CountDownLatch? = null,
        private val failure: (() -> Throwable)? = null,
    ) : ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) {
        val accepts = AtomicInteger(0)

        override fun accept(): Socket {
            accepts.incrementAndGet()
            if (failure == null) return super.accept()
            gate?.await(5, TimeUnit.SECONDS)
            throw failure.invoke()
        }
    }

    private val sockets = mutableListOf<ServerSocket>()
    private val servers = mutableListOf<DesktopSocks5Server>()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun serverOn(socket: ServerSocket): DesktopSocks5Server {
        sockets += socket
        return DesktopSocks5Server(mockk<SSHClient>(relaxed = true), 0) { socket }.also { servers += it }
    }

    @Test
    fun `an accept that fails while the server runs reports the failure once and frees the port`() {
        val gate = CountDownLatch(1)
        val boom = SocketException("Too many open files")
        val socket = GatedSocket(gate) { boom }
        val server = serverOn(socket)
        val reported = CopyOnWriteArrayList<Throwable>()
        val done = CountDownLatch(1)

        server.start()
        server.startAccepting { reported += it; done.countDown() }
        gate.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS), "the failure must be reported")
        Thread.sleep(200)
        assertEquals(1, reported.size, "reported exactly once")
        assertSame(boom, reported.single())
        assertTrue(socket.isClosed, "the listening socket must be closed")
    }

    @Test
    fun `a stop never reports`() {
        val reported = AtomicInteger(0)
        val socket = GatedSocket()
        val server = serverOn(socket)

        server.start()
        server.startAccepting { reported.incrementAndGet() }
        assertTrue(waitFor { socket.accepts.get() >= 1 }, "the loop must be waiting in accept()")
        server.stop()

        Thread.sleep(300)
        assertEquals(0, reported.get())
    }

    @Test
    fun `a server stopped before it accepts starts no loop and reports nothing`() {
        val reported = AtomicInteger(0)
        val socket = GatedSocket()
        val server = serverOn(socket)

        server.start()
        server.stop()
        server.startAccepting { reported.incrementAndGet() }

        Thread.sleep(300)
        assertEquals(0, socket.accepts.get(), "no accept() on a stopped server")
        assertEquals(0, reported.get())
    }

    @Test
    fun `an accept failure that is not an IOException is reported too`() {
        val gate = CountDownLatch(1)
        val socket = GatedSocket(gate) { IllegalStateException("odd") }
        val server = serverOn(socket)
        val reported = CopyOnWriteArrayList<Throwable>()
        val done = CountDownLatch(1)

        server.start()
        server.startAccepting { reported += it; done.countDown() }
        gate.countDown()

        assertTrue(done.await(5, TimeUnit.SECONDS), "the failure must be reported")
        assertTrue(reported.single() is IllegalStateException)
    }

    @Test
    fun `an Error raised by accept is reported, the socket closed, then the Error is rethrown`() {
        val gate = CountDownLatch(1)
        val fatal = OutOfMemoryError("test")
        val socket = GatedSocket(gate) { fatal }
        val server = serverOn(socket)
        val order = CopyOnWriteArrayList<String>()
        val rethrown = CopyOnWriteArrayList<Throwable>()
        val rethrownSignal = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            if (e === fatal) {
                rethrown += e
                rethrownSignal.countDown()
            } else {
                previous?.uncaughtException(Thread.currentThread(), e)
            }
        }
        try {
            server.start()
            server.startAccepting { order += if (socket.isClosed) "reported after close" else "reported before close" }
            gate.countDown()

            assertTrue(rethrownSignal.await(5, TimeUnit.SECONDS), "the Error must not be swallowed")
            assertSame(fatal, rethrown.single())
            assertEquals(listOf("reported after close"), order.toList())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the server accepts only once startAccepting is called`() {
        val socket = GatedSocket()
        val server = serverOn(socket)

        server.start()
        Thread.sleep(300)
        assertEquals(0, socket.accepts.get(), "start() alone must not accept")

        server.startAccepting { }
        assertTrue(waitFor { socket.accepts.get() >= 1 }, "startAccepting() must start the loop")
    }

    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }
}
