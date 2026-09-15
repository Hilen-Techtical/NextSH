// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter that wraps the Android [VaultManager] (non-suspend, Android Keystore backed)
 * and implements the shared [fr.techtical.nextsh.shared.domain.vault.VaultManager] interface
 * (all methods suspend).
 *
 * Used by the shared use cases (GenerateSshKeyUseCase, ConnectSessionUseCase in :shared, etc.)
 * via Hilt injection. The Android [VaultManager] itself is NOT changed so that existing
 * Android-only code (ViewModels, ConnectSessionUseCase Android wrapper) remains unaffected.
 */
@Singleton
class VaultManagerAdapter @Inject constructor(
    private val delegate: VaultManager,
) : fr.techtical.nextsh.shared.domain.vault.VaultManager {

    override suspend fun storePassword(credentialId: String, password: CharArray) =
        delegate.storePassword(credentialId, password)

    override suspend fun getPassword(credentialId: String): CharArray? =
        delegate.getPassword(credentialId)

    override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) =
        delegate.storePrivateKey(keyId, privateKeyPem)

    override suspend fun getPrivateKey(keyId: String): String? =
        delegate.getPrivateKey(keyId)

    override suspend fun storeCertificate(certId: String, certPem: String) =
        delegate.storeCertificate(certId, certPem)

    override suspend fun getCertificate(certId: String): String? =
        delegate.getCertificate(certId)

    override suspend fun deleteCertificate(certId: String) =
        delegate.deleteCertificate(certId)

    override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) =
        delegate.storeKeyPassphrase(keyId, passphrase)

    override suspend fun getKeyPassphrase(keyId: String): CharArray? =
        delegate.getKeyPassphrase(keyId)

    override suspend fun deleteKeyPassphrase(keyId: String) =
        delegate.deleteKeyPassphrase(keyId)

    override suspend fun listStoredKeyPassphraseIds(): List<String> =
        delegate.listStoredKeyPassphraseIds()

    override suspend fun deleteCredential(credentialId: String) {
        // The shared contract uses a single "delete by id" for tombstoning across
        // all credential types. Android's vault stores each kind under a different
        // prefix; remove from all three (plus key passphrase) so the tombstone
        // propagates whatever type was actually stored.
        delegate.deletePassword(credentialId)
        delegate.deletePrivateKey(credentialId)
        delegate.deleteCertificate(credentialId)
        delegate.deleteKeyPassphrase(credentialId)
    }

    override suspend fun wipeVault() =
        delegate.wipeVault()

    override suspend fun isInitialized(): Boolean =
        delegate.isInitialized()

    override suspend fun listStoredCredentialIds(): List<String> =
        delegate.listStoredCredentialIds()

    override suspend fun listStoredKeyIds(): List<String> =
        delegate.listStoredKeyIds()

    override suspend fun listStoredCertificateIds(): List<String> =
        delegate.listStoredCertificateIds()
}
