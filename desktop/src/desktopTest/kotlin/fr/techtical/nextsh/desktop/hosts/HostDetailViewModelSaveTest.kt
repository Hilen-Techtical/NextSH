// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.hosts

import fr.techtical.nextsh.desktop.data.db.repository.DesktopHostFolderRepository
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
            saved += host
            hosts.value = hosts.value + host
        }

        override suspend fun update(host: Host) {
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
        val viewModel = HostDetailViewModel(
            repository = repository,
            sshKeyRepository = sshKeyRepository,
            hostFolderRepository = folderRepository,
            vaultManager = vaultManager,
            customThemeRepository = themeRepository,
            appScope = TestAppScope(testScope),
        )
        return Env(viewModel, repository, vaultManager)
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
        assertEquals("Cet hôte a été supprimé entre-temps, rien n'a été enregistré.", env.viewModel.state.value.error)
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
        assertEquals("Cet hôte a été supprimé entre-temps, rien n'a été enregistré.", env.viewModel.state.value.error)
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
