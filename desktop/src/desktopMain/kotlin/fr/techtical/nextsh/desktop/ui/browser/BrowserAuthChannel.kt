// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.ui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import org.cef.browser.CefBrowser
import org.cef.browser.CefDevToolsClient

/** How long [startAuthInterception] waits for `Fetch.enable` before loading the page anyway. */
internal const val FETCH_ENABLE_TIMEOUT_MS = 5_000L

private const val DEVTOOLS_WAIT_MS = 2_000L
private const val DEVTOOLS_POLL_MS = 25L

/**
 * [CdpSender] over JCEF's in-process DevTools client: no remote debugging port is opened.
 *
 * The future returned by `executeDevToolsMethod` is deliberately dropped, and must NEVER be
 * logged or inspected for its message: a failed command completes with a `DevToolsException`
 * whose message contains the parameters JSON, which for `ProvideCredentials` includes the
 * username and the password. A command that targets a request already gone (the page
 * navigated away while the sign-in prompt was up) never completes, so nothing may wait on it.
 *
 * [sweeper] removes the finished entries JCEF leaves in the client's command queue, see
 * [DevToolsCommandSweeper]; null when the queue cannot be reached.
 */
internal class CefDevToolsSender(
    private val client: CefDevToolsClient,
    private val sweeper: DevToolsCommandSweeper? = DevToolsCommandSweeper.forClient(client),
) : CdpSender {
    override fun send(method: String, params: JsonObject) {
        client.executeDevToolsMethod(method, params.toString())
        sweeper?.maybeSweep()
    }
}

/**
 * The DevTools client of a browser that [org.cef.handler.CefLifeSpanHandler.onAfterCreated]
 * already reported as created, or null if it is still not available after two seconds.
 * JCEF flips a "pending" flag a hair around that callback, hence the short polling.
 */
internal suspend fun awaitDevToolsClient(browser: CefBrowser): CefDevToolsClient? {
    var waited = 0L
    while (true) {
        browser.devToolsClient?.let { return it }
        if (waited >= DEVTOOLS_WAIT_MS) return null
        delay(DEVTOOLS_POLL_MS)
        waited += DEVTOOLS_POLL_MS
    }
}

/**
 * Plugs [coordinator] into [devTools]: events are routed to it, then `Fetch.enable` makes
 * every request go through it (a request is paused until the coordinator continues it) and
 * every HTTP authentication challenge too, so Chromium never opens its own sign-in window.
 *
 * Suspends until `Fetch.enable` is answered, at most [FETCH_ENABLE_TIMEOUT_MS]. Returns
 * false when it failed or timed out: pages then still load, but a challenge may fall back
 * to Chromium's native window. The listener is attached first so that no event is lost
 * between the answer and the first request.
 *
 * Limits of the DevTools session, measured on Chromium 144: it is attached to the page's own
 * target. A dedicated worker's requests do go through it (a challenge from a worker gets the
 * sign-in prompt). A shared worker's or a service worker's do not: a challenge for such a
 * request is neither prompted for nor turned into Chromium's native window, the request just
 * gets the server's 401. Once the user has signed in on the origin, Chromium's authentication
 * cache serves those requests like any other. Frames rendered out of process (OOPIF) were not
 * measured; the page and its frames on 127.0.0.1 share a site, so they normally stay in the
 * page's process. Popups are never created, see
 * [DesktopTunnelBrowserViewModel.resolvePopupTarget].
 */
internal suspend fun startAuthInterception(
    devTools: CefDevToolsClient,
    coordinator: BrowserAuthCoordinator,
): Boolean {
    // Runs on the CEF UI thread: only hands the event over, the coordinator never blocks.
    devTools.addEventListener(
        CefDevToolsClient.EventListener { name, params -> coordinator.onCdpEvent(name, params) },
    )
    return try {
        withTimeoutOrNull(FETCH_ENABLE_TIMEOUT_MS) {
            devTools.executeDevToolsMethod(
                BrowserAuthCoordinator.METHOD_ENABLE,
                BrowserAuthCoordinator.enableParams().toString(),
            ).await()
            true
        } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The exception is dropped on purpose: see CefDevToolsSender about its message.
        false
    }
}
