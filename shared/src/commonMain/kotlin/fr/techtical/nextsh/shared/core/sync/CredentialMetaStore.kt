// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

/**
 * Persists [CredentialMeta] keyed by credentialId.
 *
 * The store is non-sensitive: it holds CRDT metadata (clocks, timestamps,
 * content hashes) but no secret bytes. It lives OUTSIDE the vault: the vault
 * holds the payload, this store tracks when each payload was last touched and
 * who touched it.
 *
 * Platform impls (`:app` DataStore-backed, `:desktop` JSON file) must persist
 * atomically: a crash mid-write must not corrupt the store. Callers have no
 * way to recover from that, and losing clocks silently would let stale remote
 * writes clobber local edits on the next sync.
 */
interface CredentialMetaStore {
    suspend fun get(credentialId: String): CredentialMeta?
    suspend fun put(credentialId: String, meta: CredentialMeta)
    suspend fun delete(credentialId: String)
    /** All credential IDs ever tracked (includes tombstoned entries). */
    suspend fun listIds(): Set<String>
    suspend fun getAll(): Map<String, CredentialMeta>
}
