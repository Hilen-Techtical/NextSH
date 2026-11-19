// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.viewModelScope
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.service.TerminalServiceController
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Reconnecting a tab used to close it, then connect again at the end of the list: a connection that
 * failed (host unreachable, authentication expired) left the user without the tab and without its
 * history. The connection now comes first, and [TerminalTabStore.replaceTab] swaps the tab in its
 * place only once it succeeded. Until then the old tab is left as it is.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelReconnectTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var sessionHandoff: SessionHandoff
    private lateinit var hostRepository: HostRepository
    private lateinit var connectSession: ConnectSessionUseCase
    private lateinit var terminalService: TerminalServiceController
    private lateinit var settings: SettingsDataStore
    private lateinit var tabStore: TerminalTabStore
    private lateinit var shell: Session.Shell
    private var appVisible = true

    /** The terminal session of each tab opened so far, in the order they were built. */
    private val builtTerminalSessions = mutableListOf<SshTerminalSession>()

    private val host = host("h1")
    private val otherHost = host("h2")

    private fun host(id: String) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)

        // Strict mocks: a call the test did not expect (a second connection, say) fails it.
        sshSessionManager = mockk()
        sessionHandoff = mockk()
        hostRepository = mockk()
        connectSession = mockk()
        terminalService = mockk()
        shell = mockk()
        builtTerminalSessions.clear()
        appVisible = true

        coEvery { hostRepository.getById("h1") } returns host
        coEvery { hostRepository.getById("h2") } returns otherHost
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { sshSessionManager.release(any()) } just Runs
        every { terminalService.ensureRunning() } returns true
        settings = mockk()
        every { settings.settingsFlow } returns flowOf(SettingsDataStore.Settings())
        every { settings.terminalFontSizeFlow } returns flowOf(24)

        val storeContext = mockk<Context>()
        every { storeContext.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockk<ClipboardManager>()
        tabStore = TerminalTabStore(storeContext, sshSessionManager, settings, hostRepository).also {
            it.cleanupScope = CoroutineScope(SupervisorJob() + mainDispatcher)
            it.clipboardScope = CoroutineScope(SupervisorJob() + mainDispatcher)
        }
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(context: Context = mockk()): SessionViewModel {
        val customThemes = mockk<CustomTerminalThemeRepository>()
        every { customThemes.observeAll() } returns flowOf(emptyList())

        return SessionViewModel(
            context = context,
            sshSessionManager = sshSessionManager,
            hostRepository = hostRepository,
            snippetRepository = mockk<SnippetRepository>(),
            customThemeRepository = customThemes,
            connectSession = connectSession,
            settingsDataStore = settings,
            sessionHandoff = sessionHandoff,
            tabStore = tabStore,
            terminalService = terminalService,
            appVisibility = AppVisibility { appVisible },
        ).also { viewModel ->
            viewModel.terminalSessionFactory = { _, _, _, _ ->
                mockk<SshTerminalSession>(relaxed = true).also { builtTerminalSessions += it }
            }
        }
    }

    private fun session(id: String, forHost: Host = host) =
        SshSession(id = id, host = forHost, status = SessionStatus.CONNECTED)

    /** A connection to [forHost] gives a new session [id] whose shell opens. */
    private fun connectsTo(id: String, forHost: Host = host) {
        coEvery { connectSession(forHost) } returns SshResult.Success(session(id, forHost))
        coEvery { sshSessionManager.openShell(id) } returns SshResult.Success(shell)
    }

    /** The shell of the tab on session [id] ends, as Termux reports it. */
    private fun shellEnds(id: String) {
        tabStore.buildSessionClient(id).onSessionFinished(mockk(relaxed = true))
    }

    /** A view model with one open tab on session `s1` (host h1) whose shell has ended. */
    private fun SessionViewModel.withDisconnectedTab(): SessionViewModel {
        connectsTo("s1")
        connectToHost("h1")
        shellEnds("s1")
        assertEquals(SessionStatus.DISCONNECTED, tabStore.state.value.tabs.single().status)
        return this
    }

    private val state get() = tabStore.state.value

    private fun sessionIds() = state.tabs.map { it.sessionId }

    // ── Scrollback of the settings ─────────────────────────────────────────

    /** The scrollback rows given to the factory by a connection, then by a reconnection, with [stored] in the settings. */
    private fun TestScope.rowsGivenWith(stored: Int?): List<Int> {
        val settingsValue = if (stored == null) SettingsDataStore.Settings() else SettingsDataStore.Settings(scrollbackLines = stored)
        every { settings.settingsFlow } returns flowOf(settingsValue)
        val viewModel = newViewModel()
        val rows = mutableListOf<Int>()
        viewModel.terminalSessionFactory = { _, _, _, scrollbackRows ->
            rows += scrollbackRows
            mockk<SshTerminalSession>(relaxed = true).also { builtTerminalSessions += it }
        }
        viewModel.withDisconnectedTab()
        connectsTo("s2")
        viewModel.reconnectTab(0)
        advanceUntilIdle()
        return rows
    }

    @Test
    fun `a new tab and a reconnection are built with the scrollback of the settings`() = runTest {
        assertEquals(listOf(10_000, 10_000), rowsGivenWith(10_000))
    }

    @Test
    fun `an absent setting gives the default scrollback of 5000 lines`() = runTest {
        assertEquals(listOf(5000, 5000), rowsGivenWith(null))
    }

    @Test
    fun `a stored scrollback above the steps is brought to the largest step`() = runTest {
        assertEquals(listOf(20_000, 20_000), rowsGivenWith(100_000))
    }

    @Test
    fun `a stored scrollback below the steps is brought to the smallest step`() = runTest {
        assertEquals(listOf(1000, 1000), rowsGivenWith(100))
    }

    // ── A reconnection that succeeds ────────────────────────────────────────

    @Test
    fun `a reconnection replaces the tab in its place, not at the end of the list`() = runTest {
        val viewModel = newViewModel()
        connectsTo("s1")
        viewModel.connectToHost("h1")
        connectsTo("s2", otherHost)
        viewModel.connectToHost("h2")
        shellEnds("s1")
        connectsTo("s3")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals(listOf("s3", "s2"), sessionIds())
        assertEquals(listOf(SessionStatus.CONNECTED, SessionStatus.CONNECTED), state.tabs.map { it.status })
        assertEquals("h1", state.tabs[0].host.id)
    }

    @Test
    fun `a reconnection closes the old shell and gives the old reference back once`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        connectsTo("s2")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        verify(exactly = 1) { builtTerminalSessions[0].disconnect() }
        verify(exactly = 1) { sshSessionManager.release("s1") }
        // The new tab holds the reference of its connection.
        verify(exactly = 0) { sshSessionManager.release("s2") }
        verify(exactly = 0) { builtTerminalSessions[1].disconnect() }
        val ui = viewModel.uiState.value
        assertFalse(ui.isConnecting)
        assertNull(ui.error)
    }

    @Test
    fun `a reconnection keeps the active tab and the split`() = runTest {
        val viewModel = newViewModel()
        connectsTo("s1")
        viewModel.connectToHost("h1")
        connectsTo("s2", otherHost)
        viewModel.connectToHost("h2")
        viewModel.selectTab(0)
        viewModel.enterSplit(1)
        val splitBefore = state.splitState
        shellEnds("s1")
        connectsTo("s3")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals(listOf("s3", "s2"), sessionIds())
        assertEquals(0, state.activeTabIndex)
        assertEquals(splitBefore, state.splitState)
    }

    @Test
    fun `a reconnection connects to the host as it is now, not as the tab remembers it`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        val renamed = host.copy(label = "Renamed", hostname = "moved.example.com")
        coEvery { hostRepository.getById("h1") } returns renamed
        coEvery { connectSession(renamed) } returns SshResult.Success(session("s2", renamed))
        coEvery { sshSessionManager.openShell("s2") } returns SshResult.Success(shell)

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals("Renamed", state.tabs.single().host.label)
    }

    @Test
    fun `the service is asked for once the tab is replaced`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        connectsTo("s2")
        var tabWhenAsked: String? = null
        every { terminalService.ensureRunning() } answers {
            tabWhenAsked = state.tabs.single().sessionId
            true
        }

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        // Once for the first tab, once for its replacement; the answer above saw the new tab in place.
        verify(exactly = 2) { terminalService.ensureRunning() }
        assertEquals("s2", tabWhenAsked)
    }

    @Test
    fun `a refusal of the service after a reconnection is told to the user on screen`() = runTest {
        val context = mockk<Context>()
        every { context.getString(R.string.terminal_service_start_refused) } returns "The session may drop if you leave NextSH."
        val viewModel = newViewModel(context).withDisconnectedTab()
        connectsTo("s2")
        every { terminalService.ensureRunning() } returns false

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals("The session may drop if you leave NextSH.", viewModel.uiState.value.error)
        assertEquals(listOf("s2"), sessionIds())
    }

    // ── While the connection runs ───────────────────────────────────────────

    @Test
    fun `the old tab is left alone until the connection succeeds`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        val gate = CompletableDeferred<SshResult<SshSession>>()
        coEvery { connectSession(host) } coAnswers { gate.await() }
        coEvery { sshSessionManager.openShell("s2") } returns SshResult.Success(shell)

        viewModel.reconnectTab(0)

        // Connecting: the screen shows it, the tab is neither closed nor released yet.
        assertTrue(viewModel.uiState.value.isConnecting)
        assertEquals(listOf("s1"), sessionIds())
        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { builtTerminalSessions[0].disconnect() }

        gate.complete(SshResult.Success(session("s2")))
        advanceUntilIdle()

        assertEquals(listOf("s2"), sessionIds())
        assertFalse(viewModel.uiState.value.isConnecting)
    }

    @Test
    fun `a second tap on the same tab during the reconnection starts nothing`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        val gate = CompletableDeferred<SshResult<SshSession>>()
        coEvery { connectSession(host) } coAnswers { gate.await() }
        coEvery { sshSessionManager.openShell("s2") } returns SshResult.Success(shell)

        viewModel.reconnectTab(0)
        viewModel.reconnectTab(0)
        viewModel.reconnectTab(0)
        gate.complete(SshResult.Success(session("s2")))
        advanceUntilIdle()

        // One connection for the first tab and one for its reconnection: no third.
        coVerify(exactly = 2) { connectSession(host) }
        assertEquals(listOf("s2"), sessionIds())
    }

    @Test
    fun `a tab that is connected again cannot be reconnected`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        connectsTo("s2")
        viewModel.reconnectTab(0)
        advanceUntilIdle()

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        coVerify(exactly = 2) { connectSession(host) }
        assertEquals(listOf("s2"), sessionIds())
    }

    @Test
    fun `an index with no tab starts nothing`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()

        viewModel.reconnectTab(5)
        viewModel.reconnectTab(-1)
        advanceUntilIdle()

        coVerify(exactly = 1) { connectSession(host) }
    }

    @Test
    fun `reconnecting a tab that is connected does nothing`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        viewModel.connectToHost("h1")
        advanceUntilIdle()

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        coVerify(exactly = 1) { connectSession(host) }
        assertEquals(listOf("s1"), sessionIds())
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    // ── A reconnection that fails ───────────────────────────────────────────

    @Test
    fun `a connection that fails leaves the tab as it was and shows the error`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        val before = state.tabs.single()
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "unreachable")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals("unreachable", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isConnecting)
        // The same tab, with its terminal and its history: nothing was closed or released.
        assertTrue(before === state.tabs.single())
        assertEquals(SessionStatus.DISCONNECTED, state.tabs.single().status)
        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { builtTerminalSessions[0].disconnect() }
        // The service was asked for the first tab only.
        verify(exactly = 1) { terminalService.ensureRunning() }
    }

    @Test
    fun `an authentication that expired is shown like any other error and keeps the tab`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "auth expired")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals("auth expired", viewModel.uiState.value.error)
        assertEquals(listOf("s1"), sessionIds())
    }

    @Test
    fun `a host that is gone leaves the tab and says so`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        coEvery { hostRepository.getById("h1") } returns null

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.error!!.contains("h1"))
        assertEquals(listOf("s1"), sessionIds())
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `a shell that does not open gives the new session back and keeps the old tab`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        coEvery { connectSession(host) } returns SshResult.Success(session("s2"))
        coEvery { sshSessionManager.openShell("s2") } returns SshResult.Error(SshErrorCode.UNKNOWN, "no shell")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals("no shell", viewModel.uiState.value.error)
        verify(exactly = 1) { sshSessionManager.release("s2") }
        verify(exactly = 0) { sshSessionManager.release("s1") }
        assertEquals(listOf("s1"), sessionIds())
    }

    @Test
    fun `after a failure the tab can be reconnected again`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "unreachable")
        viewModel.reconnectTab(0)
        advanceUntilIdle()
        connectsTo("s2")

        viewModel.reconnectTab(0)
        advanceUntilIdle()

        assertEquals(listOf("s2"), sessionIds())
        assertNull(viewModel.uiState.value.error)
    }

    // ── The screen is left, or the tab closed, meanwhile ────────────────────

    @Test
    fun `leaving the screen during the reconnection gives the new reference back and keeps the old tab`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        coEvery { connectSession(host) } returns SshResult.Success(session("s2"))
        coEvery { sshSessionManager.openShell("s2") } coAnswers { awaitCancellation() }

        viewModel.reconnectTab(0)
        viewModel.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()

        verify(exactly = 1) { sshSessionManager.release("s2") }
        verify(exactly = 0) { sshSessionManager.release("s1") }
        assertEquals(listOf("s1"), sessionIds())
        assertEquals(SessionStatus.DISCONNECTED, state.tabs.single().status)
        verify(exactly = 1) { terminalService.ensureRunning() }
    }

    @Test
    fun `a screen that comes back can reconnect the tab the first one left`() = runTest {
        val first = newViewModel().withDisconnectedTab()
        coEvery { connectSession(host) } returns SshResult.Success(session("s2"))
        coEvery { sshSessionManager.openShell("s2") } coAnswers { awaitCancellation() }
        first.reconnectTab(0)
        first.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()
        connectsTo("s3")

        val second = newViewModel()
        second.reconnectTab(0)
        advanceUntilIdle()

        assertEquals(listOf("s3"), sessionIds())
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    @Test
    fun `a tab closed during its reconnection leaves no place, the new session is given back`() = runTest {
        val viewModel = newViewModel().withDisconnectedTab()
        val gate = CompletableDeferred<SshResult<SshSession>>()
        coEvery { connectSession(host) } coAnswers { gate.await() }
        coEvery { sshSessionManager.openShell("s2") } returns SshResult.Success(shell)

        viewModel.reconnectTab(0)
        viewModel.closeTab(0)
        gate.complete(SshResult.Success(session("s2")))
        advanceUntilIdle()

        assertTrue(state.tabs.isEmpty())
        // The old reference once, by the cross, and the new one once, for want of a place.
        verify(exactly = 1) { sshSessionManager.release("s1") }
        verify(exactly = 1) { sshSessionManager.release("s2") }
        // The shell built for the new session is closed before its reference goes back, as when a
        // tab is closed: another holder of that session would otherwise keep it open, orphaned.
        verify(exactly = 1) { builtTerminalSessions.last().disconnect() }
        verifyOrder {
            builtTerminalSessions.last().disconnect()
            sshSessionManager.release("s2")
        }
        assertFalse(viewModel.uiState.value.isConnecting)
        assertNull(viewModel.uiState.value.error)
        // The service was asked for the first tab only: there is no new tab to serve.
        verify(exactly = 1) { terminalService.ensureRunning() }
    }
}
