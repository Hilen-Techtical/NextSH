// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.core.sync

import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.CustomTerminalTheme
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.Snippet
import fr.techtical.nextsh.shared.domain.model.SshKey
import fr.techtical.nextsh.shared.domain.model.SshKeyType
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TunnelConfig as an old peer knows it: no browserPath. */
@Serializable
private data class LegacyTunnelConfig(
    val id: String,
    val label: String,
    val hostId: String,
    val type: TunnelType,
    val localPort: Int,
    val remoteHost: String,
    val remotePort: Int,
    val autoStart: Boolean = false,
    val openBrowserOnConnect: Boolean = false,
    val keepAliveAfterBrowserClose: Boolean = true,
    val isFavorite: Boolean = false,
)

class ConflictSerializerTest {

    private fun clock(vararg pairs: Pair<String, Long>): VectorClock =
        pairs.fold(VectorClock.EMPTY) { acc, (id, ts) -> acc.tick(id, ts) }

    @Test
    fun `encode and decode Host SyncEntry round trip`() {
        val host = Host(
            id = "host-1",
            label = "Prod",
            hostname = "example.com",
            port = 22,
            username = "root",
            authType = AuthType.PASSWORD,
            credentialId = "cred-1",
        )
        val entry = SyncEntry(
            id = host.id,
            payload = host,
            clock = clock("device-A" to 1L),
            updatedAt = 1_000L,
        )

        val encoded = ConflictSerializer.encode(SyncableEntityType.HOST, entry)
        val decoded = ConflictSerializer.decodeHost(encoded)

        assertEquals(entry, decoded)
    }

    @Test
    fun `encode and decode TunnelConfig SyncEntry round trip`() {
        val tunnel = TunnelConfig(
            id = "tunnel-1",
            label = "Dev DB",
            hostId = "host-1",
            type = TunnelType.LOCAL_FORWARD,
            localPort = 5432,
            remoteHost = "db.internal",
            remotePort = 5432,
        )
        val entry = SyncEntry(
            id = tunnel.id,
            payload = tunnel,
            clock = clock("device-B" to 3L),
            updatedAt = 2_000L,
        )

        val encoded = ConflictSerializer.encode(SyncableEntityType.TUNNEL, entry)
        val decoded = ConflictSerializer.decodeTunnel(encoded)

        assertEquals(entry, decoded)
    }

    // --- TunnelConfig.browserPath wire compatibility ---

    /** JSON of a tunnel SyncEntry as emitted by a peer that predates browserPath. */
    private val legacyTunnelEntryJson = """
        {
          "id": "tunnel-1",
          "payload": {
            "id": "tunnel-1",
            "label": "Dev DB",
            "hostId": "host-1",
            "type": "LOCAL_FORWARD",
            "localPort": 5432,
            "remoteHost": "db.internal",
            "remotePort": 5432,
            "autoStart": true,
            "openBrowserOnConnect": true,
            "keepAliveAfterBrowserClose": false,
            "isFavorite": true
          },
          "clock": { "entries": { "device-B": 3 } },
          "deleted": false,
          "deletedAt": null,
          "updatedAt": 2000
        }
    """.trimIndent()

    private fun tunnelWithPath(path: String) = TunnelConfig(
        id = "tunnel-1",
        label = "Dev DB",
        hostId = "host-1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 5432,
        remoteHost = "db.internal",
        remotePort = 5432,
        browserPath = path,
    )

    private fun tunnelEntry(tunnel: TunnelConfig) = SyncEntry(
        id = tunnel.id,
        payload = tunnel,
        clock = clock("device-B" to 3L),
        updatedAt = 2_000L,
    )

    @Test
    fun `TunnelConfig with a non default browserPath round trips`() {
        val entry = tunnelEntry(tunnelWithPath("/admin/?tab=2#top"))

        val encoded = ConflictSerializer.encode(SyncableEntityType.TUNNEL, entry)
        val decoded = ConflictSerializer.decodeTunnel(encoded)

        assertEquals(entry, decoded)
        assertEquals("/admin/?tab=2#top", decoded.payload!!.browserPath)
        assertTrue(encoded.contains("\"browserPath\":\"/admin/?tab=2#top\""), encoded)
    }

    @Test
    fun `legacy tunnel JSON without browserPath decodes to the root and keeps its other fields`() {
        val decoded = ConflictSerializer.decodeTunnel(legacyTunnelEntryJson)

        val tunnel = decoded.payload!!
        assertEquals("/", tunnel.browserPath)
        val expected = tunnelWithPath("/").copy(
            autoStart = true,
            openBrowserOnConnect = true,
            keepAliveAfterBrowserClose = false,
            isFavorite = true,
        )
        assertEquals(expected, tunnel)
    }

    @Test
    fun `minimal tunnel JSON with only the required fields decodes to the root`() {
        val minimal = """
            {"id":"t","payload":{"id":"t","label":"L","hostId":"h","type":"LOCAL_FORWARD",
            "localPort":1,"remoteHost":"r","remotePort":2},"clock":{"entries":{}},"updatedAt":1}
        """.trimIndent()

        val tunnel = ConflictSerializer.decodeTunnel(minimal).payload!!

        assertEquals("/", tunnel.browserPath)
    }

