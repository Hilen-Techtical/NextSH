// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class EnrolledDeviceSecretStoreTest {

    private class FakeVaultManager : VaultManager {
        val passwords = mutableMapOf<String, CharArray>()

        override suspend fun storePassword(credentialId: String, password: CharArray) {
            passwords[credentialId] = password.copyOf()
        }

        override suspend fun getPassword(credentialId: String): CharArray? =
            passwords[credentialId]?.copyOf()

        override suspend fun deleteCredential(credentialId: String) {
            passwords.remove(credentialId)
        }

        override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) = Unit
        override suspend fun getPrivateKey(keyId: String): String? = null
        override suspend fun storeCertificate(certId: String, certPem: String) = Unit
        override suspend fun getCertificate(certId: String): String? = null
        override suspend fun deleteCertificate(certId: String) = Unit
        override suspend fun wipeVault() { passwords.clear() }
        override suspend fun isInitialized(): Boolean = true
        override suspend fun listStoredCredentialIds(): List<String> = passwords.keys.toList()
        override suspend fun listStoredKeyIds(): List<String> = emptyList()
        override suspend fun listStoredCertificateIds(): List<String> = emptyList()
        override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) = Unit
        override suspend fun getKeyPassphrase(keyId: String): CharArray? = null
        override suspend fun deleteKeyPassphrase(keyId: String) = Unit
        override suspend fun listStoredKeyPassphraseIds(): List<String> = emptyList()
    }

    @Test
    fun `store then retrieve returns same bytes`() = runTest {
        val vault = FakeVaultManager()
        val store = EnrolledDeviceSecretStore(vault)
        val deviceId = "device-abc"
        val secret = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)

        store.store(deviceId, secret)
        val retrieved = store.retrieve(deviceId)

        assertContentEquals(secret, retrieved)
        retrieved?.fill(0)
    }

    @Test
    fun `retrieve returns null when device not stored`() = runTest {
        val vault = FakeVaultManager()
        val store = EnrolledDeviceSecretStore(vault)

        val result = store.retrieve("unknown-device")

        assertNull(result)
    }

    @Test
    fun `delete removes stored secret`() = runTest {
        val vault = FakeVaultManager()
        val store = EnrolledDeviceSecretStore(vault)
        val deviceId = "device-xyz"
        val secret = byteArrayOf(0x10, 0x20, 0x30)

        store.store(deviceId, secret)
        store.delete(deviceId)
        val retrieved = store.retrieve(deviceId)

        assertNull(retrieved)
    }
}
