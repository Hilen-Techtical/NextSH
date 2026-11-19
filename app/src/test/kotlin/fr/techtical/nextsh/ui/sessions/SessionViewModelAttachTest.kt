// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.viewModelScope
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
import fr.techtical.nextsh.testutil.awaitVerified
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tapping a host in the list used to open two SSH connections: the list connected, then the
 * terminal connected again (and the first session stayed open, orphaned). The terminal now takes
 * over the session of the list with [SessionViewModel.attachToSession], and only connects itself
 * when that session is gone.
 *
 * The session of the list is on offer in the [SessionHandoff]: the terminal claims it, which makes
 * it the owner of the reference the list held, and releases that reference unless a tab keeps it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelAttachTest {

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

    /** The terminal sessions built so far: one per shell turned into a tab. */
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
        every { sshSessionManager.release(any()) } just Runs
        every { terminalService.ensureRunning() } returns true

        // The store is the app singleton the ViewModels share. Its closing scope runs on the test
        // dispatcher, so a shell is closed and its reference given back when the call returns.
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

    private fun newViewModel(): SessionViewModel {
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        val customThemes = mockk<CustomTerminalThemeRepository>()
        every { customThemes.observeAll() } returns flowOf(emptyList())

        return SessionViewModel(
            context = mockk<Context>(),
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
            // The real SshTerminalSession needs an Android Handler, absent from a unit test.
            viewModel.terminalSessionFactory = { _, _, _, _ ->
                mockk<SshTerminalSession>(relaxed = true).also { builtTerminalSessions += it }
            }
        }
    }

    private fun session(id: String, forHost: Host = host) =
        SshSession(id = id, host = forHost, status = SessionStatus.CONNECTED)

    /** The session [id] is on offer, live in the manager, and its shell opens. */
    private fun liveSession(id: String) {
        every { sessionHandoff.claim(id) } returns true
        coEvery { sshSessionManager.getSession(id) } returns session(id)
        every { sshSessionManager.isConnected(id) } returns true
        coEvery { sshSessionManager.openShell(id) } returns SshResult.Success(shell)
    }

    /** What a connection of the terminal itself gives: a new session [id] whose shell opens. */
    private fun connectsTo(id: String) {
        coEvery { connectSession(host) } returns SshResult.Success(session(id))
        coEvery { sshSessionManager.openShell(id) } returns SshResult.Success(shell)
    }

    // ── The session of the list is taken over ───────────────────────────────

    @Test
    fun `attachToSession opens the shell on the live session and never connects again`() = runTest {
        liveSession("s1")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.tabs.size)
        // The tab keeps the id of the session of the list: closing it releases that very session.
        assertEquals("s1", state.tabs[0].sessionId)
        assertEquals("h1", state.tabs[0].host.id)
        assertEquals(SessionStatus.CONNECTED, state.tabs[0].status)
        assertEquals(0, state.activeTabIndex)
        assertFalse(state.isConnecting)
        assertNull(state.error)
        coVerify(exactly = 0) { connectSession(any()) }
        coVerify(exactly = 1) { sshSessionManager.openShell("s1") }
        // The reference of the list is the tab's now: nothing is given back while the tab lives.
        verify(exactly = 1) { sessionHandoff.claim("s1") }
        verify(exactly = 0) { sshSessionManager.release(any()) }
        coVerify(exactly = 0) { sshSessionManager.disconnect(any()) }
    }

    @Test
    fun `a second attachToSession for a session already shown opens no second tab nor shell`() = runTest {
        liveSession("s1")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()
        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.tabs.size)
        coVerify(exactly = 1) { sshSessionManager.openShell("s1") }
        assertEquals(1, builtTerminalSessions.size)
        verify(exactly = 1) { sessionHandoff.claim("s1") }
    }

    @Test
    fun `a second attachToSession while the first still opens the shell does nothing`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns session("s1")
        every { sshSessionManager.isConnected("s1") } returns true
        val shellOpening = CompletableDeferred<Unit>()
        coEvery { sshSessionManager.openShell("s1") } coAnswers {
            shellOpening.await()
            SshResult.Success(shell)
        }
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        viewModel.attachToSession("s1", "h1")
        shellOpening.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.tabs.size)
        coVerify(exactly = 1) { sshSessionManager.openShell("s1") }
        assertEquals(1, builtTerminalSessions.size)
        verify(exactly = 1) { sessionHandoff.claim("s1") }
    }

    // ── The session of the list is gone: one connection of the terminal's own ──

    @Test
    fun `attachToSession connects once itself when the session was not on offer any more`() = runTest {
        // The window of the hand-over passed, and the session was released there: nothing to take.
        every { sessionHandoff.claim("s1") } returns false
        connectsTo("s2")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("s2"), state.tabs.map { it.sessionId })
        assertFalse(state.isConnecting)
        coVerify(exactly = 0) { sshSessionManager.getSession("s1") }
        coVerify(exactly = 0) { sshSessionManager.openShell("s1") }
        coVerify(exactly = 1) { connectSession(any()) }
        // Nothing was claimed, so there is nothing to give back.
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `attachToSession releases what it claimed and connects once itself when the session no longer exists`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns null
        connectsTo("s2")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("s2"), state.tabs.map { it.sessionId })
        assertFalse(state.isConnecting)
        // The reference of the session taken over is given back, and its shell was never asked for.
        verify(exactly = 1) { sshSessionManager.release("s1") }
        coVerify(exactly = 0) { sshSessionManager.openShell("s1") }
        coVerify(exactly = 1) { connectSession(any()) }
    }

    @Test
    fun `attachToSession connects once itself when the session is no longer connected`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns session("s1")
        every { sshSessionManager.isConnected("s1") } returns false
        connectsTo("s2")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        assertEquals(listOf("s2"), viewModel.uiState.value.tabs.map { it.sessionId })
        verify(exactly = 1) { sshSessionManager.release("s1") }
        coVerify(exactly = 0) { sshSessionManager.openShell("s1") }
        coVerify(exactly = 1) { connectSession(any()) }
    }

    @Test
    fun `attachToSession does not take over a session of another host`() = runTest {
        val other = host.copy(id = "h2", label = "Host h2")
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns session("s1", forHost = other)
        every { sshSessionManager.isConnected("s1") } returns true
        connectsTo("s2")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val tabs = viewModel.uiState.value.tabs
        assertEquals(listOf("s2"), tabs.map { it.sessionId })
        assertEquals("h1", tabs[0].host.id)
        coVerify(exactly = 0) { sshSessionManager.openShell("s1") }
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    @Test
    fun `attachToSession releases the session and connects once itself when the shell fails to open`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns session("s1")
        every { sshSessionManager.isConnected("s1") } returns true
        coEvery { sshSessionManager.openShell("s1") } returns SshResult.Error(SshErrorCode.UNKNOWN, "channel refused")
        connectsTo("s2")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("s2"), state.tabs.map { it.sessionId })
        // The shell failure of the session taken over is not shown: the connection that follows is.
        assertNull(state.error)
        assertFalse(state.isConnecting)
        verify(exactly = 1) { sshSessionManager.release("s1") }
        coVerify(exactly = 1) { connectSession(any()) }
        assertEquals(1, builtTerminalSessions.size)
    }

    @Test
    fun `the fallback connects at most once, even when it fails`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns null
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "unreachable")
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.isEmpty())
        assertEquals("unreachable", state.error)
        assertFalse(state.isConnecting)
        coVerify(exactly = 1) { connectSession(any()) }
        verify(exactly = 1) { sshSessionManager.release("s1") }
    }

    @Test
    fun `attachToSession on a host deleted meanwhile releases the session and reports it`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { hostRepository.getById("h1") } returns null
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.isEmpty())
        assertNotNull(state.error)
        assertFalse(state.isConnecting)
        verify(exactly = 1) { sshSessionManager.release("s1") }
        coVerify(exactly = 0) { connectSession(any()) }
        coVerify(exactly = 0) { sshSessionManager.openShell(any(), any(), any()) }
    }

    @Test
    fun `leaving the screen while the session is taken over releases it`() = runTest {
        every { sessionHandoff.claim("s1") } returns true
        coEvery { sshSessionManager.getSession("s1") } returns session("s1")
        every { sshSessionManager.isConnected("s1") } returns true
        coEvery { sshSessionManager.openShell("s1") } coAnswers { awaitCancellation() }
        val viewModel = newViewModel()

        viewModel.attachToSession("s1", "h1")
        // The ViewModel of the screen is cleared before the shell is open: no tab yet, so nothing
        // else would ever give this reference back.
        viewModel.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()

        verify(exactly = 1) { sshSessionManager.release("s1") }
        coVerify(exactly = 0) { connectSession(any()) }
        assertTrue(viewModel.uiState.value.tabs.isEmpty())
    }

    // ── A tab gives back its own reference, and only its own ────────────────

    @Test
    fun `closing a taken over tab releases the session once`() = runTest {
        liveSession("s1")
        val viewModel = newViewModel()
        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()

        viewModel.closeTab(0)
        advanceUntilIdle()

        // Released, not disconnected: whoever else holds the session (a tunnel, a transfer) keeps it.
        awaitVerified { verify(exactly = 1) { sshSessionManager.release("s1") } }
        coVerify(exactly = 0) { sshSessionManager.disconnect(any()) }
        assertTrue(viewModel.uiState.value.tabs.isEmpty())
    }

    @Test
    fun `a tab whose shell does not close still gives its reference back`() = runTest {
        liveSession("s1")
        val viewModel = newViewModel()
        viewModel.attachToSession("s1", "h1")
        advanceUntilIdle()
        every { builtTerminalSessions.single().disconnect() } throws IllegalStateException("shell already gone")

        viewModel.closeTab(0)
        advanceUntilIdle()

        awaitVerified { verify(exactly = 1) { sshSessionManager.release("s1") } }
    }

    // ── connectToHost keeps its behaviour ───────────────────────────────────

    @Test
    fun `connectToHost still connects, opens the shell and adds one tab`() = runTest {
        connectsTo("s9")
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("s9"), state.tabs.map { it.sessionId })
        assertEquals(0, state.activeTabIndex)
        assertFalse(state.isConnecting)
        coVerify(exactly = 1) { connectSession(any()) }
        // The connection gave the session with one reference: it is the tab's.
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `connectToHost shows an expired authentication like any other error`() = runTest {
        // An expired vault window used to emit an event that nobody collected: the spinner stopped
        // and the user saw nothing.
        coEvery { connectSession(host) } returns
            SshResult.Error(SshErrorCode.AUTH_EXPIRED, "Authentification vault expirée : veuillez vous ré-authentifier")
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.isEmpty())
        assertEquals("Authentification vault expirée : veuillez vous ré-authentifier", state.error)
        assertFalse(state.isConnecting)
        coVerify(exactly = 0) { sshSessionManager.openShell(any(), any(), any()) }
    }

    @Test
    fun `leaving the screen while the shell of a new connection opens releases the session`() = runTest {
        coEvery { connectSession(host) } returns SshResult.Success(session("s9"))
        coEvery { sshSessionManager.openShell("s9") } coAnswers { awaitCancellation() }
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        // The connection is done and has given its session, with one reference; the ViewModel is
        // cleared before any tab owns it. Nothing else would ever give that reference back.
        viewModel.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()

        verify(exactly = 1) { sshSessionManager.release("s9") }
        assertTrue(viewModel.uiState.value.tabs.isEmpty())
    }

    @Test
    fun `leaving the screen while a connection is made releases nothing it was never given`() = runTest {
        coEvery { connectSession(host) } coAnswers { awaitCancellation() }
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        viewModel.viewModelScope.coroutineContext.cancelChildren()
        advanceUntilIdle()

        // The session of a connection cancelled on the way is released by the connection itself.
        verify(exactly = 0) { sshSessionManager.release(any()) }
    }

    @Test
    fun `connectToHost shows the shell error, adds no tab and releases the session`() = runTest {
        coEvery { connectSession(host) } returns SshResult.Success(session("s9"))
        coEvery { sshSessionManager.openShell("s9") } returns SshResult.Error(SshErrorCode.UNKNOWN, "no shell")
        val viewModel = newViewModel()

        viewModel.connectToHost("h1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.tabs.isEmpty())
        assertEquals("no shell", state.error)
        assertFalse(state.isConnecting)
        // Connected but without a shell, no tab owns this session: its reference goes back, or it
        // would stay open, unseen.
        verify(exactly = 1) { sshSessionManager.release("s9") }
    }
}
