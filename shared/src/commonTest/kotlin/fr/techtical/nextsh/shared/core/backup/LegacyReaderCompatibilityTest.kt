// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.backup

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// FROZEN copies of the model as published in the tag android-v1.0.1 (shared/.../domain/model/Models.kt)
// and of the DTO the 1.0.x readers (Android and Desktop) use to decode a backup. DO NOT UPDATE THEM to
// follow the current model: their only job is to prove that a v2 file still decodes in a 1.0.x reader
// and reads `version == 2`. If this test breaks, the v1 core changed (a new enum value, a new required
// field...) and Android 1.0.x would show and log the raw decoder message, which copies the decrypted
// secrets. In that case the change must not reach the core of the file (or the version must go to 3).

@Serializable
private data class LegacyHost(
    val id: String,
    val label: String,
    val hostname: String,
    val port: Int = 22,
    val username: String,
    val authType: LegacyAuthType,
    val credentialId: String,
    val group: String? = null,
    val keepAliveSeconds: Int = 30,
    val autoReconnect: Boolean = true,
    val terminalTheme: String = "TECHTICAL_DARK",
    val fido2Mode: LegacyFido2Mode? = null,
    val isFavorite: Boolean = false,
)

@Serializable
private enum class LegacyAuthType { PASSWORD, SSH_KEY, CERTIFICATE, FIDO2, BIOMETRIC_KEY }

@Serializable
private enum class LegacyFido2Mode { HARDWARE_KEY, PASSKEY }

@Serializable
private data class LegacySshKey(
    val id: String,
    val label: String,
    val keyType: LegacySshKeyType,
    val publicKey: String,
    val isBiometric: Boolean = false,
    val keystoreAlias: String? = null,
    val fido2CredentialId: String? = null,
    val fido2RpId: String? = null,
)

@Serializable
private enum class LegacySshKeyType { ED25519, RSA_4096, ECDSA_256, ECDSA_384, ECDSA_521, SK_ED25519, SK_ECDSA_256 }

@Serializable
private data class LegacyTunnelConfig(
    val id: String,
    val label: String,
    val hostId: String,
    val type: LegacyTunnelType,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    val isFavorite: Boolean = false,
)

@Serializable
private enum class LegacyTunnelType { LOCAL_FORWARD, REMOTE_FORWARD, DYNAMIC_SOCKS5 }

@Serializable
private data class LegacyVaultBackupV1(
    val version: Int = 1,
    val exportedAt: Long,
    val hosts: List<LegacyHost>,
    val sshKeys: List<LegacySshKey>,
    val tunnels: List<LegacyTunnelConfig>,
    val credentials: Map<String, String>,
    val privateKeys: Map<String, String>,
    val keyPassphrases: Map<String, String> = emptyMap(),
)

class LegacyReaderCompatibilityTest {

    // The 1.0.x readers: `Json { ignoreUnknownKeys = true }`.
    private val legacyJson = Json { ignoreUnknownKeys = true }

    /** A document that uses EVERY value of every enum of the current model. */
    private fun documentWithAllEnumValues(): BackupDocument {
        val base = sampleDocument()
        val hosts = AuthType.entries.mapIndexed { i, auth ->
            base.hosts.first().copy(id = "host-$i", authType = auth, fido2Mode = Fido2Mode.entries[i % Fido2Mode.entries.size])
        } + Fido2Mode.entries.mapIndexed { i, mode ->
            base.hosts.first().copy(id = "host-fido-$i", authType = AuthType.FIDO2, fido2Mode = mode)
        }
        val keys = SshKeyType.entries.mapIndexed { i, type ->
            SshKey(id = "key-$i", label = "key $i", keyType = type, publicKey = "ssh-ed25519 AAAA fixture")
        }
        val tunnels = TunnelType.entries.mapIndexed { i, type ->
            TunnelConfig(
                id = "tunnel-$i", label = "t$i", hostId = "host-0", type = type,
                localPort = 1000 + i, remoteHost = "127.0.0.1", remotePort = 2000 + i,
            )
        }
        return base.copy(hosts = hosts, sshKeys = keys, tunnels = tunnels)
    }

