// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialEntry
import fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ConflictResolutionViewModel] Desktop.
 *
 * Repositories are in-memory fakes, the credential repository is a MockK mock.
 * The test scope is injected via a fake [AppScope] to control coroutine execution.
 * The device id and the clock are injected so the stamped entries are deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConflictResolutionViewModelTest {

    // ── Fake repositories ─────────────────────────────────────────────────────

    /** Seedable sync rows of one repository: [entries] is what getAllSyncEntries returns. */
    private class SyncStore<T> {
        val entries = mutableListOf<SyncEntry<T>>()
        val upserted = mutableListOf<SyncEntry<T>>()
        var failOnUpsert: Throwable? = null

        /** Runs before each upsert and may suspend, to let another coroutine interleave. */
        var beforeUpsert: (suspend () -> Unit)? = null

        suspend fun upsert(entry: SyncEntry<T>) {
            beforeUpsert?.invoke()
            failOnUpsert?.let { throw it }
            upserted.add(entry)
            val index = entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) entries[index] = entry else entries.add(entry)
        }
    }

    private class FakePendingConflictRepository(
        initial: List<PendingConflict> = emptyList(),
    ) : PendingConflictRepository {
        private val _list = MutableStateFlow(initial)
        val deleted = mutableListOf<String>()

        /** What the sync does on a re-detection: the marker is replaced by a new one with a new id. */
        fun redetect(conflict: PendingConflict) {
            _list.value = _list.value.filterNot {
                it.entityType == conflict.entityType && it.entityId == conflict.entityId
            } + conflict
        }

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
        val store = SyncStore<Host>()

        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) { store.upsert(entry) }
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
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = store.entries.toList()
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeTunnelRepository : TunnelRepository {
        val store = SyncStore<TunnelConfig>()

        override fun observeAll() = MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override fun observeByHost(hostId: String) = MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun getAutoStartTunnels() = emptyList<TunnelConfig>()
        override suspend fun save(config: TunnelConfig) = Unit
        override suspend fun update(config: TunnelConfig) = Unit
        override suspend fun delete(id: String) = Unit
        override fun observeFavorites() = MutableStateFlow(emptyList<TunnelConfig>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = store.entries.toList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) { store.upsert(entry) }
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeSshKeyRepository : SshKeyRepository {
        val store = SyncStore<SshKey>()

        override fun observeAll() = MutableStateFlow(emptyList<SshKey>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(key: SshKey) = Unit
        override suspend fun update(key: SshKey) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = store.entries.toList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) { store.upsert(entry) }
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeSnippetRepository : SnippetRepository {
        val store = SyncStore<Snippet>()

        override fun observeAll() = MutableStateFlow(emptyList<Snippet>()).asStateFlow()
        override fun observeForHost(hostId: String) = MutableStateFlow(emptyList<Snippet>()).asStateFlow()
        override fun observeCategories() = MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(snippet: Snippet) = Unit
        override suspend fun update(snippet: Snippet) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Snippet>> = store.entries.toList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Snippet>) { store.upsert(entry) }
        override suspend fun hardDelete(id: String) = Unit
    }

    private class FakeCustomTerminalThemeRepository : CustomTerminalThemeRepository {
        val store = SyncStore<CustomTerminalTheme>()

        override fun observeAll() = MutableStateFlow(emptyList<CustomTerminalTheme>()).asStateFlow()
        override suspend fun getById(id: String) = null
        override suspend fun save(theme: CustomTerminalTheme) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<CustomTerminalTheme>> = store.entries.toList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<CustomTerminalTheme>) { store.upsert(entry) }
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

    /** Everything a test may seed or inspect. */
    private class Fixture(
        val conflictRepo: FakePendingConflictRepository,
        val hostRepo: FakeHostRepository = FakeHostRepository(),
        val tunnelRepo: FakeTunnelRepository = FakeTunnelRepository(),
        val sshKeyRepo: FakeSshKeyRepository = FakeSshKeyRepository(),
        val snippetRepo: FakeSnippetRepository = FakeSnippetRepository(),
        val themeRepo: FakeCustomTerminalThemeRepository = FakeCustomTerminalThemeRepository(),
        val credentialSync: CredentialSyncRepository = mockk(relaxed = true),
    )

    private fun TestScope.viewModel(
        fixture: Fixture,
        afterConflictResolved: suspend () -> Unit = {},
    ) = ConflictResolutionViewModel(
        pendingConflictRepository = fixture.conflictRepo,
        hostRepository = fixture.hostRepo,
        tunnelRepository = fixture.tunnelRepo,
        sshKeyRepository = fixture.sshKeyRepo,
        snippetRepository = fixture.snippetRepo,
        customThemeRepository = fixture.themeRepo,
        syncScheduler = FakeSyncScheduler(),
        // backgroundScope does not block runTest from completing when collectors are active
        appScope = TestAppScope(backgroundScope),
        credentialSyncRepository = fixture.credentialSync,
        enrolledDeviceRepository = mockk(relaxed = true),
        secretStore = mockk(relaxed = true),
        deviceIdProvider = { DEVICE_ID },
        clock = { NOW },
        afterConflictResolved = afterConflictResolved,
    )

    // ── Test data ─────────────────────────────────────────────────────────────

    private companion object {
        const val DEVICE_ID = "device-desktop"
        const val NOW = 9_000_000L
        val OLD_CLOCK = VectorClock(mapOf("device-A" to 1L))
        val REMOTE_CLOCK = VectorClock(mapOf("device-android" to 4L))
    }

    private fun host(id: String = "host-1", label: String = "Prod") = Host(
        id = id,
        label = label,
        hostname = "example.com",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun tunnel(id: String = "tunnel-1", label: String = "Web") = TunnelConfig(
        id = id,
        label = label,
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
    )

    private fun sshKey(id: String = "key-1", label: String = "Laptop") = SshKey(
        id = id,
        label = label,
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    private fun snippet(id: String = "snippet-1", label: String = "Update") =
        Snippet(id = id, label = label, command = "apt update", createdAt = 1L)

    private fun theme(id: String = "theme-1", name: String = "Night") = CustomTerminalTheme(
        id = id,
        name = name,
        background = 0xFF000000.toInt(),
        foreground = 0xFFFFFFFF.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
        selectionBg = 0xFF333333.toInt(),
        ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() },
        createdAt = 1L,
    )

    private fun <T> entry(
        id: String,
        payload: T?,
        clock: VectorClock = OLD_CLOCK,
        updatedAt: Long = 1_000L,
        deleted: Boolean = payload == null,
        deletedAt: Long? = if (deleted) 500L else null,
    ) = SyncEntry(
        id = id,
        payload = payload,
        clock = clock,
        deleted = deleted,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    private fun makeHostEntry(id: String = "host-1", label: String = "Prod"): SyncEntry<Host> =
        entry(id, host(id, label))

    private fun conflictOf(
        type: SyncableEntityType,
        entityId: String,
        local: SyncEntry<*>,
        remote: SyncEntry<*>,
        id: String = "conflict-$entityId",
    ) = PendingConflict(
        id = id,
        entityType = type,
        entityId = entityId,
        localJson = ConflictSerializer.encode(type, local),
        remoteJson = ConflictSerializer.encode(type, remote),
        detectedAt = System.currentTimeMillis(),
    )

    private fun makePendingConflict(
        id: String = "conflict-1",
        entityId: String = "host-1",
        localLabel: String = "Prod Local",
        remoteLabel: String = "Prod Remote",
    ): PendingConflict = conflictOf(
        SyncableEntityType.HOST,
        entityId,
        local = makeHostEntry(id = entityId, label = localLabel),
        remote = makeHostEntry(id = entityId, label = remoteLabel),
        id = id,
    )

    /** A host conflict: stale snapshot in the marker, newer local row in the repository. */
    private fun hostConflictWithNewerLocalRow(fixture: Fixture): PendingConflict {
        fixture.hostRepo.store.entries.add(
            entry("host-1", host(label = "Prod Edited Since"), clock = VectorClock(mapOf("device-A" to 1L, DEVICE_ID to 3L)), updatedAt = 2_000L),
        )
        return conflictOf(
            SyncableEntityType.HOST,
            "host-1",
            local = entry("host-1", host(label = "Prod Snapshot")),
            remote = entry("host-1", host(label = "Prod Remote"), clock = REMOTE_CLOCK),
        )
    }

    // ── keepLocal ─────────────────────────────────────────────────────────────

    /**
     * keepLocal must write the CURRENT row, not the snapshot held by the marker, with a clock that
     * dominates both versions and a newer updatedAt; deleting the marker alone would leave the
     * clocks concurrent and the conflict would come back at the next sync.
     */
    @Test
    fun `keepLocal then applyAll upserts the current row with a clock above both versions`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        val current = fixture.hostRepo.store.entries.single()
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)
        val outcome = vm.applyAll()

        assertEquals(ApplyOutcome(resolved = 1, failed = 0), outcome)
        val written = fixture.hostRepo.store.upserted.single()
        assertEquals("Prod Edited Since", written.payload?.label, "the current row is written, not the snapshot")
        assertTrue(written.clock.dominates(current.clock))
        assertTrue(written.clock.dominates(REMOTE_CLOCK))
        assertEquals(NOW, written.updatedAt)
        assertEquals(false, written.deleted)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
    }

    @Test
    fun `a resolved host conflict runs the post-resolve step after its marker is removed, keep local or keep remote`() = runTest {
        listOf(true, false).forEach { keepLocal ->
            val fixture = Fixture(FakePendingConflictRepository())
            val conflict = hostConflictWithNewerLocalRow(fixture)
            fixture.conflictRepo.save(conflict)
            val markersLeftWhenCalled = mutableListOf<Int>()
            val vm = viewModel(fixture, afterConflictResolved = {
                markersLeftWhenCalled += fixture.conflictRepo.getAll().size
            })

            if (keepLocal) vm.keepLocal(conflict) else vm.keepRemote(conflict)
            vm.applyAll()

            assertEquals(listOf(0), markersLeftWhenCalled, "called once, after the marker was deleted (keepLocal=$keepLocal)")
        }
    }

    @Test
    fun `the post-resolve step is not run for other kinds, for an unresolved host or after a failed write`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        fixture.snippetRepo.store.entries.add(entry("snippet-1", snippet(label = "Update Current")))
        val snippetConflict = conflictOf(
            SyncableEntityType.SNIPPET, "snippet-1",
            local = entry("snippet-1", snippet(label = "Update Snapshot")),
            remote = entry("snippet-1", snippet(label = "Update Remote"), clock = REMOTE_CLOCK),
        )
        val hostConflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(snippetConflict)
        fixture.conflictRepo.save(hostConflict)
        var calls = 0
        val vm = viewModel(fixture, afterConflictResolved = { calls++ })

        // A snippet choice, and no choice yet on the host: nothing to repair.
        vm.keepLocal(snippetConflict)
        vm.applyAll()
        assertEquals(0, calls)

        // The host write fails: the conflict stays pending, the step does not run.
        fixture.hostRepo.store.failOnUpsert = IllegalStateException("disk I/O error")
        vm.keepLocal(hostConflict)
        assertEquals(ApplyOutcome(resolved = 0, failed = 1), vm.applyAll())
        assertEquals(0, calls)
    }

    @Test
    fun `a resolved tunnel conflict runs the post-resolve step too, keep local or keep remote`() = runTest {
        listOf(true, false).forEach { keepLocal ->
            val fixture = Fixture(FakePendingConflictRepository())
            fixture.tunnelRepo.store.entries.add(entry("tunnel-1", tunnel(label = "Web Current")))
            val conflict = conflictOf(
                SyncableEntityType.TUNNEL, "tunnel-1",
                local = entry("tunnel-1", tunnel(label = "Web Snapshot")),
                remote = entry("tunnel-1", tunnel(label = "Web Remote"), clock = REMOTE_CLOCK),
            )
            fixture.conflictRepo.save(conflict)
            val markersLeftWhenCalled = mutableListOf<Int>()
            val vm = viewModel(fixture, afterConflictResolved = {
                markersLeftWhenCalled += fixture.conflictRepo.getAll().size
            })

            if (keepLocal) vm.keepLocal(conflict) else vm.keepRemote(conflict)
            vm.applyAll()

            assertEquals(listOf(0), markersLeftWhenCalled, "called once, after the marker was deleted (keepLocal=$keepLocal)")
        }
    }

    @Test
    fun `several choices in one apply run the post-resolve step once`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val hostConflict = hostConflictWithNewerLocalRow(fixture)
        fixture.tunnelRepo.store.entries.add(entry("tunnel-1", tunnel(label = "Web Current")))
        val tunnelConflict = conflictOf(
            SyncableEntityType.TUNNEL, "tunnel-1",
            local = entry("tunnel-1", tunnel(label = "Web Snapshot")),
            remote = entry("tunnel-1", tunnel(label = "Web Remote"), clock = REMOTE_CLOCK),
        )
        fixture.conflictRepo.save(hostConflict)
        fixture.conflictRepo.save(tunnelConflict)
        var calls = 0
        val vm = viewModel(fixture, afterConflictResolved = { calls++ })

        vm.keepRemote(hostConflict)
        vm.keepRemote(tunnelConflict)

        assertEquals(ApplyOutcome(resolved = 2, failed = 0), vm.applyAll())
        assertEquals(1, calls)
    }

    @Test
    fun `ignoring a host or tunnel conflict runs the post-resolve step after the marker is removed`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val hostConflict = hostConflictWithNewerLocalRow(fixture)
        val tunnelConflict = conflictOf(
            SyncableEntityType.TUNNEL, "tunnel-1",
            local = entry("tunnel-1", tunnel(label = "Web Snapshot")),
            remote = entry("tunnel-1", tunnel(label = "Web Remote"), clock = REMOTE_CLOCK),
        )
        fixture.conflictRepo.save(hostConflict)
        fixture.conflictRepo.save(tunnelConflict)
        val markersLeftWhenCalled = mutableListOf<Int>()
        val vm = viewModel(fixture, afterConflictResolved = {
            markersLeftWhenCalled += fixture.conflictRepo.getAll().size
        })

        vm.ignoreConflict(hostConflict)
        runCurrent()
        vm.ignoreConflict(tunnelConflict)
        runCurrent()

        assertEquals(listOf(1, 0), markersLeftWhenCalled, "after each marker was deleted")
    }

    @Test
    fun `ignoring a conflict of another kind does not run the post-resolve step`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val snippetConflict = conflictOf(
            SyncableEntityType.SNIPPET, "snippet-1",
            local = entry("snippet-1", snippet(label = "Update Snapshot")),
            remote = entry("snippet-1", snippet(label = "Update Remote"), clock = REMOTE_CLOCK),
        )
        fixture.conflictRepo.save(snippetConflict)
        var calls = 0
        val vm = viewModel(fixture, afterConflictResolved = { calls++ })

        vm.ignoreConflict(snippetConflict)
        runCurrent()

        assertEquals(listOf(snippetConflict.id), fixture.conflictRepo.deleted)
        assertEquals(0, calls)
    }

    @Test
    fun `a failing post-resolve step does not turn an applied choice into a failure`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture, afterConflictResolved = { throw IllegalStateException("disk I/O error") })

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(resolved = 1, failed = 0), vm.applyAll())
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
    }

    @Test
    fun `keepLocal on a tunnel upserts the current tunnel`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        fixture.tunnelRepo.store.entries.add(
            entry("tunnel-1", tunnel(label = "Web Edited Since"), clock = VectorClock(mapOf("device-A" to 2L)), updatedAt = 2_000L),
        )
        val conflict = conflictOf(
            SyncableEntityType.TUNNEL,
            "tunnel-1",
            local = entry("tunnel-1", tunnel(label = "Web Snapshot")),
            remote = entry("tunnel-1", tunnel(label = "Web Remote"), clock = REMOTE_CLOCK),
        )
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        val written = fixture.tunnelRepo.store.upserted.single()
        assertEquals("Web Edited Since", written.payload?.label)
        assertTrue(written.clock.dominates(VectorClock(mapOf("device-A" to 2L))))
        assertTrue(written.clock.dominates(REMOTE_CLOCK))
        assertEquals(NOW, written.updatedAt)
        assertEquals(0, fixture.hostRepo.store.upserted.size)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
    }

    @Test
    fun `keepLocal covers ssh keys, snippets and custom themes`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        fixture.sshKeyRepo.store.entries.add(entry("key-1", sshKey(label = "Laptop Current")))
        fixture.snippetRepo.store.entries.add(entry("snippet-1", snippet(label = "Update Current")))
        fixture.themeRepo.store.entries.add(entry("theme-1", theme(name = "Night Current")))
        val keyConflict = conflictOf(
            SyncableEntityType.SSH_KEY, "key-1",
            local = entry("key-1", sshKey(label = "Laptop Snapshot")),
            remote = entry("key-1", sshKey(label = "Laptop Remote"), clock = REMOTE_CLOCK),
        )
        val snippetConflict = conflictOf(
            SyncableEntityType.SNIPPET, "snippet-1",
            local = entry("snippet-1", snippet(label = "Update Snapshot")),
            remote = entry("snippet-1", snippet(label = "Update Remote"), clock = REMOTE_CLOCK),
        )
        val themeConflict = conflictOf(
            SyncableEntityType.CUSTOM_TERMINAL_THEME, "theme-1",
            local = entry("theme-1", theme(name = "Night Snapshot")),
            remote = entry("theme-1", theme(name = "Night Remote"), clock = REMOTE_CLOCK),
        )
        listOf(keyConflict, snippetConflict, themeConflict).forEach { fixture.conflictRepo.save(it) }
        val vm = viewModel(fixture)

        vm.keepLocal(keyConflict)
        vm.keepLocal(snippetConflict)
        vm.keepLocal(themeConflict)

        assertEquals(ApplyOutcome(3, 0), vm.applyAll())
        val writtenKey = fixture.sshKeyRepo.store.upserted.single()
        val writtenSnippet = fixture.snippetRepo.store.upserted.single()
        val writtenTheme = fixture.themeRepo.store.upserted.single()
        assertEquals("Laptop Current", writtenKey.payload?.label)
        assertEquals("Update Current", writtenSnippet.payload?.label)
        assertEquals("Night Current", writtenTheme.payload?.name)
        listOf(writtenKey.clock, writtenSnippet.clock, writtenTheme.clock).forEach {
            assertTrue(it.dominates(OLD_CLOCK))
            assertTrue(it.dominates(REMOTE_CLOCK))
        }
        assertEquals(listOf(NOW, NOW, NOW), listOf(writtenKey.updatedAt, writtenSnippet.updatedAt, writtenTheme.updatedAt))
        assertEquals(3, fixture.conflictRepo.deleted.size)
    }

    @Test
    fun `keepLocal keeps a local tombstone as a tombstone`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val tombstone = entry<Host>("host-1", payload = null, deletedAt = 1_500L)
        fixture.hostRepo.store.entries.add(tombstone)
        val conflict = conflictOf(
            SyncableEntityType.HOST,
            "host-1",
            local = entry("host-1", host(label = "Prod Snapshot")),
            remote = entry("host-1", host(label = "Prod Remote"), clock = REMOTE_CLOCK),
        )
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        val written = fixture.hostRepo.store.upserted.single()
        assertTrue(written.deleted)
        assertNull(written.payload)
        assertEquals(1_500L, written.deletedAt)
        assertTrue(written.clock.dominates(tombstone.clock))
        assertTrue(written.clock.dominates(REMOTE_CLOCK))
        assertEquals(NOW, written.updatedAt)
    }

    @Test
    fun `keepLocal only removes the marker when the entry no longer exists locally`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = makePendingConflict(localLabel = "Prod Snapshot")
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        // The snapshot must not bring back a removed row: the next pull restores the remote version.
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(0, fixture.hostRepo.store.upserted.size)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
    }

    @Test
    fun `keepLocal on a credential reasserts the local credential and deletes the marker`() = runTest {
        val credentialSync = mockk<CredentialSyncRepository>()
        val captured = slot<SyncEntry<CredentialEntry>>()
        coEvery { credentialSync.reassertLocalCredential(capture(captured)) } returns true
        val fixture = Fixture(FakePendingConflictRepository(), credentialSync = credentialSync)
        val conflict = credentialConflict()
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals("pwd:cred-1", captured.captured.id)
        assertEquals(REMOTE_CLOCK, captured.captured.clock)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
        coVerify(exactly = 0) { credentialSync.applyRemoteCredentialForced(any(), any()) }
    }

    @Test
    fun `keepLocal on a credential deletes the marker even when there is nothing to reassert`() = runTest {
        val credentialSync = mockk<CredentialSyncRepository>()
        coEvery { credentialSync.reassertLocalCredential(any()) } returns false
        val fixture = Fixture(FakePendingConflictRepository(), credentialSync = credentialSync)
        val conflict = credentialConflict()
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
        coVerify(exactly = 1) { credentialSync.reassertLocalCredential(any()) }
    }

    @Test
    fun `keepLocal on a credential leaves the conflict pending when the reassert throws`() = runTest {
        val credentialSync = mockk<CredentialSyncRepository>()
        coEvery { credentialSync.reassertLocalCredential(any()) } throws IllegalStateException("vault locked")
        val fixture = Fixture(FakePendingConflictRepository(), credentialSync = credentialSync)
        val conflict = credentialConflict()
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(0, 1), vm.applyAll())
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(listOf(conflict), fixture.conflictRepo.getAll())
        assertEquals(true, vm.resolution.value.choiceFor(conflict), "the choice is kept so the user can retry")
    }

    private fun credentialConflict(): PendingConflict {
        fun credential(hash: String, clock: VectorClock) = entry(
            "pwd:cred-1",
            CredentialEntry("cred-1", CredentialType.HOST_PASSWORD, "iv", "ciphertext", hash),
            clock = clock,
        )
        return conflictOf(
            SyncableEntityType.CREDENTIAL,
            "pwd:cred-1",
            local = credential("hash-local", OLD_CLOCK),
            remote = credential("hash-remote", REMOTE_CLOCK),
        )
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    fun `keepLocal with an undecodable remote leaves the conflict pending`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val valid = hostConflictWithNewerLocalRow(fixture)
        val conflict = valid.copy(remoteJson = "{not json")
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(0, 1), vm.applyAll())
        assertEquals(0, fixture.hostRepo.store.upserted.size)
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(listOf(conflict), fixture.conflictRepo.getAll())
        assertEquals(true, vm.resolution.value.choiceFor(conflict), "the choice is kept so the user can retry")
    }

    @Test
    fun `keepLocal leaves the conflict pending when the write fails`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        fixture.hostRepo.store.failOnUpsert = IllegalStateException("write refused")
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertEquals(ApplyOutcome(0, 1), vm.applyAll())
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(1, fixture.conflictRepo.getAll().size)
    }

    @Test
    fun `keepRemote leaves the conflict pending when the write fails`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = makePendingConflict()
        fixture.conflictRepo.save(conflict)
        fixture.hostRepo.store.failOnUpsert = IllegalStateException("foreign key refused")
        val vm = viewModel(fixture)

        vm.keepRemote(conflict)

        assertEquals(ApplyOutcome(0, 1), vm.applyAll())
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(1, fixture.conflictRepo.getAll().size)
    }

    /** A cancelled coroutine must not be swallowed as a failed write: it propagates and nothing is lost. */
    @Test
    fun `a cancellation propagates and leaves the conflict pending with its choice`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        fixture.hostRepo.store.failOnUpsert = CancellationException("cancelled")
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)

        assertFailsWith<CancellationException> { vm.applyAll() }
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(listOf(conflict), fixture.conflictRepo.getAll())
        assertEquals(true, vm.resolution.value.choiceFor(conflict))
        assertEquals(false, vm.applying.value, "the applying flag must not stay set")

        // The next call is not blocked by the cancelled one and applies the same choice.
        fixture.hostRepo.store.failOnUpsert = null
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
    }

    // ── keepRemote and undecided ──────────────────────────────────────────────

    /**
     * keepRemote then applyAll must upsert the decoded remote entry into the host repository
     * and then delete the conflict.
     */
    @Test
    fun `keepRemote then applyAll upserts remote entry and deletes conflict`() = runTest {
        val conflict = makePendingConflict(remoteLabel = "Prod Remote")
        val fixture = Fixture(FakePendingConflictRepository(listOf(conflict)))
        val expectedRemoteEntry = ConflictSerializer.decodeHost(conflict.remoteJson)
        val vm = viewModel(fixture)

        vm.keepRemote(conflict)
        val outcome = vm.applyAll()

        assertEquals(ApplyOutcome(1, 0), outcome)
        assertEquals(listOf(expectedRemoteEntry), fixture.hostRepo.store.upserted)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
    }

    /** Applying twice must not write twice: the applied choice is forgotten and the marker is gone. */
    @Test
    fun `a second applyAll after a success returns nothing and writes nothing`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)

        vm.keepLocal(conflict)
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(ApplyOutcome(0, 0), vm.applyAll())

        assertEquals(1, fixture.hostRepo.store.upserted.size)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
        assertEquals(emptyMap(), vm.resolution.value)
    }

    /** Two overlapping calls (double click) are serialized: the choice is applied once. */
    @Test
    fun `two concurrent applyAll calls apply a choice once and the flag follows the run`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val conflict = hostConflictWithNewerLocalRow(fixture)
        fixture.conflictRepo.save(conflict)
        val vm = viewModel(fixture)
        val applyingDuringUpsert = mutableListOf<Boolean>()
        fixture.hostRepo.store.beforeUpsert = {
            applyingDuringUpsert += vm.applying.value
            yield() // let the second call run while the first one is mid-write
        }

        vm.keepLocal(conflict)
        assertEquals(false, vm.applying.value)
        val first = async { vm.applyAll() }
        val second = async { vm.applyAll() }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, outcomes.sumOf { it.resolved })
        assertEquals(0, outcomes.sumOf { it.failed })
        assertEquals(1, fixture.hostRepo.store.upserted.size)
        assertEquals(listOf(conflict.id), fixture.conflictRepo.deleted)
        assertEquals(listOf(true), applyingDuringUpsert)
        assertEquals(false, vm.applying.value)
    }

    /**
     * With 2 conflicts, one resolved (keepLocal), one unresolved,
     * only the resolved conflict must be processed.
     */
    @Test
    fun `applyAll ignores conflicts without resolution`() = runTest {
        val conflict1 = makePendingConflict(id = "conflict-1", entityId = "host-1")
        val conflict2 = makePendingConflict(id = "conflict-2", entityId = "host-2")
        val fixture = Fixture(FakePendingConflictRepository(listOf(conflict1, conflict2)))
        fixture.hostRepo.store.entries.add(makeHostEntry(id = "host-1"))
        fixture.hostRepo.store.entries.add(makeHostEntry(id = "host-2"))
        val vm = viewModel(fixture)

        // Only keep local for conflict1: conflict2 left undecided
        vm.keepLocal(conflict1)
        val outcome = vm.applyAll()

        assertEquals(ApplyOutcome(1, 0), outcome)
        assertEquals(listOf(conflict1.id), fixture.conflictRepo.deleted)
        assertEquals(listOf("host-1"), fixture.hostRepo.store.upserted.map { it.id })
    }

    // ── Choices attached to the element ───────────────────────────────────────

    /**
     * On Desktop every push from the peer re-detects the conflict with a new random id. A choice
     * made on the old id and not yet applied must still be applied to the new conflict, as long as
     * the two versions on screen are the same.
     */
    @Test
    fun `a choice survives the conflict being re-detected with a new id and the same content`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val first = hostConflictWithNewerLocalRow(fixture).copy(id = "conflict-old")
        fixture.conflictRepo.save(first)
        val vm = viewModel(fixture)

        vm.keepLocal(first)
        val redetected = first.copy(id = "conflict-new", detectedAt = first.detectedAt + 1)
        fixture.conflictRepo.redetect(redetected)

        assertEquals(true, vm.resolution.value.choiceFor(redetected))
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(listOf("conflict-new"), fixture.conflictRepo.deleted)
        assertEquals("Prod Edited Since", fixture.hostRepo.store.upserted.single().payload?.label)
    }

    /** The user chose between two versions: if the remote one changed, they must see it and choose again. */
    @Test
    fun `a choice is dropped when the conflict is re-detected with another remote version`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val first = hostConflictWithNewerLocalRow(fixture).copy(id = "conflict-old")
        fixture.conflictRepo.save(first)
        val vm = viewModel(fixture)

        vm.keepLocal(first)
        val newerRemote = entry("host-1", host(label = "Prod Remote Again"), clock = VectorClock(mapOf("device-android" to 5L)))
        val redetected = first.copy(
            id = "conflict-new",
            remoteJson = ConflictSerializer.encode(SyncableEntityType.HOST, newerRemote),
        )
        fixture.conflictRepo.redetect(redetected)

        assertNull(vm.resolution.value.choiceFor(redetected), "the old choice is not shown as selected")
        assertEquals(ApplyOutcome(0, 0), vm.applyAll())
        assertEquals(0, fixture.hostRepo.store.upserted.size)
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
        assertEquals(listOf(redetected), fixture.conflictRepo.getAll())

        // Choosing again, on the new versions, applies.
        vm.keepLocal(redetected)
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(listOf("conflict-new"), fixture.conflictRepo.deleted)
    }

    @Test
    fun `a choice is dropped when the conflict is re-detected with another local version`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val first = hostConflictWithNewerLocalRow(fixture).copy(id = "conflict-old")
        fixture.conflictRepo.save(first)
        val vm = viewModel(fixture)

        vm.keepRemote(first)
        val editedLocal = entry("host-1", host(label = "Prod Edited Again"), clock = VectorClock(mapOf("device-A" to 1L, DEVICE_ID to 4L)))
        val redetected = first.copy(
            id = "conflict-new",
            localJson = ConflictSerializer.encode(SyncableEntityType.HOST, editedLocal),
        )
        fixture.conflictRepo.redetect(redetected)

        assertNull(vm.resolution.value.choiceFor(redetected))
        assertEquals(ApplyOutcome(0, 0), vm.applyAll())
        assertEquals(0, fixture.hostRepo.store.upserted.size)
        assertEquals(emptyList(), fixture.conflictRepo.deleted)
    }

    @Test
    fun `a choice made for one entity type does not apply to another with the same id`() = runTest {
        val hostConflict = makePendingConflict(id = "conflict-host", entityId = "same-id")
        val tunnelConflict = conflictOf(
            SyncableEntityType.TUNNEL,
            "same-id",
            local = entry("same-id", tunnel(id = "same-id", label = "Local")),
            remote = entry("same-id", tunnel(id = "same-id", label = "Remote"), clock = REMOTE_CLOCK),
            id = "conflict-tunnel",
        )
        val fixture = Fixture(FakePendingConflictRepository(listOf(hostConflict, tunnelConflict)))
        val vm = viewModel(fixture)

        vm.keepRemote(hostConflict)

        assertNull(vm.resolution.value.choiceFor(tunnelConflict))
        assertEquals(ApplyOutcome(1, 0), vm.applyAll())
        assertEquals(listOf("conflict-host"), fixture.conflictRepo.deleted)
        assertEquals(0, fixture.tunnelRepo.store.upserted.size)
    }

    @Test
    fun `an applied choice is forgotten and a failed one is kept`() = runTest {
        val fixture = Fixture(FakePendingConflictRepository())
        val applied = hostConflictWithNewerLocalRow(fixture) // host-1, applies
        val failing = conflictOf(
            SyncableEntityType.TUNNEL,
            "tunnel-1",
            local = entry("tunnel-1", tunnel()),
            remote = entry("tunnel-1", tunnel(label = "Remote"), clock = REMOTE_CLOCK),
        ).copy(remoteJson = "{not json")
        fixture.conflictRepo.save(applied)
        fixture.conflictRepo.save(failing)
        val vm = viewModel(fixture)

        vm.keepLocal(applied)
        vm.keepLocal(failing)
        assertEquals(ApplyOutcome(resolved = 1, failed = 1), vm.applyAll())

        assertEquals(setOf(failing.choiceKey), vm.resolution.value.keys)
        assertNotNull(fixture.conflictRepo.getAll().singleOrNull { it.id == failing.id })
    }
}
