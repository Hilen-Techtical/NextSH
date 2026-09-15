// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.components

import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.desktop.sessions.SftpTabState
import fr.techtical.nextsh.desktop.sessions.TabContent
import fr.techtical.nextsh.desktop.sessions.TerminalTabStatus
import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.desktop.theme.TerminalThemeId
import fr.techtical.nextsh.desktop.theme.paletteFor
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure per-host live-session-status derivation backing
 * the sidebar host tree's status dot (C2): [sessionStatusPriority],
 * [reduceSessionStatuses], and [hostSessionStatusOf]: all `internal`,
 * reachable from this desktopTest source set without any production-code
 * changes (same pattern as [CmdKPositionTest]).
 *
 * No Compose harness exists for this file, so [HostTreeItem] itself is not
 * exercised here: only the pure calculation [DesktopSidebar] feeds it.
 *
 * Sessions are built directly on [TabContent.Terminal]/[TabContent.Sftp]
 * variants that need no mock collaborator: [TerminalTabStatus.Connecting],
 * [TerminalTabStatus.Error] and [TerminalTabStatus.Disconnected] are plain
 * data classes/objects, and a CONNECTED session is represented via
 * [TabContent.Sftp] + [SftpTabState.Open] rather than
 * [TerminalTabStatus.Connected] (which requires a live
 * `DesktopSshTerminalSession`), both map to [SessionStatus.CONNECTED]
 * through [SessionTab.uiStatus], and [hostSessionStatusOf] only cares about
 * that resolved status.
 */
class SidebarHostStatusTest {

    private val theme = ResolvedTheme(name = "test", palette = paletteFor(TerminalThemeId.TECHTICAL_DARK))

    private fun host(id: String) = Host(
        id = id,
        label = id,
        hostname = "example.com",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private fun tab(tabId: String, hostId: String, content: TabContent) =
        SessionTab(tabId = tabId, host = host(hostId), content = content)

    private fun connecting(tabId: String, hostId: String) =
        tab(tabId, hostId, TabContent.Terminal(theme, TerminalTabStatus.Connecting))

    private fun connected(tabId: String, hostId: String) =
        tab(tabId, hostId, TabContent.Sftp(linkedSshSessionId = "ssh-$tabId", state = SftpTabState.Open(cwd = "/home")))

    private fun error(tabId: String, hostId: String) =
        tab(tabId, hostId, TabContent.Terminal(theme, TerminalTabStatus.Error("boom")))

    private fun disconnected(tabId: String, hostId: String) =
        tab(tabId, hostId, TabContent.Terminal(theme, TerminalTabStatus.Disconnected("gone")))

    // ── sessionStatusPriority ────────────────────────────────────────────────

    @Test
    fun `CONNECTED outranks CONNECTING and RECONNECTING`() {
        assertTrue(sessionStatusPriority(SessionStatus.CONNECTED) < sessionStatusPriority(SessionStatus.CONNECTING))
        assertTrue(sessionStatusPriority(SessionStatus.CONNECTED) < sessionStatusPriority(SessionStatus.RECONNECTING))
    }

    @Test
    fun `CONNECTING and RECONNECTING share the same priority`() {
        assertEquals(sessionStatusPriority(SessionStatus.CONNECTING), sessionStatusPriority(SessionStatus.RECONNECTING))
    }

    @Test
    fun `CONNECTING outranks ERROR`() {
        assertTrue(sessionStatusPriority(SessionStatus.CONNECTING) < sessionStatusPriority(SessionStatus.ERROR))
    }

    // ── reduceSessionStatuses ────────────────────────────────────────────────

    @Test
    fun `reducing a single status returns it unchanged`() {
        assertEquals(SessionStatus.ERROR, reduceSessionStatuses(listOf(SessionStatus.ERROR)))
    }

    @Test
    fun `reduce picks CONNECTED over ERROR regardless of list order`() {
        assertEquals(SessionStatus.CONNECTED, reduceSessionStatuses(listOf(SessionStatus.ERROR, SessionStatus.CONNECTED)))
        assertEquals(SessionStatus.CONNECTED, reduceSessionStatuses(listOf(SessionStatus.CONNECTED, SessionStatus.ERROR)))
    }

    @Test
    fun `reduce picks CONNECTING over ERROR when no CONNECTED session exists`() {
        assertEquals(SessionStatus.CONNECTING, reduceSessionStatuses(listOf(SessionStatus.ERROR, SessionStatus.CONNECTING)))
    }

    // ── hostSessionStatusOf ────────────────────────────────────────────────

    @Test
    fun `an empty session list yields an empty map`() {
        assertTrue(hostSessionStatusOf(emptyList()).isEmpty())
    }

    @Test
    fun `a host with no session at all is absent from the map`() {
        val result = hostSessionStatusOf(listOf(connected("t1", "host-a")))

        assertNull(result["host-b"])
    }

    @Test
    fun `DISCONNECTED-only sessions are filtered out entirely`() {
        val result = hostSessionStatusOf(listOf(disconnected("t1", "host-a")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a lone CONNECTING session surfaces CONNECTING for its host`() {
        val result = hostSessionStatusOf(listOf(connecting("t1", "host-a")))

        assertEquals(SessionStatus.CONNECTING, result["host-a"])
    }

    @Test
    fun `multiple sessions on the same host reduce by priority - CONNECTED wins over ERROR`() {
        val result = hostSessionStatusOf(
            listOf(
                error("t1", "host-a"),
                connected("t2", "host-a"),
            ),
        )

        assertEquals(SessionStatus.CONNECTED, result["host-a"])
    }

    @Test
    fun `a DISCONNECTED tab does not mask a live session on the same host`() {
        val result = hostSessionStatusOf(
            listOf(
                disconnected("t1", "host-a"),
                error("t2", "host-a"),
            ),
        )

        assertEquals(SessionStatus.ERROR, result["host-a"])
    }

    @Test
    fun `different hosts are tracked independently`() {
        val result = hostSessionStatusOf(
            listOf(
                connected("t1", "host-a"),
                error("t2", "host-b"),
            ),
        )

        assertEquals(SessionStatus.CONNECTED, result["host-a"])
        assertEquals(SessionStatus.ERROR, result["host-b"])
        assertEquals(2, result.size)
    }
}
