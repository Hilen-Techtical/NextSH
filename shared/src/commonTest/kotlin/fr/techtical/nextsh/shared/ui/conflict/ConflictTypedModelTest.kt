// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.ui.conflict

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.CredentialEntry
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import fr.techtical.nextsh.shared.ui.conflict.ConflictField.*
import fr.techtical.nextsh.shared.ui.conflict.ConflictValue.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests of the typed conflict model ([decodeTypedEntity], [diffTypedFields]) and of its
 * link with the legacy French API, which is rendered from it. The legacy API is used on
 * purpose, hence the suppression: when it is removed, the legacy assertions go with it.
 */
@Suppress("DEPRECATION")
class ConflictTypedModelTest {

    private val ellipsis = "…"
    private val arrow = "→"

    // ── Samples, one per entity type ──────────────────────────────────────

    private val customThemeId = "3f2a9c1b-7d4e-4a55-9b0c-1234567890ab"

    private val host = Host(
        id = "host-1",
        label = "prod",
        hostname = "srv.example.org",
        port = 2222,
        username = "root",
        authType = AuthType.SSH_KEY,
        credentialId = "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
        group = "infra",
        keepAliveSeconds = 45,
        autoReconnect = false,
        terminalTheme = "DRACULA",
        fido2Mode = Fido2Mode.HARDWARE_KEY,
        isFavorite = true,
    )

    private val tunnel = TunnelConfig(
        id = "tunnel-1",
        label = "Grafana",
        hostId = "host-0123456789abcdef",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 3000,
        remoteHost = "localhost",
        remotePort = 3001,
        autoStart = true,
        openBrowserOnConnect = false,
        keepAliveAfterBrowserClose = true,
        isFavorite = false,
        browserPath = "/d/abc?orgId=1",
    )

    private val sshKey = SshKey(
        id = "key-1",
        label = "deploy",
        keyType = SshKeyType.ED25519,
        publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
    )

    private val snippet = Snippet(
        id = "snippet-1",
        label = "restart",
        command = "systemctl restart nginx",
        category = "ops",
        createdAt = 1L,
    )

