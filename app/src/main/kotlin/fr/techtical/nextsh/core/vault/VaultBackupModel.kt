// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.SshKey
import fr.techtical.nextsh.domain.model.TunnelConfig
import kotlinx.serialization.Serializable

@Serializable
internal data class VaultBackup(
    val version: Int = 1,
    val exportedAt: Long,
    val hosts: List<Host>,
    val sshKeys: List<SshKey>,
    val tunnels: List<TunnelConfig>,
    val credentials: Map<String, String>,
    val privateKeys: Map<String, String>,
    /**
     * Per-key passphrases for encrypted private keys. Required so that an
     * imported PEM with a passphrase stays usable at connect time. Defaulted
     * to empty so backups produced before the field existed still parse.
     */
    val keyPassphrases: Map<String, String> = emptyMap(),
)
