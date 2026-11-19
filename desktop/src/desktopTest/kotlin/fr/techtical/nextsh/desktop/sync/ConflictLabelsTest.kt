// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.conflict_title_deleted
import fr.techtical.nextsh.desktop.generated.resources.conflicts_subtitle_many
import fr.techtical.nextsh.desktop.generated.resources.conflicts_subtitle_one
import fr.techtical.nextsh.desktop.theme.TerminalThemeId
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
import fr.techtical.nextsh.shared.ui.conflict.ConflictField
import fr.techtical.nextsh.shared.ui.conflict.ConflictTitle
import fr.techtical.nextsh.shared.ui.conflict.ConflictValue
import fr.techtical.nextsh.shared.ui.conflict.TypedEntity
import fr.techtical.nextsh.shared.ui.conflict.decodeTypedEntity
import kotlinx.coroutines.test.runTest
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests of the texts of the Desktop conflict card (`ConflictLabels.kt`): every entry of the typed
 * model of `:shared` has its own string resource, the resources exist in French and in English with
 * the same positional tokens, and the values are read through the resources instead of the French
 * wording that `:shared` used to build.
 *
 * The assertions on the rendered text do not depend on the machine language (FR or EN): they compare
 * texts with each other or check what a text must not contain. The French and the English files are
 * read as XML, from the source tree, so that both languages are checked whatever the machine uses.
 *
 * The long dashes are built from their code points so that this file itself holds none.
 */
class ConflictLabelsTest {

    private companion object {
        const val EM_DASH_CODE_POINT = 0x2014
        const val EN_DASH_CODE_POINT = 0x2013
    }

    // ── One string resource per entry ─────────────────────────────────────

    private fun <T> assertOneResourceEach(what: String, entries: List<T>, resourceOf: (T) -> StringResource) {
        val byKey: Map<String, List<T>> = entries.groupBy { resourceOf(it).key }
        val shared = byKey.filterValues { it.size > 1 }

        assertTrue(shared.isEmpty(), "$what sharing a string resource: $shared")
        assertEquals(entries.size, byKey.size, what)
    }

