// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AppVisibility
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * A prompt that needs a person in front of the phone, asked for while the app is not on screen (a
 * tunnel reconnected by the service), ends at once: nothing is shown, the turn is not taken, and
 * the connection reads "authentication required" instead of a timeout 60 s later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PromptNoScreenTest {

    private var visible = false
    private val appVisibility = AppVisibility { visible }

    private fun fido2Request() = Fido2ChallengeRequest(
        signingData = ByteArray(32) { 9 },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    private fun fingerprintRequest() = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = "Host",
        keyLabel = "Key",
    )

    private data class Coordinators(val fido2: Fido2PromptCoordinator, val biometric: BiometricPromptCoordinator)

    /** Both coordinators on one turn and one virtual clock, as the app wires them, and the visibility of this test. */
    private fun TestScope.coordinators(): Coordinators {
        val turn = InteractivePromptTurn()
        val clock = { testScheduler.currentTime }
        return Coordinators(
            Fido2PromptCoordinator(turn, clock, appVisibility),
            BiometricPromptCoordinator(turn, clock, appVisibility),
        )
    }

    // ── FIDO2 sheet ───────────────────────────────────────────────────────────

    @Test
    fun `a FIDO2 request made while the app is hidden ends at once with no screen, nothing shown`() = runTest {
        val (fido2, _) = coordinators()
        val request = fido2Request()

        val response = fido2.prompt(request)

        assertEquals(Fido2ChallengeResponse.NoScreen, response)
        assertNull(fido2.state.value, "no sheet for nobody")
        assertEquals(0L, testScheduler.currentTime, "no deadline to wait for")
        assertTrue(request.signingData.all { it == 0.toByte() }, "the data to sign is zeroed at once")
    }

    @Test
    fun `a hidden FIDO2 request does not take the turn, a fingerprint prompt goes on`() = runTest {
        val (fido2, biometric) = coordinators()

        assertEquals(Fido2ChallengeResponse.NoScreen, fido2.prompt(fido2Request()))

        visible = true
        val finger = fingerprintRequest()
        val response = async { biometric.prompt(finger) }
        runCurrent()
        assertSame(finger, biometric.state.value?.request, "its turn came at once, nothing held it")
        biometric.authorize(finger)
        assertEquals(BiometricPromptResponse.Authorized, response.await())
    }

    @Test
    fun `a hidden FIDO2 request does not wait for a fingerprint prompt that holds the turn`() = runTest {
        val (fido2, biometric) = coordinators()
        visible = true
        val finger = fingerprintRequest()
        val fingerResponse = async { biometric.prompt(finger) }
        runCurrent()
        assertSame(finger, biometric.state.value?.request)

        visible = false
        val sheetResponse = fido2.prompt(fido2Request())

        assertEquals(Fido2ChallengeResponse.NoScreen, sheetResponse, "answered at once, not queued behind the dialog")
        assertSame(finger, biometric.state.value?.request, "the other coordinator is unaffected")
        assertFalse(fingerResponse.isCompleted)
        biometric.cancel(finger)
        assertEquals(BiometricPromptResponse.Cancelled, fingerResponse.await())
    }

    @Test
    fun `a FIDO2 request made while the app is visible is shown as always`() = runTest {
        val (fido2, _) = coordinators()
        visible = true
        val request = fido2Request()

        val response = async { fido2.prompt(request) }
        runCurrent()
        assertSame(request, fido2.state.value?.request)
        fido2.cancel(request)

        assertEquals(Fido2ChallengeResponse.Cancelled, response.await())
    }

    @Test
    fun `the visibility is read at each request, a user who opened the app is prompted`() = runTest {
        val (fido2, _) = coordinators()

        assertEquals(Fido2ChallengeResponse.NoScreen, fido2.prompt(fido2Request()))
        visible = true
        val request = fido2Request()
        val response = async { fido2.prompt(request) }
        runCurrent()
        assertSame(request, fido2.state.value?.request)
        fido2.cancel(request)
        response.await()
        visible = false
        assertEquals(Fido2ChallengeResponse.NoScreen, fido2.prompt(fido2Request()))
    }

    @Test
    fun `a prompt already on screen is not cut short when the app is left`() = runTest {
        val (fido2, _) = coordinators()
        visible = true
        val request = fido2Request()
        val response = async { fido2.prompt(request) }
        runCurrent()

        visible = false

        assertSame(request, fido2.state.value?.request, "the existing behavior: the deadline ends it")
        fido2.cancel(request)
        assertEquals(Fido2ChallengeResponse.Cancelled, response.await())
    }

    @Test
    fun `the callback installed on the session manager ends at once too when the app is hidden`() = runTest {
        val (fido2, _) = coordinators()
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (Fido2ChallengeRequest) -> Fido2ChallengeResponse?>()
        every { manager.fido2ChallengeCallback = capture(installed) } just Runs
        fido2.install(manager)

        assertEquals(Fido2ChallengeResponse.NoScreen, installed.captured(fido2Request()))
    }

    // ── Fingerprint dialog ────────────────────────────────────────────────────

    @Test
    fun `a fingerprint request made while the app is hidden ends at once with no screen, nothing shown`() = runTest {
        val (_, biometric) = coordinators()

        val response = biometric.prompt(fingerprintRequest())

        assertEquals(BiometricPromptResponse.NoScreen, response)
        assertNull(biometric.state.value, "no dialog for nobody")
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a hidden fingerprint request does not take the turn, a FIDO2 sheet goes on`() = runTest {
        val (fido2, biometric) = coordinators()
        assertEquals(BiometricPromptResponse.NoScreen, biometric.prompt(fingerprintRequest()))

        visible = true
        val sheet = fido2Request()
        val response = async { fido2.prompt(sheet) }
        runCurrent()
        assertSame(sheet, fido2.state.value?.request, "its turn came at once, nothing held it")
        fido2.cancel(sheet)
        assertEquals(Fido2ChallengeResponse.Cancelled, response.await())
    }

    @Test
    fun `a hidden fingerprint request does not wait for a FIDO2 sheet that holds the turn`() = runTest {
        val (fido2, biometric) = coordinators()
        visible = true
        val sheet = fido2Request()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        assertSame(sheet, fido2.state.value?.request)

        visible = false
        val fingerResponse = biometric.prompt(fingerprintRequest())

        assertEquals(BiometricPromptResponse.NoScreen, fingerResponse)
        assertSame(sheet, fido2.state.value?.request, "the other coordinator is unaffected")
        assertNull(biometric.state.value)
        fido2.cancel(sheet)
        assertEquals(Fido2ChallengeResponse.Cancelled, sheetResponse.await())
    }

    @Test
    fun `a fingerprint request made while the app is visible is shown as always`() = runTest {
        val (_, biometric) = coordinators()
        visible = true
        val request = fingerprintRequest()

        val response = async { biometric.prompt(request) }
        runCurrent()
        assertSame(request, biometric.state.value?.request)
        biometric.authorize(request)

        assertEquals(BiometricPromptResponse.Authorized, response.await())
    }

    @Test
    fun `the callback installed on the session manager ends at once too for a fingerprint`() = runTest {
        val (_, biometric) = coordinators()
        val manager = mockk<SshSessionManager>()
        val installed = slot<suspend (BiometricSignRequest) -> BiometricPromptResponse?>()
        every { manager.biometricSignatureCallback = capture(installed) } just Runs
        biometric.install(manager)

        assertEquals(BiometricPromptResponse.NoScreen, installed.captured(fingerprintRequest()))
    }

    // ── A request queued while the app was visible, its turn comes after the user left ──

    @Test
    fun `a FIDO2 request queued behind another ends at once if the app is left when its turn comes`() = runTest {
        val (fido2, _) = coordinators()
        visible = true
        val first = fido2Request()
        val second = fido2Request()
        val firstResponse = async { fido2.prompt(first) }
        runCurrent()
        val secondResponse = async { fido2.prompt(second) }
        runCurrent()
        assertSame(first, fido2.state.value?.request)
        assertFalse(secondResponse.isCompleted, "queued behind the sheet on screen")

        visible = false
        fido2.cancel(first)
        runCurrent()

        assertEquals(Fido2ChallengeResponse.Cancelled, firstResponse.await())
        assertEquals(Fido2ChallengeResponse.NoScreen, secondResponse.await(), "its turn came, nobody is there")
        assertNull(fido2.state.value, "no sheet is shown for nobody, and none is left holding the turn")
        assertEquals(0L, testScheduler.currentTime, "no deadline to wait for")
        assertTrue(second.signingData.all { it == 0.toByte() }, "the data to sign is zeroed")
    }

    @Test
    fun `the turn is free again after a queued request found the app left`() = runTest {
        val (fido2, _) = coordinators()
        visible = true
        val first = fido2Request()
        val firstResponse = async { fido2.prompt(first) }
        runCurrent()
        val queued = async { fido2.prompt(fido2Request()) }
        runCurrent()
        visible = false
        fido2.cancel(first)
        runCurrent()
        assertEquals(Fido2ChallengeResponse.NoScreen, queued.await())
        firstResponse.await()

        visible = true
        val next = fido2Request()
        val nextResponse = async { fido2.prompt(next) }
        runCurrent()

        assertSame(next, fido2.state.value?.request, "the next request gets the turn at once")
        fido2.cancel(next)
        assertEquals(Fido2ChallengeResponse.Cancelled, nextResponse.await())
    }

    @Test
    fun `a fingerprint request queued behind a FIDO2 sheet ends at once if the app is left when its turn comes`() = runTest {
        val (fido2, biometric) = coordinators()
        visible = true
        val sheet = fido2Request()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()
        val fingerResponse = async { biometric.prompt(fingerprintRequest()) }
        runCurrent()
        assertNull(biometric.state.value, "queued behind the sheet")

        visible = false
        fido2.cancel(sheet)
        runCurrent()

        assertEquals(Fido2ChallengeResponse.Cancelled, sheetResponse.await())
        assertEquals(BiometricPromptResponse.NoScreen, fingerResponse.await())
        assertNull(biometric.state.value, "no dialog for nobody")
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `a fingerprint request queued behind another ends at once if the app is left when its turn comes`() = runTest {
        val (_, biometric) = coordinators()
        visible = true
        val first = fingerprintRequest()
        val firstResponse = async { biometric.prompt(first) }
        runCurrent()
        val secondResponse = async { biometric.prompt(fingerprintRequest()) }
        runCurrent()

        visible = false
        biometric.cancel(first)
        runCurrent()

        assertEquals(BiometricPromptResponse.Cancelled, firstResponse.await())
        assertEquals(BiometricPromptResponse.NoScreen, secondResponse.await())
        assertNull(biometric.state.value)
    }

    @Test
    fun `a FIDO2 request queued behind a fingerprint dialog ends at once if the app is left when its turn comes`() = runTest {
        val (fido2, biometric) = coordinators()
        visible = true
        val finger = fingerprintRequest()
        val fingerResponse = async { biometric.prompt(finger) }
        runCurrent()
        val sheet = fido2Request()
        val sheetResponse = async { fido2.prompt(sheet) }
        runCurrent()

        visible = false
        biometric.cancel(finger)
        runCurrent()

        assertEquals(BiometricPromptResponse.Cancelled, fingerResponse.await())
        assertEquals(Fido2ChallengeResponse.NoScreen, sheetResponse.await())
        assertNull(fido2.state.value)
        assertTrue(sheet.signingData.all { it == 0.toByte() })
    }
    // ── What the connection reads ─────────────────────────────────────────────

    @Test
    fun `a FIDO2 no screen ends the attempt as a timeout that is held, with a message of its own`() = runTest {
        val ends = mutableListOf<Fido2PromptEnd>()

        val signature = requestFido2Signature({ Fido2ChallengeResponse.NoScreen }, fido2Request(), caller = null) { end, reason ->
            ends += end
            assertNull(reason)
        }

        assertNull(signature)
        assertEquals(listOf(Fido2PromptEnd.NO_SCREEN), ends)
        val code = fido2AuthFailureCode(Fido2PromptEnd.NO_SCREEN)
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, code)
        assertTrue(code.isNonRetryable(), "held out of the reconnection, not retried into an empty screen")
        assertEquals(R.string.auth_error_no_screen, fido2AuthFailureMessageRes(Fido2PromptEnd.NO_SCREEN))
        assertNotEquals(
            fido2AuthFailureMessageRes(Fido2PromptEnd.TIMED_OUT),
            fido2AuthFailureMessageRes(Fido2PromptEnd.NO_SCREEN),
            "the user is told to open the app, not that a key was not touched",
        )
    }

    @Test
    fun `a fingerprint no screen ends the attempt as a timeout that is held, with a message of its own`() = runTest {
        val ends = mutableListOf<BiometricPromptEnd>()

        val authorized = requestBiometricAuthorization({ BiometricPromptResponse.NoScreen }, fingerprintRequest(), caller = null) {
            ends += it
        }

        assertFalse(authorized)
        assertEquals(listOf<BiometricPromptEnd>(BiometricPromptEnd.NoScreen), ends)
        val code = biometricAuthFailureCode(BiometricPromptEnd.NoScreen)
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, code)
        assertTrue(code.isNonRetryable())
        assertEquals(R.string.auth_error_no_screen, biometricAuthFailureMessageRes(BiometricPromptEnd.NoScreen))
        assertNotEquals(
            biometricAuthFailureMessageRes(BiometricPromptEnd.TimedOut),
            biometricAuthFailureMessageRes(BiometricPromptEnd.NoScreen),
        )
    }
}
