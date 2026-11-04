// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.vault

interface VaultManager {
    suspend fun storePassword(credentialId: String, password: CharArray)
    suspend fun getPassword(credentialId: String): CharArray?

    /**
     * Supprime UNIQUEMENT le mot de passe stocké sous [credentialId]. La clé privée,
     * son certificat et sa passphrase rangés sous le même id ne sont pas touchés : un
     * hôte passé de la clé au mot de passe garde l'id de la clé comme credentialId.
     */
    suspend fun deletePassword(credentialId: String)

    suspend fun storePrivateKey(keyId: String, privateKeyPem: String)
    suspend fun getPrivateKey(keyId: String): String?

    /**
     * Supprime UNIQUEMENT la clé privée stockée sous [keyId]. Son certificat
     * ([deleteCertificate]), sa passphrase ([deleteKeyPassphrase]) et un éventuel mot
     * de passe rangé sous le même id ne sont pas touchés : l'appelant qui supprime une
     * clé du vault appelle lui-même les suppressions typées qui lui appartiennent.
     */
    suspend fun deletePrivateKey(keyId: String)

    suspend fun storeCertificate(certId: String, certPem: String)
    suspend fun getCertificate(certId: String): String?

    /**
     * Supprime UNIQUEMENT l'entrée certificat associée à [certId], sans toucher
     * à la clé SSH privée, au mot de passe ou à la passphrase stockés sous le même id.
     *
     * Le certificat est rangé sous l'id de la CLÉ (pas de l'hôte) et il est partagé par
     * tous les hôtes CERTIFICATE qui utilisent cette clé : il appartient à la clé. À
     * n'appeler qu'avec la suppression de la clé elle-même (ou sur ordre de la
     * synchronisation), jamais à la suppression d'un hôte.
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

    suspend fun wipeVault()
    suspend fun isInitialized(): Boolean
    suspend fun listStoredCredentialIds(): List<String>
    suspend fun listStoredKeyIds(): List<String>
    suspend fun listStoredCertificateIds(): List<String>
}

class VaultAuthExpiredException(message: String = "Vault authentication expired") : Exception(message)
