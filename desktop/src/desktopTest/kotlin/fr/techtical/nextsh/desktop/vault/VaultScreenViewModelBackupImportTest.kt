// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.vault

import fr.techtical.nextsh.desktop.core.vault.DesktopVaultImporter
import fr.techtical.nextsh.desktop.core.vault.FakeVaultManagerMem
import fr.techtical.nextsh.desktop.core.vault.VaultImportError
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupLimits
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Vault screen state after a backup import: success is a typed [BackupImportReport], every failure is a
 * typed [VaultNotice] that never carries the importer's text (it could quote the file).
 */
class VaultScreenViewModelBackupImportTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempFiles.forEach { it.delete() }
    }

    private class TestAppScope(override val coroutineScope: CoroutineScope) : AppScope {
        override fun onDestroy() = Unit
    }

    private val importer = mockk<DesktopVaultImporter>()

    private fun viewModel(): VaultScreenViewModel = viewModelWith()

    private fun viewModelWith(
        exporter: fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter = mockk(relaxed = true),
        generate: fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase = mockk(relaxed = true),
    ): VaultScreenViewModel {
        val keyRepository = mockk<SshKeyRepository>(relaxed = true)
        every { keyRepository.observeAll() } returns flowOf(emptyList())
        val hostRepository = mockk<HostRepository>()
        every { hostRepository.observeAll() } returns flowOf(emptyList())
        return VaultScreenViewModel(
            appScope = TestAppScope(scope),
            keyRepository = keyRepository,
            hostRepository = hostRepository,
            vaultManager = FakeVaultManagerMem(),
            generate = generate,
            exporter = exporter,
            importer = importer,
        )
    }

    private fun backupFile(): File = File.createTempFile("nextsh-test", ".nextsh").also {
        it.writeBytes(byteArrayOf(1, 2, 3))
        tempFiles += it
    }

    private fun VaultScreenViewModel.importAndAwait(file: File, passphrase: CharArray): VaultOperationState {
        importBackup(file, passphrase)
        return runBlocking { withTimeout(10_000) { status.first { !it.isBusy } } }
    }

    @Test
    fun `a successful import publishes the typed report and no notice`() {
        val report = BackupImportReport(hosts = 2, snippets = 3, knownHostsAdded = 1)
        coEvery { importer.import(any(), any()) } returns Result.success(report)

        val state = viewModel().importAndAwait(backupFile(), "pass".toCharArray())

        assertEquals(report, state.importReport)
        assertNull(state.notice)
    }

    @Test
    fun `each import failure becomes its own typed notice`() {
        val expected = listOf(
            VaultImportError.WrongPassphrase to VaultNotice.BackupWrongPassphrase,
            VaultImportError.Malformed to VaultNotice.BackupMalformed,
            VaultImportError.FileTooLarge to VaultNotice.BackupFileTooLarge,
            VaultImportError.UnsupportedVersion(7) to VaultNotice.BackupUnsupportedVersion(7),
        )
        for ((error, notice) in expected) {
            coEvery { importer.import(any(), any()) } returns Result.failure(error)

            val state = viewModel().importAndAwait(backupFile(), "pass".toCharArray())

            assertEquals(notice, state.notice, "failure ${error::class.simpleName}")
            assertTrue(state.notice!!.isError)
            assertNull(state.importReport)
        }
    }

    @Test
    fun `an unexpected failure gives the generic notice and never exposes its message`() {
        val secretText = "host.internal.example:22 /home/someone/.ssh/id_ed25519"
        coEvery { importer.import(any(), any()) } returns Result.failure(IllegalStateException(secretText))

        val state = viewModel().importAndAwait(backupFile(), "pass".toCharArray())

        assertEquals(VaultNotice.BackupImportFailed, state.notice)
        assertFalse(state.toString().contains(secretText))
    }

    @Test
    fun `an exception thrown while importing gives the generic notice`() {
        coEvery { importer.import(any(), any()) } throws IllegalStateException("boom")

        val state = viewModel().importAndAwait(backupFile(), "pass".toCharArray())

        assertEquals(VaultNotice.BackupImportFailed, state.notice)
    }

    @Test
    fun `a file above the size limit is refused before it is read`() {
        val tooLarge = object : File("never-read.nextsh") {
            override fun length(): Long = BackupLimits.MAX_FILE_BYTES + 1
        }

        val state = viewModel().importAndAwait(tooLarge, "pass".toCharArray())

        assertEquals(VaultNotice.BackupFileTooLarge, state.notice)
        coVerify(exactly = 0) { importer.import(any(), any()) }
    }

    @Test
    fun `the passphrase is wiped after the import`() {
        coEvery { importer.import(any(), any()) } returns Result.failure(VaultImportError.WrongPassphrase)
        val passphrase = "secret-pass".toCharArray()

        viewModel().importAndAwait(backupFile(), passphrase)

        assertTrue(passphrase.all { it == Char(0) })
    }

    // ── Bounded read ───────────────────────────────────────────────────────

    @Test
    fun `readAtMost reads a file within the bound and refuses one above it`() {
        val file = backupFile()
        file.writeBytes(ByteArray(100_000) { 7 })

        assertEquals(100_000, readAtMost(file, 100_000)?.size)
        assertNull(readAtMost(file, 99_999))
    }

    @Test
    fun `readAtMost bounds the read itself, not the size the file claims`() {
        val file = backupFile()
        file.writeBytes(ByteArray(200_000))
        val claimsZero = object : File(file.path) {
            override fun length(): Long = 0L
        }

        assertNull(readAtMost(claimsZero, 1_000))
    }

    // ── Export ─────────────────────────────────────────────────────────────

    private fun VaultScreenViewModel.exportAndAwait(destination: File): VaultOperationState {
        exportBackup(destination, "pass-phrase".toCharArray())
        return runBlocking { withTimeout(10_000) { status.first { !it.isBusy } } }
    }

    @Test
    fun `a failing export gives the typed export failure and no message`() {
        val exporter = mockk<fr.techtical.nextsh.desktop.core.vault.DesktopVaultExporter>()
        coEvery { exporter.exportWithSummary(any()) } throws IllegalStateException("/secret/path leaked")

        val state = viewModelWith(exporter = exporter).exportAndAwait(File("unused.nextsh"))

        assertEquals(VaultNotice.BackupExportFailed, state.notice)
        assertFalse(state.toString().contains("leaked"))
    }

    @Test
    fun `an export with elements that cannot be restored keeps its warning on screen`() {
        assertTrue(VaultNotice.BackupExported("a.nextsh", 4, notRestorable = 0).autoDismiss)
        assertFalse(VaultNotice.BackupExported("a.nextsh", 4, notRestorable = 2).autoDismiss)
        assertFalse(VaultNotice.BackupExportFailed.autoDismiss)
    }

    // ── Key notices ────────────────────────────────────────────────────────

    @Test
    fun `an empty label is refused with the typed notice`() {
        val vm = viewModel()

        vm.importKey(File("k"), "  ", null)

        assertEquals(VaultNotice.LabelRequired, vm.status.value.notice)
        assertTrue(VaultNotice.LabelRequired.isError)
    }

    @Test
    fun `a failing key generation gives the typed notice and no message`() {
        val generate = mockk<fr.techtical.nextsh.shared.domain.usecase.GenerateSshKeyUseCase>()
        coEvery { generate(any(), any()) } returns Result.failure(IllegalStateException("internal detail"))
        val vm = viewModelWith(generate = generate)

        vm.generateKey("my key", fr.techtical.nextsh.shared.domain.model.SshKeyType.ED25519)
        // The generation runs in the app scope: wait for its end, as the import and export tests do.
        val done = runBlocking { withTimeout(10_000) { vm.status.first { !it.isBusy } } }

        assertEquals(VaultNotice.KeyGenerationFailed, done.notice)
        assertFalse(done.toString().contains("internal detail"))
    }

    private fun keyImportNotice(loaderError: Throwable?, passphrase: CharArray?, readable: Boolean = true): VaultNotice? {
        val file = backupFile()
        file.writeText("not a key")
        io.mockk.mockkObject(fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader)
        try {
            if (loaderError != null) {
                every {
                    fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader.loadKeyProviderFromString(any(), any())
                } throws loaderError
            }
            val vm = viewModel()
            vm.importKey(if (readable) file else File("does-not-exist.key"), "label", passphrase)
            return runBlocking { withTimeout(10_000) { vm.status.first { !it.isBusy } } }.notice
        } finally {
            io.mockk.unmockkObject(fr.techtical.nextsh.desktop.core.ssh.DesktopSshKeyLoader)
        }
    }

    @Test
    fun `an encrypted key without passphrase asks for the passphrase`() {
        assertEquals(
            VaultNotice.KeyPassphraseRequired,
            keyImportNotice(javax.crypto.BadPaddingException("x"), passphrase = null),
        )
    }

    @Test
    fun `an encrypted key with a wrong passphrase gives the wrong passphrase notice`() {
        assertEquals(
            VaultNotice.KeyPassphraseWrong,
            keyImportNotice(javax.crypto.BadPaddingException("x"), passphrase = "nope".toCharArray()),
        )
    }

    @Test
    fun `an unreadable key gives the unreadable notice and never the loader text`() {
        val notice = keyImportNotice(IllegalArgumentException("secret key material"), passphrase = null)

        assertEquals(VaultNotice.KeyUnreadable, notice)
        assertFalse(notice.toString().contains("secret key material"))
    }

    @Test
    fun `a key file that cannot be read gives the import failure notice`() {
        assertEquals(VaultNotice.KeyImportFailed, keyImportNotice(null, passphrase = null, readable = false))
    }

    @Test
    fun `the report stays until dismissed while a notice is cleared on its own`() {
        coEvery { importer.import(any(), any()) } returns Result.success(BackupImportReport(hosts = 1))
        val vm = viewModel()
        vm.importAndAwait(backupFile(), "pass".toCharArray())

        vm.clearStatus()
        assertEquals(BackupImportReport(hosts = 1), vm.status.value.importReport)

        vm.dismissImportReport()
        assertNull(vm.status.value.importReport)
    }
}
