// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.clipboard

import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

/**
 * Puts text copied by a remote program (OSC 52, see `Osc52Filter`) on the
 * system clipboard.
 *
 * The write is posted to the AWT event queue because the caller is the SSH
 * reader thread. Writes are coalesced: while one is queued, later ones only
 * replace the pending text, so a host streaming OSC 52 sequences queues a
 * single EDT task and the latest text wins. Failures (clipboard held by
 * another process, headless environment) are swallowed: a missed copy must
 * never disturb the terminal, and nothing about the content is logged.
 *
 * Auto-clear: [DesktopClipboardManager] only treats a clipboard change as
 * NextSH's own when a NextSH window is focused, but a background pane may
 * copy while another app has the focus. The text written here is therefore
 * remembered until the manager [claim]s it, so it gets the usual auto-clear
 * instead of being mistaken for an external copy.
 */
object RemoteClipboardWriter {

    /** Latest text waiting for the EDT; non-null means a flush is already queued. */
    private val pending = AtomicReference<String?>(null)

    /** Last text put on the clipboard, until [DesktopClipboardManager] sees it. */
    private val lastWritten = AtomicReference<String?>(null)

    fun write(text: String) {
        if (pending.getAndSet(text) != null) return
        SwingUtilities.invokeLater {
            val latest = pending.getAndSet(null) ?: return@invokeLater
            // Marked before the write: the manager's poll may run between the
            // two and must already recognise the text as ours.
            noteWritten(latest)
            try {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(latest), null)
            } catch (_: Exception) {
                lastWritten.compareAndSet(latest, null)
            }
        }
    }

    /**
     * `true` (once) when [text] is the last text this writer put on the
     * clipboard. Called by [DesktopClipboardManager] on every change it sees.
     */
    internal fun claim(text: String): Boolean {
        val written = lastWritten.get() ?: return false
        return written == text && lastWritten.compareAndSet(written, null)
    }

    internal fun noteWritten(text: String) {
        lastWritten.set(text)
    }
}
