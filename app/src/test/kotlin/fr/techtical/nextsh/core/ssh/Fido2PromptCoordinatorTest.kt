// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The FIDO2 prompt must work whichever screen starts the connection: the callback is installed
 * once at app start, shows one prompt at a time, and wipes what it holds when the prompt ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fido2PromptCoordinatorTest {

    private var coordinator = Fido2PromptCoordinator()

    /**
     * runTest with a coordinator whose clock is the virtual time of the test: the deadline and the
     * coroutine timeouts then count the same milliseconds.
     */
    private fun coordinatorTest(block: suspend TestScope.() -> Unit) = runTest {
        coordinator = Fido2PromptCoordinator { testScheduler.currentTime }
        block()
    }

    /** The request of the prompt on screen, or null. */
    private fun onScreen(): Fido2ChallengeRequest? = coordinator.state.value?.request

    private fun request(fill: Byte = 9) = Fido2ChallengeRequest(
        signingData = ByteArray(32) { fill },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    private fun signature() = SkSshSignature.create(
        keyType = SkKeyType.SK_ED25519,
        rawSignature = ByteArray(64) { 7 },
        flags = SkSshSignature.FLAG_USER_PRESENT,
        counter = 1u,
    )

    private fun isWiped(data: ByteArray) = data.all { it == 0.toByte() }

    // ── Installation ──────────────────────────────────────────────────────────

    @Test
    fun `install sets the FIDO2 callback of the session manager`() = coordinatorTest {
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?>()
        every { manager.fido2ChallengeCallback = capture(installed) } just Runs

        coordinator.install(manager)

        verify(exactly = 1) { manager.fido2ChallengeCallback = any() }
        assertTrue(installed.isCaptured)
    }

    @Test
    fun `the installed callback shows the prompt and returns the signature`() = coordinatorTest {
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?>()
        every { manager.fido2ChallengeCallback = capture(installed) } just Runs
        coordinator.install(manager)
        val request = request()
        val signature = signature()

        val response = async { installed.captured(request) }
        runCurrent()
        assertSame(request, onScreen())
        coordinator.complete(request, signature)

        val signed = response.await() as Fido2ChallengeResponse.Signed
        assertSame(signature, signed.signature)
        assertNull(onScreen())
    }

    // ── One prompt, every way it ends ─────────────────────────────────────────

    @Test
    fun `no prompt is shown before a request`() {
        assertNull(onScreen())
    }

    @Test
    fun `a signed response reaches the waiting connection and clears the prompt`() = coordinatorTest {
        val request = request()
        val signature = signature()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        assertSame(request, onScreen())
        coordinator.complete(request, signature)

        val signed = response.await() as Fido2ChallengeResponse.Signed
        assertSame(signature, signed.signature)
        assertNull(onScreen())
        // The data to sign is zeroed, the signature belongs to the connection (not closed here).
        assertTrue(isWiped(request.signingData), "signing data must be zeroed")
        assertTrue(signature.rawSignature.any { it != 0.toByte() }, "the signature is handed over intact")
    }

    @Test
    fun `cancel returns Cancelled, clears the prompt and zeroes the data`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        coordinator.cancel(request)

        assertEquals(Fido2ChallengeResponse.Cancelled, response.await())
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `an unanswered prompt times out after 60 seconds, clears and zeroes`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS - 1)
        runCurrent()
        assertSame(request, onScreen(), "still waiting just before the timeout")
        assertTrue(!isWiped(request.signingData))

        advanceTimeBy(2)
        runCurrent()

        assertNull(response.await())
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData), "signing data must be zeroed on timeout")
    }

    @Test
    fun `a cancelled caller clears the prompt and zeroes the data`() = coordinatorTest {
        val request = request()

        val job = launch { coordinator.prompt(request) }
        runCurrent()
        assertSame(request, onScreen())
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `a key error returns KeyError, clears the prompt and zeroes the data`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        coordinator.failKey(request, HwKeyError.PinBlocked)

        assertEquals(Fido2ChallengeResponse.KeyError(HwKeyError.PinBlocked), response.await())
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `a key error of a request that is not on screen is ignored`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        firstResponse.await()
        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()

        coordinator.failKey(first, HwKeyError.KeyRemoved) // the sheet of the first prompt, late

        assertSame(second, onScreen())
        assertTrue(!secondResponse.isCompleted)
        coordinator.cancel(second)
        secondResponse.await()
    }

    @Test
    fun `a prompt can follow another one`() = coordinatorTest {
        val first = request()
        val second = request(fill = 5)

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        assertEquals(Fido2ChallengeResponse.Cancelled, firstResponse.await())

        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()
        assertSame(second, onScreen())
        val signature = signature()
        coordinator.complete(second, signature)
        assertSame(signature, (secondResponse.await() as Fido2ChallengeResponse.Signed).signature)
    }

    // ── Concurrent requests ───────────────────────────────────────────────────

    @Test
    fun `a second request waits for the first one, then gets its own prompt`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()

        // Only the first one is on screen; the second one is queued, not shown, not dropped.
        assertSame(first, onScreen())
        assertTrue(!secondResponse.isCompleted)

        coordinator.cancel(first)
        runCurrent()
        assertEquals(Fido2ChallengeResponse.Cancelled, firstResponse.await())
        assertSame(second, onScreen())

        val signature = signature()
        coordinator.complete(second, signature)
        assertSame(signature, (secondResponse.await() as Fido2ChallengeResponse.Signed).signature)
        assertNull(onScreen())
    }

    @Test
    fun `a queued request gets its full 60 seconds from the moment it is shown`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()

        advanceTimeBy(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS - 1_000)
        runCurrent()
        coordinator.cancel(first)
        runCurrent()
        assertEquals(Fido2ChallengeResponse.Cancelled, firstResponse.await())
        assertSame(second, onScreen())

        // 59 s after the second prompt appeared: still waiting.
        advanceTimeBy(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS - 1_000)
        runCurrent()
        assertSame(second, onScreen())

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(secondResponse.await())
        assertNull(onScreen())
        assertTrue(isWiped(second.signingData))
    }

    @Test
    fun `a queued request that is cancelled is never shown`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()
        secondJob.cancel()
        runCurrent()

        assertSame(first, onScreen(), "the waiting caller leaving does not touch the shown prompt")
        coordinator.cancel(first)
        assertEquals(Fido2ChallengeResponse.Cancelled, firstResponse.await())
        runCurrent()
        assertNull(onScreen(), "the cancelled request is not shown afterwards")
    }

    // ── The deadline: the one clock of the prompt ─────────────────────────────

    private fun deadlineOnScreen(): Long = coordinator.state.value!!.deadlineMs

    @Test
    fun `the prompt carries a deadline 60 seconds after it appeared`() = coordinatorTest {
        advanceTimeBy(5_000)
        val request = request()

        val job = launch { coordinator.prompt(request) }
        runCurrent()

        assertEquals(5_000L + Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, deadlineOnScreen())
        assertSame(request, onScreen())
        job.cancel()
    }

    @Test
    fun `the time left follows the deadline, so a sheet recreated mid-prompt shows the real time`() = coordinatorTest {
        val job = launch { coordinator.prompt(request()) }
        runCurrent()
        advanceTimeBy(20_000)
        runCurrent()

        // What a sheet recreated by a rotation reads: not a counter back at 60 s.
        val prompt = coordinator.state.value!!
        assertEquals(40_000L, prompt.remainingMs(coordinator.nowMs()))
        assertEquals(0L, prompt.remainingMs(prompt.deadlineMs + 5_000), "never negative")
        job.cancel()
    }

    @Test
    fun `a PIN entry extends the deadline and the prompt outlives the original one`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()

        // The key asks for a PIN 50 s in: the user gets 60 s from there.
        advanceTimeBy(50_000)
        runCurrent()
        coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(110_000L, deadlineOnScreen())

        // Past the original 60 s, still waiting for the PIN.
        advanceTimeBy(30_000)
        runCurrent()
        assertSame(request, onScreen())
        assertTrue(!response.isCompleted)

        // Answered in time.
        val signature = signature()
        coordinator.complete(request, signature)
        assertSame(signature, (response.await() as Fido2ChallengeResponse.Signed).signature)
    }

    @Test
    fun `an extended prompt still times out at its new deadline`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(50_000)
        runCurrent()
        coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)

        advanceTimeBy(59_999)
        runCurrent()
        assertSame(request, onScreen(), "just before the extended deadline")

        advanceTimeBy(2)
        runCurrent()

        assertNull(response.await())
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `however often it is extended the deadline stops at the ceiling counted from the first display`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        val ceiling = Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS

        // A PIN request every 50 s keeps the prompt alive, up to the ceiling and not a moment more.
        var elapsed = 0L
        while (elapsed < 250_000) {
            advanceTimeBy(50_000)
            runCurrent()
            elapsed += 50_000
            coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
        }
        assertEquals(ceiling, deadlineOnScreen(), "250 s + 60 s would be 310 s: cut at the ceiling")

        advanceTimeBy(40_000)
        runCurrent()
        coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(ceiling, deadlineOnScreen(), "extending again does not move it")

        advanceTimeBy(9_999)
        runCurrent()
        assertSame(request, onScreen(), "just before the ceiling")
        advanceTimeBy(2)
        runCurrent()

        assertNull(response.await())
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `the ceiling counts from the display of each prompt`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        firstResponse.await()
        advanceTimeBy(200_000)

        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()
        // 200 s into the run, the second prompt gets a whole new window of its own.
        assertEquals(200_000L + Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, deadlineOnScreen())
        advanceTimeBy(30_000)
        runCurrent()
        coordinator.extendDeadline(second, 10 * 60_000L)
        assertEquals(200_000L + Fido2PromptCoordinator.MAX_PROMPT_TOTAL_MS, deadlineOnScreen())
        secondJob.cancel()
    }

    @Test
    fun `the request stays the same object when the deadline moves`() = coordinatorTest {
        // The host keys the sheet on the request: a new object would recreate the sheet mid-PIN.
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(10_000)

        coordinator.extendDeadline(request, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)

        assertSame(request, onScreen())
        assertEquals(70_000L, deadlineOnScreen())
        job.cancel()
    }

    @Test
    fun `an extension never shortens the deadline`() = coordinatorTest {
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        coordinator.extendDeadline(request, 10_000)

        assertEquals(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, deadlineOnScreen())
        job.cancel()
    }

    @Test
    fun `an extension concerns the current request only`() = coordinatorTest {
        val first = request(fill = 1)
        val queued = request(fill = 2)
        val firstJob = launch { coordinator.prompt(first) }
        runCurrent()
        val queuedJob = launch { coordinator.prompt(queued) }
        runCurrent()
        val deadlineBefore = deadlineOnScreen()
        advanceTimeBy(10_000)

        // A request that waits its turn cannot move the deadline of the one on screen.
        coordinator.extendDeadline(queued, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
        // Nor can a request that never was.
        coordinator.extendDeadline(request(fill = 3), Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)

        assertEquals(deadlineBefore, deadlineOnScreen())
        firstJob.cancel()
        queuedJob.cancel()
    }

    @Test
    fun `an extension of a request that ended changes nothing for the next one`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        firstResponse.await()
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()
        val deadline = deadlineOnScreen()
        advanceTimeBy(10_000)

        // The sheet of the first prompt asks for a PIN delay late.
        coordinator.extendDeadline(first, Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)

        assertEquals(deadline, deadlineOnScreen())
        secondJob.cancel()
    }

    @Test
    fun `an extension with nothing on screen is harmless`() = coordinatorTest {
        coordinator.extendDeadline(request(), Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)

        assertNull(coordinator.state.value)
    }

    @Test
    fun `a queued request gets a deadline counted from the moment it is shown`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()

        advanceTimeBy(30_000)
        coordinator.cancel(first)
        firstResponse.await()
        runCurrent()

        assertSame(second, onScreen())
        assertEquals(30_000L + Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, deadlineOnScreen())
        secondJob.cancel()
    }

    @Test
    fun `the injected clock is the monotonic clock by default`() {
        val coordinator = Fido2PromptCoordinator()

        val first = coordinator.nowMs()
        val second = coordinator.nowMs()

        assertTrue(second >= first)
    }

    // ── Late and stale answers ────────────────────────────────────────────────

    @Test
    fun `a signature that arrives after the prompt ended is closed, not delivered`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS + 1)
        runCurrent()
        assertNull(response.await())

        val late = signature()
        coordinator.complete(request, late)

        assertTrue(isWiped(late.rawSignature), "a signature nobody waits for must be wiped")
        assertNull(onScreen())
    }

    @Test
    fun `an answer of an earlier request does not resolve the current one`() = coordinatorTest {
        val first = request(fill = 1)
        val second = request(fill = 2)
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        firstResponse.await()

        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()
        assertSame(second, onScreen())

        // The sheet of the first request answers late.
        val stale = signature()
        coordinator.complete(first, stale)
        coordinator.cancel(first)

        assertTrue(isWiped(stale.rawSignature), "the stale signature is wiped")
        assertSame(second, onScreen(), "the current prompt is untouched")
        assertTrue(!secondResponse.isCompleted)

        coordinator.cancel(second)
        assertEquals(Fido2ChallengeResponse.Cancelled, secondResponse.await())
    }

    @Test
    fun `answers with nothing on screen are harmless`() {
        val orphan = signature()

        coordinator.cancel(request())
        coordinator.complete(request(), orphan)

        assertNull(onScreen())
        assertTrue(isWiped(orphan.rawSignature))
    }

    @Test
    fun `the prompt carries the request fields the sheet needs`() = coordinatorTest {
        val credential = byteArrayOf(1, 2, 3)
        val request = Fido2ChallengeRequest(
            signingData = ByteArray(8) { 3 },
            rpId = "ssh:",
            keyType = SkKeyType.SK_ECDSA_256,
            allowedCredentialIds = listOf(credential),
        )

        val job = launch { coordinator.prompt(request) }
        runCurrent()

        val shown = onScreen()
        assertNotNull(shown)
        assertEquals(SkKeyType.SK_ECDSA_256, shown!!.keyType)
        assertEquals("ssh:", shown.rpId)
        assertSame(credential, shown.allowedCredentialIds.single())
        job.cancel()
        advanceUntilIdle()
        assertNull(onScreen(), "the prompt goes with its caller")
    }

    // ── Cancellation and timeout racing an answer ─────────────────────────────

    @Test
    fun `a caller cancelled right after the answer closes the signature nobody will take`() = coordinatorTest {
        val request = request()
        val signature = signature()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        // The key signs: the answer is accepted, but the waiting coroutine has not resumed yet...
        coordinator.complete(request, signature)
        // ...and the connection is cancelled before it does.
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled, "the cancellation reaches the caller instead of being swallowed")
        assertNull(onScreen())
        assertTrue(isWiped(signature.rawSignature), "the signature that nobody takes is wiped")
        assertTrue(isWiped(request.signingData))
    }

    @Test
    fun `an answer landing exactly at the deadline is kept, not lost to the timeout`() = coordinatorTest {
        val request = request()
        val signature = signature()
        val response = async { coordinator.prompt(request) }
        runCurrent()

        // The clock reads the deadline, but the timeout task due at that instant has not run yet.
        advanceTimeBy(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(Fido2PromptCoordinator.PROMPT_TIMEOUT_MS, coordinator.nowMs())
        assertSame(request, onScreen(), "still on screen: the precondition of this test")
        coordinator.complete(request, signature)
        runCurrent()

        val signed = response.await() as Fido2ChallengeResponse.Signed
        assertSame(signature, signed.signature)
        assertTrue(signature.rawSignature.any { it != 0.toByte() }, "the signature is handed over intact")
        assertNull(onScreen())
        assertTrue(isWiped(request.signingData))
    }
}
