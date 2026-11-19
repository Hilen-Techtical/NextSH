// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The launcher shortcut that waits for the unlock lives in the app and not in the activity: it
 * survives a recreation, is spent once, is replaced by a newer intent, and expires if it was left
 * on the lock screen.
 */
class PendingShortcutTest {

    private var nowMs = 1_000L
    private val pending = PendingShortcut { nowMs }

    private val host = ShortcutRequest.ConnectHost("h1")
    private val otherHost = ShortcutRequest.ConnectHost("h2")
    private val tunnel = ShortcutRequest.StartTunnel("t1")

    // ── Offer and consume ─────────────────────────────────────────────────────

    @Test
    fun `nothing is pending at first`() {
        assertNull(pending.request.value)
    }

    @Test
    fun `an offered request is pending until it is consumed`() {
        pending.offer(host)

        assertEquals(host, pending.request.value)
    }

    @Test
    fun `consuming the pending request spends it, once`() {
        pending.offer(host)

        assertTrue(pending.consume(host))

        assertNull(pending.request.value)
        assertFalse(pending.consume(host), "a second consume does not act a second time")
    }

    @Test
    fun `consuming a request that is not the pending one changes nothing`() {
        pending.offer(host)

        assertFalse(pending.consume(otherHost))
        assertFalse(pending.consume(tunnel))

        assertEquals(host, pending.request.value, "the pending one stays for its own consumer")
        assertTrue(pending.consume(host))
    }

    @Test
    fun `consuming with nothing pending is false`() {
        assertFalse(pending.consume(host))
    }

    // ── Replace and clear ─────────────────────────────────────────────────────

    @Test
    fun `a newer intent replaces the request that is pending, and only the newest acts`() {
        pending.offer(host)
        pending.offer(tunnel)

        assertEquals(tunnel, pending.request.value)
        assertFalse(pending.consume(host), "the replaced one is gone")
        assertTrue(pending.consume(tunnel))
        assertNull(pending.request.value)
    }

    @Test
    fun `an intent without a valid shortcut clears the pending request`() {
        pending.offer(host)

        pending.offer(null)

        assertNull(pending.request.value)
        assertFalse(pending.consume(host))
    }

    @Test
    fun `a consumer that saw the old request cannot spend the one that replaced it`() {
        pending.offer(host)
        val seenByTheEffect = pending.request.value!!

        pending.offer(otherHost) // an intent arrives between the composition and the effect

        assertFalse(pending.consume(seenByTheEffect))
        assertEquals(otherHost, pending.request.value, "the newer one still waits")
    }

    // ── Recreation ────────────────────────────────────────────────────────────

    @Test
    fun `the request is still there for the next activity, as after a recreation at the lock screen`() {
        pending.offer(host)

        // The activity is destroyed and a new one starts: it reads the same holder.
        val seenByTheNextActivity = pending.request.value

        assertEquals(host, seenByTheNextActivity)
        assertTrue(pending.consume(host))
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    @Test
    fun `a request that waited less than the time to live still acts`() {
        pending.offer(host)
        nowMs += PendingShortcut.TTL_MS - 1

        assertTrue(pending.consume(host))
    }

    @Test
    fun `a request left on the lock screen does not act after the time to live, and is cleared`() {
        pending.offer(host)
        nowMs += PendingShortcut.TTL_MS

        assertFalse(pending.consume(host), "the unlock hours later acts on nothing")

        assertNull(pending.request.value)
    }

    @Test
    fun `dropExpired forgets an expired request, not a fresh one`() {
        pending.offer(host)
        nowMs += PendingShortcut.TTL_MS - 1
        pending.dropExpired()
        assertEquals(host, pending.request.value, "still fresh")

        nowMs += 1
        pending.dropExpired()

        assertNull(pending.request.value)
    }

    @Test
    fun `dropExpired with nothing pending does nothing`() {
        pending.dropExpired()

        assertNull(pending.request.value)
    }

    @Test
    fun `offering again renews the time to live`() {
        pending.offer(host)
        nowMs += PendingShortcut.TTL_MS - 1

        pending.offer(host) // the user taps the same shortcut again
        nowMs += PendingShortcut.TTL_MS - 1

        assertTrue(pending.consume(host))
    }

    @Test
    fun `the time to live is two minutes`() {
        assertEquals(120_000L, PendingShortcut.TTL_MS)
    }
}
