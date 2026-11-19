// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.R
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.Signature

/**
 * How a fingerprint prompt that ended without an authorization reads, and the child-of-the-connection
 * rule: leaving or cancelling a connection removes its fingerprint prompt.
 *
 * `BiometricAuthMethod` calls its signer inside a `runBlocking` on the SSH transport thread. That
 * coroutine is blind to the connecting coroutine, so the prompt, shown or queued, would outlive the
 * connection until its 60 s were over. These tests reproduce the shape of the call (a blocking
 * `runBlocking` inside an IO coroutine) with real threads, hence real time and generous timeouts.
 */
class BiometricAuthorizationTest {

    private val coordinator = BiometricPromptCoordinator()

    private fun request(label: String = "Host") = BiometricSignRequest(
        signature = Signature.getInstance("SHA256withECDSA"),
        hostLabel = label,
        keyLabel = "Key",
    )

    // ── What each answer means ────────────────────────────────────────────────

    @Test
    fun `an authorization is a yes and ends nothing`() = runBlocking {
        val ends = mutableListOf<BiometricPromptEnd>()

        val authorized = requestBiometricAuthorization({ BiometricPromptResponse.Authorized }, request(), null) { ends += it }

        assertTrue(authorized)
        assertTrue(ends.isEmpty())
    }

    @Test
    fun `every other answer is a no that says how the prompt ended`() = runBlocking {
        val cases = listOf(
            BiometricPromptResponse.Cancelled to BiometricPromptEnd.Cancelled,
            null to BiometricPromptEnd.TimedOut,
            BiometricPromptResponse.Failed(BiometricFailure.LOCKOUT) to BiometricPromptEnd.Failed(BiometricFailure.LOCKOUT),
            BiometricPromptResponse.Failed(BiometricFailure.NO_BIOMETRICS) to BiometricPromptEnd.Failed(BiometricFailure.NO_BIOMETRICS),
        )
        for ((response, expected) in cases) {
            val ends = mutableListOf<BiometricPromptEnd>()

            val authorized = requestBiometricAuthorization({ response }, request(), null) { ends += it }

            assertFalse(authorized, "$response")
            assertEquals(listOf(expected), ends, "$response")
        }
    }

    @Test
    fun `the request given to the callback is the one that was asked for`() = runBlocking {
        val asked = request()
        var received: BiometricSignRequest? = null

        requestBiometricAuthorization({ received = it; BiometricPromptResponse.Authorized }, asked, null) { }

        assertSame(asked, received)
    }

    // ── Codes and messages ────────────────────────────────────────────────────

