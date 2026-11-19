// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * [FrenchTuForm] finds the "tu" form and nothing else. Some negatives are the words that held a "tu"
 * form for the ASCII `\b` of a JDK 19 or later without `(?U)`: the accented letter before the
 * letters counted as the end of a word. Others open the line after a written `\n`, which the pattern
 * accepts as a start: only a whole form counts there too.
 */
class FrenchTuFormTest {

    @Test
    fun `the pronouns and the possessives of the tu form are found`() {
        listOf(
            "Tu peux" to "Tu",
            "à ton vault" to "ton",
            "ta clé" to "ta",
            "tes hôtes" to "tes",
            "Je te le dis" to "te",
            "avec toi" to "toi",
            "TU" to "TU",
        ).forEach { (text, word) ->
            assertEquals(word, FrenchTuForm.find(text), "in: $text")
        }
    }

    @Test
    fun `the elided t is found in the three spellings of the apostrophe`() {
        // The text of a strings.xml as it is written (the apostrophe escaped for aapt2), then unescaped.
        assertEquals("t\\'", FrenchTuForm.find("Merci de t\\'identifier"))
        assertEquals("t'", FrenchTuForm.find("Merci de t'identifier"))
        val typographic = Char(0x2019)
        assertEquals("t$typographic", FrenchTuForm.find("Merci de t${typographic}identifier"))
    }

    @Test
    fun `a text with no tu form gives null`() {
        listOf(
            "Alignez le QR dans le cadre",
            "Assignez-la à un hôte SSH pour l\\'utiliser.",
            "Vous pouvez réessayer",
            "",
        ).forEach { text ->
            assertNull(FrenchTuForm.find(text), "in: $text")
        }
    }

    @Test
    fun `the end of a word that follows an accented letter is not a tu form`() {
        listOf(
            "hôtes",
            "hôte",
            "Êtes-vous sûr ?",
            "requêtes",
            "boîtes",
            "complètes",
            "méta",
            "piéton",
            "vêtu",
            "Bouton",
            "statut",
            "tests",
        ).forEach { text ->
            assertNull(FrenchTuForm.find(text), "found a tu form in: $text")
        }
    }

    @Test
    fun `a form that opens the line after a written line break is found`() {
        // The raw text of a strings.xml writes the break as a backslash and an n, and the n is a
        // letter for \b: the form that follows it must still be seen.
        assertEquals("Tu", FrenchTuForm.find("Échec.\\nTu peux réessayer"))
        assertEquals("Ton", FrenchTuForm.find("Échec.\\nTon vault est verrouillé"))
        assertEquals("T\\'", FrenchTuForm.find("Fin.\\nT\\'identifier"))
        assertEquals("Tu", FrenchTuForm.find("Fin.\\n\\nTu peux"))
        // A real line break was never a problem: it is not a letter.
        assertEquals("Tu", FrenchTuForm.find("Fin.\nTu peux"))
    }

    @Test
    fun `a longer word that opens the line after a written line break is not a tu form`() {
        listOf(
            "\\ntest",
            "\\ntentative",
            "\\nTerminal",
            "Fin.\\nVeuillez réessayer",
            "Fin.\\nOuvrez les réglages",
            "Fin.\\nAssignez-la à un hôte",
        ).forEach { text ->
            assertNull(FrenchTuForm.find(text), "found a tu form in: $text")
        }
    }

    @Test
    fun `the word found is the first one of the text`() {
        assertEquals("ta", FrenchTuForm.find("Vos hôtes et ta clé, puis ton vault"))
    }
}
