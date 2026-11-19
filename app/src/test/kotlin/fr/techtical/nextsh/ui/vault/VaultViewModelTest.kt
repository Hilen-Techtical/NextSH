// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.ContentResolver
import android.content.Context
import android.content.res.Resources
import android.net.Uri
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.crypto.BiometricKeyAlias
import fr.techtical.nextsh.core.crypto.StrongBiometricStatus
import fr.techtical.nextsh.core.lock.AppLock
import fr.techtical.nextsh.core.ssh.SshKeyManager
import fr.techtical.nextsh.core.vault.DocumentDeleter
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultBackupReadException
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupReportRow
import fr.techtical.nextsh.core.vault.VaultExport
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException as SharedVaultAuthExpiredException
import fr.techtical.nextsh.data.repository.KeyUsage
import fr.techtical.nextsh.data.repository.KeyUsageReader
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ExportVaultUseCase
import fr.techtical.nextsh.domain.usecase.GenerateSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportSshKeyUseCase
import fr.techtical.nextsh.domain.usecase.ImportVaultUseCase
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import fr.techtical.nextsh.testutil.awaitVerified
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class VaultViewModelTest {

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyManager: SshKeyManager

    @MockK
    private lateinit var keystoreManager: fr.techtical.nextsh.core.crypto.KeystoreManager

    @MockK
    private lateinit var biometricHelper: BiometricHelper

    @MockK
    private lateinit var importSshKey: ImportSshKeyUseCase

    @MockK
    private lateinit var generateSshKey: GenerateSshKeyUseCase

    @MockK
    private lateinit var exportVaultUseCase: ExportVaultUseCase

    @MockK
    private lateinit var importVaultUseCase: ImportVaultUseCase

    @MockK
    private lateinit var documentDeleter: DocumentDeleter

    @MockK
    private lateinit var keyUsageReader: KeyUsageReader

    /** A real lock, open as it is when the Vault screen is in use. */
    private val appLock = AppLock { 0L }.apply { unlock() }

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: VaultViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeAll() } returns flowOf(emptyList())

        viewModel = newViewModel()
    }

    /** Every ViewModel of the test: the count follows the hosts for ever, [tearDown] ends it. */
    private val createdViewModels = mutableListOf<VaultViewModel>()

    private fun newViewModel() = VaultViewModel(
        sshKeyRepository = sshKeyRepository,
        hostRepository = hostRepository,
        vaultManager = vaultManager,
        sshKeyManager = sshKeyManager,
        keystoreManager = keystoreManager,
        biometricHelper = biometricHelper,
        importSshKey = importSshKey,
        generateSshKey = generateSshKey,
        exportVaultUseCase = exportVaultUseCase,
        importVaultUseCase = importVaultUseCase,
        documentDeleter = documentDeleter,
        keyUsageReader = keyUsageReader,
        appLock = appLock,
    ).also { createdViewModels += it }

    @AfterEach
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state - isLoading is true before data loads`() {
        // After construction with flowOf(emptyList()), the UnconfinedTestDispatcher
        // processes the flow immediately. We verify the keys list is initialized.
        val state = viewModel.uiState.value
        assertNotNull(state)
        // isLoading starts as true in VaultUiState default
        assertTrue(state.sshKeys.isEmpty())
    }

    // ── stored password count: the passwords the hosts use, asked one by one ─
    //
    // The count follows the host list, which never ends: each test waits, with a bound, for the state
    // it expects. A count that is 0 is proved by a witness host counted in the same list or in the
    // next one, never by waiting for a job to end.

    private fun host(
        id: String,
        authType: AuthType = AuthType.PASSWORD,
        credentialId: String = "cred-$id",
    ) = Host(
        id = id,
        label = "host $id",
        hostname = "$id.example.com",
        username = "root",
        authType = authType,
        credentialId = credentialId,
    )

    /** The host list the count tests feed to the ViewModel, and change after it was opened. */
    private val hostList = MutableStateFlow<List<Host>>(emptyList())

    /** Waits, with a bound, until the count shown is [expected]. */
    private fun awaitCount(vm: VaultViewModel, expected: Int) {
        awaitVerified { assertEquals(expected, vm.uiState.value.storedCredentialCount) }
    }

    /**
     * A new ViewModel that counts over [hostList], starting at [hosts], with [vaultHasPassword] as
     * the answer of the vault for each credential id it is asked about.
     *
     * The ViewModel of setUp has taken its (empty, finite) host flow before the mocks are stubbed
     * again: it asks the mocks for nothing else, so nothing races the stubbing.
     */
    private fun viewModelCountingOver(hosts: List<Host>, vaultHasPassword: (String) -> Boolean): VaultViewModel {
        awaitVerified { verify(atLeast = 1) { hostRepository.observeAll() } }
        hostList.value = hosts
        every { hostRepository.observeAll() } returns hostList
        every { vaultManager.hasPassword(any()) } answers { vaultHasPassword(firstArg()) }
        return newViewModel()
    }

    private fun viewModelCountingOver(hosts: List<Host>, storedPasswords: Set<String>): VaultViewModel =
        viewModelCountingOver(hosts) { it in storedPasswords }

    @Test
    fun `with no host the vault is asked about nothing, and a host that comes is counted`() {
        val vm = viewModelCountingOver(hosts = emptyList(), storedPasswords = setOf("cred-a", "lan_sync_secret_device-1"))

        // The witness: once its password is counted, the empty list was handled before it.
        hostList.value = listOf(host("a"))

        awaitCount(vm, 1)
        verify(exactly = 1) { vaultManager.hasPassword(any()) }
        verify(exactly = 1) { vaultManager.hasPassword("cred-a") }
    }

    @Test
    fun `the count holds the password hosts that have a stored password and skips the others`() {
        val vm = viewModelCountingOver(
            hosts = listOf(host("a"), host("b"), host("c")),
            storedPasswords = setOf("cred-a", "cred-c"),
        )

        awaitCount(vm, 2)
        assertFalse(vm.uiState.value.isLoading)
        verify(exactly = 1) { vaultManager.hasPassword("cred-b") }
    }

    @Test
    fun `hosts that do not sign in with a password are not counted, nor asked about`() {
        val others = listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2, AuthType.BIOMETRIC_KEY)
            .mapIndexed { index, authType -> host("other-$index", authType, credentialId = "shared-with-a-key-$index") }
        val vm = viewModelCountingOver(
            hosts = listOf(host("a")) + others,
            // Even a password stored under the id of a key host (the Vault screen allows it) is no
            // host password: only the ids of the PASSWORD hosts are asked.
            storedPasswords = setOf("cred-a") + others.map { it.credentialId },
        )

        awaitCount(vm, 1)
        verify(exactly = 1) { vaultManager.hasPassword(any()) }
        verify(exactly = 1) { vaultManager.hasPassword("cred-a") }
    }

    @Test
    fun `two hosts that share a credential id count once`() {
        val vm = viewModelCountingOver(
            hosts = listOf(host("a", credentialId = "shared"), host("b", credentialId = "shared"), host("c")),
            storedPasswords = setOf("shared", "cred-c"),
        )

        awaitCount(vm, 2)
        verify(exactly = 1) { vaultManager.hasPassword("shared") }
    }

    @Test
    fun `the secret of an enrolled device is stored as a password but does not count`() {
        val lanSecret = "${LAN_SYNC_SECRET_KEY_PREFIX}device-1"
        val vm = viewModelCountingOver(
            hosts = listOf(host("a")),
            storedPasswords = setOf("cred-a", lanSecret, "${LAN_SYNC_SECRET_KEY_PREFIX}device-2"),
        )

        awaitCount(vm, 1)
        verify(exactly = 0) { vaultManager.hasPassword(lanSecret) }
    }

    @Test
    fun `the count reads the hosts through observeAll only, never the sync entries, so a deleted host is not counted`() {
        // observeAll() hands the live hosts only (the DAO filters the tombstones): the password of a
        // deleted host, which may well still sit in the vault, is neither asked about nor counted.
        val vm = viewModelCountingOver(
            hosts = listOf(host("live")),
            storedPasswords = setOf("cred-live", "cred-deleted"),
        )

        awaitCount(vm, 1)
        verify(exactly = 0) { vaultManager.hasPassword("cred-deleted") }
        coVerify(exactly = 0) { hostRepository.getAllSyncEntries() }
    }

    @Test
    fun `the count never lists nor reads the content of the vault`() {
        val vm = viewModelCountingOver(
            hosts = listOf(host("a"), host("b")),
            storedPasswords = setOf("cred-a", "cred-b"),
        )

        awaitCount(vm, 2)
        // listStoredCredentialIds reads `all`, which decrypts every secret of the vault into a String.
        verify(exactly = 0) { vaultManager.listStoredCredentialIds() }
        verify(exactly = 0) { vaultManager.listStoredKeyIds() }
        verify(exactly = 0) { vaultManager.listStoredCertificateIds() }
        verify(exactly = 0) { vaultManager.listStoredKeyPassphraseIds() }
        verify(exactly = 0) { vaultManager.isInitialized() }
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.getPrivateKey(any()) }
    }

    @Test
    fun `a host added after the screen was opened makes the count go up, and a host removed makes it go down`() {
        val vm = viewModelCountingOver(
            hosts = listOf(host("a")),
            storedPasswords = setOf("cred-a", "cred-b", "cred-c"),
        )
        awaitCount(vm, 1)

        // A host created, or a backup restored: the list changes while the ViewModel is alive.
        hostList.value = listOf(host("a"), host("b"))
        awaitCount(vm, 2)

        // A host that has no password in the vault does not move the count.
        hostList.value = listOf(host("a"), host("b"), host("no-password"))
        hostList.value = listOf(host("a"), host("b"), host("no-password"), host("c"))
        awaitCount(vm, 3)

        // A host deleted.
        hostList.value = listOf(host("b"), host("c"))
        awaitCount(vm, 2)
    }

    @Test
    fun `a vault that fails during the count shows 0 and logs the exception class only`() {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            val vm = viewModelCountingOver(
                hosts = listOf(host("a", credentialId = "my-secret-label")),
                vaultHasPassword = { throw IllegalStateException(LEAK_MARKER) },
            )

            awaitVerified { assertTrue(tree.entries.any { it.message.contains("IllegalStateException") }) }

            assertEquals(0, vm.uiState.value.storedCredentialCount)
            assertFalse(vm.uiState.value.isLoading)
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertFalse(entry.message.contains("my-secret-label"), "log leaks a credential id")
                assertNull(entry.throwable, "no throwable (its message and trace) may be logged")
            }
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `a failed count does not lock the app and the next change of the hosts counts again`() {
        var vaultReadable = false
        val vm = viewModelCountingOver(listOf(host("a"))) {
            if (!vaultReadable) throw IllegalStateException(LEAK_MARKER)
            true
        }
        awaitVerified { verify(exactly = 1) { vaultManager.hasPassword("cred-a") } }

        vaultReadable = true
        hostList.value = listOf(host("a"), host("b"))

        awaitCount(vm, 2)
        // Only an expired vault window locks the app.
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `an expired vault window during the count locks the app and shows 0`() {
        assertFalse(appLock.isLocked())

        val vm = viewModelCountingOver(listOf(host("a"))) { throw VaultAuthExpiredException() }

        awaitVerified { assertTrue(appLock.isLocked()) }
        assertEquals(0, vm.uiState.value.storedCredentialCount)
        assertFalse(vm.uiState.value.isLoading)
    }

    @Test
    fun `the expired window of the shared interface, behind a wrapper, locks the app too`() {
        val vm = viewModelCountingOver(listOf(host("a"))) {
            throw IllegalStateException("wrapper", SharedVaultAuthExpiredException())
        }

        awaitVerified { assertTrue(appLock.isLocked()) }
        assertEquals(0, vm.uiState.value.storedCredentialCount)
    }

    @Test
    fun `a host list that cannot be read shows 0, logs the class only and does not lock the app`() {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            awaitVerified { verify(atLeast = 1) { hostRepository.observeAll() } }
            every { hostRepository.observeAll() } returns flow<List<Host>> { throw IllegalStateException(LEAK_MARKER) }

            val vm = newViewModel()

            awaitVerified { assertTrue(tree.entries.any { it.message.contains("IllegalStateException") }) }
            assertEquals(0, vm.uiState.value.storedCredentialCount)
            assertFalse(vm.uiState.value.isLoading)
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertNull(entry.throwable, "no throwable (its message and trace) may be logged")
            }
            verify(exactly = 0) { vaultManager.hasPassword(any()) }
            assertFalse(appLock.isLocked())
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `leaving the screen during the count is no failure and logs nothing`() {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val vm = viewModelCountingOver(listOf(host("a"))) {
                entered.countDown()
                release.await(2, TimeUnit.SECONDS)
                false
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS), "the count did not start")

            // The screen is left (the ViewModel is cleared) while the vault is being read.
            vm.viewModelScope.cancel()
            release.countDown()
            runBlocking {
                withTimeout(2_000) { vm.viewModelScope.coroutineContext[Job]!!.children.toList().joinAll() }
            }

            assertTrue(tree.entries.none { it.message.contains("Vault access failed") }, "a cancellation was logged as a failure")
            assertFalse(appLock.isLocked())
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `generateKey with blank label sets error and isGenerating stays false`() = runTest {
        val blankLabel = "   "

        viewModel.generateKey(label = blankLabel, type = fr.techtical.nextsh.domain.model.SshKeyType.ED25519)

        val state = viewModel.uiState.value
        assertFalse(state.isGenerating)
        // A fixed sentence kept as a resource, in the language of the phone when it is shown.
        assertEquals(MessageRes(R.string.vault_biometric_name_empty), state.errorRes)
        assertNull(state.error)
    }

    @Test
    fun `deleteKey removes the private key, its passphrase and its certificate but never the password`() = runTest {
        val testKey = SshKey(
            id = "key-1",
            label = "My Key",
            keyType = fr.techtical.nextsh.domain.model.SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA...",
            isBiometric = false,
            keystoreAlias = null,
        )

        coEvery { sshKeyRepository.getById(testKey.id) } returns testKey
        every { vaultManager.deletePrivateKey(testKey.id) } returns Unit
        every { vaultManager.deleteKeyPassphrase(testKey.id) } returns Unit
        every { vaultManager.deleteCertificate(testKey.id) } returns Unit
        coEvery { sshKeyRepository.delete(testKey.id) } returns Unit

        viewModel.deleteKey(testKey.id)

        // deleteKey runs in a coroutine; poll until the verifications and the
        // resulting state update land, instead of racing them. The assertions
        // are unchanged, only the timing race is removed.
        awaitVerified {
            verify(exactly = 1) { vaultManager.deletePrivateKey(testKey.id) }
            verify(exactly = 1) { vaultManager.deleteKeyPassphrase(testKey.id) }
            verify(exactly = 1) { vaultManager.deleteCertificate(testKey.id) }
            // Le mot de passe sous le même id peut appartenir à un hôte PASSWORD : intouchable.
            verify(exactly = 0) { vaultManager.deletePassword(any()) }
            coVerify(exactly = 1) { sshKeyRepository.delete(testKey.id) }
            assertEquals(MessageRes(R.string.vault_key_deleted), viewModel.uiState.value.successRes)
        }
    }

    // ── importVault: localized success messages, plurals included ───────────

    private fun contextFor(uri: Uri, bytes: ByteArray = byteArrayOf(1, 2, 3)): Context {
        val resolver = mockk<ContentResolver>()
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream(bytes)
        val resources = mockk<Resources>()
        return mockk<Context> {
            // The ViewModel keeps only the application context, never the Activity.
            every { applicationContext } returns this@mockk
            every { contentResolver } returns resolver
            every { this@mockk.resources } returns resources
            every { getString(R.string.error_backup_wrong_passphrase) } returns "wrong passphrase text"
            every { getString(R.string.error_backup_malformed) } returns "malformed text"
            every { getString(R.string.error_backup_unsupported_version) } returns "unsupported version text"
            every { getString(R.string.vault_import_failed) } returns "import failed text"
            every { getString(R.string.vault_error_auth_expired) } returns "auth expired text"
            every { getString(R.string.vault_import_file_unreadable) } returns "file unreadable text"
            every { getString(R.string.vault_import_file_too_large) } returns "file too large text"
        }
    }

    @Test
    fun `importVault success keeps the report for the summary dialog and shows no message`() = runTest {
        val uri = mockk<Uri>()
        val report = BackupImportReport(hosts = 6, tunnels = 2, tunnelsSkipped = 1)
        coEvery { importVaultUseCase(any(), any()) } returns Result.success(report)

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified {
            assertEquals(report, viewModel.uiState.value.importReport)
            assertFalse(viewModel.uiState.value.isImporting)
        }
        assertFalse(viewModel.uiState.value.hasSuccess)
        assertNull(viewModel.uiState.value.errorText(contextFor(uri)))
    }

    @Test
    fun `an empty report still opens the summary`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns Result.success(BackupImportReport())

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified { assertEquals(BackupImportReport(), viewModel.uiState.value.importReport) }
    }

    @Test
    fun `dismissing the summary clears it, and a new import starts without the old one`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns Result.success(BackupImportReport(hosts = 1))
        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))
        awaitVerified { assertNotNull(viewModel.uiState.value.importReport) }

        viewModel.dismissImportReport()
        assertNull(viewModel.uiState.value.importReport)

        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED))
        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))
        awaitVerified { assertEquals("malformed text", viewModel.uiState.value.error) }
        assertNull(viewModel.uiState.value.importReport)
    }

    @Test
    fun `importVault of a file over the limit says so and never calls the use case`() = runTest {
        val uri = mockk<Uri>()
        val context = contextFor(uri)
        // Endless stream: the read must stop by itself once the limit is passed.
        every { context.contentResolver.openInputStream(uri) } returns object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }

        viewModel.importVault("a passphrase".toCharArray(), uri, context)

        awaitVerified {
            assertEquals("file too large text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isImporting)
        }
        coVerify(exactly = 0) { importVaultUseCase(any(), any()) }
    }

    @Test
    fun `importVault wipes the passphrase whatever the outcome`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns Result.success(BackupImportReport(hosts = 1))
        val passphrase = "a passphrase".toCharArray()

        viewModel.importVault(passphrase, uri, contextFor(uri))

        awaitVerified { assertTrue(passphrase.all { it == '\u0000' }) }
    }

    @Test
    fun `importVault wipes the passphrase when the use case fails`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.WRONG_PASSPHRASE))
        val passphrase = "a passphrase".toCharArray()

        viewModel.importVault(passphrase, uri, contextFor(uri))

        awaitVerified {
            assertEquals("wrong passphrase text", viewModel.uiState.value.error)
            assertTrue(passphrase.all { it == '\u0000' })
        }
    }

    @Test
    fun `importVault wipes the passphrase when the use case throws`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } throws IllegalStateException(LEAK_MARKER)
        val passphrase = "a passphrase".toCharArray()

        viewModel.importVault(passphrase, uri, contextFor(uri))

        awaitVerified {
            assertEquals("import failed text", viewModel.uiState.value.error)
            assertTrue(passphrase.all { it == '\u0000' })
        }
    }

    @Test
    fun `importVault wipes the passphrase when the file is unreadable or too large`() = runTest {
        val uri = mockk<Uri>()
        val context = contextFor(uri)
        every { context.contentResolver.openInputStream(uri) } returns null
        val passphrase = "a passphrase".toCharArray()

        viewModel.importVault(passphrase, uri, context)

        awaitVerified {
            assertEquals("file unreadable text", viewModel.uiState.value.error)
            assertTrue(passphrase.all { it == '\u0000' })
        }
    }

    @Test
    fun `every row and every warning of the report has a text`() {
        BackupReportRow.entries.forEach { importRowLabelRes(it) }
        // Compiles only while the mapping covers every row; the warnings are checked in ImportSummaryTextTest.
        assertEquals(BackupReportRow.entries.size, BackupReportRow.entries.map { importRowLabelRes(it) }.toSet().size)
    }

    // ── importVault: an unreadable backup shows a localized reason, never the exception text ──

    @Test
    fun `importVault with a wrong passphrase shows the localized message`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.WRONG_PASSPHRASE))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified {
            assertEquals("wrong passphrase text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isImporting)
        }
    }

    @Test
    fun `importVault with a malformed backup shows the localized message`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified { assertEquals("malformed text", viewModel.uiState.value.error) }
    }

    @Test
    fun `importVault shows the localized message when the use case throws the typed failure`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } throws
            VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED)

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified { assertEquals("malformed text", viewModel.uiState.value.error) }
    }

    @Test
    fun `importVault never displays the message of a typed read failure`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified {
            val error = viewModel.uiState.value.error
            assertNotNull(error)
            assertFalse(error!!.contains("MALFORMED"))
        }
    }

    @Test
    fun `importVault shows a fixed sentence for any other failure, never the message of the exception`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified {
            assertEquals("import failed text", viewModel.uiState.value.error)
        }
        assertFalse(viewModel.uiState.value.error!!.contains(LEAK_MARKER))
    }

    @Test
    fun `importVault tells that the vault window is over, whatever wraps the failure`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(IllegalStateException(LEAK_MARKER, VaultAuthExpiredException()))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified { assertEquals("auth expired text", viewModel.uiState.value.error) }
    }

    @Test
    fun `importVault of a file that cannot be opened says so with a fixed sentence`() = runTest {
        val uri = mockk<Uri>()
        val context = contextFor(uri)
        every { context.contentResolver.openInputStream(uri) } returns null

        viewModel.importVault("a passphrase".toCharArray(), uri, context)

        awaitVerified { assertEquals("file unreadable text", viewModel.uiState.value.error) }
        coVerify(exactly = 0) { importVaultUseCase(any(), any()) }
    }

    @Test
    fun `importVault of a backup from an unknown format version shows its own localized message`() = runTest {
        val uri = mockk<Uri>()
        coEvery { importVaultUseCase(any(), any()) } returns
            Result.failure(VaultBackupReadException(VaultBackupReadException.Reason.UNSUPPORTED_VERSION))

        viewModel.importVault("a passphrase".toCharArray(), uri, contextFor(uri))

        awaitVerified { assertEquals("unsupported version text", viewModel.uiState.value.error) }
    }

    // ── deleteKey: a biometric key only ever destroys its own Keystore entry ──

    private val bioKeyId = "0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
    private val otherKeyAlias = BiometricKeyAlias.forKeyId("11111111-2222-4333-8444-555555555555")

    private fun biometricKey(storedAlias: String?) = SshKey(
        id = bioKeyId,
        label = "Bio",
        keyType = fr.techtical.nextsh.domain.model.SshKeyType.ECDSA_256,
        publicKey = "ecdsa-sha2-nistp256 AAAA",
        isBiometric = true,
        keystoreAlias = storedAlias,
    )

    @Test
    fun `deleteKey of a biometric key ignores a stored alias of another key`() = runTest {
        // A legacy row: id A carrying a valid-shape alias that belongs to key B.
        coEvery { sshKeyRepository.getById(bioKeyId) } returns biometricKey(storedAlias = otherKeyAlias)
        every { keystoreManager.deleteBiometricKey(any()) } returns Unit
        coEvery { sshKeyRepository.delete(bioKeyId) } returns Unit

        viewModel.deleteKey(bioKeyId)

        awaitVerified {
            verify(exactly = 1) { keystoreManager.deleteBiometricKey(BiometricKeyAlias.forKeyId(bioKeyId)) }
            verify(exactly = 0) { keystoreManager.deleteBiometricKey(otherKeyAlias) }
            verify(exactly = 0) { vaultManager.deletePrivateKey(any()) }
            coVerify(exactly = 1) { sshKeyRepository.delete(bioKeyId) }
        }
    }

    @Test
    fun `deleteKey of a biometric key never passes the stored alias on, whatever it is`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns biometricKey(storedAlias = "nextsh_vault_auth_key")
        every { keystoreManager.deleteBiometricKey(any()) } returns Unit
        coEvery { sshKeyRepository.delete(bioKeyId) } returns Unit

        viewModel.deleteKey(bioKeyId)

        awaitVerified {
            verify(exactly = 1) { keystoreManager.deleteBiometricKey(BiometricKeyAlias.forKeyId(bioKeyId)) }
            verify(exactly = 0) { keystoreManager.deleteBiometricKey("nextsh_vault_auth_key") }
        }
    }

    @Test
    fun `deleteKey of a biometric key with no stored alias still deletes its own Keystore entry`() = runTest {
        coEvery { sshKeyRepository.getById(bioKeyId) } returns biometricKey(storedAlias = null)
        every { keystoreManager.deleteBiometricKey(any()) } returns Unit
        coEvery { sshKeyRepository.delete(bioKeyId) } returns Unit

        viewModel.deleteKey(bioKeyId)

        awaitVerified {
            verify(exactly = 1) { keystoreManager.deleteBiometricKey(BiometricKeyAlias.forKeyId(bioKeyId)) }
            coVerify(exactly = 1) { sshKeyRepository.delete(bioKeyId) }
        }
    }

    // ── export: passphrase first, then location ─────────────────────────────
    //
    // CreateDocument creates the (empty) file as soon as the location is picked, so the backup is
    // encrypted and held in memory BEFORE the picker opens, and a created document is removed when
    // the write cannot complete.

    private val encryptedBackup = byteArrayOf(11, 22, 33, 44, 55)

    private fun exportContext(resolver: ContentResolver? = null): Context = mockk {
        // The ViewModel keeps only the application context, never the Activity.
        every { applicationContext } returns this@mockk
        if (resolver != null) every { contentResolver } returns resolver
        every { getString(R.string.vault_export_success) } returns "export success text"
        every { getString(R.string.vault_export_failed) } returns "export failed text"
        every { getString(R.string.vault_export_interrupted) } returns "export interrupted text"
    }

    private fun resolverWriting(uri: Uri, out: OutputStream?): ContentResolver =
        mockk { every { openOutputStream(uri) } returns out }

    private fun verifyNothingDeleted() {
        verify(exactly = 0) { documentDeleter.delete(any()) }
        verify(exactly = 0) { documentDeleter.deleteIfEmpty(any()) }
    }

    private fun ByteArray.isWiped(): Boolean = all { it == 0.toByte() }

    private fun CharArray.isWiped(): Boolean = all { it == '\u0000' }

    /** Runs [startExport] to the point where the encrypted backup is held and the picker may open. */
    private fun prepareExport(bytes: ByteArray, context: Context, notRestorable: Int = 0) {
        coEvery { exportVaultUseCase(any()) } returns Result.success(VaultExport(bytes, notRestorable))
        viewModel.startExport("a passphrase".toCharArray(), context)
        awaitVerified { assertTrue(viewModel.uiState.value.exportReadyToSave) }
    }

    @Test
    fun `startExport shows progress while encrypting, then signals the picker and keeps the bytes`() = runTest {
        val bytes = encryptedBackup.copyOf()
        val passphrase = "a passphrase".toCharArray()
        coEvery { exportVaultUseCase(any()) } returns Result.success(VaultExport(bytes, 0))

        viewModel.startExport(passphrase, exportContext())

        // Progress state: set synchronously, before the (background) encryption finishes.
        assertTrue(viewModel.uiState.value.isExporting)
        awaitVerified {
            assertTrue(viewModel.uiState.value.exportReadyToSave)
            assertTrue(passphrase.isWiped(), "the passphrase must be wiped once the backup is prepared")
        }
        // Still exporting until the file is written, and the bytes are held, not wiped.
        assertTrue(viewModel.uiState.value.isExporting)
        assertTrue(bytes.contentEquals(encryptedBackup))
        assertEquals(null, viewModel.uiState.value.error)
        verifyNothingDeleted()
    }

    @Test
    fun `startExport failure wipes the passphrase, reports the localized error and creates nothing`() = runTest {
        val passphrase = "a passphrase".toCharArray()
        coEvery { exportVaultUseCase(any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))

        viewModel.startExport(passphrase, exportContext())

        awaitVerified {
            val state = viewModel.uiState.value
            assertEquals("export failed text", state.error)
            assertFalse(state.isExporting)
            assertFalse(state.exportReadyToSave)
            assertTrue(passphrase.isWiped(), "the passphrase must be wiped when the preparation fails")
        }
        assertFalse(viewModel.uiState.value.error!!.contains(LEAK_MARKER))
        verifyNothingDeleted()
    }

    @Test
    fun `startExport wipes the passphrase when the use case throws`() = runTest {
        val passphrase = "a passphrase".toCharArray()
        coEvery { exportVaultUseCase(any()) } throws IllegalStateException(LEAK_MARKER)

        viewModel.startExport(passphrase, exportContext())

        awaitVerified {
            assertEquals("export failed text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isExporting)
            assertTrue(passphrase.isWiped())
        }
    }

    @Test
    fun `startExport failure logs the exception class only, never its message`() = runTest {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            coEvery { exportVaultUseCase(any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))

            viewModel.startExport("a passphrase".toCharArray(), exportContext())

            awaitVerified { assertTrue(tree.entries.isNotEmpty()) }
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalStateException") })
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `startExport while an export is running wipes the second passphrase and does not encrypt again`() = runTest {
        prepareExport(encryptedBackup.copyOf(), exportContext())
        val second = "another passphrase".toCharArray()

        viewModel.startExport(second, exportContext())

        assertTrue(second.isWiped())
        coVerify(exactly = 1) { exportVaultUseCase(any()) }
    }

    @Test
    fun `onExportPickerLaunched consumes the ready signal but keeps the bytes for the write`() = runTest {
        val uri = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val context = exportContext(resolverWriting(uri, out))
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)

        viewModel.onExportPickerLaunched()

        assertFalse(viewModel.uiState.value.exportReadyToSave)
        assertTrue(viewModel.uiState.value.isExporting)
        assertTrue(bytes.contentEquals(encryptedBackup), "the bytes must survive the picker being opened")

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertTrue(out.toByteArray().contentEquals(encryptedBackup)) }
    }

    @Test
    fun `cancelling the picker discards and wipes the bytes, creates no file and shows no message`() = runTest {
        val bytes = encryptedBackup.copyOf()
        // No resolver stubbed: any attempt to open a stream would fail the test.
        val context = exportContext()
        prepareExport(bytes, context)
        assertFalse(bytes.isWiped(), "the bytes are held while the picker is open")

        viewModel.onExportLocationChosen(uri = null, belongsToExport = true, context = context)

        assertTrue(bytes.isWiped(), "the pending bytes must be wiped on cancel")
        val state = viewModel.uiState.value
        assertEquals(null, state.error)
        assertEquals(null, state.successMessage)
        assertFalse(state.isExporting)
        assertFalse(state.exportReadyToSave)
        verifyNothingDeleted()
    }

    @Test
    fun `after a cancel the bytes are gone, a later location is treated as interrupted`() = runTest {
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns true
        val context = exportContext()
        prepareExport(encryptedBackup.copyOf(), context)
        viewModel.onExportLocationChosen(uri = null, belongsToExport = true, context = context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertEquals("export interrupted text", viewModel.uiState.value.error) }
        verify(exactly = 1) { documentDeleter.deleteIfEmpty(uri) }
        verify(exactly = 0) { documentDeleter.delete(any()) }
    }

    @Test
    fun `a chosen location receives exactly the encrypted bytes, which are then cleared`() = runTest {
        val uri = mockk<Uri>()
        val out = ByteArrayOutputStream()
        val context = exportContext(resolverWriting(uri, out))
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified {
            assertEquals("export success text", viewModel.uiState.value.successMessage)
            assertFalse(viewModel.uiState.value.isExporting)
        }
        assertTrue(out.toByteArray().contentEquals(encryptedBackup), "the file content must be exactly the encrypted backup")
        assertTrue(bytes.isWiped(), "the bytes must be wiped once written")
        assertEquals(null, viewModel.uiState.value.error)
        verifyNothingDeleted()
    }

    @Test
    fun `an export with items that a restore would refuse warns once the file is written`() = runTest {
        val uri = mockk<Uri>()
        val context = exportContext(resolverWriting(uri, ByteArrayOutputStream()))
        prepareExport(encryptedBackup.copyOf(), context, notRestorable = 3)
        assertEquals(0, viewModel.uiState.value.exportNotRestorable, "nothing to say before the file exists")

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertEquals(3, viewModel.uiState.value.exportNotRestorable) }
        viewModel.dismissExportWarning()
        assertEquals(0, viewModel.uiState.value.exportNotRestorable)
    }

    @Test
    fun `an export with nothing to warn about shows no warning`() = runTest {
        val uri = mockk<Uri>()
        val context = exportContext(resolverWriting(uri, ByteArrayOutputStream()))
        prepareExport(encryptedBackup.copyOf(), context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertEquals("export success text", viewModel.uiState.value.successMessage) }
        assertEquals(0, viewModel.uiState.value.exportNotRestorable)
    }

    @Test
    fun `no warning is shown when the write fails`() = runTest {
        val uri = mockk<Uri>()
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException()
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException()
        }
        every { documentDeleter.delete(uri) } returns true
        val context = exportContext(resolverWriting(uri, failing))
        prepareExport(encryptedBackup.copyOf(), context, notRestorable = 2)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertEquals("export failed text", viewModel.uiState.value.error) }
        assertEquals(0, viewModel.uiState.value.exportNotRestorable)
    }

    @Test
    fun `a write failure deletes the created document, clears the bytes and reports the error`() = runTest {
        val uri = mockk<Uri>()
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException(LEAK_MARKER)
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException(LEAK_MARKER)
        }
        every { documentDeleter.delete(uri) } returns true
        val context = exportContext(resolverWriting(uri, failing))
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified {
            assertEquals("export failed text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isExporting)
        }
        assertFalse(viewModel.uiState.value.error!!.contains(LEAK_MARKER))
        assertEquals(null, viewModel.uiState.value.successMessage)
        assertTrue(bytes.isWiped(), "the bytes must be wiped after a failed write")
        // The stream was opened (the content is truncated or partial): unconditional delete.
        verify(exactly = 1) { documentDeleter.delete(uri) }
        verify(exactly = 0) { documentDeleter.deleteIfEmpty(any()) }
    }

    @Test
    fun `an output stream that cannot be opened only deletes the document if it is known to be empty`() = runTest {
        // The stream never opened, so the file was not touched by us: it may be an existing file
        // of the user. Only the size-0 rule applies, never the unconditional delete.
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns true
        val context = exportContext(resolverWriting(uri, null))
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified { assertEquals("export failed text", viewModel.uiState.value.error) }
        assertTrue(bytes.isWiped())
        verify(exactly = 1) { documentDeleter.deleteIfEmpty(uri) }
        verify(exactly = 0) { documentDeleter.delete(any()) }
    }

    @Test
    fun `an exception thrown before the stream opens only deletes the document if it is known to be empty`() = runTest {
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns false
        val resolver = mockk<ContentResolver> {
            every { openOutputStream(uri) } throws SecurityException(LEAK_MARKER)
        }
        val context = exportContext(resolver)
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified {
            assertEquals("export failed text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isExporting)
        }
        assertFalse(viewModel.uiState.value.error!!.contains(LEAK_MARKER))
        assertTrue(bytes.isWiped())
        verify(exactly = 1) { documentDeleter.deleteIfEmpty(uri) }
        verify(exactly = 0) { documentDeleter.delete(any()) }
    }

    @Test
    fun `a write failure is still reported when the opened document cannot be deleted`() = runTest {
        val uri = mockk<Uri>()
        every { documentDeleter.delete(uri) } returns false
        val failing = object : OutputStream() {
            override fun write(b: Int) = throw IOException(LEAK_MARKER)
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException(LEAK_MARKER)
        }
        val context = exportContext(resolverWriting(uri, failing))
        prepareExport(encryptedBackup.copyOf(), context)

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified {
            assertEquals("export failed text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isExporting)
        }
        verify(exactly = 1) { documentDeleter.delete(uri) }
        verify(exactly = 0) { documentDeleter.deleteIfEmpty(any()) }
    }

    @Test
    fun `a location with no pending bytes reports the interrupted export and only deletes an empty document`() = runTest {
        // A fresh ViewModel: the activity was destroyed while the picker was open, the encrypted
        // bytes died with the previous ViewModel. Nothing was written, so the unconditional delete
        // must never be used (the document could be an existing file of the user). No resolver
        // stubbed: nothing may be written either.
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns true

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = exportContext())

        awaitVerified {
            assertEquals("export interrupted text", viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isExporting)
        }
        assertEquals(null, viewModel.uiState.value.successMessage)
        verify(exactly = 1) { documentDeleter.deleteIfEmpty(uri) }
        verify(exactly = 0) { documentDeleter.delete(any()) }
    }

    @Test
    fun `the interrupted export is still reported when a non-empty document is left alone`() = runTest {
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns false

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = exportContext())

        awaitVerified { assertEquals("export interrupted text", viewModel.uiState.value.error) }
        verify(exactly = 0) { documentDeleter.delete(any()) }
    }

    @Test
    fun `a cancelled picker with no pending bytes shows no message`() = runTest {
        viewModel.onExportLocationChosen(uri = null, belongsToExport = true, context = exportContext())

        val state = viewModel.uiState.value
        assertEquals(null, state.error)
        assertEquals(null, state.successMessage)
        verifyNothingDeleted()
    }

    @Test
    fun `a picker result that does not belong to an export is ignored`() = runTest {
        val uri = mockk<Uri>()

        viewModel.onExportLocationChosen(uri, belongsToExport = false, context = exportContext())

        val state = viewModel.uiState.value
        assertEquals(null, state.error)
        assertEquals(null, state.successMessage)
        verifyNothingDeleted()
    }

    @Test
    fun `onExportPickerUnavailable discards the bytes and reports the failure`() = runTest {
        val bytes = encryptedBackup.copyOf()
        val context = exportContext()
        prepareExport(bytes, context)

        viewModel.onExportPickerUnavailable(context)

        assertTrue(bytes.isWiped())
        val state = viewModel.uiState.value
        assertEquals("export failed text", state.error)
        assertFalse(state.isExporting)
        assertFalse(state.exportReadyToSave)
        verifyNothingDeleted()
    }

    // ── export: the ViewModel disappears (activity gone for good) ───────────

    /** Clears [viewModel] the way the framework does: through a [ViewModelStore]. */
    private fun clearViewModel() {
        val cleared = viewModel
        val store = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = cleared as T
        }
        ViewModelProvider(store, factory)[VaultViewModel::class.java]
        store.clear()
    }

    @Test
    fun `clearing the ViewModel wipes the pending bytes`() = runTest {
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, exportContext())
        assertFalse(bytes.isWiped())

        clearViewModel()

        assertTrue(bytes.isWiped())
    }

    @Test
    fun `clearing the ViewModel during encryption wipes the passphrase and holds nothing`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val passphrase = "a passphrase".toCharArray()
        coEvery { exportVaultUseCase(any()) } coAnswers {
            entered.complete(Unit)
            gate.await()
            Result.success(VaultExport(encryptedBackup.copyOf(), 0))
        }
        viewModel.startExport(passphrase, exportContext())
        awaitVerified { assertTrue(entered.isCompleted, "the encryption must be running") }

        clearViewModel()

        awaitVerified { assertTrue(passphrase.isWiped(), "the passphrase must be wiped even though the work was cancelled") }
        assertFalse(viewModel.uiState.value.exportReadyToSave)
        verifyNothingDeleted()
    }

    @Test
    fun `a backup that finishes encrypting after the ViewModel was cleared is wiped, never held`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val bytes = encryptedBackup.copyOf()
        val passphrase = "a passphrase".toCharArray()
        // The encryption does not honour cancellation: its result arrives after the clear.
        coEvery { exportVaultUseCase(any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            Result.success(VaultExport(bytes, 0))
        }
        viewModel.startExport(passphrase, exportContext())
        awaitVerified { assertTrue(entered.isCompleted, "the encryption must be running") }
        clearViewModel()

        gate.complete(Unit)

        awaitVerified {
            assertTrue(bytes.isWiped(), "a backup produced after the clear must be wiped at once")
            assertTrue(passphrase.isWiped())
        }
        assertFalse(viewModel.uiState.value.exportReadyToSave)
    }

    @Test
    fun `startExport on a ViewModel that is already cleared wipes the passphrase and encrypts nothing`() = runTest {
        clearViewModel()
        val passphrase = "a passphrase".toCharArray()

        viewModel.startExport(passphrase, exportContext())

        awaitVerified { assertTrue(passphrase.isWiped(), "the passphrase must be wiped even if the work never starts") }
        coVerify(exactly = 0) { exportVaultUseCase(any()) }
    }

    @Test
    fun `a write on an already cancelled scope writes nothing, wipes the bytes and only deletes an empty document`() = runTest {
        val uri = mockk<Uri>()
        every { documentDeleter.deleteIfEmpty(uri) } returns true
        // No resolver stubbed: any attempt to open a stream would be reported as a write failure.
        val context = exportContext()
        val bytes = encryptedBackup.copyOf()
        prepareExport(bytes, context)
        viewModel.viewModelScope.cancel()

        viewModel.onExportLocationChosen(uri, belongsToExport = true, context = context)

        awaitVerified {
            assertTrue(bytes.isWiped(), "the bytes must be wiped even though the write never ran")
            verify(exactly = 1) { documentDeleter.deleteIfEmpty(uri) }
        }
        verify(exactly = 0) { documentDeleter.delete(any()) }
        assertEquals(null, viewModel.uiState.value.error, "no write was attempted, so no write failure")
    }

    // ── generateBiometricKey: a strong biometric first, localized messages ──

    private val blockedMessages = mapOf(
        R.string.vault_biometric_no_sensor to "no sensor text",
        R.string.vault_biometric_not_enrolled to "not enrolled text",
        R.string.vault_biometric_unavailable to "unavailable text",
    )

    private fun biometricContext(): Context = mockk {
        // The ViewModel keeps only the application context, never the Activity.
        every { applicationContext } returns this@mockk
        blockedMessages.forEach { (res, text) -> every { getString(res) } returns text }
        every { getString(R.string.vault_biometric_name_empty) } returns "empty name text"
        every { getString(R.string.vault_biometric_failed) } returns "failed text"
        // The label reaches the sentence trimmed, as the key is saved.
        every { getString(R.string.vault_biometric_generated, "My phone") } returns "generated My phone"
    }

    /** The phone has no strong biometric ready: nothing may reach the Keystore or the repository. */
    private fun assertBlockedWith(status: StrongBiometricStatus, expectedMessage: String) {
        every { biometricHelper.checkStrongBiometric() } returns status

        viewModel.generateBiometricKey("My phone", biometricContext())

        val state = viewModel.uiState.value
        assertEquals(expectedMessage, state.error)
        assertFalse(state.isGenerating)
        assertEquals(null, state.successMessage)
        verify(exactly = 0) { keystoreManager.generateBiometricSshKey(any()) }
        coVerify(exactly = 0) { sshKeyRepository.save(any()) }
    }

    @Test
    fun `generateBiometricKey on a phone without a biometric sensor says so and creates nothing`() = runTest {
        assertBlockedWith(StrongBiometricStatus.NO_HARDWARE, "no sensor text")
    }

    @Test
    fun `generateBiometricKey with no fingerprint enrolled says to enroll one and creates nothing`() = runTest {
        assertBlockedWith(StrongBiometricStatus.NOT_ENROLLED, "not enrolled text")
    }

    @Test
    fun `generateBiometricKey while the sensor is unavailable says to try again and creates nothing`() = runTest {
        assertBlockedWith(StrongBiometricStatus.UNAVAILABLE, "unavailable text")
    }

    @Test
    fun `generateBiometricKey with a blank label reports the localized message without asking the phone`() = runTest {
        viewModel.generateBiometricKey("   ", biometricContext())

        assertEquals("empty name text", viewModel.uiState.value.error)
        assertFalse(viewModel.uiState.value.isGenerating)
        verify(exactly = 0) { biometricHelper.checkStrongBiometric() }
        verify(exactly = 0) { keystoreManager.generateBiometricSshKey(any()) }
    }

    @Test
    fun `generateBiometricKey with a strong biometric ready saves the key and reports it localized`() = runTest {
        every { biometricHelper.checkStrongBiometric() } returns StrongBiometricStatus.AVAILABLE
        val publicKey = mockk<java.security.PublicKey>()
        every { keystoreManager.generateBiometricSshKey(any()) } returns publicKey
        every { sshKeyManager.formatKeystorePublicKeyOpenSsh(publicKey) } returns "ecdsa-sha2-nistp256 AAAA"
        val saved = slot<SshKey>()
        coEvery { sshKeyRepository.save(capture(saved)) } returns Unit

        viewModel.generateBiometricKey("  My phone  ", biometricContext())

        awaitVerified {
            val state = viewModel.uiState.value
            assertEquals("generated My phone", state.successMessage)
            assertEquals(null, state.error)
            assertFalse(state.isGenerating)
        }
        assertTrue(saved.captured.isBiometric)
        assertEquals("My phone", saved.captured.label)
        assertEquals(BiometricKeyAlias.forKeyId(saved.captured.id), saved.captured.keystoreAlias)
        verify(exactly = 1) { keystoreManager.generateBiometricSshKey(BiometricKeyAlias.forKeyId(saved.captured.id)) }
    }

    @Test
    fun `generateBiometricKey failing in the Keystore shows the localized failure, not the exception message`() = runTest {
        every { biometricHelper.checkStrongBiometric() } returns StrongBiometricStatus.AVAILABLE
        every { keystoreManager.generateBiometricSshKey(any()) } throws IllegalStateException(LEAK_MARKER)

        viewModel.generateBiometricKey("My phone", biometricContext())

        awaitVerified {
            val state = viewModel.uiState.value
            assertEquals("failed text", state.error)
            assertFalse(state.isGenerating)
        }
        assertFalse(viewModel.uiState.value.error!!.contains(LEAK_MARKER))
        coVerify(exactly = 0) { sshKeyRepository.save(any()) }
    }

    @Test
    fun `generateBiometricKey failure logs the exception class only, never its message`() = runTest {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            every { biometricHelper.checkStrongBiometric() } returns StrongBiometricStatus.AVAILABLE
            every { keystoreManager.generateBiometricSshKey(any()) } throws IllegalStateException(LEAK_MARKER)

            viewModel.generateBiometricKey("My phone", biometricContext())

            awaitVerified { assertTrue(tree.entries.isNotEmpty()) }
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalStateException") })
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `strongBiometricStatus is what the phone reports now`() {
        StrongBiometricStatus.entries.forEach { status ->
            every { biometricHelper.checkStrongBiometric() } returns status

            assertEquals(status, viewModel.strongBiometricStatus())
        }
    }

    @Test
    fun `every status that blocks a biometric key has its own sentence, an available phone has none`() {
        assertEquals(null, biometricKeyBlockedMessageRes(StrongBiometricStatus.AVAILABLE))
        val sentences = StrongBiometricStatus.entries
            .filter { it != StrongBiometricStatus.AVAILABLE }
            .map { biometricKeyBlockedMessageRes(it) }

        assertTrue(sentences.all { it != null && it != 0 })
        assertEquals(sentences.size, sentences.toSet().size, "two statuses must not say the same thing")
    }

    @Test
    fun `deleteKey surfaces the error and keeps the key row when the passphrase deletion fails`() = runTest {
        val testKey = SshKey(
            id = "key-1",
            label = "My Key",
            keyType = fr.techtical.nextsh.domain.model.SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA...",
            isBiometric = false,
            keystoreAlias = null,
        )

        coEvery { sshKeyRepository.getById(testKey.id) } returns testKey
        every { vaultManager.deletePrivateKey(testKey.id) } returns Unit
        every { vaultManager.deleteKeyPassphrase(testKey.id) } throws VaultAuthExpiredException()
        coEvery { sshKeyRepository.delete(testKey.id) } returns Unit

        viewModel.deleteKey(testKey.id)

        awaitVerified {
            // The vault window is over: retrying is useless until NextSH is reopened, so the
            // sentence says so instead of a generic failure.
            assertEquals(MessageRes(R.string.vault_error_auth_expired), viewModel.uiState.value.errorRes)
            assertNull(viewModel.uiState.value.successRes)
            assertNull(viewModel.uiState.value.successMessage)
            // La ligne de la clé reste : la suppression, idempotente, peut être relancée.
            coVerify(exactly = 0) { sshKeyRepository.delete(any()) }
            verify(exactly = 0) { vaultManager.deletePassword(any()) }
        }
    }

    @Test
    fun `deleteKey that fails shows a fixed sentence and logs the class only, never the message`() = runTest {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            coEvery { sshKeyRepository.getById("key-1") } returns plainKey()
            every { vaultManager.deletePrivateKey("key-1") } throws IllegalStateException(LEAK_MARKER)

            viewModel.deleteKey("key-1")

            awaitVerified { assertEquals(MessageRes(R.string.vault_key_delete_failed), viewModel.uiState.value.errorRes) }
            assertNull(viewModel.uiState.value.error)
            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalStateException") })
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    // ── key deletion: warn first, then let the user decide ──────────────────

    private fun plainKey(id: String = "key-1", label: String = "id_ed25519") = SshKey(
        id = id,
        label = label,
        keyType = fr.techtical.nextsh.domain.model.SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA...",
        isBiometric = false,
        keystoreAlias = null,
    )

    private fun usedBy(vararg labels: String, tunnels: Int = 0) = KeyUsage(labels.toList(), tunnels)

    /** Nothing may be deleted, from the Vault, the Keystore or the database. */
    private fun verifyNothingWasDeleted() {
        verify(exactly = 0) { vaultManager.deletePrivateKey(any()) }
        verify(exactly = 0) { vaultManager.deleteKeyPassphrase(any()) }
        verify(exactly = 0) { vaultManager.deleteCertificate(any()) }
        verify(exactly = 0) { keystoreManager.deleteBiometricKey(any()) }
        coVerify(exactly = 0) { sshKeyRepository.delete(any()) }
    }

    @Test
    fun `no dialog is pending before a deletion is requested`() {
        assertNull(viewModel.uiState.value.pendingKeyDeletion)
    }

    @Test
    fun `requestKeyDeletion of a key no host uses opens the plain dialog`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns KeyUsage.NONE

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion
        assertEquals(VaultViewModel.PendingKeyDeletion(keyId = "key-1", keyLabel = "id_ed25519"), pending)
        assertFalse(pending!!.isInUse)
        assertEquals(0, pending.hostCount)
        verifyNothingWasDeleted()
    }

    @Test
    fun `requestKeyDeletion with one host names it`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("web-01")

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals(listOf("web-01"), pending.hostNames)
        assertEquals(0, pending.otherHostCount)
        assertEquals(0, pending.tunnelCount)
        assertEquals(1, pending.hostCount)
        assertTrue(pending.isInUse)
    }

    @Test
    fun `requestKeyDeletion with three hosts names all of them and counts no other`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("alpha", "bravo", "charlie")

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals(listOf("alpha", "bravo", "charlie"), pending.hostNames)
        assertEquals(0, pending.otherHostCount)
        assertEquals(3, pending.hostCount)
    }

    @Test
    fun `requestKeyDeletion with five hosts names the first three and counts two others`() = runTest {
        // The reader hands the labels alphabetical: the first three are the ones named.
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("alpha", "bravo", "charlie", "delta", "echo")

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals(listOf("alpha", "bravo", "charlie"), pending.hostNames)
        assertEquals(2, pending.otherHostCount)
        assertEquals(5, pending.hostCount)
    }

    @Test
    fun `requestKeyDeletion with a fourth host says one other`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("alpha", "bravo", "charlie", "delta")

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals(3, pending.hostNames.size)
        assertEquals(1, pending.otherHostCount)
    }

    @Test
    fun `requestKeyDeletion carries the tunnels that go through the hosts`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("web-01", "db-02", tunnels = 3)

        viewModel.requestKeyDeletion(plainKey())

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals(3, pending.tunnelCount)
        assertEquals(listOf("web-01", "db-02"), pending.hostNames)
    }

    @Test
    fun `requestKeyDeletion looks up the key it was given, and only reads`() = runTest {
        coEvery { keyUsageReader.usageOf(any()) } returns usedBy("web-01")

        viewModel.requestKeyDeletion(plainKey(id = "key-42"))

        coVerify(exactly = 1) { keyUsageReader.usageOf("key-42") }
        verifyNothingWasDeleted()
    }

    @Test
    fun `requestKeyDeletion still opens the plain dialog when the lookup fails, flagged as unchecked, and logs no label`() = runTest {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            coEvery { keyUsageReader.usageOf("key-1") } throws IllegalStateException(LEAK_MARKER)

            viewModel.requestKeyDeletion(plainKey(label = "my-secret-key-label"))

            // Never block the deletion on a read error: the plain dialog, plus the flag that makes
            // it say the check could not be made.
            assertEquals(
                VaultViewModel.PendingKeyDeletion(
                    keyId = "key-1",
                    keyLabel = "my-secret-key-label",
                    usageCheckFailed = true,
                ),
                viewModel.uiState.value.pendingKeyDeletion,
            )
            assertFalse(viewModel.uiState.value.pendingKeyDeletion!!.isInUse)
            assertNull(viewModel.uiState.value.errorRes)
            assertNull(viewModel.uiState.value.error)
            assertTrue(tree.entries.isNotEmpty())
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertFalse(entry.message.contains("my-secret-key-label"), "log leaks the label of the key")
                assertEquals(null, entry.throwable, "no throwable (its message and trace) may be logged")
            }
            assertTrue(tree.entries.any { it.message.contains("IllegalStateException") })
            verifyNothingWasDeleted()
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `confirming deletes the key and closes the dialog`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("web-01", "db-02", tunnels = 1)
        coEvery { sshKeyRepository.getById("key-1") } returns plainKey()
        every { vaultManager.deletePrivateKey("key-1") } returns Unit
        every { vaultManager.deleteKeyPassphrase("key-1") } returns Unit
        every { vaultManager.deleteCertificate("key-1") } returns Unit
        coEvery { sshKeyRepository.delete("key-1") } returns Unit
        viewModel.requestKeyDeletion(plainKey())
        verifyNothingWasDeleted()

        viewModel.deleteKey(viewModel.uiState.value.pendingKeyDeletion!!.keyId)

        assertNull(viewModel.uiState.value.pendingKeyDeletion)
        awaitVerified {
            verify(exactly = 1) { vaultManager.deletePrivateKey("key-1") }
            coVerify(exactly = 1) { sshKeyRepository.delete("key-1") }
            assertEquals(MessageRes(R.string.vault_key_deleted), viewModel.uiState.value.successRes)
        }
    }

    @Test
    fun `cancelling deletes nothing and closes the dialog`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } returns usedBy("web-01")
        viewModel.requestKeyDeletion(plainKey())
        assertNotNull(viewModel.uiState.value.pendingKeyDeletion)

        viewModel.cancelKeyDeletion()

        assertNull(viewModel.uiState.value.pendingKeyDeletion)
        assertNull(viewModel.uiState.value.successRes)
        assertNull(viewModel.uiState.value.errorRes)
        verifyNothingWasDeleted()
    }

    @Test
    fun `cancelling while the lookup is running keeps the dialog from opening afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { keyUsageReader.usageOf("key-1") } coAnswers {
            gate.await()
            usedBy("web-01")
        }
        viewModel.requestKeyDeletion(plainKey())
        assertNull(viewModel.uiState.value.pendingKeyDeletion, "the dialog waits for the lookup")

        viewModel.cancelKeyDeletion()
        gate.complete(Unit)

        assertNull(viewModel.uiState.value.pendingKeyDeletion)
        verifyNothingWasDeleted()
    }

    @Test
    fun `a failed check can still be confirmed, and deletes the key`() = runTest {
        coEvery { keyUsageReader.usageOf("key-1") } throws IllegalStateException("db closed")
        coEvery { sshKeyRepository.getById("key-1") } returns plainKey()
        every { vaultManager.deletePrivateKey("key-1") } returns Unit
        every { vaultManager.deleteKeyPassphrase("key-1") } returns Unit
        every { vaultManager.deleteCertificate("key-1") } returns Unit
        coEvery { sshKeyRepository.delete("key-1") } returns Unit
        viewModel.requestKeyDeletion(plainKey())
        assertTrue(viewModel.uiState.value.pendingKeyDeletion!!.usageCheckFailed)

        viewModel.deleteKey("key-1")

        assertNull(viewModel.uiState.value.pendingKeyDeletion)
        awaitVerified { coVerify(exactly = 1) { sshKeyRepository.delete("key-1") } }
    }

    @Test
    fun `a second request replaces the first one, which answers last`() = runTest {
        // The first lookup (key 1) is slow, the second (key 2) answers at once. When the first
        // finally answers, its dialog must not replace the one of key 2.
        val firstGate = CompletableDeferred<Unit>()
        coEvery { keyUsageReader.usageOf("key-1") } coAnswers {
            firstGate.await()
            usedBy("web-01")
        }
        coEvery { keyUsageReader.usageOf("key-2") } returns KeyUsage.NONE

        viewModel.requestKeyDeletion(plainKey(id = "key-1", label = "first"))
        assertNull(viewModel.uiState.value.pendingKeyDeletion, "the first lookup is still running")
        viewModel.requestKeyDeletion(plainKey(id = "key-2", label = "second"))
        assertEquals("key-2", viewModel.uiState.value.pendingKeyDeletion?.keyId)

        firstGate.complete(Unit)

        val pending = viewModel.uiState.value.pendingKeyDeletion!!
        assertEquals("key-2", pending.keyId)
        assertEquals("second", pending.keyLabel)
        assertFalse(pending.isInUse)
        verifyNothingWasDeleted()
    }

    @Test
    fun `a deletion confirmed while the lookup is still running keeps the dialog from opening afterwards`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { keyUsageReader.usageOf("key-1") } coAnswers {
            gate.await()
            usedBy("web-01")
        }
        coEvery { sshKeyRepository.getById("key-1") } returns plainKey()
        every { vaultManager.deletePrivateKey("key-1") } returns Unit
        every { vaultManager.deleteKeyPassphrase("key-1") } returns Unit
        every { vaultManager.deleteCertificate("key-1") } returns Unit
        coEvery { sshKeyRepository.delete("key-1") } returns Unit
        viewModel.requestKeyDeletion(plainKey())
        assertNull(viewModel.uiState.value.pendingKeyDeletion)

        viewModel.deleteKey("key-1")
        gate.complete(Unit)

        awaitVerified { coVerify(exactly = 1) { sshKeyRepository.delete("key-1") } }
        assertNull(viewModel.uiState.value.pendingKeyDeletion, "a late answer must not reopen a dialog for a deleted key")
    }

    @Test
    fun `a biometric key goes through the same warning, and its deletion is unchanged`() = runTest {
        coEvery { keyUsageReader.usageOf(bioKeyId) } returns usedBy("phone-login")
        coEvery { sshKeyRepository.getById(bioKeyId) } returns biometricKey(storedAlias = null)
        every { keystoreManager.deleteBiometricKey(any()) } returns Unit
        coEvery { sshKeyRepository.delete(bioKeyId) } returns Unit

        viewModel.requestKeyDeletion(biometricKey(storedAlias = null))
        assertEquals(listOf("phone-login"), viewModel.uiState.value.pendingKeyDeletion!!.hostNames)
        verifyNothingWasDeleted()

        viewModel.deleteKey(bioKeyId)

        awaitVerified {
            verify(exactly = 1) { keystoreManager.deleteBiometricKey(BiometricKeyAlias.forKeyId(bioKeyId)) }
            coVerify(exactly = 1) { sshKeyRepository.delete(bioKeyId) }
        }
    }

    // ── generate and import: fixed sentences, resources in the state ───────

    @Test
    fun `generateKey success is a resource with the type and the label`() = runTest {
        val key = plainKey(label = "work laptop")
        coEvery { generateSshKey("work laptop", fr.techtical.nextsh.domain.model.SshKeyType.ED25519) } returns Result.success(key)

        viewModel.generateKey("  work laptop  ")

        awaitVerified {
            assertEquals(
                MessageRes(R.string.vault_key_generated, listOf("ED25519", "work laptop")),
                viewModel.uiState.value.successRes,
            )
            assertFalse(viewModel.uiState.value.isGenerating)
        }
    }

    @Test
    fun `generateKey failure shows a fixed sentence, never the message, and logs the class only`() = runTest {
        val tree = RecordingTree()
        timber.log.Timber.plant(tree)
        try {
            coEvery { generateSshKey(any(), any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))

            viewModel.generateKey("work laptop")

            awaitVerified { assertEquals(MessageRes(R.string.vault_key_generate_failed), viewModel.uiState.value.errorRes) }
            assertNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isGenerating)
            tree.entries.forEach { entry ->
                assertFalse(entry.message.contains(LEAK_MARKER), "log leaks the exception message")
                assertEquals(null, entry.throwable)
            }
        } finally {
            timber.log.Timber.uproot(tree)
        }
    }

    @Test
    fun `generateKey tells that the vault window is over rather than a generic failure`() = runTest {
        // The real form: the shared use case reports the shared exception, which the vault adapter
        // builds around the exception of the app (see VaultManagerAdapter).
        val sharedForm = SharedVaultAuthExpiredException("Vault authentication expired")
            .also { it.initCause(VaultAuthExpiredException()) }
        coEvery { generateSshKey(any(), any()) } returns Result.failure(sharedForm)

        viewModel.generateKey("work laptop")

        awaitVerified { assertEquals(MessageRes(R.string.vault_error_auth_expired), viewModel.uiState.value.errorRes) }
        // The lock screen comes up over the Vault screen: unlocking it opens the vault again.
        assertTrue(appLock.isLocked())
    }

    @Test
    fun `a failure that is not an expired vault window leaves the app unlocked`() = runTest {
        coEvery { generateSshKey(any(), any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))

        viewModel.generateKey("work laptop")

        awaitVerified { assertEquals(MessageRes(R.string.vault_key_generate_failed), viewModel.uiState.value.errorRes) }
        assertFalse(appLock.isLocked())
    }

    @Test
    fun `generateKey recognizes the shared expiry exception alone, without the app one as its cause`() = runTest {
        coEvery { generateSshKey(any(), any()) } returns Result.failure(SharedVaultAuthExpiredException())

        viewModel.generateKey("work laptop")

        awaitVerified { assertEquals(MessageRes(R.string.vault_error_auth_expired), viewModel.uiState.value.errorRes) }
    }

    @Test
    fun `importKey with a blank label or a blank key says what is missing and wipes the passphrase`() = runTest {
        val first = "a passphrase".toCharArray()
        viewModel.importKey("  ", "-----BEGIN OPENSSH PRIVATE KEY-----", first)
        assertEquals(MessageRes(R.string.vault_biometric_name_empty), viewModel.uiState.value.errorRes)
        assertTrue(first.isWiped())

        val second = "a passphrase".toCharArray()
        viewModel.importKey("work", "   ", second)
        assertEquals(MessageRes(R.string.vault_key_pem_empty), viewModel.uiState.value.errorRes)
        assertTrue(second.isWiped())
        coVerify(exactly = 0) { importSshKey(any(), any(), any()) }
    }

    @Test
    fun `importKey success is a resource with the label`() = runTest {
        coEvery { importSshKey("work", "pem", null) } returns Result.success(plainKey(label = "work"))

        viewModel.importKey("work", "pem")

        awaitVerified {
            assertEquals(MessageRes(R.string.vault_key_imported, listOf("work")), viewModel.uiState.value.successRes)
            assertNull(viewModel.uiState.value.pendingEncryptedImport)
        }
    }

    @Test
    fun `importKey of an encrypted key asks for the passphrase and shows no error`() = runTest {
        coEvery { importSshKey("work", "pem", null) } returns
            Result.failure(fr.techtical.nextsh.domain.usecase.PassphraseRequiredException())

        viewModel.importKey("work", "pem")

        awaitVerified {
            assertEquals(VaultViewModel.PendingImport("work", "pem"), viewModel.uiState.value.pendingEncryptedImport)
            assertNull(viewModel.uiState.value.errorRes)
            assertNull(viewModel.uiState.value.error)
        }
    }

    @Test
    fun `importKey failures each get their own fixed sentence, none shows the message`() = runTest {
        val cases = listOf(
            fr.techtical.nextsh.domain.usecase.WrongKeyPassphraseException() to R.string.vault_key_import_wrong_passphrase,
            fr.techtical.nextsh.domain.usecase.UnreadableKeyException(IllegalArgumentException(LEAK_MARKER)) to R.string.vault_key_import_unreadable,
            IllegalStateException(LEAK_MARKER) to R.string.vault_key_import_failed,
            IllegalStateException(LEAK_MARKER, VaultAuthExpiredException()) to R.string.vault_error_auth_expired,
        )
        cases.forEach { (failure, expected) ->
            coEvery { importSshKey("work", "pem", any()) } returns Result.failure(failure)

            viewModel.importKey("work", "pem", "a passphrase".toCharArray())

            awaitVerified { assertEquals(MessageRes(expected), viewModel.uiState.value.errorRes, failure.javaClass.simpleName) }
            assertNull(viewModel.uiState.value.error)
            assertFalse(viewModel.uiState.value.isGenerating)
            viewModel.clearMessage()
        }
    }

    // ── what the screen reads: a resource or a string, one text ─────────────

    @Test
    fun `clearMessage clears the strings and the resources alike`() = runTest {
        viewModel.generateKey("  ")
        assertNotNull(viewModel.uiState.value.errorRes)

        viewModel.clearMessage()

        val state = viewModel.uiState.value
        assertNull(state.errorRes)
        assertNull(state.successRes)
        assertNull(state.error)
        assertNull(state.successMessage)
    }

    @Test
    fun `a message resource is read in the current language with its arguments`() {
        val context = mockk<Context> {
            every { getString(R.string.vault_key_deleted) } returns "deleted text"
            every { getString(R.string.vault_key_generated, "ED25519", "work") } returns "generated work"
        }

        assertEquals("deleted text", MessageRes(R.string.vault_key_deleted).resolve(context))
        assertEquals("generated work", MessageRes(R.string.vault_key_generated, listOf("ED25519", "work")).resolve(context))
    }

    @Test
    fun `the screen text is the string when there is one, else the resource`() {
        val context = mockk<Context> {
            every { getString(R.string.vault_key_deleted) } returns "deleted text"
        }
        val state = VaultViewModel.VaultUiState()

        assertNull(state.errorText(context))
        assertNull(state.successText(context))
        assertFalse(state.hasSuccess)
        assertEquals("a string", state.copy(error = "a string").errorText(context))
        assertEquals("a string", state.copy(error = "a string", errorRes = MessageRes(R.string.vault_key_deleted)).errorText(context))
        assertEquals("deleted text", state.copy(successRes = MessageRes(R.string.vault_key_deleted)).successText(context))
        assertTrue(state.copy(successRes = MessageRes(R.string.vault_key_deleted)).hasSuccess)
        assertTrue(state.copy(successMessage = "done").hasSuccess)
    }

    @Test
    fun `an expired vault window is found behind a wrapper, in either of its two classes, and only that`() {
        assertEquals(R.string.vault_error_auth_expired, keyActionFailureRes(VaultAuthExpiredException(), R.string.vault_key_delete_failed))
        assertEquals(R.string.vault_error_auth_expired, keyActionFailureRes(SharedVaultAuthExpiredException(), R.string.vault_key_delete_failed))
        assertEquals(
            R.string.vault_error_auth_expired,
            keyActionFailureRes(RuntimeException("x", IllegalStateException("y", VaultAuthExpiredException())), R.string.vault_key_delete_failed),
        )
        assertEquals(
            R.string.vault_error_auth_expired,
            keyActionFailureRes(RuntimeException("x", SharedVaultAuthExpiredException()), R.string.vault_key_delete_failed),
        )
        assertEquals(R.string.vault_key_delete_failed, keyActionFailureRes(RuntimeException("x"), R.string.vault_key_delete_failed))
    }

    @Test
    fun `a cause chain that loops back on itself does not hang the lookup`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertEquals(R.string.vault_key_delete_failed, keyActionFailureRes(a, R.string.vault_key_delete_failed))
    }

    // ── one message at a time: a string and a resource are never both set ───

    @Test
    fun `an error resource set after an error string is the one shown, and the string is gone`() = runTest {
        coEvery { exportVaultUseCase(any()) } returns Result.failure(IllegalStateException(LEAK_MARKER))
        viewModel.startExport("a passphrase".toCharArray(), exportContext())
        awaitVerified { assertEquals("export failed text", viewModel.uiState.value.error) }

        viewModel.generateKey("   ")

        val state = viewModel.uiState.value
        assertNull(state.error)
        assertEquals(MessageRes(R.string.vault_biometric_name_empty), state.errorRes)
        assertEquals("empty name text", state.errorText(biometricContext()))
    }

    @Test
    fun `an error string set after an error resource is the one shown, and the resource is gone`() = runTest {
        viewModel.generateKey("   ")
        assertNotNull(viewModel.uiState.value.errorRes)

        every { biometricHelper.checkStrongBiometric() } returns StrongBiometricStatus.NO_HARDWARE
        viewModel.generateBiometricKey("My phone", biometricContext())

        val state = viewModel.uiState.value
        assertNull(state.errorRes)
        assertEquals("no sensor text", state.error)
        assertEquals("no sensor text", state.errorText(biometricContext()))
    }

    @Test
    fun `starting an export or an import clears an error resource left by a key action`() = runTest {
        viewModel.generateKey("   ")
        assertNotNull(viewModel.uiState.value.errorRes)
        coEvery { exportVaultUseCase(any()) } returns Result.success(VaultExport(encryptedBackup.copyOf(), 0))

        viewModel.startExport("a passphrase".toCharArray(), exportContext())

        assertNull(viewModel.uiState.value.errorRes)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun `a success resource and a success string replace each other, in both orders`() = runTest {
        every { biometricHelper.checkStrongBiometric() } returns StrongBiometricStatus.AVAILABLE
        val publicKey = mockk<java.security.PublicKey>()
        every { keystoreManager.generateBiometricSshKey(any()) } returns publicKey
        every { sshKeyManager.formatKeystorePublicKeyOpenSsh(publicKey) } returns "ecdsa-sha2-nistp256 AAAA"
        coEvery { sshKeyRepository.save(any()) } returns Unit
        coEvery { generateSshKey(any(), any()) } returns Result.success(plainKey(label = "work"))
        val context = mockk<Context> {
            every { getString(R.string.vault_key_generated, "ED25519", "work") } returns "generated work"
        }

        viewModel.generateBiometricKey("My phone", biometricContext())
        awaitVerified { assertEquals("generated My phone", viewModel.uiState.value.successMessage) }

        viewModel.generateKey("work")
        awaitVerified { assertEquals(MessageRes(R.string.vault_key_generated, listOf("ED25519", "work")), viewModel.uiState.value.successRes) }
        assertNull(viewModel.uiState.value.successMessage)
        assertEquals("generated work", viewModel.uiState.value.successText(context))

        viewModel.generateBiometricKey("My phone", biometricContext())
        awaitVerified { assertEquals("generated My phone", viewModel.uiState.value.successMessage) }
        assertNull(viewModel.uiState.value.successRes)
        assertTrue(viewModel.uiState.value.hasSuccess)
    }

    @Test
    fun `the state setters each clear the other member of their pair`() {
        val resource = MessageRes(R.string.vault_key_deleted)
        val state = VaultViewModel.VaultUiState()

        val errorThenResource = state.withError("text").withErrorRes(resource)
        assertNull(errorThenResource.error)
        assertEquals(resource, errorThenResource.errorRes)

        val resourceThenError = state.withErrorRes(resource).withError("text")
        assertNull(resourceThenError.errorRes)
        assertEquals("text", resourceThenError.error)

        val successThenResource = state.withSuccess("text").withSuccessRes(resource)
        assertNull(successThenResource.successMessage)
        assertEquals(resource, successThenResource.successRes)

        val resourceThenSuccess = state.withSuccessRes(resource).withSuccess("text")
        assertNull(resourceThenSuccess.successRes)
        assertEquals("text", resourceThenSuccess.successMessage)

        // Clearing one form clears the other too.
        assertNull(state.withErrorRes(resource).withError(null).errorRes)
        assertNull(state.withError("text").withErrorRes(null).error)
        assertFalse(state.withSuccessRes(resource).withSuccess(null).hasSuccess)
        assertFalse(state.withSuccess("text").withSuccessRes(null).hasSuccess)
    }
}
