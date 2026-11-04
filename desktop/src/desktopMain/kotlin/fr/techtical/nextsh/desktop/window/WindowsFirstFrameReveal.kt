// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.window

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import fr.techtical.nextsh.shared.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Window
import java.util.Locale

private const val TAG = "WindowsFirstFrameReveal"

/** Delay between two polls of `Window.isShowing`. */
internal const val REVEAL_POLL_INTERVAL_MS = 50L

/**
 * How long to wait for the window to appear on screen. Generous on purpose,
 * like the one of [WindowsRestoreBounds]: a cold start composes the content
 * for a second or more before AWT shows the window.
 */
internal const val REVEAL_SHOW_TIMEOUT_MS = 30_000L

/**
 * Safety net: the most the window stays cloaked once it is showing, whether
 * or not a frame arrives. A cloaked window is invisible, so this is also the
 * longest a stuck renderer can keep the user from seeing the application.
 */
internal const val REVEAL_FRAME_TIMEOUT_MS = 2_000L

/**
 * Pause between "Compose started its first frame" and the uncloak. The frame
 * clock ticks at the START of a frame: the draw and the buffer swap that
 * follow need a moment to reach DWM, and uncloaking before that would show
 * the blank surface this class exists to hide.
 */
internal const val REVEAL_PRESENT_SETTLE_MS = 50L

/** Size in bytes of the BOOL `DWMWA_CLOAK` takes. */
private const val BOOL_SIZE = 4

/**
 * Keeps a Windows window invisible until Compose has presented its first
 * frame.
 *
 * ## Why this exists
 * Windows shows the native window as soon as AWT asks for it, but Compose
 * (Skia on OpenGL) has not drawn anything yet at that point. The surface not
 * yet drawn is shown as a blank white page, then NearBlack (the AWT backdrop),
 * then the application. DWM cloaking (`DWMWA_CLOAK`) closes that gap: the
 * window is cloaked during composition, before the first show, so everything
 * that follows (the show, the maximize, the first paint) happens out of
 * sight. The cloak is lifted once the first frame is on its way to DWM.
 *
 * ## Contract
 *  - [cloak] is called ONCE, from the composition, while the window is
 *    displayable (it has a native handle) but before it is shown.
 *  - [revealAfterFirstFrame] is then run from a `LaunchedEffect` of the same
 *    window, and ALWAYS lifts the cloak, on every path: success, no frame
 *    within the safety delay, exception, cancellation. The window can never
 *    stay invisible.
 *  - The cloak is never set again afterwards, so a later re-show of the
 *    window (tray icon, minimize to tray) is never affected by it. The one
 *    exception is [cloakForExit], on the way out, when the window is about
 *    to be destroyed and never shown again.
 *
 * Safe on every platform: no-op if not Windows. Never throws: any failure is
 * logged (no sensitive data) and the window simply stays visible as before.
 */
object WindowsFirstFrameReveal {

    /** `true` only on Windows: all operations are no-ops on other platforms. */
    val isPlatformSupported: Boolean =
        System.getProperty("os.name", "").lowercase(Locale.US).contains("windows")

    /**
     * Cloaks [window]. Returns `true` only if the window is now cloaked, in
     * which case the caller MUST later run [revealAfterFirstFrame]. Returns
     * `false` (nothing cloaked, nothing to undo) when not on Windows, when
     * [window] has no native handle yet, or when DWM refuses.
     */
    fun cloak(window: Window): Boolean = setCloaked(window, cloaked = true)

    /**
     * Cloaks [window] right before the app exits, so the user never sees the
     * teardown (the vault locking back to its unlock screen, sessions
     * closing, the OpenGL surface destroyed during the OS close animation).
     * Nothing lifts this cloak: the window is destroyed right after. Returns
     * `false` when nothing was cloaked (not Windows, no native handle, DWM
     * refused), in which case the caller hides the window another way.
     */
    fun cloakForExit(window: Window): Boolean = setCloaked(window, cloaked = true)

    /**
     * Waits for the first frame of [window], then lifts the cloak set by
     * [cloak] and calls [onRevealed]. Call it from a `LaunchedEffect` (the
     * polling suspends with [delay] and cancels with its caller).
     *
     * Steps, when [cloaked] is `true`:
     *  1. poll `window.isShowing` every [REVEAL_POLL_INTERVAL_MS], up to
     *     [REVEAL_SHOW_TIMEOUT_MS]. A window cloaked by DWM is still "showing"
     *     for AWT, so this ends when AWT shows it;
     *  2. [awaitFrame] (the caller passes `{ withFrameNanos { } }`, the frame
     *     clock of the window), then [REVEAL_PRESENT_SETTLE_MS] for Skia to
     *     hand the image to DWM. This step as a whole is capped at
     *     [REVEAL_FRAME_TIMEOUT_MS], so the cloak lasts at most that long
     *     after the show even if no frame ever comes;
     *  3. uncloak, then [onRevealed]. These two run in a `finally`: after an
     *     exception (logged, never rethrown), after a timeout, and after a
     *     cancellation (rethrown) alike. [onRevealed] is called exactly once.
     *
     * When [cloaked] is `false` (the cloak failed or this is not Windows)
     * there is nothing to wait for or to undo: [onRevealed] is called at once
     * and the window is left alone.
     *
     * @param trace receives one factual line per step reached, for the
     *   startup trace.
     */
    suspend fun revealAfterFirstFrame(
        window: Window,
        cloaked: Boolean,
        awaitFrame: suspend () -> Unit,
        onRevealed: () -> Unit,
        trace: (String) -> Unit = {},
    ) {
        awaitFirstFrameThenReveal(
            cloaked = cloaked,
            isShowing = { window.isShowing },
            awaitFrame = awaitFrame,
            uncloak = { setCloaked(window, cloaked = false) },
            onRevealed = onRevealed,
            trace = trace,
        )
    }