    @Test
    fun `a published reader decodes a v2 file and reads version 2`() {
        val original = documentWithAllEnumValues()
        val text = BackupCodec.encode(original)

        val legacy = legacyJson.decodeFromString(LegacyVaultBackupV1.serializer(), text)

        assertEquals(2, legacy.version, "1.0.x refuses on version != 1: the key must say 2")
        assertEquals(original.exportedAt, legacy.exportedAt)
        assertEquals(original.hosts.map { it.id }, legacy.hosts.map { it.id })
        assertEquals(original.hosts.map { it.authType.name }, legacy.hosts.map { it.authType.name })
        assertEquals(original.hosts.map { it.fido2Mode?.name }, legacy.hosts.map { it.fido2Mode?.name })
        assertEquals(original.sshKeys.map { it.keyType.name }, legacy.sshKeys.map { it.keyType.name })
        assertEquals(original.tunnels.map { it.type.name }, legacy.tunnels.map { it.type.name })
        assertEquals(original.credentials, legacy.credentials)
        assertEquals(original.privateKeys, legacy.privateKeys)
        assertEquals(original.keyPassphrases, legacy.keyPassphrases)
    }

    @Test
    fun `the frozen model still has every enum value of the current model`() {
        // The other direction of the guard: a value REMOVED from the current model would not break the
        // test above, but would make this reader unable to read files written by 1.0.x.
        assertEquals(LegacyAuthType.entries.map { it.name }.toSet(), AuthType.entries.map { it.name }.toSet())
        assertEquals(LegacyFido2Mode.entries.map { it.name }.toSet(), Fido2Mode.entries.map { it.name }.toSet())
        assertEquals(LegacySshKeyType.entries.map { it.name }.toSet(), SshKeyType.entries.map { it.name }.toSet())
        assertEquals(LegacyTunnelType.entries.map { it.name }.toSet(), TunnelType.entries.map { it.name }.toSet())
    }

    @Test
    fun `a published reader decodes a minimal v2 file`() {
        val minimal = sampleDocument(snippets = emptyList(), customThemes = emptyList(), knownHosts = emptyList(), settings = null)
            .copy(certificates = emptyMap(), keyPassphrases = emptyMap(), producer = null)
        val legacy = legacyJson.decodeFromString(LegacyVaultBackupV1.serializer(), BackupCodec.encode(minimal))
        assertEquals(2, legacy.version)
    }

    @Test
    fun `the v1 core keys keep their names in the written json`() {
        val text = BackupCodec.encode(sampleDocument())
        for (key in listOf("exportedAt", "hosts", "sshKeys", "tunnels", "credentials", "privateKeys", "keyPassphrases")) {
            assertTrue(text.contains("\"$key\""), "core key $key must be written")
        }
    }

    @Test
    fun `this reader decodes what a published writer produces`() {
        // What 1.0.x writes: default Json, so no version key and no new sections.
        val legacy = LegacyVaultBackupV1(
            exportedAt = 1,
            hosts = listOf(
                LegacyHost("h1", "Web", "web.example.test", username = "u", authType = LegacyAuthType.PASSWORD, credentialId = "c"),
            ),
            sshKeys = listOf(LegacySshKey("k1", "Key", LegacySshKeyType.ED25519, "ssh-ed25519 AAAA fixture")),
            tunnels = listOf(LegacyTunnelConfig("t1", "T", "h1", LegacyTunnelType.LOCAL_FORWARD, 1, "127.0.0.1", 2)),
            credentials = mapOf("c" to "FIXTURE"),
            privateKeys = emptyMap(),
        )
        val text = Json.encodeToString(LegacyVaultBackupV1.serializer(), legacy)
        assertTrue(!text.contains("\"version\""), "the old writer omits the default version")
        val decoded = BackupCodec.decode(text).decodedOrFail()
        assertEquals(1, decoded.document.version)
        assertEquals(listOf("h1"), decoded.document.hosts.map { it.id })
    }
}
