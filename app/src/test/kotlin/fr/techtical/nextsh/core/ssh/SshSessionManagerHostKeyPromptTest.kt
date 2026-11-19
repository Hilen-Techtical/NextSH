// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import io.mockk.every
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * How a connection reads the end of its host-key prompt. For SSHJ a key nobody confirmed, for lack
 * of a screen or of time, is the same refusal as a key that changed (`HOST_KEY_NOT_VERIFIABLE`); the
 * client factory marks the first kind (see [HostKeyPromptEndedException]) and every connect path must
 * read the mark first, or a tunnel reconnecting in the background with no one in front of the phone
 * would be told that the server's key changed.
 */
class SshSessionManagerHostKeyPromptTest {

    private val harness = SessionHarness()
    private val manager = harness.manager

    init {
        // A string is its resource id, so a test can tell which message the user would read.
        every { harness.context.getString(any<Int>()) } answers { "res:${firstArg<Int>()}" }
        every { harness.context.getString(any<Int>(), *anyVararg<Any>()) } answers { "res:${firstArg<Int>()}" }
    }

    /** What SSHJ throws from `client.connect()` when the verifier refuses the key. */
    private fun refusal() = TransportException(
        DisconnectReason.HOST_KEY_NOT_VERIFIABLE,
        "Could not verify `ssh-ed25519` host key",
    )

    private fun factoryFailing(failure: Throwable) {
        manager.clientFactory = { throw failure }
    }

    private val ecKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair().public

    private fun everyConnectPath(): List<Pair<String, () -> SshResult<*>>> = listOf(
        "password" to { runBlocking { manager.connectWithPassword(harness.host, "secret".toCharArray()) } },
        "key" to { runBlocking { manager.connectWithKey(harness.host, "pem", null) } },
        "certificate" to { runBlocking { manager.connectWithCertificate(harness.host, "pem", "cert", null) } },
        "biometric key" to {
            runBlocking { manager.connectWithBiometricKey(harness.host, ecKey, "label") { error("never opened") } }
        },
    )

    private fun assertError(code: SshErrorCode, message: Int, result: SshResult<*>, path: String) {
        assertTrue(result is SshResult.Error, "$path: $result")
        result as SshResult.Error
        assertEquals(code, result.code, path)
        assertEquals("res:$message", result.message, path)
    }

    @Test
    fun `a prompt with no screen is not a key that changed, on every connect path`() {
        factoryFailing(HostKeyPromptEndedException(HostKeyPromptEnd.NO_SCREEN, refusal()))

        for ((path, connect) in everyConnectPath()) {
            val result = connect()

            assertError(SshErrorCode.FIDO2_TIMEOUT, R.string.auth_error_host_key_no_screen, result, path)
            assertNotEquals(SshErrorCode.HOST_KEY_MISMATCH, (result as SshResult.Error).code, path)
            assertTrue(result.code.isNonRetryable(), "$path: the reconnection must not open it again by itself")
        }
    }

    @Test
    fun `a prompt nobody answered in time says so, on every connect path`() {
        factoryFailing(HostKeyPromptEndedException(HostKeyPromptEnd.TIMED_OUT, refusal()))

        for ((path, connect) in everyConnectPath()) {
            assertError(SshErrorCode.FIDO2_TIMEOUT, R.string.auth_error_host_key_timeout, connect(), path)
        }
    }

    @Test
    fun `a connection cancelled with its prompt reads as cancelled`() {
        factoryFailing(HostKeyPromptEndedException(HostKeyPromptEnd.CANCELLED, refusal()))

        for ((path, connect) in everyConnectPath()) {
            assertError(SshErrorCode.FIDO2_USER_CANCELLED, R.string.auth_error_fido2_cancelled, connect(), path)
        }
    }

    @Test
    fun `a key that really does not match, or that the user refused, keeps its reading`() {
        factoryFailing(refusal())

        for ((path, connect) in everyConnectPath()) {
            assertError(SshErrorCode.HOST_KEY_MISMATCH, R.string.auth_error_host_key, connect(), path)
        }
    }

    @Test
    fun `a network failure with no prompt in it is a network failure`() {
        factoryFailing(IOException("Connection refused"))

        for ((path, connect) in everyConnectPath()) {
            val result = connect()

            assertTrue(result is SshResult.Error, path)
            assertNotEquals(SshErrorCode.HOST_KEY_MISMATCH, (result as SshResult.Error).code, path)
            assertNotEquals(SshErrorCode.FIDO2_TIMEOUT, result.code, path)
        }
    }

    // ── The wait of the key exchange covers the whole prompt ──────────────────

    @Test
    fun `the key exchange of a server with no stored key waits for the prompt and the server`() {
        assertEquals(90_000, keyExchangeTimeoutMs(hostKeyStored = false))
        assertEquals(
            HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS + TRANSPORT_TIMEOUT_MS,
            keyExchangeTimeoutMs(hostKeyStored = false).toLong(),
        )
    }

    @Test
    fun `the wait is longer than the deadline of the prompt by the time the server needs`() {
        // The prompt is shown during the exchange: the wait must not end before the user's minute does.
        assertTrue(HOST_KEY_KEX_TRANSPORT_TIMEOUT_MS >= HostKeyPromptCoordinator.PROMPT_TIMEOUT_MS + TRANSPORT_TIMEOUT_MS)
    }

    @Test
    fun `a known server keeps the 30 seconds, a server that goes quiet does not hold a reconnection longer`() {
        assertEquals(TRANSPORT_TIMEOUT_MS, keyExchangeTimeoutMs(hostKeyStored = true))
        assertEquals(30_000, TRANSPORT_TIMEOUT_MS)
    }

    // The wiring of createSshClient (the wait raised around client.connect, the verifier and its prompt,
    // the marker thrown on failure) is guarded by SshSessionManagerWiringGuardTest, which reads the body
    // of the function and the lambda of the call, not the whole file.
}
