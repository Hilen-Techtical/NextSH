// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import fr.techtical.nextsh.desktop.core.crypto.DesktopCryptoUtils
import fr.techtical.nextsh.desktop.core.ssh.DesktopKnownHostsStore
import fr.techtical.nextsh.shared.core.backup.BackupEnvelope
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType

/** Shared by the restore tests that run against a real database (in memory and on a file). */
internal const val RESTORE_TEST_PASSPHRASE = "restore-test-pass"

/** Builds a real `.nextsh` blob through the exporter, from in-memory fakes holding the backup content. */
internal suspend fun buildBackupBlob(
    hosts: List<Host> = emptyList(),
    keys: List<SshKey> = emptyList(),
    tunnels: List<TunnelConfig> = emptyList(),
    credentials: Map<String, String> = emptyMap(),
    privateKeys: Map<String, String> = emptyMap(),
    snippets: List<Snippet> = emptyList(),
    customThemes: List<CustomTerminalTheme> = emptyList(),
    knownHosts: DesktopKnownHostsStore = inMemoryKnownHostsStore(),
    settings: BackupSettingsAccess = FakeBackupSettings(),
): ByteArray {
    val source = FakeVaultManagerMem().apply {
        credentials.forEach { (id, password) -> storePassword(id, password.toCharArray()) }
        privateKeys.forEach { (id, pem) -> storePrivateKey(id, pem) }
    }
    return exporterOver(
        vault = source,
        sshKeys = FakeSshKeyRepositoryMem(keys),
        hosts = FakeHostRepositoryMem(hosts),
        tunnels = FakeTunnelRepositoryMem(tunnels),
        snippets = FakeSnippetRepositoryMem(snippets),
        customThemes = FakeCustomThemeRepositoryMem(customThemes),
        knownHosts = knownHosts,
        settings = settings,
    ).export(RESTORE_TEST_PASSPHRASE.toCharArray())
}

internal fun backupHost(id: String, label: String, isFavorite: Boolean = false) = Host(
    id = id,
    label = label,
    hostname = "$id.backup",
    username = "root",
    authType = AuthType.PASSWORD,
    credentialId = "cred-$id",
    isFavorite = isFavorite,
)

internal fun backupTunnel(id: String, hostId: String, label: String) = TunnelConfig(
    id = id,
    label = label,
    hostId = hostId,
    type = TunnelType.LOCAL_FORWARD,
    localPort = 9090,
    remoteHost = "db.internal",
    remotePort = 5432,
)

internal fun backupKey(id: String, label: String) = SshKey(
    id = id,
    label = label,
    keyType = SshKeyType.ED25519,
    publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA backup",
)

internal const val BACKUP_TEST_CREATED_AT = 1_700_000_000_000L

internal fun backupSnippet(id: String, label: String, command: String = "echo $label", hostId: String? = null) = Snippet(
    id = id,
    label = label,
    command = command,
    category = null,
    hostId = hostId,
    createdAt = BACKUP_TEST_CREATED_AT,
)

/** [id] must be a UUID: the backup validator refuses any other theme id. */
internal fun backupTheme(id: String, name: String, background: Int = 0xFF101010.toInt()) = CustomTerminalTheme(
    id = id,
    name = name,
    background = background,
    foreground = 0xFFE0E0E0.toInt(),
    cursor = 0xFFFFD000.toInt(),
    selectionBg = 0x44FFFFFF,
    ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() or (it * 0x101010) },
    createdAt = BACKUP_TEST_CREATED_AT,
)

/** Hand-builds a `.nextsh` file around [json], the way the exporter does (frame + AES-GCM, key from [passphrase]). */
internal fun encryptBackupJson(json: String, passphrase: String): ByteArray {
    val salt = DesktopCryptoUtils.generateSalt(BackupEnvelope.DEFAULT_SALT_BYTES)
    val key = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase.toCharArray(), salt)
    try {
        return BackupEnvelope.pack(salt, DesktopCryptoUtils.encryptAesGcm(json.toByteArray(Charsets.UTF_8), key))
    } finally {
        DesktopCryptoUtils.secureWipe(key)
    }
}

/** The clear JSON inside a `.nextsh` file (tests only: it holds the secrets in clear text). */
internal fun decryptBackupJson(file: ByteArray, passphrase: String): String {
    val frame = BackupEnvelope.unpack(file) ?: error("not a backup frame")
    val key = DesktopCryptoUtils.deriveKeyFromPassphrase(passphrase.toCharArray(), frame.salt)
    try {
        return DesktopCryptoUtils.decryptAesGcm(frame.sealed, key).toString(Charsets.UTF_8)
    } finally {
        DesktopCryptoUtils.secureWipe(key)
    }
}
