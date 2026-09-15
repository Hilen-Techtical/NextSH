// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.shared.core.sync.PendingConflict
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
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
}
