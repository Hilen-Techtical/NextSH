// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.usecase.StopTunnelUseCase
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopTunnelBrowserViewModel].
 *
 * [DesktopTunnelManager] is constructed with a fake [SshClientFactory] that
 * always fails: we never start real tunnels, we only seed [tunnelStates] via
 * direct [MutableStateFlow] assignment in the manager itself.
 *
 * [TunnelRepository] and [StopTunnelUseCase] are MockK mocks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopTunnelBrowserViewModelTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val testDispatcher = UnconfinedTestDispatcher()

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private fun makeScope() =
        CoroutineScope(testDispatcher + SupervisorJob())

    private fun makeTunnelConfig(
        id: String = "t1",
        localPort: Int = 8080,
        keepAlive: Boolean = false,
    ) = TunnelConfig(
        id = id,
        label = "Test Tunnel",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = localPort,
        remoteHost = "localhost",
        remotePort = 80,
        keepAliveAfterBrowserClose = keepAlive,
    )

    private fun makeHost() = Host(
        id = "host-1",
        label = "Test Host",
        hostname = "example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private class FakeTunnelRepository(
        private val configs: Map<String, TunnelConfig> = emptyMap(),
    ) : TunnelRepository {
        override fun observeAll(): Flow<List<TunnelConfig>> =
            MutableStateFlow(configs.values.toList()).asStateFlow()
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
            MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun getById(id: String): TunnelConfig? = configs[id]
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> = emptyList()
        override suspend fun save(config: TunnelConfig) = Unit
        override suspend fun update(config: TunnelConfig) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeFavorites(): Flow<List<TunnelConfig>> =
            MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private fun makeManager(scope: CoroutineScope): DesktopTunnelManager {
        val factory = SshClientFactory { _, _ -> SshResult.Error(fr.techtical.nextsh.shared.domain.model.SshErrorCode.UNKNOWN, "test") }
        return DesktopTunnelManager(scope = scope, clientFactory = factory)
    }

    private fun makeViewModel(
        tunnelRepository: TunnelRepository,
        tunnelManager: DesktopTunnelManager,
        stopTunnelUseCase: StopTunnelUseCase,
        appScope: AppScope,
    ) = DesktopTunnelBrowserViewModel(
        tunnelRepository = tunnelRepository,
        tunnelManager = tunnelManager,
        stopTunnel = stopTunnelUseCase,
        sessionHolder = DesktopBrowserSessionHolder(),
        appScope = appScope,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `isLocalhostUrl returns true for loopback addresses and false otherwise`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        assertTrue(vm.isLocalhostUrl("http://127.0.0.1:8080"))
        assertTrue(vm.isLocalhostUrl("http://localhost/foo"))
        assertTrue(vm.isLocalhostUrl("https://[::1]"))
        assertFalse(vm.isLocalhostUrl("https://example.com"))
        assertFalse(vm.isLocalhostUrl("not-a-url"))
    }

    @Test
    fun `loadTunnel populates initialUrl from tunnel localPort`() = runTest(testDispatcher) {
        val config = makeTunnelConfig(id = "t1", localPort = 8080)
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(backgroundScope),
        )

        vm.loadTunnel("t1")

        assertEquals("http://127.0.0.1:8080", vm.uiState.value.initialUrl)
        assertEquals("http://127.0.0.1:8080", vm.uiState.value.currentUrl)
        assertEquals(config, vm.uiState.value.tunnelConfig)
    }

    @Test
    fun `onNavigationRequested blocks external URLs and sets warning flag`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )
        val externalUrl = "https://example.com/page"

        val allowed = vm.onNavigationRequested(externalUrl)

        assertFalse(allowed)
        assertTrue(vm.uiState.value.showExternalNavWarning)
        assertEquals(externalUrl, vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `onNavigationRequested allows localhost URLs without warning`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        val allowed = vm.onNavigationRequested("http://127.0.0.1:8080/path")

        assertTrue(allowed)
        assertFalse(vm.uiState.value.showExternalNavWarning)
        assertNull(vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `onBrowserClosed calls stopTunnel when keepAliveAfterBrowserClose is false`() =
        runTest(testDispatcher) {
            val config = makeTunnelConfig(id = "t1", keepAlive = false)
            val stopUseCase: StopTunnelUseCase = mockk(relaxed = true)
            coEvery { stopUseCase.invoke(any()) } returns SshResult.Success(Unit)

            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = stopUseCase,
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            vm.onBrowserClosed()

            coVerify(exactly = 1) { stopUseCase.invoke("t1") }
        }

    @Test
    fun `onBrowserClosed does NOT call stopTunnel when keepAliveAfterBrowserClose is true`() =
        runTest(testDispatcher) {
            val config = makeTunnelConfig(id = "t1", keepAlive = true)
            val stopUseCase: StopTunnelUseCase = mockk(relaxed = true)

            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to config)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = stopUseCase,
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            vm.onBrowserClosed()

            coVerify(exactly = 0) { stopUseCase.invoke(any()) }
        }

    @Test
    fun `loadTunnel t2 after t1 switches uiState to t2 config`() =
        runTest(testDispatcher) {
            val t1 = makeTunnelConfig(id = "t1", localPort = 8080)
            val t2 = makeTunnelConfig(id = "t2", localPort = 9090)
            val scope = makeScope()
            val vm = makeViewModel(
                tunnelRepository = FakeTunnelRepository(mapOf("t1" to t1, "t2" to t2)),
                tunnelManager = makeManager(scope),
                stopTunnelUseCase = mockk(relaxed = true),
                appScope = TestAppScope(backgroundScope),
            )

            vm.loadTunnel("t1")
            assertEquals("http://127.0.0.1:8080", vm.uiState.value.initialUrl)

            vm.loadTunnel("t2")
            assertEquals("http://127.0.0.1:9090", vm.uiState.value.initialUrl)
            assertEquals(t2, vm.uiState.value.tunnelConfig)
        }

    // ── shouldCancelNavigation (Phase 5 CEF handler logic) ────────────────────

    @Test
    fun `shouldCancelNavigation returns false for localhost URLs`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        assertFalse(vm.shouldCancelNavigation("http://127.0.0.1:8080/path"))
        assertFalse(vm.shouldCancelNavigation("http://localhost/"))
        assertFalse(vm.shouldCancelNavigation("https://[::1]/admin"))
        assertFalse(vm.uiState.value.showExternalNavWarning)
    }

    @Test
    fun `shouldCancelNavigation returns true and triggers warning for external URLs`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )
        val externalUrl = "https://cdn.example.com/resource"

        assertTrue(vm.shouldCancelNavigation(externalUrl))
        assertTrue(vm.uiState.value.showExternalNavWarning)
        assertEquals(externalUrl, vm.uiState.value.pendingExternalUrl)
    }

    @Test
    fun `shouldCancelNavigation returns true for non-HTTP schemes`() {
        val scope = makeScope()
        val vm = makeViewModel(
            tunnelRepository = FakeTunnelRepository(),
            tunnelManager = makeManager(scope),
            stopTunnelUseCase = mockk(relaxed = true),
            appScope = TestAppScope(scope),
        )

        // Malformed / unknown schemes must be blocked: isLocalhostUrl returns false
        assertTrue(vm.shouldCancelNavigation("javascript:alert(1)"))
        assertTrue(vm.shouldCancelNavigation("file:///etc/passwd"))
    }
}
