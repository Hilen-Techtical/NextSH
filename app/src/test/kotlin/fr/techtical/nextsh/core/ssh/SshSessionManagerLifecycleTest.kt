// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The ownership of an SSH session (see the class comment of [SshSessionManager]): a connection
 * returns it with one reference, [SshSessionManager.acquire] and [SshSessionManager.release] count
 * the users, the last release closes it, and a dead transport removes it whatever its count.
 */
class SshSessionManagerLifecycleTest {

    private val context = mockk<Context>(relaxed = true)
    private val settings = mockk<SettingsDataStore>().also {
        every { it.settingsFlow } returns flowOf(SettingsDataStore.Settings())
    }
    private val manager = SshSessionManager(
        knownHostsVerifier = mockk(),
        sshKeyManager = mockk(),
        settingsDataStore = settings,
        context = context,
    ).also {
        // The client is closed right where release is called, so that the tests can see it.
        it.closeScope = CoroutineScope(Dispatchers.Unconfined)
    }

    /** The SSHJ transport listener the manager installs: what a dying link calls. */
    private val transportListener = slot<DisconnectListener>()
    private val transport = mockk<Transport>(relaxed = true).also {
        every { it.disconnectListener = capture(transportListener) } just Runs
    }

    private fun newClient(): SSHClient = mockk<SSHClient>(relaxed = true).also {
        every { it.transport } returns transport
        every { it.isConnected } returns true
        every { it.isAuthenticated } returns true
    }

    private var client = newClient()

    init {
        manager.clientFactory = { client }
    }

    private val host = Host(
        id = "host-1",
        label = "Host 1",
        hostname = "host1.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun connect(): SshSession = runBlocking {
        (manager.connectWithPassword(host, "secret".toCharArray()) as SshResult.Success).data
    }

    private class Recorder : SessionEndListener {
        val ends = mutableListOf<Pair<String, SessionEnd>>()
        override fun onSessionEnded(sessionId: String, end: SessionEnd) {
            ends += sessionId to end
        }
    }

    private fun recorder() = Recorder().also { manager.addSessionEndListener(it) }

    // ── A connection returns the session with one reference ───────────────────

    @Test
    fun `a connection registers the session with the one reference of its caller`() {
        val session = connect()

        assertSame(client, manager.getClient(session.id))
        assertTrue(manager.isConnected(session.id))
        assertEquals(listOf(session.id), runBlocking { manager.sessions.first() }.map { it.id })

        manager.release(session.id)

        // One release was enough: the caller owned the only reference.
        assertNull(manager.getClient(session.id))
        verify(exactly = 1) { client.disconnect() }
    }

    // ── Counting ──────────────────────────────────────────────────────────────

    @Test
    fun `the last release closes the client and removes the session`() {
        val session = connect()
        assertTrue(manager.acquire(session.id))
        assertTrue(manager.acquire(session.id))

        manager.release(session.id)
        manager.release(session.id)

        verify(exactly = 0) { client.disconnect() }
        assertTrue(manager.activeSessions.value.containsKey(session.id), "one reference is still held")

        manager.release(session.id)

        verify(exactly = 1) { client.disconnect() }
        assertTrue(manager.activeSessions.value.isEmpty())
        assertNull(manager.getClient(session.id))
        assertFalse(manager.isConnected(session.id))
    }

