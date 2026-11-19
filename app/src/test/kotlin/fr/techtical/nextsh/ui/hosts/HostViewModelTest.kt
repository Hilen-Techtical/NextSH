// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.BiometricPromptCoordinator
import fr.techtical.nextsh.core.ssh.BiometricPromptResponse
import fr.techtical.nextsh.core.ssh.SessionHandoff
import fr.techtical.nextsh.core.ssh.VaultAuthPurpose
import fr.techtical.nextsh.core.ssh.biometricResponseForError
import fr.techtical.nextsh.core.ssh.SshTerminalSession
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SessionStatus
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.model.SshSession
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.ui.sessions.SessionTab
import fr.techtical.nextsh.ui.sessions.TerminalTabStore
import fr.techtical.nextsh.ui.sessions.TerminalTabsState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.coJustRun
import io.mockk.Runs
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HostViewModelTest {

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var connectSession: ConnectSessionUseCase

    @MockK
    private lateinit var sessionHandoff: SessionHandoff

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var customThemeRepository: CustomTerminalThemeRepository

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    @MockK
    private lateinit var tunnelManager: SshTunnelManager

    @MockK
    private lateinit var stopTunnel: StopTunnelUseCase

    @MockK
    private lateinit var tabStore: TerminalTabStore

    private val tunnelStates = MutableStateFlow<Map<String, TunnelState>>(emptyMap())

    @MockK
    private lateinit var biometricPrompts: BiometricPromptCoordinator

    /** The real lock on a clock that never moves. It starts locked, like the app: the tests unlock it. */
    private val appLock = AppLock { 0L }

    /** The terminal tabs the store holds: none, unless a test opens some. */
    private val tabsState = MutableStateFlow(TerminalTabsState())

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: HostViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeGroups() } returns flowOf(emptyList())
        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { customThemeRepository.observeAll() } returns flowOf(emptyList())
        every { syncScheduler.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))
        every { tunnelManager.tunnelStates } returns tunnelStates
        every { tabStore.state } returns tabsState
        // Par défaut aucun id n'est celui d'une clé SSH.
        coEvery { sshKeyRepository.getById(any()) } returns null

        every { sessionHandoff.offer(any()) } just Runs
        appLock.unlock()
        viewModel = newViewModel()
    }

    /** [ioDispatcher] est le dispatcher des écritures du vault : le dispatcher de test par défaut. */
    private fun newViewModel(ioDispatcher: CoroutineDispatcher = testDispatcher) = HostViewModel(
        hostRepository = hostRepository,
        connectSession = connectSession,
        sessionHandoff = sessionHandoff,
        vaultManager = vaultManager,
        sshKeyRepository = sshKeyRepository,
        customThemeRepository = customThemeRepository,
        syncScheduler = syncScheduler,
        tunnelManager = tunnelManager,
        stopTunnel = stopTunnel,
        biometricPrompts = biometricPrompts,
        appLock = appLock,
        ioDispatcher = ioDispatcher,
        tabStore = tabStore,
    )

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun makeHost(id: String, isFavorite: Boolean) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = isFavorite,
    )

    // ── toggleFavorite tests ─────────────────────────────────────────────────

    @Test
    fun `toggleFavorite calls setFavorite with true when host is not a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coJustRun { hostRepository.setFavorite(host.id, true) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, true) }
    }

    @Test
    fun `toggleFavorite calls setFavorite with false when host is already a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = true)
        coJustRun { hostRepository.setFavorite(host.id, false) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, false) }
    }

    // ── saveHost favorite preservation tests ────────────────────────────────

    @Test
    fun `saveHost preserves isFavorite when updating an existing favorite host`() = runTest {
        val existingHost = makeHost("h1", isFavorite = true)
        coEvery { hostRepository.getById("h1") } returns existingHost
        coJustRun { hostRepository.update(any()) }

        // Load host for edit
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        // Save without changing anything
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertTrue(hostSlot.captured.isFavorite, "isFavorite should be true after update")
    }

    @Test
    fun `saveHost sets isFavorite to false for new host`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }

        // Fill minimal form for a new host
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "pass",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertFalse(hostSlot.captured.isFavorite, "isFavorite should be false for new host")
    }

    @Test
    fun `saveHost writes the host name without the spaces around it`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }
        viewModel.updateForm {
            copy(
                label = "Padded",
                hostname = "  padded.example.com  ",
                port = " 2222 ",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "pass",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("padded.example.com", hostSlot.captured.hostname)
        assertEquals(2222, hostSlot.captured.port)
    }

    @Test
    fun `saveHost writes nothing for a host name that a restore would refuse`() = runTest {
        coJustRun { hostRepository.save(any()) }
        viewModel.updateForm {
            copy(
                label = "Spaced",
                hostname = "inner space.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "pass",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `saveHost writes nothing for a port out of range`() = runTest {
        coJustRun { hostRepository.save(any()) }
        listOf("0", "65536").forEach { badPort ->
            viewModel.updateForm {
                copy(
                    label = "Port",
                    hostname = "port.example.com",
                    port = badPort,
                    username = "user",
                    authType = AuthType.PASSWORD,
                    password = "pass",
                )
            }

            viewModel.saveHost()
            advanceUntilIdle()
        }

        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    // ── Editing a host deleted meanwhile ────────────────────────────────────

    @Test
    fun `saveHost on a host deleted meanwhile writes nothing and reports it`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        // The form loads the host, then the stored row is gone (a sync deleted it) when saving.
        coEvery { hostRepository.getById("h1") } returnsMany listOf(host, null)
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "secret") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.HostNoLongerExists), events)
        coVerify(exactly = 0) { hostRepository.update(any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        // Not even the password is written for a host that no longer exists...
        coVerify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        // ...and the typed password does not stay in the form state.
        assertEquals("", viewModel.formState.value.password)
        collector.cancel()
    }

    @Test
    fun `saveHost on an existing host reports HostSaved`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.update(any()) }
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        collector.cancel()
    }

    // ── The form after a save: the screen leaves on HostSaved, it must not be seen resetting ──

    /** A form with every visible field off its default, and a typed password. */
    private fun HostFormState.typed() = copy(
        label = "Prod",
        hostname = "prod.example.com",
        port = "2222",
        username = "root",
        authType = AuthType.PASSWORD,
        password = "s3cret",
        group = "work",
        keepAliveSeconds = "45",
        autoReconnect = false,
    )

    @Test
    fun `saveHost keeps the visible fields as typed and drops only the password`() = runTest {
        coJustRun { hostRepository.save(any()) }
        // Read at the call: the ViewModel zeroes the array it hands over once the vault returns.
        val stored = mutableListOf<String>()
        every { vaultManager.storePassword(any(), any()) } answers { stored += secondArg<CharArray>().concatToString() }
        viewModel.updateForm { typed() }
        val typed = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        // The password did reach the vault, then left the form state at once...
        assertEquals(listOf("s3cret"), stored)
        assertEquals("", viewModel.formState.value.password)
        // ...and nothing else changed: the options do not flip to their defaults.
        assertEquals(typed.copy(password = ""), viewModel.formState.value)
        assertFalse(viewModel.formState.value.autoReconnect)
        assertEquals("2222", viewModel.formState.value.port)
        assertEquals("work", viewModel.formState.value.group)
    }
    private suspend fun TestScope.loadedKeepAlive(stored: Int): String {
        coEvery { hostRepository.getById("h1") } returns makeHost("h1", isFavorite = false).copy(keepAliveSeconds = stored)
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        return viewModel.formState.value.keepAliveSeconds
    }

    @Test
    fun `a stored keepalive out of the bounds loads bounded, without any write`() = runTest {
        assertEquals("60", loadedKeepAlive(120))
        assertTrue(viewModel.formState.value.isValid)
        coVerify(exactly = 0) { hostRepository.update(any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `a stored keepalive below 15 loads as 15, 0 stays 0 and a negative one loads as 0`() = runTest {
        assertEquals("15", loadedKeepAlive(5))
        assertEquals("0", loadedKeepAlive(0))
        assertEquals("0", loadedKeepAlive(-3))
        assertEquals("30", loadedKeepAlive(30))
    }


    @Test
    fun `saveHost leaves an edited host form as loaded`() = runTest {
        val host = makeHost("h1", isFavorite = false).copy(autoReconnect = false, keepAliveSeconds = 45)
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.update(any()) }
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        val loaded = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.update(any()) }
        assertEquals(loaded, viewModel.formState.value)
    }

    @Test
    fun `opening the screen for a new host after a save starts from the defaults`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }
        viewModel.updateForm { typed() }
        viewModel.saveHost()
        advanceUntilIdle()

        // What HostDetailScreen does on entry for a new host.
        viewModel.resetForm()

        assertEquals(HostFormState(), viewModel.formState.value)
    }

    @Test
    fun `opening the screen to edit a host after a save loads the stored host`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }
        viewModel.updateForm { typed() }
        viewModel.saveHost()
        advanceUntilIdle()
        coEvery { hostRepository.getById("h2") } returns makeHost("h2", isFavorite = false)

        // What HostDetailScreen does on entry for an existing host.
        viewModel.loadHostForEdit("h2")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertEquals("h2", form.id)
        assertEquals("Host h2", form.label)
        assertEquals("", form.password)
        assertTrue(form.autoReconnect)
    }

    @Test
    fun `a second tap on Save while the screen is leaving saves once and reports once`() = runTest {
        // The form stays valid after a save, so the guard is what stops a second new host.
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        viewModel.updateForm { typed() }

        viewModel.saveHost()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        collector.cancel()
    }

    @Test
    fun `a save refused because the host is gone can be tried again`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        // load, first save (row gone), second save (row back)
        coEvery { hostRepository.getById("h1") } returnsMany listOf(host, null, host)
        coJustRun { hostRepository.update(any()) }
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.HostNoLongerExists, HostEvent.HostSaved), events)
        coVerify(exactly = 1) { hostRepository.update(any()) }
        collector.cancel()
    }

    // ── The save guard is released whatever ends the save ───────────────────

    @Test
    fun `the save guard is released when the repository throws, the next tap saves`() = runTest {
        // The ViewModel catches the failure of a write and shows SAVE_FAILED (see the tests of the
        // save errors): what matters here is that the guard does not stay shut behind it.
        coEvery { hostRepository.save(any()) } throws IllegalStateException("database is locked") andThen Unit
        coJustRun { vaultManager.storePassword(any(), any()) }
        every { vaultManager.deletePassword(any()) } just Runs
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }
        viewModel.updateForm { typed() }

        viewModel.saveHost()
        advanceUntilIdle()
        assertTrue(events.isEmpty(), "a failed write reports nothing as saved")
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)

        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 2) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
        collector.cancel()
    }

    @Test
    fun `the save guard is released when the save is cancelled, the next tap saves`() = runTest {
        // The write is in flight when the scope is cancelled (screen left, ViewModel cleared).
        coEvery { hostRepository.save(any()) } coAnswers { awaitCancellation() }
        coJustRun { vaultManager.storePassword(any(), any()) }
        viewModel.updateForm { typed() }
        viewModel.saveHost()
        coVerify(exactly = 1) { hostRepository.save(any()) }

        viewModel.viewModelScope.coroutineContext[Job]!!.cancelChildren()
        advanceUntilIdle()
        // Cancelled before the write ended: the form still holds what the user typed.
        assertEquals("s3cret", viewModel.formState.value.password)

        coEvery { hostRepository.save(any()) } just Runs
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 2) { hostRepository.save(any()) }
        assertEquals("", viewModel.formState.value.password)
    }

    // ── deleteHost: running tunnels are stopped first ───────────────────────

    private fun tunnelState(id: String, hostId: String, status: TunnelStatus) = TunnelState(
        config = TunnelConfig(
            id = id,
            label = "Tunnel $id",
            hostId = hostId,
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "localhost",
            remotePort = 80,
        ),
        status = status,
    )

    @Test
    fun `deleteHost stops the tunnels of that host in every state, then deletes it`() = runTest {
        tunnelStates.value = linkedMapOf(
            "t-active" to tunnelState("t-active", "h1", TunnelStatus.ACTIVE),
            "t-starting" to tunnelState("t-starting", "h1", TunnelStatus.STARTING),
            "t-reconnecting" to tunnelState("t-reconnecting", "h1", TunnelStatus.RECONNECTING),
            "t-error" to tunnelState("t-error", "h1", TunnelStatus.ERROR),
            "t-other-host" to tunnelState("t-other-host", "h2", TunnelStatus.ACTIVE),
        )
        coEvery { stopTunnel(any()) } returns SshResult.Success(Unit)
        coEvery { hostRepository.getById("h1") } returns null
        coJustRun { hostRepository.delete("h1") }
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        // Every stop happens before the delete: once the rows are tombstoned the list no longer
        // shows the tunnels, so they could not be stopped from the UI any more.
        coVerifyOrder {
            stopTunnel("t-active")
            stopTunnel("t-starting")
            stopTunnel("t-reconnecting")
            stopTunnel("t-error")
            hostRepository.delete("h1")
        }
        coVerify(exactly = 0) { stopTunnel("t-other-host") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
        collector.cancel()
    }

    @Test
    fun `deleteHost of a host without running tunnel stops nothing`() = runTest {
        tunnelStates.value = mapOf("t-other-host" to tunnelState("t-other-host", "h2", TunnelStatus.ACTIVE))
        coEvery { hostRepository.getById("h1") } returns null
        coJustRun { hostRepository.delete("h1") }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 0) { stopTunnel(any()) }
        coVerify(exactly = 1) { hostRepository.delete("h1") }
    }

    @Test
    fun `deleteHost still deletes when a tunnel stop reports a failure`() = runTest {
        // Same as TunnelViewModel.deleteTunnel: a failed teardown must not make the host undeletable.
        tunnelStates.value = mapOf("t1" to tunnelState("t1", "h1", TunnelStatus.ACTIVE))
        coEvery { stopTunnel("t1") } returns SshResult.Error(SshErrorCode.UNKNOWN, "teardown failed")
        coEvery { hostRepository.getById("h1") } returns null
        coJustRun { hostRepository.delete("h1") }
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
        collector.cancel()
    }

    @Test
    fun `deleteHost reports an error and keeps the host when stopping a tunnel throws`() = runTest {
        tunnelStates.value = mapOf("t1" to tunnelState("t1", "h1", TunnelStatus.ACTIVE))
        coEvery { stopTunnel("t1") } throws IllegalStateException("boom")
        coEvery { hostRepository.getById("h1") } returns null
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.delete(any()) }
        // A typed event, with no payload: the screen shows a localized text, and nothing from the
        // exception (message included) can reach the user.
        assertEquals(listOf<HostEvent>(HostEvent.DeleteFailed), events)
        collector.cancel()
    }

    @Test
    fun `deleteHost reports an error when the repository delete throws`() = runTest {
        coEvery { hostRepository.getById("h1") } returns null
        coEvery { hostRepository.delete("h1") } throws IllegalStateException("database is locked")
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.DeleteFailed), events)
        collector.cancel()
    }

    @Test
    fun `deleteHost lets a cancellation through instead of reporting an error`() = runTest {
        tunnelStates.value = mapOf("t1" to tunnelState("t1", "h1", TunnelStatus.ACTIVE))
        coEvery { stopTunnel("t1") } throws CancellationException("cancelled")
        coEvery { hostRepository.getById("h1") } returns null
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        assertTrue(events.isEmpty(), "a cancellation is not a failure to show: $events")
        coVerify(exactly = 0) { hostRepository.delete(any()) }
        collector.cancel()
    }

    // ── Connecting from the list: one connection, handed over to the terminal ───

    private fun connectedSession(host: Host, sessionId: String) =
        SshSession(id = sessionId, host = host, status = SessionStatus.CONNECTED)

    @Test
    fun `connectToHost connects once and reports the session id with the host id`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        // The event used to carry the HOST id under the name sessionId: the terminal then connected
        // a second time to a host it believed it was handed.
        assertEquals(listOf<HostEvent>(HostEvent.Connected(sessionId = "session-42", hostId = "h1")), events)
        coVerify(exactly = 1) { connectSession(any()) }
        // The reference of the connection is on offer to the terminal, which claims it.
        verify(exactly = 1) { sessionHandoff.offer("session-42") }
        assertNull(viewModel.uiState.value.connectingHostId)
        collector.cancel()
    }

    @Test
    fun `connectToHost offers the session before announcing it`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val order = mutableListOf<String>()
        every { sessionHandoff.offer("session-42") } answers { order += "offer" }
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { order += "event" }
        }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        // The receiver claims as soon as it hears: the offer has to be there by then.
        assertEquals(listOf("offer", "event"), order)
        collector.cancel()
    }

    @Test
    fun `connectToHost reports the error in the list and opens no terminal`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Error(SshErrorCode.AUTH_FAILED, "refused")
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.Error("refused")), events)
        assertNull(viewModel.uiState.value.connectingHostId)
        verify(exactly = 0) { sessionHandoff.offer(any()) }
        collector.cancel()
    }

    @Test
    fun `connectToHost offers the session even when the list was left before the connection ended`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        // No collector: the user switched to another tab while the connection was being made, the
        // event is dropped and the terminal never opens. The offer is what keeps the session from
        // staying open, unseen: nobody claims it and the hand-over releases it after its window.

        viewModel.connectToHost(host)
        advanceUntilIdle()

        verify(exactly = 1) { sessionHandoff.offer("session-42") }
        assertNull(viewModel.uiState.value.connectingHostId)
    }

    // ── Connecting from the list to a host that has a terminal tab ──────────

    private fun terminalTab(host: Host, status: SessionStatus = SessionStatus.CONNECTED, sessionId: String = "session-${host.id}") =
        SessionTab(sessionId = sessionId, host = host, terminalSession = mockk<SshTerminalSession>(), status = status)

    @Test
    fun `connectToHost on a host that has a tab opens the terminal on it and connects nothing`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(host)))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.OpenTerminal("h1")), events)
        coVerify(exactly = 0) { connectSession(any()) }
        // Nothing was connected, so there is nothing to hand over to the terminal.
        verify(exactly = 0) { sessionHandoff.offer(any()) }
        assertNull(viewModel.uiState.value.connectingHostId)
        collector.cancel()
    }

    @Test
    fun `connectToHost on a host whose tab is disconnected opens the terminal too, whatever the status`() = runTest {
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        for (status in SessionStatus.entries) {
            val host = makeHost("h-$status", isFavorite = false)
            tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(host, status)))

            viewModel.connectToHost(host)
            advanceUntilIdle()
        }

        assertEquals(SessionStatus.entries.map { HostEvent.OpenTerminal("h-$it") }, events)
        coVerify(exactly = 0) { connectSession(any()) }
        verify(exactly = 0) { sessionHandoff.offer(any()) }
        collector.cancel()
    }

    @Test
    fun `connectToHost on a host that has no tab connects as before even if other hosts have one`() = runTest {
        val open = makeHost("h1", isFavorite = false)
        val host = makeHost("h2", isFavorite = false)
        tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(open)))
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.Connected(sessionId = "session-42", hostId = "h2")), events)
        coVerify(exactly = 1) { connectSession(host) }
        verify(exactly = 1) { sessionHandoff.offer("session-42") }
        collector.cancel()
    }

    @Test
    fun `connectToHost connects again once the tab of the host was closed`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(host)))
        tabsState.value = TerminalTabsState()
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectToHost(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.Connected(sessionId = "session-42", hostId = "h1")), events)
        collector.cancel()
    }

    @Test
    fun `connectForSftp still connects for a host that has a tab, it never opens the terminal`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(host)))
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectForSftp(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.SftpReady("session-42", "Host h1")), events)
        coVerify(exactly = 1) { connectSession(host) }
        collector.cancel()
    }

    // ── The terminal tabs in the state of the list ──────────────────────────

    @Test
    fun `without a tab the list shows no banner and no dot`() {
        val state = viewModel.uiState.value

        assertEquals(0, state.openTerminalCount)
        assertEquals(0, state.disconnectedTerminalCount)
        assertTrue(state.openTerminalHostIds.isEmpty())
        assertTrue(state.connectedTerminalHostIds.isEmpty())
        assertEquals(HostTerminalTab.NONE, state.terminalTabOf("h1"))
    }

    @Test
    fun `the state counts every tab and tells connected hosts from those left with a disconnected tab`() {
        val h1 = makeHost("h1", isFavorite = false)
        val h2 = makeHost("h2", isFavorite = false)
        val h3 = makeHost("h3", isFavorite = false)
        tabsState.value = TerminalTabsState(
            tabs = listOf(
                terminalTab(h1, SessionStatus.CONNECTED, sessionId = "s1"),
                terminalTab(h2, SessionStatus.DISCONNECTED, sessionId = "s2"),
                // Two tabs on one host: a connected one and a disconnected one.
                terminalTab(h1, SessionStatus.DISCONNECTED, sessionId = "s3"),
            ),
        )

        val state = viewModel.uiState.value

        assertEquals(3, state.openTerminalCount, "a tab is counted, not a host")
        assertEquals(2, state.disconnectedTerminalCount, "the banner says how many of the three are dead")
        assertEquals(setOf("h1", "h2"), state.openTerminalHostIds)
        assertEquals(setOf("h1"), state.connectedTerminalHostIds)
        assertEquals(HostTerminalTab.CONNECTED, state.terminalTabOf(h1.id), "a connected tab wins over a disconnected one")
        assertEquals(HostTerminalTab.DISCONNECTED, state.terminalTabOf(h2.id))
        assertEquals(HostTerminalTab.NONE, state.terminalTabOf(h3.id))
    }

    @Test
    fun `a tab in error or reconnecting does not make its host a connected one`() {
        val errored = makeHost("h1", isFavorite = false)
        val reconnecting = makeHost("h2", isFavorite = false)
        tabsState.value = TerminalTabsState(
            tabs = listOf(
                terminalTab(errored, SessionStatus.ERROR),
                terminalTab(reconnecting, SessionStatus.RECONNECTING),
            ),
        )

        val state = viewModel.uiState.value

        assertEquals(setOf("h1", "h2"), state.openTerminalHostIds)
        assertTrue(state.connectedTerminalHostIds.isEmpty())
    }

    @Test
    fun `the banner counts the disconnected tabs apart from the total`() {
        val host = makeHost("h1", isFavorite = false)
        tabsState.value = TerminalTabsState(tabs = listOf(terminalTab(host, sessionId = "s1")))

        val allConnected = viewModel.uiState.value
        assertEquals(1, allConnected.openTerminalCount)
        assertEquals(0, allConnected.disconnectedTerminalCount, "a live tab is not a disconnected one")

        tabsState.value = TerminalTabsState(
            tabs = listOf(
                terminalTab(host, sessionId = "s1"),
                terminalTab(host, SessionStatus.DISCONNECTED, sessionId = "s2"),
                terminalTab(host, SessionStatus.ERROR, sessionId = "s3"),
            ),
        )

        val mixed = viewModel.uiState.value
        assertEquals(3, mixed.openTerminalCount, "the total keeps every tab")
        assertEquals(2, mixed.disconnectedTerminalCount, "a tab in error is as dead as a disconnected one")
    }

    @Test
    fun `one dead tab alone is one open tab, one of them disconnected`() {
        tabsState.value = TerminalTabsState(
            tabs = listOf(terminalTab(makeHost("h1", isFavorite = false), SessionStatus.DISCONNECTED)),
        )

        val state = viewModel.uiState.value

        assertEquals(1, state.openTerminalCount)
        assertEquals(1, state.disconnectedTerminalCount)
    }

    @Test
    fun `the state follows the store as tabs fall, come back and close`() {
        val host = makeHost("h1", isFavorite = false)
        val connected = terminalTab(host)

        tabsState.value = TerminalTabsState(tabs = listOf(connected))
        assertEquals(HostTerminalTab.CONNECTED, viewModel.uiState.value.terminalTabOf("h1"))

        tabsState.value = TerminalTabsState(tabs = listOf(connected.copy(status = SessionStatus.DISCONNECTED)))
        assertEquals(HostTerminalTab.DISCONNECTED, viewModel.uiState.value.terminalTabOf("h1"))
        assertEquals(1, viewModel.uiState.value.openTerminalCount, "a disconnected tab is still an open one")
        assertEquals(1, viewModel.uiState.value.disconnectedTerminalCount)

        tabsState.value = TerminalTabsState()
        assertEquals(0, viewModel.uiState.value.openTerminalCount)
        assertEquals(0, viewModel.uiState.value.disconnectedTerminalCount)
        assertEquals(HostTerminalTab.NONE, viewModel.uiState.value.terminalTabOf("h1"))
    }

    @Test
    fun `a change of the active tab alone does not touch the list state`() {
        val tabs = listOf(terminalTab(makeHost("h1", isFavorite = false)), terminalTab(makeHost("h2", isFavorite = false)))
        tabsState.value = TerminalTabsState(tabs = tabs)
        val before = viewModel.uiState.value

        // Selecting a tab, like dragging the split divider, republishes the state of the store
        // without changing its tabs: the list has nothing to redraw.
        tabsState.value = tabsState.value.copy(activeTabIndex = 1)

        assertSame(before, viewModel.uiState.value)
    }

    // ── Connecting for the SFTP browser: the same hand-over ─────────────────

    @Test
    fun `connectForSftp offers the session and announces it with the label of the host`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        val events = mutableListOf<HostEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.events.toList(events) }

        viewModel.connectForSftp(host)
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.SftpReady("session-42", "Host h1")), events)
        verify(exactly = 1) { sessionHandoff.offer("session-42") }
        assertNull(viewModel.uiState.value.connectingHostId)
        collector.cancel()
    }

    @Test
    fun `connectForSftp offers the session even when the list was left meanwhile`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coEvery { connectSession(host) } returns SshResult.Success(connectedSession(host, "session-42"))
        // The event is dropped without a collector, and the session used to leak with it.

        viewModel.connectForSftp(host)
        advanceUntilIdle()

        verify(exactly = 1) { sessionHandoff.offer("session-42") }
    }

    // ── FIDO2 mode: PASSKEY is only offered to a host that already has it ───

    private val securityKey = SshKey("sk-1", "YubiKey", SshKeyType.SK_ED25519, "sk-ssh-ed25519@openssh.com AAAA", fido2CredentialId = "AAAA")
    private val securityKeyEcdsa = SshKey("sk-2", "Other YubiKey", SshKeyType.SK_ECDSA_256, "sk-ecdsa-sha2-nistp256@openssh.com AAAA")
    private val ordinaryKey = SshKey("key-1", "Laptop", SshKeyType.ED25519, "ssh-ed25519 AAAA")
    private val biometricKey = SshKey("bio-1", "Phone", SshKeyType.ECDSA_256, "ecdsa-sha2-nistp256 AAAA", isBiometric = true, keystoreAlias = "alias")
    private val allKeys = listOf(securityKey, securityKeyEcdsa, ordinaryKey, biometricKey)

    private fun fido2Host(mode: Fido2Mode?, credentialId: String, authType: AuthType = AuthType.FIDO2) = Host(
        id = "h1",
        label = "Host h1",
        hostname = "hosth1.example.com",
        username = "user",
        authType = authType,
        credentialId = credentialId,
        fido2Mode = mode,
    )

    /** A ViewModel whose key list is [keys]: the list the form filters by mode comes from there. */
    private fun viewModelWithKeys(keys: List<SshKey>): HostViewModel {
        every { sshKeyRepository.observeAll() } returns flowOf(keys)
        // loadHostForEdit looks the key of a host up by id: the keys of the list exist in the Vault.
        keys.forEach { key -> coEvery { sshKeyRepository.getById(key.id) } returns key }
        return newViewModel()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.load(vm: HostViewModel, host: Host) {
        coEvery { hostRepository.getById(host.id) } returns host
        vm.loadHostForEdit(host.id)
        advanceUntilIdle()
    }

    @Test
    fun `a new host is a security key host and is not offered the mode`() {
        val form = viewModel.formState.value

        assertEquals(Fido2Mode.HARDWARE_KEY, form.fido2Mode)
        assertFalse(form.showFido2ModeChoice)
    }

    @Test
    fun `choosing FIDO2 for a new host keeps the security key mode and offers no choice`() = runTest {
        val vm = viewModelWithKeys(allKeys)

        vm.selectAuthType(AuthType.FIDO2)

        assertEquals(AuthType.FIDO2, vm.formState.value.authType)
        assertEquals(Fido2Mode.HARDWARE_KEY, vm.formState.value.fido2Mode)
        assertFalse(vm.formState.value.showFido2ModeChoice)
    }

    @Test
    fun `a new FIDO2 host is saved in security key mode`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coJustRun { hostRepository.save(any()) }
        vm.updateForm { copy(label = "Prod", hostname = "prod.example.com", username = "root") }
        vm.selectAuthType(AuthType.FIDO2)
        vm.updateForm { copy(selectedKeyId = "sk-1") }

        vm.saveHost()
        advanceUntilIdle()

        val saved = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(saved)) }
        assertEquals(Fido2Mode.HARDWARE_KEY, saved.captured.fido2Mode)
        assertEquals("sk-1", saved.captured.credentialId)
    }

    @Test
    fun `a host saved in security key mode is not offered the mode when it is opened again`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(Fido2Mode.HARDWARE_KEY, "sk-1"))

        assertEquals(Fido2Mode.HARDWARE_KEY, vm.formState.value.fido2Mode)
        assertFalse(vm.formState.value.showFido2ModeChoice)
        assertEquals("sk-1", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `a FIDO2 host from the Desktop, without a mode, is a security key host without a choice`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(mode = null, credentialId = "sk-1"))

        assertEquals(Fido2Mode.HARDWARE_KEY, vm.formState.value.fido2Mode)
        assertFalse(vm.formState.value.showFido2ModeChoice)
        assertEquals("sk-1", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `a host that is not FIDO2 does not bring a stale PASSKEY mode back`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(Fido2Mode.PASSKEY, "cred-1", authType = AuthType.PASSWORD))

        assertEquals(Fido2Mode.HARDWARE_KEY, vm.formState.value.fido2Mode)
        assertFalse(vm.formState.value.showFido2ModeChoice)
        // And switching it to FIDO2 gives a security key host, not a PASSKEY one without a way out.
        vm.selectAuthType(AuthType.FIDO2)
        assertEquals(Fido2Mode.HARDWARE_KEY, vm.formState.value.fido2Mode)
        assertFalse(vm.formState.value.showFido2ModeChoice)
    }

    @Test
    fun `a host already in PASSKEY mode keeps the mode, its key and the choice`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(Fido2Mode.PASSKEY, "key-1"))

        val form = vm.formState.value
        assertEquals(Fido2Mode.PASSKEY, form.fido2Mode)
        assertTrue(form.showFido2ModeChoice)
        assertEquals("key-1", form.selectedKeyId)
    }

    @Test
    fun `a host in PASSKEY mode can be saved as it is, still in PASSKEY mode`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coJustRun { hostRepository.update(any()) }
        load(vm, fido2Host(Fido2Mode.PASSKEY, "key-1"))

        vm.saveHost()
        advanceUntilIdle()

        val saved = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(saved)) }
        assertEquals(Fido2Mode.PASSKEY, saved.captured.fido2Mode)
        assertEquals("key-1", saved.captured.credentialId)
    }

    @Test
    fun `a host in PASSKEY mode can move to a security key, which drops its ordinary key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coJustRun { hostRepository.update(any()) }
        load(vm, fido2Host(Fido2Mode.PASSKEY, "key-1"))

        vm.selectFido2Mode(Fido2Mode.HARDWARE_KEY)

        val form = vm.formState.value
        assertEquals(Fido2Mode.HARDWARE_KEY, form.fido2Mode)
        // The ordinary key is not in the list of security keys: it must not stay selected out of sight.
        assertNull(form.selectedKeyId)
        assertFalse(form.isValid, "a key has to be chosen before the host can be saved")
        // The choice stays for the time of the edit, so the user can go back.
        assertTrue(form.showFido2ModeChoice)

        vm.updateForm { copy(selectedKeyId = "sk-1") }
        vm.saveHost()
        advanceUntilIdle()

        val saved = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(saved)) }
        assertEquals(Fido2Mode.HARDWARE_KEY, saved.captured.fido2Mode)
        assertEquals("sk-1", saved.captured.credentialId)
    }

    @Test
    fun `going back to PASSKEY mode does not bring a security key along`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(Fido2Mode.PASSKEY, "key-1"))
        vm.selectFido2Mode(Fido2Mode.HARDWARE_KEY)
        vm.updateForm { copy(selectedKeyId = "sk-1") }

        vm.selectFido2Mode(Fido2Mode.PASSKEY)

        assertEquals(Fido2Mode.PASSKEY, vm.formState.value.fido2Mode)
        assertNull(vm.formState.value.selectedKeyId)
    }

    @Test
    fun `choosing the mode already in force changes nothing, the key stays selected`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        load(vm, fido2Host(Fido2Mode.PASSKEY, "key-1"))
        val before = vm.formState.value

        vm.selectFido2Mode(Fido2Mode.PASSKEY)

        assertEquals(before, vm.formState.value)
    }

    @Test
    fun `switching a host to FIDO2 drops an ordinary key that the security key list does not offer`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.SSH_KEY)
        vm.updateForm { copy(selectedKeyId = "key-1") }

        vm.selectAuthType(AuthType.FIDO2)

        assertNull(vm.formState.value.selectedKeyId)
    }

    @Test
    fun `switching a host to FIDO2 keeps a security key it was given`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.SSH_KEY)
        vm.updateForm { copy(selectedKeyId = "sk-2") }

        vm.selectAuthType(AuthType.FIDO2)

        assertEquals("sk-2", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `switching between the other authentication types keeps the selected key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.SSH_KEY)
        vm.updateForm { copy(selectedKeyId = "key-1") }

        vm.selectAuthType(AuthType.CERTIFICATE)

        assertEquals("key-1", vm.formState.value.selectedKeyId)
        assertEquals(AuthType.CERTIFICATE, vm.formState.value.authType)
    }

    @Test
    fun `switching a host to SSH key drops a security key, which has no private key in the Vault`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.FIDO2)
        vm.updateForm { copy(selectedKeyId = "sk-1") }

        vm.selectAuthType(AuthType.SSH_KEY)

        assertEquals(AuthType.SSH_KEY, vm.formState.value.authType)
        assertNull(vm.formState.value.selectedKeyId, "it would be saved out of sight, and fail at connection")
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `switching a host to certificate drops a security key too`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.FIDO2)
        vm.updateForm { copy(selectedKeyId = "sk-2") }

        vm.selectAuthType(AuthType.CERTIFICATE)

        assertNull(vm.formState.value.selectedKeyId)
    }

    @Test
    fun `switching a host to SSH key keeps an ordinary key it was given`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.updateForm { copy(selectedKeyId = "key-1") }

        vm.selectAuthType(AuthType.SSH_KEY)

        assertEquals("key-1", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `switching a host to password keeps whatever was selected`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.FIDO2)
        vm.updateForm { copy(selectedKeyId = "sk-1") }

        vm.selectAuthType(AuthType.PASSWORD)

        assertEquals("sk-1", vm.formState.value.selectedKeyId, "password has no list of keys: nothing to drop")
    }

    @Test
    fun `choosing the authentication already in force changes nothing`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.SSH_KEY)
        vm.updateForm { copy(selectedKeyId = "key-1") }
        val before = vm.formState.value

        vm.selectAuthType(AuthType.SSH_KEY)

        assertEquals(before, vm.formState.value)
    }

    // ── An SSH key host saved with a security key before the lists stopped offering one ──

    @Test
    fun `opening an SSH key host saved with a security key drops the key, the form asks for another`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("sk-1") } returns securityKey
        coJustRun { hostRepository.update(any()) }

        load(vm, fido2Host(mode = null, credentialId = "sk-1", authType = AuthType.SSH_KEY))

        val form = vm.formState.value
        assertEquals(AuthType.SSH_KEY, form.authType)
        assertNull(form.selectedKeyId, "the list of an SSH key host does not show it: it must not stay selected")
        assertFalse(form.selectedKeyMissing, "the key exists, it is only not offered: no missing key alert")
        assertFalse(form.isValid, "a key has to be chosen before the host can be saved")
        vm.saveHost()
        advanceUntilIdle()
        coVerify(exactly = 0) { hostRepository.update(any()) }

        vm.updateForm { copy(selectedKeyId = "key-1") }
        assertTrue(vm.formState.value.isValid)
    }

    @Test
    fun `opening a certificate host saved with a security key drops it too`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("sk-2") } returns securityKeyEcdsa

        load(vm, fido2Host(mode = null, credentialId = "sk-2", authType = AuthType.CERTIFICATE))

        assertNull(vm.formState.value.selectedKeyId)
        assertFalse(vm.formState.value.selectedKeyMissing)
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `opening an SSH key host with an ordinary key keeps the key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("key-1") } returns ordinaryKey

        load(vm, fido2Host(mode = null, credentialId = "key-1", authType = AuthType.SSH_KEY))

        assertEquals("key-1", vm.formState.value.selectedKeyId)
        assertTrue(vm.formState.value.isValid)
    }

    @Test
    fun `opening an SSH key host whose key is no longer in the Vault clears the selection and flags the missing key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("gone") } returns null

        load(vm, fido2Host(mode = null, credentialId = "gone", authType = AuthType.SSH_KEY))

        val form = vm.formState.value
        assertNull(form.selectedKeyId, "the dangling key must not stay selected out of sight")
        assertTrue(form.selectedKeyMissing)
        assertTrue(form.showMissingKeyError)
        assertFalse(form.isValid, "a key has to be chosen before the host can be saved")
    }

    @Test
    fun `the keys of an SSH key host are the ones with a private key in the Vault`() {
        // A biometric key is not one: its private half never leaves the Keystore.
        assertEquals(listOf(ordinaryKey), privateKeysFor(allKeys))
        assertEquals(emptyList<SshKey>(), privateKeysFor(listOf(securityKey, securityKeyEcdsa)))
        assertEquals(emptyList<SshKey>(), privateKeysFor(listOf(biometricKey)))
    }

    @Test
    fun `the keys of a biometric key host are the biometric keys only`() {
        assertEquals(listOf(biometricKey), biometricKeysFor(allKeys))
        assertEquals(emptyList<SshKey>(), biometricKeysFor(listOf(securityKey, securityKeyEcdsa, ordinaryKey)))
        assertEquals(emptyList<SshKey>(), biometricKeysFor(emptyList()))
    }

    @Test
    fun `no key of the Vault is offered to both an SSH key host and a biometric key host`() {
        val both = privateKeysFor(allKeys).toSet() intersect biometricKeysFor(allKeys).toSet()
        assertTrue(both.isEmpty(), "a key in both lists would be picked for the wrong kind of host")
    }

    // ── BIOMETRIC_KEY: a fifth authentication type, with its own list of keys ──

    @Test
    fun `a biometric key host needs a key before it can be saved`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.updateForm { copy(label = "Prod", hostname = "prod.example.com", username = "root") }

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)

        assertEquals(AuthType.BIOMETRIC_KEY, vm.formState.value.authType)
        assertNull(vm.formState.value.selectedKeyId)
        assertFalse(vm.formState.value.isValid, "a key has to be chosen before the host can be saved")

        vm.updateForm { copy(selectedKeyId = "bio-1") }
        assertTrue(vm.formState.value.isValid)
    }

    @Test
    fun `a biometric key host with no biometric key in the Vault cannot be saved`() = runTest {
        val vm = viewModelWithKeys(listOf(ordinaryKey, securityKey))
        vm.updateForm { copy(label = "Prod", hostname = "prod.example.com", username = "root") }

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        vm.saveHost()
        advanceUntilIdle()

        assertFalse(vm.formState.value.isValid)
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `a new biometric key host is saved with the key as its credential`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coJustRun { hostRepository.save(any()) }
        vm.updateForm { copy(label = "Prod", hostname = "prod.example.com", username = "root") }
        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        vm.updateForm { copy(selectedKeyId = "bio-1") }

        vm.saveHost()
        advanceUntilIdle()

        val saved = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(saved)) }
        assertEquals(AuthType.BIOMETRIC_KEY, saved.captured.authType)
        assertEquals("bio-1", saved.captured.credentialId)
        assertNull(saved.captured.fido2Mode, "the FIDO2 mode is of no use to this kind of host")
        // No password is typed or stored: the key is the credential.
        coVerify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `switching a host to a biometric key keeps a biometric key it was given`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.updateForm { copy(selectedKeyId = "bio-1") }

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)

        assertEquals(AuthType.BIOMETRIC_KEY, vm.formState.value.authType)
        assertEquals("bio-1", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `switching a host to a biometric key drops an ordinary key and a security key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.SSH_KEY)
        vm.updateForm { copy(selectedKeyId = "key-1") }

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        assertNull(vm.formState.value.selectedKeyId, "an ordinary key is not in the list of biometric keys")

        vm.selectAuthType(AuthType.FIDO2)
        vm.updateForm { copy(selectedKeyId = "sk-1") }
        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        assertNull(vm.formState.value.selectedKeyId, "nor is a security key")
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `switching a biometric key host to SSH key, certificate or FIDO2 drops the biometric key`() = runTest {
        val vm = viewModelWithKeys(allKeys)

        listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2).forEach { type ->
            vm.selectAuthType(AuthType.BIOMETRIC_KEY)
            vm.updateForm { copy(selectedKeyId = "bio-1") }

            vm.selectAuthType(type)

            assertEquals(type, vm.formState.value.authType)
            assertNull(vm.formState.value.selectedKeyId, "a biometric key is not offered to $type: it must not stay selected")
            assertFalse(vm.formState.value.isValid)
        }
    }

    @Test
    fun `switching a biometric key host to password keeps whatever was selected`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        vm.updateForm { copy(selectedKeyId = "bio-1") }

        vm.selectAuthType(AuthType.PASSWORD)

        assertEquals("bio-1", vm.formState.value.selectedKeyId, "password has no list of keys: nothing to drop")
    }

    @Test
    fun `a biometric key kept through password is dropped when the host goes on to SSH key or certificate`() = runTest {
        val vm = viewModelWithKeys(allKeys)

        listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE).forEach { type ->
            vm.selectAuthType(AuthType.BIOMETRIC_KEY)
            vm.updateForm { copy(selectedKeyId = "bio-1") }
            vm.selectAuthType(AuthType.PASSWORD)
            assertEquals("bio-1", vm.formState.value.selectedKeyId, "kept while the host is on password")

            vm.selectAuthType(type)

            assertEquals(type, vm.formState.value.authType)
            assertNull(vm.formState.value.selectedKeyId, "hidden on password, it must not come back as the $type key")
            assertFalse(vm.formState.value.isValid)
        }
    }

    @Test
    fun `going from password to a biometric key drops a leftover ordinary key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.updateForm { copy(selectedKeyId = "key-1") }

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)

        assertNull(vm.formState.value.selectedKeyId)
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `going from password to a biometric key keeps a leftover biometric key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        vm.updateForm { copy(selectedKeyId = "bio-1") }
        vm.selectAuthType(AuthType.PASSWORD)

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)

        assertEquals("bio-1", vm.formState.value.selectedKeyId)
    }

    @Test
    fun `choosing the biometric key authentication already in force changes nothing`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        vm.selectAuthType(AuthType.BIOMETRIC_KEY)
        vm.updateForm { copy(selectedKeyId = "bio-1") }
        val before = vm.formState.value

        vm.selectAuthType(AuthType.BIOMETRIC_KEY)

        assertEquals(before, vm.formState.value)
    }

    @Test
    fun `opening a biometric key host restores its key, and it can be saved as it is`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("bio-1") } returns biometricKey
        coJustRun { hostRepository.update(any()) }

        load(vm, fido2Host(mode = null, credentialId = "bio-1", authType = AuthType.BIOMETRIC_KEY))

        val form = vm.formState.value
        assertEquals(AuthType.BIOMETRIC_KEY, form.authType)
        assertEquals("bio-1", form.selectedKeyId)
        assertTrue(form.isValid)

        vm.saveHost()
        advanceUntilIdle()

        val saved = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(saved)) }
        assertEquals(AuthType.BIOMETRIC_KEY, saved.captured.authType)
        assertEquals("bio-1", saved.captured.credentialId)
    }

    @Test
    fun `opening a biometric key host whose key is not a biometric key drops it, the form asks for another`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("key-1") } returns ordinaryKey

        load(vm, fido2Host(mode = null, credentialId = "key-1", authType = AuthType.BIOMETRIC_KEY))

        assertNull(vm.formState.value.selectedKeyId, "the list of biometric keys does not show it")
        assertFalse(vm.formState.value.selectedKeyMissing, "the key exists, it is only not offered: no missing key alert")
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `opening a biometric key host whose key is no longer in the Vault clears the selection and flags the missing key`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("gone") } returns null

        load(vm, fido2Host(mode = null, credentialId = "gone", authType = AuthType.BIOMETRIC_KEY))

        val form = vm.formState.value
        assertNull(form.selectedKeyId, "the dangling key must not stay selected out of sight")
        assertTrue(form.selectedKeyMissing)
        assertTrue(form.showMissingKeyError)
        assertFalse(form.isValid, "a key has to be chosen before the host can be saved")
    }

    @Test
    fun `opening an SSH key host saved with a biometric key drops the key, the form asks for another`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("bio-1") } returns biometricKey
        coJustRun { hostRepository.update(any()) }

        load(vm, fido2Host(mode = null, credentialId = "bio-1", authType = AuthType.SSH_KEY))

        val form = vm.formState.value
        assertEquals(AuthType.SSH_KEY, form.authType)
        assertNull(form.selectedKeyId, "the list of an SSH key host does not show it: it must not stay selected")
        assertFalse(form.selectedKeyMissing, "the key exists, it is only not offered: no missing key alert")
        assertFalse(form.isValid, "a key has to be chosen before the host can be saved")
        vm.saveHost()
        advanceUntilIdle()
        coVerify(exactly = 0) { hostRepository.update(any()) }

        vm.updateForm { copy(selectedKeyId = "key-1") }
        assertTrue(vm.formState.value.isValid)
    }

    @Test
    fun `opening a certificate host saved with a biometric key drops it too`() = runTest {
        val vm = viewModelWithKeys(allKeys)
        coEvery { sshKeyRepository.getById("bio-1") } returns biometricKey

        load(vm, fido2Host(mode = null, credentialId = "bio-1", authType = AuthType.CERTIFICATE))

        assertNull(vm.formState.value.selectedKeyId)
        assertFalse(vm.formState.value.selectedKeyMissing)
        assertFalse(vm.formState.value.isValid)
    }

    @Test
    fun `the keys of each FIDO2 mode are the security keys, or the ordinary keys`() {
        assertEquals(
            listOf(securityKey, securityKeyEcdsa),
            fido2KeysFor(Fido2Mode.HARDWARE_KEY, allKeys),
        )
        // PASSKEY signs in with a private key of the Vault: neither a security key nor a biometric key has one.
        assertEquals(listOf(ordinaryKey), fido2KeysFor(Fido2Mode.PASSKEY, allKeys))
        assertEquals(emptyList<SshKey>(), fido2KeysFor(Fido2Mode.HARDWARE_KEY, listOf(ordinaryKey)))
    }

    // ── Mot de passe d'un hôte : jamais sous l'id d'une clé SSH ─────────────

    private val storedPasswords = mutableMapOf<String, String>()

    /** Enregistre le contenu de chaque storePassword au moment de l'appel (le vrai wipe le tableau). */
    private fun recordStoredPasswords() {
        every { vaultManager.storePassword(any(), any()) } answers {
            storedPasswords[firstArg()] = secondArg<CharArray>().concatToString()
        }
    }

    private fun makeSshKey(id: String) = SshKey(
        id = id,
        label = "Key $id",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    private fun makeHostWith(authType: AuthType, credentialId: String) = Host(
        id = "h1",
        label = "Host h1",
        hostname = "hosth1.example.com",
        username = "user",
        authType = authType,
        credentialId = credentialId,
    )

    @Test
    fun `saveHost gives a new credentialId to a key host switched to PASSWORD`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD, password = "typed") }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        val newId = hostSlot.captured.credentialId
        assertNotEquals("key-1", newId, "a PASSWORD host must not reuse the SSH key id")
        assertTrue(newId.isNotBlank())
        assertEquals(mapOf(newId to "typed"), storedPasswords)
        // Le mot de passe retapé suffit : l'ancien slot n'est ni lu ni supprimé.
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost repairs a PASSWORD host holding a key id and carries its password over`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns "kept".toCharArray()
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        val newId = hostSlot.captured.credentialId
        assertNotEquals("key-1", newId)
        assertEquals(mapOf(newId to "kept"), storedPasswords)
        // pwd_key-1 reste en place : un tombstone pwd:key-1 emporterait la clé chez un pair 1.0.0.
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost does not copy the password of another host onto a key host switched to PASSWORD`() = runTest {
        // pwd_key-1 peut appartenir à un autre hôte PASSWORD resté sur l'id de la clé (défaut 1.0.0).
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the credentialId of a PASSWORD host that is not a key id`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("cred-h1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `saveHost keeps the old id when the old password cannot be read`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } throws VaultAuthExpiredException()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        // Statu quo ante : l'hôte n'est pas détaché de son mot de passe, il sera réparé plus tard.
        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the old id when storing the carried over password fails`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns existing
        every { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertTrue(existing.all { it == '\u0000' }, "the password read must be wiped even when the store fails")
    }

    @Test
    fun `saveHost moves a repaired host to a new id even when there is no password to carry`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns null

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost wipes the password it read for the carry over`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns existing
        // Ce mock ne wipe PAS son argument (le vrai storePassword le fait) : seul le ViewModel peut le zéroter.
        every { vaultManager.storePassword(any(), any()) } just Runs

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertTrue(existing.all { it == '\u0000' }, "the CharArray read from the vault must be zeroed")
    }

    @Test
    fun `saveHost does not carry a password shared with another live host and repairs the second host later`() = runTest {
        val first = makeHostWith(AuthType.PASSWORD, "key-1")
        val second = first.copy(id = "h2", label = "Host h2")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()

        // 1) Deux hôtes PASSWORD sur le même id de clé : enregistrer le premier sans retaper le mot
        //    de passe ne recopie rien, ne supprime rien et le laisse sur l'id partagé.
        coEvery { hostRepository.getById("h1") } returns first
        every { hostRepository.observeAll() } returns flowOf(listOf(first, second))
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val firstSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(firstSlot)) }
        assertEquals("key-1", firstSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }

        // 2) Le premier repasse sur un slot isolé (mot de passe retapé) : le second n'est plus
        //    partagé, sa réparation recopie le mot de passe existant.
        val firstRepaired = first.copy(credentialId = "isolated-1")
        coEvery { hostRepository.getById("h2") } returns second
        every { hostRepository.observeAll() } returns flowOf(listOf(firstRepaired, second))
        every { vaultManager.getPassword("key-1") } returns "kept".toCharArray()
        viewModel.loadHostForEdit("h2")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val updates = mutableListOf<Host>()
        coVerify(exactly = 2) { hostRepository.update(capture(updates)) }
        assertEquals("h2", updates.last().id)
        val newId = updates.last().credentialId
        assertNotEquals("key-1", newId)
        assertEquals(mapOf(newId to "kept"), storedPasswords)
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost gives a new id and copies nothing to a BIOMETRIC_KEY host switched to PASSWORD`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.BIOMETRIC_KEY, "key-bio")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-bio", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost stores the typed password of a new host under the id it saves`() = runTest {
        coJustRun { hostRepository.save(any()) }
        recordStoredPasswords()

        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "typed",
            )
        }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        val savedId = hostSlot.captured.credentialId
        assertTrue(savedId.isNotBlank())
        assertEquals(mapOf(savedId to "typed"), storedPasswords)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
    }

    @Test
    fun `saveHost keeps the id and copies nothing for PASSWORD then SSH_KEY then PASSWORD in one session`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.SSH_KEY) }
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("cred-h1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    // ── Clé disparue : plus de sélection invisible ──────────────────────────

    @Test
    fun `loadHostForEdit clears the selection and flags a key that no longer exists`() = runTest {
        // sshKeyRepository.getById renvoie null par défaut : la clé n'existe plus.
        for (authType in listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2, AuthType.BIOMETRIC_KEY)) {
            coEvery { hostRepository.getById("h1") } returns makeHostWith(authType, "key-gone")

            viewModel.loadHostForEdit("h1")
            advanceUntilIdle()

            val form = viewModel.formState.value
            assertNull(form.selectedKeyId, "$authType: the dangling key must not stay selected")
            assertTrue(form.selectedKeyMissing, "$authType: the missing key must be flagged")
            assertTrue(form.showMissingKeyError, "$authType: the error must be shown")
            assertFalse(form.isValid, "$authType: the form must be invalid until a key is chosen")
        }
    }

    @Test
    fun `the missing key error is only shown while no key is selected`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertTrue(viewModel.formState.value.showMissingKeyError)

        // Une sélection posée sans passer par selectKey masque déjà l'erreur, même si le drapeau reste levé.
        viewModel.updateForm { copy(selectedKeyId = "key-2") }
        assertTrue(viewModel.formState.value.selectedKeyMissing)
        assertFalse(viewModel.formState.value.showMissingKeyError)
    }

    @Test
    fun `loadHostForEdit keeps the selection of a key that still exists`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertEquals("key-1", form.selectedKeyId)
        assertFalse(form.selectedKeyMissing)
        assertTrue(form.isValid)
    }

    @Test
    fun `loadHostForEdit does not flag a PASSWORD host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertFalse(form.selectedKeyMissing)
        assertNull(form.selectedKeyId)
        assertTrue(form.isValid)
    }

    @Test
    fun `selectKey clears the missing key flag and makes the form valid`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertFalse(viewModel.formState.value.isValid)

        viewModel.selectKey("key-2")

        val form = viewModel.formState.value
        assertEquals("key-2", form.selectedKeyId)
        assertFalse(form.selectedKeyMissing)
        assertFalse(form.showMissingKeyError)
        assertTrue(form.isValid)
    }

    @Test
    fun `saveHost writes nothing while the key of the host is missing`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `saveHost stores the newly chosen key instead of the dangling one`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        coJustRun { hostRepository.update(any()) }
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.selectKey("key-2")
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-2", hostSlot.captured.credentialId)
    }

    @Test
    fun `a host with a missing key switched to PASSWORD does not keep the dead key id`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.updateForm { copy(authType = AuthType.PASSWORD, password = "typed") }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-gone", hostSlot.captured.credentialId)
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
    }

    // ── Suppression d'un hôte : son mot de passe part avec lui ──────────────

    private fun TestScope.collectEvents(): List<HostEvent> {
        val events = mutableListOf<HostEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }
        return events
    }

    @Test
    fun `deleteHost deletes the password of a PASSWORD host`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        // Le registre peut encore lister l'hôte lui-même : il ne compte pas comme "un autre".
        every { hostRepository.observeAll() } returns
            flowOf(listOf(host, host.copy(id = "h2", credentialId = "cred-h2")))
        every { vaultManager.deletePassword("cred-h1") } returns Unit
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        // La ligne de l'hôte est supprimée d'abord : un échec du vault ne peut pas la retenir.
        coVerifyOrder {
            hostRepository.delete("h1")
            vaultManager.deletePassword("cred-h1")
        }
        verify(exactly = 1) { vaultManager.deletePassword("cred-h1") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost keeps the password slot when its id is an SSH key id`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost keeps the password slot while another host still references it`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-shared")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { hostRepository.observeAll() } returns flowOf(listOf(host.copy(id = "h2")))

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `deleteHost still deletes the host when the vault refuses`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { vaultManager.deletePassword("cred-h1") } throws VaultAuthExpiredException()
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost still emits HostDeleted when the key lookup fails after the soft delete`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("cred-h1") } throws IllegalStateException("db closed")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost still emits HostDeleted when the shared id lookup fails after the soft delete`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { hostRepository.observeAll() } returns flow { throw IllegalStateException("db closed") }
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost does not swallow a cancellation during the password cleanup`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("cred-h1") } throws CancellationException("cancelled")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        assertTrue(events.isEmpty(), "a cancelled deletion must not report HostDeleted")
    }

    @Test
    fun `deleteHost never touches the vault for a key host or an unknown host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { hostRepository.getById("h2") } returns null
        coJustRun { hostRepository.delete(any()) }

        viewModel.deleteHost("h1")
        viewModel.deleteHost("h2")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        coVerify(exactly = 1) { hostRepository.delete("h2") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the key id as credentialId for a key host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
    }

    // ── Vault reverrouillé à l'enregistrement : ré-authentification ─────────

    private val successCallbacks = mutableListOf<(BiometricPrompt.AuthenticationResult) -> Unit>()
    private val errorCallbacks = mutableListOf<(Int, String) -> Unit>()

    /**
     * Remplace l'invite système, demandée au coordinateur. Chaque invite attend une réponse, que les
     * tests donnent à la main par les rappels de CHAQUE invite, empilés dans [successCallbacks] et
     * [errorCallbacks] (rang 0 = première invite) : un succès, ou un code d'erreur de BiometricPrompt
     * traduit comme le fait le coordinateur ([biometricResponseForError]). Une invite ne répond
     * qu'une fois : le second rappel de la même invite est sans effet.
     */
    private fun stubPrompt() {
        coEvery { biometricPrompts.authenticateVault(any()) } coAnswers {
            val answer = CompletableDeferred<BiometricPromptResponse?>()
            successCallbacks += { _: BiometricPrompt.AuthenticationResult -> answer.complete(BiometricPromptResponse.Authorized) }
            errorCallbacks += { errorCode: Int, _: String -> answer.complete(biometricResponseForError(errorCode)) }
            answer.await()
        }
    }

    private fun verifyPromptShown(times: Int) {
        coVerify(exactly = times) { biometricPrompts.authenticateVault(any()) }
    }

    private fun fillNewPasswordHost() {
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "typed",
            )
        }
    }

    /** Rôle de l'écran RESUMED : lance l'invite pour la demande de ré-authentification en attente. */
    private fun confirmIdentity() {
        viewModel.confirmIdentityAndSave()
    }

    /** Le vault refuse toute écriture de mot de passe : authentification expirée. */
    private fun vaultExpired() {
        every { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()
    }

    /**
     * Les [writes] premières écritures sont refusées (authentification expirée), les suivantes
     * réussissent et laissent leur contenu dans [storedPasswords].
     */
    private fun vaultExpiredForFirst(writes: Int) {
        var calls = 0
        every { vaultManager.storePassword(any(), any()) } answers {
            if (calls++ < writes) throw VaultAuthExpiredException()
            storedPasswords[firstArg()] = secondArg<CharArray>().concatToString()
        }
    }

    /** Enregistre le formulaire courant jusqu'à l'ouverture de l'invite (vault expiré à l'appui). */
    private fun TestScope.saveUntilPromptShown() {
        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
    }

    @Test
    fun `saveHost with an expired vault writes nothing and waits for the re-authentication`() = runTest {
        vaultExpired()
        val events = collectEvents()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals(formBefore, viewModel.formState.value, "the form must stay untouched")
        assertNull(viewModel.saveError.value, "no message yet: the prompt is about to be shown")
        verifyPromptShown(times = 0)
    }

    @Test
    fun `the re-authentication request is kept until the screen launches the prompt`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        // Écran pas RESUMED (activité arrêtée, recréée) : personne n'appelle confirmIdentityAndSave.
        // La demande n'est ni perdue ni consommée, et Enregistrer reste désactivé.
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        verifyPromptShown(times = 0)
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)

        // L'écran revient au premier plan : la demande part, une seule fois.
        confirmIdentity()
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
        verifyPromptShown(times = 1)
    }

    @Test
    fun `an expired vault on an existing host also writes nothing`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        vaultExpired()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals("h1", viewModel.formState.value.id)
        assertEquals("retyped", viewModel.formState.value.password)
    }

    @Test
    fun `a successful prompt runs the save again and the typed password reaches the vault`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
        verifyPromptShown(times = 1)
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        // Le refus n'a rien écrit : seul le second essai laisse le mot de passe, sous l'id de l'hôte.
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
        // Le mot de passe part dans le vault avant la ligne de l'hôte (deux essais : refus, puis succès).
        // Le nouvel hôte reçoit un autre id à la relance, puisque le refus n'a rien écrit.
        coVerifyOrder {
            vaultManager.storePassword(any(), any())
            vaultManager.storePassword(hostSlot.captured.credentialId, any())
            hostRepository.save(any())
        }
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value)
        // Only the password leaves the form: the screen is leaving, its options must not flip to their defaults.
        assertEquals(formBefore.copy(password = ""), viewModel.formState.value)
    }

    @Test
    fun `a successful prompt on an existing host saves it through update`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }
        vaultExpiredForFirst(writes = 1)
        stubPrompt()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals("h1", hostSlot.captured.id)
        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `the prompt is the confirm-save prompt of the coordinator, the screen gives it no activity`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.confirmIdentityAndSave()

        coVerify(exactly = 1) { biometricPrompts.authenticateVault(VaultAuthPurpose.CONFIRM_SAVE) }
    }

    // ── Sous l'écran de verrou, la demande attend le déverrouillage ─────────

    @Test
    fun `under the lock screen the re-authentication request waits, intact, and no prompt is opened`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()
        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)

        appLock.lock() // the app locked while the user was filling the form
        confirmIdentity()
        confirmIdentity()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value, "neither consumed nor lost")
        verifyPromptShown(times = 0)
        assertNull(viewModel.saveError.value)
        assertTrue(viewModel.appLocked.value, "the screen sees the lock and waits for it")
    }

    @Test
    fun `the request goes out once the app is unlocked, the screen asks again when the lock falls`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()
        appLock.lock()
        confirmIdentity()
        verifyPromptShown(times = 0)

        appLock.unlock()
        assertFalse(viewModel.appLocked.value)
        confirmIdentity()

        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
        verifyPromptShown(times = 1)
    }

    @Test
    fun `a vault that refuses the write of the host does not lock the app, the save keeps its own retry`() = runTest {
        vaultExpired()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `a prompt that nobody answered in time shows the vault unavailable message`() = runTest {
        vaultExpired()
        coEvery { biometricPrompts.authenticateVault(any()) } returns null
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        assertEquals(formBefore, viewModel.formState.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a prompt cancelled by the system because the app was left says the vault stayed locked`() = runTest {
        vaultExpired()
        coEvery { biometricPrompts.authenticateVault(any()) } returns BiometricPromptResponse.Cancelled
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()

        assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancelled prompt keeps the form, shows the locked message and writes nothing`() = runTest {
        val cancelCodes = listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            // Annulation par le système (application passée en arrière-plan) : « resté verrouillé » est exact.
            BiometricPrompt.ERROR_CANCELED,
        )
        vaultExpired()
        stubPrompt()
        for (code in cancelCodes) {
            viewModel.resetForm()
            viewModel.dismissSaveError()
            fillNewPasswordHost()
            val formBefore = viewModel.formState.value

            saveUntilPromptShown()
            errorCallbacks.last()(code, "system text that must never be shown")
            advanceUntilIdle()

            assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value, "code $code")
            assertEquals(formBefore, viewModel.formState.value, "code $code: the form must stay intact")
            assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "code $code: Save must be enabled again")
        }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    @Test
    fun `saving again after a cancelled prompt asks for authentication again`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks.last()(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value, "the previous message is cleared by the new attempt")
        confirmIdentity()
        verifyPromptShown(times = 2)
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
    }

    @Test
    fun `a prompt failing for another reason shows the vault unavailable message`() = runTest {
        val failureCodes = listOf(
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_TIMEOUT,
        )
        vaultExpired()
        stubPrompt()
        for (code in failureCodes) {
            viewModel.resetForm()
            viewModel.dismissSaveError()
            fillNewPasswordHost()
            val formBefore = viewModel.formState.value

            saveUntilPromptShown()
            errorCallbacks.last()(code, "Too many attempts. Try again later.")
            advanceUntilIdle()

            assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value, "code $code")
            assertEquals(formBefore, viewModel.formState.value, "code $code: the form must stay intact")
            assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "code $code")
        }
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `a vault still expired after a successful prompt fails without a second prompt`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        verifyPromptShown(times = 1)
        verify(exactly = 2) { vaultManager.storePassword(any(), any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals(formBefore, viewModel.formState.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "no AWAITING_REAUTH loop")
    }

    @Test
    fun `any other vault failure shows the vault unavailable message and keeps the form`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws IllegalStateException("keystore broke")
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals(formBefore, viewModel.formState.value)
        // Pas d'invite : s'authentifier n'y change rien.
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancellation during the vault write is not turned into a message`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws CancellationException("cancelled")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        assertNull(viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "Save must not stay disabled after a cancellation")
    }

    @Test
    fun `the password copy is wiped when the vault refuses the write`() = runTest {
        val passwords = mutableListOf<CharArray>()
        every { vaultManager.storePassword(any(), capture(passwords)) } throws VaultAuthExpiredException()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        assertTrue(passwords.single().all { it == '\u0000' }, "the CharArray handed to the vault must be zeroed")
    }

    // ── Un seul rappel terminal par invite ──────────────────────────────────

    /**
     * Ouvre une invite sur un nouvel hôte, puis déclenche DEUX rappels l'un après l'autre :
     * [firstSuccess] et [secondSuccess] disent s'il s'agit d'un succès ou d'une erreur. Les
     * erreurs ont des codes distincts (annulation, puis verrouillage) pour qu'un rappel pris à tort
     * se voie dans le message. L'écriture de l'hôte est suspendue entre les deux rappels : le
     * doublon arrive alors que la relance du premier est encore en vol, le cas où il ferait un
     * second enregistrement.
     */
    private fun TestScope.fireTwoCallbacks(firstSuccess: Boolean, secondSuccess: Boolean): List<HostEvent> {
        // Succès en premier : la relance trouve un vault déverrouillé. Erreur en premier : toujours expiré.
        vaultExpiredForFirst(writes = if (firstSuccess) 1 else Int.MAX_VALUE)
        val hostWriteGate = CompletableDeferred<Unit>()
        coEvery { hostRepository.save(any()) } coAnswers { hostWriteGate.await() }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()
        saveUntilPromptShown()

        fun fire(success: Boolean, errorCode: Int) {
            if (success) successCallbacks.last()(mockk()) else errorCallbacks.last()(errorCode, "x")
            advanceUntilIdle()
        }
        fire(firstSuccess, BiometricPrompt.ERROR_USER_CANCELED)
        fire(secondSuccess, BiometricPrompt.ERROR_LOCKOUT)
        hostWriteGate.complete(Unit)
        advanceUntilIdle()
        return events
    }

    @Test
    fun `a duplicate success callback does not save a second host`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = true, secondSuccess = true)

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(1, storedPasswords.size)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `a late error callback after a success shows no error`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = true, secondSuccess = false)

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value, "an error after a success must be ignored")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a late success callback after an error does not save`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = false, secondSuccess = true)

        assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertTrue(events.isEmpty())
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a duplicate error callback keeps the first message`() = runTest {
        fireTwoCallbacks(firstSuccess = false, secondSuccess = false)

        assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value, "the second error must not overwrite the first")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a late callback of an older prompt cannot answer the current prompt`() = runTest {
        vaultExpiredForFirst(writes = 2)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks[0](BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        saveUntilPromptShown()
        // Le succès tardif de la première invite arrive pendant la seconde : il est ignoré.
        successCallbacks[0](mockk())
        advanceUntilIdle()

        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 2) { vaultManager.storePassword(any(), any()) }

        successCallbacks[1](mockk())
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Un seul enregistrement à la fois ────────────────────────────────────

    @Test
    fun `a second tap while a save is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { hostRepository.save(any()) } coAnswers { gate.await() }
        recordStoredPasswords()
        collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        assertEquals(HostSavePhase.SAVING, viewModel.savePhase.value, "Save is disabled while the host is being written")
        viewModel.saveHost()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(1, storedPasswords.size)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a tap while the prompt is pending does not ask twice`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
        // L'écran qui rappelle la confirmation, ou un appui sur Enregistrer, ne rouvre pas d'invite.
        confirmIdentity()
        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        verifyPromptShown(times = 1)
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
    }

    @Test
    fun `an invalid form leaves the save state idle and does not block a later save`() = runTest {
        recordStoredPasswords()
        coJustRun { hostRepository.save(any()) }
        val events = collectEvents()

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)

        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
    }

    // ── Cas limites de la ré-authentification ───────────────────────────────

    @Test
    fun `confirmIdentityAndSave does nothing when no re-authentication was requested`() = runTest {
        confirmIdentity()

        verifyPromptShown(times = 0)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a prompt that throws on opening shows the failure message instead of crashing`() = runTest {
        vaultExpired()
        coEvery { biometricPrompts.authenticateVault(any()) } throws IllegalStateException("activity destroyed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `dismissSaveError clears the message`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws IllegalStateException("keystore broke")
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()
        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)

        viewModel.dismissSaveError()

        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `a key host saves normally even when the vault is expired`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        vaultExpired()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.update(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a biometric key host also saves normally when the vault is expired`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.BIOMETRIC_KEY, "bio-1")
        coEvery { sshKeyRepository.getById("bio-1") } returns biometricKey
        coJustRun { hostRepository.update(any()) }
        vaultExpired()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("bio-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Vault reverrouillé et garde-fous de la 1.1.0 : ce qu'ils changent l'un pour l'autre ─

    @Test
    fun `a host deleted meanwhile is reported without asking for the vault even when it is expired`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        // load, then the stored row is gone (a sync deleted it) when saving
        coEvery { hostRepository.getById("h1") } returnsMany listOf(host, null)
        vaultExpired()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.HostNoLongerExists), events)
        // The row is read before any write: nothing reached the vault, so there was nothing to refuse.
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals("", viewModel.formState.value.password)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a host deleted during the re-authentication is reported by the retry and nothing is written`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        // load, first attempt (vault refuses), retry after the prompt (row gone)
        coEvery { hostRepository.getById("h1") } returnsMany listOf(host, host, null)
        vaultExpiredForFirst(writes = 1)
        stubPrompt()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        assertEquals(listOf<HostEvent>(HostEvent.HostNoLongerExists), events)
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertTrue(storedPasswords.isEmpty(), "the retry stopped before writing the password")
        assertEquals("", viewModel.formState.value.password)
        assertNull(viewModel.saveError.value)
        verifyPromptShown(times = 1)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a second tap after a save that went through the re-authentication saves once`() = runTest {
        // The form stays valid after a save, so the guard is what stops a second new host.
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        verifyPromptShown(times = 1)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `reopening the screen for a new host after a save lets it save again`() = runTest {
        coJustRun { hostRepository.save(any()) }
        recordStoredPasswords()
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()

        // What HostDetailScreen does on entry for a new host.
        viewModel.resetForm()
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 2) { hostRepository.save(any()) }
    }

    // ── Panne hors vault : plus aucune exception ne sort de l'enregistrement ─

    @Test
    fun `a repository failure shows the save failed message, keeps the form and re-enables Save`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        val events = collectEvents()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertEquals(formBefore, viewModel.formState.value, "the form must stay intact")
        assertTrue(events.isEmpty())

        // Enregistrer est de nouveau utilisable, et le nouvel essai efface le message.
        coJustRun { hostRepository.save(any()) }
        viewModel.saveHost()
        advanceUntilIdle()

        assertNull(viewModel.saveError.value)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
    }

    @Test
    fun `a failing lookup of the existing host shows the save failed message`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        coEvery { hostRepository.getById("h1") } throws IllegalStateException("db closed")
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertEquals(formBefore, viewModel.formState.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    @Test
    fun `a failure while repairing the credential id shows the save failed message`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        // La recopie du mot de passe lit la liste des hôtes : cette lecture échoue.
        every { hostRepository.observeAll() } returns flow { throw IllegalStateException("db closed") }

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    // ── Relance sur le formulaire de l'appui ────────────────────────────────

    @Test
    fun `the retry saves the form of the tap even if the screen reset it meanwhile`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        // Activité recréée pendant l'invite : l'effet d'initialisation de l'écran rejoue resetForm().
        viewModel.resetForm()
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value, "a reset form must not turn the retry into a silent loss")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `the retry keeps the retyped password of an existing host reloaded meanwhile`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }
        vaultExpiredForFirst(writes = 1)
        stubPrompt()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()
        // Activité recréée : l'effet de l'écran recharge l'hôte depuis la base, sans le mot de passe retapé.
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertEquals("", viewModel.formState.value.password)
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.update(any()) }
        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `the retry saves the form as of the tap, not later edits`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.updateForm { copy(label = "Edited later") }
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
    }

    @Test
    fun `a second save after a cancelled prompt works on the form of its own tap`() = runTest {
        vaultExpiredForFirst(writes = 2)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        collectEvents()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks.last()(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        viewModel.updateForm { copy(label = "Second try") }
        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("Second try", hostSlot.captured.label, "the first tap's snapshot must not be reused")
    }

    // ── Slot de mot de passe orphelin ───────────────────────────────────────

    @Test
    fun `a host row failure on a new host deletes the password slot it just wrote`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        val writtenId = storedPasswords.keys.single()
        verify(exactly = 1) { vaultManager.deletePassword(any()) }
        coVerifyOrder {
            vaultManager.storePassword(writtenId, any())
            vaultManager.deletePassword(writtenId)
        }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a host row failure on an existing host never deletes its password slot`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.update(any()) } throws IllegalStateException("db closed")
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords, "the password was written before the failure")
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
    }

    @Test
    fun `a host row failure on a new key host deletes nothing`() = runTest {
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.SSH_KEY,
                selectedKeyId = "key-2",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
    }

    @Test
    fun `a failing cleanup of the orphan slot does not hide the save failure`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } throws IllegalStateException("keystore broke")
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancelled host row write on a new host still deletes the orphan slot`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws CancellationException("cancelled")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.deletePassword(storedPasswords.keys.single()) }
        assertNull(viewModel.saveError.value, "a cancellation is not an error to show")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Annulation pendant la recopie d'un mot de passe ─────────────────────

    @Test
    fun `a cancellation while reading the password to carry over is not swallowed`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        every { vaultManager.getPassword("key-1") } throws CancellationException("cancelled")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancellation while writing the carried over password is not swallowed`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        every { vaultManager.getPassword("key-1") } returns existing
        every { vaultManager.storePassword(any(), any()) } throws CancellationException("cancelled")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertTrue(existing.all { it == '\u0000' }, "the password read must be wiped even on a cancellation")
    }

    // ── Fin de vie du ViewModel ─────────────────────────────────────────────

    /** Détruit [vm] comme le fait le framework quand son écran disparaît (onCleared est protégé). */
    private fun clearViewModel(vm: HostViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[HostViewModel::class.java]
        store.clear()
    }

    @Test
    fun `onCleared drops the typed password`() = runTest {
        fillNewPasswordHost()
        assertEquals("typed", viewModel.formState.value.password)

        clearViewModel(viewModel)

        assertEquals(HostFormState(), viewModel.formState.value)
    }

    @Test
    fun `a prompt callback arriving after the ViewModel is cleared saves nothing`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        fillNewPasswordHost()

        saveUntilPromptShown()
        clearViewModel(viewModel)
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertEquals(HostFormState(), viewModel.formState.value)
    }

    // ── Écritures du vault hors du thread principal ─────────────────────────

    /** Dispatcher d'IO factice : exécute le bloc sur place et dit s'il est en train de le faire. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var running = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val previous = running
            running = true
            try {
                block.run()
            } finally {
                running = previous
            }
        }
    }

    @Test
    fun `the vault write runs on the injected IO dispatcher`() = runTest {
        val io = RecordingDispatcher()
        var wroteOnIo = false
        every { vaultManager.storePassword(any(), any()) } answers { wroteOnIo = io.running }
        coJustRun { hostRepository.save(any()) }
        val ioViewModel = newViewModel(io)

        ioViewModel.updateForm {
            copy(label = "New Host", hostname = "new.example.com", username = "user", password = "typed")
        }
        ioViewModel.saveHost()
        advanceUntilIdle()

        assertTrue(wroteOnIo, "storePassword must run inside the IO dispatcher")
        coVerify(exactly = 1) { hostRepository.save(any()) }
    }

    @Test
    fun `the carried over password is read and written on the injected IO dispatcher`() = runTest {
        val io = RecordingDispatcher()
        var readOnIo = false
        var wroteOnIo = false
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } answers {
            readOnIo = io.running
            "kept".toCharArray()
        }
        every { vaultManager.storePassword(any(), any()) } answers { wroteOnIo = io.running }
        val ioViewModel = newViewModel(io)

        ioViewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        ioViewModel.saveHost()
        advanceUntilIdle()

        assertTrue(readOnIo, "getPassword must run inside the IO dispatcher")
        assertTrue(wroteOnIo, "storePassword must run inside the IO dispatcher")
    }
}
