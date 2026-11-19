// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

/**
 * Finds the "tu" form in a French text. The French of the app speaks to the user with "vous", and the
 * tests of the strings that check it all share this one pattern, so that they cannot disagree.
 *
 * It looks for the pronouns and possessives of the "tu" form (tu, te, toi, ton, ta, tes) as whole
 * words, and the elided "t'" of "t'identifier", in the three spellings of the apostrophe that a text
 * can hold: the one escaped for aapt2 (`t\'`), the plain one and the typographic one (U+2019).
 *
 * Why `(?U)` (Unicode character classes): since the JDK 19 the `\b` of a pattern without it is ASCII
 * only, and before it was not. So the tests that used such a pattern FAILED on the JDK 21 of a laptop
 * and passed on the JDK 17 of the CI: with an ASCII `\b`, the "tes" of "hôtes", the "te" of "hôte" and
 * the "tes" of "Êtes-vous" count as words, because "ô" and "Ê" are not ASCII letters. `(?U)` can only
 * remove matches, never add one, so adding it hides no real "tu" form.
 *
 * Why the lookbehind on `\n`: the tests read the raw text of a `strings.xml`, where a line break is
 * written `\n` (a backslash and an n). The n is a letter for `\b`, so a form that opens the line
 * ("Échec.\nTu peux") would sit inside a word and be missed. A form may therefore also start right
 * after `\n`. A real line break needs nothing: it is not a letter.
 *
 * Why no imperatives (Aligne, Ouvre, Réessaie...): the imperative of the "tu" form is also the third
 * person of a help text ("Relance les tunnels SSH qui se coupent", "Crée un proxy SOCKS5"), which is
 * correct French for "vous". A pattern that lists them flags those texts, so a test that wants them
 * keeps its own list, limited to the texts it knows to be instructions.
 */
internal object FrenchTuForm {

    private val TU_FORM = Regex(
        """(?U)(?:\b|(?<=\\n))(?:tu|te|toi|ton|ta|tes)\b|(?:\b|(?<=\\n))t(?:\\'|'|\x{2019})""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The first word of the "tu" form in [text] (the text of a `strings.xml` as it is written, `\'`
     * and `\n` included, or unescaped), or null when there is none.
     */
    fun find(text: String): String? = TU_FORM.find(text)?.value
}
