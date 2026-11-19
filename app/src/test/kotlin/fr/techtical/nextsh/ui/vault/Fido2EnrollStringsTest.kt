// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.EnrollFailure
import fr.techtical.nextsh.testutil.FrenchTuForm
import fr.techtical.nextsh.testutil.ProjectFiles
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the user reads while enrolling a security key comes from resources, in French and English:
 * the enrollment manager has no language, the screen turns each reason into one of these strings.
 */
class Fido2EnrollStringsTest {

    private val french = strings("values/strings.xml")
    private val english = strings("values-en/strings.xml")

    /** Every string of a resource file, name to raw text. */
    private fun strings(path: String): Map<String, String> {
        val file = ProjectFiles.appFile("src/main/res/$path")
        return Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** The strings of the enrollment of a security key, and of the one of a Desktop device (its QR scan). */
    private fun isEnrollmentString(name: String) =
        name.startsWith("fido2_") || name.startsWith("enrollment_") ||
            name in setOf("title_fido2_enroll", "vault_action_enroll_fido2", "title_enrollment_scan")

    /** The source of a file of the app. */
    private fun mainSource(relativeToPackage: String): String = ProjectFiles.mainSource(relativeToPackage).readText()

    /**
     * Every string the two enrollment screens (the security key and the QR scan of a Desktop device)
     * show, shared ones such as the Back or Retry buttons included: read from what the screens refer to.
     */
    private val shownByEnrollmentScreens: Set<String> by lazy {
        listOf("ui/vault/Fido2EnrollScreen.kt", "ui/sync/EnrollmentScanScreen.kt")
            .flatMap { Regex("""R\.string\.([a-z0-9_]+)""").findAll(mainSource(it)).map { m -> m.groupValues[1] }.toList() }
            .toSet()
    }

    /** What a user of the enrollment reads: the strings of its own, and every one its screens show. */
    private fun isShownInEnrollment(name: String) = isEnrollmentString(name) || name in shownByEnrollmentScreens

    /** The name of a string resource, from its id: unit tests have no Resources to ask. */
    private fun nameOf(@StringRes id: Int): String =
        R.string::class.java.fields.first { it.type == Int::class.javaPrimitiveType && it.getInt(null) == id }.name

    private fun placeholders(text: String) = Regex("""%\d\$[sd]""").findAll(text).map { it.value }.toList()

    /** One of each reason, and a refused PIN with and without a count: what the screen can be told. */
    private val failures = listOf(
        EnrollFailure.NoKeyInTime,
        EnrollFailure.PinRequired,
        EnrollFailure.PinIncorrect(triesRemaining = null),
        EnrollFailure.PinIncorrect(triesRemaining = 4),
        EnrollFailure.PinNotSet,
        EnrollFailure.PinBlocked,
        EnrollFailure.PinAuthBlocked,
        EnrollFailure.UnsupportedKey,
        EnrollFailure.KeyRemoved,
        EnrollFailure.TouchTimeout,
        EnrollFailure.Refused,
        EnrollFailure.NameMissing,
        EnrollFailure.NotSaved,
        EnrollFailure.Unexpected,
    )

    @Test
    fun `every enrollment string exists in French and in English`() {
        val fr = french.keys.filter(::isEnrollmentString).toSet()
        val en = english.keys.filter(::isEnrollmentString).toSet()

        assertTrue(fr.isNotEmpty())
        assertEquals(emptySet<String>(), fr - en, "in French only")
        assertEquals(emptySet<String>(), en - fr, "in English only")
    }

    @Test
    fun `every reason has a sentence in both languages that takes the arguments it is given`() {
        failures.forEach { failure ->
            val name = nameOf(enrollFailureMessageRes(failure))
            val args = enrollFailureMessageArgs(failure).size

            assertTrue(name in french, "$failure has no French sentence ($name)")
            assertTrue(name in english, "$failure has no English sentence ($name)")
            assertEquals(args, placeholders(french.getValue(name)).size, "arguments of $name in French for $failure")
            assertEquals(args, placeholders(english.getValue(name)).size, "arguments of $name in English for $failure")
        }
    }

    @Test
    fun `two reasons that call for different things do not say the same sentence`() {
        val ids = failures.map { enrollFailureMessageRes(it) }

        assertEquals(ids.size, ids.toSet().size, "a reason shares its sentence with another")
    }

    @Test
    fun `the sentences of a failure are written for the user, not for a developer`() {
        val technical = Regex("""CTAP|0x[0-9A-Fa-f]|[Ee]xception|rpId|[Ee]rror code""")
        failures.forEach { failure ->
            val name = nameOf(enrollFailureMessageRes(failure))
            listOf("fr" to french, "en" to english).forEach { (language, strings) ->
                val text = strings.getValue(name)
                assertFalse(text.isBlank(), "$name ($language) is empty")
                assertFalse(technical.containsMatchIn(text), "$name ($language) is technical: $text")
            }
        }
    }

    /**
     * The imperatives of the "tu" form that a text of the enrollment could use at the start of a
     * sentence (a line start, a "\n" of the resource, or after a full stop), and the ones that sit in
     * the middle of one, after a colon or a comma (reset, spelled with or without a hyphen). The
     * pronouns and the possessives are [FrenchTuForm]'s: this list stays here because the same words
     * are the third person of a help text elsewhere in the app.
     */
    private val imperative = Regex(
        """(^|\\n|[.:;!?]\s)(Aligne|Ouvre|Vérifie|Assigne|Saisis|Choisis|Entre|Appuie|Clique|Utilise|Active|""" +
            """Sélectionne|Scanne|Branche|Approche|Retire|Réessaie|Essaie|Touche|Garde|Donne|Pose|Copie|Colle|""" +
            """Autorise|Accorde|Compare|Confirme|Attends|Patiente|Ajoute|Crée|Génère|Importe|Exporte|Tape|Lance|""" +
            """Ferme|Relance|Laisse|Maintiens|Veille)(?![\p{L}])""" +
            """|(?<![\p{L}'-])(?i:ré-?initialise)(?![\p{L}])""",
    )

    /** The first word of the "tu" form in [text]: a pronoun or a possessive, else an imperative. */
    private fun tuForm(text: String): String? = FrenchTuForm.find(text) ?: imperative.find(text)?.value

    @Test
    fun `the check for the tu form finds what it is meant to find`() {
        // Raw texts, as the resource file holds them: the strings of before the fix.
        listOf(
            "La clé « %1${'$'}s » a été ajoutée à ton vault.\\nAssigne-la à un hôte SSH pour l\\'utiliser.",
            "Aligne le QR dans le cadre",
            "Permission requise.\\nOuvre les paramètres de l\\'application pour l\\'accorder.",
            "Vérifie que cette empreinte correspond à celle affichée sur ton PC.",
            "Tu peux réessayer",
            "Merci de t\\'identifier",
            "Empreinte ne correspond pas.\\n\\nPossible MITM : ré-initialise l\\'enrôlement côté Desktop.",
            "Possible MITM : réinitialise l\\'enrôlement côté Desktop.",
            "Réinitialise le vault.",
        ).forEach { assertTrue(tuForm(it) != null, "not found in: $it") }
        listOf(
            "Alignez le QR dans le cadre",
            "Assignez-la à un hôte SSH pour l\\'utiliser.",
            "Une clé de sécurité (YubiKey ou équivalente) est protégée par un PIN.",
            "Possible MITM : réinitialisez l\\'enrôlement côté Desktop.",
            "Possible MITM : ré-initialisez l\\'enrôlement côté Desktop.",
            "Une réinitialisation complète est nécessaire.",
        ).forEach { assertFalse(tuForm(it) != null, "found in: $it") }
    }

    @Test
    fun `the French strings the enrollment screens show speak to the user with vous`() {
        // Its own strings (the fido2_ and enrollment_ ones) and every one its two screens refer to,
        // the QR scan screen's error and the shared buttons included.
        val shown = french.filterKeys(::isShownInEnrollment)

        assertTrue("error_fingerprint_mismatch" in shown, "the fingerprint mismatch is shown by the QR scan")
        assertTrue("action_back" in shown, "the buttons the screens share are read too")
        shown.forEach { (name, text) ->
            val found = tuForm(text)
            assertTrue(found == null, "$name uses the tu form (\"${found?.trim()}\"): $text")
        }
    }

    @Test
    fun `the success message and the scan hint use vous`() {
        assertEquals(
            "La clé « %1\$s » a été ajoutée à votre Vault.\\nAssignez-la à un hôte SSH pour l\\'utiliser.",
            french.getValue("fido2_enroll_success_message"),
        )
        assertEquals("Alignez le QR dans le cadre", french.getValue("enrollment_scan_hint"))
    }

    @Test
    fun `the introduction does not promise Ed25519 alone, the key may only do ECDSA P-256`() {
        // Fido2EnrollManager asks for Ed25519 first and falls back on ECDSA P-256: the text says both.
        listOf(french, english).forEach { strings ->
            val intro = strings.getValue("fido2_enroll_intro")
            assertTrue(intro.contains("Ed25519") && intro.contains("ECDSA P-256"), intro)
            assertFalse(intro.contains("sk-ssh-ed25519"), "the name of the key type is for the key, not the promise: $intro")
        }
    }

    @Test
    fun `the placeholder of the name of the key is a resource that promises no algorithm`() {
        val fr = french.getValue("fido2_enroll_label_placeholder")
        val en = english.getValue("fido2_enroll_label_placeholder")

        assertEquals("Ma clé de sécurité", fr)
        assertEquals("My security key", en)
        assertFalse(fr.contains("Ed25519") || en.contains("Ed25519"), "$fr / $en")
    }

    @Test
    fun `the hint under the PIN field says why the button stays grey below 4 characters`() {
        // The field is optional, but a PIN that is typed must have 4 characters at least: the button
        // waits for it (isFidoPinAcceptable), and says nothing else about it.
        val fr = french.getValue("fido2_pin_optional_hint")
        val en = english.getValue("fido2_pin_optional_hint")

        assertTrue(fr.contains("Laissez vide") && fr.contains("au moins 4 caractères"), fr)
        assertTrue(en.contains("Leave blank") && en.contains("at least 4 characters"), en)
    }

    @Test
    fun `the title of the wait for the key says a security key, not a brand`() {
        // Any FIDO2 key can be enrolled, as the rest of the enrollment says.
        assertEquals("Branchez ou approchez votre clé de sécurité", french.getValue("fido2_waiting_key_title"))
        assertEquals("Plug in or tap your security key", english.getValue("fido2_waiting_key_title"))
        listOf(french, english).forEach { strings ->
            listOf("fido2_waiting_key_title", "fido2_waiting_key_hint").forEach { name ->
                assertFalse(strings.getValue(name).contains("YubiKey"), "$name names a brand")
            }
        }
    }

    @Test
    fun `a PIN typed for a key that has none is told to leave the field blank`() {
        val fr = french.getValue(nameOf(enrollFailureMessageRes(EnrollFailure.PinNotSet)))
        val en = english.getValue(nameOf(enrollFailureMessageRes(EnrollFailure.PinNotSet)))

        assertTrue(fr.contains("n\\'a pas de PIN") && fr.contains("Laissez le champ PIN vide"), fr)
        assertTrue(en.contains("has no PIN") && en.contains("Leave the PIN field blank"), en)
    }

    @Test
    fun `the enrollment strings hold no em dash or en dash`() {
        listOf("fr" to french, "en" to english).forEach { (language, strings) ->
            strings.filterKeys(::isEnrollmentString).forEach { (name, text) ->
                assertFalse(text.any { it.code == 0x2014 || it.code == 0x2013 }, "$name ($language) holds a dash")
            }
        }
    }

    @Test
    fun `the English strings of the enrollment are in American spelling`() {
        val british = listOf("recognis", "authoris", "colour", "behaviour", "centre", "favour", "organis", "initialis")
        english.filterKeys(::isEnrollmentString).forEach { (name, text) ->
            british.forEach { word ->
                assertFalse(text.contains(word, ignoreCase = true), "$name uses the British \"$word\": $text")
            }
        }
    }
}
