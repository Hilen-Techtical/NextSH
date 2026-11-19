// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.browser

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A phone without a WebView has no web data: the wipe must end there, in every form the framework reports
 * it. But a WebView that is only being updated, or failed to load, still has cookies on the disk: the same
 * exceptions then fail the step, so that "Retry" plays it again.
 */
class WebDataEraserTest {

    /** Stands for `android.webkit.WebViewFactory.MissingWebViewPackageException`, an `Exception`. */
    private class MissingWebViewPackageException : Exception("missing")

    private class WrappingException(cause: Throwable) : RuntimeException(cause)

    /** [installed] answers the first question (before the erase), [installedAfter] the second (in the catch). */
    private fun eraser(installed: Boolean = true, installedAfter: Boolean = installed, erase: suspend () -> Unit = {}): WebDataEraser {
        val answers = ArrayDeque(listOf(installed, installedAfter))
        return WebDataEraser(webViewInstalled = { answers.removeFirstOrNull() ?: installedAfter }, eraseWebData = erase)
    }

    @Test
    fun `no WebView package means nothing to erase, and nothing is called`() = runTest {
        var called = false

        eraser(installed = false) { called = true }.eraseAll()

        assertFalse(called)
    }

    @Test
    fun `the data is erased when there is a WebView`() = runTest {
        var called = false

        eraser { called = true }.eraseAll()

        assertTrue(called)
    }

    @Test
    fun `the missing package exception, which is not a RuntimeException, ends the erase when the package is gone`() = runTest {
        eraser(installed = true, installedAfter = false) { throw MissingWebViewPackageException() }.eraseAll()
    }

    @Test
    fun `the missing package wrapped once or twice ends the erase when the package is gone`() = runTest {
        eraser(installed = true, installedAfter = false) { throw WrappingException(MissingWebViewPackageException()) }.eraseAll()
        eraser(installed = true, installedAfter = false) {
            throw WrappingException(WrappingException(MissingWebViewPackageException()))
        }.eraseAll()
    }

    @Test
    fun `a device that does not support WebView ends the erase when the package is gone`() = runTest {
        eraser(installed = true, installedAfter = false) { throw UnsupportedOperationException("no webview") }.eraseAll()
    }

    @Test
    fun `a WebView that is being updated or failed to load still has cookies, so the same exceptions fail the step`() {
        for (failure in listOf<Throwable>(
            MissingWebViewPackageException(),
            WrappingException(MissingWebViewPackageException()),
            UnsupportedOperationException("loading"),
        )) {
            assertThrows(Throwable::class.java) {
                runBlocking { eraser(installed = true, installedAfter = true) { throw failure }.eraseAll() }
            }
        }
    }

    @Test
    fun `any other failure is raised, so the wipe says it is incomplete`() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { eraser(installedAfter = false) { throw IllegalStateException("real") }.eraseAll() }
        }
        assertThrows(RuntimeException::class.java) {
            runBlocking { eraser(installedAfter = false) { throw WrappingException(IllegalStateException("real")) }.eraseAll() }
        }
    }

    @Test
    fun `a cancellation is never swallowed`() {
        assertThrows(CancellationException::class.java) {
            runBlocking { eraser(installedAfter = false) { throw CancellationException("cancelled") }.eraseAll() }
        }
    }

    @Test
    fun `the chain of causes is read, and a loop in it ends at the bound`() {
        assertTrue(isWebViewMissing(WrappingException(MissingWebViewPackageException())))
        assertFalse(isWebViewMissing(IllegalStateException("x")))
        // A real loop: a -> b -> a. The walk is bounded, it ends and finds nothing.
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertEquals(false, isWebViewMissing(a))
    }
}
