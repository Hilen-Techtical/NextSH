// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Whether [error], or one of its causes, says that the phone has no usable WebView: the framework
 * throws `MissingWebViewPackageException` (an `Exception`, not a `RuntimeException`), wraps it in an
 * `AndroidRuntimeException` (sometimes twice), or throws `UnsupportedOperationException` on a device
 * that does not support WebView at all. By class name: those classes are not public API.
 */
internal fun isWebViewMissing(error: Throwable): Boolean {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is UnsupportedOperationException || current.javaClass.simpleName == MISSING_WEBVIEW_EXCEPTION) return true
        current = current.cause
        depth++
    }
    return false
}

private const val MISSING_WEBVIEW_EXCEPTION = "MissingWebViewPackageException"
private const val MAX_CAUSE_DEPTH = 8

/**
 * Erases what the tunnel browser (a WebView) keeps: the cookies and the web storage. Pages served through
 * a tunnel are private, and what a session left in the cookies must not survive the "Wipe the vault"
 * action.
 *
 * A phone without a WebView has no tunnel browser, so no web data: there is nothing to erase, and a wipe
 * that could never end for that reason would be worse. That is tested first (no WebView package) and
 * again on the failure (the forms of [isWebViewMissing]); any other failure is raised.
 *
 * Main thread only: the WebView singletons belong to it. The two collaborators are replaceable for the
 * tests, which have no Android.
 */
@Singleton
class WebDataEraser internal constructor(
    private val webViewInstalled: () -> Boolean,
    private val eraseWebData: suspend () -> Unit,
) {
    @Inject
    constructor() : this(
        webViewInstalled = { WebView.getCurrentWebViewPackage() != null },
        eraseWebData = {
            val cookies = CookieManager.getInstance()
            suspendCancellableCoroutine<Unit> { continuation ->
                cookies.removeAllCookies { continuation.resume(Unit) }
            }
            cookies.flush()
            WebStorage.getInstance().deleteAllData()
        },
    )

    suspend fun eraseAll() {
        try {
            if (!webViewInstalled()) {
                Timber.w("No WebView on this device, no web data to erase")
                return
            }
            eraseWebData()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tolerated only when the WebView package is really absent: a WebView being updated, or that failed
            // to load, still has cookies on the disk, and the step must fail so that "Retry" plays it again.
            if (!isWebViewMissing(e) || webViewInstalled()) throw e
            Timber.w("No usable WebView on this device, no web data to erase (${errorClass(e)})")
        }
    }
}
