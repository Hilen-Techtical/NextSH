// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.hosts

import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import fr.techtical.nextsh.core.crypto.BiometricHelper
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.core.vault.VaultManager
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.usecase.ConnectSessionUseCase
import fr.techtical.nextsh.shared.core.sync.SyncScheduler
import fr.techtical.nextsh.shared.core.sync.SyncState
import fr.techtical.nextsh.shared.core.sync.SyncStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.junit5.MockKExtension
import io.mockk.coJustRun
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(MockKExtension::class)
class HostViewModelTest {

    @MockK
    private lateinit var hostRepository: HostRepository

    @MockK
    private lateinit var connectSession: ConnectSessionUseCase

    @MockK
    private lateinit var vaultManager: VaultManager

    @MockK
    private lateinit var sshKeyRepository: SshKeyRepository

    @MockK
    private lateinit var customThemeRepository: CustomTerminalThemeRepository

    @MockK
    private lateinit var syncScheduler: SyncScheduler

    @MockK
    private lateinit var biometricHelper: BiometricHelper

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var viewModel: HostViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        every { hostRepository.observeAll() } returns flowOf(emptyList())
        every { hostRepository.observeGroups() } returns flowOf(emptyList())
        every { sshKeyRepository.observeAll() } returns flowOf(emptyList())
        every { customThemeRepository.observeAll() } returns flowOf(emptyList())
        every { syncScheduler.syncState } returns MutableStateFlow(SyncState(SyncStatus.IDLE, null))
        // Par défaut aucun id n'est celui d'une clé SSH.
        coEvery { sshKeyRepository.getById(any()) } returns null

