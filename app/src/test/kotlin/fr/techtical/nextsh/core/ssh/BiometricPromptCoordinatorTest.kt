// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.biometric.BiometricPrompt
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * The fingerprint prompt must work whichever screen starts the connection: the callback is
 * installed once at app start, shows one prompt at a time, shares its turn with the FIDO2 sheet,
 * ends by itself at its deadline, and only listens to the request on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BiometricPromptCoordinatorTest {

    private var coordinator = BiometricPromptCoordinator()

    /** runTest with a coordinator whose clock is the virtual time of the test. */
    private fun coordinatorTest(block: suspend TestScope.() -> Unit) = runTest {
        coordinator = BiometricPromptCoordinator { testScheduler.currentTime }
        block()
    }

    private fun onScreen(): BiometricRequest? = coordinator.state.value?.request

    private fun request(hostLabel: String = "Host", keyLabel: String = "Key") = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = hostLabel,
        keyLabel = keyLabel,
    )

    // ── Installation ──────────────────────────────────────────────────────────

    @Test
    fun `install sets the fingerprint callback of the session manager`() = coordinatorTest {
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (BiometricSignRequest) -> BiometricPromptResponse?>()
        every { manager.biometricSignatureCallback = capture(installed) } just Runs

        coordinator.install(manager)

        verify(exactly = 1) { manager.biometricSignatureCallback = any() }
        assertTrue(installed.isCaptured)
    }

    @Test
    fun `the installed callback shows the prompt and returns the answer`() = coordinatorTest {
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (BiometricSignRequest) -> BiometricPromptResponse?>()
        every { manager.biometricSignatureCallback = capture(installed) } just Runs
        coordinator.install(manager)
        val request = request()

        val response = async { installed.captured(request) }
        runCurrent()
        assertSame(request, onScreen())
        coordinator.authorize(request)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(onScreen())
    }

    // ── One prompt, every way it ends ─────────────────────────────────────────

    @Test
    fun `no prompt is shown before a request`() {
        assertNull(onScreen())
    }

    @Test
    fun `authorize returns Authorized and clears the prompt`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        assertSame(request, onScreen())
        coordinator.authorize(request)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(onScreen())
    }

    @Test
    fun `cancel returns Cancelled and clears the prompt`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        coordinator.cancel(request)

        assertEquals(BiometricPromptResponse.Cancelled, response.await())
        assertNull(onScreen())
    }

    @Test
    fun `fail returns the reason and clears the prompt`() = coordinatorTest {
        for (reason in BiometricFailure.entries) {
            val request = request()

            val response = async { coordinator.prompt(request) }
            runCurrent()
            coordinator.fail(request, reason)

            assertEquals(BiometricPromptResponse.Failed(reason), response.await(), "$reason")
            assertNull(onScreen())
        }
    }

    @Test
    fun `the system timeout reads as a prompt nobody answered`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        coordinator.timeOut(request)

        assertNull(response.await())
        assertNull(onScreen())
    }

    @Test
    fun `an unanswered prompt times out after 60 seconds and clears`() = coordinatorTest {
        val request = request()

        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1)
        runCurrent()
        assertSame(request, onScreen(), "still waiting just before the timeout")

        advanceTimeBy(2)
        runCurrent()

        assertNull(response.await())
        assertNull(onScreen())
    }

    @Test
    fun `a cancelled caller clears the prompt`() = coordinatorTest {
        val request = request()

        val job = launch { coordinator.prompt(request) }
        runCurrent()
        assertSame(request, onScreen())
        job.cancel()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertNull(onScreen())
    }

    @Test
    fun `a prompt can follow another one`() = coordinatorTest {
        val first = request()
        val second = request()

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        assertEquals(BiometricPromptResponse.Cancelled, firstResponse.await())

        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()
        assertSame(second, onScreen())
        coordinator.authorize(second)
        assertEquals(BiometricPromptResponse.Authorized, secondResponse.await())
    }

    // ── The deadline ──────────────────────────────────────────────────────────

    @Test
    fun `the prompt carries a deadline 60 seconds after it appeared`() = coordinatorTest {
        advanceTimeBy(5_000)
        val request = request()

        val job = launch { coordinator.prompt(request) }
        runCurrent()

        assertEquals(5_000L + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, coordinator.state.value!!.deadlineMs)
        assertSame(request, onScreen())
        job.cancel()
    }


    @Test
    fun `an answer landing exactly at the deadline is kept, not lost to the timeout`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()

        // The clock reads the deadline, but the timeout task due at that instant has not run yet.
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS)
        assertEquals(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, testScheduler.currentTime)
        assertSame(request, onScreen(), "still on screen: the precondition of this test")
        coordinator.authorize(request)
        runCurrent()

        assertEquals(BiometricPromptResponse.Authorized, response.await())
        assertNull(onScreen())
    }

    @Test
    fun `the clock is the monotonic clock by default`() = runTest {
        // The test constructor, and the one that Hilt calls with the shared turn and the visibility.
        val coordinators = listOf(
            BiometricPromptCoordinator(),
            BiometricPromptCoordinator(InteractivePromptTurn()),
            BiometricPromptCoordinator(InteractivePromptTurn(), AppVisibility { true }),
        )
        for (coordinator in coordinators) {
            val before = System.nanoTime() / 1_000_000L
            val job = launch(UnconfinedTestDispatcher(testScheduler)) { coordinator.prompt(request()) }
            val after = System.nanoTime() / 1_000_000L

            val deadline = coordinator.state.value!!.deadlineMs
            assertTrue(deadline in (before + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS)..(after + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS), "$deadline")
            job.cancel()
        }
    }

    // ── Concurrent requests ───────────────────────────────────────────────────

    @Test
    fun `a second request waits for the first one, then gets its own prompt`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()

        // Only the first one is on screen; the second one is queued, not shown, not dropped.
        assertSame(first, onScreen())
        assertFalse(secondResponse.isCompleted)

        coordinator.cancel(first)
        runCurrent()
        assertEquals(BiometricPromptResponse.Cancelled, firstResponse.await())
        assertSame(second, onScreen())

        coordinator.authorize(second)
        assertEquals(BiometricPromptResponse.Authorized, secondResponse.await())
        assertNull(onScreen())
    }

    @Test
    fun `a queued request gets its full 60 seconds from the moment it is shown`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1_000)
        runCurrent()
        coordinator.cancel(first)
        runCurrent()
        firstResponse.await()
        assertSame(second, onScreen())
        assertEquals(
            BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1_000 + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS,
            coordinator.state.value!!.deadlineMs,
        )

        // 59 s after the second prompt appeared: still waiting.
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1_000)
        runCurrent()
        assertSame(second, onScreen())

        advanceTimeBy(1_001)
        runCurrent()
        assertNull(secondResponse.await())
        assertNull(onScreen())
    }

    @Test
    fun `a queued request that is cancelled is never shown`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")

        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()
        secondJob.cancel()
        runCurrent()

        assertSame(first, onScreen(), "the waiting caller leaving does not touch the shown prompt")
        coordinator.cancel(first)
        assertEquals(BiometricPromptResponse.Cancelled, firstResponse.await())
        runCurrent()
        assertNull(onScreen(), "the cancelled request is not shown afterwards")
    }

    // ── Late and stale answers ────────────────────────────────────────────────

    @Test
    fun `an answer of an earlier request does not resolve the current one`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        coordinator.cancel(first)
        firstResponse.await()

        val secondResponse = async { coordinator.prompt(second) }
        runCurrent()
        assertSame(second, onScreen())

        // The dialog of the first request answers late, in every way it can.
        coordinator.authorize(first)
        coordinator.cancel(first)
        coordinator.fail(first, BiometricFailure.LOCKOUT)
        coordinator.timeOut(first)
        coordinator.reportError(first, BiometricPrompt.ERROR_CANCELED)

        assertSame(second, onScreen(), "the current prompt is untouched")
        assertFalse(secondResponse.isCompleted)

        coordinator.cancel(second)
        assertEquals(BiometricPromptResponse.Cancelled, secondResponse.await())
    }

    @Test
    fun `an answer of a request that is only queued is ignored`() = coordinatorTest {
        val first = request("First")
        val queued = request("Queued")
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        val queuedResponse = async { coordinator.prompt(queued) }
        runCurrent()

        coordinator.authorize(queued)

        assertSame(first, onScreen())
        assertFalse(firstResponse.isCompleted)
        assertFalse(queuedResponse.isCompleted)
        coordinator.cancel(first)
        runCurrent()
        // The queued one gets its own prompt, unanswered: the early authorize did not stick.
        assertSame(queued, onScreen())
        assertFalse(queuedResponse.isCompleted)
        coordinator.cancel(queued)
        queuedResponse.await()
    }

    @Test
    fun `answers with nothing on screen are harmless`() {
        val orphan = request()

        coordinator.authorize(orphan)
        coordinator.cancel(orphan)
        coordinator.fail(orphan, BiometricFailure.OTHER)
        coordinator.timeOut(orphan)
        coordinator.reportError(orphan, BiometricPrompt.ERROR_USER_CANCELED)

        assertNull(onScreen())
    }

    @Test
    fun `an answer that arrives after the prompt ended changes nothing`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1)
        runCurrent()
        assertNull(response.await())

        coordinator.authorize(request)

        assertNull(onScreen())
    }

    // ── Starting the dialog once ──────────────────────────────────────────────

    @Test
    fun `the dialog of a request is started once, however often the host asks`() = coordinatorTest {
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        assertTrue(coordinator.markLaunched(request), "the first host starts the dialog")
        assertFalse(coordinator.markLaunched(request), "a host recreated by a rotation does not start it again")
        assertFalse(coordinator.markLaunched(request))
        job.cancel()
    }

    @Test
    fun `each request has its own launch, even right after another`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        assertTrue(coordinator.markLaunched(first))
        coordinator.cancel(first)
        firstResponse.await()
        coordinator.markClosed(first) // the host confirms the first dialog is gone

        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()

        assertFalse(coordinator.markLaunched(first), "the ended request cannot be launched")
        assertTrue(coordinator.markLaunched(second), "the next one starts clean")
        secondJob.cancel()
    }

    @Test
    fun `a request waits for the dialog before it to be confirmed closed`() = coordinatorTest {
        val first = request("First")
        val second = request("Second")
        val firstResponse = async { coordinator.prompt(first) }
        runCurrent()
        assertTrue(coordinator.markLaunched(first))
        coordinator.cancel(first)
        firstResponse.await()
        val secondJob = launch { coordinator.prompt(second) }
        runCurrent()

        // The first dialog is going away but not confirmed: androidx would drop a second authenticate().
        assertFalse(coordinator.markLaunched(second))
        coordinator.markClosed(second) // not the one that is open: ignored
        assertFalse(coordinator.markLaunched(second))
        coordinator.markClosed(first)
        assertTrue(coordinator.markLaunched(second))
        secondJob.cancel()
    }

    @Test
    fun `the dialog believed up outlives its request, so the next host can close it`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        assertNull(coordinator.openDialog())
        assertTrue(coordinator.markLaunched(request))
        assertSame(request, coordinator.openDialog())

        advanceTimeBy(BiometricPromptCoordinator.MAX_PROMPT_TOTAL_MS)
        runCurrent()
        assertNull(response.await())
        assertNull(onScreen(), "the request is over")
        assertSame(request, coordinator.openDialog(), "the dialog of a request that ended is not known to be closed")

        coordinator.markClosed(request)
        assertNull(coordinator.openDialog())
    }

    @Test
    fun `a dialog that was never launched is not open`() = coordinatorTest {
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        assertNull(coordinator.openDialog())
        coordinator.markClosed(request) // harmless
        job.cancel()
    }

    // ── The deadline counts from the launch of the dialog ─────────────────────

    @Test
    fun `launching the dialog gives it a full 60 seconds from that moment`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(59_000)
        runCurrent()

        // Launched at second 59: not one second left, a whole minute.
        assertTrue(coordinator.markLaunched(request))
        assertEquals(59_000L + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, coordinator.state.value!!.deadlineMs)

        advanceTimeBy(2_000) // second 61: the original deadline is behind us
        runCurrent()
        assertSame(request, onScreen(), "the dialog is still up")
        assertFalse(response.isCompleted)

        advanceTimeBy(58_000 + 1) // second 119 and a millisecond: the new deadline
        runCurrent()
        assertNull(response.await())
        assertNull(onScreen())
    }

    @Test
    fun `an answer after the original deadline is kept once the dialog was launched in time`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(50_000)
        coordinator.markLaunched(request)
        advanceTimeBy(30_000) // second 80
        runCurrent()

        coordinator.authorize(request)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
    }

    @Test
    fun `the deadline never moves past the maximum total counted from the publication`() = coordinatorTest {
        // The cap is what keeps the SSH transport wait valid whatever the host does.
        assertEquals(2 * BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, BiometricPromptCoordinator.MAX_PROMPT_TOTAL_MS)
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS - 1) // the last moment a launch can happen

        assertTrue(coordinator.markLaunched(request))

        assertTrue(coordinator.state.value!!.deadlineMs <= BiometricPromptCoordinator.MAX_PROMPT_TOTAL_MS)
        job.cancel()
    }

    @Test
    fun `launching never brings the deadline closer`() = coordinatorTest {
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        val published = coordinator.state.value!!.deadlineMs

        assertTrue(coordinator.markLaunched(request)) // at once: now + 60 s equals the published deadline

        assertEquals(published, coordinator.state.value!!.deadlineMs)
        job.cancel()
    }

    @Test
    fun `a launch that is refused does not touch the deadline`() = coordinatorTest {
        val request = request()
        val job = launch { coordinator.prompt(request) }
        runCurrent()
        assertTrue(coordinator.markLaunched(request))
        val deadline = coordinator.state.value!!.deadlineMs
        advanceTimeBy(20_000)

        assertFalse(coordinator.markLaunched(request), "a host recreated by a rotation asks again")

        assertEquals(deadline, coordinator.state.value!!.deadlineMs, "the dialog does not get its minute again")
        job.cancel()
    }

    @Test
    fun `a request that is not launched ends 60 seconds after its publication`() = coordinatorTest {
        // Nobody can answer a prompt that is not on screen: the reconnection driven by a service ends here.
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()

        advanceTimeBy(BiometricPromptCoordinator.PROMPT_TIMEOUT_MS + 1)
        runCurrent()

        assertNull(response.await())
        assertNull(onScreen())
    }

    @Test
    fun `a request that is queued or unknown is not launched`() = coordinatorTest {
        val first = request("First")
        val queued = request("Queued")
        val firstJob = launch { coordinator.prompt(first) }
        runCurrent()
        val queuedJob = launch { coordinator.prompt(queued) }
        runCurrent()

        assertFalse(coordinator.markLaunched(queued), "not on screen yet")
        assertFalse(coordinator.markLaunched(request("Stranger")), "never asked for")
        assertTrue(coordinator.markLaunched(first))
        firstJob.cancel()
        queuedJob.cancel()
    }

    @Test
    fun `nothing is launched with nothing on screen`() {
        assertFalse(coordinator.markLaunched(request()))
    }

    @Test
    fun `a request that already ended is not launched`() = coordinatorTest {
        val request = request()
        val response = async { coordinator.prompt(request) }
        runCurrent()
        coordinator.authorize(request)
        response.await()

        assertFalse(coordinator.markLaunched(request))
    }

    // ── System error codes ────────────────────────────────────────────────────

    @Test
    fun `the system error codes map to the answers of the connection`() {
        val cancelled = BiometricPromptResponse.Cancelled
        fun failed(reason: BiometricFailure) = BiometricPromptResponse.Failed(reason)

        assertEquals(cancelled, biometricResponseForError(BiometricPrompt.ERROR_USER_CANCELED))
        assertEquals(cancelled, biometricResponseForError(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertEquals(cancelled, biometricResponseForError(BiometricPrompt.ERROR_CANCELED))
        assertNull(biometricResponseForError(BiometricPrompt.ERROR_TIMEOUT))
        assertEquals(failed(BiometricFailure.LOCKOUT), biometricResponseForError(BiometricPrompt.ERROR_LOCKOUT))
        assertEquals(failed(BiometricFailure.LOCKOUT_PERMANENT), biometricResponseForError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))
        assertEquals(failed(BiometricFailure.NO_BIOMETRICS), biometricResponseForError(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertEquals(failed(BiometricFailure.HW_UNAVAILABLE), biometricResponseForError(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        // Permanent: the phone has no usable sensor, which is not the sensor being busy for now.
        assertEquals(failed(BiometricFailure.HW_NOT_PRESENT), biometricResponseForError(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        for (other in listOf(
            BiometricPrompt.ERROR_UNABLE_TO_PROCESS,
            BiometricPrompt.ERROR_NO_SPACE,
            BiometricPrompt.ERROR_VENDOR,
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED,
            12345,
            -1,
        )) {
            assertEquals(failed(BiometricFailure.OTHER), biometricResponseForError(other), "$other")
        }
    }

    @Test
    fun `reportError answers the prompt with what the code means`() = coordinatorTest {
        val cases = mapOf(
            BiometricPrompt.ERROR_NEGATIVE_BUTTON to BiometricPromptResponse.Cancelled,
            BiometricPrompt.ERROR_LOCKOUT to BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT),
            BiometricPrompt.ERROR_TIMEOUT to null,
        )
        for ((code, expected) in cases) {
            val request = request()
            val response = async { coordinator.prompt(request) }
            runCurrent()

            coordinator.reportError(request, code)

            assertEquals(expected, response.await(), "code $code")
            assertNull(onScreen())
        }
    }

    // ── What the state shows ──────────────────────────────────────────────────

    @Test
    fun `the labels of the request never reach a string of the state`() = coordinatorTest {
        val request = request(hostLabel = "prod-db-secret-host", keyLabel = "my-private-key-label")
        val job = launch { coordinator.prompt(request) }
        runCurrent()

        val text = coordinator.state.value.toString() + request.toString()

        assertFalse(text.contains("prod-db-secret-host"), text)
        assertFalse(text.contains("my-private-key-label"), text)
        job.cancel()
    }

    // ── One turn with the FIDO2 sheet ─────────────────────────────────────────

    private fun fido2Request() = Fido2ChallengeRequest(
        signingData = ByteArray(32) { 9 },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    @Test
    fun `a fingerprint prompt waits for the FIDO2 sheet that is on screen`() = runTest {
        val turn = InteractivePromptTurn()
        val fido2 = Fido2PromptCoordinator(turn) { testScheduler.currentTime }
        val biometric = BiometricPromptCoordinator(turn) { testScheduler.currentTime }
        val sheet = fido2Request()
        val finger = request()

        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val fingerResponse = async { biometric.prompt(finger) }
        runCurrent()

        assertSame(sheet, fido2.state.value?.request)
        assertNull(biometric.state.value, "the fingerprint dialog must not show over the sheet")
        assertFalse(fingerResponse.isCompleted)

        fido2.cancel(sheet)
        runCurrent()
        assertEquals(Fido2ChallengeResponse.Cancelled, sheetResponse.await())
        assertNull(fido2.state.value)
        assertSame(finger, biometric.state.value?.request, "its turn")

        biometric.authorize(finger)
        assertEquals(BiometricPromptResponse.Authorized, fingerResponse.await())
    }

    @Test
    fun `a FIDO2 sheet waits for the fingerprint prompt that is on screen`() = runTest {
        val turn = InteractivePromptTurn()
        val fido2 = Fido2PromptCoordinator(turn) { testScheduler.currentTime }
        val biometric = BiometricPromptCoordinator(turn) { testScheduler.currentTime }
        val sheet = fido2Request()
        val finger = request()

        val fingerResponse = async { biometric.prompt(finger) }
        runCurrent()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()

        assertSame(finger, biometric.state.value?.request)
        assertNull(fido2.state.value, "the sheet must not show over the fingerprint dialog")

        biometric.cancel(finger)
        runCurrent()
        assertEquals(BiometricPromptResponse.Cancelled, fingerResponse.await())
        assertSame(sheet, fido2.state.value?.request)

        fido2.cancel(sheet)
        assertEquals(Fido2ChallengeResponse.Cancelled, sheetResponse.await())
    }

    @Test
    fun `the deadline of a prompt that waited for the other kind counts from when it appears`() = runTest {
        val turn = InteractivePromptTurn()
        val fido2 = Fido2PromptCoordinator(turn) { testScheduler.currentTime }
        val biometric = BiometricPromptCoordinator(turn) { testScheduler.currentTime }
        val sheet = fido2Request()
        val finger = request()

        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val fingerJob = launch { biometric.prompt(finger) }
        runCurrent()
        advanceTimeBy(45_000)
        fido2.cancel(sheet)
        sheetResponse.await()
        runCurrent()

        assertEquals(45_000L + BiometricPromptCoordinator.PROMPT_TIMEOUT_MS, biometric.state.value!!.deadlineMs)
        fingerJob.cancel()
    }

    @Test
    fun `a fingerprint request cancelled while it waits for the sheet never shows`() = runTest {
        val turn = InteractivePromptTurn()
        val fido2 = Fido2PromptCoordinator(turn) { testScheduler.currentTime }
        val biometric = BiometricPromptCoordinator(turn) { testScheduler.currentTime }
        val sheet = fido2Request()

        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val fingerJob = launch { biometric.prompt(request()) }
        runCurrent()
        fingerJob.cancel()
        runCurrent()
        fido2.cancel(sheet)
        sheetResponse.await()
        runCurrent()

        assertNull(biometric.state.value)
        assertNull(fido2.state.value)
    }
}
