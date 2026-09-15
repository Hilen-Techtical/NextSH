// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for [paneLeafPaths] (pure DFS leaf-path resolver) and
// [DesktopSessionManager.focusNextPane] / [focusPreviousPane] (Ctrl+Shift+N /
// Ctrl+Shift+P): split-pane cycling within a single tab, mirroring the
// tab-cycling coverage in [DesktopSessionManagerTabCycleTest] (same
// positive-modulo wrap-around arithmetic, same construction strategy).
//
// [paneLeafPaths] is exercised directly with hand-built [TabContent] trees
// (style borrowed from [DesktopSessionManagerFocusTest]'s treatment of
// [focusedConnectedTerminalId]). The manager-level tests need REAL
// [TabContent.Split] trees, which only [DesktopSessionManager.splitPaneAt]
// can build, so `sshSessionManager.connectWithPassword` / `getTerminalSession`
// are stubbed to succeed (unlike TabCycleTest's AwaitingPassword-only setup)
// so panes reach [TerminalTabStatus.Connected] and `splitPaneAt`'s
// precondition is satisfied.
// ──────────────────────────────────────────────────────────────────────────────

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
class DesktopSessionManagerPaneCycleTest {

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

    /** Same as [DesktopSessionManagerTabCycleTest]'s builder: panes stay AwaitingPassword. */
    private fun buildManager(scope: CoroutineScope): DesktopSessionManager {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns null

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager    = mockk<DesktopSftpManager>(relaxed = true)
        val knownHosts     = mockk<DesktopKnownHostsVerifier>(relaxed = true)

        return DesktopSessionManager(
            hostRepository    = hostRepository,
            vaultManager      = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager       = sftpManager,
            knownHostsVerifier = knownHosts,
            appScope          = TestAppScope(scope),
        )
    }

    /**
     * Builder whose panes actually reach [TerminalTabStatus.Connected]:
     * needed so [DesktopSessionManager.splitPaneAt] (which requires the
     * target pane Connected) can build real [TabContent.Split] trees.
     * `connectWithPassword` succeeds for ANY host with a single shared
     * sshSessionId: these tests only assert on [TabContent.Split] shape /
     * focus paths, never on which SSH session backs which pane, so a fixed
     * id keeps the stubbing simple (no need to branch on the call args).
     *
     * [sftpOpenSucceeds] additionally stubs [DesktopSftpManager] so
     * [DesktopSessionManager.splitPaneAsSftpAt] can build a real
     * `Terminal | Sftp` split. The stubs are EXPLICIT (not `relaxed`) because
     * `openSftp` / `getHomeDirectory` return the sealed [SshResult], which a
     * relaxed mock cannot fabricate a valid instance of.
     */
    private fun buildConnectingManager(
        scope: CoroutineScope,
        sftpOpenSucceeds: Boolean = false,
    ): DesktopSessionManager {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())
        coEvery { sshSessionManager.connectWithPassword(any(), any()) } returns
            SshResult.Success(SshSession(id = "ssh-shared", host = makeHost("shared"), status = SessionStatus.CONNECTED))
        every { sshSessionManager.getTerminalSession(any()) } returns
            mockk<DesktopSshTerminalSession>(relaxed = true)

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns "pw".toCharArray()

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager    = mockk<DesktopSftpManager>(relaxed = true)
        if (sftpOpenSucceeds) {
            coEvery { sftpManager.openSftp(any()) } returns SshResult.Success(Unit)
            coEvery { sftpManager.getHomeDirectory(any()) } returns SshResult.Success("/root")
        }
        val knownHosts     = mockk<DesktopKnownHostsVerifier>(relaxed = true)

