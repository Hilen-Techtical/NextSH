// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.vault

interface VaultManager {
    suspend fun storePassword(credentialId: String, password: CharArray)
    suspend fun getPassword(credentialId: String): CharArray?
    suspend fun storePrivateKey(keyId: String, privateKeyPem: String)
    suspend fun getPrivateKey(keyId: String): String?
    suspend fun storeCertificate(certId: String, certPem: String)
    suspend fun getCertificate(certId: String): String?

    /**
     * Supprime UNIQUEMENT l'entrée certificat associée à [certId], sans toucher
     * à la clé SSH privée, au mot de passe ou à la passphrase stockés sous le même id.
     * Utilisé par les ViewModels qui suppriment un hôte CERTIFICATE dont la clé SSH
     * peut être partagée avec d'autres hôtes : seul le certificat par-hôte doit être
     * purgé, pas la clé privée partagée.
     */
    suspend fun deleteCertificate(certId: String)

    /**
     * Passphrase protecting an encrypted SSH private key. Stored under the same
     * [keyId] as the private key. Keys without a passphrase must not have an
     * entry here: callers distinguish "no passphrase needed" from "stored
     * passphrase empty" by the absence of a stored entry.
     *
     * SECURITY: the passphrase is an AES-GCM blob inside the vault, same
     * guarantees as passwords. Callers MUST wipe the returned CharArray after
     * use (via [Arrays.fill] or equivalent).
     */
    suspend fun storeKeyPassphrase(keyId: String, passphrase: CharArray)
    suspend fun getKeyPassphrase(keyId: String): CharArray?
    suspend fun deleteKeyPassphrase(keyId: String)
    suspend fun listStoredKeyPassphraseIds(): List<String>

    suspend fun deleteCredential(credentialId: String)
    suspend fun wipeVault()
    suspend fun isInitialized(): Boolean
    suspend fun listStoredCredentialIds(): List<String>
    suspend fun listStoredKeyIds(): List<String>
    suspend fun listStoredCertificateIds(): List<String>
}

class VaultAuthExpiredException(message: String = "Vault authentication expired") : Exception(message)
