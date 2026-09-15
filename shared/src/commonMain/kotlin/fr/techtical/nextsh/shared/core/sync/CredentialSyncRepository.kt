// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

/**
 * Credential-side sync repository: peer of [SyncRepository] but scoped to
 * encrypted credentials rather than regular CRDT entities.
 *
 * **Keyed by the ECDH shared secret**: unlike [SyncRepository], credential
 * export/apply needs the per-pair shared secret because the wire-format
 * [CredentialEntry.encryptedPayload] is encrypted with an HKDF-derived inner
 * key (see [CredentialCodec]). The HTTP layer (LanSyncClient / LanSyncServer)
 * provides the secret at call time and wipes it immediately after.
 *
 * Isolation from [SyncRepository]:
 * - Keeps secret-handling code in one place (this repo + [CredentialCodec])
 * - Lets the HTTP layer decide whether credential sync succeeds independently
 *   of regular-entity sync (graceful degradation on credential decrypt fail)
 * - No interface changes on [SyncRepository]: Phase 2 peers keep working
 *
 * **Filtering responsibility**: implementations MUST skip credentials that
 * are not exportable (biometric-only TEE keys, FIDO2 hardware-bound keys).
 * The [SshKey] model exposes [SshKey.isBiometric] / [SshKey.fido2CredentialId]
 * for that decision.
 */
interface CredentialSyncRepository {

    /**
     * Snapshots all local credentials as encrypted [SyncEntry] list ready for
     * transport. Each entry has:
     * - An already-AES-GCM-encrypted payload (HKDF-derived inner key)
     * - CRDT metadata (vector clock, updatedAt, soft-delete flag) identical to
     *   regular entities so the receiver can merge via [CrdtEngine]
     *
     * [sharedSecret] is used to derive the inner key via
     * [CredentialCodec.deriveInnerKey]. Implementations must wipe the inner
     * key on return (caller wipes sharedSecret at HTTP layer boundary).
     */
    suspend fun exportEncryptedEntries(sharedSecret: ByteArray): List<SyncEntry<CredentialEntry>>

    /**
     * Merges remote [entries] into local state, decrypting each payload with
     * the HKDF-derived inner key and re-encrypting at rest via the local vault.
     *
     * Returns [CredentialApplyResult] with:
     * - cleanApplied: count of credentials merged without conflict
     * - conflicts: concurrent edits that need user resolution (implementations
     *   persist these to [PendingConflictRepository] internally with
     *   [SyncableEntityType.CREDENTIAL], mirroring how [SyncRepository] persists
     *   host/tunnel conflicts; the return value is informational)
     * - skipped: count of entries whose ciphertext failed to decrypt (tampering,
     *   wrong key, etc.) and were silently skipped
     *
     * **Graceful degradation**: if a single entry's payload fails to decrypt
     * (tampering, HKDF mismatch, etc.), that entry is skipped and logged but
     * the rest of [entries] is processed. Does not throw.
     */
    suspend fun applyRemoteEntries(
        entries: List<SyncEntry<CredentialEntry>>,
        sharedSecret: ByteArray,
    ): CredentialApplyResult

    /**
     * Forcibly accepts a single remote [entry]: **bypasses** CRDT merge.
     *
     * Used by the conflict resolution UI after the user explicitly chose the
     * remote version for a conflict surfaced by [applyRemoteEntries]. The
     * normal merge path would re-detect the same conflict because neither
     * clock dominates; this method writes the remote payload into the vault
     * unconditionally and stamps the local meta with the remote's clock.
     *
     * Returns true on success, false if the payload couldn't be decrypted
     * (tampering, wrong peer secret). Does not throw.
     */
    suspend fun applyRemoteCredentialForced(
        entry: SyncEntry<CredentialEntry>,
        sharedSecret: ByteArray,
    ): Boolean
}

data class CredentialApplyResult(
    val cleanApplied: Int,
    val conflicts: List<MergeResult.Conflict<CredentialEntry>>,
    /** Count of entries skipped due to decrypt failure: tracked separately from conflicts. */
    val skipped: Int,
)
