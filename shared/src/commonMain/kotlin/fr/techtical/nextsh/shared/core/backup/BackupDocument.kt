// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The decrypted JSON document of a `.nextsh` backup, format version 2.
 *
 * The first block (`version` to `keyPassphrases`) is the v1 core with the SAME names and types as the
 * 1.0.1 DTO: published readers must keep decoding a v2 file (see [BackupFormat]). Everything after it
 * is optional and defaults to empty, so a v1 file reads as a v2 file without the new sections.
 *
 * Do not add a field with a default id or a default timestamp in the new sections: the DTOs below
 * have none on purpose, so an entry without `id` is invalid instead of getting a random one.
 *
 * Contains secrets in clear text (passwords, private keys): never log it, never put it in an error.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupDocument(
    /**
     * Format major version. The default (1) is the value assumed for files without the key; the
     * writer always emits the key ([EncodeDefault]) and [BackupCodec.encode] sets it to
     * [BackupFormat.CURRENT_VERSION].
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val version: Int = BackupFormat.DEFAULT_VERSION,
    val exportedAt: Long,
    val hosts: List<Host>,
    val sshKeys: List<SshKey>,
    val tunnels: List<TunnelConfig>,
    /** Password credential id to secret. Never contains the LAN sync secret. */
    val credentials: Map<String, String>,
    /** Private key id to PEM. */
    val privateKeys: Map<String, String>,
    /** Key id to passphrase of an encrypted private key. */
    val keyPassphrases: Map<String, String> = emptyMap(),

    // New in version 2 (all optional).

    /** SSH certificate id to certificate content (same treatment as [privateKeys]). */
    val certificates: Map<String, String> = emptyMap(),
    val snippets: List<BackupSnippet> = emptyList(),
    val customThemes: List<BackupCustomTheme> = emptyList(),
    val knownHosts: List<BackupKnownHost> = emptyList(),
    val settings: BackupSettings? = null,
    val producer: BackupProducer? = null,
) {
    // Hand written: the generated one would print the whole document, secrets included.
    override fun toString(): String =
        "BackupDocument(version=$version, hosts=${hosts.size}, snippets=${snippets.size}, " +
            "customThemes=${customThemes.size}, knownHosts=${knownHosts.size})"
}

/** Who wrote the backup. Informational only (diagnostics). */
@Serializable
data class BackupProducer(
    /** `ANDROID` or `DESKTOP` (kept as text so a future platform does not break decoding). */
    val platform: String,
    val appVersion: String,
)

/** A snippet. No default for [id] or [createdAt]: an entry without id is invalid. */
@Serializable
data class BackupSnippet(
    val id: String,
    val label: String,
    val command: String,
    val category: String? = null,
    val hostId: String? = null,
    val createdAt: Long,
)

/**
 * A custom terminal theme. Colors are ARGB ints: background, foreground, cursor and every ANSI entry
 * must have alpha 0xFF (the editors force it); [selectionBg] keeps any alpha (translucent highlight).
 */
@Serializable
data class BackupCustomTheme(
    val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val selectionBg: Int,
    val ansi: List<Int>,
    val createdAt: Long,
)

/**
 * A known host in a platform independent form: [keyType] is the SSH wire name and [publicKey] the
 * base64 of the SSH public key blob (the content of the second field of an OpenSSH public key line).
 */
@Serializable
data class BackupKnownHost(
    val hostPort: String,
    val keyType: String,
    val publicKey: String,
    val addedAt: Long = 0,
)

/**
 * Allow-list of the settings carried by a backup. Every field is optional: absent means "not
 * carried". Security settings (auto-lock, clipboard clearing, capture hiding, sync, onboarding,
 * window geometry) and the minimize-to-tray choice are NEVER exported nor imported. An importer applies [common] and the section of
 * its own platform only.
 */
@Serializable
data class BackupSettings(
    val common: BackupCommonSettings? = null,
    val android: BackupAndroidSettings? = null,
    val desktop: BackupDesktopSettings? = null,
) {
    /** Number of values an importer of [platform] would apply (common plus its own section). */
    fun countFor(platform: BackupPlatform): Int {
        val own = when (platform) {
            BackupPlatform.ANDROID -> android?.count() ?: 0
            BackupPlatform.DESKTOP -> desktop?.count() ?: 0
        }
        return (common?.count() ?: 0) + own
    }
}

/** Settings with the same meaning on both platforms. */
@Serializable
data class BackupCommonSettings(
    /** Connection timeout in seconds: 5, 10, 15, 20, 25 or 30. */
    val connectionTimeoutSeconds: Int? = null,
) {
    internal fun count(): Int = listOfNotNull(connectionTimeoutSeconds).size
}

/** Android only. */
@Serializable
data class BackupAndroidSettings(
    /** Terminal font size, 12..46. */
    val terminalFontSize: Int? = null,
    /** Scrollback lines, 1000..100000. */
    val scrollbackLines: Int? = null,
    /** Keep-alive interval in seconds, 0..120. */
    val keepAliveIntervalSeconds: Int? = null,
    val autoReconnect: Boolean? = null,
) {
    internal fun count(): Int =
        listOfNotNull(terminalFontSize, scrollbackLines, keepAliveIntervalSeconds, autoReconnect).size
}

/** Desktop only. */
@Serializable
data class BackupDesktopSettings(
    /** Terminal font size in pixels, 10..20. */
    val terminalFontSizePx: Int? = null,
    /** `SYSTEM`, `FR` or `EN` (takes effect at the next start). */
    val language: String? = null,
) {
    internal fun count(): Int = listOfNotNull(terminalFontSizePx, language).size
}