    @Test
    fun `ConflictSerializer writes a default browserPath because encodeDefaults is on`() {
        val encoded = ConflictSerializer.encode(SyncableEntityType.TUNNEL, tunnelEntry(tunnelWithPath("/")))

        // New peers always emit the field, so the stored value is explicit on the wire.
        assertTrue(encoded.contains("\"browserPath\":\"/\""), encoded)
    }

    @Test
    fun `a JSON config without encodeDefaults omits the default path and still decodes to the root`() {
        val lenient = Json { ignoreUnknownKeys = true }
        val serializer = SyncEntry.serializer(TunnelConfig.serializer())
        val entry = tunnelEntry(tunnelWithPath("/"))

        val compact = lenient.encodeToString(serializer, entry)
        val withPath = lenient.encodeToString(serializer, tunnelEntry(tunnelWithPath("/admin")))

        // Documents why decoding must never rely on the field being present.
        assertFalse(compact.contains("browserPath"), compact)
        assertEquals("/", lenient.decodeFromString(serializer, compact).payload!!.browserPath)
        // A non default value is always written, whatever the encoder configuration.
        assertTrue(withPath.contains("\"browserPath\":\"/admin\""), withPath)
    }

    @Test
    fun `an old peer that does not know browserPath ignores it instead of failing`() {
        val encoded = ConflictSerializer.encode(SyncableEntityType.TUNNEL, tunnelEntry(tunnelWithPath("/admin")))
        // Same settings as the old peers' ConflictSerializer and SyncBundleCodec.
        val oldPeerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val decoded = oldPeerJson.decodeFromString(SyncEntry.serializer(LegacyTunnelConfig.serializer()), encoded)

        assertEquals("tunnel-1", decoded.payload!!.id)
        assertEquals("Dev DB", decoded.payload.label)
        assertEquals(5432, decoded.payload.localPort)
    }

    @Test
    fun `encode and decode SshKey SyncEntry round trip`() {
        val key = SshKey(
            id = "key-1",
            label = "Personal Ed25519",
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAA... comment",
        )
        val entry = SyncEntry(
            id = key.id,
            payload = key,
            clock = clock("device-C" to 2L),
            updatedAt = 3_000L,
        )

        val encoded = ConflictSerializer.encode(SyncableEntityType.SSH_KEY, entry)
        val decoded = ConflictSerializer.decodeSshKey(encoded)

        assertEquals(entry, decoded)
    }

    @Test
    fun `encode and decode Snippet SyncEntry round trip`() {
        val snippet = Snippet(
            id = "snippet-1",
            label = "List services",
            command = "systemctl list-units --type=service",
            category = "sysadmin",
            hostId = null,
            createdAt = 4_000L,
        )
        val entry = SyncEntry(
            id = snippet.id,
            payload = snippet,
            clock = clock("device-D" to 5L),
            updatedAt = 4_000L,
        )

        val encoded = ConflictSerializer.encode(SyncableEntityType.SNIPPET, entry)
        val decoded = ConflictSerializer.decodeSnippet(encoded)

        assertEquals(entry, decoded)
    }

    @Test
    fun `encode and decode CustomTerminalTheme SyncEntry round trip`() {
        val ansi16 = listOf(
            0xFF000000.toInt(), 0xFFAA0000.toInt(), 0xFF00AA00.toInt(), 0xFFAA5500.toInt(),
            0xFF0000AA.toInt(), 0xFFAA00AA.toInt(), 0xFF00AAAA.toInt(), 0xFFAAAAAA.toInt(),
            0xFF555555.toInt(), 0xFFFF5555.toInt(), 0xFF55FF55.toInt(), 0xFFFFFF55.toInt(),
            0xFF5555FF.toInt(), 0xFFFF55FF.toInt(), 0xFF55FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )
        val theme = CustomTerminalTheme(
            id = "theme-test-1",
            name = "Techtical Dark",
            background = 0xFF0F1117.toInt(),
            foreground = 0xFFD4D4D4.toInt(),
            cursor = 0xFF00BFFF.toInt(),
            selectionBg = 0x4D0066CC.toInt(),
            ansi = ansi16,
            createdAt = 5_000L,
        )
        val entry = SyncEntry(
            id = theme.id,
            payload = theme,
            clock = clock("device-E" to 7L),
            updatedAt = 5_000L,
        )

        val encoded = ConflictSerializer.encode(SyncableEntityType.CUSTOM_TERMINAL_THEME, entry)
        val decoded = ConflictSerializer.decodeCustomTerminalTheme(encoded)

        assertEquals(entry, decoded)
        // Assert all 16 ANSI entries survived wire round-trip individually
        assertEquals(16, decoded.payload!!.ansi.size)
        for (i in 0..15) {
            assertEquals(ansi16[i], decoded.payload.ansi[i], "ansi[$i] mismatch after round-trip")
        }
        assertEquals(theme.background, decoded.payload.background)
        assertEquals(theme.foreground, decoded.payload.foreground)
        assertEquals(theme.cursor, decoded.payload.cursor)
        assertEquals(theme.selectionBg, decoded.payload.selectionBg)
        assertEquals(theme.name, decoded.payload.name)
        assertEquals(theme.id, decoded.payload.id)
        assertEquals(theme.createdAt, decoded.payload.createdAt)
    }
}
