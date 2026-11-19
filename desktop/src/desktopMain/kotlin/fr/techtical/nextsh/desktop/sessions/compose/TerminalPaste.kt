// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sessions.compose

/**
 * What a paste sends to the terminal, on par with the Termux fork of the Android app.
 *
 * The text is cleaned first, whether or not the host asked for bracketed paste (mode 2004):
 * - ESC (U+001B) and the C1 controls (U+0080 to U+009F) are removed. A pasted `ESC [ 2 0 1 ~` would end a
 *   bracketed paste early and the rest would be read as typed, and a C1 control is a single character
 *   escape sequence (U+009B is CSI) for an application that reads 8-bit controls.
 * - CRLF and LF become CR, what the Enter key sends: a line is validated the way a typed one is.
 *
 * Then, when [bracketed] is true, the text is wrapped in the paste markers so that the shell can tell pasted
 * text from typed text. The markers are the only ESC characters left in the result.
 */
internal fun pastePayload(text: String, bracketed: Boolean): String {
    val cleaned = buildString(text.length) {
        for (c in text) {
            if (c == '\u001B' || c in '\u0080'..'\u009F') continue
            append(c)
        }
    }.replace("\r\n", "\r").replace('\n', '\r')
    return if (bracketed) "$BRACKETED_PASTE_START$cleaned$BRACKETED_PASTE_END" else cleaned
}

private const val BRACKETED_PASTE_START = "\u001B[200~"
private const val BRACKETED_PASTE_END = "\u001B[201~"
