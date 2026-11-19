// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Deleting a host from the list stops its running tunnels first: the delete only tombstones rows, and a
 * tunnel left running would keep forwarding with no row in the tunnel list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostViewModelDeleteTest {

    private lateinit var scope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (::scope.isInitialized) scope.cancel()
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private class Env(
        val viewModel: HostViewModel,
        val hostRepository: HostRepository,
        val tunnelService: DesktopTunnelService,
        val events: MutableList<String>,
    )

    private fun newEnv(): Env {
        scope = CoroutineScope(UnconfinedTestDispatcher())
        val events = mutableListOf<String>()
        val folderRepository = mockk<DesktopHostFolderRepository>()
        every { folderRepository.observeAllWithHosts() } returns flowOf(emptyList<HostFolderWithHosts>())
        val sessionManager = mockk<DesktopSessionManager>()
        every { sessionManager.tabs } returns MutableStateFlow(emptyList<SessionTab>())
        val hostRepository = mockk<HostRepository>()
        coEvery { hostRepository.delete("h1") } coAnswers { events += "delete" }
        val tunnelService = mockk<DesktopTunnelService>()
        coEvery { tunnelService.stopTunnelsOfHost("h1") } coAnswers { events += "stop" }
        val viewModel = HostViewModel(
            folderRepository = folderRepository,
            sessionManager = sessionManager,
            hostRepository = hostRepository,
            appScope = TestAppScope(scope),
            tunnelService = tunnelService,
        )
        return Env(viewModel, hostRepository, tunnelService, events)
    }

    @Test
    fun `deleteHost stops the host tunnels before deleting the host`() {
        val env = newEnv()

        env.viewModel.deleteHost("h1")

        assertEquals(listOf("stop", "delete"), env.events)
        coVerify(exactly = 1) { env.tunnelService.stopTunnelsOfHost("h1") }
        coVerify(exactly = 1) { env.hostRepository.delete("h1") }
    }

    @Test
    fun `deleteHost goes on with the delete when stopping the tunnels fails`() {
        val env = newEnv()
        coEvery { env.tunnelService.stopTunnelsOfHost("h1") } throws IllegalStateException("socket error")

        env.viewModel.deleteHost("h1")

        assertEquals(listOf("delete"), env.events)
    }

    @Test
    fun `deleteHost does not swallow a cancellation`() {
        val env = newEnv()
        coEvery { env.tunnelService.stopTunnelsOfHost("h1") } throws CancellationException("scope cancelled")

        env.viewModel.deleteHost("h1")

        assertEquals(emptyList<String>(), env.events, "a cancelled delete must not carry on and delete the host")
    }
}
