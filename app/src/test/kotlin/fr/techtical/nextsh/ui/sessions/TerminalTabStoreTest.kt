// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.repository.HostRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The tabs belong to [TerminalTabStore], an app singleton, and not to a screen: a tab closes on an
 * explicit action only ([TerminalTabStore.closeTab], [TerminalTabStore.closeAll]), and each tab
 * gives back the one reference it holds on its SSH session, once.
 *
 * Both scopes of the store run on an unconfined dispatcher here, so the shell is closed and the
 * reference given back by the time the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalTabStoreTest {

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)

    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var clipboard: ClipboardManager
    private lateinit var hostRepository: HostRepository
    private val hostsInRepository = MutableStateFlow<List<Host>>(emptyList())
    private lateinit var store: TerminalTabStore
    private var clipboardClearTimeout = 60

    /** The terminal session of each tab built by [tab], by session id. */
    private val terminalSessions = mutableMapOf<String, SshTerminalSession>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sshSessionManager = mockk()
        every { sshSessionManager.release(any()) } just Runs
        clipboard = mockk(relaxed = true)
        clipboardClearTimeout = 60

        val context = mockk<Context>()
        every { context.getSystemService(Context.CLIPBOARD_SERVICE) } returns clipboard
        val settings = mockk<SettingsDataStore>()
        every { settings.settingsFlow } answers {
            flowOf(SettingsDataStore.Settings(clipboardClearTimeout = clipboardClearTimeout))
        }

        hostsInRepository.value = emptyList()
        hostRepository = mockk()
        every { hostRepository.observeAll() } returns hostsInRepository

        store = TerminalTabStore(context, sshSessionManager, settings, hostRepository).also {
            it.hostScope = CoroutineScope(SupervisorJob() + dispatcher)
            it.cleanupScope = CoroutineScope(SupervisorJob() + dispatcher)
            it.clipboardScope = CoroutineScope(SupervisorJob() + dispatcher)
        }
        terminalSessions.clear()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(ClipData::class)
        Dispatchers.resetMain()
    }

    private fun host(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "host-$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    /** A connected tab on [sessionId] for host [hostId]. */
    private fun tab(
        sessionId: String,
        hostId: String = "h-$sessionId",
        status: SessionStatus = SessionStatus.CONNECTED,
    ): SessionTab {
        val terminalSession = mockk<SshTerminalSession>(relaxed = true)
        terminalSessions[sessionId] = terminalSession
        return SessionTab(sessionId, host(hostId), terminalSession, status)
    }

    private fun addTabs(vararg sessionIds: String) = sessionIds.forEach { store.addTab(tab(it)) }

    private val state get() = store.state.value

    private fun sessionIds() = state.tabs.map { it.sessionId }

    // ── Adding ──────────────────────────────────────────────────────────────

    @Test
    fun `a new store has no tab`() {
        assertTrue(state.tabs.isEmpty())
        assertEquals(0, state.activeTabIndex)
        assertNull(state.splitState)
    }

    @Test
    fun `an added tab becomes the active one`() {
        addTabs("s1")
        assertEquals(listOf("s1"), sessionIds())
        assertEquals(0, state.activeTabIndex)

        addTabs("s2")
        assertEquals(listOf("s1", "s2"), sessionIds())
        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `adding a tab neither closes nor releases anything`() {
        addTabs("s1", "s2")

        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { terminalSessions.getValue("s1").disconnect() }
    }

    @Test
    fun `hasSession tells whether a tab shows the session`() {
        addTabs("s1")

        assertTrue(store.hasSession("s1"))
        assertFalse(store.hasSession("s2"))
    }

    @Test
    fun `adding a tab leaves the split, so the new active tab is on screen and not in no pane`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplit(1)
        assertTrue(state.splitState != null)

        addTabs("s3")

        // The split outlives the screen: the tab added while it is up would be in neither pane.
        assertNull(state.splitState)
        assertEquals(listOf("s1", "s2", "s3"), sessionIds())
        assertEquals(2, state.activeTabIndex)
    }

    @Test
    fun `adding a tab without a split changes nothing else`() {
        addTabs("s1")
        store.enterSplitWithSftp("s1", "Host h-s1")
        store.exitSplit()

        addTabs("s2")

        assertNull(state.splitState)
        assertEquals(1, state.activeTabIndex)
    }

    // ── Selecting ───────────────────────────────────────────────────────────

    @Test
    fun `selectTab changes the active tab and ignores an index out of range`() {
        addTabs("s1", "s2", "s3")

        store.selectTab(0)
        assertEquals(0, state.activeTabIndex)

        store.selectTab(7)
        store.selectTab(-1)
        assertEquals(0, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost without a tab of the host changes nothing`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        val before = state

        assertFalse(store.selectTabOfHost("unknown"))

        assertSame(before, state)
    }

    @Test
    fun `selectTabOfHost selects the tab of the host`() {
        addTabs("s1", "s2", "s3")

        assertTrue(store.selectTabOfHost("h-s2"))

        assertEquals(1, state.activeTabIndex)
        // Selecting closes nothing and releases nothing.
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `selectTabOfHost keeps the active tab when it is of the host`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1"))
        store.selectTab(2)

        assertTrue(store.selectTabOfHost("h1"))

        assertEquals(2, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost takes the first tab of a host that has several`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1"))
        store.selectTab(1)

        assertTrue(store.selectTabOfHost("h1"))

        assertEquals(0, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost prefers a connected tab of the host to a disconnected one before it`() {
        // The dot of the list says "connected": the tap must not land on the dead tab.
        store.addTab(tab("s1", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1"))
        store.selectTab(1)

        assertTrue(store.selectTabOfHost("h1"))

        assertEquals(2, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost takes the first connected tab when several are and none is active`() {
        store.addTab(tab("s1", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.addTab(tab("s2", hostId = "h1"))
        store.addTab(tab("s3", hostId = "h1"))
        store.addTab(tab("s4", hostId = "h2"))

        assertTrue(store.selectTabOfHost("h1"))

        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost leaves a disconnected active tab for a connected one of the same host`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.addTab(tab("s2", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.selectTab(1)

        assertTrue(store.selectTabOfHost("h1"))

        assertEquals(0, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost falls back to the first tab when none of the host is connected`() {
        store.addTab(tab("s1", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.selectTab(1)

        assertTrue(store.selectTabOfHost("h1"))

        // A disconnected tab opens on "Reconnecter": better than refusing.
        assertEquals(0, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost leaves a split whose pane shows only a disconnected tab of the host`() {
        store.addTab(tab("s1", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1"))
        store.selectTab(0)
        store.enterSplit(1)

        assertTrue(store.selectTabOfHost("h1"))

        assertNull(state.splitState)
        assertEquals(2, state.activeTabIndex)
    }

    @Test
    fun `selectTabOfHost gives the focus to the pane that already shows the host`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(2)

        assertTrue(store.selectTabOfHost("h-s3"))

        val split = state.splitState!!
        assertEquals(PaneSlot.RIGHT_OR_BOTTOM, split.focusedPane)
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(2), split.rightOrBottomPane)
    }

    @Test
    fun `selectTabOfHost leaves a split that does not show the host`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(1)

        assertTrue(store.selectTabOfHost("h-s3"))

        // The tab asked for would have been hidden behind two other hosts.
        assertNull(state.splitState)
        assertEquals(2, state.activeTabIndex)
    }

    // ── Closing one tab ─────────────────────────────────────────────────────

    @Test
    fun `closeTab closes the shell, gives the reference back once and removes the tab`() {
        addTabs("s1", "s2")

        store.closeTab(0)

        assertEquals(listOf("s2"), sessionIds())
        verify(exactly = 1) { terminalSessions.getValue("s1").disconnect() }
        verify(exactly = 1) { sshSessionManager.release("s1") }
        // Only the reference of the tab goes back: the session stays open for whoever else holds it.
        verify(exactly = 0) { sshSessionManager.release("s2") }
        verify(exactly = 0) { terminalSessions.getValue("s2").disconnect() }
    }

    @Test
    fun `closeTab gives the reference back even when the shell does not close`() {
        addTabs("s1")
        every { terminalSessions.getValue("s1").disconnect() } throws IllegalStateException("shell already gone")

        store.closeTab(0)

        verify(exactly = 1) { sshSessionManager.release("s1") }
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun `closeTab on an index with no tab does nothing`() {
        addTabs("s1")

        store.closeTab(3)
        store.closeTab(-1)

        assertEquals(listOf("s1"), sessionIds())
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `closing the last tab leaves an empty store`() {
        addTabs("s1")

        store.closeTab(0)

        assertTrue(state.tabs.isEmpty())
        assertEquals(0, state.activeTabIndex)
    }

    @Test
    fun `closing the active last tab moves the active index to the new last one`() {
        addTabs("s1", "s2", "s3")
        assertEquals(2, state.activeTabIndex)

        store.closeTab(2)

        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `closing a tab before the active one keeps the displayed tab`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(1)

        store.closeTab(0)

        // B stays on screen: it only slid one rank down.
        assertEquals(listOf("s2", "s3"), sessionIds())
        assertEquals(0, state.activeTabIndex)
        assertEquals("s2", state.tabs[state.activeTabIndex].sessionId)
    }

    @Test
    fun `closing a tab before the last active one keeps the displayed tab`() {
        addTabs("s1", "s2", "s3")
        assertEquals(2, state.activeTabIndex)

        store.closeTab(0)

        assertEquals("s3", state.tabs[state.activeTabIndex].sessionId)
        store.closeTab(0)
        assertEquals("s3", state.tabs[state.activeTabIndex].sessionId)
    }

    @Test
    fun `closing a tab after the active one leaves the displayed tab`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)

        store.closeTab(2)

        assertEquals(0, state.activeTabIndex)
        assertEquals("s1", state.tabs[state.activeTabIndex].sessionId)
    }

    @Test
    fun `closing the active tab in the middle shows the tab that takes its place`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(1)

        store.closeTab(1)

        assertEquals(listOf("s1", "s3"), sessionIds())
        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `closing a tab before the active one with a split keeps both the displayed tab and the panes`() {
        addTabs("s1", "s2", "s3", "s4")
        store.selectTab(2)
        store.enterSplit(3)

        store.closeTab(0)

        assertEquals("s3", state.tabs[state.activeTabIndex].sessionId)
        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(1), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(2), split.rightOrBottomPane)
    }

    @Test
    fun `a shell that ends after its tab was closed does not bring the tab back`() {
        addTabs("s1")
        val client = store.buildSessionClient("s1")
        store.closeTab(0)

        // Closing the shell makes Termux report the end of the session.
        client.onSessionFinished(mockk<TerminalSession>(relaxed = true))

        assertTrue(state.tabs.isEmpty())
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    // ── Replacing one tab (the reconnection) ────────────────────────────────

    @Test
    fun `replaceTab puts the new tab in the place of the old one`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(1)
        val replacement = tab("s4", hostId = "h-s2")

        assertTrue(store.replaceTab("s2", replacement))

        assertEquals(listOf("s1", "s4", "s3"), sessionIds())
        assertSame(replacement, state.tabs[1])
        // The user is where they were: the active tab keeps its rank.
        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `replaceTab closes the old shell and gives its reference back once, and only that`() {
        addTabs("s1", "s2", "s3")

        store.replaceTab("s2", tab("s4"))

        verify(exactly = 1) { terminalSessions.getValue("s2").disconnect() }
        verify(exactly = 1) { sshSessionManager.release("s2") }
        // The new tab holds the reference of the caller, and the others keep theirs.
        listOf("s1", "s3", "s4").forEach { id ->
            verify(exactly = 0) { terminalSessions.getValue(id).disconnect() }
            verify(exactly = 0) { sshSessionManager.release(id) }
        }
    }

    @Test
    fun `discard closes a tab the store never took, shell first, then its reference once`() {
        addTabs("s1")
        val unplaced = tab("s2")

        store.discard(unplaced)

        verifyOrder {
            terminalSessions.getValue("s2").disconnect()
            sshSessionManager.release("s2")
        }
        verify(exactly = 1) { sshSessionManager.release("s2") }
        // The tabs of the store are not touched.
        assertEquals(listOf("s1"), sessionIds())
        verify(exactly = 0) { sshSessionManager.release("s1") }
    }

    @Test
    fun `replaceTab gives the old reference back even when the old shell does not close`() {
        addTabs("s1")
        every { terminalSessions.getValue("s1").disconnect() } throws IllegalStateException("shell already gone")

        assertTrue(store.replaceTab("s1", tab("s2")))

        verify(exactly = 1) { sshSessionManager.release("s1") }
        assertEquals(listOf("s2"), sessionIds())
    }

    @Test
    fun `replaceTab keeps the split and the active tab where they are`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(2)
        store.updateSplitRatio(0.3f)
        val before = state.splitState

        store.replaceTab("s3", tab("s4"))

        // The new tab is at the rank of the old one: the pane that showed it shows the new one.
        assertEquals(before, state.splitState)
        assertEquals(0, state.activeTabIndex)
        assertEquals(listOf("s1", "s2", "s4"), sessionIds())
    }

    @Test
    fun `replaceTab makes the SFTP pane of the split follow the tab to its new session`() {
        // The pane borrows the session of the tab next to it. Reconnecting the tab opens a new session:
        // a pane left on the old one would stay on a dead session for good.
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplitWithSftp("s1", "Host h-s1")
        store.updateSplitRatio(0.3f)
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)
        val before = state.splitState!!

        store.replaceTab("s1", tab("s3", hostId = "h-s1"))

        val split = state.splitState!!
        assertEquals(PaneContent.Sftp("s3", "Host h-s1"), split.rightOrBottomPane)
        // The host label of the pane stays (same host), and nothing else of the split moves.
        assertEquals(before.leftOrTopPane, split.leftOrTopPane)
        assertEquals(before.orientation, split.orientation)
        assertEquals(before.splitRatio, split.splitRatio)
        assertEquals(before.focusedPane, split.focusedPane)
        assertEquals(listOf("s3", "s2"), sessionIds())
    }

    @Test
    fun `replaceTab follows an SFTP pane in the first slot too`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplitWithSftp("s1", "Host h-s1")
        store.swapPanes()
        assertEquals(PaneContent.Sftp("s1", "Host h-s1"), state.splitState!!.leftOrTopPane)

        store.replaceTab("s1", tab("s3", hostId = "h-s1"))

        assertEquals(PaneContent.Sftp("s3", "Host h-s1"), state.splitState!!.leftOrTopPane)
        assertEquals(PaneContent.Terminal(0), state.splitState!!.rightOrBottomPane)
    }

    @Test
    fun `replaceTab leaves an SFTP pane on another session where it is`() {
        addTabs("s1", "s2")
        store.selectTab(1)
        store.enterSplitWithSftp("s2", "Host h-s2")
        val before = state.splitState

        store.replaceTab("s1", tab("s3"))

        // Not the tab the pane borrows from: the very same split, not even a copy of it.
        assertSame(before, state.splitState)
        assertEquals(PaneContent.Sftp("s2", "Host h-s2"), state.splitState!!.rightOrBottomPane)
    }

    @Test
    fun `replaceTab of a tab that is gone leaves the SFTP pane on its session`() {
        addTabs("s1")
        store.enterSplitWithSftp("s1", "Host h-s1")
        val before = state

        assertFalse(store.replaceTab("gone", tab("s2")))

        assertSame(before, state)
        assertEquals(PaneContent.Sftp("s1", "Host h-s1"), state.splitState!!.rightOrBottomPane)
    }

    @Test
    fun `replaceTab of a tab that is gone changes nothing and the new tab stays the caller's`() {
        addTabs("s1")
        val before = state

        assertFalse(store.replaceTab("gone", tab("s2")))

        assertSame(before, state)
        // Not in the store, so the caller gives its reference back: the store does not.
        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { terminalSessions.getValue("s1").disconnect() }
    }

    @Test
    fun `a tab closed while its reconnection runs is not replaced, and nothing is released twice`() {
        addTabs("s1")
        store.closeTab(0)

        assertFalse(store.replaceTab("s1", tab("s2")))

        assertTrue(state.tabs.isEmpty())
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    @Test
    fun `replacing the same tab twice gives its reference back once`() {
        addTabs("s1")

        assertTrue(store.replaceTab("s1", tab("s2")))
        assertFalse(store.replaceTab("s1", tab("s3")))

        verify(exactly = 1) { sshSessionManager.release("s1") }
        assertEquals(listOf("s2"), sessionIds())
    }

    @Test
    fun `the end of the old shell does not mark the new tab as disconnected`() {
        addTabs("s1")
        val oldClient = store.buildSessionClient("s1")
        store.replaceTab("s1", tab("s2"))

        // Closing the old shell makes Termux report its end, after the replacement.
        oldClient.onSessionFinished(mockk<TerminalSession>(relaxed = true))

        assertEquals(listOf(SessionStatus.CONNECTED), state.tabs.map { it.status })
    }

    // ── Hosts: the tabs follow the edits made to them ───────────────────────

    private fun edited(hostId: String, label: String, theme: String = "TECHTICAL_DARK") =
        host(hostId).copy(label = label, terminalTheme = theme)

    @Test
    fun `an edited host replaces the host of its tabs, whatever their status`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.addTab(tab("s2", hostId = "h2"))
        store.addTab(tab("s3", hostId = "h1", status = SessionStatus.DISCONNECTED))
        store.startObservingHosts()

        hostsInRepository.value = listOf(edited("h1", "Renamed", theme = "OTHER"), host("h2"))

        assertEquals(listOf("Renamed", "Host h2", "Renamed"), state.tabs.map { it.host.label })
        assertEquals("OTHER", state.tabs[0].host.terminalTheme)
        // Only the host changed: status, session and terminal are the tab's own.
        assertEquals(listOf("s1", "s2", "s3"), sessionIds())
        assertEquals(SessionStatus.DISCONNECTED, state.tabs[2].status)
        assertSame(terminalSessions.getValue("s1"), state.tabs[0].terminalSession)
    }

    @Test
    fun `a later edit is applied too, not just the first`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.startObservingHosts()

        hostsInRepository.value = listOf(edited("h1", "First"))
        hostsInRepository.value = listOf(edited("h1", "Second"))

        assertEquals("Second", state.tabs.single().host.label)
    }

    @Test
    fun `a tab opened after an edit keeps the host its opener read and follows the next change`() {
        store.startObservingHosts()
        hostsInRepository.value = listOf(edited("h1", "Renamed"))

        store.addTab(tab("s1", hostId = "h1"))
        assertEquals("Host h1", state.tabs.single().host.label)

        hostsInRepository.value = listOf(edited("h1", "Renamed"), host("h2"))
        assertEquals("Renamed", state.tabs.single().host.label)
    }

    @Test
    fun `a deleted host leaves its tab with the last host it had`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.addTab(tab("s2", hostId = "h2"))
        store.startObservingHosts()
        hostsInRepository.value = listOf(edited("h1", "Renamed"), host("h2"))

        hostsInRepository.value = listOf(host("h2"))

        assertEquals(listOf("Renamed", "Host h2"), state.tabs.map { it.host.label })
    }

    @Test
    fun `hosts that did not change do not republish the state`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.startObservingHosts()
        hostsInRepository.value = listOf(host("h1"), host("h2"))
        val before = state

        hostsInRepository.value = listOf(host("h1"), host("h2"), host("h3"))

        assertSame(before, state)
    }

    @Test
    fun `the edit of a host without a tab changes no tab`() {
        store.addTab(tab("s1", hostId = "h1"))
        store.startObservingHosts()
        val before = state

        hostsInRepository.value = listOf(edited("h2", "Renamed"))

        assertSame(before, state)
    }

    @Test
    fun `an edited host keeps the active tab, the split and the order`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(2)
        val splitBefore = state.splitState
        store.startObservingHosts()

        hostsInRepository.value = listOf(edited("h-s3", "Renamed"))

        assertEquals(0, state.activeTabIndex)
        assertEquals(splitBefore, state.splitState)
        assertEquals("Renamed", state.tabs[2].host.label)
    }

    @Test
    fun `startObservingHosts called twice observes once`() {
        store.startObservingHosts()
        store.startObservingHosts()

        verify(exactly = 1) { hostRepository.observeAll() }
    }

    @Test
    fun `without startObservingHosts the tabs keep their host`() {
        store.addTab(tab("s1", hostId = "h1"))

        hostsInRepository.value = listOf(edited("h1", "Renamed"))

        assertEquals("Host h1", state.tabs.single().host.label)
    }

    // ── Closing all ─────────────────────────────────────────────────────────

    @Test
    fun `closeAll closes every shell and gives every reference back once`() {
        addTabs("s1", "s2", "s3")

        store.closeAll()

        assertTrue(state.tabs.isEmpty())
        listOf("s1", "s2", "s3").forEach { id ->
            verify(exactly = 1) { terminalSessions.getValue(id).disconnect() }
            verify(exactly = 1) { sshSessionManager.release(id) }
        }
    }

    @Test
    fun `closeAll resets the active index and the split`() {
        addTabs("s1", "s2")
        store.enterSplit(0)
        assertTrue(state.splitState != null)

        store.closeAll()

        assertEquals(TerminalTabsState(), state)
    }

    @Test
    fun `a tab that does not close does not keep the others from closing`() {
        addTabs("s1", "s2", "s3")
        every { terminalSessions.getValue("s2").disconnect() } throws IllegalStateException("shell already gone")

        store.closeAll()

        listOf("s1", "s2", "s3").forEach { id -> verify(exactly = 1) { sshSessionManager.release(id) } }
    }

    @Test
    fun `closeAll twice, or after closeTab, never gives a reference back twice`() {
        addTabs("s1", "s2")
        store.closeTab(0)

        store.closeAll()
        store.closeAll()
        store.closeTab(0)

        verify(exactly = 1) { sshSessionManager.release("s1") }
        verify(exactly = 1) { sshSessionManager.release("s2") }
    }

    @Test
    fun `closeAll on an empty store does nothing`() {
        store.closeAll()

        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    // ── End of a shell ──────────────────────────────────────────────────────

    @Test
    fun `the end of a shell marks its tab as disconnected and keeps it`() {
        addTabs("s1", "s2")

        store.buildSessionClient("s1").onSessionFinished(mockk<TerminalSession>(relaxed = true))

        assertEquals(listOf(SessionStatus.DISCONNECTED, SessionStatus.CONNECTED), state.tabs.map { it.status })
        assertEquals(listOf("s1", "s2"), sessionIds())
        // The tab still holds its reference: only the user closes it ("Reconnecter" or the cross).
        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { terminalSessions.getValue("s1").disconnect() }
    }

    @Test
    fun `the end of the shell of an unknown session changes nothing`() {
        addTabs("s1")
        val before = state

        store.buildSessionClient("unknown").onSessionFinished(mockk<TerminalSession>(relaxed = true))

        assertSame(before, state)
    }

    @Test
    fun `the screen is refreshed through the callback of the bound view`() {
        addTabs("s1")
        val session = terminalSessions.getValue("s1")
        var refreshed = 0
        every { session.onScreenUpdate } returns { refreshed++ }

        store.buildSessionClient("s1").onTextChanged(session)

        assertEquals(1, refreshed)
    }

    @Test
    fun `text arriving for a session no view shows is dropped without a fault`() {
        addTabs("s1")
        val session = terminalSessions.getValue("s1")
        every { session.onScreenUpdate } returns null

        store.buildSessionClient("s1").onTextChanged(session)
    }

    // ── Split ───────────────────────────────────────────────────────────────

    @Test
    fun `enterSplit puts the active tab and the second one in the panes`() {
        addTabs("s1", "s2")
        store.selectTab(0)

        store.enterSplit(1, SplitOrientation.VERTICAL)

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(1), split.rightOrBottomPane)
        assertEquals(SplitOrientation.VERTICAL, split.orientation)
    }

    @Test
    fun `enterSplit refuses the active tab itself and an index out of range`() {
        addTabs("s1", "s2")
        store.selectTab(0)

        store.enterSplit(0)
        store.enterSplit(9)

        assertNull(state.splitState)
    }

    @Test
    fun `enterSplitWithSftp pairs the active tab with an SFTP pane`() {
        addTabs("s1")

        store.enterSplitWithSftp("s1", "Host h-s1")

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Sftp("s1", "Host h-s1"), split.rightOrBottomPane)
    }

    @Test
    fun `exitSplit makes the tab of the focused pane the active one`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplit(1)
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)

        store.exitSplit()

        assertNull(state.splitState)
        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `exitSplit with the SFTP pane focused keeps the active tab`() {
        addTabs("s1", "s2")
        store.selectTab(1)
        store.enterSplitWithSftp("s2", "Host h-s2")
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)

        store.exitSplit()

        assertNull(state.splitState)
        assertEquals(1, state.activeTabIndex)
    }

    @Test
    fun `the split ratio is clamped between 0,2 and 0,8`() {
        addTabs("s1", "s2")
        store.enterSplit(0)

        store.updateSplitRatio(0.05f)
        assertEquals(0.2f, state.splitState!!.splitRatio)
        store.updateSplitRatio(0.95f)
        assertEquals(0.8f, state.splitState!!.splitRatio)
        store.updateSplitRatio(0.6f)
        assertEquals(0.6f, state.splitState!!.splitRatio)
    }

    @Test
    fun `toggleSplitOrientation flips the orientation`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplit(1)

        store.toggleSplitOrientation()
        assertEquals(SplitOrientation.VERTICAL, state.splitState!!.orientation)
        store.toggleSplitOrientation()
        assertEquals(SplitOrientation.HORIZONTAL, state.splitState!!.orientation)
    }

    @Test
    fun `swapPanes swaps the content and the focus follows it`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplit(1)

        store.swapPanes()

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(1), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(0), split.rightOrBottomPane)
        assertEquals(PaneSlot.RIGHT_OR_BOTTOM, split.focusedPane)
    }

    @Test
    fun `split operations without a split change nothing`() {
        addTabs("s1")
        val before = state

        store.exitSplit()
        store.toggleSplitOrientation()
        store.updateSplitRatio(0.3f)
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)
        store.swapPanes()

        assertSame(before, state)
    }

    @Test
    fun `closing a tab shown in a pane leaves the split`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(1)

        store.closeTab(1)

        assertNull(state.splitState)
        assertEquals(listOf("s1", "s3"), sessionIds())
    }

    @Test
    fun `closing a tab before the panes shifts the pane indices down`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(1)
        store.enterSplit(2)

        store.closeTab(0)

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(1), split.rightOrBottomPane)
    }

    @Test
    fun `closing a tab after the panes leaves the pane indices alone`() {
        addTabs("s1", "s2", "s3")
        store.selectTab(0)
        store.enterSplit(1)

        store.closeTab(2)

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Terminal(1), split.rightOrBottomPane)
    }

    @Test
    fun `closing another tab keeps an SFTP pane as it is`() {
        addTabs("s1", "s2")
        store.selectTab(1)
        store.enterSplitWithSftp("s2", "Host h-s2")

        store.closeTab(0)

        val split = state.splitState!!
        assertEquals(PaneContent.Terminal(0), split.leftOrTopPane)
        assertEquals(PaneContent.Sftp("s2", "Host h-s2"), split.rightOrBottomPane)
    }

    @Test
    fun `focusedSession is the active tab outside a split`() {
        addTabs("s1", "s2")
        store.selectTab(0)

        assertSame(terminalSessions.getValue("s1"), store.focusedSession)
    }

    @Test
    fun `focusedSession follows the focused pane and is null on an SFTP pane`() {
        addTabs("s1", "s2")
        store.selectTab(0)
        store.enterSplit(1)

        assertSame(terminalSessions.getValue("s1"), store.focusedSession)
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)
        assertSame(terminalSessions.getValue("s2"), store.focusedSession)

        store.exitSplit()
        store.enterSplitWithSftp("s2", "Host h-s2")
        store.setFocusedPane(PaneSlot.RIGHT_OR_BOTTOM)
        assertNull(store.focusedSession)
    }

    @Test
    fun `focusedSession is null with no tab`() {
        assertNull(store.focusedSession)
    }

    // ── Clipboard ───────────────────────────────────────────────────────────

    private fun copy(text: String): ClipData {
        mockkStatic(ClipData::class)
        val clip = mockk<ClipData>()
        every { ClipData.newPlainText("NextSH", text) } returns clip
        store.buildSessionClient("s1").onCopyTextToClipboard(mockk(relaxed = true), text)
        return clip
    }

    @Test
    fun `copied text goes to the clipboard and is cleared after the delay of the settings`() {
        clipboardClearTimeout = 30

        val clip = copy("some output")
        verify(exactly = 1) { clipboard.setPrimaryClip(clip) }

        scheduler.advanceTimeBy(29_000)
        scheduler.runCurrent()
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        scheduler.advanceTimeBy(1_000)
        scheduler.runCurrent()
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `a new copy restarts the delay and only one clearing is left`() {
        clipboardClearTimeout = 30
        copy("first")
        scheduler.advanceTimeBy(20_000)
        scheduler.runCurrent()

        copy("second")
        scheduler.advanceTimeBy(20_000)
        scheduler.runCurrent()
        // 40 s after the first copy but only 20 s after the second: not cleared yet.
        verify(exactly = 0) { clipboard.clearPrimaryClip() }

        scheduler.advanceTimeBy(10_000)
        scheduler.runCurrent()
        verify(exactly = 1) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `a delay of zero keeps the clipboard`() {
        clipboardClearTimeout = 0

        copy("some output")
        scheduler.advanceTimeBy(3_600_000)
        scheduler.runCurrent()

        verify(exactly = 0) { clipboard.clearPrimaryClip() }
    }

    @Test
    fun `empty copied text is ignored`() {
        store.buildSessionClient("s1").onCopyTextToClipboard(mockk(relaxed = true), "")
        store.buildSessionClient("s1").onCopyTextToClipboard(mockk(relaxed = true), null)

        verify(exactly = 0) { clipboard.setPrimaryClip(any()) }
    }

    @Test
    fun `pasting hands the text of the clipboard to the emulator as a paste`() {
        val item = mockk<ClipData.Item>()
        every { item.coerceToText(any()) } returns "pasted text"
        val clip = mockk<ClipData>()
        every { clip.itemCount } returns 1
        every { clip.getItemAt(0) } returns item
        every { clipboard.primaryClip } returns clip
        val emulator = mockk<TerminalEmulator>(relaxed = true)
        val session = mockk<TerminalSession>(relaxed = true)
        every { session.emulator } returns emulator

        store.buildSessionClient("s1").onPasteTextFromClipboard(session)

        verify(exactly = 1) { emulator.paste("pasted text") }
        verify(exactly = 0) { session.write(any<String>()) }
    }

    @Test
    fun `pasting an empty clipboard writes nothing`() {
        every { clipboard.primaryClip } returns null
        val session = mockk<TerminalSession>(relaxed = true)

        store.buildSessionClient("s1").onPasteTextFromClipboard(session)

        verify(exactly = 0) { session.write(any<String>()) }
    }
}
