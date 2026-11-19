// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefBrowserOsrWithHandler
import org.cef.browser.CefRequestContext
import org.cef.handler.CefRenderHandler
import java.awt.Component
import javax.swing.SwingUtilities

/**
 * Holds a close request until the native browser exists.
 *
 * JCEF only completes a close that comes after the creation: a `close(true)` sent before
 * `onAfterCreated` sets its internal "closing" flag, and the retry it makes once the browser
 * is created then returns early, so the native browser, its renderer process and its request
 * context live until the application exits. [requestClose] therefore runs [closeNow] at once
 * when the browser was already reported as created, and otherwise waits for [markCreated].
 *
 * Every follow-up callback handed to [requestClose] (releasing the client, say) runs exactly
 * once, each one guarded: after [closeNow], never before, whichever request came first and
 * whether it came before or after the close ran. The client must outlive the creation
 * callback, which is why the follow-ups wait for the close too.
 *
 * [runDeferred] runs the deferred close; it lets the caller leave the CEF callback thread.
 * Only the first request starts the close.
 */
internal class CloseGate(
    private val closeNow: () -> Unit,
    private val runDeferred: (() -> Unit) -> Unit = { it() },
) {
    private val lock = Any()
    private var created = false
    private var requested = false
    private var done = false
    private val followUps = ArrayList<() -> Unit>()

    /** The native browser exists (JCEF's `onAfterCreated`). Runs a close asked for earlier. */
    fun markCreated() {
        val pendingClose = synchronized(lock) {
            if (created) return
            created = true
            requested
        }
        if (pendingClose) runDeferred(::execute)
    }

    /** Asks for the close. [then] runs once the close has been started, however many requests there are. */
    fun requestClose(then: () -> Unit = {}) {
        var startNow = false
        var runNow = false
        synchronized(lock) {
            when {
                done -> runNow = true
                requested -> followUps += then
                else -> {
                    requested = true
                    followUps += then
                    startNow = created
                }
            }
        }
        when {
            runNow -> runQuietly(then)
            startNow -> execute()
        }
    }

    private fun execute() {
        try {
            closeNow()
        } finally {
            drainFollowUps()
        }
    }

    // A follow-up added while the others run is picked up by the next round.
    private fun drainFollowUps() {
        while (true) {
            val batch = synchronized(lock) {
                if (followUps.isEmpty()) {
                    done = true
                    null
                } else {
                    ArrayList(followUps).also { followUps.clear() }
                }
            } ?: return
            batch.forEach(::runQuietly)
        }
    }

    private fun runQuietly(action: () -> Unit) {
        runCatching(action)
    }
}
/**
 * The OSR browser of the in-app browser overlay, with a safe way to close it.
 *
 * Why: JCEF only completes the native close of a browser once [setCloseAllowed] was called,
 * and at that point its `doClose` posts a `WINDOW_CLOSING` event to the root window of
 * [getUIComponent]. For the overlay that window is the main NextSH window, so closing the
 * browser would run the application's own close request (minimise to tray, quit...).
 * [closeForGood] first makes [getUIComponent] report nothing, so JCEF has no window to
 * notify, then does the close sequence. Without `setCloseAllowed` the close never
 * completes (measured: no `onBeforeClose` within 10 s), which would keep the browser, its
 * renderer process and its in-memory request context (cookies, HTTP authentication cache)
 * alive until the application exits. A close requested before the browser is created waits
 * for it, see [CloseGate]: the owner must call [markCreated] from `onAfterCreated`.
 */
internal class NextShOsrBrowser(
    client: CefClient,
    url: String,
    context: CefRequestContext,
    renderHandler: CefRenderHandler,
    component: Component,
) : CefBrowserOsrWithHandler(client, url, context, renderHandler, component) {

    @Volatile
    private var closing = false

    private val gate = CloseGate(
        closeNow = {
            runCatching { setCloseAllowed() }
            runCatching { close(true) }
        },
        // Out of the creation callback, onto the UI thread.
        runDeferred = { SwingUtilities.invokeLater(it) },
    )

    override fun getUIComponent(): Component? = if (closing) null else super.getUIComponent()

    /** To call from `onAfterCreated`. */
    fun markCreated() = gate.markCreated()

    /**
     * Starts the close that really ends the native browser, now or as soon as it exists.
     * [then] runs right after, even when the close is deferred. Never throws.
     */
    fun closeForGood(then: () -> Unit = {}) {
        closing = true
        gate.requestClose(then)
    }
}

/**
 * Closes [browser] for good, see [NextShOsrBrowser]. A browser of another kind (nothing
 * else creates one today) still gets the `setCloseAllowed` + `close(true)` sequence.
 */
internal fun closeBrowserForGood(browser: CefBrowser, then: () -> Unit = {}) {
    if (browser is NextShOsrBrowser) {
        browser.closeForGood(then)
    } else {
        runCatching { browser.setCloseAllowed() }
        runCatching { browser.close(true) }
        then()
    }
}
