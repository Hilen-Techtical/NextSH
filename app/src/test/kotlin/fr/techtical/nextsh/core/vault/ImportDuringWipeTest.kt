// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.ui.hosts.HostImportError
import fr.techtical.nextsh.ui.hosts.HostImportViewModel
import fr.techtical.nextsh.shared.domain.vault.VaultManager as SharedVaultManager
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A restore or an import started before the "Wipe the vault" action must not write after it: the mocks
 * here are strict, so any write, to the database, the vault or the known hosts, fails the test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImportDuringWipeTest {

    private val gate = VaultWipeGate()
    private val hostRepository = mockk<HostRepository>()
    private val vaultManager = mockk<VaultManager>()
    private val sshKeyRepository = mockk<SshKeyRepository>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a restore asked while the app is closed fails before it reads or writes anything`() = runTest {
        val importer = VaultImporter(
            vaultManager = vaultManager,
            sshKeyRepository = sshKeyRepository,
            hostRepository = hostRepository,
            tunnelRepository = mockk<TunnelRepository>(),
            backupRestoreStore = mockk(),
            knownHostsVerifier = mockk<KnownHostsVerifier>(),
            settingsDataStore = mockk<SettingsDataStore>(),
            cryptoUtils = CryptoUtils(),
            transactionRunner = mockk<TransactionRunner>(),
            wipeGate = gate,
        )

        for (state in listOf(VaultWipeState.RUNNING, VaultWipeState.FAILED, VaultWipeState.DONE)) {
            gate.set(state)

            val result = importer.import(ByteArray(64), "correct horse battery".toCharArray())

            assertTrue(result.isFailure, "$state")
            val failure = result.exceptionOrNull()
            assertTrue(failure is IOException, "$state: $failure")
            assertEquals("The vault is being wiped", failure?.message)
        }
    }

    @Test
    fun `the importer checks the gate at its start, before the writes, inside the transaction and before the secrets`() {
        val source = fr.techtical.nextsh.core.logging.blankComments(
            fr.techtical.nextsh.testutil.ProjectFiles.mainSource("core/vault/VaultImporter.kt").readText(),
        )

        assertTrue(Regex("""ensureOpen\(\)""").findAll(source).count() >= 5, "definition and four checks")
        val run = source.indexOf("transactionRunner.run {")
        val insideTransaction = source.substring(source.indexOf('{', run) + 1).trimStart()
        assertTrue(insideTransaction.startsWith("ensureOpen()"), "first thing inside the transaction")
    }

    @Test
    fun `a host import confirmed while the app is closed imports nothing and wipes the parsed secrets`() = runTest {
        val viewModel = HostImportViewModel(hostRepository, mockk<SharedVaultManager>(), sshKeyRepository, gate)
        viewModel.parse("""{ "format": "nextsh-hosts", "hosts": [ { "hostname": "wipe.example" } ] }""")
        val deadline = System.currentTimeMillis() + 5_000
        while (!viewModel.uiState.value.hasPreview && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(viewModel.uiState.value.hasPreview)
        gate.set(VaultWipeState.RUNNING)

        viewModel.import()

        // Strict repositories: a write would have thrown. The screen says it failed, with no result.
        assertEquals(HostImportError.Generic, viewModel.uiState.value.error)
        assertEquals(null, viewModel.uiState.value.result)
    }

    @Test
    fun `a gate that closes after the first entry of a host import stops the writes of the next ones`() = runTest {
        val repository = mockk<HostRepository>(relaxed = true)
        io.mockk.coEvery { repository.save(any()) } coAnswers { gate.set(VaultWipeState.RUNNING) }
        val viewModel = HostImportViewModel(
            repository, mockk<SharedVaultManager>(relaxed = true), mockk<SshKeyRepository>(relaxed = true), gate,
        )
        viewModel.parse(
            """{ "format": "nextsh-hosts", "hosts": [ { "hostname": "first.example" }, { "hostname": "second.example" } ] }""",
        )
        var deadline = System.currentTimeMillis() + 5_000
        while (!viewModel.uiState.value.hasPreview && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(viewModel.uiState.value.hasPreview)

        viewModel.import()
        deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.result == null && System.currentTimeMillis() < deadline) Thread.sleep(5)

        val result = viewModel.uiState.value.result
        assertEquals(1, result?.imported, "the entry in progress when the wipe started")
        assertEquals(1, result?.skipped, "the next one is refused by the gate")
        io.mockk.coVerify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `the gated wrappers refuse every write while the app is closed, and let reads and deletions through`() = runTest {
        val repository = mockk<HostRepository>(relaxed = true)
        val vault = mockk<SharedVaultManager>(relaxed = true)
        val keys = mockk<SshKeyRepository>(relaxed = true)
        val hosts = WipeGatedHostRepository(repository, gate)
        val gatedVault = WipeGatedVaultManager(vault, gate)
        val gatedKeys = WipeGatedSshKeyRepository(keys, gate)
        gate.set(VaultWipeState.RUNNING)

        for (write in listOf<suspend () -> Unit>(
            { hosts.save(mockk(relaxed = true)) },
            { hosts.update(mockk(relaxed = true)) },
            { gatedKeys.save(mockk(relaxed = true)) },
            { gatedKeys.update(mockk(relaxed = true)) },
            { gatedVault.storePassword("c", charArrayOf('x')) },
            { gatedVault.storePrivateKey("k", "pem") },
            { gatedVault.storeCertificate("c", "pem") },
            { gatedVault.storeKeyPassphrase("k", charArrayOf('x')) },
        )) {
            val failure = runCatching { write() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException, "$failure")
            assertEquals("The vault is being wiped", failure?.message)
        }
        // The rollback of the entry in progress still works.
        hosts.delete("h")
        gatedVault.deletePassword("c")
        io.mockk.coVerify(exactly = 1) { repository.delete("h") }
        io.mockk.coVerify(exactly = 1) { vault.deletePassword("c") }
        io.mockk.coVerify(exactly = 0) { repository.save(any()) }
        io.mockk.coVerify(exactly = 0) { vault.storePassword(any(), any()) }
    }
}
