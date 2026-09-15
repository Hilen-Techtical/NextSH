// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.shared.core.sync.EnrolledDevice
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceRepository
import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import fr.techtical.nextsh.shared.core.sync.Platform
import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [EnrolledDevicesViewModel] Desktop.
 *
 * Uses in-memory fakes, no MockK needed, mirrors the pattern from ConflictResolutionViewModelTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EnrolledDevicesViewModelTest {

    // ── Fakes ─────────────────────────────────────────────────────────────────

    private class FakeEnrolledDeviceRepository : EnrolledDeviceRepository {
        private val _list = MutableStateFlow(emptyList<EnrolledDevice>())
        val deleted = mutableListOf<String>()

        override fun observeAll(): Flow<List<EnrolledDevice>> = _list.asStateFlow()
        override suspend fun getAll(): List<EnrolledDevice> = _list.value
        override suspend fun getByDeviceId(deviceId: String): EnrolledDevice? =
            _list.value.firstOrNull { it.deviceId == deviceId }
        override suspend fun save(device: EnrolledDevice) {
            _list.value = _list.value + device
        }
        override suspend fun delete(deviceId: String) {
            deleted.add(deviceId)
            _list.value = _list.value.filter { it.deviceId != deviceId }
        }
        override suspend fun updateLastSyncAt(deviceId: String, timestamp: Long) = Unit
        override suspend fun updateLastKnownHost(deviceId: String, host: String) = Unit
    }

    private class FakeThrowingEnrolledDeviceRepository : EnrolledDeviceRepository {
        override fun observeAll(): Flow<List<EnrolledDevice>> = MutableStateFlow(emptyList<EnrolledDevice>()).asStateFlow()
        override suspend fun getAll(): List<EnrolledDevice> = emptyList()
        override suspend fun getByDeviceId(deviceId: String): EnrolledDevice? = null
        override suspend fun save(device: EnrolledDevice) = Unit
        override suspend fun delete(deviceId: String) {
            throw RuntimeException("DB write error")
        }
        override suspend fun updateLastSyncAt(deviceId: String, timestamp: Long) = Unit
        override suspend fun updateLastKnownHost(deviceId: String, host: String) = Unit
    }

    private class FakeVaultManager : VaultManager {
        private val passwordStore = mutableMapOf<String, CharArray>()
        private val keyStore = mutableMapOf<String, String>()
        private val certStore = mutableMapOf<String, String>()
        val deletedKeys = mutableListOf<String>()

        override suspend fun storePassword(credentialId: String, password: CharArray) {
            passwordStore[credentialId] = password
        }
        override suspend fun getPassword(credentialId: String): CharArray? = passwordStore[credentialId]
        override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) {
            keyStore[keyId] = privateKeyPem
        }
        override suspend fun getPrivateKey(keyId: String): String? = keyStore[keyId]
        override suspend fun storeCertificate(certId: String, certPem: String) {
            certStore[certId] = certPem
        }
        override suspend fun getCertificate(certId: String): String? = certStore[certId]
        override suspend fun deleteCertificate(certId: String) { certStore.remove(certId) }
        override suspend fun deleteCredential(credentialId: String) {
            deletedKeys.add(credentialId)
            passwordStore.remove(credentialId)
            keyStore.remove(credentialId)
            certStore.remove(credentialId)
        }
        override suspend fun wipeVault() {
            passwordStore.clear()
            keyStore.clear()
            certStore.clear()
        }
        override suspend fun isInitialized(): Boolean = true
        override suspend fun listStoredCredentialIds(): List<String> = passwordStore.keys.toList()
        override suspend fun listStoredKeyIds(): List<String> = keyStore.keys.toList()
        override suspend fun listStoredCertificateIds(): List<String> = certStore.keys.toList()
        override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) = Unit
        override suspend fun getKeyPassphrase(keyId: String): CharArray? = null
        override suspend fun deleteKeyPassphrase(keyId: String) = Unit
        override suspend fun listStoredKeyPassphraseIds(): List<String> = emptyList()
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    // ── Test data ─────────────────────────────────────────────────────────────

    private val testDevice = EnrolledDevice(
        deviceId = "device-desktop-test-001",
        deviceName = "Desktop Thibaud",
        platform = Platform.DESKTOP,
        publicKeyFingerprint = "fp-ecdh-desktop",
        tlsCertFingerprint = "abcdef1234567890",
        lastSyncAt = null,
        enrolledAt = 2_000_000L,
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * revoke must call secretStore.delete before enrolledDeviceRepository.delete,
     * then emit a Revoked event with the device name.
     *
     * Uses UnconfinedTestDispatcher so coroutines run synchronously, no need to advance time.
     */
    @Test
    fun `revoke deletes secret then device and emits Revoked event`() = runTest(UnconfinedTestDispatcher()) {
        val testScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

        val repo = FakeEnrolledDeviceRepository()
        repo.save(testDevice)
        val vaultManager = FakeVaultManager()
        val secretStore = EnrolledDeviceSecretStore(vaultManager)
        secretStore.store(testDevice.deviceId, ByteArray(32) { 0 })

        val viewModel = EnrolledDevicesViewModel(
            enrolledDeviceRepository = repo,
            secretStore = secretStore,
            appScope = TestAppScope(testScope),
        )

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = testScope.launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.revoke(testDevice)

        assertTrue(
            vaultManager.deletedKeys.contains("lan_sync_secret_${testDevice.deviceId}"),
            "Secret key should be deleted",
        )
        assertTrue(
            repo.deleted.contains(testDevice.deviceId),
            "Device row should be deleted",
        )
        val secretIndex = vaultManager.deletedKeys.indexOf("lan_sync_secret_${testDevice.deviceId}")
        val deviceIndex = repo.deleted.indexOf(testDevice.deviceId)
        assertTrue(secretIndex <= deviceIndex, "Secret must be deleted before device row")

        assertEquals(1, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.Revoked)
        assertEquals(
            testDevice.deviceName,
            (collectedEvents[0] as EnrolledDevicesViewModel.Event.Revoked).deviceName,
        )

        collector.cancel()
    }

    /**
     * When enrolledDeviceRepository.delete throws, viewModel must emit an Error event.
     */
    @Test
    fun `revoke emits Error event when device delete fails`() = runTest(UnconfinedTestDispatcher()) {
        val testScope = CoroutineScope(UnconfinedTestDispatcher() + SupervisorJob())

        val repo = FakeThrowingEnrolledDeviceRepository()
        val vaultManager = FakeVaultManager()
        val secretStore = EnrolledDeviceSecretStore(vaultManager)

        val viewModel = EnrolledDevicesViewModel(
            enrolledDeviceRepository = repo,
            secretStore = secretStore,
            appScope = TestAppScope(testScope),
        )

        val collectedEvents = mutableListOf<EnrolledDevicesViewModel.Event>()
        val collector = testScope.launch {
            viewModel.events.collect { collectedEvents.add(it) }
        }

        viewModel.revoke(testDevice)

        assertEquals(1, collectedEvents.size)
        assertTrue(collectedEvents[0] is EnrolledDevicesViewModel.Event.Error)

        collector.cancel()
    }
}