    @Test
    fun `release is idempotent and never goes below zero`() {
        val session = connect()

        manager.release(session.id)
        manager.release(session.id)
        manager.release(session.id)

        verify(exactly = 1) { client.disconnect() }
        // Not even one reference is left over for the next session.
        client = newClient()
        val next = connect()
        manager.release(next.id)
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `releasing an unknown session is a no-op`() {
        val listener = recorder()

        manager.release("nobody")

        assertTrue(listener.ends.isEmpty(), "an unknown session is not an end")
    }

    @Test
    fun `an unknown session cannot be acquired`() {
        assertFalse(manager.acquire("nobody"))
    }

    @Test
    fun `a session that was released cannot be acquired again`() {
        val session = connect()
        manager.release(session.id)

        assertFalse(manager.acquire(session.id))
    }

    @Test
    fun `a session whose client died unnoticed cannot be acquired, and is dropped and announced`() {
        val session = connect()
        val listener = recorder()
        every { client.isConnected } returns false

        assertFalse(manager.acquire(session.id))

        assertNull(manager.getClient(session.id))
        assertTrue(manager.activeSessions.value.isEmpty())
        assertEquals(listOf(session.id to SessionEnd.Lost("CONNECTION_LOST")), listener.ends)
        // The count went with it: its holder's late release is a no-op.
        manager.release(session.id)
        assertEquals(1, listener.ends.size)
    }

    @Test
    fun `a client that is connected but not authenticated cannot be acquired`() {
        val session = connect()
        every { client.isAuthenticated } returns false

        assertFalse(manager.acquire(session.id))
    }

    @Test
    fun `two sessions are counted apart`() {
        val first = connect()
        val firstClient = client
        client = newClient()
        val second = connect()

        manager.release(first.id)

        verify(exactly = 1) { firstClient.disconnect() }
        verify(exactly = 0) { client.disconnect() }
        assertTrue(manager.activeSessions.value.containsKey(second.id))
    }

    // ── The end of a session is announced ─────────────────────────────────────

    @Test
    fun `the last release announces the end as closed`() {
        val session = connect()
        val listener = recorder()
        manager.acquire(session.id)

        manager.release(session.id)
        assertTrue(listener.ends.isEmpty(), "a holder is left")
        manager.release(session.id)

        assertEquals(listOf(session.id to SessionEnd.Closed), listener.ends)
    }

    @Test
    fun `a dying transport removes the session whatever its count, and tells the listeners`() {
        val session = connect()
        manager.acquire(session.id)
        manager.acquire(session.id)
        val listener = recorder()

        transportListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "link down")

        assertTrue(manager.activeSessions.value.isEmpty(), "dead sessions do not stay in the registry")
        assertNull(manager.getClient(session.id))
        assertEquals(listOf(session.id to SessionEnd.Lost("CONNECTION_LOST")), listener.ends)
        // What is left of the client is closed.
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `the holders of a dead session may still release it, without effect`() {
        val session = connect()
        manager.acquire(session.id)
        val listener = recorder()
        transportListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "link down")

        manager.release(session.id)
        manager.release(session.id)

        assertEquals(1, listener.ends.size, "the end is announced once")
        assertFalse(manager.acquire(session.id))
    }

    @Test
    fun `closing the client on the last release is not announced a second time by the transport`() {
        val session = connect()
        val listener = recorder()
        // What SSHJ does when the application closes the connection.
        every { client.disconnect() } answers {
            transportListener.captured.notifyDisconnect(DisconnectReason.BY_APPLICATION, "")
        }

        manager.release(session.id)

        assertEquals(listOf(session.id to SessionEnd.Closed), listener.ends)
    }

    @Test
    fun `the disconnection of a session that left the registry does not touch the next one`() {
        val session = connect()
        val staleListener = transportListener.captured
        manager.release(session.id)
        client = newClient()
        val next = connect()
        val listener = recorder()

        staleListener.notifyDisconnect(DisconnectReason.BY_APPLICATION, "")

        assertTrue(manager.activeSessions.value.containsKey(next.id))
        assertTrue(listener.ends.isEmpty())
    }

    @Test
    fun `a listener that fails does not hide the end from the others`() {
        val session = connect()
        manager.addSessionEndListener { _, _ -> throw IllegalStateException("boom") }
        val listener = recorder()

        manager.release(session.id)

        assertEquals(listOf(session.id to SessionEnd.Closed), listener.ends)
        verify(exactly = 1) { client.disconnect() }
    }

    @Test
    fun `a listener that was removed hears nothing`() {
        val session = connect()
        val listener = Recorder()
        manager.addSessionEndListener(listener)
        manager.removeSessionEndListener(listener)

        manager.release(session.id)

        assertTrue(listener.ends.isEmpty())
    }

    @Test
    fun `the forced disconnect closes the session for everyone and announces it`() = runBlocking {
        val session = connect()
        manager.acquire(session.id)
        val listener = recorder()

        manager.disconnect(session.id)

        verify(exactly = 1) { client.disconnect() }
        assertNull(manager.getClient(session.id))
        assertEquals(listOf(session.id to SessionEnd.Closed), listener.ends)
        // The holders' releases that come after are no-ops.
        manager.release(session.id)
        manager.release(session.id)
        assertEquals(1, listener.ends.size)
    }

