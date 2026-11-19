// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sync

import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Fido2Mode
import fr.techtical.nextsh.domain.model.SshKeyType
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.shared.core.sync.CredentialType
import fr.techtical.nextsh.shared.ui.conflict.ConflictField
import fr.techtical.nextsh.testutil.AccentedLiteralScanner
import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.StringResourceFile
import fr.techtical.nextsh.ui.SourceGuardSupport
import fr.techtical.nextsh.ui.hosts.authTypeLabelRes
import fr.techtical.nextsh.ui.theme.TerminalThemeId
import fr.techtical.nextsh.ui.tunnels.tunnelTypeLabelRes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The card of a sync conflict used to show the names and the values of its rows as French sentences built by
 * `:shared` ("Favori", "Reconnexion automatique", "oui", "clé matérielle"): an English phone read them between
 * English lines. The screen now reads the typed model of `:shared` (a [ConflictField] and a value per row) and
 * says each of them from resources. This guards that every field and every kind of value has its words, in
 * French (the default) and in English, and that the screen no longer calls the French API.
 */
class ConflictFieldLabelsTest {

    private val french = StringResourceFile("values/strings.xml")
    private val english = StringResourceFile("values-en/strings.xml")

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    private fun nameOfString(id: Int): String =
        R.string::class.java.fields.first { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }.name

    private fun frenchText(name: String) = StringResourceFile.unescape(french.strings.getValue(name))

    private fun englishText(name: String) = StringResourceFile.unescape(english.strings.getValue(name))

    private val noArgument = emptySet<String>()
    private val stringArg = setOf("%1\$s")
    private val intArg = setOf("%1\$d")

    /** The resource of a row name, with the French and the English text it must read. */
    private data class Name(val key: String, val french: String, val english: String)

    private val fieldNames: Map<ConflictField, Name> = mapOf(
        // The names that the forms of the app already say with one word.
        ConflictField.LABEL to Name("label_name", "Nom", "Name"),
        ConflictField.NAME to Name("label_theme_name", "Nom du thème", "Theme name"),
        ConflictField.AUTH_TYPE to Name("section_authentication", "Authentification", "Authentication"),
        ConflictField.GROUP to Name("label_group", "Groupe", "Group"),
        ConflictField.KEEP_ALIVE to Name("label_keepalive_interval", "Keep-alive", "Keep-alive"),
        ConflictField.TUNNEL_TYPE to Name("section_tunnel_type", "Type de tunnel", "Tunnel type"),
        ConflictField.BROWSER_PATH to Name("label_tunnel_browser_path", "Chemin d'ouverture", "Start path"),
        ConflictField.AUTO_START to Name("label_auto_start", "Démarrage automatique", "Auto start"),
        ConflictField.COMMAND to Name("label_snippet_command", "Commande", "Command"),
        ConflictField.BACKGROUND to Name("label_theme_background", "Fond", "Background"),
        ConflictField.FOREGROUND to Name("label_theme_foreground", "Texte", "Foreground"),
        // The names of the card only (shorter than the label of the form, more precise than it, or without one).
        // Same words as the desktop card. "Host" and "Type" alone would repeat the badge of the kind of item.
        ConflictField.ADDRESS to Name("conflict_field_address", "Adresse", "Address"),
        ConflictField.KEY_TYPE to Name("conflict_field_key_type", "Type de clé", "Key type"),
        ConflictField.FAVORITE to Name("conflict_field_favorite", "Favori", "Favorite"),
        ConflictField.AUTO_RECONNECT to Name("conflict_field_auto_reconnect", "Reconnexion automatique", "Auto reconnect"),
        ConflictField.TERMINAL_THEME to Name("conflict_field_terminal_theme", "Thème", "Theme"),
        ConflictField.FIDO2_MODE to Name("conflict_field_fido2_mode", "Mode FIDO2", "FIDO2 mode"),
        ConflictField.CREDENTIAL_REFERENCE to Name("conflict_field_credential_reference", "Clé ou mot de passe", "Key or password"),
        ConflictField.FORWARDING to Name("conflict_field_forwarding", "Redirection de port", "Port forwarding"),
        ConflictField.HOST_REFERENCE to Name("conflict_field_host_reference", "Identifiant de l'hôte", "Host ID"),
        ConflictField.OPEN_BROWSER_ON_CONNECT to Name("conflict_field_open_browser_on_connect", "Ouvrir le navigateur", "Open browser"),
        ConflictField.KEEP_ALIVE_AFTER_BROWSER_CLOSE to Name(
            "conflict_field_keep_alive_after_browser_close",
            "Actif sans navigateur",
            "Active without browser",
        ),
        ConflictField.PUBLIC_KEY to Name("conflict_field_public_key", "Clé publique", "Public key"),
        ConflictField.CATEGORY to Name("conflict_field_category", "Catégorie", "Category"),
        ConflictField.CREDENTIAL_TYPE to Name("conflict_field_credential_type", "Type de credential", "Credential type"),
        ConflictField.CREDENTIAL_ID to Name("conflict_field_credential_id", "Identifiant", "ID"),
        ConflictField.ENCRYPTED_CONTENT to Name("conflict_field_encrypted_content", "Chiffré", "Encrypted"),
        ConflictField.DELETED to Name("conflict_field_deleted", "Supprimé", "Deleted"),
    )

