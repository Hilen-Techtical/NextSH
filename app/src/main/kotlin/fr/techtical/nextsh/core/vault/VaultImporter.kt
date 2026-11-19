// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.core.crypto.CryptoUtils
import fr.techtical.nextsh.core.di.DefaultDispatcher
import fr.techtical.nextsh.core.ssh.KnownHostCandidate
import fr.techtical.nextsh.core.ssh.KnownHostsVerifier
import fr.techtical.nextsh.data.db.TransactionRunner
import fr.techtical.nextsh.data.preferences.SettingsDataStore
import fr.techtical.nextsh.data.repository.BackupRestoreStore
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.SshKeyRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.core.backup.BackupCodec
import fr.techtical.nextsh.shared.core.backup.BackupDecodeResult
import fr.techtical.nextsh.shared.core.backup.BackupDocument
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.core.backup.BackupImportReport
import fr.techtical.nextsh.shared.core.backup.BackupKeyRules
import fr.techtical.nextsh.shared.core.backup.LocalPrivateKey
import fr.techtical.nextsh.shared.core.backup.SshKeyRestoreDecision
import fr.techtical.nextsh.shared.core.ssh.PublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.memoizedWithoutPassphrase
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDeriver
import fr.techtical.nextsh.shared.core.ssh.SshPublicKeyDerivation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import fr.techtical.nextsh.shared.core.backup.BackupRestoreStep
import fr.techtical.nextsh.shared.core.backup.BackupTrustRules
import fr.techtical.nextsh.shared.core.backup.BackupValidationResult
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import fr.techtical.nextsh.shared.core.backup.KnownHostPortable
import fr.techtical.nextsh.shared.core.sync.LAN_SYNC_SECRET_KEY_PREFIX
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Un backup .nextsh qu'on n'a pas pu lire. Le message est un texte fixe (le nom du motif) et
 * l'exception n'a jamais de cause : le JSON déchiffré peut contenir des mots de passe et les
 * messages des exceptions de décodage le recopient. L'UI traduit [reason] en message localisé.
 */
class VaultBackupReadException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Le déchiffrement authentifié a échoué : passphrase incorrecte, ou fichier altéré. */
        WRONG_PASSPHRASE,

        /**
         * Ce n'est pas un backup lisible : conteneur trop court, sel ou IV de taille impossible,
         * ou déchiffré mais le contenu n'est pas un JSON de backup valide.
         */
        MALFORMED,

        /** Un backup valide, mais d'une version de format que cette application ne connaît pas. */
        UNSUPPORTED_VERSION,
    }
}

/**
 * Importe un backup .nextsh (formats 1 et 2).
 * Déchiffre avec la passphrase et fusionne les données dans le vault existant : le backup
 * l'emporte sur un élément existant (même identifiant), les éléments locaux absents du backup sont
 * conservés. Exceptions : les known hosts sont importés en INSERTION SEULE (une clé locale
 * différente est conservée et signalée) et les réglages sont limités à la liste blanche
 * (`common` et `android`), jamais les réglages de sécurité.
 *
 * Format binaire attendu (voir [BackupEnvelope]) :
 * [SALT_LEN(4 BE)][SALT][IV_LEN(1)][IV][CIPHERTEXT||TAG]
 *
 * Ordre : déchiffrement, décodage, validation de tout le document (AVANT toute écriture), contrôle de
 * la fenêtre du vault, une transaction pour la base (thèmes, snippets, hôtes, clés, tunnels), puis
 * les secrets du vault, les known hosts et les réglages.
 */
