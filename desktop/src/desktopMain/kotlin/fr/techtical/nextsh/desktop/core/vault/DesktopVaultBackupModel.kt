// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.serialization.Serializable

/**
 * Backup payload: JSON shape stays in sync with Android's `VaultBackup`
 * (`app/src/main/kotlin/fr/techtical/nextsh/core/vault/VaultBackupModel.kt`).
 *
 * [keyPassphrases] is required so an encrypted private key imported from a
 * backup stays usable at connect time: without it the PEM is stored but
 * decryption fails with "Decryption of the key failed" (regression caught
 * after Wave 2 + Wave 4 delivery).
 *
 * Both platforms now decode with `ignoreUnknownKeys = true`, so new fields
 * can be added on one side without breaking the other: defaulted here so
 * older backups created without the field still parse.
 */
@Serializable
internal data class VaultBackup(
    val version: Int = 1,
    val exportedAt: Long,
    val hosts: List<Host>,
    val sshKeys: List<SshKey>,
    val tunnels: List<TunnelConfig>,
    val credentials: Map<String, String>,
    val privateKeys: Map<String, String>,
    val keyPassphrases: Map<String, String> = emptyMap(),
)