    /** The type of credential: `conflict_value_credential_<name of the type>`. */
    private val credentialNames: Map<CredentialType, Name> = mapOf(
        CredentialType.HOST_PASSWORD to Name("conflict_value_credential_host_password", "Mot de passe", "Password"),
        CredentialType.SSH_PRIVATE_KEY to Name("conflict_value_credential_ssh_private_key", "Clé privée SSH", "SSH private key"),
        CredentialType.SSH_PRIVATE_KEY_PASSPHRASE to Name(
            "conflict_value_credential_ssh_private_key_passphrase",
            "Passphrase de clé SSH",
            "SSH key passphrase",
        ),
        CredentialType.CERTIFICATE to Name("conflict_value_credential_certificate", "Certificat SSH", "SSH certificate"),
    )

    /** The other values and the title of a deleted item. */
    private val valueNames: List<Name> = listOf(
        Name("conflict_value_yes", "Oui", "Yes"),
        Name("conflict_value_no", "Non", "No"),
        Name("conflict_value_seconds", "%1\$d s", "%1\$d s"),
        Name("conflict_value_custom_theme", "Personnalisé (%1\$s…)", "Custom (%1\$s…)"),
        Name("conflict_value_encrypted", "Oui (contenu non affichable)", "Yes (content not shown)"),
        Name("conflict_value_deleted_title", "(supprimé)", "(deleted)"),
    )

    /** Every string added for the card, name to the placeholders it takes. */
    private val newStrings: Map<String, Set<String>> =
        (fieldNames.values.filter { it.key.startsWith("conflict_field_") } + credentialNames.values + valueNames)
            .associate { it.key to placeholders(it.french) }

    /** The ones that read the same in both languages: a number and its unit. */
    private val sameInBothLanguages = setOf("conflict_value_seconds")

    // ── The names of the rows ─────────────────────────────────────────────────

    @Test
    fun `every field of a conflict card has a name of its own, in French and in English`() {
        // The table is complete: a field added to :shared fails here until it is listed.
        assertEquals(ConflictField.entries.toSet(), fieldNames.keys, "a field has no expected name")

        val resources = ConflictField.entries.associateWith { conflictFieldLabelRes(it) }
        assertEquals(resources.size, resources.values.toSet().size, "two fields share a resource: $resources")

        resources.forEach { (field, id) ->
            val expected = fieldNames.getValue(field)
            assertEquals(expected.key, nameOfString(id), "resource of $field")
            assertTrue(expected.key in french.strings, "$field is missing from French")
            assertTrue(expected.key in english.strings, "$field is missing from English")
            assertEquals(expected.french, frenchText(expected.key), "$field (fr)")
            assertEquals(expected.english, englishText(expected.key), "$field (en)")
        }
    }

    @Test
    fun `a row name is a short word, never a sentence, a label with a colon or a format`() {
        fieldNames.forEach { (field, name) ->
            for ((language, text) in listOf("fr" to name.french, "en" to name.english)) {
                assertFalse(text.contains(':'), "$field ($language) holds a colon: $text")
                assertFalse(text.contains('%'), "$field ($language) holds a placeholder: $text")
                assertFalse(text.contains('(') || text.contains('.'), "$field ($language) reads as a sentence: $text")
                assertTrue(text.length <= 24, "$field ($language) would take two lines in half a card: $text")
            }
        }
    }

    @Test
    fun `the keys of the card that exist for it are all named after their field`() {
        fieldNames.filterValues { it.key.startsWith("conflict_field_") }.forEach { (field, name) ->
            assertEquals("conflict_field_${field.name.lowercase()}", name.key)
        }
    }

    // ── The values ────────────────────────────────────────────────────────────

