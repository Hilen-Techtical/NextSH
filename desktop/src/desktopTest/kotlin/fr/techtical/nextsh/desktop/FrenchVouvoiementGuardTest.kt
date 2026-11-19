// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guard against the informal address (tutoiement) coming back into the French texts of the Desktop.
 *
 * The app speaks to the user with "vous". Labels are written in the infinitive ("Confirmer le PIN"),
 * sentences in the "vous" imperative ("Réessayez."). The French resource file is read as XML, from the
 * source tree, and two lexical checks run on every value:
 *
 *  1. [pronoun]: no informal pronoun or possessive (tu, te, toi, ton, ta, tes) and no elided "t'"
 *     ("t'authentifier", also written "t\'" in the file).
 *  2. [imperative]: no clause opening with the informal imperative of a verb the app has used
 *     ("Crée", "Réessaie", "Touche"...). A clause opens at the start of the value, after a
 *     full stop, "!", "?", ":", ";", a comma or an ellipsis, after a written "\n", after a "(a) "
 *     marker, or after "puis", "et" or "ou" ("Vérifiez le format puis réessaie").
 *
 * The imperative check works on a list of verbs: a new verb of the app must be added to it. The
 * scope is the resource file only: the texts written in Kotlin sources are checked by hand.
 *
 * The flag "(?U)" (Unicode character classes) is required: without it (JDK 19 and later), an
 * accented letter is not a word character, so a word boundary is found between "ô" and "t", and
 * "tes" is found inside "hôtes".
 */
class FrenchVouvoiementGuardTest {

    private companion object {
        val pronoun = Regex(
            """(?U)\b(?:tu|te|toi|ton|ta|tes)\b|\bt\\?['’]""",
            RegexOption.IGNORE_CASE,
        )

        // A verb opening a clause: value start, after . ! ? : ; , or an ellipsis, after a written \n,
        // after "(a) ", after "puis", "et" or "ou".
        val imperative = Regex(
            """(?U)(?:^|[.!?:;…]\s+|,\s+|\\n\s*|\(\p{L}\)\s+|\b(?:puis|et|ou)\s+)""" +
                """(?:crée|ajoute|ajuste|affine|personnalise|génère|importe|touche|pose|tape|utilise|ferme|redémarre|""" +
                """débranche|enregistre|réessaie|vérifie|confirme|sélectionne|scanne|laisse|ouvre|choisis|saisis|clique|appuie|""" +
                """réessaye|essaie|essaye|patiente|branche|rebranche|approche)\b""",
            RegexOption.IGNORE_CASE,
        )

        // "Crée un proxy SOCKS5..." is a third person ("it creates"), not an order.
        val thirdPersonOpenings = setOf("tunnel_config_type_dynamic_desc")
    }

    // ── The resource file ─────────────────────────────────────────────────

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

    // ── The French texts ──────────────────────────────────────────────────

    @Test
    fun `the French file is read in full`() {
        // A sentinel string and a low floor: an exact count would break at the first clean-up of dead strings.
        assertEquals("Annuler", french["action_cancel"], "sentinel string action_cancel: wrong file?")
        assertTrue(french.size > 100, "only ${french.size} French strings were read: wrong file?")
    }

    @Test
    fun `no French string uses the informal address`() {
        val offenders = french.mapNotNull { (key, value) ->
            pronoun.find(value)?.let { "$key: found \"${it.value}\" in \"$value\"" }
        }

        assertTrue(offenders.isEmpty(), "informal pronouns, use \"vous\":\n" + offenders.joinToString("\n"))
    }

    @Test
    fun `no French sentence opens with an informal imperative`() {
        val offenders = french
            .filterKeys { it !in thirdPersonOpenings }
            .mapNotNull { (key, value) ->
                imperative.find(value)?.let { "$key: found \"${it.value.trim()}\" in \"$value\"" }
            }

        assertTrue(
            offenders.isEmpty(),
            "informal imperatives, use \"vous\" (or the infinitive for a label):\n" + offenders.joinToString("\n"),
        )
    }

    @Test
    fun `every exception is still needed`() {
        for (key in thirdPersonOpenings) {
            val value = assertNotNull(french[key], "$key is not in the French file any more: drop it from the exceptions")

            assertTrue(
                imperative.containsMatchIn(value),
                "$key no longer trips the imperative check: drop it from the exceptions",
            )
        }
    }

    // ── The checks themselves ─────────────────────────────────────────────

    @Test
    fun `the pronoun check finds the informal forms`() {
        val informal = listOf(
            "Tu devras ré-enrôler cet appareil",
            "sur ta YubiKey",
            "pour t'authentifier",
            "pour t\\'authentifier",
            "tes hôtes",
            "TON doigt",
            "%1\$s clignote : pose ton doigt",
        )

        for (text in informal) {
            assertTrue(pronoun.containsMatchIn(text), "the pronoun check missed: $text")
        }
    }

    @Test
    fun `the imperative check finds the informal forms`() {
        val informal = listOf(
            "Crée un snippet",
            "Données illisibles. Réessaie.",
            "%1\$s clignote : pose ton doigt",
            "perdus.\\n\\nTape CONFIRMER",
            "(a) Ferme tout logiciel",
            "Vérifiez le format, puis réessaie.",
            "Enregistré, réessaie.",
            "Branche ta clé et réessaye.",
        )

        for (text in informal) {
            assertTrue(imperative.containsMatchIn(text), "the imperative check missed: $text")
        }
    }

    @Test
    fun `the checks accept the formal forms`() {
        val formal = listOf(
            "vos hôtes",
            "Hôtes",
            "bouton",
            "ssh-keygen -t ed25519-sk",
            "Tapez CONFIRMER",
            "L\\'application",
            "n'est",
            "Créez un snippet",
            "Cet import crée des hôtes",
            "Échap ferme le dialogue",
            "Brancher la clé",
            "Réessayez.",
            "(c) Utiliser un lecteur",
            "Entre deux synchros",
            "Vérifiez le format puis réessayez.",
            "Réessayez ou fermez la fenêtre.",
        )

        for (text in formal) {
            assertFalse(pronoun.containsMatchIn(text), "the pronoun check flagged: $text")
            assertFalse(imperative.containsMatchIn(text), "the imperative check flagged: $text")
        }
    }
}
