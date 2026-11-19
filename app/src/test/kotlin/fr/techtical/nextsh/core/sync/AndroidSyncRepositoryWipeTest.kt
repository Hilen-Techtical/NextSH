// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.sync

import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.core.vault.VaultWipeState
import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.repository.OrphanTunnelRepair
import fr.techtical.nextsh.shared.core.sync.PendingConflictRepository
import fr.techtical.nextsh.shared.core.sync.SyncBundle
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SnippetRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A pull that is still in flight when the "Wipe the vault" action closes the app writes nothing: it would
 * bring back, into a database that is being erased, the hosts of the peer.
 */
class AndroidSyncRepositoryWipeTest {

    private val gate = VaultWipeGate()
    private val hostRepo = mockk<HostRepository>(relaxed = true)
    private val tunnelRepo = mockk<TunnelRepository>(relaxed = true)
    private val sshKeyRepo = mockk<SshKeyRepository>(relaxed = true)
    private val snippetRepo = mockk<SnippetRepository>(relaxed = true)
    private val themeRepo = mockk<CustomTerminalThemeRepository>(relaxed = true)
    private val conflicts = mockk<PendingConflictRepository>(relaxed = true)
    private val orphanRepair = mockk<OrphanTunnelRepair>(relaxed = true)
    private val transactions = mockk<TransactionRunner>(relaxed = true)

    private val repository = AndroidSyncRepository(
        hostRepo = hostRepo,
        tunnelRepo = tunnelRepo,
        sshKeyRepo = sshKeyRepo,
        snippetRepo = snippetRepo,
        customThemeRepo = themeRepo,
        pendingConflictRepository = conflicts,
        orphanTunnelRepair = orphanRepair,
        transactionRunner = transactions,
        wipeGate = gate,
    )

    private val emptyBundle = SyncBundle(
        hosts = emptyList(),
        tunnels = emptyList(),
        sshKeys = emptyList(),
        snippets = emptyList(),
    )

    @Test
    fun `a pull applied while the app is closed reads nothing and writes nothing`() = runTest {
        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)

            val result = repository.applyRemoteBundle(emptyBundle)

            assertEquals(0, result.cleanApplied, "$state")
            assertEquals(emptyList<Any>(), result.conflicts, "$state")
        }

        coVerify(exactly = 0) { hostRepo.getAllSyncEntries() }
        coVerify(exactly = 0) { orphanRepair.runSafely() }
    }

    @Test
    fun `a pull applied once the app is open reads the local entries again`() = runTest {
        gate.set(VaultWipeState.DONE)
        gate.acknowledgeDone()

        repository.applyRemoteBundle(emptyBundle)

        coVerify(exactly = 1) { hostRepo.getAllSyncEntries() }
    }
}
