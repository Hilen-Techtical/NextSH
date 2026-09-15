// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import fr.techtical.nextsh.desktop.db.NextShDatabase
import java.security.MessageDigest
import java.util.Base64

/**
 * Outcome of verifying an SSH host key against the local known-hosts store.
 *
 * Mirrors the semantics of the Android `KnownHostsVerifier` types so both
 * platforms behave identically at the UX level. Kept in `:desktop` for now:
 * if iOS/macOS join the party, these will migrate to `:shared/commonMain`.
 */
sealed class HostKeyVerifyResult {
    /** Stored fingerprint matches: connection allowed without prompt. */
    object Trusted : HostKeyVerifyResult()

    /** No record for this host: user must confirm (TOFU). */
    data class Unknown(
        val hostPort: String,
        val algorithm: String,
        val fingerprint: String,
    ) : HostKeyVerifyResult()

    /**
     * Key has changed since last connection: potential MITM.
     * The verifier REJECTS the connection in this case; the user must wipe
     * the old entry via [KnownHostsScreen] before reconnecting.
     */
    data class Mismatch(
        val hostPort: String,
        val storedFingerprint: String,
        val receivedFingerprint: String,
    ) : HostKeyVerifyResult()
}

/** One row from the known_hosts table, surfaced to the UI with a truncated fingerprint. */
data class KnownHostEntry(
    val hostPort: String,
    val algorithm: String,
    val fingerprint: String,
    val addedAt: Long,
)

/**
 * SQLDelight-backed store for trusted SSH host keys.
 *
 * Queries are synchronous: known_hosts is small (tens of rows at most in the
 * typical deployment) and lookup happens once per connection attempt: no
 * perf concern. Writes happen only at "trust this host" time.
 */
class DesktopKnownHostsStore(
    private val database: NextShDatabase,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    private val queries get() = database.knownHostQueries

    /**
     * Returns the stored algorithm + raw public key bytes for [hostPort], or
     * `null` if no entry exists (first-time-use: triggers the Unknown branch).
     */
    fun getStored(hostPort: String): Pair<String, ByteArray>? {
        val row = queries.selectByHostPort(hostPort).executeAsOneOrNull() ?: return null
        return row.algorithm to row.publicKeyEncoded
    }

    fun upsert(hostPort: String, algorithm: String, publicKeyEncoded: ByteArray) {
        queries.upsert(hostPort, algorithm, publicKeyEncoded, nowMs())
    }

    fun deleteByHostPort(hostPort: String): Boolean {
        val existed = queries.selectByHostPort(hostPort).executeAsOneOrNull() != null
        if (existed) queries.deleteByHostPort(hostPort)
        return existed
    }

    fun deleteAll() {
        queries.deleteAll()
    }

    /**
     * Returns every stored entry with a displayable SHA-256 fingerprint. The
     * fingerprint is derived fresh here so we don't bloat the schema: the
     * canonical data is the raw public key bytes.
     */
    fun getAllEntries(): List<KnownHostEntry> =
        queries.selectAll().executeAsList().map {
            KnownHostEntry(
                hostPort = it.hostPort,
                algorithm = it.algorithm,
                fingerprint = sha256Fingerprint(it.publicKeyEncoded),
                addedAt = it.addedAt,
            )
        }

    companion object {
        /** OpenSSH-style SHA256 fingerprint (`SHA256:<base64-no-padding>`). */
        fun sha256Fingerprint(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
