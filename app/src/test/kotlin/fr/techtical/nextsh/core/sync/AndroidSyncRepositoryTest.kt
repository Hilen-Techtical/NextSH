// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncEntry
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [AndroidSyncRepository]: conflict persistence and auto-resolve.
 */
class AndroidSyncRepositoryTest {

    private lateinit var hostRepo: HostRepository
    private lateinit var tunnelRepo: TunnelRepository
    private lateinit var sshKeyRepo: SshKeyRepository
    private lateinit var snippetRepo: SnippetRepository
    private lateinit var customThemeRepo: CustomTerminalThemeRepository
    private lateinit var pendingConflictRepository: PendingConflictRepository
    private lateinit var orphanTunnelRepair: OrphanTunnelRepair
    private val transactionRunner = FakeTransactionRunner()
    private lateinit var repository: AndroidSyncRepository

    private val deviceA = "device-A"
    private val deviceB = "device-B"
    private val hostId = "host-uuid-001"

    private fun makeHost(id: String, label: String) = Host(
        id = id,
        label = label,
        hostname = "example.com",
        port = 22,
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private fun makeSyncEntry(host: Host, clockEntries: Map<String, Long>) = SyncEntry(
        id = host.id,
        payload = host,
        clock = VectorClock(clockEntries),
        updatedAt = clockEntries.values.maxOrNull() ?: 0L,
    )

    @BeforeEach
    fun setUp() {
        hostRepo = mockk()
        tunnelRepo = mockk()
        sshKeyRepo = mockk()
        snippetRepo = mockk()
        customThemeRepo = mockk()
        pendingConflictRepository = mockk()
        orphanTunnelRepair = mockk()
        coEvery { orphanTunnelRepair.runSafely() } returns 0

        // Default: empty entries for repos not under test
        coEvery { tunnelRepo.getAllSyncEntries() } returns emptyList()
        coEvery { sshKeyRepo.getAllSyncEntries() } returns emptyList()
        coEvery { snippetRepo.getAllSyncEntries() } returns emptyList()
        coEvery { customThemeRepo.getAllSyncEntries() } returns emptyList()

        repository = AndroidSyncRepository(
            hostRepo = hostRepo,
            tunnelRepo = tunnelRepo,
            sshKeyRepo = sshKeyRepo,
            snippetRepo = snippetRepo,
            customThemeRepo = customThemeRepo,
            pendingConflictRepository = pendingConflictRepository,
            orphanTunnelRepair = orphanTunnelRepair,
            transactionRunner = transactionRunner,
        )
    }

    // ── Test 1 : conflit détecté → persisté avec dedup ────────────────────────

    @Test
    fun `conflict detected is persisted in PendingConflictRepository`() = runTest {
        // Two concurrent entries for the same host: clocks diverge on different devices
        val localEntry = makeSyncEntry(makeHost(hostId, "Local Label"), mapOf(deviceA to 2L, deviceB to 1L))
        val remoteEntry = makeSyncEntry(makeHost(hostId, "Remote Label"), mapOf(deviceA to 1L, deviceB to 2L))

        coEvery { hostRepo.getAllSyncEntries() } returns listOf(localEntry)

        val savedConflict = slot<PendingConflict>()
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } returns Unit
        coEvery { pendingConflictRepository.save(capture(savedConflict)) } returns Unit

        val bundle = SyncBundle(
            hosts = listOf(remoteEntry),
            tunnels = emptyList(),
            sshKeys = emptyList(),
            snippets = emptyList(),
        )

        val result = repository.applyRemoteBundle(bundle)

        // ApplyResult.conflicts is still populated for the caller
        assertEquals(1, result.conflicts.size)
        assertEquals(hostId, result.conflicts.first().local.id)

        // deleteByEntity called before save (dedup)
        coVerifyOrder {
            pendingConflictRepository.deleteByEntity(SyncableEntityType.HOST, hostId)
            pendingConflictRepository.save(any())
        }

        // Persisted conflict has correct shape
        assertEquals(SyncableEntityType.HOST, savedConflict.captured.entityType)
        assertEquals(hostId, savedConflict.captured.entityId)
    }

