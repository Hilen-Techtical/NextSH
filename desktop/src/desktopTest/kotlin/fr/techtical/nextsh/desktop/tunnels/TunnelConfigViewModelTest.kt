// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.tunnels

import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of [TunnelConfigViewModel.save]: an edit re-reads the stored tunnel
 * right before building (so fields the form does not own survive), and a
 * tunnel deleted meanwhile (e.g. by a sync) is not resurrected.
 *
 * The scope uses [UnconfinedTestDispatcher] and the fakes never suspend for
 * real, so every `launch` in the ViewModel completes before the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelConfigViewModelTest {

    private lateinit var testScope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (::testScope.isInitialized) testScope.cancel()
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    /** In-memory repository; [remove] mimics a tombstone (`getById` ignores deleted rows). */
    private class FakeTunnelRepository(initial: List<TunnelConfig> = emptyList()) : TunnelRepository {
        private val tunnels = MutableStateFlow(initial)
        val saved = mutableListOf<TunnelConfig>()
        val updated = mutableListOf<TunnelConfig>()

        fun remove(id: String) {
            tunnels.value = tunnels.value.filterNot { it.id == id }
        }

        fun replace(config: TunnelConfig) {
            tunnels.value = tunnels.value.map { if (it.id == config.id) config else it }
        }

        override fun observeAll(): Flow<List<TunnelConfig>> = tunnels
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> = tunnels
        override suspend fun getById(id: String): TunnelConfig? = tunnels.value.find { it.id == id }
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> = emptyList()

        override suspend fun save(config: TunnelConfig) {
            saved += config
            tunnels.value = tunnels.value + config
        }

        override suspend fun update(config: TunnelConfig) {
            updated += config
            replace(config)
        }

        override suspend fun delete(id: String) = remove(id)
        override fun observeFavorites(): Flow<List<TunnelConfig>> = tunnels
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {
            tunnels.value.find { it.id == id }?.let { replace(it.copy(isFavorite = isFavorite)) }
        }

        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = remove(id)
    }

    private fun storedTunnel(isFavorite: Boolean = false) = TunnelConfig(
        id = "t1",
        label = "Web",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
        isFavorite = isFavorite,
    )

    private fun newViewModel(repository: FakeTunnelRepository): TunnelConfigViewModel {
        testScope = CoroutineScope(UnconfinedTestDispatcher())
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList<Host>())
        return TunnelConfigViewModel(
            repository = repository,
            hostRepository = hostRepository,
            appScope = TestAppScope(testScope),
        )
    }

    @Test
    fun `save of an existing tunnel keeps the favorite flag read at save time`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel(isFavorite = false)))
        val vm = newViewModel(repo)
        vm.load("t1")

        // Favorite toggled elsewhere (list screen, sync) while the form is open.
        repo.replace(storedTunnel(isFavorite = true))
        vm.onLabel("Renamed")
        var done = false
        vm.save { done = true }

        assertTrue(done)
        assertTrue(repo.saved.isEmpty())
        val updated = repo.updated.single()
        assertEquals("Renamed", updated.label)
        assertTrue(updated.isFavorite)
        assertFalse(vm.state.value.isSaving)
        assertNull(vm.state.value.error)
    }

    @Test
    fun `save of a tunnel deleted meanwhile writes nothing and reports the error`() {
        val repo = FakeTunnelRepository(listOf(storedTunnel()))
        val vm = newViewModel(repo)
        vm.load("t1")

        // Deleted (e.g. by a sync) while the form is open.
        repo.remove("t1")
        vm.onLabel("Renamed")
        var done = false
        vm.save { done = true }

        assertFalse(done)
        assertTrue(repo.updated.isEmpty())
        assertTrue(repo.saved.isEmpty())
        assertEquals("Ce tunnel a été supprimé entre-temps, rien n'a été enregistré.", vm.state.value.error)
        assertFalse(vm.state.value.isSaving)
    }

    @Test
    fun `save of a new tunnel calls save and not update`() {
        val repo = FakeTunnelRepository()
        val vm = newViewModel(repo)
        vm.load(null)
        vm.onLabel("  New  ")
        vm.onHost("h1")
        vm.onLocalPort("9000")
        vm.onRemotePort("443")

        var done = false
        vm.save { done = true }

        assertTrue(done)
        assertTrue(repo.updated.isEmpty())
        val saved = repo.saved.single()
        assertEquals("New", saved.label)
        assertEquals(9000, saved.localPort)
        assertFalse(saved.isFavorite)
        assertFalse(vm.state.value.isSaving)
        assertNull(vm.state.value.error)
    }
}