    private val theme = CustomTerminalTheme(
        id = customThemeId,
        name = "Night",
        background = 0xFF0F0F0F.toInt(),
        foreground = 0xFFE0E0E0.toInt(),
        cursor = 0xFFFFFFFF.toInt(),
        selectionBg = 0xFF333333.toInt(),
        ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() },
        createdAt = 1L,
    )

    // Distinctive values, so that a leak into the model is found by searching for them.
    private val credential = CredentialEntry(
        credentialId = "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
        type = CredentialType.SSH_PRIVATE_KEY,
        iv = "IV-SENTINEL-0123",
        encryptedPayload = "PAYLOAD-SENTINEL-4567",
        contentHashBase64 = "HASH-SENTINEL-89ab",
    )

    private val samples: Map<SyncableEntityType, Any> = mapOf(
        SyncableEntityType.HOST to host,
        SyncableEntityType.TUNNEL to tunnel,
        SyncableEntityType.SSH_KEY to sshKey,
        SyncableEntityType.SNIPPET to snippet,
        SyncableEntityType.CREDENTIAL to credential,
        SyncableEntityType.CUSTOM_TERMINAL_THEME to theme,
    )

    private fun encodeEntry(type: SyncableEntityType, payload: Any?): String {
        val entry = SyncEntry(
            id = "entity-1",
            payload = payload,
            clock = VectorClock.EMPTY.tick("device-A", 1L),
            deleted = payload == null,
            deletedAt = if (payload == null) 5L else null,
            updatedAt = 1_000L,
        )
        return ConflictSerializer.encode(type, entry)
    }

    private fun typed(type: SyncableEntityType, payload: Any?): TypedEntity =
        assertNotNull(decodeTypedEntity(type, encodeEntry(type, payload)))

    private fun legacy(type: SyncableEntityType, payload: Any?): DecodedEntity =
        assertNotNull(decodeEntity(type, encodeEntry(type, payload)))

    private fun TypedEntity.fieldList(): List<ConflictField> = fields.map { it.field }

    private fun TypedEntity.value(field: ConflictField): ConflictValue? =
        fields.firstOrNull { it.field == field }?.value

    private fun hostValue(field: ConflictField, h: Host = host) =
        typed(SyncableEntityType.HOST, h).value(field)

    private fun tunnelValue(field: ConflictField, t: TunnelConfig = tunnel) =
        typed(SyncableEntityType.TUNNEL, t).value(field)

    // ── Every legacy row has its typed row ────────────────────────────────

    /** For each entity type: the typed fields, in order, and the legacy row name of each. */
    private val correspondence: Map<SyncableEntityType, List<Pair<ConflictField, String>>> = mapOf(
        SyncableEntityType.HOST to listOf(
            LABEL to "label",
            ADDRESS to "hôte",
            AUTH_TYPE to "authType",
            GROUP to "groupe",
            FAVORITE to "favori",
            KEEP_ALIVE to "keep-alive",
            AUTO_RECONNECT to "reconnexion automatique",
            TERMINAL_THEME to "thème",
            FIDO2_MODE to "mode FIDO2",
            CREDENTIAL_REFERENCE to "clé ou mot de passe",
        ),
        SyncableEntityType.TUNNEL to listOf(
            LABEL to "label",
            TUNNEL_TYPE to "type",
            FORWARDING to "forwarding",
            HOST_REFERENCE to "hostId",
            BROWSER_PATH to "chemin navigateur",
            FAVORITE to "favori",
            AUTO_START to "démarrage automatique",
            OPEN_BROWSER_ON_CONNECT to "ouvrir le navigateur",
            KEEP_ALIVE_AFTER_BROWSER_CLOSE to "actif après navigateur",
        ),
        SyncableEntityType.SSH_KEY to listOf(
            LABEL to "label",
            KEY_TYPE to "keyType",
            PUBLIC_KEY to "publicKey",
        ),
        SyncableEntityType.SNIPPET to listOf(
            LABEL to "label",
            CATEGORY to "catégorie",
            COMMAND to "commande",
        ),
        SyncableEntityType.CUSTOM_TERMINAL_THEME to listOf(
            NAME to "nom",
            BACKGROUND to "fond",
            FOREGROUND to "texte",
        ),
        SyncableEntityType.CREDENTIAL to listOf(
            CREDENTIAL_TYPE to "type",
            CREDENTIAL_ID to "credentialId",
            ENCRYPTED_CONTENT to "chiffré",
        ),
    )

    @Test
    fun `every legacy row of every entity type has its typed row in the same order`() {
        for ((type, payload) in samples) {
            val expected = correspondence.getValue(type)

            assertEquals(expected.map { it.first }, typed(type, payload).fieldList(), "typed fields of $type")
            assertEquals(expected.map { it.second }, legacy(type, payload).fields.map { it.first }, "legacy rows of $type")
        }
    }

    @Test
    fun `every deleted entity has a single deleted row in both APIs`() {
        for (type in SyncableEntityType.entries) {
            assertEquals(
                listOf(ConflictFieldRow(DELETED, YesNo(true))),
                typed(type, null).fields,
                "typed rows of a deleted $type",
            )
            assertEquals(
                listOf("supprimé" to "oui"),
                legacy(type, null).fields,
                "legacy rows of a deleted $type",
            )
        }
    }

    // ── Titles ────────────────────────────────────────────────────────────

    @Test
    fun `the title is the label typed by the user`() {
        assertEquals(ConflictTitle.Label("prod"), typed(SyncableEntityType.HOST, host).title)
        assertEquals(ConflictTitle.Label("Grafana"), typed(SyncableEntityType.TUNNEL, tunnel).title)
        assertEquals(ConflictTitle.Label("deploy"), typed(SyncableEntityType.SSH_KEY, sshKey).title)
        assertEquals(ConflictTitle.Label("restart"), typed(SyncableEntityType.SNIPPET, snippet).title)
        assertEquals(ConflictTitle.Label("Night"), typed(SyncableEntityType.CUSTOM_TERMINAL_THEME, theme).title)
    }

    @Test
    fun `a credential is titled by its type`() {
        assertEquals(
            ConflictTitle.Credential(CredentialType.SSH_PRIVATE_KEY),
            typed(SyncableEntityType.CREDENTIAL, credential).title,
        )
        for (credentialType in CredentialType.entries) {
            val entry = CredentialEntry("id-$credentialType", credentialType, "iv", "payload", "hash")

            assertEquals(
                ConflictTitle.Credential(credentialType),
                typed(SyncableEntityType.CREDENTIAL, entry).title,
            )
        }
    }

    @Test
    fun `a deleted entity has the deleted title`() {
        for (type in SyncableEntityType.entries) {
            assertEquals(ConflictTitle.Deleted, typed(type, null).title, "title of a deleted $type")
        }
    }

    @Test
    fun `the legacy primary label is the typed title rendered`() {
        assertEquals("prod", legacy(SyncableEntityType.HOST, host).primaryLabel)
        assertEquals("Night", legacy(SyncableEntityType.CUSTOM_TERMINAL_THEME, theme).primaryLabel)
        assertEquals("Clé privée SSH", legacy(SyncableEntityType.CREDENTIAL, credential).primaryLabel)
        assertEquals("(supprimé)", legacy(SyncableEntityType.TUNNEL, null).primaryLabel)
    }

    // ── Typed values ──────────────────────────────────────────────────────

    @Test
    fun `a host is decoded into typed values`() {
        assertEquals(
            listOf(
                ConflictFieldRow(LABEL, Text("prod")),
                ConflictFieldRow(ADDRESS, Text("root@srv.example.org:2222")),
                ConflictFieldRow(AUTH_TYPE, AuthMethod(AuthType.SSH_KEY)),
                ConflictFieldRow(GROUP, Text("infra")),
                ConflictFieldRow(FAVORITE, YesNo(true)),
                ConflictFieldRow(KEEP_ALIVE, Seconds(45)),
                ConflictFieldRow(AUTO_RECONNECT, YesNo(false)),
                ConflictFieldRow(TERMINAL_THEME, PresetTheme("DRACULA")),
                ConflictFieldRow(FIDO2_MODE, Fido2(Fido2Mode.HARDWARE_KEY)),
                ConflictFieldRow(CREDENTIAL_REFERENCE, Text("0a1b2c3d-4e5$ellipsis")),
            ),
            typed(SyncableEntityType.HOST, host).fields,
        )
    }

    @Test
    fun `a host is still rendered with the legacy wording`() {
        assertEquals(
            listOf(
                "label" to "prod",
                "hôte" to "root@srv.example.org:2222",
                "authType" to "SSH_KEY",
                "groupe" to "infra",
                "favori" to "oui",
                "keep-alive" to "45 s",
                "reconnexion automatique" to "non",
                "thème" to "DRACULA",
                "mode FIDO2" to "clé matérielle",
                "clé ou mot de passe" to "0a1b2c3d-4e5$ellipsis",
            ),
            legacy(SyncableEntityType.HOST, host).fields,
        )
    }

    @Test
    fun `a tunnel is decoded into typed values`() {
        assertEquals(
            listOf(
                ConflictFieldRow(LABEL, Text("Grafana")),
                ConflictFieldRow(TUNNEL_TYPE, TunnelKind(TunnelType.LOCAL_FORWARD)),
                ConflictFieldRow(FORWARDING, Text("3000 $arrow localhost:3001")),
                ConflictFieldRow(HOST_REFERENCE, Text("host-0123456$ellipsis")),
                ConflictFieldRow(BROWSER_PATH, Text("/d/abc?orgId=1")),
                ConflictFieldRow(FAVORITE, YesNo(false)),
                ConflictFieldRow(AUTO_START, YesNo(true)),
                ConflictFieldRow(OPEN_BROWSER_ON_CONNECT, YesNo(false)),
                ConflictFieldRow(KEEP_ALIVE_AFTER_BROWSER_CLOSE, YesNo(true)),
            ),
            typed(SyncableEntityType.TUNNEL, tunnel).fields,
        )
    }

    @Test
    fun `a tunnel is still rendered with the legacy wording`() {
        assertEquals(
            listOf(
                "label" to "Grafana",
                "type" to "LOCAL_FORWARD",
                "forwarding" to "3000 $arrow localhost:3001",
                "hostId" to "host-0123456$ellipsis",
                "chemin navigateur" to "/d/abc?orgId=1",
                "favori" to "non",
                "démarrage automatique" to "oui",
                "ouvrir le navigateur" to "non",
                "actif après navigateur" to "oui",
            ),
            legacy(SyncableEntityType.TUNNEL, tunnel).fields,
        )
    }

    @Test
    fun `an SSH key is decoded into typed values and keeps the legacy wording`() {
        assertEquals(
            listOf(
                ConflictFieldRow(LABEL, Text("deploy")),
                ConflictFieldRow(KEY_TYPE, SshKeyKind(SshKeyType.ED25519)),
                ConflictFieldRow(PUBLIC_KEY, Text("ssh-ed25519 AAAAC3NzaC1lZDI1NT$ellipsis")),
            ),
            typed(SyncableEntityType.SSH_KEY, sshKey).fields,
        )
        assertEquals(
            listOf(
                "label" to "deploy",
                "keyType" to "ED25519",
                "publicKey" to "ssh-ed25519 AAAAC3NzaC1lZDI1NT$ellipsis",
            ),
            legacy(SyncableEntityType.SSH_KEY, sshKey).fields,
        )
    }

    @Test
    fun `a short public key is shown whole`() {
        val shortKey = sshKey.copy(publicKey = "ssh-ed25519 AAAA")

        assertEquals(Text("ssh-ed25519 AAAA"), typed(SyncableEntityType.SSH_KEY, shortKey).value(PUBLIC_KEY))
    }

    @Test
    fun `a snippet is decoded into typed values and keeps the legacy wording`() {
        assertEquals(
            listOf(
                ConflictFieldRow(LABEL, Text("restart")),
                ConflictFieldRow(CATEGORY, Text("ops")),
                ConflictFieldRow(COMMAND, Text("systemctl restart nginx")),
            ),
            typed(SyncableEntityType.SNIPPET, snippet).fields,
        )
        assertEquals(
            listOf(
                "label" to "restart",
                "catégorie" to "ops",
                "commande" to "systemctl restart nginx",
            ),
            legacy(SyncableEntityType.SNIPPET, snippet).fields,
        )
    }

    @Test
    fun `a snippet without category has no category value`() {
        val noCategory = snippet.copy(category = null)

        assertEquals(None, typed(SyncableEntityType.SNIPPET, noCategory).value(CATEGORY))
        assertEquals("-", legacy(SyncableEntityType.SNIPPET, noCategory).fields.toMap()["catégorie"])
    }

    @Test
    fun `a long snippet command is cut at 60 characters`() {
        val long = snippet.copy(command = "c".repeat(100))

        assertEquals(Text("c".repeat(60) + ellipsis), typed(SyncableEntityType.SNIPPET, long).value(COMMAND))
        assertEquals("c".repeat(60) + ellipsis, legacy(SyncableEntityType.SNIPPET, long).fields.toMap()["commande"])
    }

    @Test
    fun `a custom terminal theme is decoded into typed values and keeps the legacy wording`() {
        assertEquals(
            listOf(
                ConflictFieldRow(NAME, Text("Night")),
                ConflictFieldRow(BACKGROUND, Text("#0F0F0F")),
                ConflictFieldRow(FOREGROUND, Text("#E0E0E0")),
            ),
            typed(SyncableEntityType.CUSTOM_TERMINAL_THEME, theme).fields,
        )
        assertEquals(
            listOf("nom" to "Night", "fond" to "#0F0F0F", "texte" to "#E0E0E0"),
            legacy(SyncableEntityType.CUSTOM_TERMINAL_THEME, theme).fields,
        )
    }

    @Test
    fun `a credential is decoded into typed values and keeps the legacy wording`() {
        assertEquals(
            listOf(
                ConflictFieldRow(CREDENTIAL_TYPE, CredentialKind(CredentialType.SSH_PRIVATE_KEY)),
                ConflictFieldRow(CREDENTIAL_ID, Text("9f8e7d6c-5b4$ellipsis")),
                ConflictFieldRow(ENCRYPTED_CONTENT, Encrypted),
            ),
            typed(SyncableEntityType.CREDENTIAL, credential).fields,
        )
        assertEquals(
            listOf(
                "type" to "Clé privée SSH",
                "credentialId" to "9f8e7d6c-5b4$ellipsis",
                "chiffré" to "oui (contenu non affichable)",
            ),
            legacy(SyncableEntityType.CREDENTIAL, credential).fields,
        )
    }

    @Test
    fun `every credential type has a typed value and a legacy name`() {
        val legacyNames = mapOf(
            CredentialType.HOST_PASSWORD to "Mot de passe",
            CredentialType.SSH_PRIVATE_KEY to "Clé privée SSH",
            CredentialType.SSH_PRIVATE_KEY_PASSPHRASE to "Passphrase de clé SSH",
            CredentialType.CERTIFICATE to "Certificat SSH",
        )
        assertEquals(CredentialType.entries.toSet(), legacyNames.keys)

        for ((credentialType, legacyName) in legacyNames) {
            val entry = credential.copy(type = credentialType)

            assertEquals(
                CredentialKind(credentialType),
                typed(SyncableEntityType.CREDENTIAL, entry).value(CREDENTIAL_TYPE),
            )
            assertEquals(legacyName, legacy(SyncableEntityType.CREDENTIAL, entry).fields.toMap()["type"])
        }
    }

    // ── Host values in detail ─────────────────────────────────────────────

    @Test
    fun `a host without group has no group value`() {
        assertEquals(None, hostValue(GROUP, host.copy(group = null)))
        assertEquals(Text("infra"), hostValue(GROUP))
    }

    @Test
    fun `an empty group is a value and not the absence of one`() {
        assertEquals(Text(""), hostValue(GROUP, host.copy(group = "")))
    }

    @Test
    fun `the FIDO2 mode is typed and none stays none`() {
        assertEquals(Fido2(Fido2Mode.HARDWARE_KEY), hostValue(FIDO2_MODE, host.copy(fido2Mode = Fido2Mode.HARDWARE_KEY)))
        assertEquals(Fido2(Fido2Mode.PASSKEY), hostValue(FIDO2_MODE, host.copy(fido2Mode = Fido2Mode.PASSKEY)))
        assertEquals(None, hostValue(FIDO2_MODE, host.copy(fido2Mode = null)))

        assertEquals("clé matérielle", legacy(SyncableEntityType.HOST, host.copy(fido2Mode = Fido2Mode.HARDWARE_KEY)).fields.toMap()["mode FIDO2"])
        assertEquals("passkey", legacy(SyncableEntityType.HOST, host.copy(fido2Mode = Fido2Mode.PASSKEY)).fields.toMap()["mode FIDO2"])
        assertEquals("-", legacy(SyncableEntityType.HOST, host.copy(fido2Mode = null)).fields.toMap()["mode FIDO2"])
    }

    @Test
    fun `every authentication type is typed`() {
        for (authType in AuthType.entries) {
            assertEquals(AuthMethod(authType), hostValue(AUTH_TYPE, host.copy(authType = authType)))
            assertEquals(
                authType.name,
                legacy(SyncableEntityType.HOST, host.copy(authType = authType)).fields.toMap()["authType"],
            )
        }
    }

    @Test
    fun `the keepalive is the raw number of seconds`() {
        assertEquals(Seconds(0), hostValue(KEEP_ALIVE, host.copy(keepAliveSeconds = 0)))
        assertEquals(Seconds(300), hostValue(KEEP_ALIVE, host.copy(keepAliveSeconds = 300)))
        assertEquals("0 s", legacy(SyncableEntityType.HOST, host.copy(keepAliveSeconds = 0)).fields.toMap()["keep-alive"])
    }

    @Test
    fun `a built-in theme is a preset and a UUID is a custom theme with a short id`() {
        assertEquals(PresetTheme("DRACULA"), hostValue(TERMINAL_THEME, host.copy(terminalTheme = "DRACULA")))
        assertEquals(CustomTheme("3f2a9c1b"), hostValue(TERMINAL_THEME, host.copy(terminalTheme = customThemeId)))
        assertEquals(
            "personnalisé (3f2a9c1b$ellipsis)",
            legacy(SyncableEntityType.HOST, host.copy(terminalTheme = customThemeId)).fields.toMap()["thème"],
        )
    }

    @Test
    fun `a custom theme id is never carried whole`() {
        val value = assertNotNull(hostValue(TERMINAL_THEME, host.copy(terminalTheme = customThemeId)))

        assertFalse(value.toString().contains(customThemeId))
        assertFalse(value.toString().contains("7d4e"))
    }

    @Test
    fun `a blank theme has no value`() {
        assertEquals(None, hostValue(TERMINAL_THEME, host.copy(terminalTheme = "")))
        assertEquals(None, hostValue(TERMINAL_THEME, host.copy(terminalTheme = "   ")))
    }

    @Test
    fun `an oversized theme name from a peer is capped`() {
        assertEquals(
            PresetTheme("T".repeat(32) + ellipsis),
            hostValue(TERMINAL_THEME, host.copy(terminalTheme = "T".repeat(500))),
        )
        assertEquals(
            PresetTheme("T".repeat(32)),
            hostValue(TERMINAL_THEME, host.copy(terminalTheme = "T".repeat(32))),
        )
    }

    @Test
    fun `the credential reference of a host is a short id and never the full one`() {
        assertEquals(Text("0a1b2c3d-4e5$ellipsis"), hostValue(CREDENTIAL_REFERENCE))
        assertEquals(Text("cred-1"), hostValue(CREDENTIAL_REFERENCE, host.copy(credentialId = "cred-1")))
        assertEquals(None, hostValue(CREDENTIAL_REFERENCE, host.copy(credentialId = "")))
        assertFalse(hostValue(CREDENTIAL_REFERENCE).toString().contains("ef0123456789"))
    }

    @Test
    fun `a host from an old peer decodes with the defaults of the newer fields`() {
        val legacyJson = """
            {"id":"h","payload":{"id":"h","label":"L","hostname":"x","username":"u",
            "authType":"PASSWORD","credentialId":"cred-1"},"clock":{"entries":{}},"updatedAt":1}
        """.trimIndent()

        val decoded = assertNotNull(decodeTypedEntity(SyncableEntityType.HOST, legacyJson))

        assertEquals(YesNo(false), decoded.value(FAVORITE))
        assertEquals(Seconds(30), decoded.value(KEEP_ALIVE))
        assertEquals(YesNo(true), decoded.value(AUTO_RECONNECT))
        assertEquals(PresetTheme("TECHTICAL_DARK"), decoded.value(TERMINAL_THEME))
        assertEquals(None, decoded.value(FIDO2_MODE))
        assertEquals(None, decoded.value(GROUP))
        assertEquals(Text("cred-1"), decoded.value(CREDENTIAL_REFERENCE))
    }

    // ── Tunnel values in detail ───────────────────────────────────────────

    @Test
    fun `every tunnel type is typed`() {
        for (tunnelType in TunnelType.entries) {
            assertEquals(TunnelKind(tunnelType), tunnelValue(TUNNEL_TYPE, tunnel.copy(type = tunnelType)))
            assertEquals(
                tunnelType.name,
                legacy(SyncableEntityType.TUNNEL, tunnel.copy(type = tunnelType)).fields.toMap()["type"],
            )
        }
    }

    @Test
    fun `the host reference of a tunnel is a short id`() {
        assertEquals(Text("host-0123456$ellipsis"), tunnelValue(HOST_REFERENCE))
        assertEquals(Text("h-1"), tunnelValue(HOST_REFERENCE, tunnel.copy(hostId = "h-1")))
        assertEquals(None, tunnelValue(HOST_REFERENCE, tunnel.copy(hostId = "")))
    }

    @Test
    fun `an oversized browser path from a peer is capped`() {
        val shown = tunnelValue(BROWSER_PATH, tunnel.copy(browserPath = "/" + "a".repeat(2_000)))

        val text = assertIs<Text>(shown).text
        assertEquals(TunnelBrowserPath.MAX_LENGTH + 1, text.length)
        assertTrue(text.endsWith(ellipsis))
    }

    @Test
    fun `a browser path of exactly the maximum length is shown in full`() {
        val path = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH - 1)

        assertEquals(Text(path), tunnelValue(BROWSER_PATH, tunnel.copy(browserPath = path)))
    }

    @Test
    fun `a tunnel from an old peer without browser path is shown as the root`() {
        val legacyJson = """
            {"id":"t","payload":{"id":"t","label":"L","hostId":"h","type":"LOCAL_FORWARD",
            "localPort":1,"remoteHost":"r","remotePort":2},"clock":{"entries":{}},"updatedAt":1}
        """.trimIndent()

        val decoded = assertNotNull(decodeTypedEntity(SyncableEntityType.TUNNEL, legacyJson))

        assertEquals(Text("/"), decoded.value(BROWSER_PATH))
        assertEquals(Text("1 $arrow r:2"), decoded.value(FORWARDING))
    }

    // ── Comparison ────────────────────────────────────────────────────────

    /**
     * Compares [local] and [remote] with both APIs and checks that they report the same rows:
     * the typed fields are translated to the legacy row names (by position, from each side's
     * own rows) and must give exactly the legacy result. Returns the typed difference.
     */
    private fun typedDiff(type: SyncableEntityType, local: Any?, remote: Any?): Set<ConflictField> {
        val localTyped = typed(type, local)
        val remoteTyped = typed(type, remote)
        val localLegacy = legacy(type, local)
        val remoteLegacy = legacy(type, remote)

        val names = legacyNames(localTyped, localLegacy) + legacyNames(remoteTyped, remoteLegacy)
        val typedDifference = diffTypedFields(localTyped, remoteTyped)

        assertEquals(
            diffFields(localLegacy, remoteLegacy),
            typedDifference.map { names.getValue(it) }.toSet(),
            "typed and legacy differences of $type",
        )
        return typedDifference
    }

    private fun legacyNames(typedEntity: TypedEntity, legacyEntity: DecodedEntity): Map<ConflictField, String> {
        assertEquals(typedEntity.fields.size, legacyEntity.fields.size, "typed and legacy rows have the same count")
        return typedEntity.fieldList().zip(legacyEntity.fields.map { it.first }).toMap()
    }

    private class Change<T>(val name: String, val field: ConflictField, val modify: (T) -> T)

    private val hostChanges: List<Change<Host>> = listOf(
        Change("label", LABEL) { it.copy(label = it.label + "-changed") },
        Change("hostname", ADDRESS) { it.copy(hostname = "other." + it.hostname) },
        Change("port", ADDRESS) { it.copy(port = it.port + 1) },
        Change("username", ADDRESS) { it.copy(username = it.username + "2") },
        Change("authType", AUTH_TYPE) { it.copy(authType = AuthType.PASSWORD) },
        Change("credentialId", CREDENTIAL_REFERENCE) { it.copy(credentialId = "changed-" + it.credentialId) },
        Change("group", GROUP) { it.copy(group = null) },
        Change("keepAliveSeconds", KEEP_ALIVE) { it.copy(keepAliveSeconds = it.keepAliveSeconds + 1) },
        Change("autoReconnect", AUTO_RECONNECT) { it.copy(autoReconnect = !it.autoReconnect) },
        Change("terminalTheme", TERMINAL_THEME) { it.copy(terminalTheme = customThemeId) },
        Change("fido2Mode", FIDO2_MODE) { it.copy(fido2Mode = Fido2Mode.PASSKEY) },
        Change("isFavorite", FAVORITE) { it.copy(isFavorite = !it.isFavorite) },
    )

    private val tunnelChanges: List<Change<TunnelConfig>> = listOf(
        Change("label", LABEL) { it.copy(label = it.label + "-changed") },
        Change("hostId", HOST_REFERENCE) { it.copy(hostId = "changed-" + it.hostId) },
        Change("type", TUNNEL_TYPE) { it.copy(type = TunnelType.DYNAMIC_SOCKS5) },
        Change("localPort", FORWARDING) { it.copy(localPort = it.localPort + 1) },
        Change("remoteHost", FORWARDING) { it.copy(remoteHost = it.remoteHost + "2") },
        Change("remotePort", FORWARDING) { it.copy(remotePort = it.remotePort + 1) },
        Change("autoStart", AUTO_START) { it.copy(autoStart = !it.autoStart) },
        Change("openBrowserOnConnect", OPEN_BROWSER_ON_CONNECT) { it.copy(openBrowserOnConnect = !it.openBrowserOnConnect) },
        Change("keepAliveAfterBrowserClose", KEEP_ALIVE_AFTER_BROWSER_CLOSE) {
            it.copy(keepAliveAfterBrowserClose = !it.keepAliveAfterBrowserClose)
        },
        Change("isFavorite", FAVORITE) { it.copy(isFavorite = !it.isFavorite) },
        Change("browserPath", BROWSER_PATH) { it.copy(browserPath = "/changed") },
    )

    private val sshKeyChanges: List<Change<SshKey>> = listOf(
        Change("label", LABEL) { it.copy(label = it.label + "-changed") },
        Change("keyType", KEY_TYPE) { it.copy(keyType = SshKeyType.RSA_4096) },
        Change("publicKey", PUBLIC_KEY) { it.copy(publicKey = "ssh-rsa " + it.publicKey) },
    )

    private val snippetChanges: List<Change<Snippet>> = listOf(
        Change("label", LABEL) { it.copy(label = it.label + "-changed") },
        Change("category", CATEGORY) { it.copy(category = null) },
        Change("command", COMMAND) { it.copy(command = "echo " + it.command) },
    )

    private val themeChanges: List<Change<CustomTerminalTheme>> = listOf(
        Change("name", NAME) { it.copy(name = it.name + "-changed") },
        Change("background", BACKGROUND) { it.copy(background = 0xFF123456.toInt()) },
        Change("foreground", FOREGROUND) { it.copy(foreground = 0xFF654321.toInt()) },
    )

    private val credentialChanges: List<Change<CredentialEntry>> = listOf(
        Change("credentialId", CREDENTIAL_ID) { it.copy(credentialId = "ffffffff-0000-0000-0000-000000000000") },
        Change("type", CREDENTIAL_TYPE) { it.copy(type = CredentialType.CERTIFICATE) },
    )

    /** [CredentialEntry] is not a data class: a copy that keeps the secret-related fields as they are. */
    private fun CredentialEntry.copy(
        credentialId: String = this.credentialId,
        type: CredentialType = this.type,
    ) = CredentialEntry(credentialId, type, iv, encryptedPayload, contentHashBase64)

    private fun <T : Any> assertEachChange(entityType: SyncableEntityType, base: T, changes: List<Change<T>>) {
        for (change in changes) {
            val changed = change.modify(base)

            assertTrue(changed != base, "The sample change for $entityType.${change.name} changes nothing.")
            assertEquals(
                setOf(change.field),
                typedDiff(entityType, base, changed),
                "Changing $entityType.${change.name} must flag only ${change.field}.",
            )
        }
    }

    @Test
    fun `each host property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.HOST, host, hostChanges)
    }

    @Test
    fun `each tunnel property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.TUNNEL, tunnel, tunnelChanges)
    }

    @Test
    fun `each SSH key property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.SSH_KEY, sshKey, sshKeyChanges)
    }

    @Test
    fun `each snippet property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.SNIPPET, snippet, snippetChanges)
    }

    @Test
    fun `each custom theme property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.CUSTOM_TERMINAL_THEME, theme, themeChanges)
    }

    @Test
    fun `each shown credential property alone flags its field and both APIs agree`() {
        assertEachChange(SyncableEntityType.CREDENTIAL, credential, credentialChanges)
    }

    @Test
    fun `identical entities have no difference in either API`() {
        for ((type, payload) in samples) {
            assertTrue(typedDiff(type, payload, payload).isEmpty(), "identical $type")
        }
    }

    @Test
    fun `several differences are all reported`() {
        val changed = host.copy(label = "other", keepAliveSeconds = 60, isFavorite = false)

        assertEquals(
            setOf(LABEL, KEEP_ALIVE, FAVORITE),
            typedDiff(SyncableEntityType.HOST, host, changed),
        )
    }

    @Test
    fun `a deleted entity differs from a live one on every row`() {
        for ((type, payload) in samples) {
            val liveFields = typed(type, payload).fieldList().toSet()

            assertEquals(liveFields + DELETED, typedDiff(type, payload, null), "live against deleted $type")
            assertEquals(liveFields + DELETED, typedDiff(type, null, payload), "deleted against live $type")
            assertTrue(typedDiff(type, null, null).isEmpty(), "deleted against deleted $type")
        }
    }

    @Test
    fun `a row present on one side only counts as a difference`() {
        val local = TypedEntity(
            ConflictTitle.Label("a"),
            listOf(ConflictFieldRow(LABEL, Text("a")), ConflictFieldRow(FAVORITE, YesNo(false))),
        )
        val remote = TypedEntity(ConflictTitle.Label("a"), listOf(ConflictFieldRow(LABEL, Text("a"))))

        assertEquals(setOf(FAVORITE), diffTypedFields(local, remote))
        assertEquals(setOf(FAVORITE), diffTypedFields(remote, local))
    }

    @Test
    fun `values of different kinds are different`() {
        val local = TypedEntity(ConflictTitle.Deleted, listOf(ConflictFieldRow(GROUP, None)))
        val remote = TypedEntity(ConflictTitle.Deleted, listOf(ConflictFieldRow(GROUP, Text("-"))))

        assertEquals(setOf(GROUP), diffTypedFields(local, remote))
    }

    // ── Coverage ──────────────────────────────────────────────────────────

    @Test
    fun `every conflict field is reachable from at least one entity type`() {
        val reached = mutableSetOf<ConflictField>()
        for ((type, payload) in samples) {
            reached += typed(type, payload).fieldList()
        }
        reached += typed(SyncableEntityType.HOST, null).fieldList()

        assertEquals(ConflictField.entries.toSet(), reached)
    }

    @Test
    fun `every entity type of the sync has a sample here`() {
        assertEquals(SyncableEntityType.entries.toSet(), samples.keys)
        assertEquals(SyncableEntityType.entries.toSet(), correspondence.keys)
    }

    // ── Security ──────────────────────────────────────────────────────────

    @Test
    fun `the encrypted payload of a credential never reaches the typed model`() {
        val everything = typed(SyncableEntityType.CREDENTIAL, credential).toString()

        assertFalse(everything.contains(credential.encryptedPayload))
        assertFalse(everything.contains(credential.iv))
        assertFalse(everything.contains(credential.contentHashBase64))
        assertFalse(everything.contains(credential.credentialId))
    }

    @Test
    fun `the encrypted payload of a credential never reaches the legacy rendering either`() {
        val everything = legacy(SyncableEntityType.CREDENTIAL, credential).toString()

        assertFalse(everything.contains(credential.encryptedPayload))
        assertFalse(everything.contains(credential.iv))
        assertFalse(everything.contains(credential.contentHashBase64))
        assertFalse(everything.contains(credential.credentialId))
    }

    @Test
    fun `the encrypted marker carries no data`() {
        val rows = typed(SyncableEntityType.CREDENTIAL, credential).fields

        assertEquals(Encrypted, rows.single { it.field == ENCRYPTED_CONTENT }.value)
    }

    @Test
    fun `no id is carried whole by the typed model`() {
        val hostEverything = typed(SyncableEntityType.HOST, host).toString()
        val tunnelEverything = typed(SyncableEntityType.TUNNEL, tunnel).toString()

        assertFalse(hostEverything.contains(host.credentialId))
        assertFalse(tunnelEverything.contains(tunnel.hostId))
    }

    // ── Invalid input ─────────────────────────────────────────────────────

    @Test
    fun `malformed json decodes to null in both APIs`() {
        for (type in SyncableEntityType.entries) {
            assertNull(decodeTypedEntity(type, "not json"), "typed $type")
            assertNull(decodeEntity(type, "not json"), "legacy $type")
            assertNull(decodeTypedEntity(type, ""), "typed empty $type")
            assertNull(decodeEntity(type, ""), "legacy empty $type")
        }
    }

    @Test
    fun `json of another entity type decodes to null in both APIs`() {
        val hostJson = encodeEntry(SyncableEntityType.HOST, host)

        assertNull(decodeTypedEntity(SyncableEntityType.TUNNEL, hostJson))
        assertNull(decodeEntity(SyncableEntityType.TUNNEL, hostJson))
    }
}
