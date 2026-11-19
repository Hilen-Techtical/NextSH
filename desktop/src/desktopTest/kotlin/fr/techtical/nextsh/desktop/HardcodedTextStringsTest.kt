// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The texts that replaced the hard-coded ones exist in French and in English, with the same
 * positional placeholders, and the English file really is English where it matters (units,
 * "OK", the Windows Hello wait).
 */
class HardcodedTextStringsTest {

    private fun resourcesDir(): File {
        val candidates = listOf("src/desktopMain/composeResources", "desktop/src/desktopMain/composeResources")
        return candidates.map { File(it) }.firstOrNull { File(it, "values/strings.xml").isFile }
            ?: error("composeResources not found (tried $candidates)")
    }

    private val entry = Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    private fun texts(folder: String): Map<String, String> =
        entry.findAll(File(resourcesDir(), "$folder/strings.xml").readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val french by lazy { texts("values") }
    private val english by lazy { texts("values-en") }

    private val keysByPrefix = listOf(
        "action_ok", "file_size_", "host_key_dialog_", "sftp_error_preview_too_large_max",
        "browser_external_", "browser_action_", "browser_tunnel_disconnected", "browser_kcef_",
        "fido2_enroll_windows_waiting_", "fido2_enroll_error_", "fido2_enroll_btn_start_windows",
        "sync_error_", "known_hosts_subtitle_", "transfers_summary_", "enrolled_devices_error_",
        "host_detail_error_", "tunnel_config_error_", "vault_error_", "auth_error_fido2_transport", "auth_error_fido2_failed", "open_link_what_repository",
    )

    private val addedKeys by lazy {
        french.keys.filter { key -> keysByPrefix.any { key == it || (it.endsWith("_") && key.startsWith(it)) } }.sorted()
    }

    private val placeholder = Regex("%\\d\\$[sd]")

    @Test
    fun `every added key exists in both languages`() {
        assertTrue(addedKeys.size >= 80, "too few keys found: ${addedKeys.size}")
        val missing = addedKeys.filter { it !in english }
        assertTrue(missing.isEmpty(), "missing in values-en: $missing")
        // The other way round: an English key without French text would show an empty label.
        val orphans = english.keys.filter { key -> keysByPrefix.any { key == it || (it.endsWith("_") && key.startsWith(it)) } }
            .filter { it !in french }
        assertTrue(orphans.isEmpty(), "missing in values: $orphans")
    }

    @Test
    fun `the placeholders are positional and identical in both languages`() {
        for (key in addedKeys) {
            val fr = french.getValue(key)
            val en = english[key] ?: continue
            assertEquals(
                placeholder.findAll(fr).map { it.value }.sorted().toList(),
                placeholder.findAll(en).map { it.value }.sorted().toList(),
                "placeholders differ for $key",
            )
            assertTrue(!Regex("%(?!\\d\\$|%)[sd]").containsMatchIn(fr + en), "non positional placeholder in $key")
        }
    }

    @Test
    fun `the added texts carry no dash or backslash apostrophe`() {
        for (key in addedKeys) {
            for (text in listOfNotNull(french[key], english[key])) {
                assertTrue('\u2013' !in text && '\u2014' !in text, "dash in $key")
                assertTrue("\\'" !in text, "escaped apostrophe in $key")
            }
        }
    }

    @Test
    fun `the sizes use the unit of the language`() {
        assertEquals("%1\$s Ko", french["file_size_kb"])
        assertEquals("%1\$s Mo", french["file_size_mb"])
        assertEquals("%1\$s Go", french["file_size_gb"])
        assertEquals("%1\$s KB", english["file_size_kb"])
        assertEquals("%1\$s MB", english["file_size_mb"])
        assertEquals("%1\$s GB", english["file_size_gb"])
    }

    @Test
    fun `OK and the Windows Hello wait are translated`() {
        assertEquals("OK", french["action_ok"])
        assertEquals("OK", english["action_ok"])
        assertTrue(french.getValue("fido2_enroll_windows_waiting_title").contains("Windows Hello"))
        assertTrue(english.getValue("fido2_enroll_windows_waiting_title").startsWith("Waiting"))
    }

    @Test
    fun `the host key dialogs say the same thing in both languages`() {
        val fr = french.getValue("host_key_dialog_unknown_warning")
        val en = english.getValue("host_key_dialog_unknown_warning")
        assertTrue("MITM" in fr && "MITM" in en)
        assertTrue("SHA-256" in french.getValue("host_key_dialog_fingerprint_label"))
        assertTrue("SHA-256" in english.getValue("host_key_dialog_fingerprint_label"))
        assertTrue("Known hosts" in english.getValue("host_key_dialog_mismatch_hint"))
        assertTrue("Hôtes connus" in french.getValue("host_key_dialog_mismatch_hint"))
    }

    @Test
    fun `the chmod labels used by the permissions dialog exist in both languages`() {
        for (key in listOf("owner", "group", "others", "read", "write", "execute")) {
            assertTrue("sftp_dialog_chmod_$key" in french && "sftp_dialog_chmod_$key" in english, key)
        }
    }
}
