// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import javax.inject.Inject
import javax.inject.Singleton
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException as SharedVaultAuthExpiredException

/**
 * Adapter that wraps the Android [VaultManager] (non-suspend, Android Keystore backed)
 * and implements the shared [fr.techtical.nextsh.shared.domain.vault.VaultManager] interface
 * (all methods suspend).
 *
 * Used by the shared code (GenerateSshKeyUseCase, EnrolledDeviceSecretStore, the credential sync,
 * the host import) via Hilt injection. The Android [VaultManager] itself is NOT changed so that
 * existing Android-only code (ViewModels, the app's own ConnectSessionUseCase) remains unaffected.
 *
 * Two classes are named `VaultAuthExpiredException`: the app's (thrown by the Android
 * [VaultManager] when the authentication window of the Keystore ran out) and the shared one. A
 * caller of the shared interface can only know the shared one, so every delegated call turns the
 * app's exception into the shared one. Without that, the exception went through the interface
 * under a type that no shared caller catches, and each of them had to catch every exception to
 * notice. The original exception stays as the cause, and its message (a fixed text, no secret) is
 * kept, as it is what the user reads when a caller shows it.
 */
@Singleton
class VaultManagerAdapter @Inject constructor(
    private val delegate: VaultManager,
) : fr.techtical.nextsh.shared.domain.vault.VaultManager {

    /** Runs a call of the Android vault, with its expired-authentication exception translated. */
    private inline fun <T> translateExpiry(call: () -> T): T = try {
        call()
    } catch (e: VaultAuthExpiredException) {
        throw SharedVaultAuthExpiredException(e.message ?: "Vault authentication expired")
            .also { it.initCause(e) }
    }

    override suspend fun storePassword(credentialId: String, password: CharArray) =
        translateExpiry { delegate.storePassword(credentialId, password) }

    override suspend fun getPassword(credentialId: String): CharArray? =
        translateExpiry { delegate.getPassword(credentialId) }

    override suspend fun deletePassword(credentialId: String) =
        translateExpiry { delegate.deletePassword(credentialId) }

    override suspend fun storePrivateKey(keyId: String, privateKeyPem: String) =
        translateExpiry { delegate.storePrivateKey(keyId, privateKeyPem) }

    override suspend fun getPrivateKey(keyId: String): String? =
        translateExpiry { delegate.getPrivateKey(keyId) }

    override suspend fun deletePrivateKey(keyId: String) =
        translateExpiry { delegate.deletePrivateKey(keyId) }

    override suspend fun storeCertificate(certId: String, certPem: String) =
        translateExpiry { delegate.storeCertificate(certId, certPem) }

    override suspend fun getCertificate(certId: String): String? =
        translateExpiry { delegate.getCertificate(certId) }

    override suspend fun deleteCertificate(certId: String) =
        translateExpiry { delegate.deleteCertificate(certId) }

    override suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray) =
        translateExpiry { delegate.storeKeyPassphrase(keyId, passphrase) }

    override suspend fun getKeyPassphrase(keyId: String): CharArray? =
        translateExpiry { delegate.getKeyPassphrase(keyId) }

    override suspend fun deleteKeyPassphrase(keyId: String) =
        translateExpiry { delegate.deleteKeyPassphrase(keyId) }

    override suspend fun listStoredKeyPassphraseIds(): List<String> =
        translateExpiry { delegate.listStoredKeyPassphraseIds() }

    override suspend fun wipeVault() =
        translateExpiry { delegate.wipeVault() }

    override suspend fun isInitialized(): Boolean =
        translateExpiry { delegate.isInitialized() }

    override suspend fun listStoredCredentialIds(): List<String> =
        translateExpiry { delegate.listStoredCredentialIds() }

    override suspend fun listStoredKeyIds(): List<String> =
        translateExpiry { delegate.listStoredKeyIds() }

    override suspend fun listStoredCertificateIds(): List<String> =
        translateExpiry { delegate.listStoredCertificateIds() }
}
