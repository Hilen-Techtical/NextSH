// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

// ──────────────────────────────────────────────────────────────────────────────
// Unit tests for the picker-close terminal-focus restore plumbing:
// - [DesktopSessionManager.terminalFocusEpoch] / [requestTerminalFocus]: the
//   monotonic epoch bumped by Main.kt when a modal picker (snippet / host)
//   closes, observed by TerminalScreen's renderer branches to re-grab focus.
// - [focusedConnectedTerminalId]: the pure resolver that decides WHICH pane
//   may re-grab focus (the active tab's focused Connected Terminal, walking
//   nested Split focusedSlots).
// - [DesktopSessionManager.activeFocusedTerminalSshSessionId] and the
//   [sendToActiveTerminal] refactor that now shares it (regression: both must
//   stay null/false in every non-Connected state).
//
// Construction strategy mirrors [DesktopSessionManagerTabCycleTest]: relaxed
// MockK collaborators, `sessions` stubbed to an empty StateFlow for the init
// collector, `vaultManager.getPassword` → null so opened tabs stay in
// [TerminalTabStatus.AwaitingPassword]. Split/SFTP shapes are exercised via
// the pure [focusedConnectedTerminalId] with hand-built [TabContent] trees.
// Connected IS reachable through the manager without a live SSH connect:
// stub getPassword → chars + connectWithPassword → Success + getTerminalSession
// → mock, which the happy-path routing test below uses, populating the
// compose-session cache headlessly (jediterm-core only, no Swing).
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopSessionManagerFocusTest {

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

    // ── terminalFocusEpoch ────────────────────────────────────────────────────

    @Test
    fun `terminalFocusEpoch starts at zero`() = runTest(UnconfinedTestDispatcher()) {
        val mgr = buildManager(backgroundScope)
        assertEquals(0L, mgr.terminalFocusEpoch.value, "epoch must start at 0 (renderers skip 0)")
    }

    @Test
    fun `requestTerminalFocus increments the epoch monotonically`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope)
            mgr.requestTerminalFocus()
            assertEquals(1L, mgr.terminalFocusEpoch.value)
            mgr.requestTerminalFocus()
            assertEquals(2L, mgr.terminalFocusEpoch.value)
            mgr.requestTerminalFocus()
            assertEquals(3L, mgr.terminalFocusEpoch.value)
        }

    // ── focusedConnectedTerminalId (pure resolver) ────────────────────────────

    @Test
    fun `connected terminal resolves to its sshSessionId`() {
        assertEquals("ssh-1", focusedConnectedTerminalId(connectedTerminal("ssh-1")))
    }

    @Test
    fun `non-connected terminal states resolve to null`() {
        val host = makeHost("A")
        assertNull(focusedConnectedTerminalId(
            TabContent.Terminal(theme, TerminalTabStatus.Connecting)))
        assertNull(focusedConnectedTerminalId(
            TabContent.Terminal(theme, TerminalTabStatus.AwaitingPassword(host))))
        assertNull(focusedConnectedTerminalId(
            TabContent.Terminal(theme, TerminalTabStatus.Disconnected("gone"))))
        assertNull(focusedConnectedTerminalId(
            TabContent.Terminal(theme, TerminalTabStatus.Error("boom"))))
    }

    @Test
    fun `sftp pane resolves to null`() {
        assertNull(focusedConnectedTerminalId(sftpPane()))
    }

    @Test
    fun `split resolves the focused slot only`() {
        val split = TabContent.Split(
            first       = connectedTerminal("ssh-left"),
            second      = connectedTerminal("ssh-right"),
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals("ssh-right", focusedConnectedTerminalId(split))
        assertEquals(
            "ssh-left",
            focusedConnectedTerminalId(split.copy(focusedSlot = PaneSlot.LEFT_OR_TOP)),
        )
    }

    @Test
    fun `split focused on sftp resolves to null even with a connected sibling`() {
        val split = TabContent.Split(
            first       = connectedTerminal("ssh-left"),
            second      = sftpPane(),
            orientation = SplitOrientation.VERTICAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertNull(focusedConnectedTerminalId(split))
    }

    @Test
    fun `nested split resolves the deepest focused pane`() {
        val inner = TabContent.Split(
            first       = connectedTerminal("ssh-inner-left"),
            second      = connectedTerminal("ssh-inner-right"),
            orientation = SplitOrientation.VERTICAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        val outer = TabContent.Split(
            first       = connectedTerminal("ssh-outer-left"),
            second      = inner,
            orientation = SplitOrientation.HORIZONTAL,
            focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
        )
        assertEquals("ssh-inner-right", focusedConnectedTerminalId(outer))
    }

    // ── activeFocusedTerminalSshSessionId + sendToActiveTerminal regression ──

    @Test
    fun `no open tab resolves to null and send fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope)
            assertNull(mgr.activeFocusedTerminalSshSessionId())
            assertFalse(mgr.sendToActiveTerminal("ls\n"))
        }

    @Test
    fun `awaiting-password tab resolves to null and send fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val mgr = buildManager(backgroundScope)
            mgr.openSession(makeHost("A"))
            advanceUntilIdle()
            // Vault returned null → the pane sits in AwaitingPassword, which
            // must never be treated as a focus/send target.
            assertNull(mgr.activeFocusedTerminalSshSessionId())
            assertFalse(mgr.sendToActiveTerminal("ls\n"))
        }

    @Test
    fun `connected tab resolves its id and sendToActiveTerminal routes the text verbatim`() =
        runTest(UnconfinedTestDispatcher()) {
            // Connected is reachable without live SSH: vault yields a
            // password, connectWithPassword succeeds, and getTerminalSession
            // returns a mock: openSession's coroutine then flips the tab to
            // Connected("ssh-1", …).
            val host = makeHost("A")
            val sshSessionManager = mockk<DesktopSshSessionManager>(relaxed = true)
            every { sshSessionManager.sessions } returns MutableStateFlow(emptyList<SshSession>())
            coEvery { sshSessionManager.connectWithPassword(any(), any()) } returns
                SshResult.Success(SshSession(id = "ssh-1", host = host, status = SessionStatus.CONNECTED))
            every { sshSessionManager.getTerminalSession("ssh-1") } returns
                mockk<DesktopSshTerminalSession>(relaxed = true)

            val vaultManager = mockk<VaultManager>(relaxed = true)
            coEvery { vaultManager.getPassword(any()) } returns "pw".toCharArray()

            val mgr = DesktopSessionManager(
                hostRepository    = mockk(relaxed = true),
                vaultManager      = vaultManager,
                sshSessionManager = sshSessionManager,
                sftpManager       = mockk(relaxed = true),
                knownHostsVerifier = mockk(relaxed = true),
                appScope          = TestAppScope(backgroundScope),
            )

            mgr.openSession(host)
            advanceUntilIdle()
            assertEquals("ssh-1", mgr.activeFocusedTerminalSshSessionId())

            // Populate the compose-session cache headlessly (jediterm-core
            // only, no Swing). The relaxed connector's read() returns 0 → the
            // parse loop hits EOF and its daemon thread exits; sendString
            // forwards user text through TtyConnector.write(String).
            val connector = mockk<TtyConnector>(relaxed = true)
            mgr.getOrCreateComposeSession(
                "ssh-1",
                connector,
                TerminalThemePalette(
                    background  = 0x000000,
                    foreground  = 0xFFFFFF,
                    cursor      = 0xFFFFFF,
                    selectionBg = 0x333333,
                    ansi        = IntArray(16),
                ),
            )

            assertTrue(mgr.sendToActiveTerminal("ls -la"))
            // Exactly the picked text, no trailing newline appended (the
            // user reviews the command at the prompt, then presses Enter).
            verify(timeout = 2000) { connector.write("ls -la") }
        }
}
