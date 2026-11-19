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
 * A stored host key in local form: [algorithm] is the JCA name of the key and [publicKeyEncoded] its X.509 DER.
 * Carries key material: no log, no `toString` content.
 */
class StoredKnownHost(
    val hostPort: String,
    val algorithm: String,
    val publicKeyEncoded: ByteArray,
    val addedAt: Long,
) {
    override fun toString(): String = "StoredKnownHost"
}

/**
 * Outcome of [DesktopKnownHostsStore.importMissing]. [conflicts] are the host:port whose local key differs
 * from the imported one (the local key was kept), in input order.
 */
class KnownHostsImportResult(
    val added: Int,
    val alreadyKnown: Int,
    val conflicts: List<String>,
    /** host:port not stored yet whose first fingerprint the file must not bring (see `importMissingInTransaction`). */
    val held: List<String> = emptyList(),
) {
    override fun toString(): String = "KnownHostsImportResult(added=$added, alreadyKnown=$alreadyKnown, conflicts=${conflicts.size}, held=${held.size})"
}

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

    // ── Backup (.nextsh) ──────────────────────────────────────────────────────

    /**
     * Every stored entry with its raw key, for a backup export (the portable form is built by the exporter).
     * Unlike [getAllEntries] this carries the key bytes: the result must not be logged.
     */
    fun exportEntries(): List<StoredKnownHost> =
        queries.selectAll().executeAsList().map {
            StoredKnownHost(it.hostPort, it.algorithm, it.publicKeyEncoded, it.addedAt)
        }

    /**
     * Import by INSERTION ONLY, in its own transaction: a host:port not stored yet is added, the same key
     * is counted as already known, a DIFFERENT key is never written (the local one is kept and the
     * host:port is returned in [KnownHostsImportResult.conflicts]). A known host is a trust decision:
     * replacing one from a file would let anybody who knows the backup passphrase install an interception
     * key without any alert.
     */
    fun importMissing(entries: List<StoredKnownHost>): KnownHostsImportResult =
        database.transactionWithResult { importMissingInTransaction(entries) }

    /**
     * Body of [importMissing] without a transaction of its own, so that a backup restore writes the known
     * hosts in the SAME transaction as the rest of the database. Not a suspend function on purpose: a
     * SQLDelight transaction is bound to its thread.
     */
    internal fun importMissingInTransaction(
        entries: List<StoredKnownHost>,
        heldDestinations: Set<String> = emptySet(),
    ): KnownHostsImportResult {
        var added = 0
        var alreadyKnown = 0
        val conflicts = ArrayList<String>()
        val held = ArrayList<String>()
        for (entry in entries) {
            val existing = queries.selectByHostPort(entry.hostPort).executeAsOneOrNull()
            when {
                existing == null && entry.hostPort in heldDestinations -> held += entry.hostPort
                existing == null -> {
                    // A portable entry may carry no date (0): the date of the import is the honest one.
                    val addedAt = if (entry.addedAt > 0) entry.addedAt else nowMs()
                    queries.insertIfAbsent(entry.hostPort, entry.algorithm, entry.publicKeyEncoded, addedAt)
                    added++
                }
                existing.algorithm == entry.algorithm &&
                    existing.publicKeyEncoded.contentEquals(entry.publicKeyEncoded) -> alreadyKnown++
                else -> conflicts += entry.hostPort
            }
        }
        return KnownHostsImportResult(added = added, alreadyKnown = alreadyKnown, conflicts = conflicts, held = held)
    }

    companion object {
        /** OpenSSH-style SHA256 fingerprint (`SHA256:<base64-no-padding>`). */
        fun sha256Fingerprint(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
