// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for the broadcast ("synchronize-panes", Ctrl+Shift+B) feature:
// - [connectedTerminalIdsIn]: the pure resolver that lists a tab's relay
//   targets (Connected Terminal panes only, never Sftp, never a Connecting /
//   AwaitingPassword / Disconnected / Error Terminal).
// - [DesktopSessionManager.toggleBroadcastOnActiveTab]: the Ctrl+Shift+B
//   toggle, no-op below 2 connected panes.
// - [DesktopSessionManager.sendToActiveTerminal]'s broadcast-aware fan-out
//   (command mode: the snippet picker's delivery path).
// - [fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalSession.onUserInput]
//   relay (live-typing mode) and its anti-loop guarantee.
// - Broadcast purge on [DesktopSessionManager.closeSession] /
//   [DesktopSessionManager.closePaneAt].
//
// Construction strategy mirrors [DesktopSessionManagerPaneCycleTest]'s
// `buildConnectingManager` (openSession/splitPaneAt + advanceUntilIdle
// between each async step), except `connectWithPassword` here returns a
// DISTINCT sshSessionId per host (`ssh-<hostId>`) so each split pane gets
// its own mocked [TtyConnector] and its own `ComposeTerminalSession`:
// required to assert fan-out / relay independently per target, which a
// single shared sshSessionId (as in PaneCycleTest) can't do.
//
// TIMING DISCIPLINE (uniform across this file, no exceptions): every
// `advanceUntilIdle()` call below drains the scheduler shared by the
// [UnconfinedTestDispatcher] and `backgroundScope`, on which the manager's
// [AppScope] runs. One is placed after EVERY manager call that can launch a
// coroutine (`openSession`, `splitPaneAt`, `closeSession`, `closePaneAt`)
// and immediately before EVERY assertion block that observes the result of
// one, so no `verify` can ever race a still-pending continuation. The write
// path itself is NOT coroutine-based: `ComposeTerminalSession.sendBytes` goes
// through JediTerm's `TerminalStarter`, which performs `TtyConnector.write` on
// its own real executor thread that `advanceUntilIdle()` cannot drain. Hence
// every positive write assertion uses `verify(timeout = …)` to poll until the
// executor lands the write. The drain must still be systematic rather than
// case-by-case: an assertion that passes only because the production path is
// currently synchronous is a false negative waiting for the day it isn't.
// ──────────────────────────────────────────────────────────────────────────────

