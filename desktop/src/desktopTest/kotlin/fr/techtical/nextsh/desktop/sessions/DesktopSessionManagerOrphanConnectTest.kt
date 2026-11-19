// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// A connection can succeed after the tab (or pane) that asked for it is gone. Rule under test: a connected
// SSH session that no open tab references is closed, never left authenticated for the whole process. Also
// the wait-for-the-end variant of closeAllSessions, which the vault wipe relies on.
//
// Construction strategy mirrors [DesktopSessionManagerSftpAfterConnectTest]: relaxed MockK collaborators.
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionManagerOrphanConnectTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private class Rig(
        val manager: DesktopSessionManager,
        val sshSessionManager: DesktopSshSessionManager,
        val sftpManager: DesktopSftpManager,
        val hostRepository: HostRepository,
    )

    private fun makeHost(id: String) = Host(
        id = id, label = id, hostname = "example.com", port = 22,
        username = "root", authType = AuthType.PASSWORD, credentialId = "cred-$id",
    )

    private fun success(sshSessionId: String, host: Host) =
        SshResult.Success(SshSession(id = sshSessionId, host = host, status = SessionStatus.CONNECTED))

    /**
     * [gates] holds one optional gate per connect call, in call order: the call waits for it before
     * answering with `ssh-<n>` (n starting at 1). A null entry answers at once.
     */
    private fun buildRig(
        scope: CoroutineScope,
        host: Host,
        gates: List<CompletableDeferred<Unit>?>,
        liveSessions: List<SshSession> = emptyList(),
    ): Rig {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(liveSessions)
        every { sshSessionManager.getTerminalSession(any()) } returns mockk<DesktopSshTerminalSession>(relaxed = true)
        var attempt = 0
        coEvery { sshSessionManager.connectWithPassword(any(), any()) } coAnswers {
            val n = attempt++
            gates.getOrNull(n)?.await()
            success("ssh-${n + 1}", host)
        }
        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns "pw".toCharArray()
        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager = mockk<DesktopSftpManager>(relaxed = true)
        val manager = DesktopSessionManager(
            hostRepository = hostRepository,
            vaultManager = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager = sftpManager,
            knownHostsVerifier = mockk<DesktopKnownHostsVerifier>(relaxed = true),
            appScope = TestAppScope(scope),
        )
        return Rig(manager, sshSessionManager, sftpManager, hostRepository)
    }

    @Test
    fun `a connection that succeeds after its tab was closed is disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val gate = CompletableDeferred<Unit>()
            val rig = buildRig(backgroundScope, host, listOf(gate))

            val tabId = rig.manager.openSession(host)
            advanceUntilIdle()
            rig.manager.closeSession(tabId)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(rig.manager.tabs.value.isEmpty())
            coVerify(exactly = 1) { rig.sshSessionManager.disconnect("ssh-1") }
            coVerify(exactly = 1) { rig.sftpManager.closeSftp("ssh-1") }
            coVerify(exactly = 0) { rig.hostRepository.updateLastConnected(any()) }
        }

    @Test
    fun `a connection that succeeds after closeAllSessions is disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val gate = CompletableDeferred<Unit>()
            val rig = buildRig(backgroundScope, host, listOf(gate))

            rig.manager.openSession(host, thenOpenSftp = true)
            advanceUntilIdle()
            rig.manager.closeAllSessions()
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(rig.manager.tabs.value.isEmpty(), "no tab, no split resurrected by a stale SFTP intent")
            coVerify(exactly = 1) { rig.sshSessionManager.disconnect("ssh-1") }
            coVerify(exactly = 0) { rig.sftpManager.openSftp(any()) }
        }

    @Test
    fun `a connection that succeeds after its split pane was closed is disconnected`() =
        runTest(UnconfinedTestDispatcher()) {
            val hostA = makeHost("A")
            val hostB = makeHost("B")
            val gate = CompletableDeferred<Unit>()
            // First connect (the tab) answers at once, the second (the pane) waits.
            val rig = buildRig(backgroundScope, hostA, listOf(null, gate))

            val tabId = rig.manager.openSession(hostA)
            advanceUntilIdle()
            assertTrue(rig.manager.splitPaneAt(tabId, emptyList(), hostB))
            advanceUntilIdle()
            rig.manager.closePaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM))
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()

            coVerify(exactly = 1) { rig.sshSessionManager.disconnect("ssh-2") }
            coVerify(exactly = 0) { rig.sshSessionManager.disconnect("ssh-1") }
            assertEquals(1, rig.manager.tabs.value.size, "the first pane stays")
        }

    @Test
    fun `a connection that succeeds into an open tab is kept`() = runTest(UnconfinedTestDispatcher()) {
        val host = makeHost("A")
        val rig = buildRig(backgroundScope, host, listOf(null))

        rig.manager.openSession(host)
        advanceUntilIdle()

        coVerify(exactly = 0) { rig.sshSessionManager.disconnect(any()) }
        coVerify(exactly = 1) { rig.hostRepository.updateLastConnected("A") }
    }

    @Test
    fun `closeAllSessionsAndWait closes the tabs then disconnects every session, orphans included`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val live = listOf(
                SshSession(id = "ssh-1", host = host, status = SessionStatus.CONNECTED),
                SshSession(id = "orphan", host = host, status = SessionStatus.CONNECTED),
            )
            val rig = buildRig(backgroundScope, host, listOf(null), liveSessions = live)
            rig.manager.openSession(host)
            advanceUntilIdle()

            rig.manager.closeAllSessionsAndWait()

            assertTrue(rig.manager.tabs.value.isEmpty())
            coVerify(exactly = 1) { rig.sftpManager.closeSftp("orphan") }
            coVerifyOrder {
                rig.sftpManager.closeSftp("orphan")
                rig.sshSessionManager.disconnectAll()
            }
        }

    // -- Wipe: host key lockdown and bounded close ---------------------------------

    private class Verifier {
        val unknown = io.mockk.slot<suspend (fr.techtical.nextsh.desktop.core.ssh.HostKeyVerifyResult.Unknown) -> Boolean>()
        val mismatch = io.mockk.slot<(fr.techtical.nextsh.desktop.core.ssh.HostKeyVerifyResult.Mismatch) -> Unit>()
        val mock = mockk<DesktopKnownHostsVerifier>(relaxed = true).also {
            every { it.unknownHostCallback = capture(unknown) } just io.mockk.Runs
            every { it.mismatchListener = capture(mismatch) } just io.mockk.Runs
        }
    }

    private fun managerWithVerifier(scope: CoroutineScope, verifier: Verifier): DesktopSessionManager {
        val ssh = mockk<DesktopSshSessionManager>(relaxed = true)
        every { ssh.sessions } returns MutableStateFlow(emptyList<SshSession>())
        return DesktopSessionManager(
            hostRepository = mockk<HostRepository>(relaxed = true),
            vaultManager = mockk<VaultManager>(relaxed = true),
            sshSessionManager = ssh,
            sftpManager = mockk<DesktopSftpManager>(relaxed = true),
            knownHostsVerifier = verifier.mock,
            appScope = TestAppScope(scope),
        )
    }

    @Test
    fun `during the lockdown a host key question is answered no at once and none is shown`() =
        runTest(UnconfinedTestDispatcher()) {
            val verifier = Verifier()
            val manager = managerWithVerifier(backgroundScope, verifier)
            manager.setHostKeyLockdown(true)

            val answer = verifier.unknown.captured.invoke(mockk(relaxed = true))

            assertEquals(false, answer)
            assertEquals(null, manager.hostKeyPrompt.value)
        }

    @Test
    fun `a question already on screen is refused when the lockdown begins`() = runTest(UnconfinedTestDispatcher()) {
        val verifier = Verifier()
        val manager = managerWithVerifier(backgroundScope, verifier)
        val answer = kotlinx.coroutines.CompletableDeferred<Boolean>()
        backgroundScope.launch { answer.complete(verifier.unknown.captured.invoke(mockk(relaxed = true))) }
        advanceUntilIdle()
        assertTrue(manager.hostKeyPrompt.value != null, "the question is on screen")

        manager.setHostKeyLockdown(true)
        advanceUntilIdle()

        assertEquals(false, answer.await())
        assertEquals(null, manager.hostKeyPrompt.value)
    }

    @Test
    fun `the mismatch alert is dropped by the lockdown and none is raised during it`() =
        runTest(UnconfinedTestDispatcher()) {
            val verifier = Verifier()
            val manager = managerWithVerifier(backgroundScope, verifier)
            verifier.mismatch.captured.invoke(mockk(relaxed = true))
            assertTrue(manager.hostKeyMismatch.value != null)

            manager.setHostKeyLockdown(true)
            assertEquals(null, manager.hostKeyMismatch.value)
            verifier.mismatch.captured.invoke(mockk(relaxed = true))
            assertEquals(null, manager.hostKeyMismatch.value)

            manager.setHostKeyLockdown(false)
            verifier.mismatch.captured.invoke(mockk(relaxed = true))
            assertTrue(manager.hostKeyMismatch.value != null, "questions and alerts work again after the wipe")
        }

    @Test
    fun `closeAllSessionsAndWait gives the hand back at the delay when a close blocks a thread`() = kotlinx.coroutines.runBlocking {
        // Real time, real scope: the close is BLOCKING (a dead route), which a cancellation cannot interrupt.
        val realScope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val host = makeHost("A")
            val rig = buildRig(realScope, host, listOf(null))
            coEvery { rig.sshSessionManager.disconnectAll() } coAnswers {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(3_000L) }
            }

            val started = System.currentTimeMillis()
            val ended = rig.manager.closeAllSessionsAndWait(timeoutMs = 300L)
            val elapsed = System.currentTimeMillis() - started

            assertEquals(false, ended)
            assertTrue(elapsed < 2_000L, "the wait is bounded even for a blocking close, it took $elapsed ms")
        } finally {
            realScope.cancel()
        }
    }
}