    private fun setCloaked(window: Window, cloaked: Boolean): Boolean =
        applyCloak(
            cloaked = cloaked,
            platformSupported = isPlatformSupported,
            resolveHwnd = { windowHandle(window) },
            setAttribute = { hwnd, attribute, value ->
                Dwmapi.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), BOOL_SIZE)
            },
        )

    /**
     * The native handle of [window], or null if it has none (not displayable
     * yet, already disposed). `Native.getWindowPointer` throws in the first
     * case and can return a zero handle in the second.
     */
    private fun windowHandle(window: Window): Pointer? =
        runCatching { Native.getWindowPointer(window) }.getOrNull()
            ?.takeIf { Pointer.nativeValue(it) != 0L }
}

/**
 * Sets or clears `DWMWA_CLOAK` through [setAttribute] (the injected
 * `DwmSetWindowAttribute`: window handle, attribute, BOOL value, HRESULT
 * back). Returns `true` only if DWM accepted the change.
 *
 * Returns `false`, calling nothing, when [platformSupported] is `false`. Any
 * other failure (no handle, non-zero HRESULT, a JNA exception) is logged and
 * turned into `false`: this never throws.
 */
internal fun applyCloak(
    cloaked: Boolean,
    platformSupported: Boolean,
    resolveHwnd: () -> Pointer?,
    setAttribute: (hwnd: Pointer, attribute: Int, value: Int) -> Int,
): Boolean {
    if (!platformSupported) return false
    return try {
        val hwnd = resolveHwnd()
        if (hwnd == null) {
            Logger.w(TAG, "no native window handle, cloak=$cloaked not applied")
            false
        } else {
            val hresult = setAttribute(hwnd, Dwmapi.DWMWA_CLOAK, if (cloaked) 1 else 0)
            if (hresult != 0) {
                Logger.w(TAG, "DWMWA_CLOAK=$cloaked refused, HRESULT=0x${Integer.toHexString(hresult)}")
            }
            hresult == 0
        }
    } catch (e: Throwable) {
        // JNA load failure, bad handle: never let it reach the UI.
        Logger.w(TAG, "DWMWA_CLOAK=$cloaked failed: ${e.message}")
        false
    }
}

/**
 * The scheduling of [WindowsFirstFrameReveal.revealAfterFirstFrame], with
 * every dependency injected so it runs under virtual time in tests. See that
 * function for the contract.
 *
 * [uncloak] runs before [onRevealed], and a failure of the first never
 * prevents the second.
 */
internal suspend fun awaitFirstFrameThenReveal(
    cloaked: Boolean,
    isShowing: () -> Boolean,
    awaitFrame: suspend () -> Unit,
    uncloak: () -> Unit,
    onRevealed: () -> Unit,
    trace: (String) -> Unit = {},
) {
    if (!cloaked) {
        onRevealed()
        return
    }
    try {
        val shown = withTimeoutOrNull(REVEAL_SHOW_TIMEOUT_MS) {
            while (!isShowing()) delay(REVEAL_POLL_INTERVAL_MS)
            true
        } ?: false
        if (!shown) {
            Logger.w(TAG, "window not showing after $REVEAL_SHOW_TIMEOUT_MS ms, uncloaking")
            trace("window never showed within $REVEAL_SHOW_TIMEOUT_MS ms, uncloaking")
        } else {
            trace("window showing (cloaked), waiting for the first frame")
            val presented = withTimeoutOrNull(REVEAL_FRAME_TIMEOUT_MS) {
                awaitFrame()
                delay(REVEAL_PRESENT_SETTLE_MS)
                true
            } ?: false
            if (presented) {
                trace("first frame rendered, uncloaking")
            } else {
                Logger.w(TAG, "no first frame within $REVEAL_FRAME_TIMEOUT_MS ms, uncloaking anyway")
                trace("no first frame within $REVEAL_FRAME_TIMEOUT_MS ms, uncloaking anyway")
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Logger.w(TAG, "waiting for the first frame failed: ${e.message}")
        trace("first frame wait failed, uncloaking")
    } finally {
        try {
            uncloak()
        } catch (e: Throwable) {
            Logger.w(TAG, "uncloak failed: ${e.message}")
        } finally {
            onRevealed()
        }
    }
}
