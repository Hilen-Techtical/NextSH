// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import kotlinx.serialization.Serializable

/**
 * CRDT metadata persisted locally for each credential (password / private key /
 * certificate) in the vault.
 *
 * Stored **outside** the vault itself: the vault keeps the secret bytes only;
 * this metadata is non-sensitive. The vault is single-source-of-truth for what
 * EXISTS, this store tracks when each entry was last MODIFIED and by whom so
 * that conflicting concurrent changes can be detected during sync.
 *
 * [contentHashBase64] is a SHA-256 over the raw secret bytes. On sync export we
 * recompute the hash and compare against the stored value: if they differ, the
 * user edited the credential locally since the last sync, so we bump
 * [vectorClock] and refresh [updatedAt]. This detects changes without hooking
 * into every vault write site.
 */
@Serializable
data class CredentialMeta(
    val type: CredentialType,
    val vectorClock: VectorClock,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
    /** Base64 URL-safe (no padding): SHA-256 of the raw payload bytes. Empty for deleted entries. */
    val contentHashBase64: String = "",
)
