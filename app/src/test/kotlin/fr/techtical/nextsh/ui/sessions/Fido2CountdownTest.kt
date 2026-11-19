// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.sessions

import fr.techtical.nextsh.core.ssh.Fido2ChallengeRequest
import fr.techtical.nextsh.core.ssh.Fido2Prompt
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The countdown of the FIDO2 sheet reads the coordinator's deadline and nothing else: it counts
 * down to zero and stops, it does not end the prompt (the coordinator does, at that deadline).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fido2CountdownTest {

    private fun prompt(deadlineMs: Long) = Fido2Prompt(
        request = Fido2ChallengeRequest(signingData = ByteArray(4), rpId = "ssh:", keyType = SkKeyType.SK_ED25519),
        deadlineMs = deadlineMs,
    )

    @Test
    fun `it reports the time left from the deadline, every tick`() = runTest {
        val ticks = mutableListOf<Long>()
        val job = launch { runFido2Countdown(prompt(deadlineMs = 1_000), { testScheduler.currentTime }) { ticks += it } }

        runCurrent()
        assertEquals(listOf(1_000L), ticks, "first reading at once")
        advanceTimeBy(FIDO2_COUNTDOWN_TICK_MS)
        runCurrent()
        assertEquals(listOf(1_000L, 900L), ticks)
        job.cancel()
    }

    @Test
    fun `it ends at zero and does not end the prompt`() = runTest {
        // The function has no way to cancel the prompt: reaching zero only returns.
        val ticks = mutableListOf<Long>()
        val job = launch { runFido2Countdown(prompt(deadlineMs = 300), { testScheduler.currentTime }) { ticks += it } }

        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(job.isCompleted && !job.isCancelled)
        assertEquals(listOf(300L, 200L, 100L, 0L), ticks)
    }

    @Test
    fun `a deadline already passed reports zero once and returns`() = runTest {
        advanceTimeBy(5_000)
        val ticks = mutableListOf<Long>()

        runFido2Countdown(prompt(deadlineMs = 1_000), { testScheduler.currentTime }) { ticks += it }

        assertEquals(listOf(0L), ticks)
    }

    @Test
    fun `the time left is the deadline minus the clock, whatever the deadline`() = runTest {
        // An extension to 110 s, read 50 s in, shows 60 s left: the sheet keys its countdown on
        // the deadline, so an extension restarts it on the new one.
        val ticks = mutableListOf<Long>()
        val job = launch { runFido2Countdown(prompt(deadlineMs = 110_000), { testScheduler.currentTime }) { ticks += it } }

        advanceTimeBy(50_000)
        runCurrent()

        assertEquals(60_000L, ticks.last())
        job.cancel()
    }
}