    @Test
    fun `every type of credential has a name of its own, in French and in English`() {
        assertEquals(CredentialType.entries.toSet(), credentialNames.keys, "a type has no expected name")

        val resources = CredentialType.entries.associateWith { credentialTypeLabelRes(it) }
        assertEquals(resources.size, resources.values.toSet().size, "two types share a resource: $resources")

        resources.forEach { (type, id) ->
            val expected = credentialNames.getValue(type)
            assertEquals("conflict_value_credential_${type.name.lowercase()}", nameOfString(id))
            assertEquals(expected.key, nameOfString(id))
            assertEquals(expected.french, frenchText(expected.key), "$type (fr)")
            assertEquals(expected.english, englishText(expected.key), "$type (en)")
        }
    }

    @Test
    fun `the FIDO2 mode, the authentication and the type of tunnel reuse the words of their screens`() {
        val modes = Fido2Mode.entries.associateWith { fido2ModeLabelRes(it) }
        assertEquals(modes.size, modes.values.toSet().size, "two modes share a resource: $modes")
        assertEquals("fido2_mode_hardware", nameOfString(modes.getValue(Fido2Mode.HARDWARE_KEY)))
        assertEquals("fido2_mode_passkey", nameOfString(modes.getValue(Fido2Mode.PASSKEY)))

        val auth = AuthType.entries.associateWith { authTypeLabelRes(it) }
        val tunnels = TunnelType.entries.associateWith { tunnelTypeLabelRes(it) }
        listOf(modes.values, auth.values, tunnels.values).flatten().map(::nameOfString).forEach { name ->
            assertTrue(name in french.strings, "$name is missing from French")
            assertTrue(name in english.strings, "$name is missing from English")
            assertFalse(frenchText(name).isBlank() || englishText(name).isBlank(), "$name is empty")
        }
        assertEquals("Clé matérielle", frenchText("fido2_mode_hardware"))
        assertEquals("Hardware Key", englishText("fido2_mode_hardware"))
    }

    @Test
    fun `a preset theme is shown with the name of the app, and an unknown one as it came`() {
        TerminalThemeId.entries.forEach { assertEquals(it.displayName, presetThemeName(it.name)) }
        assertEquals("Dracula", presetThemeName("DRACULA"))
        assertEquals("Techtical Dark", presetThemeName("TECHTICAL_DARK"))
        // A theme of a newer version, and a long name that :shared cut: nothing is invented.
        assertEquals("FUTURE_THEME", presetThemeName("FUTURE_THEME"))
        val cut = "A".repeat(32) + "…"
        assertEquals(cut, presetThemeName(cut))
    }

    @Test
    fun `a type of SSH key is written as the algorithm, the same in every language`() {
        val names = SshKeyType.entries.associateWith { sshKeyTypeName(it) }

        assertEquals(names.size, names.values.toSet().size, "two key types share a name: $names")
        names.forEach { (type, name) ->
            assertFalse(name.isBlank(), "$type")
            assertFalse(name.contains('_'), "$type is written like the enum: $name")
            assertTrue(name.all { it.code < 128 }, "$type: $name")
        }
        assertEquals("ED25519", names.getValue(SshKeyType.ED25519))
        assertEquals("RSA-4096", names.getValue(SshKeyType.RSA_4096))
    }

    @Test
    fun `a row without a value shows a plain hyphen`() {
        assertEquals("-", CONFLICT_NO_VALUE)
        assertFalse(holdsDash(CONFLICT_NO_VALUE))
    }

    @Test
    fun `the values read right in French and in English`() {
        fun render(text: String, argument: String) = text.replace("%1\$d", argument).replace("%1\$s", argument)

        assertEquals("30 s", render(frenchText("conflict_value_seconds"), "30"))
        assertEquals("30 s", render(englishText("conflict_value_seconds"), "30"))
        assertEquals("Personnalisé (a1b2c3d4…)", render(frenchText("conflict_value_custom_theme"), "a1b2c3d4"))
        assertEquals("Custom (a1b2c3d4…)", render(englishText("conflict_value_custom_theme"), "a1b2c3d4"))
        assertEquals("Oui (contenu non affichable)", frenchText("conflict_value_encrypted"))
        assertEquals("Yes (content not shown)", englishText("conflict_value_encrypted"))
        assertEquals("(supprimé)", frenchText("conflict_value_deleted_title"))
        assertEquals("(deleted)", englishText("conflict_value_deleted_title"))
    }

    // ── The new strings ───────────────────────────────────────────────────────