    @Test
    fun `every conflict field has its own string resource`() =
        assertOneResourceEach("fields", ConflictField.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every entity type has its own string resource`() =
        assertOneResourceEach("entity types", SyncableEntityType.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every credential type has its own string resource`() =
        assertOneResourceEach("credential types", CredentialType.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every authentication type has its own string resource`() =
        assertOneResourceEach("authentication types", AuthType.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every tunnel type has its own string resource`() =
        assertOneResourceEach("tunnel types", TunnelType.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every SSH key type has its own string resource`() =
        assertOneResourceEach("SSH key types", SshKeyType.entries.toList()) { it.conflictLabelRes() }

    @Test
    fun `every FIDO2 mode has its own string resource`() =
        assertOneResourceEach("FIDO2 modes", Fido2Mode.entries.toList()) { it.conflictLabelRes() }

    // ── The resource files ────────────────────────────────────────────────

    private fun resourcesDir(): File {
        val candidates = listOf("src/desktopMain/composeResources", "desktop/src/desktopMain/composeResources")
        return candidates.map { File(it) }.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error(
                "composeResources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun readStrings(relative: String): Map<String, String> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val document = factory.newDocumentBuilder().parse(File(resourcesDir(), relative))
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    private val french: Map<String, String> by lazy { readStrings("values/strings.xml") }
    private val english: Map<String, String> by lazy { readStrings("values-en/strings.xml") }

    // ── Samples ───────────────────────────────────────────────────────────

    private val sampleValues: List<ConflictValue> = listOf(
        ConflictValue.Text("root@srv.example.org:2222"),
        ConflictValue.YesNo(true),
        ConflictValue.YesNo(false),
        ConflictValue.Seconds(0),
        ConflictValue.Seconds(45),
        ConflictValue.None,
        ConflictValue.PresetTheme("DRACULA"),
        ConflictValue.CustomTheme("3f2a9c1b"),
        ConflictValue.Encrypted,
    ) + Fido2Mode.entries.map { ConflictValue.Fido2(it) } +
        AuthType.entries.map { ConflictValue.AuthMethod(it) } +
        TunnelType.entries.map { ConflictValue.TunnelKind(it) } +
        SshKeyType.entries.map { ConflictValue.SshKeyKind(it) } +
        CredentialType.entries.map { ConflictValue.CredentialKind(it) }

    private val sampleTitles: List<ConflictTitle> =
        listOf(ConflictTitle.Label("prod"), ConflictTitle.Deleted) +
            CredentialType.entries.map { ConflictTitle.Credential(it) }

    /** Every string resource a card or the page header reads. */
    private fun usedResources(): List<StringResource> {
        val ofValues = (sampleValues.map { it.toConflictText() } + sampleTitles.map { it.toConflictText() })
            .filterIsInstance<ConflictText.Localized>()
            .map { it.resource }
        return ConflictField.entries.map { it.conflictLabelRes() } +
            SyncableEntityType.entries.map { it.conflictLabelRes() } +
            ofValues +
            listOf(Res.string.conflicts_subtitle_one, Res.string.conflicts_subtitle_many)
    }

    private fun usedKeys(): Set<String> = usedResources().map { it.key }.toSet()

    private fun conflictKeys(strings: Map<String, String>): Set<String> =
        strings.keys.filter { it.startsWith("conflict_") || it.startsWith("conflicts_") }.toSet()

    // ── French and English ────────────────────────────────────────────────

    @Test
    fun `every string a conflict card reads exists in French and in English`() {
        val missing = usedKeys().filter { french[it].isNullOrBlank() || english[it].isNullOrBlank() }

        assertTrue(missing.isEmpty(), "keys missing or blank in values or values-en: $missing")
    }

    @Test
    fun `the conflict keys are the same in French and in English`() {
        val inFrench = conflictKeys(french)
        val inEnglish = conflictKeys(english)

        assertTrue(inFrench.isNotEmpty())
        assertEquals(inFrench - inEnglish, emptySet<String>(), "conflict keys missing from values-en")
        assertEquals(inEnglish - inFrench, emptySet<String>(), "conflict keys missing from values")
    }

    private val positionalToken = Regex("""%\d+\$[sd]""")

    private fun tokens(text: String): List<String> = positionalToken.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `positional tokens are the same in both languages and none is bare`() {
        for (key in usedKeys() + conflictKeys(french)) {
            val inFrench = french.getValue(key)
            val inEnglish = english.getValue(key)

            assertEquals(tokens(inFrench), tokens(inEnglish), "tokens of $key")
            // Compose resources only fill %1$s and %1$d: a bare %s or %d would be shown as it is.
            assertFalse(positionalToken.replace(inFrench, "").contains('%'), "bare token in $key (values)")
            assertFalse(positionalToken.replace(inEnglish, "").contains('%'), "bare token in $key (values-en)")
        }
    }

    @Test
    fun `the duration and the custom theme strings take one positional token`() {
        assertEquals(listOf("%1\$d"), tokens(french.getValue("conflict_value_seconds")))
        assertEquals(listOf("%1\$d"), tokens(english.getValue("conflict_value_seconds")))
        assertEquals(listOf("%1\$s"), tokens(french.getValue("conflict_value_custom_theme")))
        assertEquals(listOf("%1\$s"), tokens(english.getValue("conflict_value_custom_theme")))
        assertEquals(listOf("%1\$d"), tokens(french.getValue("conflicts_subtitle_many")))
        assertEquals(listOf("%1\$d"), tokens(english.getValue("conflicts_subtitle_many")))
    }

    @Test
    fun `the conflict strings hold no em dash and no en dash`() {
        val longDashes = listOf(Char(EM_DASH_CODE_POINT), Char(EN_DASH_CODE_POINT))
        val keys = usedKeys() + conflictKeys(french)

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in keys) {
                val text = strings.getValue(key)
                assertTrue(longDashes.none { it in text }, "long dash in $language/$key: $text")
            }
        }
    }

    @Test
    fun `French puts a space before a colon and English does not`() {
        for (key in usedKeys() + conflictKeys(french)) {
            assertFalse(Regex("""\S:""").containsMatchIn(french.getValue(key)), "no space before a colon in values/$key")
            assertFalse(english.getValue(key).contains(" :"), "space before a colon in values-en/$key")
        }
    }

    @Test
    fun `the name of a row is a plain noun without a colon`() {
        for (field in ConflictField.entries) {
            val key = field.conflictLabelRes().key

            assertFalse(french.getValue(key).contains(':'), "colon in values/$key")
            assertFalse(english.getValue(key).contains(':'), "colon in values-en/$key")
        }
    }

    // ── Wording shared with the Android app ───────────────────────────────

    private fun frenchNameOf(field: ConflictField): String = french.getValue(field.conflictLabelRes().key)

    private fun englishNameOf(field: ConflictField): String = english.getValue(field.conflictLabelRes().key)

    @Test
    fun `the flags and the placeholders are written as on Android`() {
        assertEquals("Oui", french.getValue("conflict_value_yes"))
        assertEquals("Non", french.getValue("conflict_value_no"))
        assertEquals("Personnalisé (%1\$s…)", french.getValue("conflict_value_custom_theme"))
        assertEquals("Oui (contenu non affichable)", french.getValue("conflict_value_encrypted"))

        assertEquals("Yes", english.getValue("conflict_value_yes"))
        assertEquals("No", english.getValue("conflict_value_no"))
        assertEquals("Custom (%1\$s…)", english.getValue("conflict_value_custom_theme"))
        assertEquals("Yes (content not shown)", english.getValue("conflict_value_encrypted"))
    }

    @Test
    fun `the rows of a host, a tunnel and a credential are named as on Android`() {
        assertEquals("Nom", frenchNameOf(ConflictField.LABEL))
        assertEquals("Name", englishNameOf(ConflictField.LABEL))

        assertEquals("Type de tunnel", frenchNameOf(ConflictField.TUNNEL_TYPE))
        assertEquals("Tunnel type", englishNameOf(ConflictField.TUNNEL_TYPE))

        assertEquals("Démarrage automatique", frenchNameOf(ConflictField.AUTO_START))
        assertEquals("Auto start", englishNameOf(ConflictField.AUTO_START))

        assertEquals("Ouvrir le navigateur", frenchNameOf(ConflictField.OPEN_BROWSER_ON_CONNECT))
        assertEquals("Open browser", englishNameOf(ConflictField.OPEN_BROWSER_ON_CONNECT))

        assertEquals("Actif sans navigateur", frenchNameOf(ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE))
        assertEquals("Active without browser", englishNameOf(ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE))

        assertEquals("Type de credential", frenchNameOf(ConflictField.CREDENTIAL_TYPE))
        assertEquals("Credential type", englishNameOf(ConflictField.CREDENTIAL_TYPE))
    }

    @Test
    fun `these rows have their own conflict strings and no longer borrow a form label`() {
        val own = mapOf(
            ConflictField.LABEL to "conflict_field_label",
            ConflictField.TUNNEL_TYPE to "conflict_field_tunnel_type",
            ConflictField.AUTO_START to "conflict_field_auto_start",
            ConflictField.OPEN_BROWSER_ON_CONNECT to "conflict_field_open_browser_on_connect",
            ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE to "conflict_field_keep_alive_after_browser_close",
            ConflictField.CREDENTIAL_TYPE to "conflict_field_credential_type",
        )

        for ((field, key) in own) {
            assertEquals(key, field.conflictLabelRes().key, "string of $field")
        }
    }

    // ── The rows of one card are told apart ───────────────────────────────

    private val customThemeId = "3f2a9c1b-7d4e-4a55-9b0c-1234567890ab"

    private val samples: Map<SyncableEntityType, Any> = mapOf(
        SyncableEntityType.HOST to Host(
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
        ),
        SyncableEntityType.TUNNEL to TunnelConfig(
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
        ),
        SyncableEntityType.SSH_KEY to SshKey(
            id = "key-1",
            label = "deploy",
            keyType = SshKeyType.ED25519,
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
        ),
        SyncableEntityType.SNIPPET to Snippet(
            id = "snippet-1",
            label = "restart",
            command = "systemctl restart nginx",
            category = "ops",
            createdAt = 1L,
        ),
        SyncableEntityType.CREDENTIAL to CredentialEntry(
            credentialId = "9f8e7d6c-5b4a-3210-fedc-ba9876543210",
            type = CredentialType.SSH_PRIVATE_KEY,
            iv = "IV-SENTINEL-0123",
            encryptedPayload = "PAYLOAD-SENTINEL-4567",
            contentHashBase64 = "HASH-SENTINEL-89ab",
        ),
        SyncableEntityType.CUSTOM_TERMINAL_THEME to CustomTerminalTheme(
            id = customThemeId,
            name = "Night",
            background = 0xFF0F0F0F.toInt(),
            foreground = 0xFFE0E0E0.toInt(),
            cursor = 0xFFFFFFFF.toInt(),
            selectionBg = 0xFF333333.toInt(),
            ansi = List(CustomTerminalTheme.ANSI_SIZE) { 0xFF000000.toInt() },
            createdAt = 1L,
        ),
    )

    private fun encode(type: SyncableEntityType, payload: Any?): String {
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

    private fun decoded(type: SyncableEntityType, payload: Any?): TypedEntity =
        assertNotNull(decodeTypedEntity(type, encode(type, payload)), "decoding of $type")

    @Test
    fun `the rows of a card have different names in French and in English`() {
        for ((type, payload) in samples) {
            val fields = decoded(type, payload).fields.map { it.field }

            for ((language, strings) in listOf("values" to french, "values-en" to english)) {
                val names = fields.map { strings.getValue(it.conflictLabelRes().key) }

                assertEquals(names.size, names.toSet().size, "two rows of a $type card read the same in $language: $names")
            }
        }
    }

    // ── The text of a value or of a title ─────────────────────────────────

    private suspend fun ConflictText.read(): String = when (this) {
        is ConflictText.Literal -> text
        is ConflictText.Localized -> getString(resource, *args.toTypedArray())
    }

    private fun assertReadable(text: String, what: String) {
        assertTrue(text.isNotBlank(), "$what reads blank")
        assertFalse(text.contains('%'), "$what keeps a raw token: $text")
    }

    @Test
    fun `every sample value and title reads as a plain text`() = runTest {
        for (value in sampleValues) assertReadable(value.toConflictText().read(), "value $value")
        for (title in sampleTitles) assertReadable(title.toConflictText().read(), "title $title")
    }

    @Test
    fun `a flag reads yes or no and the two differ`() = runTest {
        val yes = ConflictValue.YesNo(true).toConflictText().read()
        val no = ConflictValue.YesNo(false).toConflictText().read()

        assertReadable(yes, "yes")
        assertReadable(no, "no")
        assertNotEquals(yes, no)
    }

    @Test
    fun `a duration carries its number`() = runTest {
        val fortyFive = ConflictValue.Seconds(45).toConflictText().read()
        val zero = ConflictValue.Seconds(0).toConflictText().read()

        assertTrue(fortyFive.contains("45"), fortyFive)
        assertTrue(zero.contains("0"), zero)
        assertFalse(fortyFive.contains('%'), fortyFive)
    }

    @Test
    fun `an absent value reads as a dash and typed text is shown as it is`() = runTest {
        assertEquals("-", ConflictValue.None.toConflictText().read())
        assertEquals("root@srv.example.org:2222", ConflictValue.Text("root@srv.example.org:2222").toConflictText().read())
        assertEquals("", ConflictValue.Text("").toConflictText().read())
    }

    @Test
    fun `a built-in theme reads its display name and an unknown name is shown as it is`() = runTest {
        for (theme in TerminalThemeId.entries) {
            assertEquals(theme.displayName, ConflictValue.PresetTheme(theme.name).toConflictText().read())
        }
        assertEquals("Dracula", ConflictValue.PresetTheme("DRACULA").toConflictText().read())
        assertEquals("FUTURE_THEME", ConflictValue.PresetTheme("FUTURE_THEME").toConflictText().read())
        val cut = "T".repeat(32) + "…"
        assertEquals(cut, ConflictValue.PresetTheme(cut).toConflictText().read())
    }

    @Test
    fun `a custom theme shows its short id followed by an ellipsis and never the whole id`() = runTest {
        val text = ConflictValue.CustomTheme(customThemeId.take(8)).toConflictText().read()

        assertReadable(text, "custom theme")
        assertTrue(text.contains(customThemeId.take(8) + "…"), text)
        assertFalse(text.contains(customThemeId), text)
    }

    @Test
    fun `the encrypted marker is a fixed sentence from the resources and carries no data`() = runTest {
        val marker = ConflictValue.Encrypted.toConflictText()

        assertTrue(marker is ConflictText.Localized && marker.args.isEmpty(), "marker: $marker")
        assertReadable(marker.read(), "encrypted marker")
    }

    @Test
    fun `enumerated values read through the resources and not as their enum name`() = runTest {
        for (authType in AuthType.entries) {
            assertNotEquals(authType.name, ConflictValue.AuthMethod(authType).toConflictText().read())
        }
        for (tunnelType in TunnelType.entries) {
            assertNotEquals(tunnelType.name, ConflictValue.TunnelKind(tunnelType).toConflictText().read())
        }
        for (mode in Fido2Mode.entries) {
            assertNotEquals(mode.name, ConflictValue.Fido2(mode).toConflictText().read())
        }
        for (credentialType in CredentialType.entries) {
            assertNotEquals(credentialType.name, ConflictValue.CredentialKind(credentialType).toConflictText().read())
        }
        for (keyType in SshKeyType.entries) {
            // Algorithm names: "ED25519" is its own name in every language, so only blankness is checked.
            assertReadable(ConflictValue.SshKeyKind(keyType).toConflictText().read(), "SSH key type $keyType")
        }
    }

    @Test
    fun `a title is the label as typed, or the credential type, or the deleted marker`() = runTest {
        assertEquals("prod", ConflictTitle.Label("prod").toConflictText().read())
        for (credentialType in CredentialType.entries) {
            assertEquals(
                ConflictValue.CredentialKind(credentialType).toConflictText().read(),
                ConflictTitle.Credential(credentialType).toConflictText().read(),
                "title of a $credentialType credential",
            )
        }
        assertReadable(ConflictTitle.Deleted.toConflictText().read(), "deleted title")
    }

    // ── The title of a card, from its two versions ────────────────────────

    private val deletedKey = Res.string.conflict_title_deleted.key

    private fun keyOf(text: ConflictText): String = assertIs<ConflictText.Localized>(text).resource.key

    @Test
    fun `a card is named after the local version first, then the remote one`() = runTest {
        val local = ConflictTitle.Label("prod-local")
        val remote = ConflictTitle.Label("prod-remote")

        assertEquals("prod-local", conflictCardTitle(local, remote, SyncableEntityType.HOST).read())
        assertEquals("prod-remote", conflictCardTitle(null, remote, SyncableEntityType.HOST).read())
        assertEquals("prod-local", conflictCardTitle(local, null, SyncableEntityType.HOST).read())
    }

    @Test
    fun `an element deleted on one side only is named after the side that still exists`() = runTest {
        val alive = ConflictTitle.Label("prod")

        assertEquals("prod", conflictCardTitle(ConflictTitle.Deleted, alive, SyncableEntityType.HOST).read())
        assertEquals("prod", conflictCardTitle(alive, ConflictTitle.Deleted, SyncableEntityType.HOST).read())
        for (credentialType in CredentialType.entries) {
            val credential = ConflictTitle.Credential(credentialType)
            val expected = ConflictTitle.Credential(credentialType).toConflictText().read()

            assertEquals(expected, conflictCardTitle(ConflictTitle.Deleted, credential, SyncableEntityType.CREDENTIAL).read())
            assertEquals(expected, conflictCardTitle(credential, ConflictTitle.Deleted, SyncableEntityType.CREDENTIAL).read())
        }
    }

    @Test
    fun `an element deleted on every readable side is named with the deleted marker`() = runTest {
        for (type in SyncableEntityType.entries) {
            val both = conflictCardTitle(ConflictTitle.Deleted, ConflictTitle.Deleted, type)
            val localOnly = conflictCardTitle(ConflictTitle.Deleted, null, type)
            val remoteOnly = conflictCardTitle(null, ConflictTitle.Deleted, type)

            for (title in listOf(both, localOnly, remoteOnly)) {
                assertEquals(deletedKey, keyOf(title), "title of a deleted $type")
                assertReadable(title.read(), "deleted title of $type")
            }
        }
    }

    @Test
    fun `a card whose two versions are unreadable is named after its kind of element, not after an id`() = runTest {
        val entityId = "9f8e7d6c-5b4a-3210-fedc-ba9876543210"

        for (type in SyncableEntityType.entries) {
            val title = conflictCardTitle(null, null, type)

            assertEquals(type.conflictLabelRes().key, keyOf(title), "title of an unreadable $type")
            val text = title.read()
            assertReadable(text, "fallback title of $type")
            assertFalse(text.contains(entityId.take(12)), text)
        }
    }

    @Test
    fun `a deleted entity reads as one deleted row of a deleted card in every entity type`() = runTest {
        for (type in SyncableEntityType.entries) {
            val entity = decoded(type, null)

            assertEquals(ConflictTitle.Deleted, entity.title, "title of a deleted $type")
            val row = entity.fields.single()
            assertEquals(ConflictField.DELETED, row.field)
            assertEquals(ConflictValue.YesNo(true), row.value)
            assertReadable(row.value.toConflictText().read(), "deleted row of $type")
        }
    }

    @Test
    fun `every row of every decoded entity reads as a plain text`() = runTest {
        for ((type, payload) in samples) {
            val entity = decoded(type, payload)

            assertReadable(entity.title.toConflictText().read(), "title of $type")
            for (row in entity.fields) {
                assertReadable(row.value.toConflictText().read(), "${row.field} of $type")
                assertTrue(french.getValue(row.field.conflictLabelRes().key).isNotBlank(), "name of ${row.field}")
            }
        }
    }

    @Test
    fun `a decoded host and a decoded tunnel no longer show their enum names`() = runTest {
        val host = decoded(SyncableEntityType.HOST, samples.getValue(SyncableEntityType.HOST))
        val tunnel = decoded(SyncableEntityType.TUNNEL, samples.getValue(SyncableEntityType.TUNNEL))

        val authType = host.fields.single { it.field == ConflictField.AUTH_TYPE }.value.toConflictText().read()
        val tunnelType = tunnel.fields.single { it.field == ConflictField.TUNNEL_TYPE }.value.toConflictText().read()

        assertNotEquals("SSH_KEY", authType)
        assertNotEquals("LOCAL_FORWARD", tunnelType)
    }

    // ── The screen does not use the French API of :shared any more ─────────

    private fun desktopSrc(): File {
        val candidates = listOf("src", "desktop/src")
        return candidates.map { File(it) }.firstOrNull { File(it, "desktopMain/kotlin").isDirectory }
            ?: error(
                "desktop sources not found (working directory ${File(".").absoluteFile.normalize()}, " +
                    "tried $candidates)",
            )
    }

    private fun withoutComments(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")

    @Test
    fun `the conflict screen is written on the typed model and holds no French sentence`() {
        val screen = File(desktopSrc(), "desktopMain/kotlin/fr/techtical/nextsh/desktop/sync/ConflictResolutionScreen.kt")
        assertTrue(screen.isFile, "screen source not found: $screen")
        val source = withoutComments(screen.readText(Charsets.UTF_8))

        for (legacy in listOf("decodeEntity", "DecodedEntity", "diffFields", "primaryLabel", "displayName")) {
            assertFalse(Regex("\\b$legacy\\b").containsMatchIn(source), "the screen still uses $legacy")
        }
        assertTrue(source.contains("decodeTypedEntity"), "the screen does not decode the typed model")
        assertTrue(source.contains("diffTypedFields"), "the screen does not compare the typed fields")
        // The page header used to be built in French in the screen.
        assertFalse(source.contains("à résoudre"), "a French sentence is back in the screen")
        // A fragment of an id is not a title: the card is named by conflictCardTitle.
        assertFalse(source.contains("entityId.take"), "the screen shows a fragment of the entity id again")
        assertTrue(source.contains("conflictCardTitle"), "the screen does not name its cards with conflictCardTitle")
    }

    @Test
    fun `no source of the Desktop imports the French API of the shared conflict package`() {
        val legacyImport = Regex(
            """import\s+fr\.techtical\.nextsh\.shared\.ui\.conflict\.""" +
                """((decodeEntity|DecodedEntity|diffFields|displayName)(?![A-Za-z0-9_])|\*)""",
        )
        val files = File(desktopSrc(), "desktopMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size > 50, "suspiciously few Desktop sources found: ${files.size}")

        val offenders = files.filter { legacyImport.containsMatchIn(it.readText(Charsets.UTF_8)) }.map { it.name }

        assertTrue(offenders.isEmpty(), "French API of :shared imported by $offenders")
    }
}
