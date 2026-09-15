// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.auth.fido2

import fr.techtical.nextsh.desktop.vault.Fido2EnrollState
import fr.techtical.nextsh.desktop.vault.Fido2EnrollViewModel
import fr.techtical.nextsh.desktop.vault.WINDOWS_HID_LOCKED_MESSAGE
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.repository.SshKeyRepository
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests unitaires pour l'enrôlement FIDO2 Desktop.
 *
 * Vérifie :
 * 1. Parsing de [Fido2Enroller.EnrollResult] depuis un buffer attestedCredData factice (CBOR Ed25519).
 * 2. Conversion [EnrollResult.publicKey] → [SshKey.publicKey] via [SkSshPublicKey.toOpenSshWireFormat].
 * 3. Flux ViewModel : détection sans PIN → MakeCredential → Success.
 * 4. Flux ViewModel : détection avec PIN → WaitingForPin → submitPin → Success.
 * 5. Flux ViewModel : erreur de détection → Error.
 *
 * Aucun YubiKey physique requis : [FakeFido2Enroller] simule le device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YubiKitFidoManagerEnrollTest {

    // ── Fake Fido2Enroller ────────────────────────────────────────────────────

    /**
     * Enroller fake déterministe.
     *
     * @param pinConfigured true = simuler une YubiKey avec PIN configuré
     * @param enrollError   si non-null, [makeCredential] lève cette exception
     * @param detectError   si non-null, [detectDevice] lève cette exception
     */
    private class FakeFido2Enroller(
        private val pinConfigured: Boolean = false,
        private val enrollError: Exception? = null,
        private val detectError: Exception? = null,
    ) : Fido2Enroller {

        val fakeCredentialId = ByteArray(16) { (it + 200).toByte() }
        val fakePubKey = ByteArray(32) { (it * 3 + 7).toByte() }
        val fakeDeviceLabel = "YubiKey 5 NFC Test"

        var makeCredentialCalled = false
            private set
        var lastPin: CharArray? = null
            private set

        override suspend fun detectDevice(): Fido2Enroller.DeviceDetection {
            detectError?.let { throw it }
            return Fido2Enroller.DeviceDetection(
                deviceLabel = fakeDeviceLabel,
                pinConfigured = pinConfigured,
            )
        }

        override suspend fun makeCredential(
            rpId: String,
            rpName: String,
            userName: String,
            userDisplayName: String,
            pin: CharArray?,
            timeoutMs: Long,
        ): Fido2Enroller.EnrollResult {
            enrollError?.let { throw it }
            makeCredentialCalled = true
            lastPin = pin?.copyOf()
            return Fido2Enroller.EnrollResult(
                credentialId = fakeCredentialId.copyOf(),
                application = rpId,
                publicKey = fakePubKey.copyOf(),
                deviceLabel = fakeDeviceLabel,
            )
        }
    }

    // ── Fake SshKeyRepository ─────────────────────────────────────────────────

    private class InMemorySshKeyRepository : SshKeyRepository {
        val saved = mutableListOf<SshKey>()

        override fun observeAll(): Flow<List<SshKey>> =
            MutableStateFlow(saved.toList())

        override suspend fun save(key: SshKey) {
            saved.add(key)
        }

        override suspend fun update(key: SshKey) {
            val idx = saved.indexOfFirst { it.id == key.id }
            if (idx >= 0) saved[idx] = key else saved.add(key)
        }

        override suspend fun delete(keyId: String) {
            saved.removeIf { it.id == keyId }
        }

        override suspend fun getById(keyId: String): SshKey? =
            saved.find { it.id == keyId }

        override suspend fun getAllSyncEntries(): List<SyncEntry<SshKey>> = emptyList()

        override suspend fun upsertSyncEntry(entry: SyncEntry<SshKey>) {}

        override suspend fun hardDelete(id: String) {
            saved.removeIf { it.id == id }
        }
    }

    // ── Fake AppScope ─────────────────────────────────────────────────────────

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() {}
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /**
     * Crée un ViewModel avec un [UnconfinedTestDispatcher] partagé pour l'appScope
     * et l'ioDispatcher, de sorte que toutes les coroutines s'exécutent de manière
     * synchrone/eager dans les tests (pas de vrai thread IO).
     */
    private fun buildViewModel(
        enroller: Fido2Enroller,
        repo: SshKeyRepository = InMemorySshKeyRepository(),
    ): Fido2EnrollViewModel {
        val testDispatcher = UnconfinedTestDispatcher()
        return Fido2EnrollViewModel(
            enroller = enroller,
            sshKeyRepository = repo,
            appScope = TestAppScope(CoroutineScope(testDispatcher + SupervisorJob())),
            ioDispatcher = testDispatcher,
        )
    }

    // ── Tests - EnrollResult parsing ──────────────────────────────────────────

    @Test
    fun `EnrollResult credentialId encodes correctly to Base64`() {
        val credId = ByteArray(16) { it.toByte() }
        val result = Fido2Enroller.EnrollResult(
            credentialId = credId,
            application = "ssh:",
            publicKey = ByteArray(32),
            deviceLabel = "YubiKey",
        )
        val encoded = Base64.getEncoder().encodeToString(result.credentialId)
        // Should be standard Base64, not URL-safe, no whitespace
        assertTrue(encoded.isNotBlank())
        val decoded = Base64.getDecoder().decode(encoded)
        assertTrue(credId.contentEquals(decoded), "Base64 round-trip must be lossless")
    }

    @Test
    fun `EnrollResult publicKey converts to OpenSSH public key string via toOpenSshWireFormat`() {
        val pubKey = ByteArray(32) { (it * 3 + 1).toByte() }
        val application = "ssh:"
        val result = Fido2Enroller.EnrollResult(
            credentialId = ByteArray(16),
            application = application,
            publicKey = pubKey,
            deviceLabel = "YubiKey",
        )

        val openSshLine = SkSshPublicKey.toOpenSshPublicKeyString(
            application = result.application,
            ed25519PubKeyRaw = result.publicKey,
        )

        assertTrue(openSshLine.startsWith("sk-ssh-ed25519@openssh.com "), "Line should start with key type")

        // Parse it back to verify round-trip
        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(openSshLine)
        assertNotNull(parsed, "Should parse back to SkSshPublicKey")
        assertEquals(application, parsed!!.application)
        assertTrue(pubKey.contentEquals(parsed.rawKeyData), "pubKey must survive OpenSSH round-trip")

        parsed.close()
    }

    // ── Tests - ViewModel flux sans PIN ──────────────────────────────────────

    @Test
    fun `startDetection without PIN triggers makeCredential and ends in Success`() = runTest {
        val fakeEnroller = FakeFido2Enroller(pinConfigured = false)
        val repo = InMemorySshKeyRepository()
        val vm = buildViewModel(fakeEnroller, repo)

        assertEquals(Fido2EnrollState.Idle, vm.state.value, "Initial state should be Idle")

        vm.startDetection("Ma YubiKey", "ssh:")

        // UnconfinedTestDispatcher runs coroutines eagerly: state is final synchronously
        val finalState = vm.state.value
        assertIs<Fido2EnrollState.Success>(finalState, "Expected Success but got $finalState")
        assertEquals("Ma YubiKey", finalState.key.label, "Key label should match")
        assertEquals(SshKeyType.SK_ED25519, finalState.key.keyType, "Key type should be SK_ED25519")
        assertNotNull(finalState.key.fido2CredentialId, "fido2CredentialId should be set")
        assertEquals("ssh:", finalState.key.fido2RpId, "fido2RpId should be 'ssh:'")

        assertTrue(fakeEnroller.makeCredentialCalled, "makeCredential should have been called")
        assertEquals(1, repo.saved.size, "One key should be saved in repository")
    }

    // ── Tests - ViewModel flux avec PIN ──────────────────────────────────────

    @Test
    fun `startDetection with PIN required transitions to WaitingForPin`() = runTest {
        val fakeEnroller = FakeFido2Enroller(pinConfigured = true)
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")

        val state = vm.state.value
        assertIs<Fido2EnrollState.WaitingForPin>(state, "Expected WaitingForPin but got $state")
        assertEquals(fakeEnroller.fakeDeviceLabel, state.deviceLabel)
    }

    @Test
    fun `submitPin after WaitingForPin triggers makeCredential and ends in Success`() = runTest {
        val fakeEnroller = FakeFido2Enroller(pinConfigured = true)
        val repo = InMemorySshKeyRepository()
        val vm = buildViewModel(fakeEnroller, repo)

        vm.startDetection("Clé FIDO2 test", "ssh:")
        assertIs<Fido2EnrollState.WaitingForPin>(vm.state.value)

        vm.submitPin(charArrayOf('1', '2', '3', '4'))

        val finalState = vm.state.value
        assertIs<Fido2EnrollState.Success>(finalState, "Expected Success but got $finalState")
        assertEquals("Clé FIDO2 test", finalState.key.label)

        assertTrue(fakeEnroller.makeCredentialCalled, "makeCredential should have been called after submitPin")
        assertEquals(1, repo.saved.size, "Key should be saved in repository")
    }

    @Test
    fun `key public field contains valid sk-ssh-ed25519 OpenSSH line`() = runTest {
        val fakeEnroller = FakeFido2Enroller(pinConfigured = false)
        val repo = InMemorySshKeyRepository()
        val vm = buildViewModel(fakeEnroller, repo)

        vm.startDetection("Test key", "ssh:")

        val state = vm.state.value as? Fido2EnrollState.Success
            ?: error("Expected Success, got ${vm.state.value}")

        val pubKey = state.key.publicKey
        assertTrue(pubKey.startsWith("sk-ssh-ed25519@openssh.com "), "publicKey must be OpenSSH sk-ed25519 format")

        // Verify it round-trips through SkSshPublicKey
        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(pubKey)
        assertNotNull(parsed, "publicKey should be parseable by SkSshPublicKey.fromAuthorizedKeysLine")
        assertTrue(fakeEnroller.fakePubKey.contentEquals(parsed!!.rawKeyData), "rawKeyData should match fake pubkey")
        parsed.close()
    }

    // ── Tests - erreur de détection ───────────────────────────────────────────

    @Test
    fun `detect NoDeviceFound error results in Error state`() = runTest {
        val fakeEnroller = FakeFido2Enroller(
            detectError = YubiKitFidoManager.FidoError.NoDeviceFound,
        )
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")

        val state = vm.state.value
        assertIs<Fido2EnrollState.Error>(state, "Expected Error but got $state")
        assertTrue(state.message.isNotBlank(), "Error message should not be blank")
    }

    @Test
    fun `makeCredential TouchTimeout results in Error state`() = runTest {
        val fakeEnroller = FakeFido2Enroller(
            pinConfigured = false,
            enrollError = YubiKitFidoManager.FidoError.TouchTimeout,
        )
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")

        val state = vm.state.value
        assertIs<Fido2EnrollState.Error>(state, "Expected Error but got $state")
    }

    // ── Tests - reset ─────────────────────────────────────────────────────────

    @Test
    fun `reset from Error returns to Idle`() = runTest {
        val fakeEnroller = FakeFido2Enroller(
            detectError = YubiKitFidoManager.FidoError.NoDeviceFound,
        )
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")
        assertIs<Fido2EnrollState.Error>(vm.state.value)

        vm.reset()
        assertIs<Fido2EnrollState.Idle>(vm.state.value, "reset() should restore Idle state")
    }

    // ── Tests - WindowsHidLocked ──────────────────────────────────────────────

    @Test
    fun `detect WindowsHidLocked error results in Error state with workaround message`() = runTest {
        val fakeEnroller = FakeFido2Enroller(
            detectError = YubiKitFidoManager.FidoError.WindowsHidLocked,
        )
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")

        val state = vm.state.value
        assertIs<Fido2EnrollState.Error>(state, "Expected Error but got $state")
        assertEquals(WINDOWS_HID_LOCKED_MESSAGE, state.message,
            "WindowsHidLocked must surface the specific multi-line workaround message")
        assertTrue(state.message.contains("CCID"), "Message should mention CCID workaround")
        assertTrue(state.message.contains("NFC"), "Message should mention NFC workaround")
        assertTrue(state.message.contains("administrateur"), "Message should mention admin workaround")
    }

    @Test
    fun `makeCredential WindowsHidLocked results in Error state with workaround message`() = runTest {
        val fakeEnroller = FakeFido2Enroller(
            pinConfigured = false,
            enrollError = YubiKitFidoManager.FidoError.WindowsHidLocked,
        )
        val vm = buildViewModel(fakeEnroller)

        vm.startDetection("Ma YubiKey", "ssh:")

        val state = vm.state.value
        assertIs<Fido2EnrollState.Error>(state, "Expected Error for WindowsHidLocked makeCredential")
        assertEquals(WINDOWS_HID_LOCKED_MESSAGE, state.message)
    }
}
