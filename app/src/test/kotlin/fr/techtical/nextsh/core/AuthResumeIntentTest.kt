// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import android.content.Intent
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the exported activity does with the token of the "authentication required" notification:
 * the extra is always taken out of the intent, a forged token does nothing, and the notification is
 * cancelled only when the token was good.
 */
class AuthResumeIntentTest {

    private var nowMs = 1_000L
    private val resume = PendingAuthResume { nowMs }
    private var cancelled = 0

    private fun intentCarrying(token: String?): Intent = mockk<Intent>().also {
        every { it.getStringExtra(PendingAuthResume.EXTRA_TOKEN) } returns token
        justRun { it.removeExtra(PendingAuthResume.EXTRA_TOKEN) }
    }

    private fun spend(intent: Intent?) = spendAuthResumeToken(intent, resume) { cancelled++ }

    @Test
    fun `a good token makes the tunnels ready and cancels the notification`() {
        val token = resume.register("t1", "Web").token
        val intent = intentCarrying(token)

        assertTrue(spend(intent))

        assertEquals(listOf("t1"), resume.ready.value)
        assertEquals(1, cancelled)
        verify(exactly = 1) { intent.removeExtra(PendingAuthResume.EXTRA_TOKEN) }
    }

    @Test
    fun `the extra is stripped from the intent whatever the token was`() {
        resume.register("t1", "Web")
        val forged = intentCarrying("A".repeat(43))

        spend(forged)

        verify(exactly = 1) { forged.removeExtra(PendingAuthResume.EXTRA_TOKEN) }
    }

    @Test
    fun `a forged token does nothing, the notification stays and the tunnels are not ready`() {
        val token = resume.register("t1", "Web").token

        assertFalse(spend(intentCarrying("forged")))
        assertFalse(spend(intentCarrying("")))

        assertNull(resume.ready.value)
        assertEquals(0, cancelled)
        assertEquals(listOf("t1"), resume.consume(token), "the real token is still good for its owner")
    }

    @Test
    fun `a replayed token does nothing the second time`() {
        val token = resume.register("t1", "Web").token
        assertTrue(spend(intentCarrying(token)))
        resume.takeReady()

        assertFalse(spend(intentCarrying(token)))

        assertNull(resume.ready.value, "nothing started again")
        assertEquals(1, cancelled, "cancelled once only")
    }

    @Test
    fun `the token of an older notification does nothing`() {
        val old = resume.register("t1", "Web").token
        resume.register("t2", "Db")

        assertFalse(spend(intentCarrying(old)))

        assertNull(resume.ready.value)
        assertEquals(0, cancelled)
    }

    @Test
    fun `an intent without the extra is left alone, as is a missing intent`() {
        resume.register("t1", "Web")
        val plain = intentCarrying(null)

        assertFalse(spend(plain))
        assertFalse(spend(null))

        verify(exactly = 0) { plain.removeExtra(any<String>()) }
        assertEquals(0, cancelled)
        assertNull(resume.ready.value)
    }

    @Test
    fun `a request that nobody followed up is forgotten when any intent arrives, a launcher shortcut included`() {
        resume.consume(resume.register("t1", "Web").token)
        assertEquals(listOf("t1"), resume.ready.value)
        nowMs += PendingAuthResume.READY_TTL_MS

        // A launcher shortcut: no token at all.
        spend(intentCarrying(null))

        assertNull(resume.ready.value, "the shortcut will not meet a stale resume")
    }

    @Test
    fun `a notification tapped in the morning is honoured, only a validated request ages`() {
        val token = resume.register("t1", "Web").token
        nowMs += 9 * 60 * 60_000L

        assertTrue(spend(intentCarrying(token)), "the tap is the consent, however late")

        assertEquals(listOf("t1"), resume.ready.value)
        assertEquals(1, cancelled)
    }
}