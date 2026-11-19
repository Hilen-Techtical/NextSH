// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.content.res.Resources
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AuthResumeNotice
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.WaitingChange
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The words of the "authentication required" notification come from resources (so they follow the
 * device language), the locked screen version names no tunnel, and a notification is posted only
 * when the system allows it (the guard is the one of the "stopped" alerts, see
 * [DeletedTunnelNotifierTest]). The posting itself needs a device (NotificationCompat.Builder is a
 * framework class), it is checked by hand.
 */
class AuthRequiredNotifierTest {

    private lateinit var resources: Resources

    @BeforeEach
    fun setUp() {
        resources = mockk()
        every { resources.getString(R.string.auth_required_title) } returns "Authentification requise"
        every { resources.getString(R.string.auth_required_public_text) } returns "Des tunnels attendent."
    }

    @Test
    fun `one tunnel is named in the text, with no lines`() {
        every { resources.getString(R.string.auth_required_text_single, "Web") } returns
            "\"Web\" attend votre authentification."

        val message = AuthRequiredMessage.of(resources, listOf("Web"))

        assertEquals("Authentification requise", message.title)
        assertEquals("\"Web\" attend votre authentification.", message.text)
        assertTrue(message.lines.isEmpty(), "the text already names the only tunnel")
        assertNull(message.more)
    }

    @Test
    fun `several tunnels share the text with a count and an inbox line each`() {
        every { resources.getQuantityString(R.plurals.auth_required_text_count, 3, 3) } returns
            "3 tunnels attendent votre authentification."

        val message = AuthRequiredMessage.of(resources, listOf("A", "B", "C"))

        assertEquals("3 tunnels attendent votre authentification.", message.text)
        assertEquals(listOf("A", "B", "C"), message.lines)
        assertNull(message.more)
    }

    @Test
    fun `a long list is cut and the rest is summarized`() {
        val labels = (1..7).map { "T$it" }
        every { resources.getQuantityString(R.plurals.auth_required_text_count, 7, 7) } returns "7 tunnels"
        every { resources.getString(R.string.tunnel_notification_more, 2) } returns "+2 autre(s)"

        val message = AuthRequiredMessage.of(resources, labels)

        assertEquals(AuthRequiredMessage.MAX_LINES, message.lines.size)
        assertEquals(labels.take(AuthRequiredMessage.MAX_LINES), message.lines)
        assertEquals("+2 autre(s)", message.more)
    }

    @Test
    fun `the locked screen version names no tunnel, for one or for several`() {
        every { resources.getString(R.string.auth_required_text_single, "prod-db") } returns "\"prod-db\" attend."
        every { resources.getQuantityString(R.plurals.auth_required_text_count, 2, 2) } returns "2 tunnels attendent."

        val single = AuthRequiredMessage.of(resources, listOf("prod-db"))
        val several = AuthRequiredMessage.of(resources, listOf("prod-db", "staging-web"))

        for (message in listOf(single, several)) {
            assertEquals("Des tunnels attendent.", message.publicText)
            assertFalse(message.publicText.contains("prod-db"))
            assertFalse(message.publicText.contains("staging-web"))
        }
    }

    @Test
    fun `every text is read from resources, none is built in code`() {
        every { resources.getString(R.string.auth_required_text_single, "Web") } returns "texte"

        AuthRequiredMessage.of(resources, listOf("Web"))

        verify(exactly = 1) { resources.getString(R.string.auth_required_title) }
        verify(exactly = 1) { resources.getString(R.string.auth_required_public_text) }
        verify(exactly = 1) { resources.getString(R.string.auth_required_text_single, "Web") }
    }

    @Test
    fun `the notification is posted only when the system allows it`() {
        // The guard is shared with the "stopped" alerts: from Android 13 it needs the permission.
        assertFalse(mayPostNotifications(sdkInt = 33) { false })
        assertTrue(mayPostNotifications(sdkInt = 33) { true })
        assertTrue(mayPostNotifications(sdkInt = 29) { false })
    }

    @Test
    fun `the channel is its own, apart from the silent tunnel one`() {
        assertEquals("auth_required_channel", AUTH_REQUIRED_CHANNEL_ID)
        assertTrue(AUTH_REQUIRED_CHANNEL_ID != TUNNEL_CHANNEL_ID)
    }

    @Test
    fun `the message hides the tunnel names from a log`() {
        every { resources.getString(R.string.auth_required_text_single, "prod-db") } returns "\"prod-db\" attend."

        val message = AuthRequiredMessage.of(resources, listOf("prod-db"))

        assertFalse(message.toString().contains("prod-db"), message.toString())
        assertFalse(message.text.isEmpty())
    }

