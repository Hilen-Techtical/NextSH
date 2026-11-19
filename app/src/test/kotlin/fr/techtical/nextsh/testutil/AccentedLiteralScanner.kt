// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.testutil

import fr.techtical.nextsh.core.logging.blankComments
import fr.techtical.nextsh.ui.SourceGuardSupport
import java.io.File

/**
 * Finds the French words that are written in the code of the app instead of its resources: a string
 * literal that holds a letter outside ASCII ("Arrêté", "Démarrer") is text for the user, and an
 * English phone would read it in French. The guards of the screens call it on their package, for
 * instance `AccentedLiteralScanner.scan("ui/hosts")`, and expect an empty list.
 *
 * "A letter outside ASCII" is `Char.isLetter() && code > 127`: the ellipsis, the bullet or the middle
 * dot that separate two values are not letters and pass. The scanner reads the source and runs
 * nothing, like [SourceGuardSupport]. What it does with the Kotlin it reads:
 *  - comments are blanked first (line numbers stay), so a French comment is never reported;
 *  - string literals are found by a small lexer: escapes (`é` counts as the letter it spells),
 *    raw triple-quoted strings, and the `${...}` of a template, whose own literals are read as
 *    literals of their own while their code is not text;
 *  - a literal is skipped when it sits on a line that logs (`Timber.`, `Logger.`) or inside the
 *    parentheses of a call that opened on such a line: a log line is text for the developer, in English;
 *  - character literals (`'é'`) are not strings and are not read.
 *
 * A directory or a file that cannot be found FAILS (AssertionError), it never passes empty: a guard
 * that checks nothing must not look green.
 */
internal object AccentedLiteralScanner {

    /**
     * Every string literal of the Kotlin files under src/main/kotlin/fr/techtical/nextsh/<dirUnderNextSh>
     * (recursively) that holds a letter outside ASCII, as "path/relative/to/nextsh.kt:line: literal".
     * Comments are ignored; literals on a line that logs (Timber.) are skipped; [allowed] lists
     * literals (their text, without quotes) that are legitimate.
     */
    fun scan(dirUnderNextSh: String, allowed: Set<String> = emptySet()): List<String> {
        val nextSh = nextShDir()
        val root = File(nextSh, dirUnderNextSh)
        if (!root.isDirectory) {
            throw AssertionError("$dirUnderNextSh not found under ${nextSh.path}: the scanner cannot check it")
        }
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        if (files.isEmpty()) {
            throw AssertionError("no Kotlin source under ${root.path}: the scanner cannot check it")
        }
        return files.flatMap { file ->
            scanSource(file.relativeTo(nextSh).path.replace('\\', '/'), file.readText(), allowed)
        }
    }

    /** Same, for one file given by its path under src/main/kotlin/fr/techtical/nextsh/. */
    fun scanFile(pathUnderNextSh: String, allowed: Set<String> = emptySet()): List<String> {
        val file = SourceGuardSupport.locate("src/main/kotlin/fr/techtical/nextsh/$pathUnderNextSh")
        return scanSource(pathUnderNextSh.replace('\\', '/'), file.readText(), allowed)
    }

    /**
     * The scan of a source text, [label] standing for its path in the report. Kept visible to the
     * tests so that the lexer can be checked on a few lines without a file.
     */
    internal fun scanSource(label: String, source: String, allowed: Set<String> = emptySet()): List<String> {
        val code = blankComments(source)
        val lineBreaks = code.indices.filter { code[it] == '\n' }
        return LiteralLexer(code).literals()
            .filter { literal ->
                !literal.logged &&
                    literal.ownText.any { it.isLetter() && it.code > 127 } &&
                    literal.text !in allowed
            }
            .map { literal ->
                val found = lineBreaks.binarySearch(literal.offset)
                val before = if (found >= 0) found else -found - 1
                val shown = literal.text.replace("\r", "").replace("\n", "\\n")
                "$label:${before + 1}: $shown"
            }
    }

