// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CrdtEngine
import fr.techtical.nextsh.shared.core.sync.CredentialEntry
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.DeviceIdentity
import fr.techtical.nextsh.shared.core.sync.MergeResult
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
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
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

    private fun makeHost(id: String = "host-1", label: String = "Prod") = Host(
        id = id,
        label = label,
        hostname = "example.com",
        port = 22,
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun makeHostEntry(
        id: String = "host-1",
        label: String = "Prod",
        clock: VectorClock = VectorClock(mapOf("device-A" to 1L)),
        updatedAt: Long = 1_000L,
    ): SyncEntry<Host> = SyncEntry(
        id = id,
        payload = makeHost(id, label),
        clock = clock,
        updatedAt = updatedAt,
    )

    private fun makeHostTombstone(
        id: String = "host-1",
        clock: VectorClock = VectorClock(mapOf("device-A" to 3L)),
        deletedAt: Long = 4_000L,
        updatedAt: Long = 4_000L,
    ): SyncEntry<Host> = SyncEntry(
        id = id,
        payload = null,
        clock = clock,
        deleted = true,
        deletedAt = deletedAt,
        updatedAt = updatedAt,
    )

    private fun makeTunnelEntry(
        id: String = "tunnel-1",
        label: String = "Web",
        clock: VectorClock = VectorClock(mapOf("device-A" to 1L)),
        updatedAt: Long = 1_000L,
    ): SyncEntry<TunnelConfig> = SyncEntry(
        id = id,
        payload = TunnelConfig(
            id = id,
            label = label,
            hostId = "host-1",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 8080,
            remoteHost = "localhost",
            remotePort = 80,
        ),
        clock = clock,
        updatedAt = updatedAt,
    )

    private fun makeSshKeyEntry(
        id: String = "key-1",
        label: String = "Laptop",
        clock: VectorClock = VectorClock(mapOf("device-A" to 1L)),
        updatedAt: Long = 1_000L,
    ): SyncEntry<SshKey> = SyncEntry(
        id = id,
        payload = SshKey(
            id = id,
            label = label,
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAItest",
        ),
        clock = clock,
        updatedAt = updatedAt,
    )

    private fun makeSnippetEntry(
        id: String = "snippet-1",
        label: String = "Disk",
        clock: VectorClock = VectorClock(mapOf("device-A" to 1L)),
        updatedAt: Long = 1_000L,
    ): SyncEntry<Snippet> = SyncEntry(
        id = id,
        payload = Snippet(id = id, label = label, command = "df -h", createdAt = 1L),
        clock = clock,
        updatedAt = updatedAt,
    )

    private fun makeThemeEntry(
        id: String = "theme-1",
        name: String = "Night",
        clock: VectorClock = VectorClock(mapOf("device-A" to 1L)),
        updatedAt: Long = 1_000L,
    ): SyncEntry<CustomTerminalTheme> = SyncEntry(
        id = id,
        payload = CustomTerminalTheme(
            id = id,
            name = name,
            background = 0xFF000000.toInt(),
            foreground = 0xFFFFFFFF.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            selectionBg = 0xFF333333.toInt(),
            ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() + it },
            createdAt = 1L,
        ),
        clock = clock,
        updatedAt = updatedAt,
    )

    private fun makeCredentialEntry(
        clock: VectorClock = VectorClock(mapOf("device-B" to 2L)),
    ): SyncEntry<CredentialEntry> = SyncEntry(
        id = "HOST_PASSWORD:cred-1",
        payload = CredentialEntry(
            credentialId = "cred-1",
            type = CredentialType.HOST_PASSWORD,
            iv = "aXZpdml2aXZpdml2",
            encryptedPayload = "Y2lwaGVydGV4dA",
            contentHashBase64 = "aGFzaGhhc2hoYXNo",
        ),
        clock = clock,
        updatedAt = 2_000L,
    )

    private fun makePendingConflict(
        id: String = "conflict-1",
        entityId: String = "host-1",
        localLabel: String = "Prod Local",
        remoteLabel: String = "Prod Remote",
    ): PendingConflict = conflictOf(
        id = id,
        type = SyncableEntityType.HOST,
        local = makeHostEntry(id = entityId, label = localLabel),
        remote = makeHostEntry(id = entityId, label = remoteLabel),
    )

    private fun conflictOf(
        type: SyncableEntityType,
        local: SyncEntry<*>,
        remote: SyncEntry<*>,
        id: String = "conflict-1",
    ): PendingConflict = PendingConflict(
        id = id,
        entityType = type,
        entityId = local.id,
        localJson = ConflictSerializer.encode(type, local),
        remoteJson = ConflictSerializer.encode(type, remote),
        detectedAt = System.currentTimeMillis(),
    )

    private fun assertSupersedes(result: VectorClock, vararg older: VectorClock) {
        older.forEach { assertTrue(result.dominates(it), "clock $result must dominate $it") }
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
    private val orphanTunnelRepair = io.mockk.mockk<OrphanTunnelRepair>(relaxed = true)

    // ── Orphan tunnels: a decided host may leave live tunnels under a deleted host ────────

    @Test
    fun `resolving a host conflict runs the orphan tunnel repair after the marker is deleted`() = runTest {
        val snapshot = makeHostEntry(label = "Prod Local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeHostEntry(label = "Prod Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val current = makeHostTombstone(clock = VectorClock(mapOf("device-A" to 3L)))
        val conflict = conflictOf(SyncableEntityType.HOST, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))
        coEvery { hostRepository.getAllSyncEntries() } returns listOf(current)
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        viewModel.applyAll()

        // The spared HOST marker must be gone before the repair looks for orphans.
        coVerifyOrder {
            pendingConflictRepository.deleteById(conflict.id)
            orphanTunnelRepair.runSafely()
        }
        coVerify(exactly = 1) { orphanTunnelRepair.runSafely() }
    }

    @Test
    fun `taking the remote host tombstone runs the orphan tunnel repair`() = runTest {
        val snapshot = makeHostEntry(label = "Prod Local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeHostTombstone(clock = VectorClock(mapOf("device-B" to 2L)))
        val conflict = conflictOf(SyncableEntityType.HOST, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepRemote(conflict)
        viewModel.applyAll()

        coVerify(exactly = 1) { orphanTunnelRepair.runSafely() }
    }

    @Test
    fun `resolving a tunnel conflict does not run the orphan tunnel repair`() = runTest {
        val snapshot = makeTunnelEntry(label = "Web local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeTunnelEntry(label = "Web remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val conflict = conflictOf(SyncableEntityType.TUNNEL, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))
        coJustRun { tunnelRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepRemote(conflict)
        viewModel.applyAll()

        coVerify(exactly = 0) { orphanTunnelRepair.runSafely() }
    }

    @Test
    fun `a host conflict that fails to apply does not run the orphan tunnel repair`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))
        coEvery { hostRepository.upsertSyncEntry(any()) } throws IllegalStateException("disk full")

        viewModel.keepRemote(conflict)
        viewModel.applyAll()

        coVerify(exactly = 0) { orphanTunnelRepair.runSafely() }
    }

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
            orphanTunnelRepair = orphanTunnelRepair,
        )
    }

    // ── keepLocal: re-asserts the CURRENT row, never the snapshot ────────────

    /**
     * keepLocal then applyAll must write the CURRENT stored row (not the snapshot taken when
     * the conflict was detected) back with a clock that dominates both versions and a newer
     * updatedAt, then delete the marker. Without this the clocks stay concurrent and the
     * very next sync recreates the conflict.
     */
    @Test
    fun `keepLocal then applyAll upserts the current row with a dominating clock and deletes conflict`() = runTest {
        val deviceId = DeviceIdentity.deviceId()
        val snapshot = makeHostEntry(label = "Prod Local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeHostEntry(label = "Prod Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        // The user edited the host after the conflict was detected: row is ahead of the snapshot.
        val current = makeHostEntry(
            label = "Prod Edited",
            clock = VectorClock(mapOf("device-A" to 3L)),
            updatedAt = 5_000L,
        )
        val other = makeHostEntry(id = "host-2", label = "Other")
        val conflict = conflictOf(SyncableEntityType.HOST, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(other, current)
        val upserted = slot<SyncEntry<Host>>()
        coJustRun { hostRepository.upsertSyncEntry(capture(upserted)) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        val before = System.currentTimeMillis()
        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(any()) }
        val written = upserted.captured
        assertEquals("host-1", written.id)
        // The edit made since detection survives: the snapshot label is not written back.
        assertEquals(current.payload, written.payload)
        assertSupersedes(written.clock, current.clock, remote.clock, snapshot.clock)
        assertTrue(written.clock.entries.getValue(deviceId) >= before)
        assertTrue(written.updatedAt >= before)
        assertTrue(written.updatedAt > current.updatedAt)
        assertEquals(false, written.deleted)
        // Convergence: the written entry wins as a clean merge on both devices, whichever
        // side is local, and carries the written payload (no conflict can come back).
        assertEquals(MergeResult.Clean(written), CrdtEngine.merge(remote, written))
        assertEquals(MergeResult.Clean(written), CrdtEngine.merge(written, remote))
        coVerifyOrder {
            hostRepository.upsertSyncEntry(any())
            pendingConflictRepository.deleteById(conflict.id)
        }
        coVerify(exactly = 0) { tunnelRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { sshKeyRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { snippetRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { customThemeRepository.upsertSyncEntry(any()) }
    }

    @Test
    fun `keepLocal on a tunnel conflict upserts the current tunnel row with a dominating clock`() = runTest {
        val snapshot = makeTunnelEntry(label = "Web local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeTunnelEntry(label = "Web remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val current = makeTunnelEntry(
            label = "Web edited",
            clock = VectorClock(mapOf("device-A" to 4L)),
            updatedAt = 6_000L,
        )
        val conflict = conflictOf(SyncableEntityType.TUNNEL, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))

        coEvery { tunnelRepository.getAllSyncEntries() } returns listOf(current)
        val upserted = slot<SyncEntry<TunnelConfig>>()
        coJustRun { tunnelRepository.upsertSyncEntry(capture(upserted)) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        val before = System.currentTimeMillis()
        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        assertEquals(current.payload, upserted.captured.payload)
        assertSupersedes(upserted.captured.clock, current.clock, remote.clock)
        assertTrue(upserted.captured.updatedAt >= before)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
    }

    @Test
    fun `keepLocal on a local deletion keeps the tombstone and supersedes the remote clock`() = runTest {
        val snapshot = makeHostEntry(label = "Prod Local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeHostEntry(label = "Prod Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        // The host was deleted locally after the conflict was detected.
        val current = makeHostTombstone(clock = VectorClock(mapOf("device-A" to 3L)), deletedAt = 4_000L)
        val conflict = conflictOf(SyncableEntityType.HOST, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(current)
        val upserted = slot<SyncEntry<Host>>()
        coJustRun { hostRepository.upsertSyncEntry(capture(upserted)) }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        val before = System.currentTimeMillis()
        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        val written = upserted.captured
        assertNull(written.payload)
        assertEquals(true, written.deleted)
        assertEquals(4_000L, written.deletedAt)
        assertSupersedes(written.clock, current.clock, remote.clock)
        assertTrue(written.updatedAt >= before)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
    }

    /**
     * The snapshot in localJson is NEVER written back: it predates later edits, and for a
     * missing row it would resurrect it (or hit the tunnel foreign key). With no current
     * row there is nothing to re-assert: only the marker goes, and the next pull restores
     * the remote version through a clean merge.
     */
    @Test
    fun `keepLocal drops only the marker when the current row is missing`() = runTest {
        val snapshot = makeHostEntry(label = "Prod Local", clock = VectorClock(mapOf("device-A" to 1L)))
        val remote = makeHostEntry(label = "Prod Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val conflict = conflictOf(SyncableEntityType.HOST, snapshot, remote)
        viewModel = buildViewModel(listOf(conflict))

        // Only another host exists: the conflicted element has no current row.
        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry(id = "host-2"))
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        val outcome = viewModel.applyAll()

        assertEquals(ApplyOutcome(resolved = 1, failed = 0), outcome)
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
    }

    @Test
    fun `keepLocal on an ssh key snippet and theme conflict writes to the matching repository`() = runTest {
        val sshLocal = makeSshKeyEntry(clock = VectorClock(mapOf("device-A" to 1L)))
        val sshRemote = makeSshKeyEntry(label = "Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val snippetLocal = makeSnippetEntry(clock = VectorClock(mapOf("device-A" to 1L)))
        val snippetRemote = makeSnippetEntry(label = "Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val themeLocal = makeThemeEntry(clock = VectorClock(mapOf("device-A" to 1L)))
        val themeRemote = makeThemeEntry(name = "Remote", clock = VectorClock(mapOf("device-B" to 2L)))
        val conflicts = listOf(
            conflictOf(SyncableEntityType.SSH_KEY, sshLocal, sshRemote, id = "c-key"),
            conflictOf(SyncableEntityType.SNIPPET, snippetLocal, snippetRemote, id = "c-snippet"),
            conflictOf(SyncableEntityType.CUSTOM_TERMINAL_THEME, themeLocal, themeRemote, id = "c-theme"),
        )
        viewModel = buildViewModel(conflicts)

        coEvery { sshKeyRepository.getAllSyncEntries() } returns listOf(sshLocal)
        coEvery { snippetRepository.getAllSyncEntries() } returns listOf(snippetLocal)
        coEvery { customThemeRepository.getAllSyncEntries() } returns listOf(themeLocal)
        val sshWritten = slot<SyncEntry<SshKey>>()
        val snippetWritten = slot<SyncEntry<Snippet>>()
        val themeWritten = slot<SyncEntry<CustomTerminalTheme>>()
        coJustRun { sshKeyRepository.upsertSyncEntry(capture(sshWritten)) }
        coJustRun { snippetRepository.upsertSyncEntry(capture(snippetWritten)) }
        coJustRun { customThemeRepository.upsertSyncEntry(capture(themeWritten)) }
        coJustRun { pendingConflictRepository.deleteById(any()) }

        conflicts.forEach { viewModel.keepLocal(it) }
        val resolved = viewModel.applyAll().resolved

        assertEquals(3, resolved)
        assertEquals(sshLocal.payload, sshWritten.captured.payload)
        assertSupersedes(sshWritten.captured.clock, sshLocal.clock, sshRemote.clock)
        assertEquals(snippetLocal.payload, snippetWritten.captured.payload)
        assertSupersedes(snippetWritten.captured.clock, snippetLocal.clock, snippetRemote.clock)
        assertEquals(themeLocal.payload, themeWritten.captured.payload)
        assertSupersedes(themeWritten.captured.clock, themeLocal.clock, themeRemote.clock)
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { tunnelRepository.upsertSyncEntry(any()) }
    }

    // ── keepLocal: credentials ───────────────────────────────────────────────

    @Test
    fun `keepLocal on a credential conflict reasserts the local credential and deletes the marker`() = runTest {
        val remote = makeCredentialEntry(clock = VectorClock(mapOf("device-B" to 2L)))
        val conflict = conflictOf(
            type = SyncableEntityType.CREDENTIAL,
            local = makeCredentialEntry(clock = VectorClock(mapOf("device-A" to 1L))),
            remote = remote,
        )
        viewModel = buildViewModel(listOf(conflict))

        val reasserted = slot<SyncEntry<CredentialEntry>>()
        coEvery { credentialSyncRepository.reassertLocalCredential(capture(reasserted)) } returns true
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        assertEquals(remote.id, reasserted.captured.id)
        assertEquals(remote.clock, reasserted.captured.clock)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        coVerify(exactly = 0) { credentialSyncRepository.applyRemoteCredentialForced(any(), any()) }
        assertNoSecretAccess()
    }

    @Test
    fun `keepLocal on a credential still deletes the marker when there is nothing to reassert`() = runTest {
        val conflict = conflictOf(
            type = SyncableEntityType.CREDENTIAL,
            local = makeCredentialEntry(clock = VectorClock(mapOf("device-A" to 1L))),
            remote = makeCredentialEntry(clock = VectorClock(mapOf("device-B" to 2L))),
        )
        viewModel = buildViewModel(listOf(conflict))

        coEvery { credentialSyncRepository.reassertLocalCredential(any()) } returns false
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 1) { credentialSyncRepository.reassertLocalCredential(any()) }
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        assertNoSecretAccess()
    }

    /**
     * Keeping the local credential only re-stamps vault metadata: no enrolled device is
     * looked up and no shared secret is retrieved (unlike taking the remote version).
     */
    private fun assertNoSecretAccess() {
        confirmVerified(secretStore, enrolledDeviceRepository)
    }

    // ── Failures leave the conflict pending ──────────────────────────────────

    @Test
    fun `keepLocal with an undecodable remote leaves the conflict pending and keeps the choice`() = runTest {
        val good = makePendingConflict()
        val conflict = good.copy(remoteJson = "not a sync entry")
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())

        viewModel.keepLocal(conflict)
        val outcome = viewModel.applyAll()

        assertEquals(ApplyOutcome(resolved = 0, failed = 1), outcome)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
        assertEquals(true, viewModel.resolution.value.choiceFor(conflict))
    }

    @Test
    fun `keepLocal with a failing write leaves the conflict pending`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coEvery { hostRepository.upsertSyncEntry(any()) } throws IllegalStateException("disk full")

        viewModel.keepLocal(conflict)
        val outcome = viewModel.applyAll()

        assertEquals(ApplyOutcome(resolved = 0, failed = 1), outcome)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
        assertEquals(true, viewModel.resolution.value.choiceFor(conflict))
    }

    @Test
    fun `a retry after a failed write succeeds and clears the choice`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coEvery { hostRepository.upsertSyncEntry(any()) } throws IllegalStateException("disk full") andThen Unit
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        assertEquals(ApplyOutcome(resolved = 0, failed = 1), viewModel.applyAll())
        // The choice survived the failure: no need to choose again to retry.
        assertEquals(true, viewModel.resolution.value.choiceFor(conflict))
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }

        assertEquals(ApplyOutcome(resolved = 1, failed = 0), viewModel.applyAll())
        assertTrue(viewModel.resolution.value.isEmpty())
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        coVerify(exactly = 2) { hostRepository.upsertSyncEntry(any()) }
    }

    @Test
    fun `one failure does not stop the others and is reported next to the resolved count`() = runTest {
        val failing = makePendingConflict(id = "c-fail", entityId = "host-1")
        val fine = makePendingConflict(id = "c-ok", entityId = "host-2")
        viewModel = buildViewModel(listOf(failing, fine))

        coEvery { hostRepository.getAllSyncEntries() } returns
            listOf(makeHostEntry(id = "host-1"), makeHostEntry(id = "host-2"))
        coEvery { hostRepository.upsertSyncEntry(match { it.id == "host-1" }) } throws IllegalStateException("disk full")
        coJustRun { hostRepository.upsertSyncEntry(match { it.id == "host-2" }) }
        coJustRun { pendingConflictRepository.deleteById("c-ok") }

        viewModel.keepLocal(failing)
        viewModel.keepLocal(fine)
        val outcome = viewModel.applyAll()

        assertEquals(ApplyOutcome(resolved = 1, failed = 1), outcome)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById("c-ok") }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById("c-fail") }
        assertEquals(true, viewModel.resolution.value.choiceFor(failing))
        assertNull(viewModel.resolution.value.choiceFor(fine))
    }

    /**
     * A cancelled apply (the user left the screen) must propagate the cancellation instead
     * of reporting a failure, and must neither delete the marker nor lose the choice nor
     * leave the applying flag stuck.
     */
    @Test
    fun `a cancellation during apply is rethrown and leaves everything pending`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coEvery { hostRepository.upsertSyncEntry(any()) } throws CancellationException("screen left")

        viewModel.keepLocal(conflict)
        var rethrown: Throwable? = null
        try {
            viewModel.applyAll()
        } catch (e: CancellationException) {
            rethrown = e
        }

        assertTrue(rethrown is CancellationException)
        assertFalse(viewModel.applying.value)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
        assertEquals(true, viewModel.resolution.value.choiceFor(conflict))
    }

    @Test
    fun `keepRemote cancellation is rethrown too`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.upsertSyncEntry(any()) } throws CancellationException("screen left")

        viewModel.keepRemote(conflict)
        var rethrown: Throwable? = null
        try {
            viewModel.applyAll()
        } catch (e: CancellationException) {
            rethrown = e
        }

        assertTrue(rethrown is CancellationException)
        assertFalse(viewModel.applying.value)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
    }

    /** A double tap on Apply must not apply twice: the second call is rejected while the first runs. */
    @Test
    fun `a second applyAll while one is running is rejected`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        val gate = CompletableDeferred<Unit>()
        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coEvery { hostRepository.upsertSyncEntry(any()) } coAnswers { gate.await() }
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        val first = async { viewModel.applyAll() }
        runCurrent()
        assertTrue(viewModel.applying.value)

        val second = viewModel.applyAll()
        assertEquals(ApplyOutcome(resolved = 0, failed = 0), second)

        gate.complete(Unit)
        assertEquals(ApplyOutcome(resolved = 1, failed = 0), first.await())
        assertFalse(viewModel.applying.value)
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
    }

    @Test
    fun `keepLocal on a credential with an undecodable remote leaves the conflict pending`() = runTest {
        val conflict = conflictOf(
            type = SyncableEntityType.CREDENTIAL,
            local = makeCredentialEntry(clock = VectorClock(mapOf("device-A" to 1L))),
            remote = makeCredentialEntry(clock = VectorClock(mapOf("device-B" to 2L))),
        ).copy(remoteJson = "garbage")
        viewModel = buildViewModel(listOf(conflict))

        viewModel.keepLocal(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(0, resolved)
        coVerify(exactly = 0) { credentialSyncRepository.reassertLocalCredential(any()) }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
    }

    @Test
    fun `keepRemote with a failing write leaves the conflict pending`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.upsertSyncEntry(any()) } throws IllegalStateException("disk full")

        viewModel.keepRemote(conflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(0, resolved)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
        assertEquals(false, viewModel.resolution.value.choiceFor(conflict))
    }

    // ── keepRemote and undecided conflicts ───────────────────────────────────

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
        val resolved = viewModel.applyAll().resolved

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

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry(id = "host-1"))
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(conflict1.id) }

        // Only keep local for conflict1, conflict2 left undecided
        viewModel.keepLocal(conflict1)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict1.id) }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(conflict2.id) }
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(any()) }
    }

    // ── Choices are attached to the element, not to the conflict id ──────────

    /**
     * A sync that runs between the tap and the Apply button re-detects the conflict under a
     * new random id. The choice must follow the element (type + entity id), not the id.
     */
    @Test
    fun `a choice survives the conflict being re-detected under a new id`() = runTest {
        val original = makePendingConflict(id = "conflict-1")
        viewModel = buildViewModel(listOf(original))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(any()) }

        viewModel.keepLocal(original)

        // Background sync: same element, new marker id.
        val redetected = original.copy(id = "conflict-2", detectedAt = original.detectedAt + 1)
        coEvery { pendingConflictRepository.getAll() } returns listOf(redetected)

        assertEquals(true, viewModel.resolution.value.choiceFor(redetected))
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById("conflict-2") }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById("conflict-1") }
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(any()) }
    }

    @Test
    fun `a keepRemote choice also survives a re-detection with identical content`() = runTest {
        val original = makePendingConflict(id = "conflict-1", remoteLabel = "Remote v1")
        viewModel = buildViewModel(listOf(original))

        viewModel.keepRemote(original)

        val redetected = original.copy(id = "conflict-2", detectedAt = original.detectedAt + 60_000L)
        coEvery { pendingConflictRepository.getAll() } returns listOf(redetected)
        val expected = ConflictSerializer.decodeHost(redetected.remoteJson)
        coJustRun { hostRepository.upsertSyncEntry(expected) }
        coJustRun { pendingConflictRepository.deleteById("conflict-2") }

        assertEquals(false, viewModel.resolution.value.choiceFor(redetected))
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(expected) }
    }

    /**
     * The user decided on the versions they saw. If the next sync re-detects the conflict
     * with other content (the peer edited again), the choice no longer applies and is not
     * shown as selected: the user chooses again after seeing the new versions.
     */
    @Test
    fun `a re-detection with a different remote version does not inherit the choice`() = runTest {
        val original = makePendingConflict(id = "conflict-1", remoteLabel = "Remote v1")
        viewModel = buildViewModel(listOf(original))

        viewModel.keepRemote(original)

        val redetected = makePendingConflict(id = "conflict-2", remoteLabel = "Remote v2")
        coEvery { pendingConflictRepository.getAll() } returns listOf(redetected)

        assertNull(viewModel.resolution.value.choiceFor(redetected))
        val outcome = viewModel.applyAll()

        assertEquals(ApplyOutcome(resolved = 0, failed = 0), outcome)
        coVerify(exactly = 0) { hostRepository.upsertSyncEntry(any()) }
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }

        // After seeing the new versions the user chooses again, and that choice applies.
        val expected = ConflictSerializer.decodeHost(redetected.remoteJson)
        coJustRun { hostRepository.upsertSyncEntry(expected) }
        coJustRun { pendingConflictRepository.deleteById("conflict-2") }
        viewModel.keepRemote(redetected)

        assertEquals(false, viewModel.resolution.value.choiceFor(redetected))
        assertEquals(ApplyOutcome(resolved = 1, failed = 0), viewModel.applyAll())
        coVerify(exactly = 1) { hostRepository.upsertSyncEntry(expected) }
    }

    @Test
    fun `a re-detection with a different local version does not inherit the choice either`() = runTest {
        val original = makePendingConflict(id = "conflict-1", localLabel = "Local v1")
        viewModel = buildViewModel(listOf(original))

        viewModel.keepLocal(original)

        // The user edited the host since: same remote, new local snapshot.
        val redetected = makePendingConflict(id = "conflict-2", localLabel = "Local v2")
        coEvery { pendingConflictRepository.getAll() } returns listOf(redetected)

        assertNull(viewModel.resolution.value.choiceFor(redetected))
        assertEquals(ApplyOutcome(resolved = 0, failed = 0), viewModel.applyAll())
        coVerify(exactly = 0) { pendingConflictRepository.deleteById(any()) }
    }

    @Test
    fun `the fingerprint depends on the two versions only`() {
        val conflict = makePendingConflict(id = "conflict-1")

        // Same content under a new id and detection time: same fingerprint.
        assertEquals(
            conflict.fingerprint(),
            conflict.copy(id = "conflict-2", detectedAt = conflict.detectedAt + 1).fingerprint(),
        )
        // Different content on either side: different fingerprint.
        assertTrue(conflict.fingerprint() != makePendingConflict(remoteLabel = "Other").fingerprint())
        assertTrue(conflict.fingerprint() != makePendingConflict(localLabel = "Other").fingerprint())
        // Moving the boundary between the two versions changes it too.
        assertTrue(
            conflict.copy(localJson = "ab", remoteJson = "c").fingerprint() !=
                conflict.copy(localJson = "a", remoteJson = "bc").fingerprint(),
        )
    }

    @Test
    fun `lazy list keys follow the element and not the marker id`() {
        val conflict = makePendingConflict(id = "conflict-1")

        assertEquals(conflict.listKey(), conflict.copy(id = "conflict-2").listKey())
        assertTrue(
            conflict.listKey() != conflict.copy(entityType = SyncableEntityType.TUNNEL).listKey(),
        )
    }

    @Test
    fun `a choice for a host does not apply to a tunnel with the same entity id`() = runTest {
        val hostConflict = makePendingConflict(id = "c-host", entityId = "shared-id")
        val tunnelConflict = conflictOf(
            type = SyncableEntityType.TUNNEL,
            local = makeTunnelEntry(id = "shared-id"),
            remote = makeTunnelEntry(id = "shared-id", label = "Remote"),
            id = "c-tunnel",
        )
        viewModel = buildViewModel(listOf(hostConflict, tunnelConflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry(id = "shared-id"))
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById("c-host") }

        viewModel.keepLocal(hostConflict)
        val resolved = viewModel.applyAll().resolved

        assertEquals(1, resolved)
        coVerify(exactly = 0) { pendingConflictRepository.deleteById("c-tunnel") }
        coVerify(exactly = 0) { tunnelRepository.upsertSyncEntry(any()) }
    }

    @Test
    fun `an applied choice is cleared and does not leak to a later conflict on the same element`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))

        coEvery { hostRepository.getAllSyncEntries() } returns listOf(makeHostEntry())
        coJustRun { hostRepository.upsertSyncEntry(any()) }
        coJustRun { pendingConflictRepository.deleteById(any()) }

        viewModel.keepLocal(conflict)
        assertEquals(1, viewModel.applyAll().resolved)

        assertTrue(viewModel.resolution.value.isEmpty())
        // Nothing is decided any more: a second pass touches nothing.
        assertEquals(0, viewModel.applyAll().resolved)
        coVerify(exactly = 1) { pendingConflictRepository.deleteById(any()) }
    }

    @Test
    fun `ignoring a corrupt conflict deletes it and drops its choice`() = runTest {
        val conflict = makePendingConflict()
        viewModel = buildViewModel(listOf(conflict))
        coJustRun { pendingConflictRepository.deleteById(conflict.id) }

        viewModel.keepLocal(conflict)
        viewModel.ignoreConflict(conflict)

        coVerify(exactly = 1) { pendingConflictRepository.deleteById(conflict.id) }
        assertTrue(viewModel.resolution.value.isEmpty())
    }
}