    @Test
    fun `every new string exists in French and in English and takes the arguments it is called with`() {
        assertTrue(newStrings.size >= 25, "the new strings are not found: ${newStrings.keys}")
        newStrings.forEach { (name, expected) ->
            assertTrue(name in french.strings, "$name is missing from French")
            assertTrue(name in english.strings, "$name is missing from English")
            assertEquals(expected, placeholders(french.strings.getValue(name)), "placeholders of $name in French")
            assertEquals(expected, placeholders(english.strings.getValue(name)), "placeholders of $name in English")
        }
        assertEquals(noArgument, newStrings.getValue("conflict_field_favorite"))
        assertEquals(intArg, newStrings.getValue("conflict_value_seconds"))
        assertEquals(stringArg, newStrings.getValue("conflict_value_custom_theme"))
    }

    @Test
    fun `a new string is not empty, and is translated unless it reads the same in both languages`() {
        newStrings.keys.forEach { name ->
            val fr = french.strings.getValue(name)
            val en = english.strings.getValue(name)
            assertFalse(fr.isBlank() || en.isBlank(), "$name must not be empty")
            if (name in sameInBothLanguages) {
                assertEquals(fr, en, "$name is listed as the same in both languages")
            } else {
                assertNotEquals(fr, en, "$name is not translated")
            }
        }
    }

    @Test
    fun `no new string holds an em dash or an en dash`() {
        newStrings.keys.forEach { name ->
            assertFalse(holdsDash(french.strings.getValue(name)), "$name (fr)")
            assertFalse(holdsDash(english.strings.getValue(name)), "$name (en)")
        }
    }

    @Test
    fun `the French new strings address the user as vous, never tu`() {
        newStrings.keys.forEach { name ->
            val tu = FrenchTuForm.find(french.strings.getValue(name))
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): ${french.strings.getValue(name)}")
        }
    }

    @Test
    fun `the apostrophes of the new strings are escaped for aapt2`() {
        newStrings.keys.forEach { name ->
            listOf("fr" to french, "en" to english).forEach { (language, file) ->
                val text = file.strings.getValue(name)
                assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
            }
        }
    }

    // ── The screen ────────────────────────────────────────────────────────────

    @Test
    fun `the conflict screen reads the typed model, not the French API of the shared module`() {
        val screen = SourceGuardSupport.mainSource("ui/sync/ConflictResolutionScreen.kt")

        listOf("decodeEntity", "DecodedEntity", "diffFields", "primaryLabel", "displayName").forEach {
            assertFalse(screen.contains(it), "ConflictResolutionScreen still uses $it")
        }
        assertTrue(screen.contains("decodeTypedEntity(conflict.entityType, conflict.localJson)"))
        assertTrue(screen.contains("decodeTypedEntity(conflict.entityType, conflict.remoteJson)"))
        assertTrue(screen.contains("diffTypedFields(localDecoded, remoteDecoded)"))
        // The highlight follows the field of the row, not its text.
        assertTrue(screen.contains("row.field in differingFields"), "the highlight does not follow the field")
        assertTrue(screen.contains("stringResource(conflictFieldLabelRes(row.field))"), "the row name is not a resource")
        assertTrue(screen.contains("conflictValueText(row.value)"), "the row value is not said from resources")
        assertTrue(screen.contains("conflictTitleText(it.title)"), "the title is not said from resources")
        // An item deleted on one side only is titled by the other side, not by "(deleted)".
        assertTrue(
            screen.contains("it.title !is ConflictTitle.Deleted"),
            "the title of an item deleted on one side does not come from the other side",
        )
    }

    @Test
    fun `no source of the app calls the deprecated French API of the conflict model any more`() {
        val kotlinRoot = File(SourceGuardSupport.locate("src/main/AndroidManifest.xml").parentFile, "kotlin")
        val legacy = Regex("""\b(?:decodeEntity|DecodedEntity|diffFields)\b|shared\.ui\.conflict\.displayName""")

        val files = kotlinRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no Kotlin source under ${kotlinRoot.path}: the guard cannot check it")
        val offenders = files
            .filter { legacy.containsMatchIn(SourceGuardSupport.withoutComments(it.readText())) }
            .map { it.name }
        assertEquals(emptyList<String>(), offenders, "still on the French API of the conflict model")
    }

    @Test
    fun `no French word is written in the code of the sync screens`() {
        assertEquals(emptyList<String>(), AccentedLiteralScanner.scan("ui/sync"), "a French literal in a sync screen")
    }
}
