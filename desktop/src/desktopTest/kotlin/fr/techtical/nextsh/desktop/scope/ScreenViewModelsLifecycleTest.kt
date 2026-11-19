// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.scope

import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyManager
import fr.techtical.nextsh.desktop.core.sync.DesktopSyncScheduler
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter
import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.VaultPinManager
import fr.techtical.nextsh.desktop.data.db.DesktopDatabase
import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.data.db.repository.HostFolderWithHosts
import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.desktop.hosts.HostDetailViewModel
import fr.techtical.nextsh.desktop.hosts.HostViewModel
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.desktop.service.DesktopTunnelTray
import fr.techtical.nextsh.desktop.service.StoppedTunnel
import fr.techtical.nextsh.desktop.sessions.DesktopSessionManager
import fr.techtical.nextsh.desktop.sessions.SessionTab
import fr.techtical.nextsh.desktop.settings.SettingsViewModel
import fr.techtical.nextsh.desktop.snippets.SnippetListViewModel
import fr.techtical.nextsh.desktop.sync.LanSyncServer
import fr.techtical.nextsh.desktop.sync.LanSyncServerLifecycle
import fr.techtical.nextsh.desktop.tunnels.TunnelConfigViewModel
import fr.techtical.nextsh.desktop.tunnels.TunnelListViewModel
import fr.techtical.nextsh.desktop.vault.VaultScreenViewModel
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every ViewModel that is tied to a screen releases its database observers when the screen closes it, and the
 * work the user started before leaving still finishes.
 *
 * Each ViewModel is built over fakes whose `observeAll` return a [MutableStateFlow]: its `subscriptionCount`
 * is the number of live listeners, which is what leaked (one more per visit of the screen, none ever
 * released) when the observers lived in the app scope.
 */
