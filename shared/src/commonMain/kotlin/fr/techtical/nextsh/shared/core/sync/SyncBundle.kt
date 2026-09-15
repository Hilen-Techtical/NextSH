// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.serialization.Serializable

@Serializable
data class SyncBundle(
    val hosts: List<SyncEntry<Host>>,
    val tunnels: List<SyncEntry<TunnelConfig>>,
    val sshKeys: List<SyncEntry<SshKey>>,
    val snippets: List<SyncEntry<Snippet>>,
    /**
     * Encrypted credential entries (passwords / private keys / certificates).
     *
     * Defaulted to empty for backward compatibility: Phase 2 peers (Android out
     * there, pre-Wave 1bis) neither emit nor understand this field: they see a
     * missing field on decode and kotlinx-serialization uses the default. Wave
     * 1bis peers emit populated lists when both sides are Wave 1bis+, and an
     * empty list when one side is still Phase 2.
     *
     * Each entry's [CredentialEntry.encryptedPayload] is AES-GCM-encrypted with
     * an HKDF-derived inner key (see [CredentialCodec]): the outer envelope
     * (AES-GCM of the whole bundle) is the transport layer, this is the content
     * layer. A failure to decrypt one entry must not abort the whole sync.
     */
    val credentials: List<SyncEntry<CredentialEntry>> = emptyList(),
    /**
     * User-defined terminal themes (metadata only, non-secret). Defaulted to
     * empty for backward compatibility with peers that predate WS7: they emit
     * no field and decode it via the default, exactly like [credentials].
     */
    val customThemes: List<SyncEntry<CustomTerminalTheme>> = emptyList(),
) {
    val totalCount: Int
        get() = hosts.size + tunnels.size + sshKeys.size + snippets.size + credentials.size + customThemes.size

    companion object {
        val EMPTY = SyncBundle(emptyList(), emptyList(), emptyList(), emptyList())
    }
}
