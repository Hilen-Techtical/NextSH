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

    /**
     * Resolves a conflict in favor of the **local** credential: counterpart of
     * [applyRemoteCredentialForced] for the "keep local" choice.
     *
     * Deleting the pending-conflict marker alone would leave the local and remote
     * clocks concurrent, so the next sync would recreate the same conflict on both
     * devices. This stamps the local sync metadata with a clock that strictly
     * dominates both versions ([VectorClock.supersede] with the local device id, same
     * rule as [CrdtEngine.keepLocal]) and refreshes `updatedAt`, then removes the
     * pending conflict for this credential. The next export therefore sends the local
     * version (or the local tombstone, if the local side had deleted it) as a clean
     * win, and the peer applies it without conflict.
     *
     * Only the metadata changes: the vault content and the hash are untouched, and
     * no secret is read, decrypted or logged, so no shared secret is needed.
     *
     * [remote] is the remote version of the conflict, i.e. the entry decoded from
     * `PendingConflict.remoteJson` with `ConflictSerializer.decodeCredential`; its
     * [SyncEntry.id] is the conflict's `entityId` (the composite `type:localId`).
     * Only its clock is used. The CURRENT local metadata is used as the local side,
     * not the snapshot stored in the conflict, so a local change made since the
     * conflict was detected is never lost. Calling it again for the same conflict is
     * safe: each call moves the clock strictly forward.
     *
     * Returns true when the metadata was updated and the pending conflict removed.
     * Returns false, without touching the metadata or the pending conflict, when there
     * is nothing to re-assert: no local metadata for this credential, or a
     * device-local LAN secret that never syncs. Does not throw in those cases. The
     * caller must then delete the marker itself by id
     * (`PendingConflictRepository.deleteById(conflict.id)`). Without local metadata
     * the next export creates a fresh one, whose clock can be concurrent with the
     * remote clock: the conflict may then be detected again at the next sync, and the
     * user is asked once more.
     *
     * There is no lock between this method and the sync path
     * ([exportEncryptedEntries], [applyRemoteEntries]). A sync running at the same time
     * is harmless: this call is idempotent and only moves the clock forward, and the
     * next export recomputes the content hash from the vault. The worst case is a
     * transient re-detection of the conflict, resolved the same way again.
     */
    suspend fun reassertLocalCredential(remote: SyncEntry<CredentialEntry>): Boolean
}

data class CredentialApplyResult(
    val cleanApplied: Int,
    val conflicts: List<MergeResult.Conflict<CredentialEntry>>,
    /** Count of entries skipped due to decrypt failure: tracked separately from conflicts. */
    val skipped: Int,
)
