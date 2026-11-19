// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * [SshSessionManager.declareUnresponsive]: the end of a session whose link the liveness watchdog
 * found mute. Same harness as [SshSessionManagerLifecycleTest].
 */
class SshSessionManagerUnresponsiveTest {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }
    private val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = mockk(),
        settingsDataStore = settings,
        context = context,
    ).also { it.closeScope = CoroutineScope(Dispatchers.Unconfined) }

    private val transportListener = slot<DisconnectListener>()
    private val transport = mockk<Transport>(relaxed = true).also {
        every { it.disconnectListener = capture(transportListener) } just Runs
    }
    private val socket = mockk<Socket>(relaxed = true)

    private fun newClient(withSocket: Socket? = socket): SSHClient = mockk<SSHClient>(relaxed = true).also {
        every { it.transport } returns transport
        every { it.isConnected } returns true
        every { it.isAuthenticated } returns true
        every { it.socket } returns withSocket
    }

    private var client = newClient()

    init {
        manager.clientFactory = { client }
    }

    private fun host(keepAliveSeconds: Int = 30) = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "host1.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
        keepAliveSeconds = keepAliveSeconds,
    )

    private fun connect(host: Host = host()): SshSession = runBlocking {
        (manager.connectWithPassword(host, "secret".toCharArray()) as SshResult.Success).data
    }

    private class Recorder : SessionEndListener {
        val ends = mutableListOf<Pair<String, SessionEnd>>()
        override fun onSessionEnded(sessionId: String, end: SessionEnd) {
            ends += sessionId to end
        }
    }

    private fun recorder() = Recorder().also { manager.addSessionEndListener(it) }

    @Test
    fun `an unresponsive session leaves the registry whatever its references`() {
        val session = connect()
        assertTrue(manager.acquire(session.id))
        assertTrue(manager.acquire(session.id))

        manager.declareUnresponsive(session.id, client)

        assertNull(manager.getClient(session.id))
        assertTrue(manager.activeSessions.value.isEmpty())
        assertFalse(manager.isConnected(session.id))
        assertFalse(manager.acquire(session.id), "a dead session cannot be taken again")
    }

    @Test
    fun `the listeners are told once, with Unresponsive`() {
        val listener = recorder()
        val session = connect()

        manager.declareUnresponsive(session.id, client)
        manager.declareUnresponsive(session.id, client)

        assertEquals(listOf(session.id to SessionEnd.Unresponsive), listener.ends)
    }

    @Test
    fun `the raw socket is closed before the client`() {
        val session = connect()

        manager.declareUnresponsive(session.id, client)

        verifyOrder {
            socket.close()
            client.disconnect()
        }
    }

    @Test
    fun `a client without a socket is still closed`() {
        client = newClient(withSocket = null)
        val session = connect()

        manager.declareUnresponsive(session.id, client)

        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `a socket that fails to close does not stop the client from being closed`() {
        every { socket.close() } throws java.io.IOException("boom")
        val listener = recorder()
        val session = connect()

        manager.declareUnresponsive(session.id, client)

        verify(exactly = 1) { client.disconnect() }
        assertEquals(1, listener.ends.size)
    }

    @Test
    fun `a lost session closes the raw socket before the client`() {
        val listener = recorder()
        val session = connect()

        transportListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "mute link")

        assertEquals(listOf(session.id), listener.ends.map { it.first })
        assertTrue(listener.ends.single().second is SessionEnd.Lost)
        verifyOrder {
            socket.close()
            client.disconnect()
        }
    }

    @Test
    fun `the disconnect that SSHJ reports afterwards does not say Lost in addition`() {
        val listener = recorder()
        val session = connect()

        manager.declareUnresponsive(session.id, client)
        transportListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "closed")

        assertEquals(listOf(session.id to SessionEnd.Unresponsive), listener.ends)
    }

    @Test
    fun `another client registered under the same id is not touched`() {
        val listener = recorder()
        val session = connect()
        val stranger = newClient()

        manager.declareUnresponsive(session.id, stranger)

        assertSame(client, manager.getClient(session.id))
        assertTrue(listener.ends.isEmpty())
        verify(exactly = 0) { client.disconnect() }
        verify(exactly = 0) { stranger.disconnect() }
        manager.release(session.id)
    }

    @Test
    fun `an unknown session is a no-op`() {
        val listener = recorder()

        manager.declareUnresponsive("nobody", client)

        assertTrue(listener.ends.isEmpty())
        verify(exactly = 0) { client.disconnect() }
    }

    @Test
    fun `the cadence of the host is kept with the session and forgotten with it`() {
        val session = connect(host(keepAliveSeconds = 45))
        assertEquals(45, manager.livenessCadenceSeconds(session.id))

        manager.declareUnresponsive(session.id, client)

        assertNull(manager.livenessCadenceSeconds(session.id))
        assertNull(manager.livenessCadenceSeconds("nobody"))
    }

    @Test
    fun `a keepalive of zero is reported as zero, the session is not watched`() {
        val session = connect(host(keepAliveSeconds = 0))

        assertEquals(0, manager.livenessCadenceSeconds(session.id))
    }
}
