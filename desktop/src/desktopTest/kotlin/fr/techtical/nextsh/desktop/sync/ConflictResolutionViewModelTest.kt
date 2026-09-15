// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [ConflictResolutionViewModel] Desktop.
 *
 * All repositories are in-memory fakes, no MockK dependency needed.
 * The test scope is injected via a fake [AppScope] to control coroutine execution.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionViewModelTest {

    // ── Fake repositories ─────────────────────────────────────────────────────

    private class FakePendingConflictRepository(
        initial: List<PendingConflict> = emptyList(),
    ) : PendingConflictRepository {
        private val _list = MutableStateFlow(initial)
        val deleted = mutableListOf<String>()

        override fun observeAll(): Flow<List<PendingConflict>> = _list.asStateFlow()
        override fun observeCount(): Flow<Int> = MutableStateFlow(_list.value.size)
        override suspend fun getAll(): List<PendingConflict> = _list.value
        override suspend fun save(conflict: PendingConflict) { _list.value = _list.value + conflict }
        override suspend fun deleteById(id: String) {
            deleted.add(id)
            _list.value = _list.value.filter { it.id != id }
        }
        override suspend fun deleteByEntity(entityType: SyncableEntityType, entityId: String) = Unit
        override suspend fun deleteAll() { _list.value = emptyList() }
    }

    private class FakeHostRepository : HostRepository {
        val upserted = mutableListOf<SyncEntry<Host>>()

        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) { upserted.add(entry) }
        override fun observeAll() = MutableStateFlow(emptyList<Host>()).asStateFlow()
        override fun observeByGroup(group: String) = MutableStateFlow(emptyList<Host>()).asStateFlow()
        override fun observeGroups() = MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String): Host? = null
        override suspend fun save(host: Host) = Unit
        override suspend fun update(host: Host) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun updateLastConnected(id: String) = Unit
        override fun observeFavorites() = MutableStateFlow(emptyList<Host>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeTunnelRepository : TunnelRepository {
        override fun observeAll() = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.TunnelConfig>()).asStateFlow()
        override fun observeByHost(hostId: String) = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.TunnelConfig>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun getAutoStartTunnels() = emptyList<fr.techtical.nextsh.shared.domain.model.TunnelConfig>()
        override suspend fun save(config: fr.techtical.nextsh.shared.domain.model.TunnelConfig) = Unit
        override suspend fun update(config: fr.techtical.nextsh.shared.domain.model.TunnelConfig) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeFavorites() = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.TunnelConfig>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries() = emptyList<fr.techtical.nextsh.shared.core.sync.SyncEntry<fr.techtical.nextsh.shared.domain.model.TunnelConfig>>()
        override suspend fun upsertSyncEntry(entry: SyncEntry<fr.techtical.nextsh.shared.domain.model.TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeSshKeyRepository : SshKeyRepository {
        override fun observeAll() = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.SshKey>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(key: fr.techtical.nextsh.shared.domain.model.SshKey) = Unit
        override suspend fun update(key: fr.techtical.nextsh.shared.domain.model.SshKey) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries() = emptyList<SyncEntry<fr.techtical.nextsh.shared.domain.model.SshKey>>()
        override suspend fun upsertSyncEntry(entry: SyncEntry<fr.techtical.nextsh.shared.domain.model.SshKey>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeSnippetRepository : SnippetRepository {
        override fun observeAll() = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.Snippet>()).asStateFlow()
        override fun observeForHost(hostId: String) = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.Snippet>()).asStateFlow()
        override fun observeCategories() = MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(snippet: fr.techtical.nextsh.shared.domain.model.Snippet) = Unit
        override suspend fun update(snippet: fr.techtical.nextsh.shared.domain.model.Snippet) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries() = emptyList<SyncEntry<fr.techtical.nextsh.shared.domain.model.Snippet>>()
        override suspend fun upsertSyncEntry(entry: SyncEntry<fr.techtical.nextsh.shared.domain.model.Snippet>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeCustomTerminalThemeRepository : CustomTerminalThemeRepository {
        override fun observeAll() = MutableStateFlow(emptyList<fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(theme: fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries() = emptyList<SyncEntry<fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme>>()
        override suspend fun upsertSyncEntry(entry: SyncEntry<fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeSyncScheduler : SyncScheduler {
        override val syncState: StateFlow<SyncState> =
            MutableStateFlow(SyncState(SyncStatus.IDLE, null)).asStateFlow()
        override fun start(intervalMs: Long) = Unit
        override fun stop() = Unit
        override suspend fun forceSync() = Unit
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        // Use the provided scope directly: in tests this should be backgroundScope
        // so that ongoing collectors (StateFlow sharing) do not cause UncompletedCoroutinesError.
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    // ── Test data ─────────────────────────────────────────────────────────────

    private fun makeHostEntry(id: String = "host-1", label: String = "Prod"): SyncEntry<Host> {
        val host = Host(
            id = id,
            label = label,
            hostname = "example.com",
            port = 22,
            username = "root",
            authType = AuthType.PASSWORD,
            credentialId = "cred-1",
        )
        return SyncEntry(
            id = host.id,
            payload = host,
            clock = VectorClock(mapOf("device-A" to 1L)),
            updatedAt = 1_000L,
        )
    }

    private fun makePendingConflict(
        id: String = "conflict-1",
        entityId: String = "host-1",
        localLabel: String = "Prod Local",
        remoteLabel: String = "Prod Remote",
    ): PendingConflict {
        val localEntry = makeHostEntry(id = entityId, label = localLabel)
        val remoteEntry = makeHostEntry(id = entityId, label = remoteLabel)
        return PendingConflict(
            id = id,
            entityType = SyncableEntityType.HOST,
            entityId = entityId,
            localJson = ConflictSerializer.encode(SyncableEntityType.HOST, localEntry),
            remoteJson = ConflictSerializer.encode(SyncableEntityType.HOST, remoteEntry),
            detectedAt = System.currentTimeMillis(),
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * keepLocal then applyAll must only call deleteById, no upsert on the host repository.
     */
    @Test
    fun `keepLocal then applyAll deletes conflict only`() = runTest {
        val conflict = makePendingConflict()
        val conflictRepo = FakePendingConflictRepository(listOf(conflict))
        val hostRepo = FakeHostRepository()

        val viewModel = ConflictResolutionViewModel(
            pendingConflictRepository = conflictRepo,
            hostRepository = hostRepo,
            tunnelRepository = FakeTunnelRepository(),
            sshKeyRepository = FakeSshKeyRepository(),
            snippetRepository = FakeSnippetRepository(),
            customThemeRepository = FakeCustomTerminalThemeRepository(),
            syncScheduler = FakeSyncScheduler(),
            // backgroundScope does not block runTest from completing when collectors are active
            appScope = TestAppScope(backgroundScope),
            credentialSyncRepository = io.mockk.mockk(relaxed = true),
            enrolledDeviceRepository = io.mockk.mockk(relaxed = true),
            secretStore = io.mockk.mockk(relaxed = true),
        )

        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        assertEquals(listOf(conflict.id), conflictRepo.deleted)
        assertEquals(0, hostRepo.upserted.size)
    }

    /**
     * keepRemote then applyAll must upsert the decoded remote entry into the host repository
     * and then delete the conflict.
     */
    @Test
    fun `keepRemote then applyAll upserts remote entry and deletes conflict`() = runTest {
        val conflict = makePendingConflict(remoteLabel = "Prod Remote")
        val conflictRepo = FakePendingConflictRepository(listOf(conflict))
        val hostRepo = FakeHostRepository()
        val expectedRemoteEntry = ConflictSerializer.decodeHost(conflict.remoteJson)

        val viewModel = ConflictResolutionViewModel(
            pendingConflictRepository = conflictRepo,
            hostRepository = hostRepo,
            tunnelRepository = FakeTunnelRepository(),
            sshKeyRepository = FakeSshKeyRepository(),
            snippetRepository = FakeSnippetRepository(),
            customThemeRepository = FakeCustomTerminalThemeRepository(),
            syncScheduler = FakeSyncScheduler(),
            appScope = TestAppScope(backgroundScope),
            credentialSyncRepository = io.mockk.mockk(relaxed = true),
            enrolledDeviceRepository = io.mockk.mockk(relaxed = true),
            secretStore = io.mockk.mockk(relaxed = true),
        )

        viewModel.keepRemote(conflict)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        assertEquals(listOf(expectedRemoteEntry), hostRepo.upserted)
        assertEquals(listOf(conflict.id), conflictRepo.deleted)
    }

    /**
     * With 2 conflicts, one resolved (keepLocal), one unresolved,
     * only the resolved conflict must be processed.
     */
    @Test
    fun `applyAll ignores conflicts without resolution`() = runTest {
        val conflict1 = makePendingConflict(id = "conflict-1", entityId = "host-1")
        val conflict2 = makePendingConflict(id = "conflict-2", entityId = "host-2")
        val conflictRepo = FakePendingConflictRepository(listOf(conflict1, conflict2))

        val viewModel = ConflictResolutionViewModel(
            pendingConflictRepository = conflictRepo,
            hostRepository = FakeHostRepository(),
            tunnelRepository = FakeTunnelRepository(),
            sshKeyRepository = FakeSshKeyRepository(),
            snippetRepository = FakeSnippetRepository(),
            customThemeRepository = FakeCustomTerminalThemeRepository(),
            syncScheduler = FakeSyncScheduler(),
            appScope = TestAppScope(backgroundScope),
            credentialSyncRepository = io.mockk.mockk(relaxed = true),
            enrolledDeviceRepository = io.mockk.mockk(relaxed = true),
            secretStore = io.mockk.mockk(relaxed = true),
        )

        // Only keep local for conflict1: conflict2 left undecided
        viewModel.keepLocal(conflict1)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        assertEquals(listOf(conflict1.id), conflictRepo.deleted)
    }
}
