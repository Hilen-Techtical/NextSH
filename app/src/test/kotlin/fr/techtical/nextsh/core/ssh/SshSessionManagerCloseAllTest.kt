// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The closing of every session that comes before the erase of the vault ([SshSessionManager.closeAllAndWait]):
 * every session is forgotten whoever holds it, the clients close in parallel and the wait is bounded, and a
 * connection that is on its way is closed instead of entering the emptied registry.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshSessionManagerCloseAllTest {

    private val gate = VaultWipeGate()
    private val harness = SessionHarness(gate)
    private val manager = harness.manager

    private val ended = mutableListOf<Pair<String, SessionEnd>>()

    init {
        manager.addSessionEndListener { id, end -> ended += id to end }
    }

    @Test
    fun `every session is forgotten whatever the references held on it`() = runTest {
        val tab = harness.connect()
        val tunnel = harness.connect()
        assertTrue(manager.acquire(tab.id))
        assertTrue(manager.acquire(tab.id)) // a tab, a browser, a transfer: three references
        assertTrue(manager.acquire(tunnel.id))

        val closed = manager.closeAllAndWait(1_000)

        assertTrue(closed)
        assertFalse(harness.isRegistered(tab.id))
        assertFalse(harness.isRegistered(tunnel.id))
        assertEquals(2, harness.closedLinks())
        assertFalse(manager.acquire(tab.id), "nothing to take a reference on any more")
    }

    @Test
    fun `each listener is told once per session that the app closed it`() = runTest {
        val first = harness.connect()
        val second = harness.connect()

        manager.closeAllAndWait(1_000)

        assertEquals(setOf(first.id, second.id), ended.map { it.first }.toSet())
        assertEquals(2, ended.size, "once each")
        assertTrue(ended.all { it.second == SessionEnd.Closed })
    }

    @Test
    fun `a late release of a holder on a forgotten session does nothing`() = runTest {
        val tab = harness.connect()
        assertTrue(manager.acquire(tab.id))
        manager.closeAllAndWait(1_000)
        ended.clear()

        manager.release(tab.id)
        manager.release(tab.id)

        assertTrue(ended.isEmpty(), "no second end is announced")
        assertEquals(1, harness.closedLinks(), "and no second close")
    }

    @Test
    fun `with no session it returns true at once`() = runTest {
        assertTrue(manager.closeAllAndWait(1_000))
    }

    // ── Parallel, and bounded ────────────────────────────────────────────────

    @Test
    fun `the clients close in parallel`() {
        manager.closeScope = CoroutineScope(Dispatchers.IO)
        val sessions = runBlocking { listOf(harness.connect(), harness.connect(), harness.connect()) }
        // Each close waits for the two others to have started: one after the other, they would never meet.
        val barrier = CountDownLatch(sessions.size)
        val met = java.util.concurrent.atomic.AtomicInteger(0)
        for (session in sessions) {
            every { harness.linkOf(session.id).client.disconnect() } answers {
                barrier.countDown()
                if (barrier.await(5, TimeUnit.SECONDS)) met.incrementAndGet()
            }
        }

        val closed = runBlocking { manager.closeAllAndWait(10_000) }

        assertTrue(closed)
        assertEquals(sessions.size, met.get(), "every close saw the others start")
    }

    @Test
    fun `a close that blocks does not hold the wait, its socket is closed and false is returned`() = runTest {
        manager.closeScope = CoroutineScope(Dispatchers.IO)
        val stuck = harness.connect()
        val healthy = harness.connect()
        val latch = CountDownLatch(1)
        every { harness.linkOf(stuck.id).client.disconnect() } answers { latch.await(30, TimeUnit.SECONDS) }
        val stuckClient = harness.linkOf(stuck.id).client

        // The wait is bounded in the scope of the caller (virtual time here), the closes run in the scope of
        // the manager: a bound on blocking children of the same scope would wait for them before it reported.
        val closed = manager.closeAllAndWait(10_000)

        assertFalse(closed)
        verify(exactly = 1) { stuckClient.socket }
        latch.countDown()
        // The registry is empty all the same: the sessions were forgotten before anything blocked.
        assertFalse(harness.isRegistered(stuck.id))
        assertFalse(harness.isRegistered(healthy.id))
    }

    // ── A connection on its way ──────────────────────────────────────────────

    @Test
    fun `a connection that began before the closing and succeeds after it is closed and fails`() = runTest {
        harness.duringAuthentication = { runBlocking { manager.closeAllAndWait(1_000) } }

        val result = manager.connectWithPassword(harness.host, "secret".toCharArray())

        assertTrue(result is SshResult.Error, "no session for a connection of the app that was just closed")
        assertEquals(1, harness.closedLinks(), "its client is closed, it is not left open without an owner")
        assertTrue(ended.isEmpty(), "it was never registered, so nobody is told it ended")
    }

    @Test
    fun `a connection after the closing, with the app still closed, is refused`() = runTest {
        gate.set(VaultWipeState.RUNNING)

        val result = manager.connectWithPassword(harness.host, "secret".toCharArray())

        assertTrue(result is SshResult.Error)
        assertEquals(1, harness.closedLinks())
        assertEquals(0, manager.activeSessions.value.size, "nothing is registered")
    }

    @Test
    fun `a connection after the wipe is done and the app restarted works again`() = runTest {
        gate.set(VaultWipeState.DONE)
        assertTrue(gate.acknowledgeDone())

        val session = harness.connect()

        assertTrue(harness.isRegistered(session.id))
    }

    @Test
    fun `a connection that begins after the closing, with the gate open again, works`() = runTest {
        manager.closeAllAndWait(1_000)

        val session = harness.connect()

        assertTrue(harness.isRegistered(session.id), "the epoch is the one the connection read when it began")
    }
}
