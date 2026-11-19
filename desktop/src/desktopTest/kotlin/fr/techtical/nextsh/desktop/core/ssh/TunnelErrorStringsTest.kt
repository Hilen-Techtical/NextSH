// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.ssh

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests of the string resources behind the errors of a tunnel start (`TunnelSetupFailure.kt` and the
 * factory of `DesktopTunnelManager.kt`), the ones of a local listener or SOCKS5 proxy that stopped
 * (`tunnels_error_listener_stopped`, `tunnels_error_socks_stopped`) and the reasons of a reconnection
 * (`tunnels_error_session_closed`, `tunnels_error_no_answer`, `tunnels_error_network_lost`) : each key exists in French and in English, with the same
 * positional tokens and none left bare, no long dash, and the punctuation of each language.
 *
 * The French and the English files are read as XML from the source tree (the same way as
 * `ConflictLabelsTest`), so that both languages are checked whatever the machine uses. The long
 * dashes are built from their code points so that this file itself holds none.
 *
 * `tunnels_error_unreadable` is not covered : it is rewritten by another change.
 */
class TunnelErrorStringsTest {

    private companion object {
        const val EM_DASH_CODE_POINT = 0x2014
        const val EN_DASH_CODE_POINT = 0x2013

        /** The keys this change adds, each with the positional tokens its text must carry. */
        val NEW_KEYS: Map<String, List<String>> = mapOf(
            "tunnels_error_local_port_reserved" to listOf("%1\$d"),
            "tunnels_error_remote_forward_refused" to listOf("%1\$d"),
            "tunnels_error_setup_timeout" to emptyList(),
            "tunnels_error_connection_lost" to emptyList(),
            "tunnels_error_listener_stopped" to emptyList(),
            "tunnels_error_socks_stopped" to emptyList(),
            "tunnels_error_session_closed" to emptyList(),
            "tunnels_error_no_answer" to emptyList(),
            "tunnels_error_network_lost" to emptyList(),
            "tunnels_error_password_missing" to listOf("%1\$s"),
            "tunnels_error_fido2_unsupported" to emptyList(),
            "tunnels_error_key_unreadable" to emptyList(),
            "tunnels_error_auth_failed" to emptyList(),
            "tunnels_error_host_key_rejected" to emptyList(),
        )

        /** The keys that were already there and that the same paths read now. */
        val REUSED_KEYS: Map<String, List<String>> = mapOf(
            "tunnels_error_port_in_use" to listOf("%1\$d"),
            "tunnels_error_start_failed" to emptyList(),
            "session_error_ssh_key_missing" to listOf("%1\$s"),
            "session_error_key_or_certificate_missing" to listOf("%1\$s"),
            "session_error_biometric_unsupported" to emptyList(),
            "connect_error_generic" to emptyList(),
        )

        val ALL_KEYS: Map<String, List<String>> = NEW_KEYS + REUSED_KEYS
    }

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

    private val positionalToken = Regex("""%\d+\$[sd]""")

    private fun tokens(text: String): List<String> = positionalToken.findAll(text).map { it.value }.sorted().toList()

    @Test
    fun `every key of a tunnel start error exists in French and in English and is not blank`() {
        val missing = ALL_KEYS.keys.filter { french[it].isNullOrBlank() || english[it].isNullOrBlank() }

        assertTrue(missing.isEmpty(), "keys missing or blank in values or values-en: $missing")
    }

    @Test
    fun `each text carries the same positional tokens in both languages and none is bare`() {
        for ((key, expected) in ALL_KEYS) {
            val inFrench = french.getValue(key)
            val inEnglish = english.getValue(key)

            assertEquals(expected, tokens(inFrench), "tokens of $key (values)")
            assertEquals(expected, tokens(inEnglish), "tokens of $key (values-en)")
            // Compose resources only fill %1$s and %1$d: a bare %s or %d would be shown as it is.
            assertFalse(positionalToken.replace(inFrench, "").contains('%'), "bare token in $key (values)")
            assertFalse(positionalToken.replace(inEnglish, "").contains('%'), "bare token in $key (values-en)")
        }
    }

