// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.ssh.SshSessionManager
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_LOCAL_PORT_RESERVED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_CANCELLED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_START_STOPPED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_STOP_FAILED_MESSAGE
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsGate
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.LiveTunnelLookup
import fr.techtical.nextsh.domain.usecase.ResumeTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.service.TunnelServiceController
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPathError
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class TunnelViewModelTest {

    @MockK
    private lateinit var tunnelRepository: TunnelRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var tunnelManager: SshTunnelManager

    @MockK
    private lateinit var startTunnel: StartTunnelUseCase

    @MockK
    private lateinit var stopTunnel: StopTunnelUseCase

    @MockK
    private lateinit var autoStartTunnelsUseCase: AutoStartTunnelsUseCase

    @MockK
    private lateinit var browserSessionHolder: BrowserSessionHolder

    @MockK
    private lateinit var tunnelService: TunnelServiceController

    @MockK
    private lateinit var liveTunnels: LiveTunnelLookup

    @MockK
    private lateinit var resumeTunnelsUseCase: ResumeTunnelsUseCase

    private val testDispatcher = UnconfinedTestDispatcher()

    // A singleton in the app, one per test here (JUnit creates a test instance per test).
    private val autoStartGate = AutoStartTunnelsGate()

    // Also a singleton in the app: the notification and the list screen share it.
    private val pendingAuthResume = PendingAuthResume()

    private lateinit var viewModel: TunnelViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { tunnelRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        // The host the tunnels of these tests point at still exists (saveTunnel re-reads it).
        coEvery { hostRepository.getById("host-1") } returns Host(
            id = "host-1",
            label = "Host 1",
            hostname = "host1.example.com",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-1",
        )
        every { tunnelManager.tunnelStates } returns MutableStateFlow<Map<String, TunnelState>>(emptyMap())
        // No tunnel runs unless a test says so: the start decisions read the state of the manager.
        every { tunnelManager.getTunnelState(any()) } returns null
        // The ports of a tunnel can be used and no start was stopped meanwhile, unless a test says so
        // (the start refuses a port that cannot be used, and says nothing of a stopped start).
        every { tunnelManager.refuseInvalidPorts(any()) } returns null
        every { tunnelManager.isStartAbandoned(any()) } returns false
        // No error to clear unless a test says so (saving an edited tunnel asks the manager).
        every { tunnelManager.dismissError(any()) } returns false
        every { browserSessionHolder.activeTunnelId } returns MutableStateFlow(null)
        every { tunnelService.ensureRunning(any()) } returns true

        viewModel = newViewModel()
    }

    /** One ViewModel per navigation entry in the app: the gate is the only thing they share. */
    private fun newViewModel(manager: SshTunnelManager = tunnelManager) = TunnelViewModel(
        tunnelRepository = tunnelRepository,
        hostRepository = hostRepository,
        tunnelManager = manager,
        startTunnel = startTunnel,
        stopTunnel = stopTunnel,
        autoStartTunnelsUseCase = autoStartTunnelsUseCase,
        autoStartGate = autoStartGate,
        browserSessionHolder = browserSessionHolder,
        tunnelService = tunnelService,
        liveTunnels = liveTunnels,
        resumeTunnelsUseCase = resumeTunnelsUseCase,
        pendingAuthResume = pendingAuthResume,
    )

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun makeTunnel(id: String, isFavorite: Boolean) = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
        isFavorite = isFavorite,
    )

    // ── toggleFavorite tests ─────────────────────────────────────────────────

    @Test
    fun `toggleFavorite calls setFavorite with true when tunnel is not a favorite`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coJustRun { tunnelRepository.setFavorite(tunnel.id, true) }

        viewModel.toggleFavorite(tunnel)

        advanceUntilIdle()
        coVerify(exactly = 1) { tunnelRepository.setFavorite(tunnel.id, true) }
    }

    @Test
    fun `toggleFavorite calls setFavorite with false when tunnel is already a favorite`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = true)
        coJustRun { tunnelRepository.setFavorite(tunnel.id, false) }

        viewModel.toggleFavorite(tunnel)

        advanceUntilIdle()
        coVerify(exactly = 1) { tunnelRepository.setFavorite(tunnel.id, false) }
    }

    // ── saveTunnel favorite preservation tests ──────────────────────────────

    @Test
    fun `saveTunnel preserves isFavorite when updating an existing favorite tunnel`() = runTest {
        val existingTunnel = makeTunnel("t1", isFavorite = true)
        coEvery { tunnelRepository.getById("t1") } returns existingTunnel
        coJustRun { tunnelRepository.update(any()) }

        // Load tunnel for edit
        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()

        // Save without changing anything
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.update(capture(configSlot)) }
        assertTrue(configSlot.captured.isFavorite, "isFavorite should be true after update")
    }

    @Test
    fun `saveTunnel sets isFavorite to false for new tunnel`() = runTest {
        coJustRun { tunnelRepository.save(any()) }

        // Fill minimal form for a new tunnel
        viewModel.updateForm {
            copy(
                label = "New Tunnel",
                hostId = "host-1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = "9090",
                remoteHost = "127.0.0.1",
                remotePort = "80",
            )
        }

        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.save(capture(configSlot)) }
        assertFalse(configSlot.captured.isFavorite, "isFavorite should be false for new tunnel")
    }

    // ── Start path of the in-app browser ─────────────────────────────────────

    /** Fills the minimal valid form, then applies [extra] (type, start path...). */
    private fun fillForm(extra: TunnelFormState.() -> TunnelFormState = { this }) {
        viewModel.updateForm {
            copy(
                label = "New Tunnel",
                hostId = "host-1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = "9090",
                remoteHost = "127.0.0.1",
                remotePort = "80",
            ).extra()
        }
    }

    private fun TestScope.saveAndCapture(): TunnelConfig {
        coJustRun { tunnelRepository.save(any()) }
        viewModel.saveTunnel()
        advanceUntilIdle()
        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.save(capture(configSlot)) }
        return configSlot.captured
    }

    @Test
    fun `a new tunnel with an empty start path is saved with the site root`() = runTest {
        fillForm()

        assertEquals("/", saveAndCapture().browserPath)
    }

    @Test
    fun `a blank start path is saved as the site root`() = runTest {
        fillForm { copy(browserPath = "   ") }

        assertEquals("/", saveAndCapture().browserPath)
    }

    @Test
    fun `saveTunnel prepends the missing slash of the start path`() = runTest {
        fillForm { copy(browserPath = "admin") }

        assertEquals("/admin", saveAndCapture().browserPath)
    }

    @Test
    fun `saveTunnel prepends the slash to a query-only start path`() = runTest {
        fillForm { copy(browserPath = "?tab=2") }

        assertEquals("/?tab=2", saveAndCapture().browserPath)
    }

    @Test
    fun `saveTunnel trims the start path and keeps query and fragment`() = runTest {
        fillForm { copy(browserPath = "  /admin/users?tab=2&sort=asc#top \n") }

        assertEquals("/admin/users?tab=2&sort=asc#top", saveAndCapture().browserPath)
    }

    @Test
    fun `saveTunnel keeps a valid percent encoding in the start path`() = runTest {
        fillForm { copy(browserPath = "/a%20b/%E2%82%AC") }

        assertEquals("/a%20b/%E2%82%AC", saveAndCapture().browserPath)
    }

    @Test
    fun `a valid start path leaves the form valid and without error`() {
        fillForm { copy(browserPath = "/admin") }

        assertNull(viewModel.formState.value.browserPathError)
        assertTrue(viewModel.formState.value.isValid)
    }

    @Test
    fun `saveTunnel stores the site root for a remote forward even with a leftover path`() = runTest {
        fillForm { copy(type = TunnelType.REMOTE_FORWARD, browserPath = "/admin") }

        assertEquals("/", saveAndCapture().browserPath)
    }

    @Test
    fun `saveTunnel stores the site root for a SOCKS5 proxy even with a leftover path`() = runTest {
        fillForm { copy(type = TunnelType.DYNAMIC_SOCKS5, browserPath = "/admin?token=x") }

        assertEquals("/", saveAndCapture().browserPath)
    }

    @Test
    fun `an invalid leftover path does not block saving a non-local forward`() = runTest {
        // The field is hidden for these types: an error the user cannot see must not disable Save.
        fillForm { copy(type = TunnelType.DYNAMIC_SOCKS5, browserPath = "/has space") }

        assertNull(viewModel.formState.value.browserPathError)
        assertTrue(viewModel.formState.value.isValid)
        assertEquals("/", saveAndCapture().browserPath)
    }

    @Test
    fun `switching back to local forward brings the typed path back`() = runTest {
        fillForm { copy(type = TunnelType.REMOTE_FORWARD, browserPath = "/admin") }
        viewModel.updateForm { copy(type = TunnelType.LOCAL_FORWARD) }

        assertEquals("/admin", saveAndCapture().browserPath)
    }

    private fun assertRejected(path: String, expected: TunnelBrowserPathError) = runTest {
        fillForm { copy(browserPath = path) }

        val form = viewModel.formState.value
        assertEquals(expected, form.browserPathError, "error for the path under test")
        assertFalse(form.isValid, "an invalid start path must disable Save")

        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 0) { tunnelRepository.save(any()) }
        coVerify(exactly = 0) { tunnelRepository.update(any()) }
    }

    @Test
    fun `a start path longer than the maximum blocks the save`() {
        assertRejected("/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH), TunnelBrowserPathError.TOO_LONG)
    }

    @Test
    fun `a start path of exactly the maximum length is accepted`() {
        fillForm { copy(browserPath = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH - 1)) }

        assertNull(viewModel.formState.value.browserPathError)
        assertTrue(viewModel.formState.value.isValid)
    }

    @Test
    fun `a space in the start path blocks the save`() {
        assertRejected("/admin panel", TunnelBrowserPathError.INVALID_CHARACTER)
    }

    @Test
    fun `an accent in the start path blocks the save`() {
        assertRejected("/café", TunnelBrowserPathError.INVALID_CHARACTER)
    }

    @Test
    fun `a backslash in the start path blocks the save`() {
        assertRejected("/a\\b", TunnelBrowserPathError.INVALID_CHARACTER)
    }

    @Test
    fun `a dangling percent in the start path blocks the save`() {
        assertRejected("/a%2", TunnelBrowserPathError.INVALID_PERCENT_ENCODING)
    }

    @Test
    fun `a percent followed by non hexadecimal characters blocks the save`() {
        assertRejected("/a%zz", TunnelBrowserPathError.INVALID_PERCENT_ENCODING)
    }

    @Test
    fun `two fragment markers in the start path block the save`() {
        assertRejected("/a#b#c", TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS)
    }

    @Test
    fun `fixing an invalid start path re-enables the form`() {
        fillForm { copy(browserPath = "/a b") }
        assertFalse(viewModel.formState.value.isValid)

        viewModel.updateForm { copy(browserPath = "/a-b") }

        assertNull(viewModel.formState.value.browserPathError)
        assertTrue(viewModel.formState.value.isValid)
    }

    @Test
    fun `every start path error maps to its own message`() {
        val messages = TunnelBrowserPathError.entries.associateWith { it.messageRes() }

        assertTrue(messages.values.none { it == 0 }, "every error needs a message resource: $messages")
        assertEquals(
            messages.size,
            messages.values.toSet().size,
            "each error must have a distinct message: $messages",
        )
        assertEquals(R.string.error_tunnel_browser_path_too_long, TunnelBrowserPathError.TOO_LONG.messageRes())
        assertEquals(
            R.string.error_tunnel_browser_path_missing_slash,
            TunnelBrowserPathError.MISSING_LEADING_SLASH.messageRes(),
        )
        assertEquals(
            R.string.error_tunnel_browser_path_invalid_character,
            TunnelBrowserPathError.INVALID_CHARACTER.messageRes(),
        )
        assertEquals(
            R.string.error_tunnel_browser_path_invalid_percent,
            TunnelBrowserPathError.INVALID_PERCENT_ENCODING.messageRes(),
        )
        assertEquals(
            R.string.error_tunnel_browser_path_multiple_fragments,
            TunnelBrowserPathError.MULTIPLE_FRAGMENT_MARKERS.messageRes(),
        )
    }

    @Test
    fun `loadTunnelForEdit loads the stored start path`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
            .copy(browserPath = "/admin?tab=2")

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()

        assertEquals("/admin?tab=2", viewModel.formState.value.browserPath)
        assertNull(viewModel.formState.value.browserPathError)
    }

    @Test
    fun `loadTunnelForEdit shows an empty field for a tunnel stored at the site root`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()

        assertEquals("", viewModel.formState.value.browserPath)
        assertNull(viewModel.formState.value.browserPathError)
        assertTrue(viewModel.formState.value.isValid)
    }

    @Test
    fun `saving a tunnel stored at the site root keeps the site root`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
        coJustRun { tunnelRepository.update(any()) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.update(capture(configSlot)) }
        assertEquals("/", configSlot.captured.browserPath)
    }

    @Test
    fun `saving an edited tunnel without touching the path keeps it`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
            .copy(browserPath = "/admin?tab=2")
        coJustRun { tunnelRepository.update(any()) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.update(capture(configSlot)) }
        assertEquals("/admin?tab=2", configSlot.captured.browserPath)
    }

    @Test
    fun `an edited tunnel can change its start path`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
            .copy(browserPath = "/admin")
        coJustRun { tunnelRepository.update(any()) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.updateForm { copy(browserPath = "grafana") }
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.update(capture(configSlot)) }
        assertEquals("/grafana", configSlot.captured.browserPath)
    }

    @Test
    fun `an edited tunnel can be reset to the site root`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
            .copy(browserPath = "/admin")
        coJustRun { tunnelRepository.update(any()) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.updateForm { copy(browserPath = "") }
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.update(capture(configSlot)) }
        assertEquals("/", configSlot.captured.browserPath)
    }

    @Test
    fun `a stored start path that fails validation is flagged and blocks the save`() = runTest {
        // It can arrive from a sync peer or an old backup: the user must fix it, not save it as is.
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
            .copy(browserPath = "/bad path")

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()

        assertEquals(TunnelBrowserPathError.INVALID_CHARACTER, viewModel.formState.value.browserPathError)
        assertFalse(viewModel.formState.value.isValid)
    }

    @Test
    fun `resetForm clears the start path`() {
        fillForm { copy(browserPath = "/admin") }

        viewModel.resetForm()

        assertEquals("", viewModel.formState.value.browserPath)
    }

    // ── Editing a tunnel (or its host) deleted meanwhile ────────────────────

    @Test
    fun `saveTunnel on a tunnel deleted meanwhile writes nothing and reports it`() = runTest {
        // The form loads the tunnel, then the stored row is gone (a sync deleted it) when saving.
        coEvery { tunnelRepository.getById("t1") } returnsMany listOf(makeTunnel("t1", isFavorite = false), null)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelNoLongerExists), events)
        coVerify(exactly = 0) { tunnelRepository.update(any()) }
        coVerify(exactly = 0) { tunnelRepository.save(any()) }
        verify(exactly = 0) { tunnelManager.dismissError(any()) } // nothing was saved, no error cleared
        collector.cancel()
    }

    @Test
    fun `saveTunnel whose host was deleted meanwhile writes nothing and reports it`() = runTest {
        coEvery { hostRepository.getById("host-1") } returns null
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        fillForm()

        viewModel.saveTunnel()
        advanceUntilIdle()

        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelHostNoLongerExists), events)
        coVerify(exactly = 0) { tunnelRepository.update(any()) }
        coVerify(exactly = 0) { tunnelRepository.save(any()) }
        verify(exactly = 0) { tunnelManager.dismissError(any()) } // nothing was saved, no error cleared
        collector.cancel()
    }

    @Test
    fun `a save of an edited tunnel whose host was deleted meanwhile clears no error`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
        coEvery { hostRepository.getById("host-1") } returns null
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelHostNoLongerExists), events)
        coVerify(exactly = 0) { tunnelRepository.update(any()) }
        verify(exactly = 0) { tunnelManager.dismissError(any()) }
        collector.cancel()
    }

    @Test
    fun `saveTunnel on an existing tunnel with its host reports TunnelSaved`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
        coJustRun { tunnelRepository.update(any()) }
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelSaved), events)
        collector.cancel()
    }

    // ── Clearing the error of a card: the cross, and the save of an edited tunnel ──

    @Test
    fun `the cross of an error banner asks the manager to clear that error and says nothing`() = runTest {
        every { tunnelManager.dismissError("t1") } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.dismissTunnelError("t1")
        advanceUntilIdle()

        verify(exactly = 1) { tunnelManager.dismissError("t1") }
        // No stop of the tunnel (it could be a tunnel started again meanwhile), no "stopped" message, no error.
        coVerify(exactly = 0) { stopTunnel(any()) }
        assertTrue(events.isEmpty(), "$events")
        collector.cancel()
    }

    @Test
    fun `the cross says nothing either when the manager found the tunnel started again`() = runTest {
        every { tunnelManager.dismissError("t1") } returns false
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.dismissTunnelError("t1")
        advanceUntilIdle()

        verify(exactly = 1) { tunnelManager.dismissError("t1") }
        coVerify(exactly = 0) { stopTunnel(any()) }
        assertTrue(events.isEmpty(), "$events")
        collector.cancel()
    }

    @Test
    fun `saving an edited tunnel asks the manager to clear its error, after the write, and says only that it is saved`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
        coJustRun { tunnelRepository.update(any()) }
        every { tunnelManager.dismissError("t1") } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerifyOrder {
            tunnelRepository.update(any())
            tunnelManager.dismissError("t1")
        }
        verify(exactly = 1) { tunnelManager.dismissError(any()) }
        coVerify(exactly = 0) { stopTunnel(any()) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelSaved), events)
        collector.cancel()
    }

    @Test
    fun `saving a new tunnel clears no error`() = runTest {
        fillForm()

        saveAndCapture()

        verify(exactly = 0) { tunnelManager.dismissError(any()) }
    }

    /** A ViewModel on a real manager, to see what saving the tunnel [tunnel] does to its card. */
    private fun TestScope.editAndSave(manager: SshTunnelManager, tunnel: TunnelConfig) {
        val onRealManager = newViewModel(manager)
        coEvery { tunnelRepository.getById(tunnel.id) } returns tunnel
        coJustRun { tunnelRepository.update(any()) }
        onRealManager.loadTunnelForEdit(tunnel.id)
        advanceUntilIdle()
        onRealManager.saveTunnel()
        advanceUntilIdle()
        coVerify(exactly = 1) { tunnelRepository.update(any()) }
    }

    private fun realManager() = SshTunnelManager(sessionManager = mockk<SshSessionManager>(relaxed = true))

    @Test
    fun `saving an edited tunnel in error puts its card back to stopped and out of the reconnection`() = runTest {
        val manager = realManager()
        val tunnel = makeTunnel("t1", isFavorite = false)
        manager.markStarting(tunnel)
        manager.markError(tunnel, "Authentication failed")
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("t1")?.status)

        editAndSave(manager, tunnel)

        assertNull(manager.getTunnelState("t1"), "back to stopped")
        assertTrue(manager.getTunnelsForReconnect().isEmpty(), "and the reconnection does not take it back")
    }

    @Test
    fun `saving an edited tunnel that runs does not touch it`() = runTest {
        val manager = realManager()
        val tunnel = makeTunnel("t1", isFavorite = false)
        manager.markStarting(tunnel)
        manager.injectActiveForTest(tunnel, sessionId = "s1")

        editAndSave(manager, tunnel)

        assertEquals(TunnelStatus.ACTIVE, manager.getTunnelState("t1")?.status)
        assertTrue(manager.activeTunnels.value.containsKey("t1"))
    }

    @Test
    fun `saving an edited tunnel that is starting does not touch it`() = runTest {
        val manager = realManager()
        val tunnel = makeTunnel("t1", isFavorite = false)
        manager.markStarting(tunnel)

        editAndSave(manager, tunnel)

        assertEquals(TunnelStatus.STARTING, manager.getTunnelState("t1")?.status)
        assertFalse(manager.isStartAbandoned(tunnel), "its start was not invalidated")
    }

    @Test
    fun `saving an edited tunnel that is reconnecting does not touch it`() = runTest {
        val manager = realManager()
        val tunnel = makeTunnel("t1", isFavorite = false)
        manager.markStarting(tunnel)
        manager.markError(tunnel, "Connexion SSH perdue")
        manager.updateTunnelStatus("t1", TunnelStatus.RECONNECTING)

        editAndSave(manager, tunnel)

        assertEquals(TunnelStatus.RECONNECTING, manager.getTunnelState("t1")?.status)
        assertTrue(manager.isAwaitingReconnect("t1"))
    }

    @Test
    fun `saving a new tunnel leaves the error of another tunnel alone`() = runTest {
        val manager = realManager()
        val failed = makeTunnel("failed", isFavorite = false)
        manager.markStarting(failed)
        manager.markError(failed, "Authentication failed")
        val onRealManager = newViewModel(manager)
        onRealManager.updateForm {
            copy(
                label = "New Tunnel",
                hostId = "host-1",
                type = TunnelType.LOCAL_FORWARD,
                localPort = "9090",
                remoteHost = "127.0.0.1",
                remotePort = "80",
            )
        }
        coJustRun { tunnelRepository.save(any()) }

        onRealManager.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 1) { tunnelRepository.save(any()) }
        assertEquals(TunnelStatus.ERROR, manager.getTunnelState("failed")?.status)
    }

    // ── The form after a save: the screen leaves on TunnelSaved, it must not be seen resetting ──

    private val optionsOffTheDefaults: TunnelFormState.() -> TunnelFormState = {
        // Every option at the opposite of its default: a reset would flip all three.
        copy(autoStart = true, openBrowserOnConnect = true, keepAliveAfterBrowserClose = false)
    }

    @Test
    fun `saveTunnel leaves a new tunnel form as typed instead of flipping the options to their defaults`() = runTest {
        fillForm(optionsOffTheDefaults)
        val typed = viewModel.formState.value
        coJustRun { tunnelRepository.save(any()) }

        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 1) { tunnelRepository.save(any()) }
        assertEquals(typed, viewModel.formState.value)
        assertTrue(viewModel.formState.value.autoStart)
        assertTrue(viewModel.formState.value.openBrowserOnConnect)
        assertFalse(viewModel.formState.value.keepAliveAfterBrowserClose)
    }

    @Test
    fun `saveTunnel leaves an edited tunnel form as loaded`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false).copy(
            autoStart = true,
            openBrowserOnConnect = true,
            keepAliveAfterBrowserClose = false,
        )
        coJustRun { tunnelRepository.update(any()) }
        viewModel.loadTunnelForEdit("t1")
        advanceUntilIdle()
        val loaded = viewModel.formState.value

        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 1) { tunnelRepository.update(any()) }
        assertEquals(loaded, viewModel.formState.value)
        assertEquals("t1", viewModel.formState.value.id)
    }

    @Test
    fun `opening the screen for a new tunnel after a save starts from the defaults`() = runTest {
        fillForm(optionsOffTheDefaults)
        coJustRun { tunnelRepository.save(any()) }
        viewModel.saveTunnel()
        advanceUntilIdle()

        // What TunnelConfigScreen does on entry for a new tunnel.
        viewModel.resetForm()

        assertEquals(TunnelFormState(), viewModel.formState.value)
    }

    @Test
    fun `opening the screen to edit a tunnel after a save loads the stored tunnel`() = runTest {
        fillForm(optionsOffTheDefaults)
        coJustRun { tunnelRepository.save(any()) }
        viewModel.saveTunnel()
        advanceUntilIdle()
        coEvery { tunnelRepository.getById("t2") } returns makeTunnel("t2", isFavorite = false)

        // What TunnelConfigScreen does on entry for an existing tunnel.
        viewModel.loadTunnelForEdit("t2")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertEquals("t2", form.id)
        assertEquals("Tunnel t2", form.label)
        assertFalse(form.autoStart)
        assertFalse(form.openBrowserOnConnect)
        assertTrue(form.keepAliveAfterBrowserClose)
    }

    @Test
    fun `a second tap on Save while the screen is leaving saves once and reports once`() = runTest {
        // The form stays valid after a save, so the guard is what stops a second new tunnel.
        coJustRun { tunnelRepository.save(any()) }
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        fillForm()

        viewModel.saveTunnel()
        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 1) { tunnelRepository.save(any()) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelSaved), events)
        collector.cancel()
    }

    @Test
    fun `a save refused because the host is gone can be tried again`() = runTest {
        val host = Host(
            id = "host-1",
            label = "Host 1",
            hostname = "host1.example.com",
            username = "user",
            authType = AuthType.PASSWORD,
            credentialId = "cred-1",
        )
        coEvery { hostRepository.getById("host-1") } returnsMany listOf(null, host)
        coJustRun { tunnelRepository.save(any()) }
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        fillForm()

        viewModel.saveTunnel()
        advanceUntilIdle()
        viewModel.saveTunnel()
        advanceUntilIdle()

        assertEquals(
            listOf<TunnelEvent>(TunnelEvent.TunnelHostNoLongerExists, TunnelEvent.TunnelSaved),
            events,
        )
        coVerify(exactly = 1) { tunnelRepository.save(any()) }
        collector.cancel()
    }

    @Test
    fun `a new tunnel can be saved after resetting the form of a saved one`() = runTest {
        coJustRun { tunnelRepository.save(any()) }
        fillForm()
        viewModel.saveTunnel()
        advanceUntilIdle()

        viewModel.resetForm()
        fillForm()
        viewModel.saveTunnel()
        advanceUntilIdle()

        val saved = mutableListOf<TunnelConfig>()
        coVerify(exactly = 2) { tunnelRepository.save(capture(saved)) }
        assertTrue(saved[0].id != saved[1].id, "two entries, two tunnels")
    }

    // ── The save guard is released whatever ends the save ───────────────────

    @Test
    fun `the save guard is released when the repository throws, the next tap saves`() {
        // The exception of a failing write is not caught by the ViewModel (it reaches the
        // coroutine exception handler, which runTest reports at its end): what matters here is
        // that the guard does not stay shut behind it.
        val failure = assertThrows(IllegalStateException::class.java) {
            runTest {
                coEvery { tunnelRepository.save(any()) } throws IllegalStateException("database is locked") andThen Unit
                val events = mutableListOf<TunnelEvent>()
                val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
                fillForm()

                viewModel.saveTunnel()
                advanceUntilIdle()
                assertTrue(events.isEmpty(), "a failed write reports nothing as saved")

                viewModel.saveTunnel()
                advanceUntilIdle()

                coVerify(exactly = 2) { tunnelRepository.save(any()) }
                assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelSaved), events)
                collector.cancel()
            }
        }
        assertEquals("database is locked", failure.message)
    }

    @Test
    fun `a failed write of an edited tunnel clears no error, and the next tap saves and clears it`() {
        // Same as above for an edit: the error is cleared only once the write went through. The
        // exception of the failing write reaches the coroutine exception handler, which runTest
        // reports at its end.
        val failure = assertThrows(IllegalStateException::class.java) {
            runTest {
                coEvery { tunnelRepository.getById("t1") } returns makeTunnel("t1", isFavorite = false)
                coEvery { tunnelRepository.update(any()) } throws IllegalStateException("database is locked") andThen Unit
                every { tunnelManager.dismissError("t1") } returns true
                val events = mutableListOf<TunnelEvent>()
                val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
                viewModel.loadTunnelForEdit("t1")
                advanceUntilIdle()

                viewModel.saveTunnel()
                advanceUntilIdle()
                assertTrue(events.isEmpty(), "a failed write reports nothing as saved")
                verify(exactly = 0) { tunnelManager.dismissError(any()) }

                // The guard was released: the next tap saves, and then the error goes.
                viewModel.saveTunnel()
                advanceUntilIdle()

                coVerify(exactly = 2) { tunnelRepository.update(any()) }
                verify(exactly = 1) { tunnelManager.dismissError("t1") }
                assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelSaved), events)
                collector.cancel()
            }
        }
        assertEquals("database is locked", failure.message)
    }

    @Test
    fun `the save guard is released when the save is cancelled, the next tap saves`() = runTest {
        // The write is in flight when the scope is cancelled (screen left, ViewModel cleared).
        coEvery { tunnelRepository.save(any()) } coAnswers { awaitCancellation() }
        fillForm()
        viewModel.saveTunnel()
        coVerify(exactly = 1) { tunnelRepository.save(any()) }

        viewModel.viewModelScope.coroutineContext[Job]!!.cancelChildren()
        advanceUntilIdle()

        coEvery { tunnelRepository.save(any()) } just Runs
        viewModel.saveTunnel()
        advanceUntilIdle()

        coVerify(exactly = 2) { tunnelRepository.save(any()) }
    }

    // ── deleteTunnel: every state the manager keeps is stopped, ERROR included ──────

    private fun statesOf(vararg pairs: Pair<TunnelConfig, TunnelStatus>) =
        MutableStateFlow(pairs.associate { (config, status) -> config.id to TunnelState(config, status) })

    @Test
    fun `deleteTunnel stops a tunnel in error before deleting it`() = runTest {
        // An ERROR state is a reconnect candidate: left behind it would be restarted by the next
        // network event with no card to stop it, and would keep the foreground service alive.
        val tunnel = makeTunnel("t1", isFavorite = false)
        every { tunnelManager.tunnelStates } returns statesOf(tunnel to TunnelStatus.ERROR)
        coEvery { stopTunnel("t1") } returns SshResult.Success(Unit)
        coJustRun { tunnelRepository.delete("t1") }

        viewModel.deleteTunnel("t1")
        advanceUntilIdle()

        coVerifyOrder {
            stopTunnel("t1")
            tunnelRepository.delete("t1")
        }
    }

    @Test
    fun `deleteTunnel stops a running tunnel before deleting it`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        every { tunnelManager.tunnelStates } returns statesOf(tunnel to TunnelStatus.ACTIVE)
        coEvery { stopTunnel("t1") } returns SshResult.Success(Unit)
        coJustRun { tunnelRepository.delete("t1") }

        viewModel.deleteTunnel("t1")
        advanceUntilIdle()

        coVerifyOrder {
            stopTunnel("t1")
            tunnelRepository.delete("t1")
        }
    }

    @Test
    fun `deleteTunnel does not stop a tunnel the manager does not know`() = runTest {
        every { tunnelManager.tunnelStates } returns statesOf(makeTunnel("other", false) to TunnelStatus.ACTIVE)
        coJustRun { tunnelRepository.delete("t1") }

        viewModel.deleteTunnel("t1")
        advanceUntilIdle()

        coVerify(exactly = 0) { stopTunnel(any()) }
        coVerify(exactly = 1) { tunnelRepository.delete("t1") }
    }

    @Test
    fun `deleteTunnel deletes the tunnel even when the stop fails`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        every { tunnelManager.tunnelStates } returns statesOf(tunnel to TunnelStatus.ERROR)
        coEvery { stopTunnel("t1") } returns SshResult.Error(SshErrorCode.UNKNOWN, "teardown failed")
        coJustRun { tunnelRepository.delete("t1") }

        viewModel.deleteTunnel("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { tunnelRepository.delete("t1") }
    }

    // ── Start guard: a tunnel or host deleted meanwhile is never started ────────────

    @Test
    fun `toggleTunnel refuses to start a tunnel whose row or host is gone`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns null
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelUnavailable), events)
        collector.cancel()
    }

    @Test
    fun `toggleTunnel starts the stored row, not the copy shown on the card`() = runTest {
        val onCard = makeTunnel("t1", isFavorite = false)
        val stored = onCard.copy(localPort = 9191)
        coEvery { liveTunnels.find("t1") } returns stored
        coEvery { startTunnel(stored) } returns SshResult.Success(Unit)

        viewModel.toggleTunnel(onCard)
        advanceUntilIdle()

        coVerify(exactly = 1) { startTunnel(stored) }
        coVerify(exactly = 0) { startTunnel(onCard) }
    }

    @Test
    fun `toggleTunnel stops a running tunnel without consulting the start guard`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        // Only the manager knows it runs: the UI mirror of its states is still empty, as it can be
        // just after a start.
        every { tunnelManager.getTunnelState("t1") } returns TunnelState(tunnel, TunnelStatus.ACTIVE)
        coEvery { stopTunnel("t1") } returns SshResult.Success(Unit)

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        coVerify(exactly = 1) { stopTunnel("t1") }
        coVerify(exactly = 0) { liveTunnels.find(any()) }
        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `autoStartTunnelById leaves alone a tunnel the manager runs, whatever the UI mirror says`() = runTest {
        // The screen launches this start together with the auto-start: the mirror may not have
        // caught up with the tunnel the auto-start has just put in STARTING.
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        for (status in listOf(TunnelStatus.STARTING, TunnelStatus.ACTIVE, TunnelStatus.RECONNECTING)) {
            every { tunnelManager.getTunnelState("t1") } returns TunnelState(tunnel, status)

            viewModel.autoStartTunnelById("t1")
            advanceUntilIdle()
        }

        coVerify(exactly = 0) { startTunnel(any()) }
    }

    @Test
    fun `autoStartTunnelById starts a tunnel the manager holds in error`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        every { tunnelManager.getTunnelState("t1") } returns TunnelState(tunnel, TunnelStatus.ERROR, errorMessage = "failed")
        coEvery { startTunnel(tunnel) } returns SshResult.Success(Unit)

        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { startTunnel(tunnel) }
    }

    @Test
    fun `toggleTunnel keeps a start cancelled at the key prompt out of the automatic reconnection`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns cancelled
        every { tunnelManager.holdReconnectIfNonRetryable("t1", cancelled) } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        verify(exactly = 1) { tunnelManager.holdReconnectIfNonRetryable("t1", cancelled) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error("Connexion annulée")), events)
        collector.cancel()
    }

    @Test
    fun `toggleTunnel hands a host key mismatch to the manager to hold like an authentication failure`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        val mismatch = SshResult.Error(SshErrorCode.HOST_KEY_MISMATCH, "Vérification de la clé hôte échouée")
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns mismatch
        every { tunnelManager.holdReconnectIfNonRetryable("t1", mismatch) } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        verify(exactly = 1) { tunnelManager.holdReconnectIfNonRetryable("t1", mismatch) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error("Vérification de la clé hôte échouée")), events)
        collector.cancel()
    }

    @Test
    fun `autoStartTunnelById keeps a start cancelled at the key prompt out of the automatic reconnection`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns cancelled
        every { tunnelManager.holdReconnectIfNonRetryable("t1", cancelled) } returns true

        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        verify(exactly = 1) { tunnelManager.holdReconnectIfNonRetryable("t1", cancelled) }
    }

    @Test
    fun `toggleTunnel cancelled mid-start leaves the tunnel in error, held, and keeps the cancellation`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } coAnswers { awaitCancellation() }
        every { tunnelManager.markStartCancelled(any(), any(), any()) } just Runs

        viewModel.toggleTunnel(tunnel) // runs on the unconfined test dispatcher, up to the connection
        coVerify(exactly = 1) { startTunnel(tunnel) }
        verify(exactly = 0) { tunnelManager.markStartCancelled(any(), any(), any()) }
        // The screen is left: the scope of the ViewModel ends the start.
        viewModel.viewModelScope.coroutineContext[Job]!!.cancelChildren()
        advanceUntilIdle()

        // With the very row that was started: it is what the state carries while that start is on.
        verify(exactly = 1) { tunnelManager.markStartCancelled(match { it === tunnel }, TUNNEL_START_CANCELLED_MESSAGE, true) }
    }

    @Test
    fun `autoStartTunnelById cancelled mid-start leaves the tunnel in error, held`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } coAnswers { awaitCancellation() }
        every { tunnelManager.markStartCancelled(any(), any(), any()) } just Runs

        viewModel.autoStartTunnelById("t1")
        coVerify(exactly = 1) { startTunnel(tunnel) }
        viewModel.viewModelScope.coroutineContext[Job]!!.cancelChildren()
        advanceUntilIdle()

        // With the very row that was started: it is what the state carries while that start is on.
        verify(exactly = 1) { tunnelManager.markStartCancelled(match { it === tunnel }, TUNNEL_START_CANCELLED_MESSAGE, true) }
    }

    @Test
    fun `autoStartTunnelById refuses a tunnel whose row or host is gone`() = runTest {
        coEvery { liveTunnels.find("t1") } returns null
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelUnavailable), events)
        collector.cancel()
    }

    @Test
    fun `autoStartTunnelById starts a live tunnel`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns SshResult.Success(Unit)

        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) { startTunnel(tunnel) }
    }

    // ── autoStartTunnels: one error event per distinct failure of a host ────────────

    private fun TestScope.autoStartEvents(
        tunnels: List<TunnelConfig>,
        results: Map<String, SshResult<Unit>>,
    ): List<TunnelEvent> {
        tunnels.forEach { coEvery { tunnelRepository.getById(it.id) } returns it }
        coEvery { autoStartTunnelsUseCase() } returns tunnels.map { it.id to results.getValue(it.id) }
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.autoStartTunnels()
        advanceUntilIdle()

        collector.cancel()
        return events
    }

    @Test
    fun `autoStartTunnels runs once per process, a second view model starts nothing again`() = runTest {
        val tunnel = makeTunnel("a", isFavorite = false)
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        coEvery { tunnelRepository.getById("a") } returns tunnel
        coEvery { autoStartTunnelsUseCase() } returns listOf("a" to cancelled)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        val nextVisit = newViewModel()
        val nextVisitEvents = mutableListOf<TunnelEvent>()
        val nextCollector = launch(UnconfinedTestDispatcher(testScheduler)) { nextVisit.events.toList(nextVisitEvents) }

        viewModel.autoStartTunnels()
        advanceUntilIdle()
        // The user leaves the list and comes back: a new ViewModel, the same process.
        nextVisit.autoStartTunnels()
        advanceUntilIdle()

        coVerify(exactly = 1) { autoStartTunnelsUseCase() }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error("Connexion annulée")), events)
        assertTrue(nextVisitEvents.isEmpty(), "the cancelled prompt is not asked again at the next visit")
        collector.cancel()
        nextCollector.cancel()
    }

    @Test
    fun `autoStartTunnels asked twice on one view model starts once`() = runTest {
        coEvery { autoStartTunnelsUseCase() } returns emptyList()

        viewModel.autoStartTunnels()
        viewModel.autoStartTunnels()
        advanceUntilIdle()

        coVerify(exactly = 1) { autoStartTunnelsUseCase() }
    }

    @Test
    fun `autoStartTunnels reports once the failure shared by the tunnels of one host`() = runTest {
        val a = makeTunnel("a", isFavorite = false)
        val b = makeTunnel("b", isFavorite = false)
        val c = makeTunnel("c", isFavorite = false)
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")

        val events = autoStartEvents(listOf(a, b, c), mapOf("a" to cancelled, "b" to cancelled, "c" to cancelled))

        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error("Connexion annulée")), events)
    }

    @Test
    fun `autoStartTunnels reports the same message once per host`() = runTest {
        val a = makeTunnel("a", isFavorite = false)
        val b = makeTunnel("b", isFavorite = false)
        val other = makeTunnel("other", isFavorite = false).copy(hostId = "host-2")
        val otherToo = makeTunnel("other-too", isFavorite = false).copy(hostId = "host-2")
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")

        val events = autoStartEvents(
            listOf(a, other, b, otherToo),
            mapOf("a" to cancelled, "b" to cancelled, "other" to cancelled, "other-too" to cancelled),
        )

        assertEquals(
            listOf<TunnelEvent>(TunnelEvent.Error("Connexion annulée"), TunnelEvent.Error("Connexion annulée")),
            events,
            "one for each of the two hosts",
        )
    }

    @Test
    fun `autoStartTunnels reports each distinct message of one host`() = runTest {
        val a = makeTunnel("a", isFavorite = false)
        val b = makeTunnel("b", isFavorite = false)
        val c = makeTunnel("c", isFavorite = false)
        val unreachable = SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "Hôte injoignable")
        val busy = SshResult.Error(SshErrorCode.PORT_IN_USE, "Port déjà utilisé")

        val events = autoStartEvents(listOf(a, b, c), mapOf("a" to unreachable, "b" to busy, "c" to unreachable))

        assertEquals(
            listOf<TunnelEvent>(TunnelEvent.Error("Hôte injoignable"), TunnelEvent.Error("Port déjà utilisé")),
            events,
        )
    }

    @Test
    fun `autoStartTunnels still announces every tunnel that started, among the errors`() = runTest {
        val a = makeTunnel("a", isFavorite = false)
        val b = makeTunnel("b", isFavorite = false)
        val ok = makeTunnel("ok", isFavorite = false).copy(hostId = "host-2")
        val refused = SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification refusée")

        val events = autoStartEvents(
            listOf(a, b, ok),
            mapOf("a" to refused, "b" to refused, "ok" to SshResult.Success(Unit)),
        )

        assertEquals(
            listOf<TunnelEvent>(TunnelEvent.Error("Authentification refusée"), TunnelEvent.TunnelStarted("Tunnel ok")),
            events,
        )
    }

    @Test
    fun `autoStartTunnels does not merge the failures of tunnels that are gone`() = runTest {
        val refused = SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification refusée")
        coEvery { tunnelRepository.getById("gone-1") } returns null
        coEvery { tunnelRepository.getById("gone-2") } returns null
        coEvery { autoStartTunnelsUseCase() } returns listOf("gone-1" to refused, "gone-2" to refused)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.autoStartTunnels()
        advanceUntilIdle()

        // No host to tell they share a failure: nothing is swallowed.
        assertEquals(2, events.size)
        collector.cancel()
    }

    @Test
    fun `autoStartTunnels survives a lookup that throws and keys the failure on the tunnel`() = runTest {
        val refused = SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification refusée")
        coEvery { tunnelRepository.getById(any()) } throws IllegalStateException("database is locked")
        coEvery { autoStartTunnelsUseCase() } returns listOf("a" to refused, "b" to refused)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.autoStartTunnels()
        advanceUntilIdle()

        // Nothing crashed (runTest would report an uncaught exception), and no failure is lost.
        assertEquals(
            listOf<TunnelEvent>(
                TunnelEvent.Error("Authentification refusée"),
                TunnelEvent.Error("Authentification refusée"),
            ),
            events,
        )
        collector.cancel()
    }

    @Test
    fun `autoStartTunnels does not swallow a cancellation raised by the lookup`() {
        val refused = SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentification refusée")
        coEvery { tunnelRepository.getById(any()) } throws kotlinx.coroutines.CancellationException("scope ended")
        coEvery { autoStartTunnelsUseCase() } returns listOf("a" to refused, "b" to refused)
        val events = mutableListOf<TunnelEvent>()

        runTest {
            val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
            viewModel.autoStartTunnels()
            advanceUntilIdle()
            collector.cancel()
        }

        assertTrue(events.isEmpty(), "the cancelled run reports nothing: $events")
    }

    // ── resumeAuthTunnels: the tunnels that waited for the user ─────────────────────

    /** The way the app gets there: a tunnel reported by the notification, then its token spent by the activity. */
    private fun spendToken(vararg tunnelIds: String) {
        var token = ""
        tunnelIds.forEach { token = pendingAuthResume.register(it, "Tunnel $it").token }
        pendingAuthResume.consume(token)
    }

    @Test
    fun `nothing is resumed before a notification token was spent`() = runTest {
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        coVerify(exactly = 0) { resumeTunnelsUseCase(any()) }
        assertFalse(viewModel.authResumePending.value)
    }

    @Test
    fun `a waiting tunnel that was not confirmed by a token is not resumed`() = runTest {
        // Reported, notified, but the token was never spent: a forged intent cannot start it either.
        pendingAuthResume.register("t1", "Tunnel t1")

        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        coVerify(exactly = 0) { resumeTunnelsUseCase(any()) }
    }

    @Test
    fun `a spent token makes the resume pending, and the list restarts the tunnels it names`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { tunnelRepository.getById("t1") } returns tunnel
        coEvery { resumeTunnelsUseCase(listOf("t1")) } returns listOf("t1" to SshResult.Success(Unit))
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        spendToken("t1")
        assertTrue(viewModel.authResumePending.value, "the screen is told, wherever the user is")
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        coVerify(exactly = 1) { resumeTunnelsUseCase(listOf("t1")) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelStarted("Tunnel t1")), events)
        verify(exactly = 1) { tunnelService.ensureRunning("Tunnel t1") }
        assertFalse(viewModel.authResumePending.value, "handed over once")
        collector.cancel()
    }

    @Test
    fun `the ready tunnels are started once, whichever screen asks first`() = runTest {
        coEvery { resumeTunnelsUseCase(any()) } returns emptyList()
        val otherEntry = newViewModel()

        spendToken("t1")
        viewModel.resumeAuthTunnels()
        otherEntry.resumeAuthTunnels()
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        coVerify(exactly = 1) { resumeTunnelsUseCase(listOf("t1")) }
    }

    @Test
    fun `the resume is not once per process, every tap of the notification restarts its tunnels`() = runTest {
        coEvery { resumeTunnelsUseCase(any()) } returns emptyList()
        // The auto-start of the process was claimed long ago: it has nothing to do with the resume.
        assertTrue(autoStartGate.tryClaim())

        spendToken("t1")
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()
        spendToken("t2")
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        coVerify(exactly = 1) { resumeTunnelsUseCase(listOf("t1")) }
        coVerify(exactly = 1) { resumeTunnelsUseCase(listOf("t2")) }
    }

    @Test
    fun `the failure shared by the tunnels of one host is reported once after a resume`() = runTest {
        val a = makeTunnel("a", isFavorite = false)
        val b = makeTunnel("b", isFavorite = false)
        val cancelled = SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        listOf(a, b).forEach { coEvery { tunnelRepository.getById(it.id) } returns it }
        coEvery { resumeTunnelsUseCase(listOf("a", "b")) } returns listOf("a" to cancelled, "b" to cancelled)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        spendToken("a", "b")
        viewModel.resumeAuthTunnels()
        advanceUntilIdle()

        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error("Connexion annulée")), events)
        collector.cancel()
    }

    // ── Stop wins over Start, and a port that cannot be used ─────────────────

    @Test
    fun `stopping a tunnel this ViewModel is starting stops it first, then ends the start, with no error`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        val order = mutableListOf<String>()
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } coAnswers { awaitCancellation() }
        every { tunnelManager.markStartCancelled(any(), any(), any()) } answers { order += "markStartCancelled" }
        coEvery { stopTunnel("t1") } coAnswers {
            order += "stop"
            SshResult.Success(Unit)
        }
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel) // runs up to the connection, behind a key prompt for instance
        coVerify(exactly = 1) { startTunnel(tunnel) }
        // The card now says STARTING, which counts as running: the next tap is a stop.
        every { tunnelManager.getTunnelState("t1") } returns TunnelState(tunnel, TunnelStatus.STARTING)

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        // In that order: cancelled first, the start would put a held ERROR back that the stop must then remove.
        assertEquals(listOf("stop", "markStartCancelled"), order)
        assertEquals(listOf<TunnelEvent>(TunnelEvent.TunnelStopped(tunnel.label)), events)
        verify(exactly = 0) { tunnelManager.holdReconnectIfNonRetryable(any(), any()) }
        collector.cancel()
    }

    @Test
    fun `a stop that fails leaves the start going and says so`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } coAnswers { awaitCancellation() }
        every { tunnelManager.markStartCancelled(any(), any(), any()) } just Runs
        coEvery { stopTunnel("t1") } returns SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_STOP_FAILED_MESSAGE)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        every { tunnelManager.getTunnelState("t1") } returns TunnelState(tunnel, TunnelStatus.STARTING)
        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        verify(exactly = 0) { tunnelManager.markStartCancelled(any(), any(), any()) }
        assertEquals(listOf<TunnelEvent>(TunnelEvent.Error(TUNNEL_STOP_FAILED_MESSAGE)), events)
        viewModel.viewModelScope.coroutineContext[Job]!!.cancelChildren()
        collector.cancel()
    }

    @Test
    fun `a tunnel stopped while its start was connecting says nothing when the connection then fails`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "Authentication failed")
        // The stop came from elsewhere (the notification, a sync that deleted the tunnel): this
        // ViewModel's coroutine was not cancelled, and the manager knows the start was stopped.
        every { tunnelManager.isStartAbandoned(tunnel) } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()

        assertTrue(events.isEmpty(), "$events")
        verify(exactly = 0) { tunnelManager.holdReconnectIfNonRetryable(any(), any()) }
        collector.cancel()
    }

    @Test
    fun `the shortcut start says nothing either when it was stopped while connecting`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } returns SshResult.Error(SshErrorCode.FIDO2_USER_CANCELLED, "Connexion annulée")
        every { tunnelManager.isStartAbandoned(tunnel) } returns true
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        assertTrue(events.isEmpty(), "$events")
        verify(exactly = 0) { tunnelManager.holdReconnectIfNonRetryable(any(), any()) }
        collector.cancel()
    }

    @Test
    fun `deleting a tunnel this ViewModel is starting stops it, then ends the start`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        val order = mutableListOf<String>()
        coEvery { liveTunnels.find("t1") } returns tunnel
        coEvery { startTunnel(tunnel) } coAnswers { awaitCancellation() }
        every { tunnelManager.markStartCancelled(any(), any(), any()) } answers { order += "markStartCancelled" }
        coEvery { stopTunnel("t1") } coAnswers {
            order += "stop"
            SshResult.Success(Unit)
        }
        coEvery { tunnelRepository.delete("t1") } coAnswers {
            order += "delete"
        }

        viewModel.toggleTunnel(tunnel)
        every { tunnelManager.tunnelStates } returns statesOf(tunnel to TunnelStatus.STARTING)
        viewModel.deleteTunnel("t1")
        advanceUntilIdle()

        // The cancellation is resumed by the dispatcher, the delete goes on: only the order of the stop
        // and the cancellation matters, the card is gone when the cancelled start looks at it.
        assertEquals(setOf("stop", "markStartCancelled", "delete"), order.toSet())
        assertTrue(order.indexOf("stop") < order.indexOf("markStartCancelled"), "$order")
    }

    @Test
    fun `a tunnel whose ports cannot be used is refused before any start, and the reason is told`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false).copy(localPort = 80)
        val refusal = SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_LOCAL_PORT_RESERVED_MESSAGE)
        coEvery { liveTunnels.find("t1") } returns tunnel
        every { tunnelManager.refuseInvalidPorts(tunnel) } returns refusal
        every { tunnelManager.holdReconnectIfNonRetryable(any(), any()) } returns false
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()
        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        // No connection, so no key prompt and no fingerprint for a tunnel that cannot start.
        coVerify(exactly = 0) { startTunnel(any()) }
        assertEquals(
            listOf<TunnelEvent>(
                TunnelEvent.Error(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE),
                TunnelEvent.Error(TUNNEL_LOCAL_PORT_RESERVED_MESSAGE),
            ),
            events,
        )
        collector.cancel()
    }

    @Test
    fun `the result of a start that faded out says nothing, whatever the manager knows`() = runTest {
        val tunnel = makeTunnel("t1", isFavorite = false)
        coEvery { liveTunnels.find("t1") } returns tunnel
        // isStartAbandoned stays false (see setUp): the replacement of the faded start has finished.
        coEvery { startTunnel(tunnel) } returns SshResult.Error(SshErrorCode.UNKNOWN, TUNNEL_START_STOPPED_MESSAGE)
        val events = mutableListOf<TunnelEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.toggleTunnel(tunnel)
        advanceUntilIdle()
        viewModel.autoStartTunnelById("t1")
        advanceUntilIdle()

        assertTrue(events.isEmpty(), "$events")
        verify(exactly = 0) { tunnelManager.holdReconnectIfNonRetryable(any(), any()) }
        collector.cancel()
    }

    // ── saveTunnel port rules ────────────────────────────────────────────────

    @Test
    fun `saveTunnel saves nothing while a port breaks the rules`() = runTest {
        coJustRun { tunnelRepository.save(any()) }

        val badForms = listOf<TunnelFormState.() -> TunnelFormState>(
            { copy(type = TunnelType.LOCAL_FORWARD, localPort = "80") },
            { copy(type = TunnelType.DYNAMIC_SOCKS5, localPort = "80") },
            { copy(type = TunnelType.LOCAL_FORWARD, localPort = "70000") },
            { copy(type = TunnelType.REMOTE_FORWARD, localPort = "0") },
            { copy(type = TunnelType.LOCAL_FORWARD, localPort = "8080", remotePort = "70000") },
        )
        for (bad in badForms) {
            viewModel.updateForm {
                copy(label = "Tunnel", hostId = "host-1", remoteHost = "127.0.0.1", remotePort = "80").bad()
            }
            viewModel.saveTunnel()
            advanceUntilIdle()
        }

        coVerify(exactly = 0) { tunnelRepository.save(any()) }
    }

    @Test
    fun `saveTunnel accepts local port 80 for a remote forward`() = runTest {
        coJustRun { tunnelRepository.save(any()) }

        viewModel.updateForm {
            copy(
                label = "Remote",
                hostId = "host-1",
                type = TunnelType.REMOTE_FORWARD,
                localPort = "80",
                remoteHost = "0.0.0.0",
                remotePort = "8080",
            )
        }
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.save(capture(configSlot)) }
        assertEquals(80, configSlot.captured.localPort)
        assertEquals(8080, configSlot.captured.remotePort)
    }

    @Test
    fun `saveTunnel saves a new SOCKS5 tunnel whose remote port was never typed`() = runTest {
        coJustRun { tunnelRepository.save(any()) }

        // The SOCKS5 form has no remote port field: the text stays empty, and toInt() on an empty
        // text used to crash the save.
        viewModel.updateForm {
            copy(label = "Proxy", hostId = "host-1", type = TunnelType.DYNAMIC_SOCKS5, localPort = "1080")
        }
        viewModel.saveTunnel()
        advanceUntilIdle()

        val configSlot = slot<TunnelConfig>()
        coVerify(exactly = 1) { tunnelRepository.save(capture(configSlot)) }
        assertEquals(1080, configSlot.captured.localPort)
        assertEquals(0, configSlot.captured.remotePort)
    }
}
