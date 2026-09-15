// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.clipboard

import fr.techtical.nextsh.shared.util.AppScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Real-time tests against the polling-based design. Poll interval is set
 * to 50 ms in tests so we don't wait the production 500 ms for each tick.
 */
class DesktopClipboardManagerTest {

    private lateinit var scope: CoroutineScope
    private lateinit var appScope: AppScope

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        appScope = TestAppScope(scope)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private fun fakeClipboard() = Clipboard("nextsh-test")

    private fun readText(c: Clipboard): String? = try {
        c.getData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) { null }

    private fun waitFor(timeoutMs: Long, predicate: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return true
            Thread.sleep(20)
        }
        return predicate()
    }

    @Test
    fun `clears clipboard after timeout when terminal focused`() {
        val clipboard = fakeClipboard()
        val mgr = DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { 1 },
            clipboard = clipboard,
            isOriginatedFromUsOverride = { true },
            pollIntervalMsOverride = 50L,
        )
        mgr.start()
        clipboard.setContents(StringSelection("super-secret"), null)
        // Poll detects the change → schedules clear at 1s.
        // Wait up to 2s for the clear to actually fire.
        val cleared = waitFor(2_000) { (readText(clipboard) ?: "x").isEmpty() }
        assertEquals(true, cleared, "clipboard should have been cleared by 2 s")
        mgr.stop()
    }

    @Test
    fun `timeout 0 disables auto-clear`() {
        val clipboard = fakeClipboard()
        val mgr = DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { 0 },
            clipboard = clipboard,
            isOriginatedFromUsOverride = { true },
            pollIntervalMsOverride = 50L,
        )
        mgr.start()
        clipboard.setContents(StringSelection("kept"), null)
        Thread.sleep(400)
        assertEquals("kept", readText(clipboard))
        mgr.stop()
    }

    @Test
    fun `external focus does not trigger auto-clear`() {
        val clipboard = fakeClipboard()
        val mgr = DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { 1 },
            clipboard = clipboard,
            isOriginatedFromUsOverride = { false },
            pollIntervalMsOverride = 50L,
        )
        mgr.start()
        clipboard.setContents(StringSelection("notepad-paste"), null)
        Thread.sleep(1_400)
        assertEquals("notepad-paste", readText(clipboard))
        mgr.stop()
    }

    @Test
    fun `external clipboard change cancels pending clear`() {
        val clipboard = fakeClipboard()
        // Toggle: terminal focus for the first change, external for the second.
        var focused = true
        val mgr = DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { 2 },
            clipboard = clipboard,
            isOriginatedFromUsOverride = { focused },
            pollIntervalMsOverride = 50L,
        )
        mgr.start()
        clipboard.setContents(StringSelection("from-terminal"), null)
        // Wait long enough for the poll to register the change AND schedule a clear.
        Thread.sleep(150)
        focused = false
        clipboard.setContents(StringSelection("from-notepad"), null)
        // Wait long enough for the poll to register the second change AND
        // beyond the original 2 s timer.
        Thread.sleep(2_500)
        assertEquals("from-notepad", readText(clipboard))
        mgr.stop()
    }

    @Test
    fun `stop halts polling`() {
        val clipboard = fakeClipboard()
        val mgr = DesktopClipboardManager(
            appScope = appScope,
            timeoutSecondsProvider = { 1 },
            clipboard = clipboard,
            isOriginatedFromUsOverride = { true },
            pollIntervalMsOverride = 50L,
        )
        mgr.start()
        mgr.stop()
        clipboard.setContents(StringSelection("post-stop"), null)
        Thread.sleep(1_400)
        // Polling stopped before the change, so no clear was ever scheduled.
        assertEquals("post-stop", readText(clipboard))
        mgr.stop()
    }
}