class ScreenViewModelsLifecycleTest {

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = coroutineScope.cancel()
    }

    private val app = TestAppScope(CoroutineScope(SupervisorJob() + Dispatchers.Default))

    /** Stands for the UI: where `collectAsState` would collect the flows that only run while collected. */
    private val ui = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        ui.cancel()
        app.onDestroy()
    }

    private suspend fun awaitUntil(message: String, condition: () -> Boolean) {
        val reached = withTimeoutOrNull(5_000L) {
            while (!condition()) delay(5L)
            true
        } ?: false
        assertTrue(reached, "timed out: $message")
    }

    private suspend fun awaitSubscriptions(rig: Rig, expected: Int, what: String) {
        rig.observed.forEachIndexed { index, flow ->
            awaitUntil("${rig.name}: flow #$index has $expected subscription(s) $what (is ${flow.subscriptionCount.value})") {
                flow.subscriptionCount.value == expected
            }
        }
    }

    // -- Rigs: one per ViewModel ---------------------------------------------------------------

    /**
     * A ViewModel over fakes. [observed] are the flows it must subscribe to while open (once each) and
     * release on close. [startOneShot] starts a user action that is blocked on [gate] and completes [done]
     * when it has gone to the end.
     */
    private abstract inner class Rig(val name: String) {
        val gate = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        abstract val observed: List<MutableStateFlow<*>>
        abstract fun build(): ScreenViewModel
        abstract suspend fun startOneShot(viewModel: ScreenViewModel)
    }

    private val host = Host(
        id = "h1", label = "Prod", hostname = "prod.example", username = "root",
        authType = AuthType.PASSWORD, credentialId = "cred-1",
    )

    private val tunnel = TunnelConfig(
        id = "t1", label = "Web", hostId = "h1", type = TunnelType.LOCAL_FORWARD,
        localPort = 8080, remoteHost = "localhost", remotePort = 80,
    )

    private inner class TunnelListRig : Rig("TunnelListViewModel") {
        private val tunnels = MutableStateFlow(emptyList<TunnelConfig>())
        private val hosts = MutableStateFlow(emptyList<Host>())
        override val observed: List<MutableStateFlow<*>> = listOf(tunnels, hosts)

        private val service = mockk<DesktopTunnelService>(relaxed = true).also {
            every { it.tunnelStates } returns MutableStateFlow(emptyMap<String, TunnelState>())
            every { it.deletionNotice } returns MutableStateFlow(emptyList<StoppedTunnel>())
            coEvery { it.stopAllTunnels() } coAnswers { gate.await(); done.complete(Unit); Unit }
        }

        override fun build() = TunnelListViewModel(
            tunnelRepository = mockk<TunnelRepository>().also { every { it.observeAll() } returns tunnels },
            hostRepository = mockk<HostRepository>().also { every { it.observeAll() } returns hosts },
            tunnelService = service,
            latenciesByTunnel = MutableStateFlow(emptyMap()),
            appScope = app,
        )

        override suspend fun startOneShot(viewModel: ScreenViewModel) = (viewModel as TunnelListViewModel).stopAll()
    }

    private inner class HostRig : Rig("HostViewModel") {
        private val folders = MutableStateFlow(emptyList<HostFolderWithHosts>())
        private val tabs = MutableStateFlow(emptyList<SessionTab>())
        override val observed: List<MutableStateFlow<*>> = listOf(folders, tabs)

        private val hostRepository = mockk<HostRepository>().also {
            coEvery { it.delete("h1") } coAnswers { done.complete(Unit); Unit }
        }
        private val tunnelService = mockk<DesktopTunnelService>().also {
            coEvery { it.stopTunnelsOfHost("h1") } coAnswers { gate.await() }
        }

        override fun build() = HostViewModel(
            folderRepository = mockk<DesktopHostFolderRepository>().also { every { it.observeAllWithHosts() } returns folders },
            sessionManager = mockk<DesktopSessionManager>().also { every { it.tabs } returns tabs },
            hostRepository = hostRepository,
            appScope = app,
            tunnelService = tunnelService,
        )

        override suspend fun startOneShot(viewModel: ScreenViewModel) = (viewModel as HostViewModel).deleteHost("h1")
    }

    private inner class SnippetRig : Rig("SnippetListViewModel") {
        private val snippets = MutableStateFlow(emptyList<Snippet>())
        private val categories = MutableStateFlow(emptyList<String>())
        private val hosts = MutableStateFlow(emptyList<Host>())
        override val observed: List<MutableStateFlow<*>> = listOf(snippets, categories, hosts)

        private val snippetRepo = mockk<SnippetRepository>().also {
            every { it.observeAll() } returns snippets
            every { it.observeCategories() } returns categories
            coEvery { it.save(any()) } coAnswers { gate.await(); done.complete(Unit); Unit }
        }
        private val hostRepo = mockk<HostRepository>().also { every { it.observeAll() } returns hosts }

        override fun build() = SnippetListViewModel(snippetRepo = snippetRepo, hostRepo = hostRepo, appScope = app)

        override suspend fun startOneShot(viewModel: ScreenViewModel) =
            (viewModel as SnippetListViewModel).save("label", "ls", null, null, null)
    }

    private inner class VaultRig : Rig("VaultScreenViewModel") {
        private val keys = MutableStateFlow(emptyList<SshKey>())
        private val hosts = MutableStateFlow(emptyList<Host>())
        override val observed: List<MutableStateFlow<*>> = listOf(keys, hosts)

        private val keyRepository = mockk<SshKeyRepository>().also {
            every { it.observeAll() } returns keys
            coEvery { it.delete(any()) } coAnswers { done.complete(Unit); Unit }
        }
        private val hostRepository = mockk<HostRepository>().also { every { it.observeAll() } returns hosts }
        private val vaultManager = mockk<VaultManager>(relaxed = true).also {
            // deleteKey removes the private key first, then the passphrase and the certificate
            // (typed deletions): blocking on the first one holds the whole action.
            coEvery { it.deletePrivateKey(any()) } coAnswers { gate.await() }
        }

        override fun build() = VaultScreenViewModel(
            appScope = app,
            keyRepository = keyRepository,
            hostRepository = hostRepository,
            vaultManager = vaultManager,
            generate = mockk<GenerateSshKeyUseCase>(relaxed = true),
            keyManager = mockk<DesktopSshKeyManager>(relaxed = true),
            exporter = mockk<DesktopVaultExporter>(relaxed = true),
            importer = mockk<DesktopVaultImporter>(relaxed = true),
        )

        override suspend fun startOneShot(viewModel: ScreenViewModel) {
            val key = SshKey(id = "k1", label = "key", keyType = SshKeyType.ED25519, publicKey = "ssh-ed25519 AAAA")
            (viewModel as VaultScreenViewModel).deleteKey(key)
        }
    }

    private inner class TunnelConfigRig : Rig("TunnelConfigViewModel") {
        private val hosts = MutableStateFlow(emptyList<Host>())
        override val observed: List<MutableStateFlow<*>> = listOf(hosts)

        private val repository = mockk<TunnelRepository>().also {
            coEvery { it.getById("t1") } returns tunnel
            coEvery { it.delete("t1") } coAnswers { done.complete(Unit); Unit }
        }
        private val tunnelService = mockk<DesktopTunnelService>().also {
            coEvery { it.stopTunnelIfRunning("t1") } coAnswers { gate.await() }
        }

        override fun build() = TunnelConfigViewModel(
            repository = repository,
            hostRepository = mockk<HostRepository>().also { every { it.observeAll() } returns hosts },
            appScope = app,
            tunnelService = tunnelService,
        )

        override suspend fun startOneShot(viewModel: ScreenViewModel) {
            val vm = viewModel as TunnelConfigViewModel
            vm.load("t1")
            awaitUntil("$name: the form is loaded") { vm.state.value.tunnelId == "t1" }
            vm.delete { }
        }
    }

    private inner class SettingsRig : Rig("SettingsViewModel") {
        private val settings = MutableStateFlow(DesktopSettingsStore.Settings())
        private val themes = MutableStateFlow(emptyList<CustomTerminalTheme>())
        override val observed: List<MutableStateFlow<*>> = listOf(settings, themes)

        private val themeRepository = mockk<CustomTerminalThemeRepository>().also {
            every { it.observeAll() } returns themes
            coEvery { it.delete("th1") } coAnswers { gate.await(); done.complete(Unit); Unit }
        }

        override fun build(): SettingsViewModel {
            val viewModel = SettingsViewModel(
                store = mockk<DesktopSettingsStore>().also { every { it.settings } returns settings },
                vaultManager = mockk<VaultManager>(),
                database = mockk<DesktopDatabase>(),
                knownHostsStore = mockk<DesktopKnownHostsStore>(),
                appScope = app,
                syncLifecycle = mockk<LanSyncServerLifecycle>(),
                syncServer = mockk<LanSyncServer>().also {
                    every { it.state } returns MutableStateFlow<LanSyncServer.State>(LanSyncServer.State.Stopped)
                },
                syncScheduler = mockk<DesktopSyncScheduler>().also {
                    every { it.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))
                },
                customThemeRepository = themeRepository,
                pinManager = mockk<VaultPinManager>().also { every { it.hasRecoveryPhrase() } returns false },
                tunnelTray = mockk<DesktopTunnelTray>(),
            )
            // The theme list only runs while the screen collects it, like collectAsState does.
            viewModel.customThemes.onEach { }.launchIn(ui)
            return viewModel
        }

        override suspend fun startOneShot(viewModel: ScreenViewModel) =
            (viewModel as SettingsViewModel).deleteCustomTheme("th1")
    }

    private inner class HostDetailRig : Rig("HostDetailViewModel") {
        private val themes = MutableStateFlow(emptyList<CustomTerminalTheme>())
        override val observed: List<MutableStateFlow<*>> = listOf(themes)

        private val repository = mockk<HostRepository>().also {
            coEvery { it.getById("h1") } returns host
            coEvery { it.delete("h1") } coAnswers { done.complete(Unit); Unit }
        }
        private val tunnelService = mockk<DesktopTunnelService>().also {
            coEvery { it.stopTunnelsOfHost("h1") } coAnswers { gate.await() }
        }

        override fun build(): HostDetailViewModel {
            val viewModel = HostDetailViewModel(
                repository = repository,
                sshKeyRepository = mockk<SshKeyRepository>().also { every { it.observeAll() } returns flowOf(emptyList()) },
                hostFolderRepository = mockk<DesktopHostFolderRepository>().also {
                    every { it.observeAllGroups() } returns flowOf(emptyList())
                },
                vaultManager = mockk<VaultManager>(relaxed = true),
                customThemeRepository = mockk<CustomTerminalThemeRepository>().also { every { it.observeAll() } returns themes },
                appScope = app,
                tunnelService = tunnelService,
            )
            // The theme list only runs while the screen collects it, like collectAsState does.
            viewModel.customThemes.onEach { }.launchIn(ui)
            return viewModel
        }

        override suspend fun startOneShot(viewModel: ScreenViewModel) {
            val vm = viewModel as HostDetailViewModel
            vm.load("h1")
            awaitUntil("$name: the form is loaded") { vm.state.value.hostId == "h1" }
            vm.delete { }
        }
    }

    private fun rigs(): List<Rig> = listOf(
        TunnelListRig(), HostRig(), SnippetRig(), VaultRig(), TunnelConfigRig(), SettingsRig(), HostDetailRig(),
    )

    // -- Tests ---------------------------------------------------------------------------------

    @Test
    fun `each ViewModel observes while its screen is open and releases everything on close`() = runBlocking {
        for (rig in rigs()) {
            val viewModel = rig.build()
            awaitSubscriptions(rig, expected = 1, what = "while the screen is open")

            viewModel.close()

            awaitSubscriptions(rig, expected = 0, what = "once the screen is closed")
        }
    }

    @Test
    fun `visiting a screen again and again leaves no subscription behind`() = runBlocking {
        for (rig in rigs()) {
            repeat(40) { rig.build().close() }

            awaitSubscriptions(rig, expected = 0, what = "after 40 visits")
        }
    }

    @Test
    fun `screens open at the same time each hold their own subscription, closing them releases them`() = runBlocking {
        for (rig in rigs()) {
            val open = List(3) { rig.build() }
            awaitSubscriptions(rig, expected = 3, what = "with three screens open")

            open[0].close()
            awaitSubscriptions(rig, expected = 2, what = "with two screens left")
            open.drop(1).forEach { it.close() }
            awaitSubscriptions(rig, expected = 0, what = "once all are closed")
        }
    }

    @Test
    fun `a user action started before the screen was closed still goes to the end`() = runBlocking {
        for (rig in rigs()) {
            val viewModel = rig.build()
            rig.startOneShot(viewModel)

            viewModel.close() // the user leaves while the action is blocked
            rig.gate.complete(Unit)

            val finished = withTimeoutOrNull(5_000L) { rig.done.await() }
            assertEquals(Unit, finished, "${rig.name}: the action started before the close must finish")
        }
    }

    @Test
    fun `a closed list ViewModel stops following the database`() = runBlocking {
        val tunnels = MutableStateFlow(emptyList<TunnelConfig>())
        val viewModel = TunnelListViewModel(
            tunnelRepository = mockk<TunnelRepository>().also { every { it.observeAll() } returns tunnels },
            hostRepository = mockk<HostRepository>().also { every { it.observeAll() } returns MutableStateFlow(emptyList<Host>()) },
            tunnelService = mockk<DesktopTunnelService>(relaxed = true).also {
                every { it.tunnelStates } returns MutableStateFlow(emptyMap<String, TunnelState>())
                every { it.deletionNotice } returns MutableStateFlow(emptyList<StoppedTunnel>())
            },
            latenciesByTunnel = MutableStateFlow(emptyMap()),
            appScope = app,
        )
        awaitUntil("the list observes") { tunnels.subscriptionCount.value == 1 }

        tunnels.value = listOf(tunnel)
        awaitUntil("the open list follows the database") { viewModel.tunnels.value == listOf(tunnel) }

        viewModel.close()
        awaitUntil("the list is released") { tunnels.subscriptionCount.value == 0 }
        tunnels.value = emptyList()
        delay(100L)

        assertEquals(listOf(tunnel), viewModel.tunnels.value, "a closed ViewModel keeps its last value")
    }
}
