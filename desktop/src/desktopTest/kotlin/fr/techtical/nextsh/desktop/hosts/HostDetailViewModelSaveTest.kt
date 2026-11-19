// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
import fr.techtical.nextsh.desktop.service.DesktopTunnelService
import fr.techtical.nextsh.shared.core.backup.BackupFieldProblem
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of [HostDetailViewModel.save]: an edit re-reads the stored host right
 * before building (so fields the form does not own survive, notably the FIDO2
 * mode Android needs), and a host deleted meanwhile (e.g. by a sync) is not
 * resurrected: no host write and no vault write, and the password is still
 * wiped on that early exit.
 *
 * The scope uses [UnconfinedTestDispatcher] and the fakes never suspend for
 * real, so every `launch` in the ViewModel completes before the call returns.
 * [DesktopHostFolderRepository] is a final concrete class, hence mockk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostDetailViewModelSaveTest {

    private lateinit var testScope: CoroutineScope

    @AfterTest
    fun tearDown() {
        if (::testScope.isInitialized) testScope.cancel()
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    /** In-memory repository; [remove] mimics a tombstone (`getById` ignores deleted rows). */
    private class FakeHostRepository(initial: List<Host> = emptyList()) : HostRepository {
        private val hosts = MutableStateFlow(initial)
        val saved = mutableListOf<Host>()
        val updated = mutableListOf<Host>()

        /** When set, save and update throw it, like a database that refuses the write. */
        var writeFailure: Exception? = null

        fun remove(id: String) {
            hosts.value = hosts.value.filterNot { it.id == id }
        }

        fun replace(host: Host) {
            hosts.value = hosts.value.map { if (it.id == host.id) host else it }
        }

        override fun observeAll(): Flow<List<Host>> = hosts
        override fun observeByGroup(group: String): Flow<List<Host>> = hosts
        override fun observeGroups(): Flow<List<String>> = flowOf(emptyList())
        override suspend fun getById(id: String): Host? = hosts.value.find { it.id == id }

        override suspend fun save(host: Host) {
            writeFailure?.let { throw it }
            saved += host
            hosts.value = hosts.value + host
        }

        override suspend fun update(host: Host) {
            writeFailure?.let { throw it }
            updated += host
            replace(host)
        }

        override suspend fun delete(id: String) = remove(id)
        override suspend fun updateLastConnected(id: String) = Unit
        override fun observeFavorites(): Flow<List<Host>> = hosts
        override suspend fun setFavorite(id: String, isFavorite: Boolean) {
            hosts.value.find { it.id == id }?.let { replace(it.copy(isFavorite = isFavorite)) }
        }

        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
        override suspend fun hardDelete(id: String) = remove(id)
    }

    private val fido2Key = SshKey(
        id = "sk-1",
        label = "YubiKey",
        keyType = SshKeyType.SK_ED25519,
        publicKey = "sk-ssh-ed25519@openssh.com AAAA",
        fido2CredentialId = "cred-id",
        fido2RpId = "ssh:",
    )

    private val plainKey = SshKey(
        id = "key-1",
        label = "id_ed25519",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    private class Env(
        val viewModel: HostDetailViewModel,
        val repository: FakeHostRepository,
        val vaultManager: VaultManager,
        val tunnelService: DesktopTunnelService,
    )

    private fun newEnv(hosts: List<Host>, keys: List<SshKey> = emptyList()): Env {
        testScope = CoroutineScope(UnconfinedTestDispatcher())
        val repository = FakeHostRepository(hosts)
        val sshKeyRepository = mockk<SshKeyRepository>()
        every { sshKeyRepository.observeAll() } returns flowOf(keys)
        val folderRepository = mockk<DesktopHostFolderRepository>()
        every { folderRepository.observeAllGroups() } returns flowOf(emptyList<String>())
        val themeRepository = mockk<CustomTerminalThemeRepository>()
        every { themeRepository.observeAll() } returns flowOf(emptyList<CustomTerminalTheme>())
        val vaultManager = mockk<VaultManager>(relaxed = true)
        val tunnelService = mockk<DesktopTunnelService>(relaxed = true)
        val viewModel = HostDetailViewModel(
            repository = repository,
            sshKeyRepository = sshKeyRepository,
            hostFolderRepository = folderRepository,
            vaultManager = vaultManager,
            customThemeRepository = themeRepository,
            appScope = TestAppScope(testScope),
            tunnelService = tunnelService,
        )
        return Env(viewModel, repository, vaultManager, tunnelService)
    }

    private fun passwordHost() = Host(
        id = "h1",
        label = "Prod",
        hostname = "prod.example",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    private fun fido2Host(credentialId: String, fido2Mode: Fido2Mode) = Host(
        id = "h1",
        label = "Prod",
        hostname = "prod.example",
        username = "root",
        authType = AuthType.FIDO2,
        credentialId = credentialId,
        keepAliveSeconds = 90,
        autoReconnect = false,
        fido2Mode = fido2Mode,
        isFavorite = true,
    )

    @Test
    fun `save of a renamed FIDO2 host keeps mode, favorite, keep-alive and auto-reconnect`() {
        val stored = fido2Host(fido2Key.id, Fido2Mode.HARDWARE_KEY)
        val env = newEnv(listOf(stored), keys = listOf(fido2Key))
        env.viewModel.load("h1")

        env.viewModel.onLabel("Prod renamed")
        var done = false
        env.viewModel.save(password = null) { done = true }

        assertTrue(done)
        assertTrue(env.repository.saved.isEmpty())
        assertEquals(stored.copy(label = "Prod renamed"), env.repository.updated.single())
        assertFalse(env.viewModel.state.value.isSaving)
        assertNull(env.viewModel.state.value.error)
    }

    @Test
    fun `save of a PASSKEY host switched to a Desktop SK key becomes HARDWARE_KEY`() {
        // Android PASSKEY mode uses a regular key; Desktop can only pick an SK key.
        val stored = fido2Host(plainKey.id, Fido2Mode.PASSKEY)
        val env = newEnv(listOf(stored), keys = listOf(plainKey, fido2Key))
        env.viewModel.load("h1")

        env.viewModel.onSelectKey(fido2Key.id)
        env.viewModel.save(password = null) { }

        val updated = env.repository.updated.single()
        assertEquals(fido2Key.id, updated.credentialId)
        assertEquals(Fido2Mode.HARDWARE_KEY, updated.fido2Mode)
        assertTrue(updated.isFavorite)
        assertEquals(90, updated.keepAliveSeconds)
    }

    @Test
    fun `save of an existing host uses the stored values read at save time`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")

        // Changed elsewhere (list screen, sync) while the form is open.
        env.repository.replace(passwordHost().copy(isFavorite = true, keepAliveSeconds = 120))
        env.viewModel.onLabel("Prod renamed")
        var done = false
        env.viewModel.save(password = null) { done = true }

        assertTrue(done)
        val updated = env.repository.updated.single()
        assertEquals("Prod renamed", updated.label)
        assertTrue(updated.isFavorite)
        assertEquals(120, updated.keepAliveSeconds)
    }

    @Test
    fun `save of a host deleted meanwhile writes nothing, reports the error and wipes the password`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")

        // Deleted (e.g. by a sync) while the form is open.
        env.repository.remove("h1")
        env.viewModel.onLabel("Prod renamed")
        val password = "s3cret".toCharArray()
        var done = false
        env.viewModel.save(password) { done = true }

        assertFalse(done)
        assertTrue(env.repository.updated.isEmpty())
        assertTrue(env.repository.saved.isEmpty())
        coVerify(exactly = 0) { env.vaultManager.storePassword(any(), any()) }
        coVerify(exactly = 0) { env.vaultManager.storeCertificate(any(), any()) }
        assertEquals(HostFormError.HOST_DELETED, env.viewModel.state.value.error)
        assertFalse(env.viewModel.state.value.isSaving)
        assertTrue(password.all { it == '\u0000' }, "password must be wiped on the early exit")
    }

    @Test
    fun `save of a certificate host deleted meanwhile does not store the certificate`() {
        val certHost = passwordHost().copy(authType = AuthType.CERTIFICATE, credentialId = plainKey.id)
        val env = newEnv(listOf(certHost), keys = listOf(plainKey))
        coEvery { env.vaultManager.getCertificate(plainKey.id) } returns null
        env.viewModel.load("h1")
        env.viewModel.onCertLoaded("CERT-PEM", "id_ed25519-cert.pub")

        env.repository.remove("h1")
        var done = false
        env.viewModel.save(password = null) { done = true }

        assertFalse(done)
        assertTrue(env.repository.updated.isEmpty())
        coVerify(exactly = 0) { env.vaultManager.storeCertificate(any(), any()) }
        assertEquals(HostFormError.HOST_DELETED, env.viewModel.state.value.error)
        assertFalse(env.viewModel.state.value.isSaving)
    }

    @Test
    fun `save of an existing password host still stores the password`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")

        env.viewModel.onLabel("Prod renamed")
        val password = "s3cret".toCharArray()
        var done = false
        env.viewModel.save(password) { done = true }

        assertTrue(done)
        assertEquals("Prod renamed", env.repository.updated.single().label)
        coVerify(exactly = 1) { env.vaultManager.storePassword("cred-1", any()) }
        assertTrue(password.all { it == '\u0000' }, "password must be wiped after the save")
    }

    // -- Save refused by the data layer -----------------------------------------

    /** A new PASSWORD host with every required field filled. */
    private fun filledNewHost(env: Env) {
        env.viewModel.load(null)
        env.viewModel.onLabel("New")
        env.viewModel.onHostname("new.example")
        env.viewModel.onUsername("admin")
    }

    @Test
    fun `a refused host write gives a typed error, wipes the password and does not leave the form saving`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        env.repository.writeFailure = IllegalStateException("disk I/O error")

        env.viewModel.onLabel("Prod renamed")
        val password = "s3cret".toCharArray()
        var done = false
        env.viewModel.save(password) { done = true }

        assertFalse(done)
        assertFalse(env.viewModel.state.value.isSaving, "the Save button must come back")
        assertEquals(HostSaveError.SAVE_FAILED, env.viewModel.state.value.saveError)
        assertNull(env.viewModel.state.value.error, "the legacy hard-coded message field is not used for this")
        coVerify(exactly = 0) { env.vaultManager.storePassword(any(), any()) }
        assertTrue(password.all { it == '\u0000' }, "password must be wiped when the write fails")
    }

    @Test
    fun `a refused write of a new host leaves it new, so a retry saves it once`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.repository.writeFailure = IllegalStateException("disk I/O error")

        env.viewModel.save(password = null) { }

        assertEquals(HostSaveError.SAVE_FAILED, env.viewModel.state.value.saveError)
        assertNull(env.viewModel.state.value.hostId, "nothing was written: still a new host")

        env.repository.writeFailure = null
        var done = false
        env.viewModel.save(password = null) { done = true }

        assertTrue(done)
        assertEquals(1, env.repository.saved.size)
        assertTrue(env.repository.updated.isEmpty())
        assertNull(env.viewModel.state.value.saveError)
    }

    // -- Host name restorable from a backup (BackupValidator.isValidHostName) -----

    @Test
    fun `a host name with an inner space is refused and nothing is written`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onHostname("my host")
        val password = "s3cret".toCharArray()
        var done = false

        env.viewModel.save(password) { done = true }

        assertTrue(env.viewModel.state.value.hostnameInvalid)
        assertFalse(done)
        assertTrue(env.repository.saved.isEmpty())
        assertFalse(env.viewModel.state.value.isSaving)
        assertTrue(password.all { it == '\u0000' }, "password must be wiped on the refusal")
    }

    @Test
    fun `a host name with an invisible character is refused`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onHostname("prod" + Char(0x200B) + ".example")

        env.viewModel.save(password = null) { }

        assertTrue(env.viewModel.state.value.hostnameInvalid)
        assertTrue(env.repository.saved.isEmpty())
    }

    @Test
    fun `a host name over 255 characters is refused`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onHostname("a".repeat(256))

        env.viewModel.save(password = null) { }

        assertTrue(env.viewModel.state.value.hostnameInvalid)
        assertTrue(env.repository.saved.isEmpty())
    }

    @Test
    fun `a host name surrounded by spaces is accepted and saved trimmed`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onHostname("  new.example  ")
        var done = false

        env.viewModel.save(password = null) { done = true }

        assertFalse(env.viewModel.state.value.hostnameInvalid)
        assertTrue(done)
        assertEquals("new.example", env.repository.saved.single().hostname)
    }

    @Test
    fun `an empty host name is not flagged under the field, the required check handles it`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onHostname("   ")

        assertFalse(env.viewModel.state.value.hostnameInvalid)
    }

    // -- User name restorable from a backup (no control character) -----

    @Test
    fun `a user name with a control character is refused and nothing is written`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onUsername("ro" + Char(0x01) + "ot")
        val password = "s3cret".toCharArray()
        var done = false

        env.viewModel.save(password) { done = true }

        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, env.viewModel.state.value.usernameProblem)
        assertFalse(done)
        assertTrue(env.repository.saved.isEmpty())
        assertFalse(env.viewModel.state.value.isSaving)
        assertTrue(password.all { it == '\u0000' }, "password must be wiped on the refusal")
    }

    @Test
    fun `a user name with a tab inside is refused, a tab around it is trimmed and accepted`() {
        val env = newEnv(emptyList())
        filledNewHost(env)

        env.viewModel.onUsername("ro\tot")
        assertEquals(BackupFieldProblem.CONTROL_CHARACTER, env.viewModel.state.value.usernameProblem)

        env.viewModel.onUsername("\troot\t")
        assertNull(env.viewModel.state.value.usernameProblem)
        env.viewModel.save(password = null) { }
        assertEquals("root", env.repository.saved.single().username)
    }

    @Test
    fun `a user name over 4096 characters is refused with the length reason`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.viewModel.onUsername("u".repeat(4097))

        env.viewModel.save(password = null) { }

        assertEquals(BackupFieldProblem.TOO_LONG, env.viewModel.state.value.usernameProblem)
        assertTrue(env.repository.saved.isEmpty())
    }

    @Test
    fun `a plain or an empty user name is not flagged under the field`() {
        val env = newEnv(emptyList())
        filledNewHost(env)

        env.viewModel.onUsername("deploy")
        assertNull(env.viewModel.state.value.usernameProblem)
        env.viewModel.onUsername("   ")
        assertNull(env.viewModel.state.value.usernameProblem, "the required check handles an empty field")
    }

    @Test
    fun `a vault failure after the host of a new host was saved says so and the retry updates it`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        coEvery { env.vaultManager.storePassword(any(), any()) } throws IllegalStateException("vault locked")

        env.viewModel.save("s3cret".toCharArray()) { }

        val saved = env.repository.saved.single()
        assertEquals(HostSaveError.VAULT_FAILED, env.viewModel.state.value.saveError)
        assertFalse(env.viewModel.state.value.isSaving)
        assertEquals(saved.id, env.viewModel.state.value.hostId, "the form now edits the host that was saved")

        // Retry once the vault works: it must go through update, not create a duplicate host.
        coEvery { env.vaultManager.storePassword(any(), any()) } returns Unit
        var done = false
        env.viewModel.save("s3cret".toCharArray()) { done = true }

        assertTrue(done)
        assertEquals(1, env.repository.saved.size, "no second host")
        assertEquals(saved.id, env.repository.updated.single().id)
        assertNull(env.viewModel.state.value.saveError)
        coVerify(exactly = 2) { env.vaultManager.storePassword(saved.credentialId, any()) }
    }

    @Test
    fun `the typed password is kept after a vault failure on a new host and stored by the retry`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        val stored = mutableListOf<String>()
        coEvery { env.vaultManager.storePassword(any(), any()) } coAnswers {
            // The view model wipes the array once the call returns: keep a copy of what it was given.
            stored += secondArg<CharArray>().concatToString()
            throw IllegalStateException("vault locked")
        }
        // What the screen does: the field is cleared by the success callback only.
        var passwordField = "s3cret"
        var done = false

        env.viewModel.saveForm(passwordField) { passwordField = ""; done = true }

        assertFalse(done)
        assertEquals("s3cret", passwordField, "a refused save must not lose the typed password")
        assertEquals(HostSaveError.VAULT_FAILED, env.viewModel.state.value.saveError)
        assertEquals(listOf("s3cret"), stored)

        // The form is now an existing-host form (hostId set): the retry must still carry the password.
        coEvery { env.vaultManager.storePassword(any(), any()) } coAnswers {
            stored += secondArg<CharArray>().concatToString()
        }
        env.viewModel.saveForm(passwordField) { passwordField = ""; done = true }

        assertTrue(done)
        assertEquals("", passwordField, "the field is cleared once the save succeeded")
        assertEquals(listOf("s3cret", "s3cret"), stored)
        assertEquals(1, env.repository.saved.size, "no duplicate host")
        assertEquals(1, env.repository.updated.size)
    }

    @Test
    fun `the typed password is kept after a refused host write and stored by the retry`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        env.repository.writeFailure = IllegalStateException("disk I/O error")
        val stored = mutableListOf<String>()
        coEvery { env.vaultManager.storePassword(any(), any()) } coAnswers {
            stored += secondArg<CharArray>().concatToString()
        }
        var passwordField = "n3w-s3cret"
        var done = false

        env.viewModel.saveForm(passwordField) { passwordField = ""; done = true }

        assertFalse(done)
        assertEquals("n3w-s3cret", passwordField)
        assertEquals(HostSaveError.SAVE_FAILED, env.viewModel.state.value.saveError)
        assertTrue(stored.isEmpty(), "nothing reached the vault")

        env.repository.writeFailure = null
        env.viewModel.saveForm(passwordField) { passwordField = ""; done = true }

        assertTrue(done)
        assertEquals("", passwordField)
        assertEquals(listOf("n3w-s3cret"), stored)
    }

    @Test
    fun `a validation failure does not lose the typed password either`() {
        val env = newEnv(emptyList())
        env.viewModel.load(null) // label, hostname and username still empty
        var passwordField = "s3cret"
        var done = false

        env.viewModel.saveForm(passwordField) { passwordField = ""; done = true }

        assertFalse(done)
        assertEquals("s3cret", passwordField)
        assertEquals(HostFormError.LABEL_REQUIRED, env.viewModel.state.value.error)
    }

    @Test
    fun `an empty password field passes no password, so the stored one is kept`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")

        var done = false
        env.viewModel.saveForm("") { done = true }

        assertTrue(done)
        coVerify(exactly = 0) { env.vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `typing in the password field clears the save error`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        env.repository.writeFailure = IllegalStateException("disk I/O error")
        env.viewModel.saveForm("s3cret") { }
        assertEquals(HostSaveError.SAVE_FAILED, env.viewModel.state.value.saveError)

        env.viewModel.onPasswordEdited()

        assertNull(env.viewModel.state.value.saveError)
    }

    @Test
    fun `a vault failure on an existing host is reported as a vault failure`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        coEvery { env.vaultManager.storePassword(any(), any()) } throws IllegalStateException("vault locked")

        env.viewModel.onLabel("Prod renamed")
        var done = false
        env.viewModel.save("s3cret".toCharArray()) { done = true }

        assertFalse(done)
        assertFalse(env.viewModel.state.value.isSaving)
        assertEquals(HostSaveError.VAULT_FAILED, env.viewModel.state.value.saveError)
        assertEquals("Prod renamed", env.repository.updated.single().label)
    }

    @Test
    fun `editing the form clears the save error`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.repository.writeFailure = IllegalStateException("disk I/O error")
        env.viewModel.save(password = null) { }
        assertEquals(HostSaveError.SAVE_FAILED, env.viewModel.state.value.saveError)

        env.viewModel.onLabel("Other")

        assertNull(env.viewModel.state.value.saveError)
    }

    @Test
    fun `a cancellation is not reported as a save failure`() {
        val env = newEnv(emptyList())
        filledNewHost(env)
        env.repository.writeFailure = kotlinx.coroutines.CancellationException("scope cancelled")

        env.viewModel.save(password = null) { }

        assertNull(env.viewModel.state.value.saveError, "cancellation must propagate, not become an error banner")
    }

    @Test
    fun `deleting a host stops its running tunnels first, then deletes the host`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        var hostStillThereWhenStopped: Boolean? = null
        coEvery { env.tunnelService.stopTunnelsOfHost("h1") } coAnswers {
            hostStillThereWhenStopped = env.repository.getById("h1") != null
        }

        var done = false
        env.viewModel.delete { done = true }

        assertTrue(done)
        coVerify(exactly = 1) { env.tunnelService.stopTunnelsOfHost("h1") }
        assertEquals(true, hostStillThereWhenStopped, "tunnels must be stopped before the host is deleted")
        assertNull(runBlocking { env.repository.getById("h1") })
    }

    @Test
    fun `deleting a host goes on with the delete when stopping its tunnels fails`() {
        val env = newEnv(listOf(passwordHost()))
        env.viewModel.load("h1")
        coEvery { env.tunnelService.stopTunnelsOfHost("h1") } throws IllegalStateException("socket error")

        var done = false
        env.viewModel.delete { done = true }

        assertTrue(done)
        assertNull(runBlocking { env.repository.getById("h1") })
    }

    @Test
    fun `save of a new host calls save and not update`() {
        val env = newEnv(emptyList())
        env.viewModel.load(null)
        env.viewModel.onLabel(" New ")
        env.viewModel.onHostname("new.example")
        env.viewModel.onUsername("admin")

        var done = false
        env.viewModel.save(password = null) { done = true }

        assertTrue(done)
        assertTrue(env.repository.updated.isEmpty())
        val saved = env.repository.saved.single()
        assertEquals("New", saved.label)
        assertFalse(saved.isFavorite)
        assertNull(saved.fido2Mode)
        assertFalse(env.viewModel.state.value.isSaving)
        assertNull(env.viewModel.state.value.error)
    }
}
