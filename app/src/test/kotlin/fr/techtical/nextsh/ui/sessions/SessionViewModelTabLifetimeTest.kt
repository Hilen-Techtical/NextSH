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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
 * A terminal tab outlives the screen. The tabs, the split and the SSH reference of each tab belong
 * to [TerminalTabStore], an app singleton: [SessionViewModel] is a facade that dies with the
 * screen, and clearing it closes nothing and gives nothing back. A tab closes on an explicit action
 * of the user only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTabLifetimeTest {

    private val mainDispatcher = UnconfinedTestDispatcher()

    private lateinit var sshSessionManager: SshSessionManager
    private lateinit var sessionHandoff: SessionHandoff
    private lateinit var hostRepository: HostRepository
    private lateinit var connectSession: ConnectSessionUseCase
    private lateinit var terminalService: TerminalServiceController
    private lateinit var settings: SettingsDataStore
    private lateinit var tabStore: TerminalTabStore
    private lateinit var shell: Session.Shell

    /** Whether the app is on screen for the terminal, see [AppVisibility]. */
    private var appVisible = true

    private val builtTerminalSessions = mutableListOf<SshTerminalSession>()

    private val host = Host(
        id = "h1",
        label = "Host h1",
        hostname = "hosth1.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-h1",
    )

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)

        // Strict mocks: a call the test did not expect fails it.
        sshSessionManager = mockk()
        sessionHandoff = mockk()
        hostRepository = mockk()
        connectSession = mockk()
        terminalService = mockk()
        shell = mockk()
        builtTerminalSessions.clear()
        appVisible = true

        coEvery { hostRepository.getById("h1") } returns host
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

    /** A ViewModel on the one store, as each visit of the terminal screen gets its own. */
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

    private fun SessionViewModel.clear() {
        // onCleared is protected; the ViewModel machinery that calls it is not reachable from a unit test.
        SessionViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(this)
    }

    private fun session(id: String) = SshSession(id = id, host = host, status = SessionStatus.CONNECTED)

    /** A connection of the terminal gives a new session [id] whose shell opens. */
    private fun connectsTo(id: String) {
        coEvery { connectSession(host) } returns SshResult.Success(session(id))
        coEvery { sshSessionManager.openShell(id) } returns SshResult.Success(shell)
    }

    /** The session [id] is on offer, live in the manager, and its shell opens. */
    private fun liveSession(id: String) {
        every { sessionHandoff.claim(id) } returns true
        coEvery { sshSessionManager.getSession(id) } returns session(id)
        every { sshSessionManager.isConnected(id) } returns true
        coEvery { sshSessionManager.openShell(id) } returns SshResult.Success(shell)
    }

    // ── Leaving the screen closes nothing ───────────────────────────────────

    @Test
    fun `clearing the ViewModel closes no tab and gives no reference back`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        viewModel.connectToHost("h1")
        advanceUntilIdle()

        viewModel.clear()

        assertEquals(listOf("s1"), tabStore.state.value.tabs.map { it.sessionId })
        verify(exactly = 0) { sshSessionManager.release(any()) }
        verify(exactly = 0) { builtTerminalSessions.single().disconnect() }
    }

    @Test
    fun `clearing a ViewModel with tabs in a split keeps the split`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        viewModel.connectToHost("h1")
        advanceUntilIdle()
        viewModel.enterSplitWithSftp("s1", "Host h1")

        viewModel.clear()

        assertTrue(tabStore.state.value.splitState != null)
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `a new ViewModel finds the tabs the previous one opened`() = runTest {
        connectsTo("s1")
        val first = newViewModel()
        first.connectToHost("h1")
        advanceUntilIdle()
        first.clear()

        val second = newViewModel()

        val state = second.uiState.value
        assertEquals(listOf("s1"), state.tabs.map { it.sessionId })
        assertEquals(0, state.activeTabIndex)
        assertEquals(SessionStatus.CONNECTED, state.tabs[0].status)
        assertFalse(state.isConnecting)
        assertNull(state.error)
    }

    @Test
    fun `a new ViewModel does not take over a session a tab already shows`() = runTest {
        liveSession("s1")
        val first = newViewModel()
        first.attachToSession("s1", "h1")
        advanceUntilIdle()
        first.clear()

        val second = newViewModel()
        second.attachToSession("s1", "h1")
        advanceUntilIdle()

        // Not even claimed: the guard reads the store, which still has the tab of the first screen.
        verify(exactly = 1) { sessionHandoff.claim("s1") }
        assertEquals(1, tabStore.state.value.tabs.size)
        assertEquals(1, builtTerminalSessions.size)
    }

    @Test
    fun `selectTabOfHost selects the tab an earlier screen opened`() = runTest {
        connectsTo("s1")
        val first = newViewModel()
        first.connectToHost("h1")
        advanceUntilIdle()
        first.clear()
        val second = newViewModel()

        assertTrue(second.selectTabOfHost("h1"))
        assertFalse(second.selectTabOfHost("h2"))

        assertEquals(listOf("s1"), second.uiState.value.tabs.map { it.sessionId })
        // Selecting connects to nothing: the one connection is the first screen's.
        coVerify(exactly = 1) { connectSession(any()) }
    }

    @Test
    fun `the new screen closes the tab of the old one once, with its own cross`() = runTest {
        connectsTo("s1")
        val first = newViewModel()
        first.connectToHost("h1")
        advanceUntilIdle()
        first.clear()
        val second = newViewModel()

        second.closeTab(0)

        verify(exactly = 1) { builtTerminalSessions.single().disconnect() }
        verify(exactly = 1) { sshSessionManager.release("s1") }
        assertTrue(second.uiState.value.tabs.isEmpty())
    }

    // ── Leaving the screen still ends what is only the screen's ─────────────

    @Test
    fun `leaving the screen during a connection releases the session no tab holds yet, and adds no tab`() = runTest {
        coEvery { connectSession(host) } returns SshResult.Success(session("s1"))
        coEvery { sshSessionManager.openShell("s1") } coAnswers { awaitCancellation() }
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        viewModel.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()

        verify(exactly = 1) { sshSessionManager.release("s1") }
        assertTrue(tabStore.state.value.tabs.isEmpty())
        verify(exactly = 0) { terminalService.ensureRunning() }
    }

    // ── The state of the screen is the store plus what is the screen's ──────

    @Test
    fun `a change made to the store elsewhere, like the close all of the notification, reaches the screen`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        viewModel.connectToHost("h1")
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.tabs.size)

        tabStore.closeAll()

        assertTrue(viewModel.uiState.value.tabs.isEmpty())
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    @Test
    fun `uiState is read right after an action`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        viewModel.connectToHost("h1")
        advanceUntilIdle()

        viewModel.selectTab(0)
        viewModel.enterSplitWithSftp("s1", "Host h1")
        assertTrue(viewModel.uiState.value.splitState != null)
        viewModel.exitSplit()
        assertNull(viewModel.uiState.value.splitState)
        viewModel.closeTab(0)
        assertTrue(viewModel.uiState.value.tabs.isEmpty())
    }

    @Test
    fun `uiState emits what the store and the screen change`() = runTest {
        connectsTo("s1")
        val viewModel = newViewModel()
        val seen = mutableListOf<SessionUiState>()
        backgroundScope.launch(mainDispatcher) { viewModel.uiState.collect { seen += it } }
        assertEquals(1, seen.size)
        assertTrue(seen.last().tabs.isEmpty())

        viewModel.connectToHost("h1")
        advanceUntilIdle()
        assertEquals(listOf("s1"), seen.last().tabs.map { it.sessionId })
        assertFalse(seen.last().isConnecting)

        tabStore.buildSessionClient("s1").onSessionFinished(mockk(relaxed = true))
        assertEquals(SessionStatus.DISCONNECTED, seen.last().tabs.single().status)

        tabStore.closeAll()
        assertTrue(seen.last().tabs.isEmpty())
    }

    @Test
    fun `an error of the screen stays with the screen and clearError removes it`() = runTest {
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "unreachable")
        val first = newViewModel()
        first.connectToHost("h1")
        advanceUntilIdle()
        assertEquals("unreachable", first.uiState.value.error)

        // The error is the screen's: another screen does not see it.
        assertNull(newViewModel().uiState.value.error)

        first.clearError()
        assertNull(first.uiState.value.error)
    }

    // ── The foreground service ──────────────────────────────────────────────

    @Test
    fun `the service is asked to run once the tab is in the store`() = runTest {
        connectsTo("s1")
        var tabsWhenAsked = -1
        every { terminalService.ensureRunning() } answers {
            tabsWhenAsked = tabStore.state.value.tabs.size
            true
        }
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        verify(exactly = 1) { terminalService.ensureRunning() }
        assertEquals(1, tabsWhenAsked)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a taken over session also asks for the service`() = runTest {
        liveSession("s1")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        verify(exactly = 1) { terminalService.ensureRunning() }
    }

    @Test
    fun `a refusal of the service is told to the user and the tab stays open`() = runTest {
        connectsTo("s1")
        every { terminalService.ensureRunning() } returns false
        val context = mockk<Context>()
        every { context.getString(R.string.terminal_service_start_refused) } returns "The session may drop if you leave NextSH."
        val viewModel = newViewModel(context)

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("The session may drop if you leave NextSH.", state.error)
        assertEquals(listOf("s1"), state.tabs.map { it.sessionId })
        assertFalse(state.isConnecting)
        // The tab is open and holds its reference: nothing is given back.
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `a refusal of the service while the app is hidden is not told, the next start asks again`() = runTest {
        connectsTo("s1")
        // The connection took longer than the stay of the user in the app: the system refuses a
        // foreground service started from the background, and nobody is there to read a message.
        appVisible = false
        every { terminalService.ensureRunning() } returns false
        val context = mockk<Context>()
        val viewModel = newViewModel(context)

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(listOf("s1"), state.tabs.map { it.sessionId })
        assertFalse(state.isConnecting)
        // The message was not even built; the tab holds its reference.
        verify(exactly = 0) { context.getString(any<Int>()) }
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `the visibility is read at the moment of the refusal, not before the connection`() = runTest {
        connectsTo("s1")
        val context = mockk<Context>()
        every { context.getString(R.string.terminal_service_start_refused) } returns "The session may drop if you leave NextSH."
        // Visible when the connection starts, hidden by the time the service is asked for.
        every { terminalService.ensureRunning() } answers {
            appVisible = false
            false
        }
        val viewModel = newViewModel(context)

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a service that starts while the app is hidden needs no message`() = runTest {
        connectsTo("s1")
        appVisible = false
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        verify(exactly = 1) { terminalService.ensureRunning() }
    }

    @Test
    fun `a taken over session refused by the service while hidden is not told either`() = runTest {
        liveSession("s1")
        appVisible = false
        every { terminalService.ensureRunning() } returns false
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf("s1"), viewModel.uiState.value.tabs.map { it.sessionId })
    }

    @Test
    fun `no service is asked for when the shell does not open`() = runTest {
        coEvery { connectSession(host) } returns SshResult.Success(session("s1"))
        coEvery { sshSessionManager.openShell("s1") } returns SshResult.Error(SshErrorCode.UNKNOWN, "no shell")
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        verify(exactly = 0) { terminalService.ensureRunning() }
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }
}