    @Test
    fun `a conflict marker is replaced in one transaction, never left missing in between`() = runTest {
        // OrphanTunnelRepair spares the tunnels of a host only while its HOST marker exists: a
        // reader landing between the delete and the save would see no marker.
        val localEntry = makeSyncEntry(makeHost(hostId, "Local Label"), mapOf(deviceA to 2L, deviceB to 1L))
        val remoteEntry = makeSyncEntry(makeHost(hostId, "Remote Label"), mapOf(deviceA to 1L, deviceB to 2L))
        coEvery { hostRepo.getAllSyncEntries() } returns listOf(localEntry)
        val insideTransaction = mutableListOf<Boolean>()
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } coAnswers {
            insideTransaction += transactionRunner.active
        }
        coEvery { pendingConflictRepository.save(any()) } coAnswers {
            insideTransaction += transactionRunner.active
        }

        repository.applyRemoteBundle(
            SyncBundle(hosts = listOf(remoteEntry), tunnels = emptyList(), sshKeys = emptyList(), snippets = emptyList())
        )

        assertEquals(1, transactionRunner.runs, "one transaction for the replacement")
        assertEquals(listOf(true, true), insideTransaction, "the delete and the save are both inside it")
    }

    @Test
    fun `a failing save rolls the replacement back through the transaction`() = runTest {
        val localEntry = makeSyncEntry(makeHost(hostId, "Local Label"), mapOf(deviceA to 2L, deviceB to 1L))
        val remoteEntry = makeSyncEntry(makeHost(hostId, "Remote Label"), mapOf(deviceA to 1L, deviceB to 2L))
        coEvery { hostRepo.getAllSyncEntries() } returns listOf(localEntry)
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } returns Unit
        coEvery { pendingConflictRepository.save(any()) } throws IllegalStateException("disk full")

        var failed = false
        try {
            repository.applyRemoteBundle(
                SyncBundle(hosts = listOf(remoteEntry), tunnels = emptyList(), sshKeys = emptyList(), snippets = emptyList())
            )
        } catch (e: IllegalStateException) {
            failed = true
        }

        // The exception reaches the transaction boundary (which rolls back in production), it is
        // not swallowed between the delete and the save.
        assertTrue(failed)
        assertEquals(false, transactionRunner.active, "the transaction is closed")
    }

    // ── Test 2 : merge Clean → auto-résolution du conflit pending ─────────────

    @Test
    fun `clean merge removes any pre-existing conflict for that entity`() = runTest {
        // Remote clock dominates local → Clean merge, no conflict
        val localEntry = makeSyncEntry(makeHost(hostId, "Old Label"), mapOf(deviceA to 1L))
        val remoteEntry = makeSyncEntry(makeHost(hostId, "New Label"), mapOf(deviceA to 2L))

        coEvery { hostRepo.getAllSyncEntries() } returns listOf(localEntry)
        coEvery { hostRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } returns Unit

        val bundle = SyncBundle(
            hosts = listOf(remoteEntry),
            tunnels = emptyList(),
            sshKeys = emptyList(),
            snippets = emptyList(),
        )

        val result = repository.applyRemoteBundle(bundle)

        assertEquals(0, result.conflicts.size)
        assertEquals(1, result.cleanApplied)

        // deleteByEntity called to remove any stale conflict for this entity
        coVerify(exactly = 1) {
            pendingConflictRepository.deleteByEntity(SyncableEntityType.HOST, hostId)
        }

        // save must NOT have been called
        coVerify(exactly = 0) { pendingConflictRepository.save(any()) }
    }

    // ── Tunnels whose host is unknown locally ─────────────────────────────────
    //
    // A tunnel row has a foreign key on hosts(id). CrdtEngine.merge(null, hostTombstone) is Clean
    // but writing a tombstone for an unknown host creates no row, so a live tunnel of that host
    // would hit the foreign key and abort the whole sync (a phone paired after the host was
    // deleted on another device, for example). Such a tunnel is skipped, the rest is applied.

    private val orphanHostId = "host-deleted-elsewhere"

    private fun hostTombstone(id: String) = SyncEntry<Host>(
        id = id,
        payload = null,
        clock = VectorClock(mapOf(deviceB to 5L)),
        deleted = true,
        deletedAt = 4_000L,
        updatedAt = 4_000L,
    )

    private fun makeTunnel(id: String, forHostId: String) = TunnelConfig(
        id = id,
        label = "Tunnel $id",
        hostId = forHostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "localhost",
        remotePort = 80,
    )

    private fun tunnelEntry(tunnel: TunnelConfig, clockEntries: Map<String, Long> = mapOf(deviceB to 2L)) = SyncEntry(
        id = tunnel.id,
        payload = tunnel,
        clock = VectorClock(clockEntries),
        updatedAt = clockEntries.values.max(),
    )

    private fun stubAllWrites() {
        coEvery { hostRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { tunnelRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { sshKeyRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { snippetRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { customThemeRepo.upsertSyncEntry(any()) } returns Unit
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } returns Unit
    }

    @Test
    fun `live tunnel of an unknown host tombstone is skipped and the rest of the bundle is still applied`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns emptyList()
        stubAllWrites()
        // The real repository fails on this write (foreign key): the mock reproduces the abort.
        coEvery { tunnelRepo.upsertSyncEntry(match { it.id == "tunnel-orphan" }) } throws
            IllegalStateException("FOREIGN KEY constraint failed")

        val key = SyncEntry(
            id = "key-1",
            payload = SshKey(id = "key-1", label = "Key", keyType = SshKeyType.ED25519, publicKey = "ssh-ed25519 AAAA"),
            clock = VectorClock(mapOf(deviceB to 1L)),
            updatedAt = 1L,
        )
        val snippet = SyncEntry(
            id = "snippet-1",
            payload = Snippet(id = "snippet-1", label = "ls", command = "ls -la"),
            clock = VectorClock(mapOf(deviceB to 1L)),
            updatedAt = 1L,
        )
        val theme = SyncEntry(
            id = "theme-1",
            payload = CustomTerminalTheme(
                id = "theme-1", name = "Mine", background = 0, foreground = 1, cursor = 2, selectionBg = 3,
                ansi = List(16) { it },
            ),
            clock = VectorClock(mapOf(deviceB to 1L)),
            updatedAt = 1L,
        )
        val bundle = SyncBundle(
            hosts = listOf(hostTombstone(orphanHostId)),
            tunnels = listOf(tunnelEntry(makeTunnel("tunnel-orphan", orphanHostId))),
            sshKeys = listOf(key),
            snippets = listOf(snippet),
            customThemes = listOf(theme),
        )

        val result = repository.applyRemoteBundle(bundle)

        coVerify(exactly = 0) { tunnelRepo.upsertSyncEntry(any()) }
        coVerify(exactly = 1) { sshKeyRepo.upsertSyncEntry(key) }
        coVerify(exactly = 1) { snippetRepo.upsertSyncEntry(snippet) }
        coVerify(exactly = 1) { customThemeRepo.upsertSyncEntry(theme) }
        // The host tombstone (nothing to write) is still a clean merge; the skipped tunnel is not
        // counted and is not a conflict.
        assertEquals(4, result.cleanApplied)
        assertEquals(0, result.conflicts.size)
        coVerify(exactly = 0) { pendingConflictRepository.save(any()) }
    }

    @Test
    fun `tunnel of a live host carried by the same bundle is written`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns emptyList()
        stubAllWrites()
        val tunnel = tunnelEntry(makeTunnel("tunnel-1", hostId))
        val bundle = SyncBundle(
            hosts = listOf(makeSyncEntry(makeHost(hostId, "New host"), mapOf(deviceB to 1L))),
            tunnels = listOf(tunnel),
            sshKeys = emptyList(),
            snippets = emptyList(),
        )

        val result = repository.applyRemoteBundle(bundle)

        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(tunnel) }
        assertEquals(2, result.cleanApplied)
    }

    @Test
    fun `tunnel of a host already known locally is written`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns
            listOf(makeSyncEntry(makeHost(hostId, "Local host"), mapOf(deviceA to 1L)))
        stubAllWrites()
        val tunnel = tunnelEntry(makeTunnel("tunnel-1", hostId))

        val result = repository.applyRemoteBundle(
            SyncBundle(hosts = emptyList(), tunnels = listOf(tunnel), sshKeys = emptyList(), snippets = emptyList())
        )

        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(tunnel) }
        assertEquals(1, result.cleanApplied)
    }

    @Test
    fun `a tombstoned host still counts as present, its tunnel is written`() = runTest {
        // The row exists (tombstone), so the foreign key holds.
        coEvery { hostRepo.getAllSyncEntries() } returns listOf(hostTombstone(hostId))
        stubAllWrites()
        val tunnel = tunnelEntry(makeTunnel("tunnel-1", hostId))

        repository.applyRemoteBundle(
            SyncBundle(hosts = emptyList(), tunnels = listOf(tunnel), sshKeys = emptyList(), snippets = emptyList())
        )

        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(tunnel) }
    }

    @Test
    fun `tombstone of a tunnel is never skipped, even when its host is unknown`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns emptyList()
        stubAllWrites()
        val tunnelTombstone = SyncEntry<TunnelConfig>(
            id = "tunnel-gone",
            payload = null,
            clock = VectorClock(mapOf(deviceB to 3L)),
            deleted = true,
            deletedAt = 4_000L,
            updatedAt = 4_000L,
        )

        repository.applyRemoteBundle(
            SyncBundle(
                hosts = listOf(hostTombstone(orphanHostId)),
                tunnels = listOf(tunnelTombstone),
                sshKeys = emptyList(),
                snippets = emptyList(),
            )
        )

        // A tombstone has no payload and so no host reference: it must still reach the
        // repository, which writes nothing for an unknown id and deletes a known one.
        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(tunnelTombstone) }
    }

    @Test
    fun `only the orphan tunnel is skipped, its siblings in the same bundle are written`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns
            listOf(makeSyncEntry(makeHost(hostId, "Local host"), mapOf(deviceA to 1L)))
        stubAllWrites()
        val good = tunnelEntry(makeTunnel("tunnel-good", hostId))
        val orphan = tunnelEntry(makeTunnel("tunnel-orphan", orphanHostId))
        val other = tunnelEntry(makeTunnel("tunnel-other", hostId))

        val result = repository.applyRemoteBundle(
            SyncBundle(
                hosts = listOf(hostTombstone(orphanHostId)),
                tunnels = listOf(good, orphan, other),
                sshKeys = emptyList(),
                snippets = emptyList(),
            )
        )

        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(good) }
        coVerify(exactly = 1) { tunnelRepo.upsertSyncEntry(other) }
        coVerify(exactly = 0) { tunnelRepo.upsertSyncEntry(orphan) }
        assertTrue(result.cleanApplied == 3, "host tombstone + 2 tunnels, got ${result.cleanApplied}")
    }

    // ── Orphan tunnels left by the bundle ─────────────────────────────────────
    //
    // The live tunnel written under a tombstoned host (previous tests) is an orphan. The repair
    // runs once the whole bundle is applied, so the push of the same sync cycle carries the
    // tombstones it writes.

    @Test
    fun `the orphan tunnel repair runs once, after every write of the bundle`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns listOf(hostTombstone(hostId))
        stubAllWrites()
        val tunnel = tunnelEntry(makeTunnel("tunnel-1", hostId))
        val snippet = SyncEntry(
            id = "snippet-1",
            payload = Snippet(id = "snippet-1", label = "ls", command = "ls -la"),
            clock = VectorClock(mapOf(deviceB to 1L)),
            updatedAt = 1L,
        )

        repository.applyRemoteBundle(
            SyncBundle(hosts = emptyList(), tunnels = listOf(tunnel), sshKeys = emptyList(), snippets = listOf(snippet))
        )

        coVerify(exactly = 1) { orphanTunnelRepair.runSafely() }
        coVerifyOrder {
            tunnelRepo.upsertSyncEntry(tunnel)
            snippetRepo.upsertSyncEntry(snippet)
            orphanTunnelRepair.runSafely()
        }
    }

    @Test
    fun `the orphan tunnel repair also runs on an empty bundle`() = runTest {
        coEvery { hostRepo.getAllSyncEntries() } returns emptyList()

        repository.applyRemoteBundle(
            SyncBundle(hosts = emptyList(), tunnels = emptyList(), sshKeys = emptyList(), snippets = emptyList())
        )

        coVerify(exactly = 1) { orphanTunnelRepair.runSafely() }
    }
}
