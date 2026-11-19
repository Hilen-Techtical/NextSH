// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `rememberSaveable` puts the state in the Bundle of the activity, which outlives the screen and
 * leaves the process. A secret never goes there, and a typed text only through a bounded saver
 * (a huge pasted text would make the saved state of the activity fail, a crash). The guard reads
 * the sources of `ui/`, comments removed.
 */
class SavedStateSecretGuardTest {

    private val sources = SourceGuardSupport.mainSources("ui")

    // `var x by rememberSaveable`, `val x = rememberSaveable`, with or without a declared type.
    private val savedName = Regex("""(\w+)\s*(?::[^=\n]*?)?\s*(?:\bby\b|=)\s*rememberSaveable""")
    private val shortSecretName = Regex("""(^pin|Pin|^otp|Otp|^pwd|Pwd)([A-Z]|$)""")

    private fun isSecretName(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("passphrase", "password", "passwd", "secret", "token", "credential", "pem", "privatekey")
            .any { lower.contains(it) } || shortSecretName.containsMatchIn(name)
    }

    @Test
    fun `the secret name rule recognises the usual names`() {
        for (name in listOf("passphrase", "passwordVisible", "privateKeyPem", "pin", "pinInput", "yubiKeyPin", "apiToken", "pwd", "passwd", "userCredential", "otp", "otpCode")) {
            assertTrue(isSecretName(name), name)
        }
        for (name in listOf("label", "command", "showGenerateDialog", "pinned", "selectedType", "footprint", "adopt")) {
            assertFalse(isSecretName(name), name)
        }
    }

    @Test
    fun `every form of declaration of a saved state is seen`() {
        val declarations = listOf(
            "var a by rememberSaveable { mutableStateOf(1) }" to "a",
            "val b = rememberSaveable { mutableStateOf(1) }" to "b",
            "var c: String by rememberSaveable { mutableStateOf(\"\") }" to "c",
            "val d: MutableState<Int> = rememberSaveable { mutableStateOf(1) }" to "d",
            "var e by rememberSaveable(file.path) { mutableIntStateOf(1) }" to "e",
        )
        for ((line, name) in declarations) {
            assertEquals(name, savedName.find(line)?.groupValues?.get(1), line)
        }
    }

    @Test
    fun `no secret is held by rememberSaveable`() {
        val offences = sources.flatMap { (path, code) ->
            savedName.findAll(code).map { it.groupValues[1] }.filter(::isSecretName).map { "$path: $it" }.toList()
        }

        assertEquals(emptyList<String>(), offences, "a secret in the Bundle")
    }

    @Test
    fun `the secrets of the forms stay in remember`() {
        val mustStayRemember = mapOf(
            "ui/vault/VaultScreen.kt" to listOf("passphrase", "confirmation", "showPassphrase", "showConfirmation"),
            "ui/vault/KeyImportScreen.kt" to listOf("privateKeyPem", "passphraseInput"),
            "ui/hosts/HostDetailScreen.kt" to listOf("passwordVisible"),
            "ui/browser/HttpAuthDialog.kt" to listOf("username", "password", "passwordVisible"),
        )
        for ((path, names) in mustStayRemember) {
            val code = sources.getValue(path)
            for (name in names) {
                val declaration = Regex("""va[lr]\s+$name\s+by\s+(\w+)""").find(code)
                assertNotNull(declaration, "$path: $name is listed as staying in remember but is gone, update the list")
                assertEquals("remember", declaration!!.groupValues[1], "$path: $name is a secret, or tied to one")
            }
        }
    }

    @Test
    fun `a typed text held by rememberSaveable goes through a bounded saver`() {
        for ((path, code) in sources) {
            for (call in SourceGuardSupport.calls(code, "rememberSaveable")) {
                val holdsText = call.contains("mutableStateOf(\"\")") || call.contains("?: \"\")") ||
                    call.contains("mutableStateOf(file.name)")
                if (holdsText) {
                    assertTrue(call.contains("boundedTextSaver("), "$path: a typed text without a bound")
                }
            }
        }
    }

    @Test
    fun `the states of the lot are saved, the destructive confirmations are not`() {
        fun code(path: String) = sources.getValue(path)

        val sftp = code("ui/sftp/SftpBrowserScreen.kt")
        assertTrue(sftp.contains("rememberSaveable(file.path, stateSaver = boundedTextSaver("), "rename, keyed by path")
        assertTrue(sftp.contains("var bits by rememberSaveable(file.path)"), "chmod, one Int")
        assertEquals(
            3,
            Regex("""boundedTextSaver\(SavedTextLimits\.SFTP_NAME_BYTES\)""").findAll(sftp).count(),
            "mkdir, new file, rename",
        )

        val snippets = code("ui/snippets/SnippetListScreen.kt")
        assertTrue(snippets.contains("rememberSaveable(snippet?.id, stateSaver"), "snippet sheet, keyed by id")
        assertTrue(snippets.contains("var showDeleteDialog by remember {"), "destructive confirmation stays in remember")

        val terminal = code("ui/sessions/TerminalScreen.kt")
        for (name in listOf("showHostPicker", "showSnippetPicker", "showSplitPicker")) {
            assertTrue(terminal.contains("var $name by rememberSaveable"), name)
        }
        assertFalse(terminal.contains("snippetToConfirm by remember"), "the confirmation is held by the ViewModel")
        assertTrue(terminal.contains("viewModel.pendingSnippet"), "read from the ViewModel")

        val settings = code("ui/settings/SettingsScreen.kt")
        assertTrue(settings.contains("var showAboutLinkDialog by rememberSaveable"))
        assertTrue(settings.contains("var showWipeDialog by remember {"))
        assertTrue(code("ui/vault/VaultScreen.kt").contains("var showGenerateDialog by rememberSaveable"))
    }
}
