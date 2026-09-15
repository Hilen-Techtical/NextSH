// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
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

// ── Entrée dans le fichier known_hosts ───────────────────────────────────────

data class KnownHostEntry(
    val hostPort: String,
    val algorithm: String,
    val fingerprint: String,
)

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
) : HostKeyVerifier {

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
                Timber.w("KnownHosts: hôte inconnu $hostname:$port, refus (pas de callback UI)")
                false
            }
            is HostKeyVerifyResult.Mismatch -> {
                Timber.e(
                    "KnownHosts: MISMATCH pour $hostname:$port, " +
                    "stocké=${result.storedFingerprint} reçu=${result.receivedFingerprint}"
                )
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
                Timber.d("KnownHosts: clé vérifiée pour $hostname:$port")
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
     * Enregistre explicitement la clé hôte après confirmation de l'utilisateur.
     * À appeler uniquement suite à [HostKeyVerifyResult.Unknown] confirmé.
     */
    @Synchronized
    fun acceptHost(hostPort: String, algorithm: String, encodedKey: ByteArray) {
        val file = knownHostsFile
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        val line = "$hostPort $algorithm ${encodeBase64(encodedKey)}\n"
        file.appendText(line, Charsets.UTF_8)
        Timber.i("KnownHosts: clé acceptée et sauvegardée pour $hostPort ($algorithm)")
    }

    /**
     * Retourne toutes les entrées du fichier known_hosts avec leur empreinte SHA-256.
     */
    @Synchronized
    fun getAllEntries(): List<KnownHostEntry> {
        val file = knownHostsFile
        if (!file.exists()) return emptyList()

        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(" ")
                if (parts.size >= 3) {
                    val hostPort   = parts[0]
                    val algorithm  = parts[1]
                    val encoded    = decodeBase64(parts[2])
                    if (encoded != null) {
                        KnownHostEntry(
                            hostPort    = hostPort,
                            algorithm   = algorithm,
                            fingerprint = sha256Fingerprint(encoded),
                        )
                    } else null
                } else null
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
        Timber.i("KnownHosts: entrée supprimée pour $hostPort")
        return true
    }

    /**
     * Supprime entièrement le fichier known_hosts.
     */
    @Synchronized
    fun clearAll() {
        val file = knownHostsFile
        if (file.exists()) {
            file.delete()
            Timber.i("KnownHosts: fichier supprimé")
        }
    }

    // ── Lecture / écriture ────────────────────────────────────────────────────

    /**
     * Retourne la clé stockée pour [hostname]:[port], ou null si inconnue.
     */
    private fun getStoredKey(hostname: String, port: Int): StoredPublicKey? {
        val file = knownHostsFile
        if (!file.exists()) return null

        val prefix = "$hostname:$port "
        for (line in file.readLines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith(prefix)) {
                val parts = trimmed.split(" ")
                if (parts.size >= 3) {
                    val algorithm = parts[1]
                    val encoded   = decodeBase64(parts[2])
                    if (encoded != null) {
                        return StoredPublicKey(algorithm, encoded)
                    }
                }
            }
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
}
