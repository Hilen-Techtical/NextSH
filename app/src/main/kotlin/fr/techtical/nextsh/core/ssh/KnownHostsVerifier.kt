// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.techtical.nextsh.core.vault.VaultWipeGate
import fr.techtical.nextsh.shared.core.backup.BackupValidator
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

// ── Résultat de vérification ──────────────────────────────────────────────────

sealed class HostKeyVerifyResult {
    /** La clé correspond à l'entrée stockée : connexion autorisée. */
    object Trusted : HostKeyVerifyResult()

    /** Première connexion : l'utilisateur doit confirmer. */
    data class Unknown(
        val hostname: String,
        val algorithm: String,
        val fingerprint: String,
    ) : HostKeyVerifyResult()

    /** La clé a changé depuis la dernière connexion : avertissement MITM. */
    data class Mismatch(
        val hostname: String,
        val storedFingerprint: String,
        val receivedFingerprint: String,
    ) : HostKeyVerifyResult()
}

/**
 * Ce qui est enregistré, à un instant donné, pour l'hôte:port d'une clé qu'on s'apprête à demander à
 * l'utilisateur, voir [KnownHostsVerifier.storedKeyState].
 */
enum class StoredKeyState {
    /** Aucune clé pour cet hôte:port : la question se pose. */
    NONE,

    /** Cette clé même (algorithme et empreinte) : l'utilisateur vient de la valider, rien à demander. */
    SAME,

    /** Une autre clé : le serveur n'est plus celui que l'utilisateur vient de valider. */
    DIFFERENT,
}

// ── Entrée dans le fichier known_hosts ───────────────────────────────────────

data class KnownHostEntry(
    val hostPort: String,
    val algorithm: String,
    val fingerprint: String,
)

/** Une clé enregistrée, sous sa forme de stockage local : [algorithm] JCA et [encoded] X.509. */
class KnownHostRecord(
    val hostPort: String,
    val algorithm: String,
    val encoded: ByteArray,
) {
    // Écrit à la main : ni hôte ni clé dans une chaîne de log.
    override fun toString(): String = "KnownHostRecord"
}

/** Une clé à importer, sous la forme de stockage local (voir [KnownHostsVerifier.importEntries]). */
class KnownHostCandidate(
    val hostPort: String,
    val algorithm: String,
    val encoded: ByteArray,
) {
    override fun toString(): String = "KnownHostCandidate"
}

/**
 * Bilan de [KnownHostsVerifier.importEntries].
 *
 * @property conflicts hôte:port dont la clé du backup diffère de la locale (la locale est conservée), dans l'ordre du fichier.
 * @property rejected entrées refusées parce que leur hôte:port ou leur algorithme ne peut pas s'écrire dans le fichier.
 */
class KnownHostsImportOutcome(
    val added: Int,
    val alreadyKnown: Int,
    val conflicts: List<String>,
    val rejected: Int = 0,
    val held: List<String> = emptyList(),
) {
    override fun toString(): String = "KnownHostsImportOutcome(added=$added, alreadyKnown=$alreadyKnown, conflicts=${conflicts.size}, rejected=$rejected, held=${held.size})"
}

/**
 * Vérificateur de clés hôtes avec stratégie TOFU (Trust On First Use).
 *
 * Stockage : [context.filesDir]/known_hosts
 * Format   : une entrée par ligne, "hostname:port algorithm base64(encoded)"
 *
 * Comportement :
 *  - Première connexion : retourne [HostKeyVerifyResult.Unknown], l'utilisateur confirme.
 *  - Connexions suivantes : retourne [HostKeyVerifyResult.Trusted] si la clé correspond.
 *  - En cas de mismatch : retourne [HostKeyVerifyResult.Mismatch], rejet immédiat.
 */
