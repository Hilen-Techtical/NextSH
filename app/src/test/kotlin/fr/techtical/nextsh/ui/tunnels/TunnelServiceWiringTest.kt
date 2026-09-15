// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.AutoStartTunnelsUseCase
import fr.techtical.nextsh.domain.usecase.StartTunnelUseCase
import fr.techtical.nextsh.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.service.TunnelServiceController
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.ui.browser.BrowserSessionHolder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifie le chainon qui manquait avant la migration API 36 :
 * [TunnelForegroundService] etait declare au manifest mais aucun code ne le
 * demarrait, si bien que les tunnels mouraient des que le systeme mettait le
 * process en cache.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelServiceWiringTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val tunnelRepository: TunnelRepository = mockk(relaxed = true)
    private val hostRepository: HostRepository = mockk(relaxed = true)
    private val tunnelManager: SshTunnelManager = mockk(relaxed = true)
    private val startTunnel: StartTunnelUseCase = mockk()
    private val stopTunnel: StopTunnelUseCase = mockk()
    private val autoStartTunnelsUseCase: AutoStartTunnelsUseCase = mockk()
    private val browserSessionHolder: BrowserSessionHolder = mockk(relaxed = true)
    private val tunnelService: TunnelServiceController = mockk()

    private val config = TunnelConfig(
        id = "t1",
        label = "n8n",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 5678,
        remoteHost = "127.0.0.1",
        remotePort = 5678,
    )

    private fun viewModel(): TunnelViewModel {
        every { tunnelRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { tunnelManager.tunnelStates } returns MutableStateFlow<Map<String, TunnelState>>(emptyMap())
        every { browserSessionHolder.activeTunnelId } returns MutableStateFlow(null)
        return TunnelViewModel(
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

    @BeforeEach
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `demarrer un tunnel demarre le service au premier plan`() = runTest {
        every { tunnelService.ensureRunning(any()) } returns true
        coEvery { startTunnel(config) } returns SshResult.Success(Unit)

        viewModel().toggleTunnel(config)
        advanceUntilIdle()

        verify(exactly = 1) { tunnelService.ensureRunning("n8n") }
    }

    @Test
    fun `un echec de demarrage ne demarre pas le service`() = runTest {
        every { tunnelService.ensureRunning(any()) } returns true
        coEvery { startTunnel(config) } returns SshResult.Error(SshErrorCode.PORT_IN_USE, "Port occupe")

        viewModel().toggleTunnel(config)
        advanceUntilIdle()

        verify(exactly = 0) { tunnelService.ensureRunning(any()) }
    }

    @Test
    fun `un refus du systeme est signale a l utilisateur`() = runTest {
        every { tunnelService.ensureRunning(any()) } returns false
        coEvery { startTunnel(config) } returns SshResult.Success(Unit)

        val vm = viewModel()
        val events = mutableListOf<TunnelEvent>()
        // Dispatcher non confine explicite : le scope de runTest est sequentiel,
        // le collecteur ne demarrerait qu'apres l'emission et raterait l'evenement
        // (SharedFlow sans replay ni tampon).
        val job = backgroundScope.launch(testDispatcher) { vm.events.toList(events) }

        vm.toggleTunnel(config)
        advanceUntilIdle()
        job.cancel()

        assertTrue(
            events.any { it is TunnelEvent.ForegroundServiceRefused },
            "l'utilisateur doit etre averti que le tunnel ne survivra pas en arriere-plan",
        )
    }

    @Test
    fun `l auto-demarrage demarre aussi le service`() = runTest {
        every { tunnelService.ensureRunning(any()) } returns true
        coEvery { autoStartTunnelsUseCase() } returns listOf(config.id to SshResult.Success(Unit))
        coEvery { tunnelRepository.getById(config.id) } returns config

        viewModel().autoStartTunnels()
        advanceUntilIdle()

        verify(exactly = 1) { tunnelService.ensureRunning("n8n") }
    }
}
