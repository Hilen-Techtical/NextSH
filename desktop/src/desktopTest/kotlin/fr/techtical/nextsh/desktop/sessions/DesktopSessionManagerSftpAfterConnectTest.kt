// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for the host-list "SFTP" button auto-connect plumbing:
// [DesktopSessionManager.openSession] / [openSessionById] `thenOpenSftp`
// arms a deferred "split an SFTP pane alongside the terminal once connected"
// intent; [onConnectResult] consumes it on Success (splitting the freshly-
// connected tab's root Terminal pane with an SFTP pane piggybacking on the
// just-established SSH session, via [DesktopSessionManager.splitPaneAsSftpAt])
// and clears it on Error; [closeSession] clears it too so a tab closed
// mid-auth never leaves a dangling flag.
//
// Construction strategy mirrors [DesktopSessionManagerFocusTest]: relaxed
// MockK collaborators, `sessions` stubbed to an empty StateFlow for the init
// collector.
//
// BLACK BOX ON PURPOSE. The bookkeeping behind this feature (`sftpAfterConnect`)
// is a private field with no public accessor by design, and these tests do NOT
// reflect into it: every assertion is on what a user can observe: whether the
// tab's content becomes a [TabContent.Split] of Terminal|Sftp in
// [DesktopSessionManager.tabs], which tab holds focus, which pane is focused
// inside the split, and whether [DesktopSftpManager.openSftp] was invoked.
// "The flag was consumed" is therefore asserted the way it actually matters: a
// LATER reconnect of the same tab must not silently split in a second SFTP
// pane the user never asked for, which is exactly the regression the purge
// exists to prevent, and which a `getDeclaredField` assertion could never
// catch on its own.
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionManagerSftpAfterConnectTest {

    // ── Test AppScope ─────────────────────────────────────────────────────────

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun makeHost(id: String, label: String = id) = Host(
        id           = id,
        label        = label,
        hostname     = "example.com",
        port         = 22,
        username     = "root",
        authType     = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private fun success(sshSessionId: String, host: Host) =
        SshResult.Success(SshSession(id = sshSessionId, host = host, status = SessionStatus.CONNECTED))

    /**
     * Builds a manager wired for a scripted sequence of connect outcomes.
     *
     * - [vaultHasPassword] `false` → the vault yields no password and the pane
     *   parks in [TerminalTabStatus.AwaitingPassword]; the flow only resumes
     *   through `submitPassword`.
     * - [connectResults] is consumed one entry per `connectWithPassword` call
     *   (the last entry repeats), so a test can script "fails, then the manual
     *   reconnect succeeds": the only way to observe from the outside whether
     *   the deferred SFTP intent survived the first attempt.
     * - [connectGate] (when non-null) suspends every connect until the test
     *   completes it, which is what lets a tab be closed while its connect is
     *   still in flight.
     *
     * `getTerminalSession` is stubbed non-null for any id so that both
     * `onConnectResult`'s Connected transition and `splitPaneAsSftpAt`'s
     * precondition (root pane is a Terminal in `Connected` status) are
     * satisfied.
     */
    private fun buildManager(
        scope: CoroutineScope,
        vaultHasPassword: Boolean = true,
        connectResults: List<SshResult<SshSession>> = emptyList(),
        connectGate: CompletableDeferred<Unit>? = null,
    ): Pair<DesktopSessionManager, DesktopSftpManager> {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())
        every { sshSessionManager.getTerminalSession(any()) } returns
            mockk<DesktopSshTerminalSession>(relaxed = true)

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns
            if (vaultHasPassword) "pw".toCharArray() else null

        if (connectResults.isNotEmpty()) {
            var attempt = 0
            coEvery { sshSessionManager.connectWithPassword(any(), any()) } coAnswers {
                connectGate?.await()
                connectResults[minOf(attempt++, connectResults.lastIndex)]
            }
        }

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager    = mockk<DesktopSftpManager>(relaxed = true)
        coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
        coEvery { sftpManager.getHomeDirectory(any()) } returns SshResult.Success("/home/root")
        val knownHosts = mockk<DesktopKnownHostsVerifier>(relaxed = true)

        val mgr = DesktopSessionManager(
            hostRepository     = hostRepository,
            vaultManager       = vaultManager,
            sshSessionManager  = sshSessionManager,
            sftpManager        = sftpManager,
            knownHostsVerifier = knownHosts,
            appScope           = TestAppScope(scope),
        )
        return mgr to sftpManager
    }

    // ── Success splits the tab, once and only once ────────────────────────────

    @Test
    fun `Success splits the tab into a focused Terminal-SFTP pane and does not repeat it on a later reconnect`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val (mgr, sftpManager) = buildManager(
                backgroundScope,
                connectResults = listOf(success("ssh-1", host), success("ssh-2", host)),
            )

            val tabId = mgr.openSession(host, thenOpenSftp = true)
            advanceUntilIdle()

            val tabs = mgr.tabs.value
            assertEquals(1, tabs.size, "no separate tab: the terminal tab itself gets split")
            val tab = tabs.single()
            assertEquals(tabId, tab.tabId)
            val split = tab.content as? TabContent.Split
                ?: error("tab content must be a Split, was ${tab.content}")

            val terminalPane = split.first as? TabContent.Terminal
                ?: error("Split.first must be the pre-existing Terminal pane, was ${split.first}")
            val connected = terminalPane.status as? TerminalTabStatus.Connected
                ?: error("Split.first Terminal must be Connected, was ${terminalPane.status}")
            assertEquals("ssh-1", connected.sshSessionId, "the split must link the freshly-connected session")

            val sftpPane = split.second as? TabContent.Sftp
                ?: error("Split.second must be the new Sftp pane, was ${split.second}")
            assertEquals("ssh-1", sftpPane.linkedSshSessionId, "the Sftp pane must piggyback on the same session")

            assertEquals(
                SplitOrientation.HORIZONTAL, split.orientation,
                "side-by-side split: splitPaneAsSftpAt's default orientation",
            )
            assertEquals(
                PaneSlot.RIGHT_OR_BOTTOM, split.focusedSlot,
                "focus lands naturally on the newly-created SFTP pane, as splitPaneAsSftpAt leaves it",
            )
            assertEquals(tabId, mgr.activeTabId.value, "the tab itself stays active: no new tab was ever created")
            coVerify(exactly = 1) { sftpManager.openSftp("ssh-1") }

            // The deferred intent was CONSUMED, not merely fulfilled: reconnecting
            // the same tab (a fresh sshSessionId, "ssh-2") must not split in a
            // second SFTP pane the user never asked for.
            mgr.reconnect(tabId)
            advanceUntilIdle()

            assertEquals(1, mgr.tabs.value.size, "a reconnect must not split in another SFTP pane, nor open a tab")
            coVerify(exactly = 0) { sftpManager.openSftp("ssh-2") }
        }

    // ── Error opens nothing, now or later ─────────────────────────────────────

    @Test
    fun `Error opens no SFTP tab, and the following successful reconnect opens none either`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val (mgr, sftpManager) = buildManager(
                backgroundScope,
                connectResults = listOf(
                    SshResult.Error(SshErrorCode.AUTH_FAILED, "denied"),
                    success("ssh-1", host),
                ),
            )

            val tabId = mgr.openSession(host, thenOpenSftp = true)
            advanceUntilIdle()

            assertEquals(1, mgr.tabs.value.size, "only the (now Error) terminal tab, no SFTP tab")
            coVerify(exactly = 0) { sftpManager.openSftp(any()) }

            // The failed attempt cleared the intent: the manual retry connects
            // successfully and must still not pop an SFTP tab.
            mgr.reconnect(tabId)
            advanceUntilIdle()

            assertEquals(1, mgr.tabs.value.size, "a stale intent would surface here as a surprise SFTP tab")
            coVerify(exactly = 0) { sftpManager.openSftp(any()) }
        }

    // ── The intent survives an AwaitingPassword park ──────────────────────────

    /**
     * The mirror of the purge tests: parking in `AwaitingPassword` is NOT a
     * failure, so the intent must survive it: the user clicked "SFTP" on the
     * host list, got a password prompt, and typing the password has to honour
     * the original request.
     */
    @Test
    fun `an intent armed before an AwaitingPassword park is honoured with a split after submitPassword`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val (mgr, sftpManager) = buildManager(
                backgroundScope,
                vaultHasPassword = false,
                connectResults = listOf(success("ssh-1", host)),
            )

            val tabId = mgr.openSession(host, thenOpenSftp = true)
            advanceUntilIdle()

            assertEquals(1, mgr.tabs.value.size, "still waiting for the password: nothing else opened yet")
            val status = (mgr.tabs.value.single().content as TabContent.Terminal).status
            assertTrue(status is TerminalTabStatus.AwaitingPassword, "expected a password prompt, was $status")
            coVerify(exactly = 0) { sftpManager.openSftp(any()) }

            mgr.submitPassword(tabId, "pw".toCharArray())
            advanceUntilIdle()

            assertEquals(1, mgr.tabs.value.size, "the deferred SFTP intent must survive the AwaitingPassword park, no separate tab")
            val split = mgr.tabs.value.single().content as? TabContent.Split
                ?: error("tab content must become a Split, was ${mgr.tabs.value.single().content}")
            assertTrue(split.first is TabContent.Terminal, "Split.first must be the Terminal pane")
            val sftpPane = split.second as? TabContent.Sftp ?: error("Split.second must be the Sftp pane")
            assertEquals("ssh-1", sftpPane.linkedSshSessionId)
            assertEquals(tabId, mgr.activeTabId.value, "the same tab stays active")
            coVerify(exactly = 1) { sftpManager.openSftp("ssh-1") }
        }

    // ── closeSession clears an intent armed mid-auth ──────────────────────────

    /**
     * The tab is closed while its connect is still in flight, then the connect
     * resolves successfully. Nothing must appear: even though [splitPaneAsSftpAt]
     * itself would refuse to split a tab that no longer exists, a flag left
     * behind by `closeSession` must not even be attempted: this test locks in
     * that the purge happens on close, not that the split call happens to be
     * a no-op afterwards.
     */
    @Test
    fun `a connect resolving after the tab was closed splits no orphan pane`() =
        runTest(UnconfinedTestDispatcher()) {
            val host = makeHost("A")
            val gate = CompletableDeferred<Unit>()
            val (mgr, sftpManager) = buildManager(
                backgroundScope,
                connectResults = listOf(success("ssh-1", host)),
                connectGate = gate,
            )

            val tabId = mgr.openSession(host, thenOpenSftp = true)
            advanceUntilIdle()
            assertEquals(1, mgr.tabs.value.size, "connect still in flight, only the Connecting terminal tab")

            mgr.closeSession(tabId)
            advanceUntilIdle()
            assertTrue(mgr.tabs.value.isEmpty(), "the tab was closed mid-auth")

            // The SSH connect now succeeds, too late.
            gate.complete(Unit)
            advanceUntilIdle()

            assertTrue(mgr.tabs.value.isEmpty(), "a stale intent would resurrect the tab as a Split here")
            coVerify(exactly = 0) { sftpManager.openSftp(any()) }
        }

    // ── openSession without the flag never opens SFTP ─────────────────────────

    @Test
    fun `openSession without thenOpenSftp never auto-splits an SFTP pane`() = runTest(UnconfinedTestDispatcher()) {
        val host = makeHost("A")
        val (mgr, sftpManager) = buildManager(
            backgroundScope,
            connectResults = listOf(success("ssh-1", host)),
        )

        val tabId = mgr.openSession(host) // thenOpenSftp defaults to false
        advanceUntilIdle()

        assertEquals(1, mgr.tabs.value.size, "no separate tab, and no split, auto-opened")
        assertTrue(
            mgr.tabs.value.single().content is TabContent.Terminal,
            "the tab must stay a plain (unsplit) Terminal pane",
        )
        assertEquals(tabId, mgr.activeTabId.value)
        coVerify(exactly = 0) { sftpManager.openSftp(any()) }
    }
}