import com.jediterm.terminal.TtyConnector
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsVerifier
import fr.techtical.nextsh.desktop.core.ssh.DesktopSftpManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshSessionManager
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshTerminalSession
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.desktop.theme.TerminalThemePalette
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
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionManagerBroadcastTest {

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

    private val theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true))

    private val palette = TerminalThemePalette(
        background  = 0x000000,
        foreground  = 0xFFFFFF,
        cursor      = 0xFFFFFF,
        selectionBg = 0x333333,
        ansi        = IntArray(16),
    )

    private fun connectedTerminal(sshSessionId: String) = TabContent.Terminal(
        theme  = theme,
        status = TerminalTabStatus.Connected(
            sshSessionId = sshSessionId,
            terminal     = mockk<DesktopSshTerminalSession>(relaxed = true),
        ),
    )

    private fun sftpPane(linkedSshSessionId: String = "linked") = TabContent.Sftp(
        linkedSshSessionId = linkedSshSessionId,
        state              = SftpTabState.Opening,
    )

    /**
     * Each host connects to its OWN sshSessionId (`ssh-<hostId>`) rather
     * than a single shared id: the broadcast tests need independently
     * addressable panes (own connector, own compose session).
     */
    private fun buildBroadcastManager(scope: CoroutineScope): DesktopSessionManager {
        val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
        every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())
        coEvery { sshSessionManager.connectWithPassword(any(), any()) } answers {
            val host = firstArg<Host>()
            SshResult.Success(SshSession(id = "ssh-${host.id}", host = host, status = SessionStatus.CONNECTED))
        }
        every { sshSessionManager.getTerminalSession(any()) } returns
            mockk<DesktopSshTerminalSession>(relaxed = true)

        val vaultManager = mockk<VaultManager>(relaxed = true)
        coEvery { vaultManager.getPassword(any()) } returns "pw".toCharArray()

        val hostRepository = mockk<HostRepository>(relaxed = true)
        val sftpManager    = mockk<DesktopSftpManager>(relaxed = true)
        val knownHosts     = mockk<DesktopKnownHostsVerifier>(relaxed = true)

        return DesktopSessionManager(
            hostRepository     = hostRepository,
            vaultManager       = vaultManager,
            sshSessionManager  = sshSessionManager,
            sftpManager        = sftpManager,
            knownHostsVerifier = knownHosts,
            appScope           = TestAppScope(scope),
        )
    }

    // ── connectedTerminalIdsIn (pure resolver) ────────────────────────────────

    @Test
    fun `lone connected terminal resolves to a single-element list`() {
        assertEquals(listOf("ssh-1"), connectedTerminalIdsIn(connectedTerminal("ssh-1")))
    }

    @Test
    fun `lone sftp pane resolves to an empty list`() {
        assertEquals(emptyList(), connectedTerminalIdsIn(sftpPane()))
    }

    @Test
    fun `non-connected terminal states are excluded`() {
        val host = makeHost("A")
        assertEquals(emptyList(), connectedTerminalIdsIn(TabContent.Terminal(theme, TerminalTabStatus.Connecting)))
        assertEquals(emptyList(), connectedTerminalIdsIn(TabContent.Terminal(theme, TerminalTabStatus.AwaitingPassword(host))))
        assertEquals(emptyList(), connectedTerminalIdsIn(TabContent.Terminal(theme, TerminalTabStatus.Disconnected("gone"))))
        assertEquals(emptyList(), connectedTerminalIdsIn(TabContent.Terminal(theme, TerminalTabStatus.Error("boom"))))
    }

    @Test
    fun `split with an sftp pane excludes the sftp side, even sharing the terminal's sshSessionId`() {
        val split = TabContent.Split(
            first       = connectedTerminal("ssh-1"),
            // linkedSshSessionId intentionally matches the terminal's id:
            // broadcast must still never target the SFTP browser, there is
            // no shell on the other end to receive relayed bytes.
            second      = sftpPane(linkedSshSessionId = "ssh-1"),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals(listOf("ssh-1"), connectedTerminalIdsIn(split))
    }

    @Test
    fun `nested split excludes non-connected panes but keeps every connected terminal in DFS order`() {
        // root: first = A (connected) ; second = Split(first = B (connecting), second = C (connected))
        val inner = TabContent.Split(
            first       = TabContent.Terminal(theme, TerminalTabStatus.Connecting),
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
        assertEquals(listOf("ssh-A", "ssh-C"), connectedTerminalIdsIn(root))
    }

    // ── toggleBroadcastOnActiveTab - no-op guards ─────────────────────────────

    @Test
    fun `toggle on empty manager is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildBroadcastManager(backgroundScope)
        mgr.toggleBroadcastOnActiveTab()
        advanceUntilIdle()
        assertTrue(mgr.broadcastTabIds.value.isEmpty())
    }

    @Test
    fun `toggle on a single-pane tab is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildBroadcastManager(backgroundScope)
        val tabId = mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        mgr.toggleBroadcastOnActiveTab()
        advanceUntilIdle()
        assertFalse(tabId in mgr.broadcastTabIds.value, "single connected pane (no Split) → toggle must stay off")
    }

    // ── toggleBroadcastOnActiveTab - on/off with 2 connected panes ────────────

    @Test
    fun `toggle turns broadcast on with 2 connected panes, and off again on a second call`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            mgr.getOrCreateComposeSession("ssh-A", mockk<TtyConnector>(relaxed = true), palette)
            mgr.getOrCreateComposeSession("ssh-B", mockk<TtyConnector>(relaxed = true), palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertFalse(tabId in mgr.broadcastTabIds.value)
        }

    // ── Command-mode fan-out (sendToActiveTerminal) ───────────────────────────

    @Test
    fun `sendToActiveTerminal only reaches the focused pane when broadcast is off`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()

            // splitPaneAt focuses the freshly created RIGHT_OR_BOTTOM pane (B).
            assertTrue(mgr.sendToActiveTerminal("ls -la"))
            advanceUntilIdle()
            verify(timeout = 2_000, exactly = 1) { connectorB.write("ls -la") }
            verify(exactly = 0) { connectorA.write("ls -la") }
        }

    @Test
    fun `sendToActiveTerminal fans out to every connected pane when broadcast is on`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            assertTrue(mgr.sendToActiveTerminal("uptime"))
            advanceUntilIdle()
            verify(timeout = 2_000, exactly = 1) { connectorA.write("uptime") }
            verify(timeout = 2_000, exactly = 1) { connectorB.write("uptime") }
        }

    @Test
    fun `fan-out reaches a mounted target even when a sibling has no compose session yet`() =
        runTest(UnconfinedTestDispatcher()) {
            // B is Connected in the TabContent tree (toggle's guard is
            // topology-based, not compose-session-based) but its renderer
            // never mounted a compose session: the fan-out loop must still
            // deliver to A rather than aborting for the whole tab.
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            assertTrue(mgr.sendToActiveTerminal("echo hi"))
            advanceUntilIdle()
            verify(timeout = 2_000, exactly = 1) { connectorA.write("echo hi") }
        }

    // ── Live-typing relay (onUserInput hook) ──────────────────────────────────

    @Test
    fun `a userInput=true send on one pane relays to the sibling with userInput=false and is never re-relayed`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            val composeA = mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            val bytes = "x".toByteArray()
            composeA.sendBytes(bytes, userInput = true)
            advanceUntilIdle()

            // Content-based matchers (not raw `bytes` reference equality)
            // since JediTerm's TerminalStarter is free to copy the array
            // internally before it reaches the connector.
            // Direct delivery to A (the pane the "user" typed into)...
            verify(timeout = 2_000, exactly = 1) { connectorA.write(match<ByteArray> { it.contentEquals(bytes) }) }
            // ...and exactly one mirrored delivery to B. If the mirror were
            // itself re-mirrored (broken anti-loop), B's own hook (also
            // installed since B is a broadcast target too) would bounce the
            // bytes back to A a second time, and connectorA.write would have
            // been called twice.
            verify(timeout = 2_000, exactly = 1) { connectorB.write(match<ByteArray> { it.contentEquals(bytes) }) }
        }

    /**
     * `userInput = false` is the flag that keeps machine-generated traffic OUT
     * of the relay, and the load-bearing consumer of that guarantee is xterm
     * **mouse reporting**: [fr.techtical.nextsh.desktop.sessions.compose.ComposeTerminalRenderer]
     * sends every [fr.techtical.nextsh.desktop.sessions.compose.MouseReportEncoder]
     * report with `userInput = false` (see the CONTRACT comment at its
     * mouse-reporting call site). Two reasons, both verified here as "B receives
     * nothing":
     *  - a report carries CELL COORDINATES, which are meaningless in a sibling
     *    pane of a different size showing a different program;
     *  - relaying them would hand a compromised remote host an injection
     *    primitive: enable mouse tracking (`CSI ?1003 h`), and every pointer
     *    move it provokes gets mirrored verbatim into the OTHER hosts of the
     *    broadcast group.
     * The same flag also covers the relay's own mirrored writes (anti-loop, see
     * the test above) and the renderer's automatic responses (DA / cursor
     * reports): one rule, three call sites.
     */
    @Test
    fun `a userInput=false send never triggers the relay hook`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildBroadcastManager(backgroundScope)
        val tabId = mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
        advanceUntilIdle()
        val connectorA = mockk<TtyConnector>(relaxed = true)
        val connectorB = mockk<TtyConnector>(relaxed = true)
        val composeA = mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
        mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
        advanceUntilIdle()

        mgr.toggleBroadcastOnActiveTab()
        advanceUntilIdle()
        assertTrue(tabId in mgr.broadcastTabIds.value)

        // Shaped like a real SGR mouse report (ESC [ < 0 ; 12 ; 34 M) rather
        // than an arbitrary byte, so the test reads as the contract it guards.
        val bytes = byteArrayOf(0x1B) + "[<0;12;34M".toByteArray(Charsets.US_ASCII)
        composeA.sendBytes(bytes, userInput = false)
        advanceUntilIdle()

        // Content-based matchers (not raw `bytes` reference equality) since
        // JediTerm's TerminalStarter is free to copy the array internally
        // before it reaches the connector.
        verify(timeout = 2_000, exactly = 1) { connectorA.write(match<ByteArray> { it.contentEquals(bytes) }) }
        verify(exactly = 0) { connectorB.write(match<ByteArray> { it.contentEquals(bytes) }) }
    }

    // ── Late-connecting panes (live topology, no frozen target list) ───────────

    /**
     * Regression: the relay used to capture its target list BY VALUE when
     * Ctrl+Shift+B was pressed. A pane that reached Connected AFTERWARDS
     * (here C, created by splitting an already-broadcasting tab) showed the
     * "Diffusion" badge (SessionTabsScreen derives it from the live tree) and
     * received the snippet fan-out (also live), yet never received a single
     * relayed keystroke. Typing in A must now reach BOTH B and C.
     */
    @Test
    fun `a pane connected after the toggle receives relayed keystrokes`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            val composeA = mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            // ── C joins the tab AFTER broadcast was switched on ──
            assertTrue(mgr.splitPaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM), makeHost("C")))
            advanceUntilIdle()
            val connectorC = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-C", connectorC, palette)
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value, "adding a pane must not drop broadcast")

            val bytes = "z".toByteArray()
            composeA.sendBytes(bytes, userInput = true)
            advanceUntilIdle()

            verify(timeout = 2_000, exactly = 1) { connectorA.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(timeout = 2_000, exactly = 1) { connectorB.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(timeout = 2_000, exactly = 1) { connectorC.write(match<ByteArray> { it.contentEquals(bytes) }) }
        }

    /**
     * The mirror image of the test above: the late pane must also be able to
     * ORIGINATE a relay, not just receive one: its hook is installed at
     * compose-session creation like everyone else's.
     */
    @Test
    fun `a pane connected after the toggle can itself originate a relay`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()

            assertTrue(mgr.splitPaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM), makeHost("C")))
            advanceUntilIdle()
            val connectorC = mockk<TtyConnector>(relaxed = true)
            val composeC = mgr.getOrCreateComposeSession("ssh-C", connectorC, palette)
            advanceUntilIdle()

            val bytes = "w".toByteArray()
            composeC.sendBytes(bytes, userInput = true)
            advanceUntilIdle()

            verify(timeout = 2_000, exactly = 1) { connectorC.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(timeout = 2_000, exactly = 1) { connectorA.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(timeout = 2_000, exactly = 1) { connectorB.write(match<ByteArray> { it.contentEquals(bytes) }) }
        }

    /**
     * The hook is now installed on EVERY compose session, so the "am I
     * broadcasting" test has to be carried by the hook itself: a pane of a
     * non-broadcasting tab must never leak keystrokes to its own siblings, nor
     * to the panes of another tab that IS broadcasting.
     */
    @Test
    fun `typing in a non-broadcasting tab relays nothing, even while another tab broadcasts`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            // Tab 1: A | B, broadcasting.
            val tab1 = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tab1, emptyList(), makeHost("B")))
            advanceUntilIdle()
            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            advanceUntilIdle()
            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tab1 in mgr.broadcastTabIds.value)

            // Tab 2: C | D, NOT broadcasting (openSession makes it active).
            val tab2 = mgr.openSession(makeHost("C"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tab2, emptyList(), makeHost("D")))
            advanceUntilIdle()
            val connectorC = mockk<TtyConnector>(relaxed = true)
            val connectorD = mockk<TtyConnector>(relaxed = true)
            val composeC = mgr.getOrCreateComposeSession("ssh-C", connectorC, palette)
            mgr.getOrCreateComposeSession("ssh-D", connectorD, palette)
            advanceUntilIdle()
            assertFalse(tab2 in mgr.broadcastTabIds.value)

            val bytes = "q".toByteArray()
            composeC.sendBytes(bytes, userInput = true)
            advanceUntilIdle()

            verify(timeout = 2_000, exactly = 1) { connectorC.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(exactly = 0) { connectorD.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(exactly = 0) { connectorA.write(match<ByteArray> { it.contentEquals(bytes) }) }
            verify(exactly = 0) { connectorB.write(match<ByteArray> { it.contentEquals(bytes) }) }
        }

    // ── connectedTerminalPaneCount (quit-confirmation dialog) ─────────────────

    /**
     * The quit dialog counts LIVE SHELLS, not tabs: a split tab running two
     * shells counts 2, and a tab is worth 0 while its terminal is not
     * Connected. Guards the fix for the dialog that used `tabs.value.size`.
     */
    @Test
    fun `connectedTerminalPaneCount counts connected terminal panes across tabs`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            assertEquals(0, mgr.connectedTerminalPaneCount(), "no tab → no session")

            val tab1 = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertEquals(1, mgr.connectedTerminalPaneCount())

            // Split tab1 → 2 shells in ONE tab (tabs.size would still say 1).
            assertTrue(mgr.splitPaneAt(tab1, emptyList(), makeHost("B")))
            advanceUntilIdle()
            assertEquals(2, mgr.connectedTerminalPaneCount())
            assertEquals(1, mgr.tabs.value.size, "still a single tab: that is the whole point")

            // A second tab adds its own shell.
            mgr.openSession(makeHost("C"))
            advanceUntilIdle()
            assertEquals(3, mgr.connectedTerminalPaneCount())

            // Closing the split tab takes both of its shells with it.
            mgr.closeSession(tab1)
            advanceUntilIdle()
            assertEquals(1, mgr.connectedTerminalPaneCount())
        }

    // ── Purge on closeSession / closePaneAt ────────────────────────────────────

    @Test
    fun `closeSession purges broadcast state for the closed tab`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildBroadcastManager(backgroundScope)
        val tabId = mgr.openSession(makeHost("A"))
        advanceUntilIdle()
        assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
        advanceUntilIdle()
        mgr.getOrCreateComposeSession("ssh-A", mockk<TtyConnector>(relaxed = true), palette)
        mgr.getOrCreateComposeSession("ssh-B", mockk<TtyConnector>(relaxed = true), palette)
        advanceUntilIdle()
        mgr.toggleBroadcastOnActiveTab()
        advanceUntilIdle()
        assertTrue(tabId in mgr.broadcastTabIds.value)

        // closeSession launches the pane teardown (disconnect / SFTP close) on
        // the AppScope: drain it before reading the broadcast set.
        mgr.closeSession(tabId)
        advanceUntilIdle()

        assertTrue(mgr.broadcastTabIds.value.isEmpty(), "closing the only broadcasting tab must clear broadcastTabIds")
    }

    @Test
    fun `closePaneAt disables broadcast once the tab drops to a single pane`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            mgr.getOrCreateComposeSession("ssh-A", mockk<TtyConnector>(relaxed = true), palette)
            mgr.getOrCreateComposeSession("ssh-B", mockk<TtyConnector>(relaxed = true), palette)
            advanceUntilIdle()
            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            // Close B: the survivor (A) becomes the tab's whole content, no
            // Split left, so broadcast no longer means anything.
            mgr.closePaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM))
            advanceUntilIdle()

            assertFalse(tabId in mgr.broadcastTabIds.value, "down to 1 pane → broadcast must turn off")
            assertTrue(mgr.tabs.value.first { it.tabId == tabId }.content is TabContent.Terminal)
        }

    @Test
    fun `closePaneAt keeps broadcast on when at least 2 connected panes survive`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildBroadcastManager(backgroundScope)
            val tabId = mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, emptyList(), makeHost("B")))
            advanceUntilIdle()
            assertTrue(mgr.splitPaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM), makeHost("C")))
            advanceUntilIdle()

            val connectorA = mockk<TtyConnector>(relaxed = true)
            val connectorB = mockk<TtyConnector>(relaxed = true)
            val connectorC = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession("ssh-A", connectorA, palette)
            mgr.getOrCreateComposeSession("ssh-B", connectorB, palette)
            mgr.getOrCreateComposeSession("ssh-C", connectorC, palette)
            advanceUntilIdle()

            mgr.toggleBroadcastOnActiveTab()
            advanceUntilIdle()
            assertTrue(tabId in mgr.broadcastTabIds.value)

            // Close C (nested RIGHT_OR_BOTTOM.RIGHT_OR_BOTTOM): A and B survive.
            mgr.closePaneAt(tabId, listOf(PaneSlot.RIGHT_OR_BOTTOM, PaneSlot.RIGHT_OR_BOTTOM))
            advanceUntilIdle()

            assertTrue(tabId in mgr.broadcastTabIds.value, "2 connected panes still survive → broadcast stays on")
            assertTrue(mgr.sendToActiveTerminal("pwd"))
            advanceUntilIdle()
            verify(timeout = 2_000, exactly = 1) { connectorA.write("pwd") }
            verify(timeout = 2_000, exactly = 1) { connectorB.write("pwd") }
        }
}
