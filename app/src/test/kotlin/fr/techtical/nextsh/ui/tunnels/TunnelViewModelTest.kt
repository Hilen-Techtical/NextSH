// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.service.TunnelServiceController
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: TunnelViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { tunnelRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { tunnelManager.tunnelStates } returns MutableStateFlow<Map<String, TunnelState>>(emptyMap())
        every { browserSessionHolder.activeTunnelId } returns MutableStateFlow(null)
        every { tunnelService.ensureRunning(any()) } returns true

        viewModel = TunnelViewModel(
            tunnelRepository = tunnelRepository,
            hostRepository = hostRepository,
            tunnelManager = tunnelManager,
            startTunnel = startTunnel,
            stopTunnel = stopTunnel,
            autoStartTunnelsUseCase = autoStartTunnelsUseCase,
            browserSessionHolder = browserSessionHolder,
            tunnelService = tunnelService,
        )
    }

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
