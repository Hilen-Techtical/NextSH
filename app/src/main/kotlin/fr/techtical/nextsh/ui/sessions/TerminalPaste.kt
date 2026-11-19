// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import com.termux.terminal.TerminalEmulator
import com.termux.view.TerminalView

/**
 * Sends clipboard text to the terminal as a paste, never as typed input.
 *
 * [TerminalEmulator.paste] strips ESC and C1 control characters, turns line breaks into CR and wraps
 * the text in bracketed paste markers when the remote application enabled mode 2004. Writing the text
 * straight to the session skips all of that: text copied from a hostile page could then run commands
 * or inject escape sequences.
 *
 * Paste paths routed here: the selection menu "Paste" ([TerminalTabStore]) and clipboard text handed
 * over by the keyboard (see [PasteSafeTerminalView]). The mouse middle click already calls
 * `paste` inside the Termux view.
 *
 * Nothing is sent when [emulator] is null (session not laid out yet) or [text] is null or empty.
 */
internal fun pasteClipboardText(emulator: TerminalEmulator?, text: CharSequence?) {
    if (emulator == null || text.isNullOrEmpty()) return
    emulator.paste(text.toString())
}

/**
 * Decides whether text committed by the keyboard (`InputConnection.commitText`) is a paste.
 *
 * A single character is normal typing (letters, Enter as LF, auto-correction of one char). Longer text
 * containing CR, LF, ESC or a C1 control (0x80-0x9F) can only come from a clipboard panel or an IME
 * paste action: it must be cleaned and protected like any other paste. Plain multi-character text
 * (swipe typing, suggestions) keeps the normal typing path.
 */
internal fun isKeyboardPaste(text: CharSequence?): Boolean {
    if (text == null || text.length <= 1) return false
    return text.any { it == '\r' || it == '\n' || it == '\u001B' || it in '\u0080'..'\u009F' }
}

/**
 * [TerminalView] whose keyboard input connection routes pasted text through [pasteClipboardText].
 *
 * The Termux view sends committed text char by char: ESC passes through, each LF becomes CR (every
 * line runs) and bracketed paste markers are never added. NextSH forces char based input, so a paste
 * from the keyboard clipboard panel lands in `commitText`. Done here rather than in the Termux fork.
 */
internal class PasteSafeTerminalView(context: Context) : TerminalView(context, null) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!isKeyboardPaste(text)) return super.commitText(text, newCursorPosition)
                // The committed text replaces any composing text: committing an empty text drops it.
                super.commitText("", 1)
                pasteClipboardText(mEmulator, text)
                return true
            }
        }
    }
}
