// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The notification restarts tunnels with a token that is good for one tap, never with ids in its
 * intent: the activity is exported, so anything an intent carries can be forged. A wrong, old or
 * replayed token does nothing.
 */
class PendingAuthResumeTest {

    private val resume = PendingAuthResume()

    // ── The token ─────────────────────────────────────────────────────────────

    @Test
    fun `a token is 32 random bytes in URL-safe text`() {
        val token = resume.register("t1", "Web").token

        // 32 bytes in Base64 without padding: 43 characters.
        assertEquals(43, token.length)
        assertTrue(token.all { it.isLetterOrDigit() || it == '-' || it == '_' }, token)
    }

    @Test
    fun `every registration issues a different token`() {
        val first = resume.register("t1", "Web").token
        val second = resume.register("t2", "Db").token
        val otherProcess = PendingAuthResume().register("t1", "Web").token

        assertNotEquals(first, second)
        assertNotEquals(first, otherProcess, "nothing about a token can be guessed from another run")
    }

    @Test
    fun `the notice names every waiting tunnel in the order they were added, and hides them from a log`() {
        resume.register("t1", "Web")
        val notice = resume.register("t2", "Db")

        assertEquals(listOf("Web", "Db"), notice.labels)
        assertFalse(notice.toString().contains(notice.token), "the token must not reach a log")
        assertFalse(notice.toString().contains("Web"), "nor a tunnel name")
    }

    @Test
    fun `a tunnel registered twice is listed once, with its latest label`() {
        resume.register("t1", "Web")
        val notice = resume.register("t1", "Web renamed")

        assertEquals(listOf("Web renamed"), notice.labels)
    }

    // ── Consuming it ──────────────────────────────────────────────────────────

    @Test
    fun `the token is good once and gives the waiting ids`() {
        resume.register("t1", "Web")
        val token = resume.register("t2", "Db").token

        assertEquals(listOf("t1", "t2"), resume.consume(token))
        assertNull(resume.consume(token), "a replay does nothing")
    }

    @Test
    fun `a holder that registered nothing accepts nothing`() {
        assertNull(resume.consume("anything"))
        assertNull(resume.consume(""))
        assertNull(resume.consume(null))
        assertNull(resume.takeReady())
    }

    @Test
    fun `a wrong token does nothing and leaves the right one valid`() {
        val token = resume.register("t1", "Web").token

        assertNull(resume.consume("A".repeat(43)))
        assertNull(resume.consume(token.dropLast(1)))
        assertNull(resume.consume(token + "x"))
        assertNull(resume.consume(null))
        assertNull(resume.consume(""))
        assertNull(resume.ready.value, "nothing became ready")

        assertEquals(listOf("t1"), resume.consume(token), "the tunnel is still waiting for its owner")
    }

    @Test
    fun `a new registration voids the token of the previous notification`() {
        val old = resume.register("t1", "Web").token
        val current = resume.register("t2", "Db").token

        assertNull(resume.consume(old), "the old intent is dead")
        assertEquals(listOf("t1", "t2"), resume.consume(current), "the new one carries every waiting tunnel")
    }

    @Test
    fun `after a consume the holder is empty and starts afresh`() {
        val first = resume.register("t1", "Web").token
        resume.consume(first)

        val notice = resume.register("t2", "Db")

        assertEquals(listOf("Db"), notice.labels, "the consumed tunnel does not come back")
        assertNull(resume.consume(first))
        assertEquals(listOf("t2"), resume.consume(notice.token))
    }

    // ── Ready, until the list takes them ──────────────────────────────────────

    @Test
    fun `a consumed token makes the ids ready, and they are handed over once`() {
        val token = resume.register("t1", "Web").token
        assertNull(resume.ready.value, "nothing is ready before the tap")

        resume.consume(token)

        assertEquals(listOf("t1"), resume.ready.value)
        assertEquals(listOf("t1"), resume.takeReady())
        assertNull(resume.takeReady(), "two screens cannot start the same tunnels")
        assertNull(resume.ready.value)
    }

    @Test
    fun `two taps before the list took the first ones add up`() {
        resume.consume(resume.register("t1", "Web").token)
        resume.consume(resume.register("t2", "Db").token)

        assertEquals(listOf("t1", "t2"), resume.takeReady())
    }

    @Test
    fun `the same tunnel is not ready twice`() {
        resume.consume(resume.register("t1", "Web").token)
        resume.consume(resume.register("t1", "Web").token)

        assertEquals(listOf("t1"), resume.takeReady())
    }

    // ── The tap is the consent: waiting does not age, only a tap nobody followed up does ──

    private var nowMs = 5_000L
    private val timed = PendingAuthResume { nowMs }
    private val ttl = PendingAuthResume.READY_TTL_MS
    private val night = 9 * 60 * 60_000L

    @Test
    fun `a notification tapped in the morning restarts the tunnel that dropped at night`() {
        val token = timed.register("t1", "Web").token

        nowMs += night

        assertEquals(listOf("t1"), timed.consume(token), "the tap is the consent, however late")
        assertEquals(listOf("t1"), timed.ready.value)
    }

