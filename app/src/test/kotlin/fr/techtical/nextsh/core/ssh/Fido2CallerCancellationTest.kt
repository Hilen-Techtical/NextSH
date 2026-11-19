// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Leaving or cancelling a connection removes its FIDO2 prompt.
 *
 * `SkAuthMethod` (`:shared`) calls its handler inside a `runBlocking` on the SSH transport
 * thread. That coroutine is blind to the connecting coroutine, so the prompt, shown or queued,
 * used to outlive the connection until its 60 s were over. These tests reproduce the shape of the
 * call (a blocking `runBlocking` inside an IO coroutine) with real threads, hence real time and
 * generous timeouts.
 */
class Fido2CallerCancellationTest {

    private val coordinator = Fido2PromptCoordinator()

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

    /** The connecting coroutine: an IO coroutine that blocks in a runBlocking, like the SSH auth does. */
    private fun kotlinx.coroutines.CoroutineScope.connect(
        request: Fido2ChallengeRequest,
        passCaller: Boolean = true,
    ): Job = launch(Dispatchers.IO) {
        val caller = currentCoroutineContext()[Job]
        runBlocking {
            requestFido2Signature(coordinator::prompt, request, if (passCaller) caller else null) { _, _ -> }
        }
    }

    private suspend fun awaitShown(request: Fido2ChallengeRequest) {
        withTimeout(5_000) { coordinator.state.first { it?.request === request } }
    }

    private suspend fun awaitNothingShown() {
        withTimeout(5_000) { coordinator.state.first { it == null } }
    }

    @Test
    fun `cancelling the connection removes the prompt it shows and zeroes the data`() = runBlocking {
        val request = request()
        val connection = connect(request)
        awaitShown(request)

        connection.cancel()
        withTimeout(5_000) { connection.join() }

        assertNull(coordinator.state.value)
        assertTrue(isWiped(request.signingData), "signing data must be zeroed")
        assertTrue(connection.isCancelled)
    }

    @Test
    fun `without the caller the prompt outlives the cancelled connection (what the Job fixes)`() = runBlocking {
        val request = request()
        val connection = connect(request, passCaller = false)
        awaitShown(request)

        connection.cancel()
        delay(300)

        // The blocked runBlocking knows nothing of the cancellation: the sheet is still up.
        assertNotNull(coordinator.state.value)
        coordinator.cancel(request)
        withTimeout(5_000) { connection.join() }
    }

    @Test
    fun `a queued prompt of a cancelled connection is never shown`() = runBlocking {
        val first = request(fill = 1)
        val second = request(fill = 2)
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
        val first = request(fill = 1)
        val second = request(fill = 2)
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
    fun `a signature that arrives as the connection is cancelled is wiped`() = runBlocking {
        val signature = signature()
        val connection = launch(Dispatchers.IO) {
            val caller = currentCoroutineContext()[Job]
            runBlocking {
                requestFido2Signature(
                    callback = {
                        // The answer comes in, and the connection is cancelled in the same breath.
                        caller?.cancel()
                        Fido2ChallengeResponse.Signed(signature)
                    },
                    request = request(),
                    caller = caller,
                ) { _, _ -> }
            }
        }
        withTimeout(5_000) { connection.join() }

        assertTrue(connection.isCancelled)
        assertTrue(isWiped(signature.rawSignature), "nobody will use this signature: it must be wiped")
    }

    @Test
    fun `a connection that completes normally still gets its signature`() = runBlocking {
        val request = request()
        val signature = signature()
        var received: SkSshSignature? = null
        val connection = launch(Dispatchers.IO) {
            val caller = currentCoroutineContext()[Job]
            received = runBlocking { requestFido2Signature(coordinator::prompt, request, caller) { _, _ -> } }
        }
        awaitShown(request)

        coordinator.complete(request, signature)
        withTimeout(5_000) { connection.join() }

        assertSame(signature, received)
        assertEquals(false, connection.isCancelled)
        awaitNothingShown()
    }

    // The production wiring of the caller Job is run in SshSessionManagerConnectTest (fake client,
    // real SkAuthMethod) and guarded in the source by SshSessionManagerWiringGuardTest.
}
