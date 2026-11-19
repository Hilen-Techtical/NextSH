// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.data.db.FakeTransactionRunner
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.MergeResult
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.core.sync.SyncEntry
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Convergence of "keep local" through [AndroidSyncRepository]: once the local version is
 * re-asserted with [CrdtEngine.keepLocal], the sync that follows must not detect the same
 * conflict again. Kept apart from [AndroidSyncRepositoryTest] on purpose.
 */
class AndroidSyncRepositoryKeepLocalTest {

    private lateinit var hostRepo: HostRepository
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
        pendingConflictRepository = mockk()
        val tunnelRepo = mockk<TunnelRepository>()
        val sshKeyRepo = mockk<SshKeyRepository>()
        val snippetRepo = mockk<SnippetRepository>()
        val customThemeRepo = mockk<CustomTerminalThemeRepository>()

        // Repositories not under test hold nothing.
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
            orphanTunnelRepair = mockk(relaxed = true),
            transactionRunner = FakeTransactionRunner(),
        )
    }

    /**
     * Two rounds against the same remote bundle: round 1 detects the conflict, the user keeps
     * the local version ([CrdtEngine.keepLocal] written back as ConflictResolutionViewModel
     * does), round 2 (the forced sync that follows the Apply button) must NOT recreate it.
     * Deleting the marker alone would leave the clocks concurrent and fail the second round.
     */
    @Test
    fun `keeping the local version stops the conflict from coming back on the next sync`() = runTest {
        var stored = makeSyncEntry(makeHost(hostId, "Local Label"), mapOf(deviceA to 2L, deviceB to 1L))
        val remoteEntry = makeSyncEntry(makeHost(hostId, "Remote Label"), mapOf(deviceA to 1L, deviceB to 2L))

        coEvery { hostRepo.getAllSyncEntries() } answers { listOf(stored) }
        coEvery { hostRepo.upsertSyncEntry(any()) } answers { stored = firstArg<SyncEntry<Host>>() }
        coEvery { pendingConflictRepository.deleteByEntity(any(), any()) } returns Unit
        coEvery { pendingConflictRepository.save(any()) } returns Unit

        val bundle = SyncBundle(
            hosts = listOf(remoteEntry),
            tunnels = emptyList(),
            sshKeys = emptyList(),
            snippets = emptyList(),
        )

        // Round 1: concurrent clocks, the conflict is detected and saved.
        val first = repository.applyRemoteBundle(bundle)
        assertEquals(1, first.conflicts.size)
        coVerify(exactly = 1) { pendingConflictRepository.save(any()) }

        // The user keeps the local version.
        hostRepo.upsertSyncEntry(CrdtEngine.keepLocal(stored, remoteEntry.clock, deviceA, 10_000L))

        // Round 2: same remote bundle, no new conflict, the local row wins as a clean merge.
        val second = repository.applyRemoteBundle(bundle)
        assertEquals(0, second.conflicts.size)
        assertEquals(1, second.cleanApplied)
        coVerify(exactly = 1) { pendingConflictRepository.save(any()) }
        assertEquals("Local Label", stored.payload?.label)
        assertTrue(stored.clock.dominates(remoteEntry.clock))

        // The peer receiving the local version applies it as a clean update too.
        assertEquals(MergeResult.Clean(stored), CrdtEngine.merge(remoteEntry, stored))
    }
}