    @Test
    fun `waiting tunnels do not age, a newer report carries the older ones whatever their age`() {
        val old = timed.register("old", "Old").token
        nowMs += night
        val notice = timed.register("new", "New")

        assertEquals(listOf("Old", "New"), notice.labels, "the one that waited all night is still waiting")
        assertNull(timed.consume(old), "its notification was replaced by the newer one")
        assertEquals(listOf("old", "new"), timed.consume(notice.token))
    }

    @Test
    fun `dropping the expired never touches the waiting tunnels`() {
        val token = timed.register("t1", "Web").token
        nowMs += night

        timed.dropExpired()

        assertEquals(listOf("t1"), timed.consume(token))
    }

    @Test
    fun `the ready tunnels expire ten minutes after the tap, a launcher shortcut is not dropped for them`() {
        timed.consume(timed.register("t1", "Web").token)

        nowMs += ttl - 1
        timed.dropExpired()
        assertEquals(listOf("t1"), timed.ready.value, "still within the time")

        nowMs += 1
        timed.dropExpired()
        assertNull(timed.ready.value, "the screen that watches it sees no pending request any more")
    }

    @Test
    fun `the time of what is ready counts from the tap, not from the report`() {
        val token = timed.register("t1", "Web").token
        nowMs += night

        timed.consume(token)
        nowMs += ttl - 1

        timed.dropExpired()
        assertEquals(listOf("t1"), timed.ready.value, "ten minutes since the tap are not over")
    }

    @Test
    fun `ready tunnels that expired are not handed over even if nobody dropped them`() {
        timed.consume(timed.register("t1", "Web").token)

        nowMs += ttl

        assertNull(timed.takeReady())
        assertNull(timed.ready.value)
    }

    @Test
    fun `a second tap renews the time of what is ready`() {
        timed.consume(timed.register("t1", "Web").token)
        nowMs += ttl - 1
        timed.consume(timed.register("t2", "Db").token)

        nowMs += ttl - 1

        assertEquals(listOf("t1", "t2"), timed.takeReady())
    }
    // ── Tunnels that are not waiting any more ─────────────────────────────────

    @Test
    fun `a tunnel started again by any path leaves the waiting list`() {
        resume.register("t1", "Web")
        val token = resume.register("t2", "Db").token

        val change = resume.retainWaiting(setOf("t2", "unrelated"))

        assertTrue(change is WaitingChange.Narrowed, "one tunnel is still waiting: $change")
        assertEquals(listOf("t2"), resume.consume(token))
    }

    @Test
    fun `a partial prune says what the notification must name now, with the token it already carries`() {
        resume.register("t1", "Web")
        resume.register("t2", "Db")
        val token = resume.register("t3", "Cache").token

        val change = resume.retainWaiting(setOf("t2", "unrelated"))

        val notice = (change as WaitingChange.Narrowed).notice
        assertEquals(listOf("Db"), notice.labels, "only the tunnel that still waits is named")
        assertEquals(token, notice.token, "no tunnel was added: the intent already posted stays good")
        assertEquals(listOf("t2"), resume.consume(notice.token), "and the token restarts what remains")
    }

    @Test
    fun `a prune that drops nothing leaves the notification as it is`() {
        resume.register("t1", "Web")
        val token = resume.register("t2", "Db").token

        val change = resume.retainWaiting(setOf("t1", "t2", "unrelated"))

        assertEquals(WaitingChange.Unchanged, change)
        assertEquals(listOf("t1", "t2"), resume.consume(token))
    }

    @Test
    fun `a partial prune keeps the order of the tunnels that remain`() {
        resume.register("a", "A")
        resume.register("b", "B")
        resume.register("c", "C")
        resume.register("d", "D")

        val notice = (resume.retainWaiting(setOf("d", "b")) as WaitingChange.Narrowed).notice

        assertEquals(listOf("B", "D"), notice.labels)
    }

    @Test
    fun `the notice of a partial prune hides the labels and the token from a log`() {
        resume.register("t1", "Web")
        resume.register("t2", "Db")

        val change = resume.retainWaiting(setOf("t2")) as WaitingChange.Narrowed

        assertFalse(change.toString().contains("Db"))
        assertFalse(change.notice.toString().contains("Db"))
    }

    @Test
    fun `when the last waiting tunnel is gone the list is empty and the token is void`() {
        val token = resume.register("t1", "Web").token

        val change = resume.retainWaiting(emptySet())

        assertEquals(WaitingChange.Emptied, change, "the caller takes the notification away")
        assertNull(resume.consume(token), "nothing is left for it to restart")
    }

    @Test
    fun `a holder with nothing waiting is not reported as changed`() {
        assertEquals(WaitingChange.Unchanged, resume.retainWaiting(emptySet()))
        resume.consume(resume.register("t1", "Web").token)
        assertEquals(WaitingChange.Unchanged, resume.retainWaiting(emptySet()), "already empty since the tap")
    }

    @Test
    fun `a tunnel that is still in error stays and a later report names only the waiting ones`() {
        resume.register("gone", "Gone")
        resume.register("kept", "Kept")
        resume.retainWaiting(setOf("kept"))

        val notice = resume.register("next", "Next")

        assertEquals(listOf("Kept", "Next"), notice.labels)
    }
}