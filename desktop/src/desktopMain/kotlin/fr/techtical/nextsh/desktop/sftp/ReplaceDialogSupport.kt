// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sftp

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

// Pure helpers of the replacement confirmation dialog (SftpDialogs.kt), kept apart so
// that they can be tested without a window.

/**
 * Delay after which "Replace" can be used. A destructive button that is clickable the
 * instant its window appears can be hit by a click or a key press that was meant for
 * whatever was on screen before.
 */
internal const val REPLACE_ARM_DELAY_MS = 500L

/**
 * Keeps "Replace" usable only while its OWN window has the focus, and only once the window
 * has been in front of the user for [delayMs]. [setArmed] receives the result.
 *
 * Why the focus and not only a delay after the window appeared: the windows of two waiting
 * questions (two browsers, each with a question) open at the same size and place, one on
 * top of the other. A delay that runs for the window underneath expires while the user
 * answers the one above, and the next click, a double click for instance, lands on the
 * armed "Replace" of a question that was never read. So:
 *
 * - every time [windowFocused] turns true, the wait starts again from nothing: the first
 *   frame ([awaitFirstFrame], so that the delay counts from the moment the window can be
 *   seen), then [delayMs]; only then [setArmed] gets true;
 * - the moment it turns false, [setArmed] gets false at once, and a wait in progress is
 *   given up.
 *
 * Runs until its coroutine is cancelled (the window leaves the composition).
 */
internal suspend fun runReplaceArming(
    windowFocused: Flow<Boolean>,
    awaitFirstFrame: suspend () -> Unit,
    delayMs: Long,
    setArmed: (Boolean) -> Unit,
) {
    windowFocused.distinctUntilChanged().collectLatest { focused ->
        setArmed(false)
        if (focused) {
            awaitFirstFrame()
            delay(delayMs)
            setArmed(true)
        }
    }
}

/** Longest path shown in the dialog, in characters, before [shortenPathInMiddle] cuts it. */
internal const val REPLACE_PATH_MAX_CHARS = 100

/** U+2026, the horizontal ellipsis, built from its code point. */
private val ELLIPSIS = Char(0x2026).toString()

/** The shortest limit [shortenPathInMiddle] works with: the ellipsis and one character. */
private const val MIN_SHORTENED_CHARS = 2

/**
 * Shortens [path] to about [maxChars] characters by cutting its MIDDLE, so that both ends
 * stay readable: the beginning tells where the file is, the last segment tells which file
 * it is. A path of at most [maxChars] characters is returned as it is.
 *
 * The last segment (from the last `/` or `\`, separator included) is kept whole, and the
 * start of the path takes the room that remains:
 * `/home/thibaud/Documents/projects/nextsh/…/report-final.pdf`.
 *
 * If that last segment alone does not fit, there is no middle left to cut: the segment
 * is cut at its start instead and its END is kept, since it is the part (extension, version)
 * that tells files apart. The result is then exactly [maxChars] long.
 */
internal fun shortenPathInMiddle(path: String, maxChars: Int): String {
    val limit = maxChars.coerceAtLeast(MIN_SHORTENED_CHARS)
    if (path.length <= limit) return path
    val lastSeparator = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    val tail = if (lastSeparator >= 0) path.substring(lastSeparator) else path
    if (tail.length + ELLIPSIS.length > limit) {
        return ELLIPSIS + path.takeLast(limit - ELLIPSIS.length)
    }
    return path.take(limit - ELLIPSIS.length - tail.length) + ELLIPSIS + tail
}

/**
 * Absorbs the release of a key whose press this window never saw.
 *
 * The user validates the file chooser with Enter (or Space): the dialog opens while the key
 * is still down, and its release then reaches the new window. Compose's buttons click on the
 * key RELEASE (`KeyUp`) even if no press was seen, so that stray release would "click" the
 * button that has the focus. This guard remembers the presses ([KeyEventType.KeyDown]) of
 * the keys that activate a button, and reports a release without a press as one to absorb.
 *
 * Meant for the UI thread only (no synchronization).
 */
internal class StrayKeyUpGuard {

    private val pressed = HashSet<Key>()

    /**
     * Whether the event must be swallowed instead of delivered. A press is recorded and
     * delivered; the release of a key that was pressed in this window is delivered, once;
     * any other event, and any other key, is none of the guard's business.
     */
    fun shouldAbsorb(type: KeyEventType, key: Key): Boolean {
        if (key !in ACTIVATION_KEYS) return false
        return when (type) {
            KeyEventType.KeyDown -> {
                pressed.add(key)
                false
            }
            KeyEventType.KeyUp -> !pressed.remove(key)
            else -> false
        }
    }

    private companion object {
        /** The keys that activate a focused button. */
        val ACTIVATION_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)
    }
}