        viewModel = newViewModel()
    }

    /** [ioDispatcher] est le dispatcher des écritures du vault : le dispatcher de test par défaut. */
    private fun newViewModel(ioDispatcher: CoroutineDispatcher = testDispatcher) = HostViewModel(
        hostRepository = hostRepository,
        connectSession = connectSession,
        vaultManager = vaultManager,
        sshKeyRepository = sshKeyRepository,
        customThemeRepository = customThemeRepository,
        syncScheduler = syncScheduler,
        biometricHelper = biometricHelper,
        ioDispatcher = ioDispatcher,
    )

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Test helpers ─────────────────────────────────────────────────────────

    private fun makeHost(id: String, isFavorite: Boolean) = Host(
        id = id,
        label = "Host $id",
        hostname = "host$id.example.com",
        username = "user",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
        isFavorite = isFavorite,
    )

    // ── toggleFavorite tests ─────────────────────────────────────────────────

    @Test
    fun `toggleFavorite calls setFavorite with true when host is not a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = false)
        coJustRun { hostRepository.setFavorite(host.id, true) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, true) }
    }

    @Test
    fun `toggleFavorite calls setFavorite with false when host is already a favorite`() = runTest {
        val host = makeHost("h1", isFavorite = true)
        coJustRun { hostRepository.setFavorite(host.id, false) }

        viewModel.toggleFavorite(host)

        advanceUntilIdle()
        coVerify(exactly = 1) { hostRepository.setFavorite(host.id, false) }
    }

    // ── saveHost favorite preservation tests ────────────────────────────────

    @Test
    fun `saveHost preserves isFavorite when updating an existing favorite host`() = runTest {
        val existingHost = makeHost("h1", isFavorite = true)
        coEvery { hostRepository.getById("h1") } returns existingHost
        coJustRun { hostRepository.update(any()) }

        // Load host for edit
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        // Save without changing anything
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertTrue(hostSlot.captured.isFavorite, "isFavorite should be true after update")
    }

    @Test
    fun `saveHost sets isFavorite to false for new host`() = runTest {
        coJustRun { hostRepository.save(any()) }
        coJustRun { vaultManager.storePassword(any(), any()) }

        // Fill minimal form for a new host
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "pass",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertFalse(hostSlot.captured.isFavorite, "isFavorite should be false for new host")
    }

    // ── Mot de passe d'un hôte : jamais sous l'id d'une clé SSH ─────────────

    private val storedPasswords = mutableMapOf<String, String>()

    /** Enregistre le contenu de chaque storePassword au moment de l'appel (le vrai wipe le tableau). */
    private fun recordStoredPasswords() {
        every { vaultManager.storePassword(any(), any()) } answers {
            storedPasswords[firstArg()] = secondArg<CharArray>().concatToString()
        }
    }

    private fun makeSshKey(id: String) = SshKey(
        id = id,
        label = "Key $id",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAA",
    )

    private fun makeHostWith(authType: AuthType, credentialId: String) = Host(
        id = "h1",
        label = "Host h1",
        hostname = "hosth1.example.com",
        username = "user",
        authType = authType,
        credentialId = credentialId,
    )

    @Test
    fun `saveHost gives a new credentialId to a key host switched to PASSWORD`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD, password = "typed") }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        val newId = hostSlot.captured.credentialId
        assertNotEquals("key-1", newId, "a PASSWORD host must not reuse the SSH key id")
        assertTrue(newId.isNotBlank())
        assertEquals(mapOf(newId to "typed"), storedPasswords)
        // Le mot de passe retapé suffit : l'ancien slot n'est ni lu ni supprimé.
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost repairs a PASSWORD host holding a key id and carries its password over`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns "kept".toCharArray()
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        val newId = hostSlot.captured.credentialId
        assertNotEquals("key-1", newId)
        assertEquals(mapOf(newId to "kept"), storedPasswords)
        // pwd_key-1 reste en place : un tombstone pwd:key-1 emporterait la clé chez un pair 1.0.0.
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost does not copy the password of another host onto a key host switched to PASSWORD`() = runTest {
        // pwd_key-1 peut appartenir à un autre hôte PASSWORD resté sur l'id de la clé (défaut 1.0.0).
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the credentialId of a PASSWORD host that is not a key id`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("cred-h1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
    }

    @Test
    fun `saveHost keeps the old id when the old password cannot be read`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } throws VaultAuthExpiredException()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        // Statu quo ante : l'hôte n'est pas détaché de son mot de passe, il sera réparé plus tard.
        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the old id when storing the carried over password fails`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns existing
        every { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertTrue(existing.all { it == '\u0000' }, "the password read must be wiped even when the store fails")
    }

    @Test
    fun `saveHost moves a repaired host to a new id even when there is no password to carry`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns null

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost wipes the password it read for the carry over`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } returns existing
        // Ce mock ne wipe PAS son argument (le vrai storePassword le fait) : seul le ViewModel peut le zéroter.
        every { vaultManager.storePassword(any(), any()) } just Runs

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertTrue(existing.all { it == '\u0000' }, "the CharArray read from the vault must be zeroed")
    }

    @Test
    fun `saveHost does not carry a password shared with another live host and repairs the second host later`() = runTest {
        val first = makeHostWith(AuthType.PASSWORD, "key-1")
        val second = first.copy(id = "h2", label = "Host h2")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()

        // 1) Deux hôtes PASSWORD sur le même id de clé : enregistrer le premier sans retaper le mot
        //    de passe ne recopie rien, ne supprime rien et le laisse sur l'id partagé.
        coEvery { hostRepository.getById("h1") } returns first
        every { hostRepository.observeAll() } returns flowOf(listOf(first, second))
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val firstSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(firstSlot)) }
        assertEquals("key-1", firstSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }

        // 2) Le premier repasse sur un slot isolé (mot de passe retapé) : le second n'est plus
        //    partagé, sa réparation recopie le mot de passe existant.
        val firstRepaired = first.copy(credentialId = "isolated-1")
        coEvery { hostRepository.getById("h2") } returns second
        every { hostRepository.observeAll() } returns flowOf(listOf(firstRepaired, second))
        every { vaultManager.getPassword("key-1") } returns "kept".toCharArray()
        viewModel.loadHostForEdit("h2")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val updates = mutableListOf<Host>()
        coVerify(exactly = 2) { hostRepository.update(capture(updates)) }
        assertEquals("h2", updates.last().id)
        val newId = updates.last().credentialId
        assertNotEquals("key-1", newId)
        assertEquals(mapOf(newId to "kept"), storedPasswords)
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost gives a new id and copies nothing to a BIOMETRIC_KEY host switched to PASSWORD`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.BIOMETRIC_KEY, "key-bio")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-bio", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost stores the typed password of a new host under the id it saves`() = runTest {
        coJustRun { hostRepository.save(any()) }
        recordStoredPasswords()

        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "typed",
            )
        }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        val savedId = hostSlot.captured.credentialId
        assertTrue(savedId.isNotBlank())
        assertEquals(mapOf(savedId to "typed"), storedPasswords)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
    }

    @Test
    fun `saveHost keeps the id and copies nothing for PASSWORD then SSH_KEY then PASSWORD in one session`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(authType = AuthType.SSH_KEY) }
        viewModel.updateForm { copy(authType = AuthType.PASSWORD) }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("cred-h1", hostSlot.captured.credentialId)
        verify(exactly = 0) { vaultManager.getPassword(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    // ── Clé disparue : plus de sélection invisible ──────────────────────────

    @Test
    fun `loadHostForEdit clears the selection and flags a key that no longer exists`() = runTest {
        // sshKeyRepository.getById renvoie null par défaut : la clé n'existe plus.
        for (authType in listOf(AuthType.SSH_KEY, AuthType.CERTIFICATE, AuthType.FIDO2)) {
            coEvery { hostRepository.getById("h1") } returns makeHostWith(authType, "key-gone")

            viewModel.loadHostForEdit("h1")
            advanceUntilIdle()

            val form = viewModel.formState.value
            assertNull(form.selectedKeyId, "$authType: the dangling key must not stay selected")
            assertTrue(form.selectedKeyMissing, "$authType: the missing key must be flagged")
            assertTrue(form.showMissingKeyError, "$authType: the error must be shown")
            assertFalse(form.isValid, "$authType: the form must be invalid until a key is chosen")
        }
    }

    @Test
    fun `the missing key error is only shown while no key is selected`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertTrue(viewModel.formState.value.showMissingKeyError)

        // Une sélection posée sans passer par selectKey masque déjà l'erreur, même si le drapeau reste levé.
        viewModel.updateForm { copy(selectedKeyId = "key-2") }
        assertTrue(viewModel.formState.value.selectedKeyMissing)
        assertFalse(viewModel.formState.value.showMissingKeyError)
    }

    @Test
    fun `loadHostForEdit keeps the selection of a key that still exists`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertEquals("key-1", form.selectedKeyId)
        assertFalse(form.selectedKeyMissing)
        assertTrue(form.isValid)
    }

    @Test
    fun `loadHostForEdit does not flag a PASSWORD host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        val form = viewModel.formState.value
        assertFalse(form.selectedKeyMissing)
        assertNull(form.selectedKeyId)
        assertTrue(form.isValid)
    }

    @Test
    fun `selectKey clears the missing key flag and makes the form valid`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertFalse(viewModel.formState.value.isValid)

        viewModel.selectKey("key-2")

        val form = viewModel.formState.value
        assertEquals("key-2", form.selectedKeyId)
        assertFalse(form.selectedKeyMissing)
        assertFalse(form.showMissingKeyError)
        assertTrue(form.isValid)
    }

    @Test
    fun `saveHost writes nothing while the key of the host is missing`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `saveHost stores the newly chosen key instead of the dangling one`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        coJustRun { hostRepository.update(any()) }
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.selectKey("key-2")
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-2", hostSlot.captured.credentialId)
    }

    @Test
    fun `a host with a missing key switched to PASSWORD does not keep the dead key id`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-gone")
        coJustRun { hostRepository.update(any()) }
        recordStoredPasswords()
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()

        viewModel.updateForm { copy(authType = AuthType.PASSWORD, password = "typed") }
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertNotEquals("key-gone", hostSlot.captured.credentialId)
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
    }

    // ── Suppression d'un hôte : son mot de passe part avec lui ──────────────

    private fun TestScope.collectEvents(): List<HostEvent> {
        val events = mutableListOf<HostEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events.add(it) }
        }
        return events
    }

    @Test
    fun `deleteHost deletes the password of a PASSWORD host`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        // Le registre peut encore lister l'hôte lui-même : il ne compte pas comme "un autre".
        every { hostRepository.observeAll() } returns
            flowOf(listOf(host, host.copy(id = "h2", credentialId = "cred-h2")))
        every { vaultManager.deletePassword("cred-h1") } returns Unit
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        // La ligne de l'hôte est supprimée d'abord : un échec du vault ne peut pas la retenir.
        coVerifyOrder {
            hostRepository.delete("h1")
            vaultManager.deletePassword("cred-h1")
        }
        verify(exactly = 1) { vaultManager.deletePassword("cred-h1") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost keeps the password slot when its id is an SSH key id`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost keeps the password slot while another host still references it`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-shared")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { hostRepository.observeAll() } returns flowOf(listOf(host.copy(id = "h2")))

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `deleteHost still deletes the host when the vault refuses`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { vaultManager.deletePassword("cred-h1") } throws VaultAuthExpiredException()
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost still emits HostDeleted when the key lookup fails after the soft delete`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("cred-h1") } throws IllegalStateException("db closed")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost still emits HostDeleted when the shared id lookup fails after the soft delete`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        every { hostRepository.observeAll() } returns flow { throw IllegalStateException("db closed") }
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostDeleted), events)
    }

    @Test
    fun `deleteHost does not swallow a cancellation during the password cleanup`() = runTest {
        val host = makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.getById("h1") } returns host
        coJustRun { hostRepository.delete("h1") }
        coEvery { sshKeyRepository.getById("cred-h1") } throws CancellationException("cancelled")
        val events = collectEvents()

        viewModel.deleteHost("h1")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        assertTrue(events.isEmpty(), "a cancelled deletion must not report HostDeleted")
    }

    @Test
    fun `deleteHost never touches the vault for a key host or an unknown host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { hostRepository.getById("h2") } returns null
        coJustRun { hostRepository.delete(any()) }

        viewModel.deleteHost("h1")
        viewModel.deleteHost("h2")
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.delete("h1") }
        coVerify(exactly = 1) { hostRepository.delete("h2") }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
    }

    @Test
    fun `saveHost keeps the key id as credentialId for a key host`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        assertEquals("key-1", hostSlot.captured.credentialId)
    }

    // ── Vault reverrouillé à l'enregistrement : ré-authentification ─────────

    private val successCallbacks = mutableListOf<(BiometricPrompt.AuthenticationResult) -> Unit>()
    private val errorCallbacks = mutableListOf<(Int, String) -> Unit>()

    /**
     * Remplace l'invite système. Les rappels de CHAQUE invite sont empilés dans [successCallbacks]
     * et [errorCallbacks] (rang 0 = première invite), les tests les déclenchent à la main.
     */
    private fun stubPrompt() {
        every {
            biometricHelper.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = capture(successCallbacks),
                onError = capture(errorCallbacks),
            )
        } just Runs
    }

    private fun verifyPromptShown(times: Int) {
        verify(exactly = times) {
            biometricHelper.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any(),
            )
        }
    }

    private fun fillNewPasswordHost() {
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.PASSWORD,
                password = "typed",
            )
        }
    }

    /** Rôle de l'écran RESUMED : lance l'invite pour la demande de ré-authentification en attente. */
    private fun confirmIdentity(activity: FragmentActivity? = mockk(relaxed = true)) {
        viewModel.confirmIdentityAndSave(activity, "title", "subtitle")
    }

    /** Le vault refuse toute écriture de mot de passe : authentification expirée. */
    private fun vaultExpired() {
        every { vaultManager.storePassword(any(), any()) } throws VaultAuthExpiredException()
    }

    /**
     * Les [writes] premières écritures sont refusées (authentification expirée), les suivantes
     * réussissent et laissent leur contenu dans [storedPasswords].
     */
    private fun vaultExpiredForFirst(writes: Int) {
        var calls = 0
        every { vaultManager.storePassword(any(), any()) } answers {
            if (calls++ < writes) throw VaultAuthExpiredException()
            storedPasswords[firstArg()] = secondArg<CharArray>().concatToString()
        }
    }

    /** Enregistre le formulaire courant jusqu'à l'ouverture de l'invite (vault expiré à l'appui). */
    private fun TestScope.saveUntilPromptShown() {
        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
    }

    @Test
    fun `saveHost with an expired vault writes nothing and waits for the re-authentication`() = runTest {
        vaultExpired()
        val events = collectEvents()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals(formBefore, viewModel.formState.value, "the form must stay untouched")
        assertNull(viewModel.saveError.value, "no message yet: the prompt is about to be shown")
        verifyPromptShown(times = 0)
    }

    @Test
    fun `the re-authentication request is kept until the screen launches the prompt`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        // Écran pas RESUMED (activité arrêtée, recréée) : personne n'appelle confirmIdentityAndSave.
        // La demande n'est ni perdue ni consommée, et Enregistrer reste désactivé.
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        verifyPromptShown(times = 0)
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)

        // L'écran revient au premier plan : la demande part, une seule fois.
        confirmIdentity()
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
        verifyPromptShown(times = 1)
    }

    @Test
    fun `an expired vault on an existing host also writes nothing`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        vaultExpired()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals("h1", viewModel.formState.value.id)
        assertEquals("retyped", viewModel.formState.value.password)
    }

    @Test
    fun `a successful prompt runs the save again and the typed password reaches the vault`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
        verifyPromptShown(times = 1)
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        // Le refus n'a rien écrit : seul le second essai laisse le mot de passe, sous l'id de l'hôte.
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
        // Le mot de passe part dans le vault avant la ligne de l'hôte (deux essais : refus, puis succès).
        // Le nouvel hôte reçoit un autre id à la relance, puisque le refus n'a rien écrit.
        coVerifyOrder {
            vaultManager.storePassword(any(), any())
            vaultManager.storePassword(hostSlot.captured.credentialId, any())
            hostRepository.save(any())
        }
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value)
        assertEquals(HostFormState(), viewModel.formState.value, "the form is reset after a successful save")
    }

    @Test
    fun `a successful prompt on an existing host saves it through update`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }
        vaultExpiredForFirst(writes = 1)
        stubPrompt()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.update(capture(hostSlot)) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals("h1", hostSlot.captured.id)
        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `the prompt is shown with the title and subtitle given by the screen`() = runTest {
        vaultExpired()
        stubPrompt()
        val screenActivity = mockk<FragmentActivity>(relaxed = true)
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.confirmIdentityAndSave(screenActivity, "Confirm save", "The vault has locked.")

        verify(exactly = 1) {
            biometricHelper.authenticate(
                activity = screenActivity,
                title = "Confirm save",
                subtitle = "The vault has locked.",
                onSuccess = any(),
                onError = any(),
            )
        }
    }

    @Test
    fun `a cancelled prompt keeps the form, shows the locked message and writes nothing`() = runTest {
        val cancelCodes = listOf(
            BiometricPrompt.ERROR_USER_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            // Annulation par le système (application passée en arrière-plan) : « resté verrouillé » est exact.
            BiometricPrompt.ERROR_CANCELED,
        )
        vaultExpired()
        stubPrompt()
        for (code in cancelCodes) {
            viewModel.resetForm()
            viewModel.dismissSaveError()
            fillNewPasswordHost()
            val formBefore = viewModel.formState.value

            saveUntilPromptShown()
            errorCallbacks.last()(code, "system text that must never be shown")
            advanceUntilIdle()

            assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value, "code $code")
            assertEquals(formBefore, viewModel.formState.value, "code $code: the form must stay intact")
            assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "code $code: Save must be enabled again")
        }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    @Test
    fun `saving again after a cancelled prompt asks for authentication again`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks.last()(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.AWAITING_REAUTH, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value, "the previous message is cleared by the new attempt")
        confirmIdentity()
        verifyPromptShown(times = 2)
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
    }

    @Test
    fun `a prompt failing for another reason shows the vault unavailable message`() = runTest {
        val failureCodes = listOf(
            BiometricPrompt.ERROR_LOCKOUT,
            BiometricPrompt.ERROR_HW_UNAVAILABLE,
            BiometricPrompt.ERROR_TIMEOUT,
        )
        vaultExpired()
        stubPrompt()
        for (code in failureCodes) {
            viewModel.resetForm()
            viewModel.dismissSaveError()
            fillNewPasswordHost()
            val formBefore = viewModel.formState.value

            saveUntilPromptShown()
            errorCallbacks.last()(code, "Too many attempts. Try again later.")
            advanceUntilIdle()

            assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value, "code $code")
            assertEquals(formBefore, viewModel.formState.value, "code $code: the form must stay intact")
            assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "code $code")
        }
        coVerify(exactly = 0) { hostRepository.save(any()) }
    }

    @Test
    fun `a vault still expired after a successful prompt fails without a second prompt`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        verifyPromptShown(times = 1)
        verify(exactly = 2) { vaultManager.storePassword(any(), any()) }
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals(formBefore, viewModel.formState.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "no AWAITING_REAUTH loop")
    }

    @Test
    fun `any other vault failure shows the vault unavailable message and keeps the form`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws IllegalStateException("keystore broke")
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertEquals(formBefore, viewModel.formState.value)
        // Pas d'invite : s'authentifier n'y change rien.
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancellation during the vault write is not turned into a message`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws CancellationException("cancelled")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        assertNull(viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value, "Save must not stay disabled after a cancellation")
    }

    @Test
    fun `the password copy is wiped when the vault refuses the write`() = runTest {
        val passwords = mutableListOf<CharArray>()
        every { vaultManager.storePassword(any(), capture(passwords)) } throws VaultAuthExpiredException()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        assertTrue(passwords.single().all { it == '\u0000' }, "the CharArray handed to the vault must be zeroed")
    }

    // ── Un seul rappel terminal par invite ──────────────────────────────────

    /**
     * Ouvre une invite sur un nouvel hôte, puis déclenche DEUX rappels l'un après l'autre :
     * [firstSuccess] et [secondSuccess] disent s'il s'agit d'un succès ou d'une erreur. Les
     * erreurs ont des codes distincts (annulation, puis verrouillage) pour qu'un rappel pris à tort
     * se voie dans le message. L'écriture de l'hôte est suspendue entre les deux rappels : le
     * doublon arrive alors que la relance du premier est encore en vol, le cas où il ferait un
     * second enregistrement.
     */
    private fun TestScope.fireTwoCallbacks(firstSuccess: Boolean, secondSuccess: Boolean): List<HostEvent> {
        // Succès en premier : la relance trouve un vault déverrouillé. Erreur en premier : toujours expiré.
        vaultExpiredForFirst(writes = if (firstSuccess) 1 else Int.MAX_VALUE)
        val hostWriteGate = CompletableDeferred<Unit>()
        coEvery { hostRepository.save(any()) } coAnswers { hostWriteGate.await() }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()
        saveUntilPromptShown()

        fun fire(success: Boolean, errorCode: Int) {
            if (success) successCallbacks.last()(mockk()) else errorCallbacks.last()(errorCode, "x")
            advanceUntilIdle()
        }
        fire(firstSuccess, BiometricPrompt.ERROR_USER_CANCELED)
        fire(secondSuccess, BiometricPrompt.ERROR_LOCKOUT)
        hostWriteGate.complete(Unit)
        advanceUntilIdle()
        return events
    }

    @Test
    fun `a duplicate success callback does not save a second host`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = true, secondSuccess = true)

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(1, storedPasswords.size)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `a late error callback after a success shows no error`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = true, secondSuccess = false)

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value, "an error after a success must be ignored")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a late success callback after an error does not save`() = runTest {
        val events = fireTwoCallbacks(firstSuccess = false, secondSuccess = true)

        assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertTrue(events.isEmpty())
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a duplicate error callback keeps the first message`() = runTest {
        fireTwoCallbacks(firstSuccess = false, secondSuccess = false)

        assertEquals(HostSaveError.VAULT_LOCKED, viewModel.saveError.value, "the second error must not overwrite the first")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a late callback of an older prompt cannot answer the current prompt`() = runTest {
        vaultExpiredForFirst(writes = 2)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks[0](BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        saveUntilPromptShown()
        // Le succès tardif de la première invite arrive pendant la seconde : il est ignoré.
        successCallbacks[0](mockk())
        advanceUntilIdle()

        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 2) { vaultManager.storePassword(any(), any()) }

        successCallbacks[1](mockk())
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Un seul enregistrement à la fois ────────────────────────────────────

    @Test
    fun `a second tap while a save is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        coEvery { hostRepository.save(any()) } coAnswers { gate.await() }
        recordStoredPasswords()
        collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        assertEquals(HostSavePhase.SAVING, viewModel.savePhase.value, "Save is disabled while the host is being written")
        viewModel.saveHost()
        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(1, storedPasswords.size)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a tap while the prompt is pending does not ask twice`() = runTest {
        vaultExpired()
        stubPrompt()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()
        // L'écran qui rappelle la confirmation, ou un appui sur Enregistrer, ne rouvre pas d'invite.
        confirmIdentity()
        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        verifyPromptShown(times = 1)
        assertEquals(HostSavePhase.PROMPT_SHOWN, viewModel.savePhase.value)
    }

    @Test
    fun `an invalid form leaves the save state idle and does not block a later save`() = runTest {
        recordStoredPasswords()
        coJustRun { hostRepository.save(any()) }
        val events = collectEvents()

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)

        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.save(any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
    }

    // ── Cas limites de la ré-authentification ───────────────────────────────

    @Test
    fun `confirmIdentityAndSave does nothing when no re-authentication was requested`() = runTest {
        confirmIdentity()

        verifyPromptShown(times = 0)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `without an activity the prompt cannot open and the vault unavailable message is shown`() = runTest {
        vaultExpired()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity(activity = null)

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        verifyPromptShown(times = 0)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a prompt that throws on opening shows the failure message instead of crashing`() = runTest {
        vaultExpired()
        every {
            biometricHelper.authenticate(
                activity = any(),
                title = any(),
                subtitle = any(),
                onSuccess = any(),
                onError = any(),
            )
        } throws IllegalStateException("activity destroyed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        confirmIdentity()

        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `dismissSaveError clears the message`() = runTest {
        every { vaultManager.storePassword(any(), any()) } throws IllegalStateException("keystore broke")
        fillNewPasswordHost()
        viewModel.saveHost()
        advanceUntilIdle()
        assertEquals(HostSaveError.VAULT_UNAVAILABLE, viewModel.saveError.value)

        viewModel.dismissSaveError()

        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `a key host saves normally even when the vault is expired`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.SSH_KEY, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        vaultExpired()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.update(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Panne hors vault : plus aucune exception ne sort de l'enregistrement ─

    @Test
    fun `a repository failure shows the save failed message, keeps the form and re-enables Save`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        val events = collectEvents()
        fillNewPasswordHost()
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertEquals(formBefore, viewModel.formState.value, "the form must stay intact")
        assertTrue(events.isEmpty())

        // Enregistrer est de nouveau utilisable, et le nouvel essai efface le message.
        coJustRun { hostRepository.save(any()) }
        viewModel.saveHost()
        advanceUntilIdle()

        assertNull(viewModel.saveError.value)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
    }

    @Test
    fun `a failing lookup of the existing host shows the save failed message`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        coEvery { hostRepository.getById("h1") } throws IllegalStateException("db closed")
        val formBefore = viewModel.formState.value

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertEquals(formBefore, viewModel.formState.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    @Test
    fun `a failure while repairing the credential id shows the save failed message`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        // La recopie du mot de passe lit la liste des hôtes : cette lecture échoue.
        every { hostRepository.observeAll() } returns flow { throw IllegalStateException("db closed") }

        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        coVerify(exactly = 0) { hostRepository.update(any()) }
    }

    // ── Relance sur le formulaire de l'appui ────────────────────────────────

    @Test
    fun `the retry saves the form of the tap even if the screen reset it meanwhile`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        val events = collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        // Activité recréée pendant l'invite : l'effet d'initialisation de l'écran rejoue resetForm().
        viewModel.resetForm()
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
        assertEquals(mapOf(hostSlot.captured.credentialId to "typed"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value, "a reset form must not turn the retry into a silent loss")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `the retry keeps the retyped password of an existing host reloaded meanwhile`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coJustRun { hostRepository.update(any()) }
        vaultExpiredForFirst(writes = 1)
        stubPrompt()
        val events = collectEvents()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()
        // Activité recréée : l'effet de l'écran recharge l'hôte depuis la base, sans le mot de passe retapé.
        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        assertEquals("", viewModel.formState.value.password)
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        coVerify(exactly = 1) { hostRepository.update(any()) }
        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords)
        assertEquals(listOf<HostEvent>(HostEvent.HostSaved), events)
        assertNull(viewModel.saveError.value)
    }

    @Test
    fun `the retry saves the form as of the tap, not later edits`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        collectEvents()
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()
        viewModel.updateForm { copy(label = "Edited later") }
        confirmIdentity()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("New Host", hostSlot.captured.label)
    }

    @Test
    fun `a second save after a cancelled prompt works on the form of its own tap`() = runTest {
        vaultExpiredForFirst(writes = 2)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        collectEvents()
        fillNewPasswordHost()

        saveUntilPromptShown()
        errorCallbacks.last()(BiometricPrompt.ERROR_USER_CANCELED, "cancelled")
        advanceUntilIdle()
        viewModel.updateForm { copy(label = "Second try") }
        saveUntilPromptShown()
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        val hostSlot = slot<Host>()
        coVerify(exactly = 1) { hostRepository.save(capture(hostSlot)) }
        assertEquals("Second try", hostSlot.captured.label, "the first tap's snapshot must not be reused")
    }

    // ── Slot de mot de passe orphelin ───────────────────────────────────────

    @Test
    fun `a host row failure on a new host deletes the password slot it just wrote`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        val writtenId = storedPasswords.keys.single()
        verify(exactly = 1) { vaultManager.deletePassword(any()) }
        coVerifyOrder {
            vaultManager.storePassword(writtenId, any())
            vaultManager.deletePassword(writtenId)
        }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a host row failure on an existing host never deletes its password slot`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "cred-h1")
        coEvery { hostRepository.update(any()) } throws IllegalStateException("db closed")
        recordStoredPasswords()

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.updateForm { copy(password = "retyped") }
        viewModel.saveHost()
        advanceUntilIdle()

        assertEquals(mapOf("cred-h1" to "retyped"), storedPasswords, "the password was written before the failure")
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
    }

    @Test
    fun `a host row failure on a new key host deletes nothing`() = runTest {
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        viewModel.updateForm {
            copy(
                label = "New Host",
                hostname = "new.example.com",
                username = "user",
                authType = AuthType.SSH_KEY,
                selectedKeyId = "key-2",
            )
        }

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        verify(exactly = 0) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
    }

    @Test
    fun `a failing cleanup of the orphan slot does not hide the save failure`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } throws IllegalStateException("keystore broke")
        coEvery { hostRepository.save(any()) } throws IllegalStateException("db closed")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.deletePassword(any()) }
        assertEquals(HostSaveError.SAVE_FAILED, viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancelled host row write on a new host still deletes the orphan slot`() = runTest {
        recordStoredPasswords()
        every { vaultManager.deletePassword(any()) } just Runs
        coEvery { hostRepository.save(any()) } throws CancellationException("cancelled")
        fillNewPasswordHost()

        viewModel.saveHost()
        advanceUntilIdle()

        verify(exactly = 1) { vaultManager.deletePassword(storedPasswords.keys.single()) }
        assertNull(viewModel.saveError.value, "a cancellation is not an error to show")
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    // ── Annulation pendant la recopie d'un mot de passe ─────────────────────

    @Test
    fun `a cancellation while reading the password to carry over is not swallowed`() = runTest {
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        every { vaultManager.getPassword("key-1") } throws CancellationException("cancelled")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        verify(exactly = 0) { vaultManager.storePassword(any(), any()) }
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
    }

    @Test
    fun `a cancellation while writing the carried over password is not swallowed`() = runTest {
        val existing = "kept".toCharArray()
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        every { vaultManager.getPassword("key-1") } returns existing
        every { vaultManager.storePassword(any(), any()) } throws CancellationException("cancelled")

        viewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        viewModel.saveHost()
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.update(any()) }
        assertNull(viewModel.saveError.value)
        assertEquals(HostSavePhase.IDLE, viewModel.savePhase.value)
        assertTrue(existing.all { it == '\u0000' }, "the password read must be wiped even on a cancellation")
    }

    // ── Fin de vie du ViewModel ─────────────────────────────────────────────

    /** Détruit [vm] comme le fait le framework quand son écran disparaît (onCleared est protégé). */
    private fun clearViewModel(vm: HostViewModel) {
        val store = ViewModelStore()
        ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
            },
        )[HostViewModel::class.java]
        store.clear()
    }

    @Test
    fun `onCleared drops the typed password`() = runTest {
        fillNewPasswordHost()
        assertEquals("typed", viewModel.formState.value.password)

        clearViewModel(viewModel)

        assertEquals(HostFormState(), viewModel.formState.value)
    }

    @Test
    fun `a prompt callback arriving after the ViewModel is cleared saves nothing`() = runTest {
        vaultExpiredForFirst(writes = 1)
        coJustRun { hostRepository.save(any()) }
        stubPrompt()
        fillNewPasswordHost()

        saveUntilPromptShown()
        clearViewModel(viewModel)
        successCallbacks.last()(mockk())
        advanceUntilIdle()

        coVerify(exactly = 0) { hostRepository.save(any()) }
        verify(exactly = 1) { vaultManager.storePassword(any(), any()) }
        assertEquals(HostFormState(), viewModel.formState.value)
    }

    // ── Écritures du vault hors du thread principal ─────────────────────────

    /** Dispatcher d'IO factice : exécute le bloc sur place et dit s'il est en train de le faire. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var running = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val previous = running
            running = true
            try {
                block.run()
            } finally {
                running = previous
            }
        }
    }

    @Test
    fun `the vault write runs on the injected IO dispatcher`() = runTest {
        val io = RecordingDispatcher()
        var wroteOnIo = false
        every { vaultManager.storePassword(any(), any()) } answers { wroteOnIo = io.running }
        coJustRun { hostRepository.save(any()) }
        val ioViewModel = newViewModel(io)

        ioViewModel.updateForm {
            copy(label = "New Host", hostname = "new.example.com", username = "user", password = "typed")
        }
        ioViewModel.saveHost()
        advanceUntilIdle()

        assertTrue(wroteOnIo, "storePassword must run inside the IO dispatcher")
        coVerify(exactly = 1) { hostRepository.save(any()) }
    }

    @Test
    fun `the carried over password is read and written on the injected IO dispatcher`() = runTest {
        val io = RecordingDispatcher()
        var readOnIo = false
        var wroteOnIo = false
        coEvery { hostRepository.getById("h1") } returns makeHostWith(AuthType.PASSWORD, "key-1")
        coEvery { sshKeyRepository.getById("key-1") } returns makeSshKey("key-1")
        coJustRun { hostRepository.update(any()) }
        every { vaultManager.getPassword("key-1") } answers {
            readOnIo = io.running
            "kept".toCharArray()
        }
        every { vaultManager.storePassword(any(), any()) } answers { wroteOnIo = io.running }
        val ioViewModel = newViewModel(io)

        ioViewModel.loadHostForEdit("h1")
        advanceUntilIdle()
        ioViewModel.saveHost()
        advanceUntilIdle()

        assertTrue(readOnIo, "getPassword must run inside the IO dispatcher")
        assertTrue(wroteOnIo, "storePassword must run inside the IO dispatcher")
    }
}