        return DesktopSessionManager(
            hostRepository    = hostRepository,
            vaultManager      = vaultManager,
            sshSessionManager = sshSessionManager,
            sftpManager       = sftpManager,
            knownHostsVerifier = knownHosts,
            appScope          = TestAppScope(scope),
        )
    }

    private val theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true))

    private fun connectedTerminal(sshSessionId: String) = TabContent.Terminal(
        theme  = theme,
        status = TerminalTabStatus.Connected(
            sshSessionId = sshSessionId,
            terminal     = mockk<DesktopSshTerminalSession>(relaxed = true),
        ),
    )

    private fun sftpPane() = TabContent.Sftp(
        linkedSshSessionId = "linked",
        state              = SftpTabState.Opening,
    )

    // ── paneLeafPaths (pure) ──────────────────────────────────────────────────

    @Test
    fun `lone terminal yields a single empty-path leaf`() {
        assertEquals(listOf(emptyList()), paneLeafPaths(connectedTerminal("ssh-1")))
    }

    @Test
    fun `lone sftp pane yields a single empty-path leaf`() {
        assertEquals(listOf(emptyList()), paneLeafPaths(sftpPane()))
    }

    @Test
    fun `two-pane split yields left-then-right in DFS order`() {
        val split = TabContent.Split(
            first       = connectedTerminal("ssh-left"),
            second      = connectedTerminal("ssh-right"),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals(
            listOf(listOf(PaneSlot.LEFT_OR_TOP), listOf(PaneSlot.RIGHT_OR_BOTTOM)),
            paneLeafPaths(split),
        )
    }

    @Test
    fun `nested split yields three leaves in visual DFS order`() {
        // root: first = A ; second = Split(first = B, second = C)
        val inner = TabContent.Split(
            first       = connectedTerminal("ssh-B"),
            second      = connectedTerminal("ssh-C"),
            orientation = SplitOrientation.VERTICAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        val root = TabContent.Split(
            first       = connectedTerminal("ssh-A"),
            second      = inner,
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals(
            listOf(
                listOf(PaneSlot.LEFT_OR_TOP),                                   // A
                listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.LEFT_OR_TOP),          // B
                listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.RIGHT_OR_BOTTOM),      // C
            ),
            paneLeafPaths(root),
        )
    }

    // ── terminalPaneLeafPaths (pure) ──────────────────────────────────────────
    //
    // Same DFS order as [paneLeafPaths] but SFTP leaves are dropped: this is
    // what the Ctrl+Shift+N / Ctrl+Shift+P cycle walks, so the selection ring
    // can never land on a pane that would not take keyboard focus.

    @Test
    fun `terminal leaves - lone terminal yields a single empty-path leaf`() {
        assertEquals(listOf(emptyList()), terminalPaneLeafPaths(connectedTerminal("ssh-1")))
    }

    @Test
    fun `terminal leaves - lone sftp pane yields no leaf at all`() {
        assertEquals(emptyList(), terminalPaneLeafPaths(sftpPane()))
    }

    @Test
    fun `terminal leaves - non-connected terminals are still cycle targets`() {
        // A Connecting / AwaitingPassword / Error pane renders a real focusable
        // Compose surface (spinner, password field, Reconnect button), so the
        // cycle must be able to land on it: unlike broadcast, which needs a
        // live shell and therefore filters on Connected.
        val split = TabContent.Split(
            first       = TabContent.Terminal(theme, TerminalTabStatus.Connecting),
            second      = TabContent.Terminal(theme, TerminalTabStatus.Error("boom")),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.LEFT_OR_TOP,
        )
        assertEquals(
            listOf(listOf(PaneSlot.LEFT_OR_TOP), listOf(PaneSlot.RIGHT_OR_BOTTOM)),
            terminalPaneLeafPaths(split),
        )
    }

    @Test
    fun `terminal leaves - split with an sftp pane keeps only the terminal side`() {
        val split = TabContent.Split(
            first       = connectedTerminal("ssh-1"),
            second      = sftpPane(),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals(listOf(listOf(PaneSlot.LEFT_OR_TOP)), terminalPaneLeafPaths(split))
    }

    @Test
    fun `terminal leaves - nested split drops the sftp leaf and keeps DFS order`() {
        // root: first = A (terminal) ; second = Split(first = SFTP, second = C)
        val inner = TabContent.Split(
            first       = sftpPane(),
            second      = connectedTerminal("ssh-C"),
            orientation = SplitOrientation.VERTICAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        val root = TabContent.Split(
            first       = connectedTerminal("ssh-A"),
            second      = inner,
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals(
            listOf(
                listOf(PaneSlot.LEFT_OR_TOP),                                // A
                listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.RIGHT_OR_BOTTOM),  // C
            ),
            terminalPaneLeafPaths(root),
        )
        // The full leaf list still has three entries: only the CYCLE skips the
        // SFTP one, the pane tree itself is untouched.
        assertEquals(3, paneLeafPaths(root).size)
    }

    // ── focusNextPane / focusPreviousPane - no-op guards ──────────────────────

    @Test
    fun `focusNextPane on empty manager is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.focusNextPane()
        assertEquals(0L, mgr.terminalFocusEpoch.value, "no active tab → epoch must not bump")
    }

    @Test
    fun `focusPreviousPane on empty manager is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.focusPreviousPane()
        assertEquals(0L, mgr.terminalFocusEpoch.value, "no active tab → epoch must not bump")
    }

    @Test
    fun `focusNextPane on a single-pane tab is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        mgr.focusNextPane()
        assertEquals(0L, mgr.terminalFocusEpoch.value, "single pane (no Split) → epoch must not bump")
    }

    @Test
    fun `focusPreviousPane on a single-pane tab is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        mgr.focusPreviousPane()
        assertEquals(0L, mgr.terminalFocusEpoch.value, "single pane (no Split) → epoch must not bump")
    }

    // ── focusNextPane / focusPreviousPane - 2-pane split ──────────────────────

    /**
     * Seed a 2-pane split (A | B). `splitPaneAt` leaves focus on the new
     * RIGHT_OR_BOTTOM pane (B): assert forward/backward cycling wraps
     * correctly between the two panes and bumps [terminalFocusEpoch] on
     * every move.
     */
    @Test
    fun `focusNextPane and focusPreviousPane wrap between two panes and bump the epoch`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildConnectingManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()

            fun focusedPathNow() = mgr.focusedPath(mgr.tabs.value.first { it.tabId == tabId }.content)

            // splitPaneAt focuses the freshly created RIGHT_OR_BOTTOM pane (B).
            assertEquals(listOf(PaneSlot.RIGHT_OR_BOTTOM), focusedPathNow())
            assertEquals(0L, mgr.terminalFocusEpoch.value)

            mgr.focusNextPane() // B → wrap → A
            assertEquals(listOf(PaneSlot.LEFT_OR_TOP), focusedPathNow())
            assertEquals(1L, mgr.terminalFocusEpoch.value)

            mgr.focusNextPane() // A → B
            assertEquals(listOf(PaneSlot.RIGHT_OR_BOTTOM), focusedPathNow())
            assertEquals(2L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane() // B → A
            assertEquals(listOf(PaneSlot.LEFT_OR_TOP), focusedPathNow())
            assertEquals(3L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane() // A → wrap → B
            assertEquals(listOf(PaneSlot.RIGHT_OR_BOTTOM), focusedPathNow())
            assertEquals(4L, mgr.terminalFocusEpoch.value)
        }

    // ── focusNextPane / focusPreviousPane - nested 3-pane split ───────────────

    /**
     * Seed a nested 3-pane split: root(A, Split(B, C)), built by splitting
     * the tab root with B, then splitting B's pane with C. DFS visual order
     * is A → B → C; the second split leaves focus on C.
     */
    @Test
    fun `focusNextPane and focusPreviousPane cycle through three nested panes`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildConnectingManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle() // let B's async connect land so it can itself be split
            assertTrue(mgr.splitPaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM), makeHost("C")))
            advanceUntilIdle()

            fun currentContent() = mgr.tabs.value.first { it.tabId == tabId }.content
            fun focusedPathNow() = mgr.focusedPath(currentContent())
            val leaves = paneLeafPaths(currentContent())
            assertEquals(3, leaves.size, "expected 3 leaves: A, B, C")

            val pathA = leaves[0]
            val pathB = leaves[1]
            val pathC = leaves[2]

            // Second splitPaneAt focuses the freshly created pane (C).
            assertEquals(pathC, focusedPathNow())
            assertEquals(0L, mgr.terminalFocusEpoch.value)

            mgr.focusNextPane() // C → wrap → A
            assertEquals(pathA, focusedPathNow())
            assertEquals(1L, mgr.terminalFocusEpoch.value)

            mgr.focusNextPane() // A → B
            assertEquals(pathB, focusedPathNow())
            assertEquals(2L, mgr.terminalFocusEpoch.value)

            mgr.focusNextPane() // B → C
            assertEquals(pathC, focusedPathNow())
            assertEquals(3L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane() // C → B
            assertEquals(pathB, focusedPathNow())
            assertEquals(4L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane() // B → A
            assertEquals(pathA, focusedPathNow())
            assertEquals(5L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane() // A → wrap → C
            assertEquals(pathC, focusedPathNow())
            assertEquals(6L, mgr.terminalFocusEpoch.value)
        }

    // ── SFTP panes are skipped by the cycle ───────────────────────────────────

    /**
     * Terminal | SFTP split. The cycle must never park on the SFTP pane: it
     * moved the selection ring but not the keyboard focus (only Terminal pane
     * renderers observe [DesktopSessionManager.terminalFocusEpoch]), so the
     * next keystroke went to the terminal the user had just left.
     *
     * `splitPaneAsSftpAt` leaves focus on the new SFTP pane, which is the
     * exact state the user is in right after splitting, so the FIRST
     * Ctrl+Shift+N must bring them back to the terminal, and the second must
     * do nothing rather than bounce onto the SFTP browser again.
     */
    @Test
    fun `pane cycle skips the sftp pane of a terminal-sftp split`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildConnectingManager(backgroundScope, sftpOpenSucceeds = true)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAsSftpAt(tabId, emptyList()))
            advanceUntilIdle()

            fun currentContent() = mgr.tabs.value.first { it.tabId == tabId }.content
            fun focusedPathNow() = mgr.focusedPath(currentContent())

            // Sanity: really a Terminal | Sftp split, focused on the SFTP side.
            val root = currentContent() as TabContent.Split
            assertTrue(root.first is TabContent.Terminal)
            assertTrue(root.second is TabContent.Sftp)
            assertEquals(listOf(PaneSlot.RIGHT_OR_BOTTOM), focusedPathNow())
            assertEquals(0L, mgr.terminalFocusEpoch.value)

            // SFTP focused → the only terminal is the single cycle target.
            mgr.focusNextPane()
            assertEquals(listOf(PaneSlot.LEFT_OR_TOP), focusedPathNow())
            assertEquals(1L, mgr.terminalFocusEpoch.value)

            // Already on the only terminal → no move, and no epoch bump either
            // (bumping would re-grab focus for nothing on every key press).
            mgr.focusNextPane()
            assertEquals(listOf(PaneSlot.LEFT_OR_TOP), focusedPathNow())
            assertEquals(1L, mgr.terminalFocusEpoch.value)

            mgr.focusPreviousPane()
            assertEquals(listOf(PaneSlot.LEFT_OR_TOP), focusedPathNow())
            assertEquals(1L, mgr.terminalFocusEpoch.value)
        }

    /**
     * Terminal | SFTP | Terminal: the SFTP pane sits BETWEEN the two
     * terminals in DFS order, so a correct cycle jumps straight over it
     * instead of spending one keystroke on it.
     */
    @Test
    fun `pane cycle jumps over an sftp pane sitting between two terminals`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildConnectingManager(backgroundScope, sftpOpenSucceeds = true)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            // root = Split(A, B)
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            // A becomes Split(A, SFTP) → DFS: A, SFTP, B
            assertTrue(mgr.splitPaneAsSftpAt(tabId, listOf(PaneSlot.LEFT_OR_TOP)))
            advanceUntilIdle()

            fun currentContent() = mgr.tabs.value.first { it.tabId == tabId }.content
            fun focusedPathNow() = mgr.focusedPath(currentContent())

            val pathA = listOf(PaneSlot.LEFT_OR_TOP, PaneSlot.LEFT_OR_TOP)
            val pathSftp = listOf(PaneSlot.LEFT_OR_TOP, PaneSlot.RIGHT_OR_BOTTOM)
            val pathB = listOf(PaneSlot.RIGHT_OR_BOTTOM)
            assertEquals(listOf(pathA, pathSftp, pathB), paneLeafPaths(currentContent()))
            assertEquals(listOf(pathA, pathB), terminalPaneLeafPaths(currentContent()))

            // splitPaneAsSftpAt focused the new SFTP pane.
            assertEquals(pathSftp, focusedPathNow())

            mgr.focusNextPane() // SFTP (not a target) → first terminal, A
            assertEquals(pathA, focusedPathNow())

            mgr.focusNextPane() // A → B, skipping the SFTP pane in between
            assertEquals(pathB, focusedPathNow())

            mgr.focusNextPane() // B → wrap → A
            assertEquals(pathA, focusedPathNow())

            mgr.focusPreviousPane() // A → wrap back → B
            assertEquals(pathB, focusedPathNow())
        }
}
