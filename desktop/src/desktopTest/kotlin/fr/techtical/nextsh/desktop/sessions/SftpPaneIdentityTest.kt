// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions

import fr.techtical.nextsh.desktop.theme.ResolvedTheme
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The identity of an SFTP pane ([TabContent.Sftp.paneId]) and the walk that lists the panes
 * of a tab ([sftpPaneIdsIn]). The pending "replace this file?" questions are keyed on that
 * id: it must tell two panes apart even when they have the same slot, and it must follow a
 * pane when the tree around it changes.
 */
class SftpPaneIdentityTest {

    private val theme = ResolvedTheme(name = "test", palette = mockk(relaxed = true))

    private val host = Host(
        id = "h",
        label = "h",
        hostname = "example.com",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-h",
    )

    private fun terminal() = TabContent.Terminal(theme = theme, status = TerminalTabStatus.Connecting, host = host)

    private fun sftp(linked: String = "ssh-1") =
        TabContent.Sftp(linkedSshSessionId = linked, state = SftpTabState.Opening, host = host)

    private fun split(first: TabContent, second: TabContent) = TabContent.Split(
        first = first,
        second = second,
        orientation = SplitOrientation.HORIZONTAL,
        focusedSlot = PaneSlot.RIGHT_OR_BOTTOM,
    )

    // ── paneId ────────────────────────────────────────────────────────────────

    @Test
    fun `every SFTP pane gets its own id`() {
        val ids = List(50) { sftp().paneId }

        assertEquals(50, ids.toSet().size)
        assertTrue(ids.none { it.isBlank() })
    }

    @Test
    fun `the id survives the copies that update the tree`() {
        val pane = sftp()

        val opened = pane.copy(state = SftpTabState.Open("/home/t"))
        val failed = opened.copy(state = SftpTabState.Error("lost"))

        assertEquals(pane.paneId, opened.paneId)
        assertEquals(pane.paneId, failed.paneId)
    }

    @Test
    fun `two panes on the same session are two panes`() {
        assertNotEquals(sftp("ssh-1").paneId, sftp("ssh-1").paneId)
    }

    // ── sftpPaneIdsIn ─────────────────────────────────────────────────────────

    @Test
    fun `a terminal alone has no SFTP pane`() {
        assertEquals(emptySet(), sftpPaneIdsIn(terminal()))
    }

    @Test
    fun `an SFTP tab is one pane`() {
        val pane = sftp()

        assertEquals(setOf(pane.paneId), sftpPaneIdsIn(pane))
    }

    @Test
    fun `a terminal and its SFTP pane list the SFTP pane only`() {
        val pane = sftp()

        assertEquals(setOf(pane.paneId), sftpPaneIdsIn(split(terminal(), pane)))
    }

    @Test
    fun `two servers side by side list both SFTP panes, though both sit in the second slot`() {
        // Split(Split(T_X, SFTP_X), Split(T_Y, SFTP_Y)): the SFTP panes are at
        // [LEFT, RIGHT] and [RIGHT, RIGHT], and their last slot is the same.
        val sftpX = sftp("ssh-x")
        val sftpY = sftp("ssh-y")
        val tree = split(split(terminal(), sftpX), split(terminal(), sftpY))

        assertEquals(setOf(sftpX.paneId, sftpY.paneId), sftpPaneIdsIn(tree))
        assertNotEquals(sftpX.paneId, sftpY.paneId)
    }

    @Test
    fun `a pane promoted after its sibling was closed is still listed under the same id`() {
        val sftpX = sftp("ssh-x")
        val sftpY = sftp("ssh-y")
        val before = split(split(terminal(), sftpX), split(terminal(), sftpY))

        // The first terminal pane and its SFTP are closed: the second pair takes the place of the tree.
        val after = before.second

        assertEquals(setOf(sftpX.paneId, sftpY.paneId), sftpPaneIdsIn(before))
        assertEquals(setOf(sftpY.paneId), sftpPaneIdsIn(after))
    }

    @Test
    fun `a closed SFTP pane is no longer listed`() {
        val pane = sftp()
        val terminal = terminal()

        assertEquals(setOf(pane.paneId), sftpPaneIdsIn(split(terminal, pane)))
        assertEquals(emptySet(), sftpPaneIdsIn(terminal))
    }
}
