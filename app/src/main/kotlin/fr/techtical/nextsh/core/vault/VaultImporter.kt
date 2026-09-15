// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Importe un backup .nextsh.
 * Déchiffre avec la passphrase et restaure toutes les données.
 *
 * Format binaire attendu :
 * [SALT_LEN(4 bytes)][SALT][ENCRYPTED_JSON]
 */
@Singleton
class VaultImporter @Inject constructor(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val cryptoUtils: CryptoUtils,
) {

    /**
     * Importe un backup .nextsh.
     * La passphrase est effacée de la mémoire en fin d'appel.
     *
     * @return Result<Int> : nombre total d'éléments importés, ou échec avec exception
     */
    suspend fun import(data: ByteArray, passphrase: CharArray): Result<Int> {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        return try {
            Timber.i("VaultImporter: starting import (${data.size} bytes)")

            if (data.size < 4) {
                return Result.failure(IllegalArgumentException("Fichier .nextsh invalide ou corrompu"))
            }

            // Lire la longueur du sel
            val saltLen = ((data[0].toInt() and 0xFF) shl 24) or
                          ((data[1].toInt() and 0xFF) shl 16) or
                          ((data[2].toInt() and 0xFF) shl  8) or
                           (data[3].toInt() and 0xFF)

            if (saltLen <= 0 || 4 + saltLen >= data.size) {
                return Result.failure(IllegalArgumentException("Fichier .nextsh invalide ou corrompu"))
            }

            val salt      = data.sliceArray(4 until (4 + saltLen))
            val encrypted = data.sliceArray((4 + saltLen) until data.size)

            // Dériver la clé AES depuis la passphrase
            derivedKey = cryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            // SECURITY: jsonBytes contient le vault déchiffré, wipé dans le finally block
            jsonBytes = cryptoUtils.decryptAesGcm(encrypted, derivedKey)
            val json = jsonBytes.toString(Charsets.UTF_8)

            // Lenient decoder: accept unknown top-level keys so a future schema
            // field added by Desktop doesn't break Android import.
            val backup = Json { ignoreUnknownKeys = true }
                .decodeFromString<VaultBackup>(json)

            if (backup.version != 1) {
                return Result.failure(IllegalArgumentException("Version de backup non supportée : ${backup.version}"))
            }

            // Restaurer les hôtes
            backup.hosts.forEach { host -> hostRepository.save(host) }

            // Restaurer les clés SSH (metadata)
            backup.sshKeys.forEach { key -> sshKeyRepository.save(key) }

            // Restaurer les tunnels
            backup.tunnels.forEach { tunnel -> tunnelRepository.save(tunnel) }

            // Restaurer les credentials dans le vault. Ignorer tout secret de sync
            // LAN qu'un backup pré-correctif pourrait encore contenir : c'est une clé
            // d'authentification locale à l'appareil, à ne pas importer sur une autre machine.
            backup.credentials.forEach { (credentialId, password) ->
                if (credentialId.startsWith(LAN_SYNC_SECRET_KEY_PREFIX)) return@forEach
                vaultManager.storePassword(credentialId, password.toCharArray())
            }

            // Restaurer les clés privées dans le vault
            backup.privateKeys.forEach { (keyId, pem) ->
                vaultManager.storePrivateKey(keyId, pem)
            }

            // Restaurer les passphrases de clés privées chiffrées. Sans ceci une
            // clé chiffrée importée serait inutilisable (cf. Wave 2 passphrase).
            backup.keyPassphrases.forEach { (keyId, pass) ->
                vaultManager.storeKeyPassphrase(keyId, pass.toCharArray())
            }

            val restoredCredentials = backup.credentials.count { !it.key.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
            val total = backup.hosts.size + backup.sshKeys.size + backup.tunnels.size +
                        restoredCredentials + backup.privateKeys.size +
                        backup.keyPassphrases.size

            Timber.i("VaultImporter: import complete ($total items restored)")
            Result.success(total)

        } catch (e: VaultAuthExpiredException) {
            throw e  // propagate, UI must handle re-auth
        } catch (e: Exception) {
            Timber.e(e, "VaultImporter: import failed")
            Result.failure(e)
        } finally {
            derivedKey?.let { cryptoUtils.secureWipe(it) }
            jsonBytes?.let { cryptoUtils.secureWipe(it) }
            passphrase.fill('\u0000')
        }
    }
}
