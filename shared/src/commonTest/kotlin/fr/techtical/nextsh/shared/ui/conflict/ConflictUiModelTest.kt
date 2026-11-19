// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.ui.conflict

import fr.techtical.nextsh.shared.core.sync.ConflictSerializer
import fr.techtical.nextsh.shared.core.sync.SyncEntry
import fr.techtical.nextsh.shared.core.sync.SyncableEntityType
import fr.techtical.nextsh.shared.core.sync.VectorClock
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Fido2Mode
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.tunnel.TunnelBrowserPath
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConflictUiModelTest {

    private val pathField = "chemin navigateur"

    private val favoriteField = "favori"
    private val autoStartField = "démarrage automatique"
    private val openBrowserField = "ouvrir le navigateur"
    private val keepOpenField = "actif après navigateur"
    private val keepAliveField = "keep-alive"
    private val reconnectField = "reconnexion automatique"
    private val themeField = "thème"
    private val fido2Field = "mode FIDO2"
    private val credentialField = "clé ou mot de passe"

    private fun tunnelJson(browserPath: String, label: String = "Grafana"): String =
        tunnelJson(tunnelConfig(browserPath = browserPath, label = label))

    private fun tunnelConfig(
        browserPath: String = "/",
        label: String = "Grafana",
        autoStart: Boolean = false,
        openBrowserOnConnect: Boolean = false,
        keepAliveAfterBrowserClose: Boolean = true,
        isFavorite: Boolean = false,
    ) = TunnelConfig(
        id = "tunnel-1",
        label = label,
        hostId = "host-0123456789abcdef",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 3000,
        remoteHost = "localhost",
        remotePort = 3000,
        autoStart = autoStart,
        openBrowserOnConnect = openBrowserOnConnect,
        keepAliveAfterBrowserClose = keepAliveAfterBrowserClose,
        isFavorite = isFavorite,
        browserPath = browserPath,
    )

    private fun tunnelJson(tunnel: TunnelConfig): String {
        val entry = SyncEntry(
            id = tunnel.id,
            payload = tunnel,
            clock = VectorClock.EMPTY.tick("device-A", 1L),
            updatedAt = 1_000L,
        )
        return ConflictSerializer.encode(SyncableEntityType.TUNNEL, entry)
    }

    private fun decodeTunnel(json: String): DecodedEntity =
        assertNotNull(decodeEntity(SyncableEntityType.TUNNEL, json))

    private fun decodeTunnel(tunnel: TunnelConfig): DecodedEntity = decodeTunnel(tunnelJson(tunnel))

    private fun host(
        label: String = "prod",
        isFavorite: Boolean = false,
        keepAliveSeconds: Int = 30,
        autoReconnect: Boolean = true,
        terminalTheme: String = "TECHTICAL_DARK",
        fido2Mode: Fido2Mode? = null,
        credentialId: String = "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
        authType: AuthType = AuthType.PASSWORD,
    ) = Host(
        id = "host-1",
        label = label,
        hostname = "srv.example.org",
        port = 22,
        username = "root",
        authType = authType,
        credentialId = credentialId,
        group = null,
        keepAliveSeconds = keepAliveSeconds,
        autoReconnect = autoReconnect,
        terminalTheme = terminalTheme,
        fido2Mode = fido2Mode,
        isFavorite = isFavorite,
    )

    private fun decodeHost(host: Host): DecodedEntity {
        val entry = SyncEntry(
            id = host.id,
            payload = host,
            clock = VectorClock.EMPTY.tick("device-A", 1L),
            updatedAt = 1_000L,
        )
        val json = ConflictSerializer.encode(SyncableEntityType.HOST, entry)
        return assertNotNull(decodeEntity(SyncableEntityType.HOST, json))
    }

    private fun DecodedEntity.value(name: String): String? = fields.toMap()[name]

    @Test
    fun `tunnel conflict card shows the browser path`() {
        val decoded = decodeTunnel(tunnelJson("/d/abc?orgId=1"))

        assertEquals("/d/abc?orgId=1", decoded.fields.toMap()[pathField])
        assertEquals("Grafana", decoded.primaryLabel)
    }

    @Test
    fun `tunnel conflict card keeps its previous fields in order`() {
        val names = decodeTunnel(tunnelJson("/")).fields.map { it.first }

        assertEquals(
            listOf(
                "label", "type", "forwarding", "hostId", pathField,
                favoriteField, autoStartField, openBrowserField, keepOpenField,
            ),
            names,
        )
    }

    @Test
    fun `tunnel conflict card shows the favorite and the other synced flags`() {
        val decoded = decodeTunnel(
            tunnelConfig(
                isFavorite = true,
                autoStart = true,
                openBrowserOnConnect = true,
                keepAliveAfterBrowserClose = false,
            ),
        )

        assertEquals("oui", decoded.value(favoriteField))
        assertEquals("oui", decoded.value(autoStartField))
        assertEquals("oui", decoded.value(openBrowserField))
        assertEquals("non", decoded.value(keepOpenField))
    }

    @Test
    fun `tunnel flags default to non non non oui when nothing is set`() {
        val decoded = decodeTunnel(tunnelConfig())

        assertEquals("non", decoded.value(favoriteField))
        assertEquals("non", decoded.value(autoStartField))
        assertEquals("non", decoded.value(openBrowserField))
        assertEquals("oui", decoded.value(keepOpenField))
    }

    @Test
    fun `the tunnel host id row is a 12 character prefix`() {
        assertEquals("host-0123456…", decodeTunnel(tunnelConfig()).value("hostId"))
    }

    @Test
    fun `a short tunnel host id is shown whole and a blank one as a dash`() {
        fun hostIdOf(hostId: String) = decodeTunnel(tunnelConfig().copy(hostId = hostId)).value("hostId")

        assertEquals("h-1", hostIdOf("h-1"))
        assertEquals("-", hostIdOf(""))
    }

    @Test
    fun `two tunnels that differ only by favorite are reported as a difference`() {
        val local = decodeTunnel(tunnelConfig(isFavorite = false))
        val remote = decodeTunnel(tunnelConfig(isFavorite = true))

        assertEquals(setOf(favoriteField), diffFields(local, remote))
    }

    @Test
    fun `each hidden tunnel flag alone is reported as a difference`() {
        val base = decodeTunnel(tunnelConfig())

        assertEquals(setOf(autoStartField), diffFields(base, decodeTunnel(tunnelConfig(autoStart = true))))
        assertEquals(
            setOf(openBrowserField),
            diffFields(base, decodeTunnel(tunnelConfig(openBrowserOnConnect = true))),
        )
        assertEquals(
            setOf(keepOpenField),
            diffFields(base, decodeTunnel(tunnelConfig(keepAliveAfterBrowserClose = false))),
        )
    }

    // ── Host ──────────────────────────────────────────────────────────────

    @Test
    fun `host conflict card keeps its previous fields in order then lists the synced ones`() {
        val names = decodeHost(host()).fields.map { it.first }

        assertEquals(
            listOf(
                "label", "hôte", "authType", "groupe",
                favoriteField, keepAliveField, reconnectField, themeField, fido2Field, credentialField,
            ),
            names,
        )
    }

    @Test
    fun `host conflict card shows every synced field with its value`() {
        val decoded = decodeHost(
            host(
                isFavorite = true,
                keepAliveSeconds = 45,
                autoReconnect = false,
                terminalTheme = "DRACULA",
                fido2Mode = Fido2Mode.PASSKEY,
            ),
        )

        assertEquals("prod", decoded.primaryLabel)
        assertEquals("root@srv.example.org:22", decoded.value("hôte"))
        assertEquals("oui", decoded.value(favoriteField))
        assertEquals("45 s", decoded.value(keepAliveField))
        assertEquals("non", decoded.value(reconnectField))
        assertEquals("DRACULA", decoded.value(themeField))
        assertEquals("passkey", decoded.value(fido2Field))
    }

    @Test
    fun `host defaults are shown as non oui and dashes`() {
        val decoded = decodeHost(host())

        assertEquals("non", decoded.value(favoriteField))
        assertEquals("30 s", decoded.value(keepAliveField))
        assertEquals("oui", decoded.value(reconnectField))
        assertEquals("TECHTICAL_DARK", decoded.value(themeField))
        assertEquals("-", decoded.value(fido2Field))
    }

    @Test
    fun `the FIDO2 mode is shown with a readable name`() {
        assertEquals("clé matérielle", decodeHost(host(fido2Mode = Fido2Mode.HARDWARE_KEY)).value(fido2Field))
        assertEquals("passkey", decodeHost(host(fido2Mode = Fido2Mode.PASSKEY)).value(fido2Field))
        assertEquals("-", decodeHost(host(fido2Mode = null)).value(fido2Field))
    }

    @Test
    fun `the keepalive is always the raw value in seconds`() {
        // Android turns the keepalive off at 0 while Desktop clamps it to 5 s: no value gets a meaning.
        assertEquals("0 s", decodeHost(host(keepAliveSeconds = 0)).value(keepAliveField))
        assertEquals("300 s", decodeHost(host(keepAliveSeconds = 300)).value(keepAliveField))
    }

    @Test
    fun `a custom terminal theme is shown as personalised with a short id`() {
        val customId = "3f2a9c1b-7d4e-4a55-9b0c-1234567890ab"

        val shown = decodeHost(host(terminalTheme = customId)).value(themeField)

        assertEquals("personnalisé (3f2a9c1b…)", shown)
    }

    @Test
    fun `a built-in theme name is shown as is`() {
        assertEquals("DRACULA", decodeHost(host(terminalTheme = "DRACULA")).value(themeField))
    }

    @Test
    fun `an oversized theme name from a peer is capped for display`() {
        val shown = assertNotNull(decodeHost(host(terminalTheme = "T".repeat(500))).value(themeField))

        assertEquals(33, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `a blank theme is shown as a dash`() {
        assertEquals("-", decodeHost(host(terminalTheme = "")).value(themeField))
    }

    @Test
    fun `the credential reference is a 12 character prefix and never the full id`() {
        val credentialId = "0a1b2c3d-4e5f-6789-abcd-ef0123456789"

        val shown = assertNotNull(decodeHost(host(credentialId = credentialId)).value(credentialField))

        assertEquals("0a1b2c3d-4e5…", shown)
        assertTrue(!shown.contains("ef0123456789"))
    }

    @Test
    fun `a blank credential reference is shown as a dash`() {
        assertEquals("-", decodeHost(host(credentialId = "")).value(credentialField))
    }

    @Test
    fun `two hosts that differ only by favorite are reported as a difference`() {
        val local = decodeHost(host(isFavorite = false))
        val remote = decodeHost(host(isFavorite = true))

        assertEquals(setOf(favoriteField), diffFields(local, remote))
    }

    @Test
    fun `each hidden host field alone is reported as a difference`() {
        val base = decodeHost(host())

        assertEquals(setOf(keepAliveField), diffFields(base, decodeHost(host(keepAliveSeconds = 60))))
        assertEquals(setOf(reconnectField), diffFields(base, decodeHost(host(autoReconnect = false))))
        assertEquals(setOf(themeField), diffFields(base, decodeHost(host(terminalTheme = "DRACULA"))))
        assertEquals(setOf(fido2Field), diffFields(base, decodeHost(host(fido2Mode = Fido2Mode.HARDWARE_KEY))))
        assertEquals(
            setOf(credentialField),
            diffFields(base, decodeHost(host(credentialId = "ffffffff-0000-0000-0000-000000000000"))),
        )
    }

    @Test
    fun `identical hosts have no difference`() {
        assertTrue(diffFields(decodeHost(host()), decodeHost(host())).isEmpty())
    }

    @Test
    fun `a deleted host has a single supprime row and no favorite row`() {
        val deleted = SyncEntry<Host>(
            id = "host-1",
            payload = null,
            clock = VectorClock.EMPTY,
            deleted = true,
            deletedAt = 5L,
            updatedAt = 5L,
        )
        val json = ConflictSerializer.encode(SyncableEntityType.HOST, deleted)

        val decoded = assertNotNull(decodeEntity(SyncableEntityType.HOST, json))

        assertEquals(listOf("supprimé" to "oui"), decoded.fields)
        assertNull(decoded.value(favoriteField))
    }

    @Test
    fun `a host from an old peer without the newer fields decodes with their defaults`() {
        val legacyJson = """
            {"id":"h","payload":{"id":"h","label":"L","hostname":"x","username":"u",
            "authType":"PASSWORD","credentialId":"cred-1"},"clock":{"entries":{}},"updatedAt":1}
        """.trimIndent()

        val decoded = assertNotNull(decodeEntity(SyncableEntityType.HOST, legacyJson))

        assertEquals("non", decoded.value(favoriteField))
        assertEquals("30 s", decoded.value(keepAliveField))
        assertEquals("oui", decoded.value(reconnectField))
        assertEquals("-", decoded.value(fido2Field))
        // A short id is shown whole: no ellipsis when nothing was cut.
        assertEquals("cred-1", decoded.value(credentialField))
    }

    @Test
    fun `two tunnels that differ only by browser path are reported as a difference`() {
        val local = decodeTunnel(tunnelJson("/"))
        val remote = decodeTunnel(tunnelJson("/admin"))

        assertEquals(setOf(pathField), diffFields(local, remote))
    }

    @Test
    fun `identical tunnels have no difference`() {
        val local = decodeTunnel(tunnelJson("/admin"))
        val remote = decodeTunnel(tunnelJson("/admin"))

        assertTrue(diffFields(local, remote).isEmpty())
    }

    @Test
    fun `a path difference is reported next to another field difference`() {
        val local = decodeTunnel(tunnelJson("/a", label = "Grafana"))
        val remote = decodeTunnel(tunnelJson("/b", label = "Grafana prod"))

        assertEquals(setOf("label", pathField), diffFields(local, remote))
    }

    @Test
    fun `tunnel from an old peer without browser path is shown as the root`() {
        val legacyJson = """
            {"id":"t","payload":{"id":"t","label":"L","hostId":"h","type":"LOCAL_FORWARD",
            "localPort":1,"remoteHost":"r","remotePort":2},"clock":{"entries":{}},"updatedAt":1}
        """.trimIndent()

        val legacy = decodeTunnel(legacyJson)

        assertEquals("/", legacy.fields.toMap()[pathField])
        // A legacy root and an explicit root are the same value: no false conflict on the path.
        val explicitRoot = decodeTunnel(tunnelJson("/", label = "L"))
        assertTrue(pathField !in diffFields(legacy, explicitRoot))
    }

    @Test
    fun `an oversized path from a peer is capped for display`() {
        val shown = decodeTunnel(tunnelJson("/" + "a".repeat(2_000))).fields.toMap().getValue(pathField)

        assertEquals(TunnelBrowserPath.MAX_LENGTH + 1, shown.length)
        assertTrue(shown.endsWith("\u2026"))
    }

    @Test
    fun `a path of exactly the maximum length is shown in full`() {
        val path = "/" + "a".repeat(TunnelBrowserPath.MAX_LENGTH - 1)

        assertEquals(path, decodeTunnel(tunnelJson(path)).fields.toMap()[pathField])
    }

    @Test
    fun `a deleted tunnel has no path field`() {
        val deleted = SyncEntry<TunnelConfig>(
            id = "t",
            payload = null,
            clock = VectorClock.EMPTY,
            deleted = true,
            deletedAt = 5L,
            updatedAt = 5L,
        )
        val json = ConflictSerializer.encode(SyncableEntityType.TUNNEL, deleted)

        assertNull(decodeTunnel(json).fields.toMap()[pathField])
    }

    // ── Guard: no synced property may be hidden from the card ─────────────

    /**
     * Properties the card deliberately does not show. `id` is the key of the entry, identical
     * on both sides by construction, so it can never be what makes two versions differ.
     */
    private val ignoredHostProperties = setOf("id")
    private val ignoredTunnelProperties = setOf("id")

    /**
     * Every other property, each with a change of its value. The guard checks that the
     * change shows up on the card, so a property cannot be listed here without being displayed.
     */
    private val displayedHostProperties: Map<String, (Host) -> Host> = mapOf(
        "label" to { h: Host -> h.copy(label = h.label + "-changed") },
        "hostname" to { h: Host -> h.copy(hostname = "other." + h.hostname) },
        "port" to { h: Host -> h.copy(port = h.port + 1) },
        "username" to { h: Host -> h.copy(username = h.username + "2") },
        "authType" to { h: Host ->
            h.copy(authType = if (h.authType == AuthType.PASSWORD) AuthType.SSH_KEY else AuthType.PASSWORD)
        },
        "credentialId" to { h: Host -> h.copy(credentialId = "changed-" + h.credentialId) },
        "group" to { h: Host -> h.copy(group = (h.group ?: "") + "g") },
        "keepAliveSeconds" to { h: Host -> h.copy(keepAliveSeconds = h.keepAliveSeconds + 1) },
        "autoReconnect" to { h: Host -> h.copy(autoReconnect = !h.autoReconnect) },
        "terminalTheme" to { h: Host -> h.copy(terminalTheme = "CHANGED_THEME") },
        "fido2Mode" to { h: Host ->
            h.copy(fido2Mode = if (h.fido2Mode == Fido2Mode.HARDWARE_KEY) Fido2Mode.PASSKEY else Fido2Mode.HARDWARE_KEY)
        },
        "isFavorite" to { h: Host -> h.copy(isFavorite = !h.isFavorite) },
    )

    private val displayedTunnelProperties: Map<String, (TunnelConfig) -> TunnelConfig> = mapOf(
        "label" to { t: TunnelConfig -> t.copy(label = t.label + "-changed") },
        "hostId" to { t: TunnelConfig -> t.copy(hostId = "changed-" + t.hostId) },
        "type" to { t: TunnelConfig ->
            t.copy(type = if (t.type == TunnelType.LOCAL_FORWARD) TunnelType.DYNAMIC_SOCKS5 else TunnelType.LOCAL_FORWARD)
        },
        "localPort" to { t: TunnelConfig -> t.copy(localPort = t.localPort + 1) },
        "remoteHost" to { t: TunnelConfig -> t.copy(remoteHost = t.remoteHost + "2") },
        "remotePort" to { t: TunnelConfig -> t.copy(remotePort = t.remotePort + 1) },
        "autoStart" to { t: TunnelConfig -> t.copy(autoStart = !t.autoStart) },
        "openBrowserOnConnect" to { t: TunnelConfig -> t.copy(openBrowserOnConnect = !t.openBrowserOnConnect) },
        "keepAliveAfterBrowserClose" to { t: TunnelConfig ->
            t.copy(keepAliveAfterBrowserClose = !t.keepAliveAfterBrowserClose)
        },
        "isFavorite" to { t: TunnelConfig -> t.copy(isFavorite = !t.isFavorite) },
        "browserPath" to { t: TunnelConfig -> t.copy(browserPath = "/changed") },
    )

    private fun assertEveryPropertyIsClassified(
        type: String,
        properties: Set<String>,
        displayed: Set<String>,
        ignored: Set<String>,
    ) {
        val unclassified = properties - displayed - ignored
        assertTrue(
            unclassified.isEmpty(),
            "$type gained the synced properties $unclassified. Add them to decodeEntity in " +
                "ConflictUiModel.kt (a property the card hides makes two different versions look " +
                "identical), then list them in this test: displayed with a sample change, or " +
                "ignored with the reason.",
        )
        val stale = (displayed + ignored) - properties
        assertTrue(stale.isEmpty(), "This test lists $stale, which $type no longer has: remove it from the test.")
        assertTrue(displayed.intersect(ignored).isEmpty(), "A property cannot be both displayed and ignored.")
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every Host property is displayed on the card or explicitly ignored`() {
        assertEveryPropertyIsClassified(
            type = "Host",
            properties = Host.serializer().descriptor.elementNames.toSet(),
            displayed = displayedHostProperties.keys,
            ignored = ignoredHostProperties,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `every TunnelConfig property is displayed on the card or explicitly ignored`() {
        assertEveryPropertyIsClassified(
            type = "TunnelConfig",
            properties = TunnelConfig.serializer().descriptor.elementNames.toSet(),
            displayed = displayedTunnelProperties.keys,
            ignored = ignoredTunnelProperties,
        )
    }

    @Test
    fun `changing any displayed Host property changes the card`() {
        val base = host()
        for ((property, change) in displayedHostProperties) {
            val changed = change(base)

            assertTrue(changed != base, "The sample change for Host.$property does not change the property.")
            assertTrue(
                diffFields(decodeHost(base), decodeHost(changed)).isNotEmpty(),
                "Host.$property is listed as displayed but changing it leaves the two cards identical.",
            )
        }
    }

    @Test
    fun `changing any displayed TunnelConfig property changes the card`() {
        val base = tunnelConfig()
        for ((property, change) in displayedTunnelProperties) {
            val changed = change(base)

            assertTrue(changed != base, "The sample change for TunnelConfig.$property does not change the property.")
            assertTrue(
                diffFields(decodeTunnel(base), decodeTunnel(changed)).isNotEmpty(),
                "TunnelConfig.$property is listed as displayed but changing it leaves the two cards identical.",
            )
        }
    }
}
