// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Source-level guard of the PIN of the FIDO2 screens. A Compose screen cannot be run in a unit
 * test, so this reads its source: the PIN is typed into the masked char buffer of `MaskedPinField`,
 * never into a String, and nothing of it goes to a Bundle (`rememberSaveable`). The comments are
 * blanked first, so that a comment that explains the rule is not taken for a break of it.
 *
 * The rules are about the state that holds the PIN, not about the whole file: the screen may save
 * its other fields (the name, the rpId, the advanced options) the day it needs to, and the guard
 * tells the PIN's buffer by the field it feeds, whatever it is called.
 *
 * Same compromise as `LogHygieneGuardTest`: a cheap source check. A source that cannot be found
 * FAILS the test (see [ProjectFiles]).
 */
class Fido2EnrollScreenPinGuardTest {

    private fun source(relativeToPackage: String): String =
        blankComments(ProjectFiles.mainSource(relativeToPackage).readText())

    private val screen get() = source("ui/vault/Fido2EnrollScreen.kt")

    /** The name of the state the screen gives `MaskedPinField` to hold the PIN: the field it feeds says which. */
    private fun pinBuffer(code: String): String {
        val fed = Regex("""MaskedPinField\(\s*pinChars\s*=\s*(\w+)""").find(code)
        assertNotNull(fed, "the PIN field is the masked one, and it is fed a named buffer")
        return fed!!.groupValues[1]
    }

    /** Whether a name says it holds a secret: a word of its camel case or snake case is one of these. */
    private fun isSecretName(name: String): Boolean =
        name.split(Regex("""(?<=[a-z0-9])(?=[A-Z])|_""")).any {
            it.lowercase() in setOf("pin", "secret", "password", "passphrase", "credential")
        }

    /** The code of [code] without its import lines, which name `rememberSaveable` without using it. */
    private fun withoutImports(code: String) =
        code.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")

    @Test
    fun `the enrollment screen types the PIN into the masked buffer`() {
        val code = screen
        val buffer = pinBuffer(code)

        // A buffer of chars, remembered but not saved.
        assertTrue(
            Regex("""\b(?:val|var)\s+$buffer\b[^=\n]*=\s*remember\s*\{\s*mutableStateListOf\(\)\s*\}""").containsMatchIn(code),
            "$buffer is a remember { mutableStateListOf() }",
        )
        assertTrue(
            Regex("""isFidoPinAcceptable\(\s*$buffer\s*\)""").containsMatchIn(code),
            "a PIN is let through only when a key takes it",
        )
    }

    @Test
    fun `the enrollment screen never holds the PIN in a String or in a Bundle`() {
        val code = withoutImports(screen)
        val buffer = pinBuffer(code)

        // Nothing of the PIN is saved: a Bundle outlives the screen. The other fields may be.
        val saved = Regex("""\b(?:val|var)\s+(\w+)\b[^=\n]*?(?:=|\bby\b)\s*rememberSaveable""")
            .findAll(code).map { it.groupValues[1] }.toList()
        val uses = Regex("""\brememberSaveable\b""").findAll(code).count()
        assertEquals(uses, saved.size, "every rememberSaveable declares a named state, so that the guard can read what it saves")
        assertFalse(buffer in saved, "the PIN buffer $buffer is saved to a Bundle")
        assertEquals(emptyList<String>(), saved.filter(::isSecretName), "a state that is named for a secret is saved to a Bundle")

        // No String state for the PIN, under whatever name: a String can only be typed into a plain
        // text field, and the plain text fields are the name's and the rpId's. A new one must be
        // checked not to take the PIN, and be added here.
        val plainFields = Regex("""\b(?:OutlinedTextField|TextField|BasicTextField)\(\s*value\s*=\s*(\w+)""")
            .findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("labelText", "rpIdText"), plainFields, "the plain text fields of the screen")
        assertEquals(emptyList<String>(), plainFields.filter(::isSecretName), "a plain text field is named for a secret")

        assertFalse(code.contains("PasswordVisualTransformation"), "the masked field has no String to mask")
        assertFalse(code.contains("toCharArray()"), "the PIN is never a String that is turned into chars")
        assertFalse(Regex("""\bpinText\b""").containsMatchIn(code), "no String state for the PIN")
    }

    @Test
    fun `the enrollment screen empties the buffer when the PIN is handed over and when it is left`() {
        val code = screen
        val buffer = pinBuffer(code)

        // Emptied when the screen leaves composition, whatever the way out.
        assertTrue(
            Regex("""DisposableEffect\([^)]*\)\s*\{\s*onDispose\s*\{\s*$buffer\.clear\(\)""").containsMatchIn(code),
            "the buffer is emptied when the screen leaves composition",
        )

        // Copied once into the array the ViewModel owns, emptied, and only then handed over.
        val copied = code.indexOf("CharArray($buffer.size")
        val emptied = code.indexOf("$buffer.clear()", startIndex = maxOf(copied, 0))
        val handedOver = code.indexOf("startEnroll(", startIndex = maxOf(copied, 0))
        assertTrue(copied >= 0, "the PIN is copied once from the buffer into an array")
        assertTrue(emptied > copied, "the buffer is emptied after the copy")
        assertTrue(handedOver > emptied, "the buffer is already empty when the ViewModel is handed the PIN")
    }

    @Test
    fun `the masked field is a password for accessibility services, so TalkBack does not read it`() {
        val code = source("ui/components/MaskedPinField.kt")

        assertTrue(
            Regex("""modifier\s*=\s*modifier\.semantics\s*\{\s*password\(\)\s*\}""").containsMatchIn(code),
            "the text field carries the password semantics, on the modifier the caller gave it",
        )
        assertFalse(code.contains("PasswordVisualTransformation"), "the field has no String to transform")
    }

    @Test
    fun `the sign-in sheet shares the same masked field`() {
        val code = source("ui/sessions/Fido2ChallengeBottomSheet.kt")

        assertTrue(code.contains("MaskedPinField("))
        assertFalse(code.contains("BasicTextField("), "a second copy of the masked sink would drift from the first")
    }
}
