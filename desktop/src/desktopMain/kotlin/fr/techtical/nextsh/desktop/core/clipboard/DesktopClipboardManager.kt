// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.clipboard

import fr.techtical.nextsh.shared.util.AppScope
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/**
 * Mirrors Android's clipboard auto-clear. The straightforward approach
 * (`Clipboard.addFlavorListener`) is unreliable on Windows: WClipboard's
 * `checkChange()` compares the *DataFlavor list*, not the content, so two
 * consecutive copies of plain text from the same app produce identical
 * flavor lists and the listener never fires. JediTerm-only copies fall
 * exactly into that pattern, which is why the listener-based design
 * looked correct in tests but failed in production.
 *
 * Strategy: poll the system clipboard text at [POLL_INTERVAL_MS] and react
 * to content changes. When a change is detected:
 *  - if a NextSH window is focused → assume the copy came from us and
 *    schedule a clear after the configured timeout
 *  - otherwise → an external app touched the clipboard, cancel any
 *    pending clear so the user's external copy isn't wiped at our timer
 *
 * The clear writes an empty `StringSelection` with no owner. Subsequent
 * polls see the empty content and skip rescheduling.
 */
class DesktopClipboardManager(
    private val appScope: AppScope,
    private val timeoutSecondsProvider: () -> Int,
    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard,
    private val isOriginatedFromUsOverride: (() -> Boolean)? = null,
    private val pollIntervalMsOverride: Long? = null,
) {

    @Volatile
    private var pollJob: Job? = null

    @Volatile
    private var clearJob: Job? = null

    fun start() {
        if (pollJob?.isActive == true) return
        val intervalMs = pollIntervalMsOverride ?: POLL_INTERVAL_MS
        // Snapshot the clipboard SYNCHRONOUSLY on the start thread.
        // If we did this inside the launch body, Dispatchers.Default's
        // dispatch latency lets the caller (test or app) modify the
        // clipboard before our coroutine reads: the first poll would
        // see no change and miss the very copy the user just made.
        val initial = readClipboardText()
        pollJob = appScope.coroutineScope.launch {
            var lastSeen: String? = initial
            while (isActive) {
                delay(intervalMs)
                val current = try {
                    readClipboardText()
                } catch (e: Exception) {
                    Logger.w("ClipboardMgr", "poll read failed: ${e.javaClass.simpleName}")
                    continue
                }
                if (current != lastSeen) {
                    lastSeen = current
                    onClipboardChanged(current)
                }
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        clearJob?.cancel()
        clearJob = null
    }

    private fun onClipboardChanged(newText: String?) {
        if (newText.isNullOrEmpty()) return
        if (!isOriginatedFromUs()) {
            // External app's copy: cancel any pending clear so we don't
            // wipe the user's external content at our timer's expiry.
            clearJob?.cancel()
            clearJob = null
            return
        }
        val timeout = timeoutSecondsProvider()
        if (timeout <= 0) return
        scheduleClear(timeout)
    }

    private fun isOriginatedFromUs(): Boolean {
        isOriginatedFromUsOverride?.let { return it() }
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow != null
    }

    private fun scheduleClear(timeoutSeconds: Int) {
        clearJob?.cancel()
        clearJob = appScope.coroutineScope.launch {
            delay(timeoutSeconds * 1000L)
            clearNow()
        }
    }

    private fun clearNow() {
        try {
            clipboard.setContents(StringSelection(""), null)
        } catch (e: IllegalStateException) {
            Logger.w("ClipboardMgr", "clear failed: ${e.javaClass.simpleName}")
        }
    }

    private fun readClipboardText(): String? {
        val transferable = try {
            clipboard.getContents(null)
        } catch (e: IllegalStateException) {
            // Clipboard contended (another process owns it briefly): skip
            // this tick, we'll catch the change on the next poll.
            return null
        } ?: return null
        if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return null
        return try {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        // 500 ms gives sub-second perceived responsiveness for the user's
        // copy → first poll latency, while keeping CPU/syscall cost trivial
        // (one Win32 OpenClipboard per second on average).
        const val POLL_INTERVAL_MS = 500L
    }
}