    // ── The notification follows the list of tunnels that wait ─────────────────

    /** What the notifier does to the system, recorded instead of done. */
    private class Recorder(resume: PendingAuthResume) : AndroidAuthRequiredNotifier(mockk(relaxed = true), resume) {
        var allowed = true
        var showing = true

        /** The labels of each posting, in order. Several threads write it in the test of the race. */
        val posted: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
        val tokens: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var cancelled = 0

        /** Runs at the start of each posting, before it is recorded: a test holds a posting here. */
        @Volatile
        var beforeShow: () -> Unit = {}

        override fun mayPost() = allowed
        override fun isShowing() = showing
        override fun show(notice: AuthResumeNotice) {
            beforeShow()
            posted += notice.labels
            tokens += notice.token
        }

        override fun cancel() {
            cancelled++
        }
    }

    @Test
    fun `a reported tunnel is posted with every tunnel that waits`() {
        val notifier = Recorder(PendingAuthResume())

        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")

        assertEquals(listOf(listOf("Web"), listOf("Web", "Db")), notifier.posted)
    }

    @Test
    fun `nothing is registered nor posted when the system does not allow notifications`() {
        val resume = PendingAuthResume()
        val notifier = Recorder(resume).also { it.allowed = false }

        notifier.notifyAuthRequired("t1", "Web")

        assertTrue(notifier.posted.isEmpty())
        assertEquals(WaitingChange.Unchanged, resume.retainWaiting(emptySet()), "nothing waits")
    }

    @Test
    fun `when some of the tunnels leave, the notification is posted again with the ones that remain`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.notifyAuthRequired("t3", "Cache")
        notifier.posted.clear()

        notifier.retainWaiting(setOf("t2"))

        assertEquals(listOf(listOf("Db")), notifier.posted, "the count and the names are those of what remains")
        assertEquals(0, notifier.cancelled, "it is updated, not taken away")
    }

    @Test
    fun `the update keeps the token of the notification that is on screen`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        val onScreen = notifier.tokens.last()

        notifier.retainWaiting(setOf("t2"))

        assertEquals(onScreen, notifier.tokens.last())
    }

    @Test
    fun `a prune that drops nothing posts nothing`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.posted.clear()

        notifier.retainWaiting(setOf("t1", "t2"))
        notifier.retainWaiting(setOf("t1", "t2", "other"))

        assertTrue(notifier.posted.isEmpty())
        assertEquals(0, notifier.cancelled)
    }

    @Test
    fun `when the last tunnel leaves, the notification goes and nothing is posted`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.posted.clear()

        notifier.retainWaiting(emptySet())

        assertEquals(1, notifier.cancelled)
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a notification the user dismissed is not brought back for a smaller list`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.posted.clear()
        notifier.showing = false

        notifier.retainWaiting(setOf("t2"))

        assertTrue(notifier.posted.isEmpty(), "it would sound again for something the user already dismissed")
    }

    @Test
    fun `a notice that was computed first is never shown after a newer one`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.posted.clear()
        val inShow = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = AtomicBoolean(true)
        notifier.beforeShow = {
            if (first.getAndSet(false)) {
                inShow.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }

        // The prune computes "Db" alone and is on its way to show it...
        val pruning = thread { notifier.retainWaiting(setOf("t2")) }
        assertTrue(inShow.await(5, TimeUnit.SECONDS))
        // ...when another tunnel is reported: it must not overtake it.
        val reporting = thread { notifier.notifyAuthRequired("t3", "Cache") }
        reporting.join(300)
        assertTrue(reporting.isAlive, "the report waits for the posting in progress")
        release.countDown()
        pruning.join(5_000)
        reporting.join(5_000)

        assertEquals(listOf(listOf("Db"), listOf("Db", "Cache")), notifier.posted.toList(), "the last word is the newest state")
    }

    @Test
    fun `the notification taken away by an emptied list is not taken from under a report that follows`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.posted.clear()

        notifier.retainWaiting(emptySet())
        notifier.notifyAuthRequired("t2", "Db")

        assertEquals(1, notifier.cancelled)
        assertEquals(listOf(listOf("Db")), notifier.posted.toList(), "and the list starts afresh")
    }

    @Test
    fun `the list that remains is the one a later report adds to`() {
        val notifier = Recorder(PendingAuthResume())
        notifier.notifyAuthRequired("t1", "Web")
        notifier.notifyAuthRequired("t2", "Db")
        notifier.retainWaiting(setOf("t2"))
        notifier.posted.clear()

        notifier.notifyAuthRequired("t3", "Cache")

        assertEquals(listOf(listOf("Db", "Cache")), notifier.posted)
    }
}