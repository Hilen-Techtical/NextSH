// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level guard for the keyboard of the secret fields of the `app` module (same compromise as
 * `HostDetailPasswordFieldGuardTest`: no `androidTest` source set, so the source is read, comments
 * stripped).
 *
 * A masked field (`PasswordVisualTransformation()`) must also ask for the password keyboard and
 * turn autocorrect off, otherwise the keyboard offers suggestions and can learn the secret. Every
 * Kotlin file under `ui/` is swept, so a new masked field is checked without touching this guard;
 * the per-file floors only protect against a field that moved or was renamed (the guard would pass
 * on nothing). The pasted private key is not masked (a multi-line PEM cannot be) and keeps its
 * multi-line keyboard, but must not be autocorrected either.
 *
 * `app/build.gradle.kts` declares the `ui/` directory and both `strings.xml` as inputs of the Test
 * task, so that a change to these files reruns the guard despite the Gradle build cache.
 */
class SecretFieldsKeyboardGuardTest {

    private val fieldComposables =
        listOf("NextShTextField", "OutlinedTextField", "TextField", "BasicTextField", "FormField")

    private val passwordKeyboard = Regex("""keyboardType\s*=\s*KeyboardType\.Password\b""")

    /** `autoCorrect` (Compose 1.6) or `autoCorrectEnabled` (Compose 1.7 and later). */
    private val autoCorrectOff = Regex("""autoCorrect(Enabled)?\s*=\s*false\b""")

    /** Kotlin files under `ui/`, comments removed, keyed by path relative to `fr/techtical/nextsh/`. */
    private val uiSources: Map<String, String> by lazy { SourceGuardSupport.mainSources("ui") }

    private fun source(path: String): String =
        uiSources[path] ?: throw AssertionError("$path not found under ui/: the guard cannot check it")

    private fun fieldCalls(source: String): List<String> =
        fieldComposables.flatMap { SourceGuardSupport.calls(source, it) }

    private fun maskedCalls(source: String): List<String> =
        fieldCalls(source).filter { it.contains("PasswordVisualTransformation()") }

    private fun assertPasswordKeyboard(path: String, call: String) {
        val head = call.lineSequence().take(3).joinToString(" ").trim()
        assertTrue(
            passwordKeyboard.containsMatchIn(call),
            "$path: a masked field must use KeyboardType.Password (a normal text keyboard can " +
                "learn the secret): $head",
        )
        assertTrue(
            autoCorrectOff.containsMatchIn(call),
            "$path: a masked field must set autoCorrect = false: $head",
        )
    }

    /** The masked fields of [path] must use the password keyboard, with at least [atLeast] of them. */
    private fun assertMaskedFieldsUsePasswordKeyboard(path: String, atLeast: Int) {
        val masked = maskedCalls(source(path))
        assertTrue(
            masked.size >= atLeast,
            "$path: expected at least $atLeast masked secret field(s), found ${masked.size}; if " +
                "they moved or were renamed, update this guard so they are still checked",
        )
        masked.forEach { assertPasswordKeyboard(path, it) }
    }

    @Test
    fun `every masked field under ui uses the password keyboard without autocorrect`() {
        var found = 0
        uiSources.forEach { (path, source) ->
            maskedCalls(source).forEach { call ->
                found++
                assertPasswordKeyboard(path, call)
            }
        }
        // 1 host password, 1 imported key passphrase, 2 backup passphrases, 1 HTTP auth password,
        // 1 FIDO2 enrolment PIN.
        assertTrue(
            found >= 6,
            "expected at least 6 masked secret fields under ui/, found $found; if some moved or " +
                "were renamed, update this guard so they are still checked",
        )
    }

    @Test
    fun `imported key passphrase uses the password keyboard without autocorrect`() {
        assertMaskedFieldsUsePasswordKeyboard("ui/vault/KeyImportScreen.kt", atLeast = 1)
    }

    @Test
    fun `backup passphrase and its confirmation use the password keyboard without autocorrect`() {
        assertMaskedFieldsUsePasswordKeyboard("ui/vault/VaultScreen.kt", atLeast = 2)
    }

    @Test
    fun `HTTP auth password uses the password keyboard without autocorrect`() {
        assertMaskedFieldsUsePasswordKeyboard("ui/browser/HttpAuthDialog.kt", atLeast = 1)
    }

    @Test
    fun `host password uses the password keyboard without autocorrect`() {
        assertMaskedFieldsUsePasswordKeyboard("ui/hosts/HostDetailScreen.kt", atLeast = 1)
    }

    @Test
    fun `FIDO2 PIN uses the password keyboard without autocorrect`() {
        assertMaskedFieldsUsePasswordKeyboard("ui/vault/Fido2EnrollScreen.kt", atLeast = 1)
    }

    @Test
    fun `FIDO2 challenge PIN field uses a password keyboard type`() {
        // This PIN field masks its value by hand (bullets, the PIN never assembled into a String),
        // so it has no PasswordVisualTransformation: it still must not get a plain text keyboard.
        val path = "ui/sessions/Fido2ChallengeBottomSheet.kt"
        val calls = SourceGuardSupport.calls(source(path), "BasicTextField")
        assertTrue(
            calls.isNotEmpty(),
            "$path: the BasicTextField of the PIN is gone; if it moved, update this guard",
        )
        calls.forEach { call ->
            assertTrue(
                Regex("""keyboardType\s*=\s*KeyboardType\.(NumberPassword|Password)\b""").containsMatchIn(call),
                "$path: the PIN field must use KeyboardType.NumberPassword or KeyboardType.Password",
            )
        }
    }

    @Test
    fun `pasted private key is not autocorrected but keeps its multi-line keyboard and display`() {
        val pem = SourceGuardSupport.calls(source("ui/vault/KeyImportScreen.kt"), "FormField")
            .filter { Regex("""value\s*=\s*privateKeyPem\b""").containsMatchIn(it) }
        assertTrue(
            pem.size == 1,
            "KeyImportScreen.kt: expected exactly one FormField bound to privateKeyPem, found ${pem.size}",
        )
        val call = pem.single()
        assertTrue(
            Regex("""KeyboardOptions\(\s*autoCorrect(Enabled)?\s*=\s*false\s*\)""").containsMatchIn(call),
            "the pasted private key field must use KeyboardOptions(autoCorrect = false)",
        )
        assertFalse(
            call.contains("KeyboardType."),
            "the pasted private key field is multi-line: it must keep the default keyboard type",
        )
        assertFalse(
            call.contains("PasswordVisualTransformation"),
            "the pasted private key field must not be masked (a multi-line PEM cannot be)",
        )
    }

    @Test
    fun `key import FormField forwards its keyboard options to the text field`() {
        val source = source("ui/vault/KeyImportScreen.kt")
        assertTrue(
            Regex("""keyboardOptions\s*:\s*KeyboardOptions\s*=\s*KeyboardOptions\.Default""").containsMatchIn(source),
            "FormField must expose `keyboardOptions: KeyboardOptions = KeyboardOptions.Default` so " +
                "the other callers keep the default keyboard",
        )
        assertTrue(
            SourceGuardSupport.calls(source, "OutlinedTextField")
                .any { Regex("""keyboardOptions\s*=\s*keyboardOptions\b""").containsMatchIn(it) },
            "FormField must pass its keyboardOptions to the OutlinedTextField it wraps",
        )
    }
}
