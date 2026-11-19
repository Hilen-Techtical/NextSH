// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.BuildConfig
import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.domain.repository.CustomTerminalThemeRepository
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SnippetRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupCustomTheme
import fr.techtical.nextsh.shared.core.backup.BackupDocument
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupKnownHost
import fr.techtical.nextsh.shared.core.backup.BackupPlatform
import fr.techtical.nextsh.shared.core.backup.BackupProducer
import fr.techtical.nextsh.shared.core.backup.BackupSnippet
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exporte le vault au format .nextsh v2 (JSON chiffré AES-256-GCM).
 *
 * Le document JSON est le [BackupDocument] partagé avec Desktop (`:shared`), encodé par
 * [BackupCodec] qui écrit toujours `"version":2`. Sections : hôtes, clés SSH, tunnels, mots de passe,
 * clés privées, passphrases de clés, certificats, snippets, thèmes personnalisés, known hosts (forme
 * portable) et la liste blanche des réglages (`common` et `android`).
 *
 * Jamais exportés : le secret de sync LAN, les réglages de sécurité (verrouillage automatique,
 * effacement du presse-papiers), la sync et l'onboarding.
 *
 * Format binaire du fichier, posé par [BackupEnvelope] :
 * [SALT_LEN(4 BE)][SALT(32)][IV_LEN(1)][IV(12)][CIPHERTEXT||TAG]
 */
@Singleton
class VaultExporter @Inject constructor(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val snippetRepository: SnippetRepository,
    private val customTerminalThemeRepository: CustomTerminalThemeRepository,
    private val knownHostsVerifier: KnownHostsVerifier,
    private val settingsDataStore: SettingsDataStore,
    private val cryptoUtils: CryptoUtils,
) {

    /**
     * Exporte tout le vault en format .nextsh (JSON chiffré AES-256-GCM).
     * La passphrase est effacée de la mémoire en fin d'appel.
     *
     * @return le fichier .nextsh prêt à être écrit sur disque, et le nombre d éléments que l import refuserait
     */
    suspend fun export(passphrase: CharArray): VaultExport {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        try {
            Timber.i("VaultExporter: starting export")

            val hosts   = hostRepository.observeAll().first()
            val sshKeys = sshKeyRepository.observeAll().first()
            val tunnels = tunnelRepository.observeAll().first()

            // Récupérer les credentials (mots de passe). Exclure les secrets de sync
            // LAN (préfixe lan_sync_secret_) : ce sont des clés d'authentification
            // locales à l'appareil qui ne doivent jamais quitter la machine, même
            // dans un backup chiffré.
            val credentialIds = vaultManager.listStoredCredentialIds()
                .filterNot { it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }
            val credentials = credentialIds.mapNotNull { id ->
                val pwd = vaultManager.getPassword(id)
                if (pwd != null) {
                    val encoded = String(pwd)
                    pwd.fill('\u0000')
                    id to encoded
                } else null
            }.toMap()

            // Récupérer les clés privées PEM
            val keyIds = vaultManager.listStoredKeyIds()
            val privateKeys = keyIds.mapNotNull { id ->
                vaultManager.getPrivateKey(id)?.let { pem -> id to pem }
            }.toMap()

            // Passphrases pour clés privées chiffrées : MUST be exported, sinon une
            // clé importée avec passphrase sur un autre peer est inutilisable.
            val passphraseIds = vaultManager.listStoredKeyPassphraseIds()
            val keyPassphrases = passphraseIds.mapNotNull { id ->
                val chars = vaultManager.getKeyPassphrase(id) ?: return@mapNotNull null
                val encoded = String(chars)
                chars.fill('\u0000')
                id to encoded
            }.toMap()

            // Certificats SSH : même traitement que les clés privées (un hôte CERTIFICATE restauré
            // sans son certificat serait inutilisable).
            val certificates = vaultManager.listStoredCertificateIds().mapNotNull { id ->
                vaultManager.getCertificate(id)?.let { pem -> id to pem }
            }.toMap()

            val snippets = snippetRepository.observeAll().first().map {
                BackupSnippet(
                    id = it.id,
                    label = it.label,
                    command = it.command,
                    category = it.category,
                    hostId = it.hostId,
                    createdAt = it.createdAt,
                )
            }
            val customThemes = customTerminalThemeRepository.observeAll().first().map {
                BackupCustomTheme(
                    id = it.id,
                    name = it.name,
                    background = it.background,
                    foreground = it.foreground,
                    cursor = it.cursor,
                    selectionBg = it.selectionBg,
                    ansi = it.ansi,
                    createdAt = it.createdAt,
                )
            }

            // Known hosts en forme portable (type SSH + blob). Une clé qui ne se reconvertit pas est
            // omise et comptée. Android ne garde pas de date d'ajout : addedAt reste à 0.
            var knownHostsSkipped = 0
            val knownHosts = knownHostsVerifier.exportEntries().mapNotNull { entry ->
                val portable = KnownHostPortable.toPortable(entry.algorithm, entry.encoded)
                if (portable == null) {
                    knownHostsSkipped++
                    null
                } else {
                    BackupKnownHost(
                        hostPort = entry.hostPort,
                        keyType = portable.keyType,
                        publicKey = portable.publicKey,
                    )
                }
            }

            val document = BackupDocument(
                exportedAt = System.currentTimeMillis(),
                hosts = hosts,
                sshKeys = sshKeys,
                tunnels = tunnels,
                credentials = credentials,
                privateKeys = privateKeys,
                keyPassphrases = keyPassphrases,
                certificates = certificates,
                snippets = snippets,
                customThemes = customThemes,
                knownHosts = knownHosts,
                settings = settingsDataStore.backupSettings(),
                producer = BackupProducer(BackupPlatform.ANDROID.name, BuildConfig.VERSION_NAME),
            )

            // Éléments que l'import refuserait (hôtes, clés, tunnels, secrets, snippets, thèmes, ...) :
            // exportés quand même, l'UI le signale. Même validateur que l'import, donc aucun écart
            // ni double comptage (un tunnel écarté par la cascade d'un hôte invalide compte une fois).
            val notRestorable = when (val checked = BackupValidator.validate(document)) {
                is BackupValidationResult.Valid -> checked.backup.invalidTotal
                BackupValidationResult.Malformed -> 0
            }

            // SECURITY: jsonBytes contient le vault en clair, wipé dans le finally block
            jsonBytes = BackupCodec.encodeToBytes(document)

            // Dériver la clé AES depuis la passphrase
            val salt = cryptoUtils.generateSalt(BackupEnvelope.DEFAULT_SALT_BYTES)
            derivedKey = cryptoUtils.deriveKeyFromPassphrase(passphrase, salt)

            // [IV_LEN(1)][IV][CIPHERTEXT||TAG], puis le cadre [SALT_LEN(4)][SALT] devant.
            val sealed = cryptoUtils.encryptAesGcm(jsonBytes, derivedKey)
            val file = BackupEnvelope.pack(salt, sealed)

            Timber.i(
                "VaultExporter: export complete (${hosts.size} hosts, ${sshKeys.size} keys, ${tunnels.size} tunnels, " +
                    "${snippets.size} snippets, ${customThemes.size} themes, ${knownHosts.size} known hosts, " +
                    "$knownHostsSkipped known hosts skipped, $notRestorable not restorable)",
            )
            return VaultExport(file, notRestorable)

        } finally {
            derivedKey?.let { cryptoUtils.secureWipe(it) }
            jsonBytes?.let { cryptoUtils.secureWipe(it) }
            passphrase.fill('\u0000')
        }
    }
}
