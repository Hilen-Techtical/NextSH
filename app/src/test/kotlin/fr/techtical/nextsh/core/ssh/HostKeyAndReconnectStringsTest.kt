// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The texts of the confirmation of a server's key, of the automatic reconnection and of what they
 * leave on a tunnel's card are resources, in French (the default, addressed as vous) and in
 * English. The dialog that asks whom to trust is the one a hardcoded language hurt most.
 */
class HostKeyAndReconnectStringsTest {

    /** The strings these two features own, name to raw text. */
    private val names = listOf(
        // The dialog that confirms the key of an unknown server.
        "dialog_host_verification_title", "dialog_trust_host_question", "host_key_dialog_hint",
        "host_key_dialog_algorithm", "host_key_dialog_fingerprint", "host_key_dialog_time_limit",
        "action_trust_host", "action_reject", "label_host",
        // What a connection says when that confirmation could not be had.
        "auth_error_host_key", "auth_error_host_key_no_screen", "auth_error_host_key_timeout",
        // The reconnection: options, and what the card of a tunnel says.
        "label_auto_reconnect", "settings_auto_reconnect_help", "host_auto_reconnect_help",
        "tunnel_error_reconnect_failed_reason", "tunnel_error_reconnect_failed",
        "tunnel_error_reconnect_failed_internal", "tunnel_error_quick_cuts",
        // The fixed reasons of a cut, translated at display time.
        "tunnel_error_session_lost", "tunnel_error_listener_stopped", "tunnel_error_socks_stopped",
        // The fixed reasons of a start or a stop that fails, translated at display time too.
        "tunnel_error_start_failed", "tunnel_error_port_in_use", "tunnel_error_session_not_found",
        "tunnel_error_host_not_found", "tunnel_error_connect_unexpected", "tunnel_error_vault_auth_expired",
        "tunnel_error_not_found", "tunnel_error_stop_failed",
        // The ports a tunnel cannot use, the refusal of the server, and a start that is stopped.
        "tunnel_error_local_port_invalid", "tunnel_error_remote_port_invalid",
        "tunnel_error_local_port_reserved", "tunnel_error_remote_forward_refused",
        "tunnel_error_start_stopped",
        // The notification of the tunnels that wait for the user: key, fingerprint or server.
        "auth_required_channel_name", "auth_required_channel_description", "auth_required_title",
        "auth_required_text_single", "auth_required_public_text",
    )

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        val text = file.readText()
        return names.associateWith { name ->
            Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.groupValues?.get(1) ?: error("$path has no string $name")
        }
    }

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toSet()

    /** An em dash (U+2014) or an en dash (U+2013), by code point so that this file holds none. */
    private fun holdsDash(text: String) = text.any { it.code == 0x2014 || it.code == 0x2013 }

    @Test
    fun `every string exists in French and in English, and is not empty`() {
        names.forEach { name ->
            assertFalse(french.getValue(name).isBlank(), "$name (fr)")
            assertFalse(english.getValue(name).isBlank(), "$name (en)")
        }
    }

    @Test
    fun `a formatted string takes the same arguments in both languages`() {
        names.forEach { name ->
            assertEquals(placeholders(french.getValue(name)), placeholders(english.getValue(name)), "placeholders of $name")
        }
    }

    @Test
    fun `the sentences of this feature are translated`() {
        // The ones that are the same word in both languages are left out: Host is Hôte, not equal.
        listOf(
            "host_key_dialog_hint", "host_key_dialog_time_limit", "auth_error_host_key_no_screen",
            "auth_error_host_key_timeout", "tunnel_error_quick_cuts", "settings_auto_reconnect_help",
            "host_auto_reconnect_help", "tunnel_error_reconnect_failed_reason",
            "tunnel_error_start_failed", "tunnel_error_port_in_use", "tunnel_error_session_not_found",
            "tunnel_error_host_not_found", "tunnel_error_connect_unexpected", "tunnel_error_vault_auth_expired",
            "tunnel_error_not_found", "tunnel_error_stop_failed",
            "tunnel_error_local_port_invalid", "tunnel_error_remote_port_invalid",
            "tunnel_error_local_port_reserved", "tunnel_error_remote_forward_refused",
            "tunnel_error_start_stopped",
        ).forEach { name ->
            assertNotEquals(french.getValue(name), english.getValue(name), "$name is not translated")
        }
    }

    @Test
    fun `no string holds an em dash or an en dash`() {
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) -> assertFalse(holdsDash(text), "$name ($language) holds a dash") }
        }
    }

    @Test
    fun `the French strings address the user as vous, never tu`() {
        french.forEach { (name, text) ->
            val tu = FrenchTuForm.find(text)
            assertFalse(tu != null, "$name uses the tu form (\"$tu\"): $text")
        }
    }

    @Test
    fun `the apostrophes of the French and English strings are escaped for aapt2`() {
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) ->
                assertFalse(Regex("""(?<!\\)'""").containsMatchIn(text), "$name ($language) has an unescaped apostrophe: $text")
            }
        }
    }

    @Test
    fun `no string carries a bare straight double quote, it is escaped for aapt2 or typographic`() {
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.forEach { (name, text) ->
                assertFalse(Regex("""(?<!\\)"""").containsMatchIn(text), "$name ($language): $text")
            }
        }
    }

    @Test
    fun `the dialog gives the time limit it enforces`() {
        // %1$d is the 60 s of HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS, given by the dialog.
        assertTrue(french.getValue("host_key_dialog_time_limit").contains("%1\$d"))
        assertTrue(english.getValue("host_key_dialog_time_limit").contains("%1\$d"))
    }

    @Test
    fun `a server that could not be confirmed is not described as a key that changed`() {
        // The message of the real refusal talks about a failed verification; the others say what to do.
        listOf("auth_error_host_key_no_screen", "auth_error_host_key_timeout").forEach { name ->
            listOf(french, english).forEach { strings ->
                val text = strings.getValue(name)
                assertFalse(text.contains("modifi", ignoreCase = true) || text.contains("changed", ignoreCase = true), text)
            }
        }
    }

    @Test
    fun `an identity not confirmed in time says what to do, for a tunnel and for a terminal alike`() {
        // The message reaches the card of a tunnel and the screen of a terminal connection: it names
        // neither, and says to try again.
        val fr = french.getValue("auth_error_host_key_timeout")
        val en = english.getValue("auth_error_host_key_timeout")
        assertTrue(fr.contains("Réessayez"), fr)
        assertTrue(en.contains("Try again"), en)
        assertFalse(fr.contains("tunnel", ignoreCase = true) || fr.contains("terminal", ignoreCase = true), fr)
        assertFalse(en.contains("tunnel", ignoreCase = true) || en.contains("terminal", ignoreCase = true), en)
    }

    @Test
    fun `the notification fits a tunnel that waits for a key, a finger or the confirmation of a server`() {
        // It used to say the tunnel waited for the user to authenticate: not the case of a server whose
        // identity is to be confirmed.
        listOf("auth_required_title", "auth_required_text_single", "auth_required_public_text").forEach { name ->
            assertFalse(Regex("authenti", RegexOption.IGNORE_CASE).containsMatchIn(french.getValue(name)), "$name (fr): ${french.getValue(name)}")
            assertFalse(Regex("authenti", RegexOption.IGNORE_CASE).containsMatchIn(english.getValue(name)), "$name (en): ${english.getValue(name)}")
        }
        // The channel says which cases it covers.
        assertTrue(french.getValue("auth_required_channel_description").contains("serveur"))
        assertTrue(english.getValue("auth_required_channel_description").contains("server"))
    }

    @Test
    fun `the reasons of a failed start or stop carry no format argument, no port and no identifier`() {
        // They are mapped from a fixed text with no argument (see tunnelMessageRes): a placeholder
        // would be shown as it is.
        listOf(
            "tunnel_error_start_failed", "tunnel_error_port_in_use", "tunnel_error_session_not_found",
            "tunnel_error_host_not_found", "tunnel_error_connect_unexpected", "tunnel_error_vault_auth_expired",
            "tunnel_error_not_found", "tunnel_error_stop_failed",
            "tunnel_error_local_port_invalid", "tunnel_error_remote_port_invalid",
            "tunnel_error_local_port_reserved", "tunnel_error_remote_forward_refused",
            "tunnel_error_start_stopped",
        ).forEach { name ->
            assertTrue(placeholders(french.getValue(name)).isEmpty(), "$name (fr): ${french.getValue(name)}")
            assertTrue(placeholders(english.getValue(name)).isEmpty(), "$name (en): ${english.getValue(name)}")
        }
    }

    @Test
    fun `the texts of a port that cannot be used and of a remote forward that the server refuses say what to do`() {
        assertEquals(
            "Le port local de ce tunnel n\\'est pas valide. Choisissez un port entre 1 et 65535.",
            french.getValue("tunnel_error_local_port_invalid"),
        )
        assertEquals(
            "Le port distant de ce tunnel n\\'est pas valide. Choisissez un port entre 1 et 65535.",
            french.getValue("tunnel_error_remote_port_invalid"),
        )
        assertEquals(
            "Android interdit aux applications les ports locaux sous 1024. Choisissez un port entre 1024 et 65535.",
            french.getValue("tunnel_error_local_port_reserved"),
        )
        assertEquals(
            "Le serveur a refusé d\\'ouvrir le port distant. Il est peut-être déjà pris sur le serveur, réservé " +
                "(ports sous 1024), ou les redirections y sont désactivées.",
            french.getValue("tunnel_error_remote_forward_refused"),
        )
        assertEquals(
            "The local port of this tunnel is not valid. Choose a port between 1 and 65535.",
            english.getValue("tunnel_error_local_port_invalid"),
        )
        assertEquals(
            "The remote port of this tunnel is not valid. Choose a port between 1 and 65535.",
            english.getValue("tunnel_error_remote_port_invalid"),
        )
        assertEquals(
            "Android does not let apps use local ports below 1024. Choose a port between 1024 and 65535.",
            english.getValue("tunnel_error_local_port_reserved"),
        )
        assertEquals(
            "The server refused to open the remote port. It may already be taken on the server, reserved " +
                "(ports below 1024), or forwarding may be turned off there.",
            english.getValue("tunnel_error_remote_forward_refused"),
        )
    }

    @Test
    fun `the quick cuts message tells the user what to do next`() {
        assertEquals(
            "Le tunnel se coupe dès qu\\'il est rétabli. Vérifiez le serveur, puis relancez-le.",
            french.getValue("tunnel_error_quick_cuts"),
        )
        assertEquals(
            "The tunnel drops again as soon as it is restored. Check the server, then restart it.",
            english.getValue("tunnel_error_quick_cuts"),
        )
    }
}