    @Test
    fun `the new texts hold no em dash and no en dash`() {
        val longDashes = listOf(Char(EM_DASH_CODE_POINT), Char(EN_DASH_CODE_POINT))

        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in NEW_KEYS.keys) {
                val text = strings.getValue(key)
                assertTrue(longDashes.none { it in text }, "long dash in $language/$key: $text")
            }
        }
    }

    @Test
    fun `French puts a space before a colon and English does not`() {
        for (key in NEW_KEYS.keys) {
            assertFalse(Regex("""\S:""").containsMatchIn(french.getValue(key)), "no space before a colon in values/$key")
            assertFalse(english.getValue(key).contains(" :"), "space before a colon in values-en/$key")
        }
    }

    @Test
    fun `a new text is a sentence that ends with a full stop and starts with a capital`() {
        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            for (key in NEW_KEYS.keys) {
                val text = strings.getValue(key)

                assertEquals(text, text.trim(), "stray space around $language/$key")
                assertTrue(text.endsWith("."), "no full stop at the end of $language/$key: $text")
                assertTrue(text.first().isUpperCase(), "no capital at the start of $language/$key: $text")
            }
        }
    }

    @Test
    fun `the host key text names the labels the user clicks, as the application writes them`() {
        // "Settings, Known hosts" is a path in the application : the tab of the sidebar, then the row
        // of the settings page. Not the title of the page it opens, which may be worded differently.
        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            val text = strings.getValue("tunnels_error_host_key_rejected")

            assertTrue(text.contains(strings.getValue("sidebar_tab_settings")), "$language: settings tab not in $text")
            assertTrue(text.contains(strings.getValue("settings_label_known_hosts")), "$language: known hosts row not in $text")
        }
    }

    @Test
    fun `the host key text speaks of a fingerprint, as the known hosts screen does`() {
        // The screen lists "fingerprints" and offers to delete one : the text uses the same words.
        assertTrue(french.getValue("tunnels_error_host_key_rejected").contains("empreinte"))
        assertTrue(french.getValue("known_hosts_subtitle_empty").contains("empreinte"))
        assertTrue(english.getValue("tunnels_error_host_key_rejected").contains("fingerprint"))
        assertTrue(english.getValue("known_hosts_subtitle_empty").contains("fingerprint"))
    }

    @Test
    fun `the host key text holds when there is no old fingerprint to delete`() {
        // The same text is shown when the user refuses a NEW fingerprint, which has no old one : the
        // way out comes first (start again to check it), the deletion only "if it changed".
        val inFrench = french.getValue("tunnels_error_host_key_rejected")
        val inEnglish = english.getValue("tunnels_error_host_key_rejected")

        assertTrue(inFrench.indexOf("Relancez") in 0 until inFrench.indexOf("supprimez"), inFrench)
        assertTrue(inEnglish.indexOf("Start the tunnel again") in 0 until inEnglish.indexOf("delete"), inEnglish)
        assertTrue(inFrench.contains("Si elle a changé"), inFrench)
        assertTrue(inEnglish.contains("If it changed"), inEnglish)
    }

    @Test
    fun `the unreadable key text asks for an import and does not blame the connection`() {
        assertTrue(french.getValue("tunnels_error_key_unreadable").contains("Importez"))
        assertTrue(english.getValue("tunnels_error_key_unreadable").contains("Import"))
        assertFalse(french.getValue("tunnels_error_key_unreadable").contains("connexion", ignoreCase = true))
        assertFalse(english.getValue("tunnels_error_key_unreadable").contains("connection", ignoreCase = true))
    }

    @Test
    fun `no key is left for a Stop that fails because the tunnel is stopped and there is nothing to say`() {
        // A failing Stop answers Success (the tunnel is down) : its old "try again" text is gone, since a
        // click on a stopped card would start the tunnel.
        assertFalse(french.containsKey("tunnels_error_stop_failed"))
        assertFalse(english.containsKey("tunnels_error_stop_failed"))
    }

    @Test
    fun `the FIDO2 text only states the limit and does not advise changing the host`() {
        // The authentication type of a host is synchronised with the other devices, where FIDO2 works :
        // following such an advice would take the hardware key away everywhere.
        for ((language, strings) in listOf("values" to french, "values-en" to english)) {
            val text = strings.getValue("tunnels_error_fido2_unsupported")

            assertEquals(1, text.count { it == '.' }, "one sentence expected in $language: $text")
        }
    }

    @Test
    fun `the authentication text covers the passphrase of the key`() {
        // SSHJ raises the same exception for a wrong passphrase and for a refused password.
        assertTrue(french.getValue("tunnels_error_auth_failed").contains("phrase de passe"))
        assertTrue(english.getValue("tunnels_error_auth_failed").contains("passphrase"))
    }

    @Test
    fun `the French texts use the polite form`() {
        // The imperative of "tu" ends differently from the one of "vous": these are the verbs of the new texts.
        val informal = Regex("""\b(Réessaie|Vérifie|Choisis|Ajoute)\b""")

        for (key in NEW_KEYS.keys) {
            assertFalse(informal.containsMatchIn(french.getValue(key)), "informal form in values/$key: ${french.getValue(key)}")
        }
    }
}
