// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType

internal const val THEME_UUID = "5f0c1f3e-8a53-4c53-9d3e-0a1b2c3d4e5f"

// 0xFF000000 + rgb as a signed Int.
internal fun opaque(rgb: Int): Int = (0xFF000000L or rgb.toLong()).toInt()

internal fun sampleTheme(
    id: String = THEME_UUID,
    name: String = "Fixture theme",
    ansi: List<Int> = List(16) { opaque(it * 0x111111) },
    selectionBg: Int = 0x44FFFFFF,
): BackupCustomTheme = BackupCustomTheme(
    id = id,
    name = name,
    background = opaque(0x101010),
    foreground = opaque(0xE0E0E0),
    cursor = opaque(0xFFCC00),
    selectionBg = selectionBg,
    ansi = ansi,
    createdAt = 1_700_000_000_000,
)

internal fun sampleSnippet(
    id: String = "snippet-1",
    label: String = "Restart",
    command: String = "sudo systemctl restart nginx",
    category: String? = "ops",
    hostId: String? = "host-1",
): BackupSnippet = BackupSnippet(id, label, command, category, hostId, createdAt = 1_700_000_000_000)

internal fun sampleKnownHost(
    hostPort: String = "server.example.test:22",
    keyType: String = "ssh-ed25519",
    publicKey: String = "AAAAC3NzaC1lZDI1NTE5AAAAIEuIMek2W/uP4SbNqKjs93fVaAPm7ymy6RBjcJZssbhi",
    addedAt: Long = 0,
): BackupKnownHost = BackupKnownHost(hostPort, keyType, publicKey, addedAt)

internal fun sampleDocument(
    version: Int = 1,
    snippets: List<BackupSnippet> = listOf(sampleSnippet()),
    customThemes: List<BackupCustomTheme> = listOf(sampleTheme()),
    knownHosts: List<BackupKnownHost> = listOf(sampleKnownHost()),
    settings: BackupSettings? = BackupSettings(
        common = BackupCommonSettings(connectionTimeoutSeconds = 15),
        android = BackupAndroidSettings(24, 10_000, 30, true),
        desktop = BackupDesktopSettings(14, "FR"),
    ),
): BackupDocument = BackupDocument(
    version = version,
    exportedAt = 1_762_000_000_000,
    hosts = listOf(
        Host(
            id = "host-1",
            label = "Web",
            hostname = "web.example.test",
            username = "deploy",
            authType = AuthType.PASSWORD,
            credentialId = "cred-1",
            terminalTheme = THEME_UUID,
        ),
    ),
    sshKeys = listOf(
        SshKey(id = "key-1", label = "Laptop", keyType = SshKeyType.ED25519, publicKey = "ssh-ed25519 AAAA fixture"),
    ),
    tunnels = listOf(
        TunnelConfig(
            id = "tunnel-1",
            label = "DB",
            hostId = "host-1",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 5432,
            remoteHost = "127.0.0.1",
            remotePort = 5432,
        ),
    ),
    credentials = mapOf("cred-1" to "FIXTURE-password"),
    privateKeys = mapOf("key-1" to "FIXTURE-NOT-A-REAL-PRIVATE-KEY"),
    keyPassphrases = mapOf("key-1" to "FIXTURE-passphrase"),
    certificates = mapOf("cert-1" to "FIXTURE-NOT-A-REAL-CERTIFICATE"),
    snippets = snippets,
    customThemes = customThemes,
    knownHosts = knownHosts,
    settings = settings,
    producer = BackupProducer("DESKTOP", "1.1.0"),
)

internal fun BackupDecodeResult.decodedOrFail(): BackupDecodeResult.Decoded =
    this as? BackupDecodeResult.Decoded ?: kotlin.test.fail("Expected Decoded but was $this")

internal fun validOrFail(document: BackupDocument, check: (BackupKnownHost) -> Boolean = { true }): ValidatedBackup {
    val result = BackupValidator.validate(document, check)
    return (result as? BackupValidationResult.Valid)?.backup ?: kotlin.test.fail("Expected Valid but was $result")
}
