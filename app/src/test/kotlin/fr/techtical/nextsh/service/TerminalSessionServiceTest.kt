// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.ui.sessions.PaneContent
import fr.techtical.nextsh.ui.sessions.SessionTab
import fr.techtical.nextsh.ui.sessions.SplitState
import fr.techtical.nextsh.ui.sessions.TerminalTabsState
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the terminal sessions service decides, off the framework: when it ends, and what its
 * notification says. It keeps the process alive while at least one tab is connected; the
 * disconnected tabs stay in the store but do not hold the service.
 */
class TerminalSessionServiceTest {

    private fun tab(label: String, status: SessionStatus = SessionStatus.CONNECTED) = SessionTab(
        sessionId = "session-$label",
        host = Host(
            id = "host-$label",
            label = label,
            hostname = "$label.example.com",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-$label",
        ),
        terminalSession = mockk<SshTerminalSession>(),
        status = status,
    )

    private fun tabs(vararg labels: String) = labels.map { tab(it) }

    // ── When the service ends ───────────────────────────────────────────────

    @Test
    fun `a connected tab keeps the service up`() {
        assertFalse(shouldStopTerminalService(tabs("a"), hadConnectedTab = true))
        assertFalse(shouldStopTerminalService(tabs("a", "b"), hadConnectedTab = true))
    }

    @Test
    fun `the service ends once the last tab is gone`() {
        assertTrue(shouldStopTerminalService(emptyList(), hadConnectedTab = true))
    }

    @Test
    fun `the service ends when the tabs that remain are all disconnected`() {
        val tabs = listOf(tab("a", SessionStatus.DISCONNECTED), tab("b", SessionStatus.DISCONNECTED))

        assertTrue(shouldStopTerminalService(tabs, hadConnectedTab = true))
    }

    @Test
    fun `a tab in error or still connecting does not hold the service`() {
        for (status in SessionStatus.entries.filter { it != SessionStatus.CONNECTED }) {
            assertTrue(
                shouldStopTerminalService(listOf(tab("a", status)), hadConnectedTab = true),
                "a tab in $status is not a live session",
            )
        }
    }

    @Test
    fun `one connected tab among disconnected ones keeps the service up`() {
        val tabs = listOf(tab("a", SessionStatus.DISCONNECTED), tab("b"), tab("c", SessionStatus.DISCONNECTED))

        assertFalse(shouldStopTerminalService(tabs, hadConnectedTab = true))
    }

    @Test
    fun `a service that has not seen a connected tab yet does not end on the empty list`() {
        assertFalse(shouldStopTerminalService(emptyList(), hadConnectedTab = false))
        assertFalse(shouldStopTerminalService(listOf(tab("a", SessionStatus.DISCONNECTED)), hadConnectedTab = false))
    }

    // ── What the notification says ──────────────────────────────────────────

    @Test
    fun `the notification counts the connected tabs and lists their hosts`() {
        val content = terminalNotificationContent(tabs("alpha", "beta"))

        assertEquals(2, content.sessionCount)
        assertEquals(listOf("alpha", "beta"), content.lines)
        assertEquals(0, content.moreCount)
    }

    @Test
    fun `a disconnected tab is neither counted nor listed`() {
        val content = terminalNotificationContent(
            listOf(tab("alpha"), tab("beta", SessionStatus.DISCONNECTED), tab("gamma")),
        )

        assertEquals(2, content.sessionCount)
        assertEquals(listOf("alpha", "gamma"), content.lines)
    }

    @Test
    fun `five hosts fit, a sixth goes to the summary`() {
        val five = terminalNotificationContent(tabs("a", "b", "c", "d", "e"))
        assertEquals(5, five.sessionCount)
        assertEquals(5, five.lines.size)
        assertEquals(0, five.moreCount)

        val six = terminalNotificationContent(tabs("a", "b", "c", "d", "e", "f"))
        assertEquals(6, six.sessionCount)
        assertEquals(listOf("a", "b", "c", "d", "e"), six.lines)
        assertEquals(1, six.moreCount)
    }

    @Test
    fun `the summary counts every host past the lines`() {
        val content = terminalNotificationContent(tabs("a", "b", "c", "d", "e", "f", "g", "h"))

        assertEquals(8, content.sessionCount)
        assertEquals(5, content.lines.size)
        assertEquals(3, content.moreCount)
    }

    @Test
    fun `two tabs on the same host are two lines`() {
        val content = terminalNotificationContent(tabs("alpha", "alpha"))

        assertEquals(2, content.sessionCount)
        assertEquals(listOf("alpha", "alpha"), content.lines)
    }

    @Test
    fun `the number of lines can be changed`() {
        val content = terminalNotificationContent(tabs("a", "b", "c"), maxLines = 2)

        assertEquals(listOf("a", "b"), content.lines)
        assertEquals(1, content.moreCount)
    }

    @Test
    fun `no connected tab gives an empty notification`() {
        val content = terminalNotificationContent(listOf(tab("a", SessionStatus.DISCONNECTED)))

        assertEquals(TerminalNotificationContent(0, emptyList(), 0), content)
    }

    @Test
    fun `the content is equal for equal tabs, so an unchanged notification is not posted again`() {
        val a = tabs("alpha", "beta")

        assertEquals(terminalNotificationContent(a), terminalNotificationContent(a.toList()))
    }

    // ── What the service reacts to ──────────────────────────────────────────

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the service reacts to a change of the tabs, not to the move of the split divider`() = runTest {
        val list = tabs("a", "b")
        val state = MutableStateFlow(
            TerminalTabsState(
                tabs = list,
                splitState = SplitState(
                    leftOrTopPane = PaneContent.Terminal(0),
                    rightOrBottomPane = PaneContent.Terminal(1),
                ),
            ),
        )
        val seen = mutableListOf<List<SessionTab>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.tabChanges().collect { seen += it } }
        assertEquals(1, seen.size)

        // Dragging the divider republishes the state at every frame: nothing for the service to do.
        repeat(20) { step ->
            state.value = state.value.copy(splitState = state.value.splitState!!.copy(splitRatio = 0.3f + step * 0.01f))
        }
        state.value = state.value.copy(activeTabIndex = 1)
        state.value = state.value.copy(splitState = null)
        assertEquals(1, seen.size)

        // A tab that falls is something to react to, and so is a tab that comes.
        state.value = state.value.copy(tabs = listOf(list[0].copy(status = SessionStatus.DISCONNECTED), list[1]))
        assertEquals(2, seen.size)
        state.value = state.value.copy(tabs = state.value.tabs + tab("c"))
        assertEquals(3, seen.size)
        assertEquals(3, seen.last().size)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `the service reacts to the edit of the host of a tab`() = runTest {
        val list = tabs("a")
        val state = MutableStateFlow(TerminalTabsState(tabs = list))
        val seen = mutableListOf<List<SessionTab>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { state.tabChanges().collect { seen += it } }

        // The notification lists the labels: a renamed host is a change of the tabs.
        state.value = state.value.copy(tabs = listOf(list[0].copy(host = list[0].host.copy(label = "renamed"))))

        assertEquals(2, seen.size)
        assertEquals(listOf("renamed"), terminalNotificationContent(seen.last()).lines)
    }
}