    @Test
    fun `the forced disconnect of an unknown session announces nothing`() = runBlocking {
        val listener = recorder()

        manager.disconnect("nobody")

        assertTrue(listener.ends.isEmpty())
    }

    // ── A link that died before the session was registered ────────────────────

    @Test
    fun `a link that died between the authentication and the registration is a lost connection, on every path`() {
        for (path in listOf("password", "key", "certificate", "fido2")) {
            val harness = SessionHarness()
            val ends = mutableListOf<Pair<String, SessionEnd>>()
            harness.manager.addSessionEndListener { id, end -> ends += id to end }
            // The authentication succeeds, and the link is gone the moment it has: SSHJ's listener,
            // installed after that, would never have been called.
            harness.duringAuthentication = { harness.linksAlive = false }
            harness.manager.fido2ChallengeCallback = { Fido2ChallengeResponse.Cancelled }

            val result = runBlocking {
                when (path) {
                    "password" -> harness.manager.connectWithPassword(harness.host, "secret".toCharArray())
                    "key" -> harness.manager.connectWithKey(harness.host, "PEM", null)
                    "certificate" -> harness.manager.connectWithCertificate(harness.host, "PEM", "CERT", null)
                    else -> harness.manager.connectWithSkKey(
                        harness.host,
                        SkSshPublicKey(SkKeyType.SK_ED25519, ByteArray(32) { 1 }),
                    )
                }
            }

            // Not a session handed out dead: a failure that the reconnection retries.
            val error = result as SshResult.Error
            assertEquals(SshErrorCode.HOST_UNREACHABLE, error.code, path)
            assertFalse(error.code.isNonRetryable(), path)
            assertTrue(harness.manager.activeSessions.value.isEmpty(), "$path: nothing stays registered")
            assertEquals(1, harness.closedLinks(), "$path: the client is closed")
            // The session had entered the registry: it left it announced, once.
            assertEquals(1, ends.size, path)
            assertEquals(SessionEnd.Lost("CONNECTION_LOST"), ends.single().second, path)
        }
    }

    // ── The transport listener is the manager's alone ─────────────────────────

    @Test
    fun `the manager installs the transport listener of every session it registers`() {
        connect()

        verify(exactly = 1) { transport.disconnectListener = any() }
    }

    // ── acquire never asks the client under the registry lock ─────────────────

    /**
     * SSHJ's isConnected takes the lock of the transport's close promise, which die() holds while it
     * calls the disconnect listener, and the listener takes the registry lock. The client here
     * blocks in isConnected until the listener has run on another thread: with the registry lock
     * held by acquire, that listener could not finish, and the two would wait for each other.
     */
    @Test
    fun `acquire does not hold the registry lock while it asks the client, a dying session cannot deadlock it`() {
        val session = connect()
        val asked = CountDownLatch(1)
        val listenerDone = CountDownLatch(1)
        var listenerFinishedInTime = false
        every { client.isConnected } answers {
            asked.countDown()
            listenerFinishedInTime = listenerDone.await(5, TimeUnit.SECONDS)
            true
        }
        val dying = thread {
            asked.await(5, TimeUnit.SECONDS)
            transportListener.captured.notifyDisconnect(DisconnectReason.CONNECTION_LOST, "link down")
            listenerDone.countDown()
        }

        val acquired = manager.acquire(session.id)
        dying.join(5_000)

        assertTrue(listenerFinishedInTime, "the end of the session could not run while acquire asked the client")
        // The session ended between the two reads: no reference may be given on a session that is gone.
        assertFalse(acquired)
        assertNull(manager.getClient(session.id))
        assertTrue(manager.activeSessions.value.isEmpty())
    }

    @Test
    fun `acquire on a client that reports itself dead ends the session once and gives no reference`() {
        val ends = recorder().ends
        val session = connect()
        every { client.isConnected } returns false

        assertFalse(manager.acquire(session.id))

        assertNull(manager.getClient(session.id))
        assertEquals(1, ends.size)
        assertEquals(SessionEnd.Lost("CONNECTION_LOST"), ends.single().second)
    }

    @Test
    fun `acquire on an unknown session is false and creates nothing`() {
        assertFalse(manager.acquire("no-such-session"))
        assertTrue(manager.activeSessions.value.isEmpty())
    }
}