@Singleton
class KnownHostsVerifier @Inject constructor(
    @ApplicationContext private val context: Context,
    /** The wipe of the vault: while the app is closed nothing is written to the file, see [acceptHost]. */
    private val wipeGate: VaultWipeGate,
) : HostKeyVerifier {

    /** Without the wipe gate of the app (tests). Not an injection point. */
    constructor(context: Context) : this(context, VaultWipeGate())

    // SECURITY: Le fichier known_hosts est stocké en clair dans la sandbox app,
    // sans vérification d'intégrité (HMAC). Comportement identique à OpenSSH.
    // Sur un device rooté l'attaquant a déjà accès au Keystore, risque accepté.
    private val knownHostsFile: File
        get() = File(context.filesDir, "known_hosts")

    // ── HostKeyVerifier (implémentation SSHJ) ────────────────────────────────

    /**
     * Méthode appelée par SSHJ lors de la connexion.
     * Délègue à [checkKey] : le callback suspend est géré dans [SshSessionManager].
     *
     * NE PAS appeler directement pour la logique applicative : utiliser [checkKey].
     */
    @Synchronized
    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        return when (val result = checkKey(hostname, port, key)) {
            is HostKeyVerifyResult.Trusted -> true
            is HostKeyVerifyResult.Unknown -> {
                // Par défaut (sans callback) : refuser les hôtes inconnus
                Timber.w("KnownHosts: unknown host key (algorithm=${result.algorithm}), refused (no UI callback)")
                false
            }
            is HostKeyVerifyResult.Mismatch -> {
                // The fingerprints are never logged. The connection then fails with
                // HOST_KEY_MISMATCH, which is what the user gets to see (no fingerprint either).
                Timber.e("KnownHosts: host key mismatch, algorithm=${key.algorithm}")
                false
            }
        }
    }

    @Synchronized
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val stored = getStoredKey(hostname, port) ?: return emptyList()
        return listOf(stored.algorithm)
    }

    // ── API publique ──────────────────────────────────────────────────────────

    /**
     * Vérifie la clé hôte et retourne un résultat typé.
     * Utilisé par [SshSessionManager] pour décider d'afficher un dialogue UI.
     */
    @Synchronized
    fun checkKey(hostname: String, port: Int, key: PublicKey): HostKeyVerifyResult {
        val stored = getStoredKey(hostname, port)
        val received = sha256Fingerprint(key.encoded)

        return when {
            stored == null -> {
                HostKeyVerifyResult.Unknown(
                    hostname    = "$hostname:$port",
                    algorithm   = key.algorithm,
                    fingerprint = received,
                )
            }
            stored.alg == key.algorithm && stored.enc.contentEquals(key.encoded) -> {
                Timber.d("KnownHosts: host key verified, algorithm=${key.algorithm}")
                HostKeyVerifyResult.Trusted
            }
            else -> {
                val storedFingerprint = sha256Fingerprint(stored.enc)
                HostKeyVerifyResult.Mismatch(
                    hostname           = "$hostname:$port",
                    storedFingerprint  = storedFingerprint,
                    receivedFingerprint = received,
                )
            }
        }
    }

    /**
     * Ce qui est enregistré maintenant pour l'hôte:port de [unknown] (hôte:port, algorithme,
     * empreinte), comparé à la clé que cette demande s'apprête à faire valider. [StoredKeyState.SAME]
     * quand l'utilisateur vient de la valider dans une demande précédente pour le même hôte : la
     * demande suivante n'a plus rien à lui demander. [StoredKeyState.DIFFERENT] quand une autre clé a
     * été validée entre-temps : demander encore ajouterait une ligne que rien ne lit, et la connexion
     * suivante échouerait sur un changement de clé.
     */
    @Synchronized
    fun storedKeyState(unknown: HostKeyVerifyResult.Unknown): StoredKeyState {
        val stored = getStoredKey(unknown.hostname) ?: return StoredKeyState.NONE
        return if (stored.alg == unknown.algorithm && sha256Fingerprint(stored.enc) == unknown.fingerprint) {
            StoredKeyState.SAME
        } else {
            StoredKeyState.DIFFERENT
        }
    }

    /**
     * Vrai tant que le vault est en cours d'effacement : [acceptHost] n'écrit rien. Une réponse
     * « Faire confiance » arrivée dans cette fenêtre est refusée par l'appelant, la clé n'étant pas
     * mémorisée.
     */
    fun isWriteRefused(): Boolean = wipeGate.isClosed()

    /**
     * Enregistre explicitement la clé hôte après confirmation de l'utilisateur.
     * À appeler uniquement suite à [HostKeyVerifyResult.Unknown] confirmé.
     *
     * Rien n'est écrit, et le résultat est `false`, si [hostPort] n'est pas stockable (voir
     * [isStorableHostPort] : le fichier se découpe sur l'espace et par ligne, un nom avec blanc ou
     * caractère de contrôle ferait relire la ligne de travers ou en injecterait une autre), si
     * l'algorithme n'est pas écrivable ou si la clé est vide.
     *
     * @return `true` si la clé est enregistrée.
     */
    @Synchronized
    fun acceptHost(hostPort: String, algorithm: String, encodedKey: ByteArray): Boolean {
        // An answer "trust" that reaches here after the wipe closed the app must not write the key of an
        // erased host into the file the wipe is about to delete or has just deleted.
        if (wipeGate.isClosed()) {
            Timber.w("KnownHosts: host key not saved, the app is being wiped")
            return false
        }
        if (!isStorableHostPort(hostPort) || !isWritableAlgorithm(algorithm) || encodedKey.isEmpty()) {
            Timber.w("KnownHosts: host key not saved, host or algorithm not storable")
            return false
        }
        val file = knownHostsFile
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        val line = "$hostPort $algorithm ${encodeBase64(encodedKey)}\n"
        file.appendText(line, Charsets.UTF_8)
        Timber.i("KnownHosts: host key accepted and saved, algorithm=$algorithm")
        return true
    }

    /**
     * Retourne toutes les entrées du fichier known_hosts avec leur empreinte SHA-256. Une ligne
     * invalide (voir [parseLine]) est ignorée, jamais réécrite.
     */
    @Synchronized
    fun getAllEntries(): List<KnownHostEntry> {
        val file = knownHostsFile
        if (!file.exists()) return emptyList()

        return file.readLines()
            .mapNotNull { line ->
                val record = parseLine(line) ?: return@mapNotNull null
                KnownHostEntry(
                    hostPort    = record.hostPort,
                    algorithm   = record.algorithm,
                    fingerprint = sha256Fingerprint(record.encoded),
                )
            }
    }

    /**
     * Supprime l'entrée correspondant à [hostPort] du fichier known_hosts.
     * @return true si une entrée a été supprimée, false si introuvable.
     */
    @Synchronized
    fun removeEntry(hostPort: String): Boolean {
        val file = knownHostsFile
        if (!file.exists()) return false

        val lines = file.readLines()
        val filtered = lines.filter { line ->
            val parts = line.trim().split(" ")
            parts.isEmpty() || parts[0] != hostPort
        }

        if (filtered.size == lines.size) return false

        file.writeText(filtered.joinToString("\n") + if (filtered.isNotEmpty()) "\n" else "", Charsets.UTF_8)
        Timber.i("KnownHosts: entry removed")
        return true
    }

    /**
     * Supprime entièrement le fichier known_hosts, et le temporaire qu'une écriture interrompue a pu
     * laisser (`known_hosts.tmp`). Les deux suppressions sont tentées avant l'erreur.
     *
     * @throws java.io.IOException si un des deux fichiers reste (texte fixe, sans chemin)
     */
    @Synchronized
    fun clearAll() {
        val file = knownHostsFile
        val temp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
        val existed = file.exists()
        for (target in listOf(file, temp)) {
            if (target.exists()) target.delete()
        }
        if (file.exists() || temp.exists()) throw java.io.IOException("Known hosts file could not be deleted")
        if (existed) Timber.i("KnownHosts: file deleted")
    }

    // ── Backup .nextsh ────────────────────────────────────────────────────────

    /**
     * Les clés enregistrées, une par hôte:port (la première ligne gagne, comme pour [verify]), pour
     * l'export d'un backup. Les lignes illisibles sont ignorées.
     */
    @Synchronized
    fun exportEntries(): List<KnownHostRecord> {
        val file = knownHostsFile
        if (!file.exists()) return emptyList()
        val seen = LinkedHashMap<String, KnownHostRecord>()
        for (line in file.readLines(Charsets.UTF_8)) {
            val parsed = parseLine(line) ?: continue
            if (parsed.hostPort !in seen) seen[parsed.hostPort] = parsed
        }
        return seen.values.toList()
    }

    /**
     * Importe des clés d'un backup, en INSERTION SEULE : un hôte:port absent est ajouté, la même clé
     * est comptée « déjà connue », une clé différente laisse la locale en place et est nommée dans
     * [KnownHostsImportOutcome.conflicts]. Jamais de remplacement : il permettrait à quiconque connaît
     * la passphrase d'un backup d'installer une clé d'interception sans alerte.
     *
     * Sous le même verrou que [verify]. Le fichier est réécrit en une fois (temporaire dans le même
     * dossier, puis renommage atomique) : un arrêt en cours d'écriture laisse l'ancien fichier entier.
     * Un hôte:port que [isStorableHostPort] refuse (espace, caractère de contrôle ou de format
     * invisible, port hors 1..65535) est écarté et compté dans [KnownHostsImportOutcome.rejected].
     */
    @Synchronized
    fun importEntries(candidates: List<KnownHostCandidate>, held: Set<String> = emptySet()): KnownHostsImportOutcome {
        // A restore that runs while the app is being wiped writes nothing.
        if (wipeGate.isClosed()) {
            Timber.w("KnownHosts: import refused, the app is being wiped")
            return KnownHostsImportOutcome(0, 0, emptyList(), candidates.size, emptyList())
        }
        val file = knownHostsFile
        val stored = HashMap<String, StoredPublicKey>()
        var existingText = ""
        if (file.exists()) {
            existingText = file.readText(Charsets.UTF_8)
            for (line in existingText.lines()) {
                val parsed = parseLine(line) ?: continue
                if (parsed.hostPort !in stored) stored[parsed.hostPort] = StoredPublicKey(parsed.algorithm, parsed.encoded)
            }
        }

        var added = 0
        var alreadyKnown = 0
        var rejected = 0
        val conflicts = ArrayList<String>()
        val heldNames = LinkedHashSet<String>()
        val newLines = StringBuilder()
        for (candidate in candidates) {
            if (!isStorableHostPort(candidate.hostPort) || !isWritableAlgorithm(candidate.algorithm) ||
                candidate.encoded.isEmpty()
            ) {
                rejected++
                continue
            }
            val current = stored[candidate.hostPort]
            when {
                // Adresse d'un hôte déjà présent sur l'appareil : le backup n'apporte pas la
                // première empreinte de cette adresse (voir `held`).
                current == null && candidate.hostPort in held -> heldNames.add(candidate.hostPort)
                current == null -> {
                    stored[candidate.hostPort] = StoredPublicKey(candidate.algorithm, candidate.encoded)
                    newLines.append(candidate.hostPort).append(' ').append(candidate.algorithm).append(' ')
                        .append(encodeBase64(candidate.encoded)).append('\n')
                    added++
                }
                current.alg == candidate.algorithm && current.enc.contentEquals(candidate.encoded) -> alreadyKnown++
                else -> conflicts.add(candidate.hostPort)
            }
        }

        if (added > 0) {
            val head = if (existingText.isEmpty() || existingText.endsWith("\n")) existingText else existingText + "\n"
            writeAtomically(file, head + newLines)
        }
        Timber.i("KnownHosts: import done ($added added, $alreadyKnown already known, ${conflicts.size} conflicts, $rejected rejected)")
        return KnownHostsImportOutcome(added, alreadyKnown, conflicts, rejected, heldNames.toList())
    }

    private fun writeAtomically(target: File, content: String) {
        val dir = target.absoluteFile.parentFile ?: throw java.io.IOException("No parent folder")
        dir.mkdirs()
        val temp = File(dir, "${target.name}.tmp")
        try {
            temp.writeText(content, Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    /**
     * Lecture stricte d'une ligne : exactement trois champs séparés par un espace simple, un hôte:port
     * stockable, un algorithme écrivable et une clé en base64 non vide. Sinon `null` : la ligne est
     * ignorée par toutes les lectures, jamais réécrite.
     */
    private fun parseLine(line: String): KnownHostRecord? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(" ")
        if (parts.size != 3) return null
        if (!isStorableHostPort(parts[0]) || !isWritableAlgorithm(parts[1])) return null
        val encoded = decodeBase64(parts[2]) ?: return null
        if (encoded.isEmpty()) return null
        return KnownHostRecord(parts[0], parts[1], encoded)
    }

    private fun isWritableAlgorithm(algorithm: String): Boolean =
        algorithm.isNotEmpty() && algorithm.none { it.isWhitespace() || it.isISOControl() }

    // ── Lecture / écriture ────────────────────────────────────────────────────

    /**
     * Retourne la clé stockée pour [hostname]:[port], ou null si inconnue.
     */
    private fun getStoredKey(hostname: String, port: Int): StoredPublicKey? = getStoredKey("$hostname:$port")

    /** Idem, pour un hôte déjà écrit `hôte:port`, comme dans le fichier. */
    private fun getStoredKey(hostPort: String): StoredPublicKey? {
        val file = knownHostsFile
        if (!file.exists()) return null

        for (line in file.readLines()) {
            val record = parseLine(line) ?: continue
            if (record.hostPort == hostPort) return StoredPublicKey(record.algorithm, record.encoded)
        }
        return null
    }

    // ── Utilitaires ──────────────────────────────────────────────────────────

    /**
     * Calcule l'empreinte SHA-256 au format "SHA256:<base64>".
     */
    private fun sha256Fingerprint(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    private fun encodeBase64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun decodeBase64(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Implémentation minimale de [PublicKey] pour la comparaison en mémoire.
     */
    private class StoredPublicKey(
        val alg: String,
        val enc: ByteArray,
    ) : PublicKey {
        override fun getAlgorithm() = alg
        override fun getFormat()    = "X.509"
        override fun getEncoded()   = enc
    }

    companion object {
        /**
         * La règle unique de ce qui s'écrit dans le fichier, la même que `BackupValidator.isValidHostPort`
         * de `:shared` (qui n'est pas public) : coupure au dernier `:`, hôte accepté par
         * [BackupValidator.isValidHostName] (ni blanc, ni contrôle, ni caractère de format invisible,
         * 255 au plus), port de 1 à 65535 en chiffres. Aucune réduction : la valeur est écrite telle
         * quelle, donc jugée telle quelle.
         */
        internal fun isStorableHostPort(hostPort: String): Boolean {
            val colon = hostPort.lastIndexOf(':')
            if (colon <= 0) return false
            val host = hostPort.substring(0, colon)
            val port = hostPort.substring(colon + 1)
            if (!BackupValidator.isValidHostName(host)) return false
            if (port.isEmpty() || port.length > 5 || !port.all { it in '0'..'9' }) return false
            return port.toInt() in 1..65535
        }
    }
}