@Singleton
class VaultImporter @Inject constructor(
    private val vaultManager: VaultManager,
    private val sshKeyRepository: SshKeyRepository,
    private val hostRepository: HostRepository,
    private val tunnelRepository: TunnelRepository,
    private val backupRestoreStore: BackupRestoreStore,
    private val knownHostsVerifier: KnownHostsVerifier,
    private val settingsDataStore: SettingsDataStore,
    private val cryptoUtils: CryptoUtils,
    private val transactionRunner: TransactionRunner,
    private val publicKeyDeriver: PublicKeyDeriver,
    @DefaultDispatcher private val derivationDispatcher: CoroutineDispatcher,
    /** The wipe of the vault: a restore writes nothing while the app is closed, see [ensureOpen]. */
    private val wipeGate: VaultWipeGate,
) {

    /**
     * For the tests: the shared deriver, the default dispatcher and a wipe gate of its own unless given.
     * Not an injection point (no default value in an injected constructor).
     */
    constructor(
        vaultManager: VaultManager,
        sshKeyRepository: SshKeyRepository,
        hostRepository: HostRepository,
        tunnelRepository: TunnelRepository,
        backupRestoreStore: BackupRestoreStore,
        knownHostsVerifier: KnownHostsVerifier,
        settingsDataStore: SettingsDataStore,
        cryptoUtils: CryptoUtils,
        transactionRunner: TransactionRunner,
        wipeGate: VaultWipeGate = VaultWipeGate(),
        publicKeyDeriver: PublicKeyDeriver = SshPublicKeyDeriver,
        derivationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(
        vaultManager, sshKeyRepository, hostRepository, tunnelRepository, backupRestoreStore,
        knownHostsVerifier, settingsDataStore, cryptoUtils, transactionRunner,
        publicKeyDeriver, derivationDispatcher, wipeGate,
    )

    /** Refuses to write while the wipe of the vault has the app closed (fixed text). */
    private fun ensureOpen() {
        if (wipeGate.isClosed()) throw java.io.IOException("The vault is being wiped")
    }

    /**
     * Importe un backup .nextsh.
     * La passphrase est effacée de la mémoire en fin d'appel.
     *
     * @return Result<BackupImportReport> : bilan de l'import, ou échec avec exception
     */
    suspend fun import(data: ByteArray, passphrase: CharArray): Result<BackupImportReport> {
        var derivedKey: ByteArray? = null
        var jsonBytes: ByteArray? = null
        var unpacked: BackupEnvelope.Unpacked? = null
        return try {
            ensureOpen()
            Timber.i("VaultImporter: starting import (${data.size} bytes)")

            // Taille, longueur du sel et en-tête d'IV : une seule règle, celle de Desktop aussi.
            unpacked = BackupEnvelope.unpack(data)
                ?: throw VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED)

            // Dériver la clé AES depuis la passphrase
            derivedKey = cryptoUtils.deriveKeyFromPassphrase(passphrase, unpacked.salt)

            // SECURITY: jsonBytes contient le vault déchiffré, wipé dans le finally block.
            // Un échec de déchiffrement authentifié (GCM) signifie passphrase incorrecte ou
            // fichier altéré. Les exceptions de la couche crypto ne sont pas relayées telles
            // quelles : l'échec porte un motif fixe, sans message ni cause venus du fichier.
            jsonBytes = try {
                cryptoUtils.decryptAesGcm(unpacked.sealed, derivedKey)
            } catch (e: GeneralSecurityException) {
                throw VaultBackupReadException(VaultBackupReadException.Reason.WRONG_PASSPHRASE)
            } catch (e: IllegalArgumentException) {
                // Conteneur chiffré tronqué ou mal formé (taille d'IV invalide...).
                throw VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED)
            }

            // SECURITY: le décodeur partagé n'a ni message ni cause (les messages de
            // kotlinx.serialization recopient le JSON, donc les mots de passe du backup).
            val decoded = when (val result = BackupCodec.decode(jsonBytes)) {
                is BackupDecodeResult.Decoded -> result
                is BackupDecodeResult.Malformed ->
                    throw VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED)
                is BackupDecodeResult.UnsupportedVersion ->
                    throw VaultBackupReadException(VaultBackupReadException.Reason.UNSUPPORTED_VERSION)
            }

            // Toute la validation se fait AVANT la première écriture. Une entrée invalide (cœur v1
            // compris : hôtes, clés, tunnels, secrets) est ignorée et comptée, un tunnel d'un hôte
            // écarté aussi (une seule fois, il n'arrive jamais jusqu'à la boucle des tunnels) ; une
            // section au-delà de son plafond est tronquée (le surplus est compté en invalides).
            // Un known host n'est valide que si SSHJ sait relire sa clé.
            val validated = when (
                val result = BackupValidator.validate(decoded.document) {
                    KnownHostPortable.fromPortable(it.keyType, it.publicKey) != null
                }
            ) {
                is BackupValidationResult.Valid -> result.backup
                is BackupValidationResult.Malformed ->
                    throw VaultBackupReadException(VaultBackupReadException.Reason.MALFORMED)
            }
            val backup = validated.document

            val credentials = backup.credentials.filterKeys { !it.startsWith(LAN_SYNC_SECRET_KEY_PREFIX) }

            // Known hosts : conversion vers la forme de stockage locale AVANT les écritures, par le
            // même chemin que la poignée de main SSHJ.
            // Adresses retenues : pour un hôte DÉJÀ présent sur l'appareil (même id, vivant ou
            // tombstone), le backup n'apporte jamais la première empreinte de son adresse, qu'elle ait
            // changé ou non (un second import du même fichier approuverait sinon l'adresse déjà
            // déplacée au premier). La liste locale est lue AVANT l'écriture des hôtes.
            val held: Set<String> =
                if (backup.knownHosts.isNotEmpty() && backup.hosts.isNotEmpty()) {
                    BackupTrustRules.heldDestinations(
                        local = backupRestoreStore.localHostsIncludingDeleted(),
                        incoming = backup.hosts,
                    )
                } else {
                    emptySet()
                }
            val candidates = backup.knownHosts
                .mapNotNull { known ->
                    KnownHostPortable.fromPortable(known.keyType, known.publicKey)?.let {
                        KnownHostCandidate(known.hostPort, it.algorithm, it.encoded)
                    }
                }

            // Les secrets vont dans le vault, après la base. Si la fenêtre d'authentification du
            // vault est expirée, ces écritures échoueraient une fois la base déjà fusionnée : on
            // échoue avant, par le chemin existant de VaultAuthExpiredException (l'UI demande de
            // se ré-authentifier). Un backup sans aucun secret n'a pas besoin du vault.
            ensureOpen()
            val writesVault = credentials.isNotEmpty() ||
                backup.privateKeys.isNotEmpty() ||
                backup.keyPassphrases.isNotEmpty() ||
                backup.certificates.isNotEmpty()
            if (writesVault && vaultManager.isAuthRequired()) {
                throw VaultAuthExpiredException()
            }

            // Clés SSH : une décision par identifiant (règles partagées d'ajout seul), prise AVANT
            // la première écriture. La dérivation de la clé publique est coûteuse : hors du fil appelant.
            val keyPlan = planKeyRestore(backup)

            // Fusion, le backup l'emporte : un élément existant (même supprimé) est mis à jour
            // sur place avec le contenu du backup, un nouvel élément est inséré, un élément
            // local absent du backup est conservé. Toute la partie base de données est une seule
            // transaction : un échec ne laisse rien d'à moitié restauré, et aucun credential n'est
            // écrit dans le vault avant que la base soit cohérente. Les thèmes passent avant les
            // hôtes qui y renvoient.
            var skippedTunnels = 0
            var restoredTunnels = 0
            transactionRunner.run {
                // Checked again inside, right before the writes: the wipe may have closed the app meanwhile.
                ensureOpen()
                if (backup.customThemes.isNotEmpty()) backupRestoreStore.restoreThemes(backup.customThemes)
                if (backup.snippets.isNotEmpty()) backupRestoreStore.restoreSnippets(backup.snippets)

                // Restaurer les hôtes
                backup.hosts.forEach { host -> hostRepository.save(host) }

                // Restaurer les clés SSH (metadata), selon la décision de chaque identifiant : une clé
                // déjà présente garde sa clé privée et sa clé publique, sauf publique prouvée fausse.
                keyPlan.forEach { step -> step.decision.key?.let { key -> sshKeyRepository.save(key) } }

                // Restaurer les tunnels. Un tunnel dont l'hôte n'est ni dans le backup ni (vivant)
                // dans la base locale violerait la clé étrangère tunnels.hostId et ferait échouer
                // toute la restauration : il est ignoré et compté.
                val knownHostIds = backup.hosts.mapTo(HashSet()) { it.id }
                backup.tunnels.forEach { tunnel ->
                    if (tunnel.hostId !in knownHostIds && hostRepository.getById(tunnel.hostId) != null) {
                        knownHostIds.add(tunnel.hostId)
                    }
                    if (tunnel.hostId in knownHostIds) {
                        tunnelRepository.save(tunnel)
                        restoredTunnels++
                    } else {
                        skippedTunnels++
                    }
                }
            }

            // Restaurer les credentials dans le vault. Le secret de sync LAN qu'un backup
            // pré-correctif pourrait encore contenir est filtré plus haut : c'est une clé
            // d'authentification locale à l'appareil, à ne pas importer sur une autre machine.
            //
            // La base est écrite : à partir d'ici aucune étape ne fait échouer l'import. Chacune a
            // son propre try (journal : nom de classe seulement) et un échec est rendu dans le bilan
            // (restauration partielle), jamais en Result.failure ni en succès muet.
            // The wipe may have erased the tables while the transaction ran: the secrets would be orphans.
            ensureOpen()
            val failed = LinkedHashSet<BackupRestoreStep>()
            var storedPasswords = 0
            var storedPrivateKeys = 0
            var storedPassphrases = 0
            var storedCertificates = 0
            try {
                credentials.forEach { (credentialId, password) ->
                    vaultManager.storePassword(credentialId, password.toCharArray())
                    storedPasswords++
                }

                // Restaurer les clés privées dans le vault
                // (jamais par-dessus une clé privée déjà présente : la décision l'interdit)
                keyPlan.forEach { step ->
                    val pem = backup.privateKeys[step.id]
                    if (step.decision.writePrivateKey && pem != null) {
                        vaultManager.storePrivateKey(step.id, pem)
                        storedPrivateKeys++
                    }
                }

                // Restaurer les passphrases de clés privées chiffrées. Sans ceci une
                // clé chiffrée importée serait inutilisable. Elle suit sa clé privée.
                keyPlan.forEach { step ->
                    val pass = backup.keyPassphrases[step.id]
                    if (step.decision.writePassphrase && pass != null) {
                        vaultManager.storeKeyPassphrase(step.id, pass.toCharArray())
                        storedPassphrases++
                    }
                }

                // Restaurer les certificats SSH, déjà bornés par le validateur (un hôte CERTIFICATE
                // n'est utilisable qu'avec le sien).
                backup.certificates.forEach { (certId, pem) ->
                    vaultManager.storeCertificate(certId, pem)
                    storedCertificates++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e("VaultImporter: vault secrets step failed (${e::class.java.simpleName})")
                failed.add(BackupRestoreStep.VAULT_SECRETS)
            }

            // Known hosts : insertion seule, fichier réécrit d'un bloc.
            var knownAdded = 0
            var knownAlready = 0
            var knownRejected = 0
            var conflicts: List<String> = emptyList()
            var heldNames: List<String> = emptyList()
            if (candidates.isNotEmpty()) {
                try {
                    val outcome = knownHostsVerifier.importEntries(candidates, held)
                    knownAdded = outcome.added
                    knownAlready = outcome.alreadyKnown
                    knownRejected = outcome.rejected
                    conflicts = outcome.conflicts
                    heldNames = outcome.held
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("VaultImporter: known hosts step failed (${e::class.java.simpleName})")
                    failed.add(BackupRestoreStep.KNOWN_HOSTS)
                }
            }

            // Réglages : liste blanche de la plateforme (common + android), le reste est ignoré.
            var settingsApplied = 0
            ensureOpen()
            val settings = backup.settings
            if (settings != null) {
                try {
                    settingsApplied = settingsDataStore.applyBackupSettings(settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e("VaultImporter: settings step failed (${e::class.java.simpleName})")
                    failed.add(BackupRestoreStep.SETTINGS)
                }
            }

            val report = BackupImportReport(
                hosts = backup.hosts.size,
                sshKeys = backup.sshKeys.size,
                tunnels = restoredTunnels,
                passwords = storedPasswords,
                privateKeys = storedPrivateKeys,
                keyPassphrases = storedPassphrases,
                certificates = storedCertificates,
                snippets = backup.snippets.size,
                customThemes = backup.customThemes.size,
                knownHostsAdded = knownAdded,
                knownHostsAlreadyKnown = knownAlready,
                settingsApplied = settingsApplied,
                invalidIgnored = validated.invalidTotal + knownRejected,
                tunnelsSkipped = skippedTunnels,
                newerDataSkipped = decoded.hasUnknownSections,
                publicKeysRecomputed = keyPlan.count { it.decision.publicKeyRecomputed },
                localKeysKept = keyPlan.count { it.decision.localKeyKept },
            ).withKnownHostConflicts(conflicts)
                .withHeldKnownHosts(heldNames)
                .withFailedSteps(failed)

            // Comptes seulement : jamais un hôte:port, un label ni une empreinte.
            Timber.i(
                "VaultImporter: import complete (${report.rows().sumOf { it.second }} items restored, " +
                    "$skippedTunnels tunnels skipped, ${report.knownHostConflicts} known host conflicts, " +
                    "${report.invalidIgnored} invalid ignored)",
            )
            Result.success(report)

        } catch (e: VaultAuthExpiredException) {
            throw e  // propagate, UI must handle re-auth
        } catch (e: CancellationException) {
            throw e  // never turned into a failed Result
        } catch (e: Exception) {
            // Nom de classe seulement : ni le message ni la trace d'une exception qui pourrait
            // citer le contenu déchiffré du backup n'arrivent dans les logs.
            Timber.e("VaultImporter: import failed (${e::class.java.simpleName})")
            Result.failure(e)
        } finally {
            derivedKey?.let { cryptoUtils.secureWipe(it) }
            jsonBytes?.let { cryptoUtils.secureWipe(it) }
            unpacked?.let {
                cryptoUtils.secureWipe(it.salt)
                cryptoUtils.secureWipe(it.sealed)
            }
            passphrase.fill('\u0000')
        }
    }

    /** Un identifiant de clé SSH du fichier et ce que la restauration en fait ([BackupKeyRules.decide]). */
    private class KeyRestoreStep(val id: String, val decision: SshKeyRestoreDecision)

    /**
     * Décide, pour chaque identifiant de `sshKeys`, `privateKeys` et `keyPassphrases`, ce qu'on écrit :
     * une clé privée locale n'est jamais remplacée, une clé publique locale seulement par une dérivée
     * prouvée. Rien n'est écrit ici. Le vault n'est lu que pour un identifiant qui a déjà une ligne
     * locale ET dont le fichier apporte un secret : une clé privée (comparée au texte du fichier) ou une
     * passphrase (simple présence). Sans lecture possible, l'état est PRESENT, la valeur sûre.
     *
     * La dérivation se fait SANS passphrase (aucune KDF à la restauration) sur [derivationDispatcher].
     */
    private suspend fun planKeyRestore(backup: BackupDocument): List<KeyRestoreStep> {
        val incomingById = backup.sshKeys.associateBy { it.id }
        val ids = LinkedHashSet<String>().apply {
            addAll(incomingById.keys)
            addAll(backup.privateKeys.keys)
            addAll(backup.keyPassphrases.keys)
        }
        if (ids.isEmpty()) return emptyList()

        class Pending(val id: String, val incoming: SshKey?, val local: SshKey?, val state: LocalPrivateKey)

        val pending = ids.map { id ->
            val incoming = incomingById[id]
            val local = sshKeyRepository.getById(id)
            val filePem = backup.privateKeys[id]
            val state = when {
                local == null -> LocalPrivateKey.ABSENT
                // A vault that cannot be read for this id is PRESENT, the safe value: nothing is replaced.
                filePem != null -> try {
                    BackupKeyRules.localPrivateKeyState(vaultManager.getPrivateKey(id), filePem)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VaultAuthExpiredException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("VaultImporter: local private key read failed (${e::class.java.simpleName})")
                    LocalPrivateKey.PRESENT
                }
                id in backup.keyPassphrases -> try {
                    if (vaultManager.hasPrivateKey(id)) LocalPrivateKey.PRESENT else LocalPrivateKey.ABSENT
                } catch (e: CancellationException) {
                    throw e
                } catch (e: VaultAuthExpiredException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w("VaultImporter: local private key probe failed (${e::class.java.simpleName})")
                    LocalPrivateKey.PRESENT
                }
                else -> LocalPrivateKey.PRESENT
            }
            Pending(id, incoming, local, state)
        }

        // One memo per restore (never a field): the same private key under many ids is derived once.
        val deriver = publicKeyDeriver.memoizedWithoutPassphrase()
        return withContext(derivationDispatcher) {
            pending.map { p ->
                ensureActive()
                val filePem = backup.privateKeys[p.id]
                val derivation: SshPublicKeyDerivation? =
                    if (filePem != null && BackupKeyRules.shouldDerive(p.incoming, p.local, true, p.state)) {
                        // Sans passphrase : jamais de KDF à la restauration (un fichier piégé de milliers de
                        // clés chiffrées bloquerait l'import). Une clé chiffrée donne NeedsPassphrase, ce qui
                        // ne prouve rien ; la première copie la corrige avec la passphrase du vault.
                        deriver.derive(filePem, null)
                    } else {
                        null
                    }
                KeyRestoreStep(
                    p.id,
                    BackupKeyRules.decide(
                        incoming = p.incoming,
                        local = p.local,
                        filePrivateKey = filePem != null,
                        filePassphrase = p.id in backup.keyPassphrases,
                        localPrivateKey = p.state,
                        derivation = derivation,
                    ),
                )
            }
        }
    }
}