    @Test
    fun `a cancellation and a timeout use the codes that the reconnection never retries`() {
        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, biometricAuthFailureCode(BiometricPromptEnd.Cancelled))
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, biometricAuthFailureCode(BiometricPromptEnd.TimedOut))
        assertTrue(biometricAuthFailureCode(BiometricPromptEnd.Cancelled).isNonRetryable())
        assertTrue(biometricAuthFailureCode(BiometricPromptEnd.TimedOut).isNonRetryable())
    }

    @Test
    fun `a failed sensor is an authentication failure, whatever the reason, and is not retried`() {
        for (reason in BiometricFailure.entries) {
            val code = biometricAuthFailureCode(BiometricPromptEnd.Failed(reason))

            assertEquals(SshErrorCode.AUTH_FAILED, code, "$reason")
            assertTrue(code.isNonRetryable(), "$reason")
        }
    }

    @Test
    fun `a cancellation reads as a cancellation, a timeout as a fingerprint not presented`() {
        assertEquals(R.string.auth_error_fido2_cancelled, biometricAuthFailureMessageRes(BiometricPromptEnd.Cancelled))
        assertEquals(R.string.auth_error_biometric_timeout, biometricAuthFailureMessageRes(BiometricPromptEnd.TimedOut))
    }

    @Test
    fun `every reason a sensor fails has its own sentence`() {
        val sentences = BiometricFailure.entries.associateWith { biometricAuthFailureMessageRes(BiometricPromptEnd.Failed(it)) }

        assertEquals(R.string.auth_error_biometric_lockout, sentences.getValue(BiometricFailure.LOCKOUT))
        assertEquals(R.string.auth_error_biometric_lockout_permanent, sentences.getValue(BiometricFailure.LOCKOUT_PERMANENT))
        assertEquals(R.string.auth_error_biometric_none_enrolled, sentences.getValue(BiometricFailure.NO_BIOMETRICS))
        assertEquals(R.string.auth_error_biometric_sensor_unavailable, sentences.getValue(BiometricFailure.HW_UNAVAILABLE))
        assertEquals(R.string.auth_error_biometric_no_sensor, sentences.getValue(BiometricFailure.HW_NOT_PRESENT))
        assertEquals(R.string.auth_error_biometric_error, sentences.getValue(BiometricFailure.OTHER))
        assertEquals(sentences.size, sentences.values.toSet().size, "two reasons must not say the same thing")
    }

    // ── Leaving or cancelling the connection removes the prompt ───────────────

    /** The connecting coroutine: an IO coroutine that blocks in a runBlocking, like the SSH auth does. */
    private fun CoroutineScope.connect(
        request: BiometricSignRequest,
        passCaller: Boolean = true,
        ends: MutableList<BiometricPromptEnd> = mutableListOf(),
    ): Job = launch(Dispatchers.IO) {
        val caller = currentCoroutineContext()[Job]
        runBlocking {
            requestBiometricAuthorization(coordinator::prompt, request, if (passCaller) caller else null) { ends += it }
        }
    }

    private suspend fun awaitShown(request: BiometricSignRequest) {
        withTimeout(5_000) { coordinator.state.first { it?.request === request } }
    }

    private suspend fun awaitNothingShown() {
        withTimeout(5_000) { coordinator.state.first { it == null } }
    }

    @Test
    fun `cancelling the connection removes the prompt it shows`() = runBlocking {
        val request = request()
        val connection = connect(request)
        awaitShown(request)

        connection.cancel()
        withTimeout(5_000) { connection.join() }

        assertNull(coordinator.state.value)
        assertTrue(connection.isCancelled)
    }

    @Test
    fun `without the caller the prompt outlives the cancelled connection (what the Job fixes)`() = runBlocking {
        val request = request()
        val connection = connect(request, passCaller = false)
        awaitShown(request)

        connection.cancel()
        delay(300)

        // The blocked runBlocking knows nothing of the cancellation: the dialog is still up.
        assertNotNull(coordinator.state.value)
        coordinator.cancel(request)
        withTimeout(5_000) { connection.join() }
    }

    @Test
    fun `a queued prompt of a cancelled connection is never shown`() = runBlocking {
        val first = request("First")
        val second = request("Second")
        val firstConnection = connect(first)
        awaitShown(first)
        val secondConnection = connect(second)
        delay(200) // let it queue behind the first one

        secondConnection.cancel()
        withTimeout(5_000) { secondConnection.join() }
        assertSame(first, coordinator.state.value?.request, "the first prompt is untouched")

        coordinator.cancel(first)
        withTimeout(5_000) { firstConnection.join() }
        awaitNothingShown()
        delay(200)
        assertNull(coordinator.state.value, "the cancelled connection's prompt does not come up afterwards")
    }

    @Test
    fun `cancelling a connection lets the next one take its turn`() = runBlocking {
        val first = request("First")
        val second = request("Second")
        val firstConnection = connect(first)
        awaitShown(first)
        val secondConnection = connect(second)
        delay(200)

        firstConnection.cancel()
        withTimeout(5_000) { firstConnection.join() }

        awaitShown(second)
        coordinator.cancel(second)
        withTimeout(5_000) { secondConnection.join() }
    }

    @Test
    fun `a connection that completes normally still gets its authorization`() = runBlocking {
        val request = request()
        var authorized: Boolean? = null
        val connection = launch(Dispatchers.IO) {
            val caller = currentCoroutineContext()[Job]
            authorized = runBlocking { requestBiometricAuthorization(coordinator::prompt, request, caller) { } }
        }
        awaitShown(request)

        coordinator.authorize(request)
        withTimeout(5_000) { connection.join() }

        assertEquals(true, authorized)
        assertFalse(connection.isCancelled)
        awaitNothingShown()
    }

    @Test
    fun `a prompt cancelled by the user ends the connection attempt with its end recorded`() = runBlocking {
        val request = request()
        val ends = mutableListOf<BiometricPromptEnd>()
        val connection = connect(request, ends = ends)
        awaitShown(request)

        coordinator.cancel(request)
        withTimeout(5_000) { connection.join() }

        assertEquals(listOf<BiometricPromptEnd>(BiometricPromptEnd.Cancelled), ends)
        assertFalse(connection.isCancelled)
    }
}
