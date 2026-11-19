// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import net.schmizz.keepalive.KeepAliveRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The transport keepalive of every Android SSH session: the host keepalive, bounded, armed on the
 * client BEFORE `connect` (SSHJ starts its keepalive thread in `connect`, and only when the
 * interval is already positive), with the KEEP_ALIVE provider (HEARTBEAT would send an
 * `SSH_MSG_IGNORE` during the key exchange, which a strict KEX server refuses).
 */
class TransportKeepAliveTest {

    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }
    private val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = mockk(),
        settingsDataStore = settings,
        context = mockk<Context>(relaxed = true),
    )

    private fun host(keepAliveSeconds: Int) = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "127.0.0.1",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
        keepAliveSeconds = keepAliveSeconds,
    )

    @Test
    fun `a host keepalive of 0 or less asks for no transport keepalive`() {
        assertNull(transportKeepAliveSeconds(0))
        assertNull(transportKeepAliveSeconds(-1))
        assertNull(transportKeepAliveSeconds(-300))
    }

    @Test
    fun `a host keepalive is kept between 15 and 60 seconds`() {
        assertEquals(15, transportKeepAliveSeconds(1))
        assertEquals(15, transportKeepAliveSeconds(5))
        assertEquals(15, transportKeepAliveSeconds(15))
        assertEquals(30, transportKeepAliveSeconds(30))
        assertEquals(60, transportKeepAliveSeconds(60))
        assertEquals(60, transportKeepAliveSeconds(61))
        assertEquals(60, transportKeepAliveSeconds(120))
    }

    @Test
    fun `the bounds are those of the liveness watchdog`() {
        assertEquals(LIVENESS_MIN_SECONDS, transportKeepAliveSeconds(1))
        assertEquals(LIVENESS_MAX_SECONDS, transportKeepAliveSeconds(Int.MAX_VALUE))
    }

    @Test
    fun `a new client uses the KEEP_ALIVE provider and the bounded interval`() {
        val client = manager.newConfiguredClient(host(30))

        assertTrue(client.connection.keepAlive is KeepAliveRunner)
        assertEquals(30, client.connection.keepAlive.keepAliveInterval)
    }

    @Test
    fun `a stored value out of bounds is bounded at use`() {
        assertEquals(60, manager.newConfiguredClient(host(120)).connection.keepAlive.keepAliveInterval)
        assertEquals(15, manager.newConfiguredClient(host(5)).connection.keepAlive.keepAliveInterval)
    }

    @Test
    fun `a host keepalive of 0 leaves the keepalive of the client off`() {
        val client = manager.newConfiguredClient(host(0))

        assertTrue(client.connection.keepAlive is KeepAliveRunner)
        assertEquals(0, client.connection.keepAlive.keepAliveInterval)
        assertFalse(client.connection.keepAlive.isEnabled)
    }

    @Test
    fun `the keepalive thread starts at connect when the interval was set before`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val accepted = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write("SSH-2.0-Test\r\n".toByteArray())
                        flush()
                    }
                    // Mute afterwards: the client waits for the key exchange.
                    Thread.sleep(6_000)
                }
            }
        }
        val client = manager.newConfiguredClient(host(30))
        client.timeout = 3_000
        val connecting = thread(isDaemon = true) {
            runCatching { client.connect(server.inetAddress.hostAddress, server.localPort) }
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!client.connection.keepAlive.isAlive && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            assertTrue(client.connection.keepAlive.isAlive, "the keepalive thread must run once connected")
        } finally {
            runCatching { client.disconnect() }
            connecting.join(5_000)
            server.close()
            accepted.interrupt()
            accepted.join(2_000)
        }
    }

    @Test
    fun `the keepalive thread never starts for a host at 0`() {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val accepted = thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    socket.getOutputStream().apply {
                        write("SSH-2.0-Test\r\n".toByteArray())
                        flush()
                    }
                    Thread.sleep(2_000)
                }
            }
        }
        val client = manager.newConfiguredClient(host(0))
        client.timeout = 1_500
        val connecting = thread(isDaemon = true) {
            runCatching { client.connect(server.inetAddress.hostAddress, server.localPort) }
        }
        try {
            Thread.sleep(800)
            assertFalse(client.connection.keepAlive.isAlive)
        } finally {
            runCatching { client.disconnect() }
            connecting.join(5_000)
            server.close()
            accepted.interrupt()
            accepted.join(2_000)
        }
    }
}
