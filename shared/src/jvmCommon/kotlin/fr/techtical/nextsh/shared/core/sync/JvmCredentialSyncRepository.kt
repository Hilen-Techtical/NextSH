// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.vault.VaultManager
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.randomUuid
import java.util.Arrays

private const val TAG = "CredentialSync"

private const val KEY_PREFIX_PASSWORD = "pwd:"
private const val KEY_PREFIX_PRIVATE_KEY = "key:"
private const val KEY_PREFIX_CERTIFICATE = "cert:"
private const val KEY_PREFIX_KEY_PASSPHRASE = "keypass:"

/**
 * JVM-shared implementation of [CredentialSyncRepository]. Both [:app] (Android)
 * and [:desktop] use this: the only platform difference is the [vault] instance
 * passed in and the metadata file path.
 *
 * The composite credential id (`type:localId`) is what we sync: same raw
 * localId may exist for different types (rare, but possible), so prefixing
 * makes the CRDT key globally unique and prevents cross-type stomping.
 */
class JvmCredentialSyncRepository(
    private val vault: VaultManager,
    private val metaStore: CredentialMetaStore,
    private val pendingConflictRepository: PendingConflictRepository,
    private val localDeviceId: () -> String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : CredentialSyncRepository {

    override suspend fun exportEncryptedEntries(sharedSecret: ByteArray): List<SyncEntry<CredentialEntry>> {
        val innerKey = CredentialCodec.deriveInnerKey(sharedSecret)
        return try {
            // Self-heal: drop any meta left behind by builds that wrongly tracked
            // device-local LAN secrets as credentials (would otherwise keep tombstoning them).
            purgeInternalSecretMeta()
            val now = nowMs()
            val device = localDeviceId()
            val snapshot = currentVaultSnapshot()          // syncId -> (type, raw bytes)
            val existingMeta = metaStore.getAll()           // syncId -> CredentialMeta

            val result = mutableListOf<SyncEntry<CredentialEntry>>()

            // Pass 1: entries currently in the vault (may be new, unchanged, or modified)
            for ((syncId, entry) in snapshot) {
                val (type, payloadBytes) = entry
                val hash = MetaHasher.sha256Base64(payloadBytes)
                val existing = existingMeta[syncId]
                val effectiveMeta = when {
                    existing == null -> CredentialMeta(
                        type = type,
                        vectorClock = VectorClock.EMPTY.tick(device, now),
                        updatedAt = now,
                        deleted = false,
                        deletedAt = null,
                        contentHashBase64 = hash,
                    )
                    existing.deleted || existing.contentHashBase64 != hash -> existing.copy(
                        vectorClock = existing.vectorClock.tick(device, now),
                        updatedAt = now,
                        deleted = false,
                        deletedAt = null,
                        contentHashBase64 = hash,
                    )
                    else -> existing  // unchanged, reuse clock as-is
                }

                if (effectiveMeta !== existing) metaStore.put(syncId, effectiveMeta)

                val (ivB64, ctB64) = CredentialCodec.encrypt(payloadBytes, innerKey)
                Arrays.fill(payloadBytes, 0)  // wipe plaintext post-encryption

                result += SyncEntry(
                    id = syncId,
                    payload = CredentialEntry(
                        credentialId = stripPrefix(syncId),
                        type = type,
                        iv = ivB64,
                        encryptedPayload = ctB64,
                        contentHashBase64 = hash,
                    ),
                    clock = effectiveMeta.vectorClock,
                    deleted = false,
                    deletedAt = null,
                    updatedAt = effectiveMeta.updatedAt,
                )
            }

            // Pass 2: entries in meta but not in vault (locally deleted since last sync)
            val vanished = existingMeta.keys - snapshot.keys
            for (syncId in vanished) {
                // Defensive: never emit a tombstone for a device-local internal secret.
                if (isInternalSecret(stripPrefix(syncId))) continue
                val existing = existingMeta.getValue(syncId)
                val effectiveMeta = if (existing.deleted) {
                    existing  // already tombstoned
                } else {
                    existing.copy(
                        vectorClock = existing.vectorClock.tick(device, now),
                        updatedAt = now,
                        deleted = true,
                        deletedAt = now,
                        contentHashBase64 = "",
                    ).also { metaStore.put(syncId, it) }
                }

                // Tombstone: SyncEntry.payload = null, deleted = true. No ciphertext to carry.
                result += SyncEntry(
                    id = syncId,
                    payload = null,
                    clock = effectiveMeta.vectorClock,
                    deleted = true,
                    deletedAt = effectiveMeta.deletedAt,
                    updatedAt = effectiveMeta.updatedAt,
                )
            }

            result
        } finally {
            Arrays.fill(innerKey, 0)
        }
    }

    override suspend fun applyRemoteEntries(
        entries: List<SyncEntry<CredentialEntry>>,
        sharedSecret: ByteArray,
    ): CredentialApplyResult {
        // Self-heal poisoned meta even when the remote sends nothing this round.
        purgeInternalSecretMeta()
        if (entries.isEmpty()) return CredentialApplyResult(0, emptyList(), 0)

        val innerKey = CredentialCodec.deriveInnerKey(sharedSecret)
        return try {
            var clean = 0
            var skipped = 0
            val conflicts = mutableListOf<MergeResult.Conflict<CredentialEntry>>()
            val localMeta = metaStore.getAll()

            for (remote in entries) {
                val syncId = remote.id
                // Ignore any LAN secret entry/tombstone a peer on an older build may still send.
                if (isInternalSecret(stripPrefix(syncId))) {
                    skipped++
                    continue
                }
                val existing = localMeta[syncId]
                val localEntry: SyncEntry<CredentialEntry>? = existing?.let { meta ->
                    // Rebuild a *synthetic* local SyncEntry for CRDT merge. The payload's
                    // iv/encryptedPayload don't matter for merge equality: equals() looks
                    // only at (id, type, hash).
                    SyncEntry(
                        id = syncId,
                        payload = if (meta.deleted) null else CredentialEntry(
                            credentialId = stripPrefix(syncId),
                            type = meta.type,
                            iv = "",
                            encryptedPayload = "",
                            contentHashBase64 = meta.contentHashBase64,
                        ),
                        clock = meta.vectorClock,
                        deleted = meta.deleted,
                        deletedAt = meta.deletedAt,
                        updatedAt = meta.updatedAt,
                    )
                }

                when (val merged = CrdtEngine.merge(localEntry, remote)) {
                    is MergeResult.Clean -> {
                        val accepted = merged.entry
                        // Apply to vault + meta based on whether the accepted entry is
                        // a tombstone (delete locally) or a fresh payload (write to vault).
                        if (accepted.deleted) {
                            applyTombstone(syncId, accepted, existing?.type)
                            pendingConflictRepository.deleteByEntity(SyncableEntityType.CREDENTIAL, syncId)
                            clean++
                        } else {
                            val remotePayload = accepted.payload
                            if (remotePayload == null) {
                                clean++
                            } else if (accepted === localEntry) {
                                clean++
                            } else if (remotePayload.iv.isNotEmpty() && remotePayload.encryptedPayload.isNotEmpty()) {
                                val decryptedOk = decryptAndWriteToVault(syncId, remotePayload, innerKey)
                                if (decryptedOk) {
                                    metaStore.put(
                                        syncId,
                                        CredentialMeta(
                                            type = remotePayload.type,
                                            vectorClock = accepted.clock,
                                            updatedAt = accepted.updatedAt,
                                            deleted = false,
                                            deletedAt = null,
                                            contentHashBase64 = remotePayload.contentHashBase64,
                                        ),
                                    )
                                    pendingConflictRepository.deleteByEntity(SyncableEntityType.CREDENTIAL, syncId)
                                    clean++
                                } else {
                                    skipped++
                                }
                            } else {
                                // Synthetic local entry (empty iv/ciphertext) won, no action.
                                clean++
                            }
                        }
                    }

                    is MergeResult.Conflict -> {
                        conflicts.add(merged)
                        persistConflict(syncId, merged)
                    }

                    null -> Unit
                }
            }

            CredentialApplyResult(clean, conflicts, skipped)
        } finally {
            Arrays.fill(innerKey, 0)
        }
    }

    // ── Vault I/O helpers ────────────────────────────────────────────────────

    /**
     * Reads every exportable credential (password / private key / certificate)
     * from the vault and returns (syncId → (type, rawBytes)). Bytes are fresh
     * copies owned by the caller. Caller MUST wipe after use.
     *
     * Biometric-only SSH keys and FIDO2 keys are never in the vault (they live
     * in Keystore TEE / on the YubiKey), so [listStoredKeyIds] already excludes
     * them: no additional filtering is needed here.
     */
    private suspend fun currentVaultSnapshot(): Map<String, Pair<CredentialType, ByteArray>> {
        val out = mutableMapOf<String, Pair<CredentialType, ByteArray>>()

        for (id in vault.listStoredCredentialIds()) {
            // Device-local LAN sync secrets share the `pwd_` namespace but are auth keys,
            // not user credentials. They must never enter the credential CRDT.
            if (isInternalSecret(id)) continue
            val pw = vault.getPassword(id) ?: continue
            val bytes = charsToUtf8Bytes(pw)
            Arrays.fill(pw, '\u0000')
            out[KEY_PREFIX_PASSWORD + id] = CredentialType.HOST_PASSWORD to bytes
        }

        for (id in vault.listStoredKeyIds()) {
            val pem = vault.getPrivateKey(id) ?: continue
            out[KEY_PREFIX_PRIVATE_KEY + id] = CredentialType.SSH_PRIVATE_KEY to pem.toByteArray(Charsets.UTF_8)
        }

        for (id in vault.listStoredCertificateIds()) {
            val pem = vault.getCertificate(id) ?: continue
            out[KEY_PREFIX_CERTIFICATE + id] = CredentialType.CERTIFICATE to pem.toByteArray(Charsets.UTF_8)
        }

        for (id in vault.listStoredKeyPassphraseIds()) {
            val pass = vault.getKeyPassphrase(id) ?: continue
            val bytes = charsToUtf8Bytes(pass)
            java.util.Arrays.fill(pass, '\u0000')
            out[KEY_PREFIX_KEY_PASSPHRASE + id] = CredentialType.SSH_PRIVATE_KEY_PASSPHRASE to bytes
        }

        return out
    }

    private suspend fun decryptAndWriteToVault(
        syncId: String,
        entry: CredentialEntry,
        innerKey: ByteArray,
    ): Boolean {
        val plain = try {
            CredentialCodec.decrypt(entry.iv, entry.encryptedPayload, innerKey)
        } catch (e: SyncCodecException) {
            Logger.w(TAG, "Credential decrypt failed for $syncId, skipping (${e.message})")
            return false
        }
        return try {
            val localId = stripPrefix(syncId)
            when (entry.type) {
                CredentialType.HOST_PASSWORD -> {
                    val chars = utf8BytesToChars(plain)
                    try {
                        vault.storePassword(localId, chars)
                    } finally {
                        Arrays.fill(chars, '\u0000')
                    }
                }
                CredentialType.SSH_PRIVATE_KEY -> {
                    vault.storePrivateKey(localId, plain.toString(Charsets.UTF_8))
                }
                CredentialType.SSH_PRIVATE_KEY_PASSPHRASE -> {
                    val chars = utf8BytesToChars(plain)
                    try {
                        vault.storeKeyPassphrase(localId, chars)
                    } finally {
                        Arrays.fill(chars, '\u0000')
                    }
                }
                CredentialType.CERTIFICATE -> {
                    vault.storeCertificate(localId, plain.toString(Charsets.UTF_8))
                }
            }
            true
        } finally {
            Arrays.fill(plain, 0)
        }
    }

    override suspend fun applyRemoteCredentialForced(
        entry: SyncEntry<CredentialEntry>,
        sharedSecret: ByteArray,
    ): Boolean {
        // A device-local LAN secret must never be written or deleted from a peer.
        if (isInternalSecret(stripPrefix(entry.id))) return false
        val payload = entry.payload
        if (entry.deleted || payload == null) {
            // Tombstone: drop local vault entry, stamp meta as deleted with remote clock.
            val existing = metaStore.get(entry.id)
            val type = existing?.type ?: payload?.type ?: CredentialType.HOST_PASSWORD
            applyTombstone(entry.id, entry, type)
            pendingConflictRepository.deleteByEntity(SyncableEntityType.CREDENTIAL, entry.id)
            return true
        }
        val innerKey = CredentialCodec.deriveInnerKey(sharedSecret)
        return try {
            val ok = decryptAndWriteToVault(entry.id, payload, innerKey)
            if (ok) {
                metaStore.put(
                    entry.id,
                    CredentialMeta(
                        type = payload.type,
                        vectorClock = entry.clock,
                        updatedAt = entry.updatedAt,
                        deleted = false,
                        deletedAt = null,
                        contentHashBase64 = payload.contentHashBase64,
                    ),
                )
                pendingConflictRepository.deleteByEntity(SyncableEntityType.CREDENTIAL, entry.id)
            }
            ok
        } finally {
            Arrays.fill(innerKey, 0)
        }
    }

    private suspend fun persistConflict(syncId: String, conflict: MergeResult.Conflict<CredentialEntry>) {
        // Mirrors AndroidSyncRepository.mergeAndPersist: replace any previous pending
        // conflict for the same (type, id) so the UI always reflects the latest state.
        pendingConflictRepository.deleteByEntity(SyncableEntityType.CREDENTIAL, syncId)
        pendingConflictRepository.save(
            PendingConflict(
                id = randomUuid(),
                entityType = SyncableEntityType.CREDENTIAL,
                entityId = syncId,
                localJson = ConflictSerializer.encode(SyncableEntityType.CREDENTIAL, conflict.local),
                remoteJson = ConflictSerializer.encode(SyncableEntityType.CREDENTIAL, conflict.remote),
                detectedAt = nowMs(),
            ),
        )
    }

    private suspend fun applyTombstone(
        syncId: String,
        accepted: SyncEntry<CredentialEntry>,
        knownType: CredentialType?,
    ) {
        // Best-effort vault delete. The vault interface only exposes
        // deleteCredential(id) which (Android) removes the password slot; private
        // keys and certificates don't have dedicated delete methods on the shared
        // contract: write an empty value is not safe, so we rely on the vault
        // impl being forgiving. For now just call deleteCredential and let the
        // repository impl handle the rest.
        val localId = stripPrefix(syncId)
        runCatching { vault.deleteCredential(localId) }
        metaStore.put(
            syncId,
            CredentialMeta(
                type = knownType ?: accepted.payload?.type ?: CredentialType.HOST_PASSWORD,
                vectorClock = accepted.clock,
                updatedAt = accepted.updatedAt,
                deleted = true,
                deletedAt = accepted.deletedAt ?: nowMs(),
                contentHashBase64 = "",
            ),
        )
    }

    // ── UTF-8 / char helpers (avoid String intermediate that can't be wiped) ───

    private fun charsToUtf8Bytes(chars: CharArray): ByteArray {
        val buf = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(chars))
        val out = ByteArray(buf.remaining())
        buf.get(out)
        return out
    }

    private fun utf8BytesToChars(bytes: ByteArray): CharArray {
        val buf = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes))
        val out = CharArray(buf.remaining())
        buf.get(out)
        return out
    }

    /**
     * True for vault entries that are device-local and must never enter the credential
     * CRDT: currently the per-device LAN sync shared secrets. They live in the `pwd_`
     * namespace (so [VaultManager.listStoredCredentialIds] returns them) but are auth
     * keys, not user credentials: syncing them leaks the secret across devices and lets a
     * tombstone delete the very secret LAN sync depends on. [localId] is the raw vault id
     * (already stripped of the `pwd:`/`key:`/… sync-type prefix).
     */
    private fun isInternalSecret(localId: String): Boolean =
        localId.startsWith(LAN_SYNC_SECRET_KEY_PREFIX)

    /**
     * One-shot self-healing cleanup: removes any [CredentialMeta] tracking a device-local
     * internal secret (see [isInternalSecret]). Earlier builds let LAN sync secrets into
     * the credential CRDT, leaving live entries and tombstones in the meta store; left in
     * place those tombstones would keep deleting freshly-enrolled secrets on every sync.
     */
    private suspend fun purgeInternalSecretMeta() {
        for (syncId in metaStore.listIds()) {
            if (isInternalSecret(stripPrefix(syncId))) metaStore.delete(syncId)
        }
    }

    private fun stripPrefix(syncId: String): String = when {
        syncId.startsWith(KEY_PREFIX_PASSWORD) -> syncId.removePrefix(KEY_PREFIX_PASSWORD)
        syncId.startsWith(KEY_PREFIX_PRIVATE_KEY) -> syncId.removePrefix(KEY_PREFIX_PRIVATE_KEY)
        syncId.startsWith(KEY_PREFIX_CERTIFICATE) -> syncId.removePrefix(KEY_PREFIX_CERTIFICATE)
        syncId.startsWith(KEY_PREFIX_KEY_PASSPHRASE) -> syncId.removePrefix(KEY_PREFIX_KEY_PASSPHRASE)
        else -> syncId
    }
}