    /**
     * The `kotlin/fr/techtical/nextsh` directory of the module. The manifest sits at a fixed place of
     * the module, so [SourceGuardSupport.locate] finds it from the module directory (Gradle) or from
     * the repository root (an IDE), and fails when it cannot.
     */
    private fun nextShDir(): File {
        val manifest = SourceGuardSupport.locate("src/main/AndroidManifest.xml")
        val dir = File(manifest.parentFile, "kotlin/fr/techtical/nextsh")
        if (!dir.isDirectory) {
            throw AssertionError("${dir.path} not found: the scanner cannot check the sources")
        }
        return dir
    }

    /**
     * A string literal: [offset] of its opening quote, [text] as written between the quotes (what
     * `allowed` lists), [ownText] its own characters with the escapes decoded and the `${...}` left
     * out (what is tested for a letter), [logged] when it belongs to a log call.
     */
    private class Literal(val offset: Int, val text: String, val ownText: String, val logged: Boolean)

    /**
     * Reads the string literals of a Kotlin source whose comments are already blanked. The parentheses
     * are counted so that a literal on the second line of a `Timber.w(` call is still known as logged.
     */
    private class LiteralLexer(private val s: String) {
        private val found = mutableListOf<Literal>()

        /** One entry per open parenthesis: did its line hold a log call? */
        private val parens = mutableListOf<Boolean>()
        private var i = 0

        fun literals(): List<Literal> {
            code(closeAtBrace = false)
            return found.sortedBy { it.offset }
        }

        private fun lineLogs(offset: Int): Boolean {
            val start = s.lastIndexOf('\n', offset) + 1
            val end = s.indexOf('\n', offset).let { if (it < 0) s.length else it }
            val line = s.substring(start, end)
            return line.contains("Timber.") || line.contains("Logger.")
        }

        /** Walks code; with [closeAtBrace], returns after the `}` that closes a `${` template. */
        private fun code(closeAtBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> string()
                    c == '\'' -> charLiteral()
                    c == '(' -> {
                        parens.add(lineLogs(i))
                        i++
                    }
                    c == ')' -> {
                        if (parens.isNotEmpty()) parens.removeAt(parens.lastIndex)
                        i++
                    }
                    closeAtBrace && c == '{' -> {
                        depth++
                        i++
                    }
                    closeAtBrace && c == '}' -> {
                        i++
                        if (depth == 0) return
                        depth--
                    }
                    else -> i++
                }
            }
        }

        private fun charLiteral() {
            i++ // opening quote
            if (i < s.length && s[i] == '\\') i += 2 // the escape and the escaped char: '\'' and '\n'
            while (i < s.length && s[i] != '\'' && s[i] != '\n') i++ // the rest of '\u0000'
            i++ // closing quote
        }

        private fun string() {
            val start = i
            val raw = s.startsWith("\"\"\"", i)
            i += if (raw) 3 else 1
            val contentStart = i
            var contentEnd = s.length
            val own = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    !raw && c == '\\' -> {
                        // \uXXXX spells a letter: decode it so that an escape does not hide an accent.
                        val hex = if (s.getOrNull(i + 1) == 'u') s.substring(i + 2, minOf(i + 6, s.length)) else null
                        if (hex != null && hex.length == 4 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                            own.append(hex.toInt(16).toChar())
                            i += 6
                        } else {
                            i += 2
                        }
                    }
                    raw && s.startsWith("\"\"\"", i) -> {
                        contentEnd = i
                        i += 3
                        while (i < s.length && s[i] == '"') i++
                        break
                    }
                    !raw && c == '"' -> {
                        contentEnd = i
                        i++
                        break
                    }
                    // A regular string never spans lines: a lexer that lost track must not swallow the file.
                    !raw && c == '\n' -> {
                        contentEnd = i
                        break
                    }
                    c == '$' && s.startsWith("\${", i) -> {
                        i += 2
                        val mark = parens.size
                        code(closeAtBrace = true)
                        while (parens.size > mark) parens.removeAt(parens.lastIndex)
                    }
                    else -> {
                        own.append(c)
                        i++
                    }
                }
            }
            val text = s.substring(contentStart, minOf(contentEnd, s.length))
            found.add(Literal(start, text, own.toString(), lineLogs(start) || parens.any { it }))
        }
    }
}
