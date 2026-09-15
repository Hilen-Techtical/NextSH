// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

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
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class ConflictResolutionViewModelTest {

    @MockK
    private lateinit var pendingConflictRepository: PendingConflictRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var tunnelRepository: TunnelRepository

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var snippetRepository: SnippetRepository

    @MockK
    private lateinit var customThemeRepository: CustomTerminalThemeRepository

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: ConflictResolutionViewModel

    // ── Test data helpers ─────────────────────────────────────────────────────

    private fun makeHostEntry(
        id: String = "host-1",
        label: String = "Prod",
    ): SyncEntry<Host> {
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

    // ── Setup / teardown ──────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { pendingConflictRepository.observeAll() } returns MutableStateFlow(emptyList())
        every { syncScheduler.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val credentialSyncRepository = io.mockk.mockk<fr.techtical.nextsh.shared.core.sync.CredentialSyncRepository>(relaxed = true)
    private val enrolledDeviceRepository = io.mockk.mockk<fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository>(relaxed = true)
    private val secretStore = io.mockk.mockk<fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore>(relaxed = true)

    private fun buildViewModel(initialConflicts: List<PendingConflict> = emptyList()): ConflictResolutionViewModel {
        every { pendingConflictRepository.observeAll() } returns MutableStateFlow(initialConflicts)
        coEvery { pendingConflictRepository.getAll() } returns initialConflicts
        return ConflictResolutionViewModel(
            pendingConflictRepository = pendingConflictRepository,
            hostRepository = hostRepository,
            tunnelRepository = tunnelRepository,
            sshKeyRepository = sshKeyRepository,
            snippetRepository = snippetRepository,
            customThemeRepository = customThemeRepository,
            syncScheduler = syncScheduler,
            credentialSyncRepository = credentialSyncRepository,
            enrolledDeviceRepository = enrolledDeviceRepository,
            secretStore = secretStore,
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * keepLocal then applyAll must only call deleteById: no upsert on any repo.
     */
    @Test
    fun `keepLocal then applyAll deletes conflict without upsert`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        // Verify no upsert was called on any typed repository
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { tunnelRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { sshKeyRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { snippetRepository.upsertSyncEntry(any()) }
    }

    /**
     * keepRemote then applyAll must upsert the decoded remote entry into the host repository
     * and then delete the conflict.
     */
    @Test
    fun `keepRemote then applyAll upserts remote entry and deletes conflict`() = runTest {
        val conflict = makePendingConflict(remoteLabel = "Prod Remote")
        viewModel = buildViewModel(listOf(conflict))

        // Decode the expected remote entry to verify the exact argument
        val expectedRemoteEntry = ConflictSerializer.decodeHost(conflict.remoteJson)

        coJustRun { hostRepository.upsertSyncEntry(expectedRemoteEntry) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepRemote(conflict)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(expectedRemoteEntry) }
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
    }

    /**
     * With 2 conflicts, one resolved (keepLocal), one unresolved,
     * only the resolved conflict must be processed.
     */
    @Test
    fun `applyAll ignores conflicts without resolution`() = runTest {
        val conflict1 = makePendingConflict(id = "conflict-1", entityId = "host-1")
        val conflict2 = makePendingConflict(id = "conflict-2", entityId = "host-2")
        viewModel = buildViewModel(listOf(conflict1, conflict2))

        coJustRun { pendingConflictRepository.deleteById(conflict1.id) }

        // Only keep local for conflict1, conflict2 left undecided
        viewModel.keepLocal(conflict1)
        val resolved = viewModel.applyAll()

        assertEquals(1, resolved)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict1.id) }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(conflict2.id) }
    }
}
