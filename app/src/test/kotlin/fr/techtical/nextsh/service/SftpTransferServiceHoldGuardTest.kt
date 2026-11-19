// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import fr.techtical.nextsh.ui.SourceGuardSupport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The service cannot run on the JVM, and what keeps a hold from crashing the app or from leaking a
 * session sits in a few lines of its source. This guard reads them (like the other source guards of
 * the app) so that a refactor cannot lose one silently. The counting of the holds is tested for
 * real in [PickerHoldsTest].
 */
class SftpTransferServiceHoldGuardTest {

    private val source = SourceGuardSupport.mainSource("service/SftpTransferService.kt")

    /** The text of `fun name(` up to the closing brace of its body. */
    private fun functionBody(name: String): String {
        val start = Regex("""fun $name\(""").find(source)?.range?.first
            ?: throw AssertionError("fun $name( not found: the guard cannot check it")
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(open, i + 1)
            }
        }
        throw AssertionError("unbalanced braces after fun $name(")
    }

    /** The branch of the `when` of onStartCommand that starts at [label], up to the next branch. */
    private fun branch(label: String, next: String): String {
        val body = functionBody("onStartCommand")
        val from = body.indexOf("$label ->")
        val to = body.indexOf("$next ->", from)
        assertTrue(from >= 0 && to > from, "branch $label not found in onStartCommand")
        return body.substring(from, to)
    }

    private val holdBranch get() = branch("ACTION_HOLD_FOR_PICKER", "ACTION_RELEASE_PICKER_HOLD")
    private val releaseBranch get() = branch("ACTION_RELEASE_PICKER_HOLD", "else")

    @Test
    fun `a hold goes to the foreground before it reads its extras`() {
        // startForegroundService promises a startForeground within seconds: a command with missing
        // extras that returned first would crash the app.
        val foreground = holdBranch.indexOf("startInForeground(")
        val extras = holdBranch.indexOf("getStringExtra(EXTRA_HOLD_ID)")
        assertTrue(foreground >= 0, "the hold branch never goes to the foreground")
        assertTrue(extras > foreground, "the hold branch reads its extras before it goes to the foreground")
    }

    @Test
    fun `a hold with no id stops the service if it has nothing else to do`() {
        assertTrue(holdBranch.contains("stopIfIdle(startId)"), holdBranch)
    }

    @Test
    fun `the release goes through the start id, so that a transfer asked right after keeps the service`() {
        assertTrue(releaseBranch.contains("stopIfIdle(startId)"), releaseBranch)
        assertFalse(Regex("""\bstopSelf\(\)""").containsMatchIn(releaseBranch), "a bare stopSelf() would stop the transfer too")
        assertTrue(functionBody("stopIfIdle").contains("stopSelfResult(startId)"))
    }

    @Test
    fun `the release never goes to the foreground, a fresh instance has nothing to show`() {
        // The service may have been destroyed and recreated by this very command: it would stay
        // started, in the foreground, with a notification for a hold that does not exist.
        assertFalse(releaseBranch.contains("startInForeground("), releaseBranch)
        assertTrue(releaseBranch.contains("holds.release("), releaseBranch)
    }

    @Test
    fun `the references of the holds are given back when the service is destroyed or times out`() {
        assertTrue(functionBody("onDestroy").contains("holds.releaseAll()"))
        assertTrue(functionBody("onTimeout").contains("holds.releaseAll()"))
    }

    @Test
    fun `the service only stops without a hold, whichever way it stops`() {
        val idle = functionBody("isIdle")
        assertTrue(idle.contains("holds.isEmpty()"), idle)
        assertTrue(idle.contains("draining.get()"), idle)
        assertTrue(idle.contains("runningTransfers.isEmpty()"), idle)
        assertTrue(idle.contains("getQueued().isEmpty()"), idle)
        // The end of a drain, the cancellation of a transfer, the guard of a hold and the swipe of the
        // task all stop through stopIfIdle, which asks isIdle.
        assertTrue(functionBody("stopIfIdle").contains("isIdle()"))
        assertTrue(functionBody("onDrainEnded").contains("holds.isEmpty()"))
        assertTrue(functionBody("onDrainEnded").contains("stopIfIdle()"))
        assertTrue(branch("ACTION_CANCEL_TRANSFER", "ACTION_HOLD_FOR_PICKER").contains("stopIfIdle(startId)"))
        assertTrue(functionBody("onTaskRemoved").contains("stopIfIdle()"))
    }

    @Test
    fun `a guard that expires a hold lets the service look whether it can stop, on the main thread`() {
        // The declaration runs up to the next property of the class (the lambdas hold parentheses).
        val declaration = source.substringAfter("private val holds = PickerHolds(", "").substringBefore("@Volatile")
        assertTrue(
            declaration.contains("onExpired = { mainHandler.post { stopIfIdle() } }"),
            "the expiry runs on the thread of the guard, or does not reach stopIfIdle",
        )
        assertTrue(source.contains("Handler(Looper.getMainLooper())"), "the handler is not the one of the main thread")
        assertEquals(1, Regex("""\bPickerHolds\(""").findAll(source).count())
    }

    // ── Who decides, and on which thread ─────────────────────────────────────────

    @Test
    fun `the end of a drain is decided on the main thread, not on the IO thread of the drain`() {
        // On the IO thread it crossed a command being handled on the main thread: a hold lost, an
        // orphan notification of the picker, or startForegroundService() without its startForeground.
        val drain = functionBody("processQueue")
        assertTrue(drain.contains("withContext(Dispatchers.Main) { onDrainEnded() }"), drain)
        assertFalse(drain.contains("stopSelf"), "the drain stops the service itself")
        assertFalse(drain.contains("stopIfIdle"), "the drain stops the service itself")
        assertFalse(drain.contains("showNotification("), "the drain notifies the hold itself")
        assertFalse(drain.contains("holds.isEmpty()"), "the drain decides on the holds itself")
    }

    @Test
    fun `a drain that was cancelled decides nothing, the service is gone`() {
        assertTrue(functionBody("processQueue").contains("isActive"))
    }

    @Test
    fun `a drain that ends leaves the new drain of a command handled meanwhile to decide`() {
        val ended = functionBody("onDrainEnded")
        assertTrue(ended.contains("draining.get()"), ended)
        // Before the notification of the hold: it would replace the progress of the new drain.
        assertTrue(ended.indexOf("draining.get()") < ended.indexOf("showNotification("), ended)
    }

    @Test
    fun `the service never stops with a bare stopSelf, except when the system is about to kill it`() {
        // stopSelf() stops whatever command is waiting: stopSelfResult(startId) refuses when a newer
        // one is. Only onTimeout stops without condition, the system gives it a few seconds.
        val bare = Regex("""\bstopSelf\(\)""").findAll(source).count()
        assertEquals(1, bare, "only onTimeout may call stopSelf()")
        assertTrue(functionBody("onTimeout").contains("stopSelf()"))
    }

    @Test
    fun `a stop goes through the last start id the service received`() {
        assertTrue(functionBody("onStartCommand").contains("lastStartId = startId"))
        assertTrue(source.contains("private fun stopIfIdle(startId: Int = lastStartId)"))
        assertTrue(functionBody("stopIfIdle").contains("stopSelfResult(startId)"))
    }

    // ── A foreground the system refuses ──────────────────────────────────────────

    @Test
    fun `a foreground the system refuses is caught, in a hold and in a transfer`() {
        // ForegroundServiceStartNotAllowedException (quota of dataSync used up) is an IllegalStateException.
        val foreground = functionBody("startInForeground")
        assertTrue(foreground.contains("catch (e: IllegalStateException)"), foreground)
        assertTrue(foreground.contains("false"), foreground)
        // A hold that got no foreground takes no hold, and the service stops if it has nothing else to do.
        assertTrue(holdBranch.contains("!foreground"), holdBranch)
        assertTrue(holdBranch.indexOf("!foreground") < holdBranch.indexOf("holds.acquire("), holdBranch)
        // A transfer that got no foreground does not start: it fails, with a text the browser can say.
        val transfer = functionBody("onStartCommand").substringAfter("else -> {")
        assertTrue(transfer.contains("if (!startInForeground("), transfer)
        assertTrue(transfer.contains("R.string.sftp_transfer_start_refused"), transfer)
        assertTrue(transfer.indexOf("if (!startInForeground(") < transfer.indexOf("processQueue()"), transfer)
    }

    @Test
    fun `the notification of a refused foreground is not kept as the current one`() {
        // Kept before the call, it would be the one a later hold reuses during a drain, never shown.
        val foreground = functionBody("startInForeground")
        assertTrue(foreground.indexOf("ServiceCompat.startForeground(") < foreground.indexOf("currentNotification = notification"), foreground)
    }

    // ── A task that is swiped away ───────────────────────────────────────────────

    @Test
    fun `swiping the app away gives the holds back, the transfers carry on`() {
        val swiped = functionBody("onTaskRemoved")
        assertTrue(swiped.contains("holds.releaseAll()"), swiped)
        assertTrue(swiped.contains("stopIfIdle()"), swiped)
        // The transfers are not touched: the service does not stop with the task.
        assertFalse(swiped.contains("runningTransfers"), swiped)
        assertFalse(swiped.contains("failAllUnfinished"), swiped)
    }

    @Test
    fun `a decision handed to the main thread does not outlive the service`() {
        assertTrue(functionBody("onDestroy").contains("mainHandler.removeCallbacksAndMessages(null)"))
    }
}
