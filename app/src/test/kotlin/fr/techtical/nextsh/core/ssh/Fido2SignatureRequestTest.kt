// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import android.content.Context
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.auth.HwKeyError
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshSignature
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * A FIDO2 connection that fails in the authentication says why: the user cancelled, nobody
 * answered, the key failed (and for which reason), or the server refused. The user reads
 * "connection cancelled" after Cancel, and the automatic reconnection knows not to ask again.
 */
class Fido2SignatureRequestTest {

    private fun request() = Fido2ChallengeRequest(
        signingData = ByteArray(8) { 1 },
        rpId = "ssh:",
        keyType = SkKeyType.SK_ED25519,
    )

    private fun signature() = SkSshSignature.create(
        keyType = SkKeyType.SK_ED25519,
        rawSignature = ByteArray(64) { 7 },
        flags = SkSshSignature.FLAG_USER_PRESENT,
        counter = 1u,
    )

    /** What [requestFido2Signature] reported: the ending, and the reason of a key error. */
    private data class Ending(val end: Fido2PromptEnd, val keyError: HwKeyError?)

    // ── How the prompt ended ──────────────────────────────────────────────────

    @Test
    fun `a signature is returned and the prompt did not end without one`() = runTest {
        val signature = signature()
        val ends = mutableListOf<Ending>()

        val result = requestFido2Signature({ Fido2ChallengeResponse.Signed(signature) }, request(), caller = null) { end, reason ->
            ends += Ending(end, reason)
        }

        assertSame(signature, result)
        assertEquals(emptyList<Ending>(), ends)
    }

    @Test
    fun `a cancel is reported as cancelled`() = runTest {
        val ends = mutableListOf<Ending>()

        val result = requestFido2Signature({ Fido2ChallengeResponse.Cancelled }, request(), caller = null) { end, reason ->
            ends += Ending(end, reason)
        }

        assertNull(result)
        assertEquals(listOf(Ending(Fido2PromptEnd.CANCELLED, null)), ends)
    }

    @Test
    fun `no answer is reported as timed out`() = runTest {
        val ends = mutableListOf<Ending>()

        val result = requestFido2Signature({ null }, request(), caller = null) { end, reason ->
            ends += Ending(end, reason)
        }

        assertNull(result)
        assertEquals(listOf(Ending(Fido2PromptEnd.TIMED_OUT, null)), ends)
    }

    @Test
    fun `a key error is reported as a key error with its reason, neither a cancel nor a timeout`() = runTest {
        val ends = mutableListOf<Ending>()

        val result = requestFido2Signature(
            { Fido2ChallengeResponse.KeyError(HwKeyError.PinBlocked) },
            request(),
            caller = null,
        ) { end, reason -> ends += Ending(end, reason) }

        assertNull(result)
        assertEquals(listOf(Ending(Fido2PromptEnd.KEY_ERROR, HwKeyError.PinBlocked)), ends)
    }

    // ── What the user reads and what the code says ────────────────────────────

    @Test
    fun `each ending has its own error code`() {
        assertEquals(SshErrorCode.AUTH_FAILED, fido2AuthFailureCode(Fido2PromptEnd.NONE))
        assertEquals(SshErrorCode.FIDO2_USER_CANCELLED, fido2AuthFailureCode(Fido2PromptEnd.CANCELLED))
        assertEquals(SshErrorCode.FIDO2_TIMEOUT, fido2AuthFailureCode(Fido2PromptEnd.TIMED_OUT))
        // A key error is an authentication failure (not retried) with a message of its own.
        assertEquals(SshErrorCode.AUTH_FAILED, fido2AuthFailureCode(Fido2PromptEnd.KEY_ERROR))
    }

    @Test
    fun `a cancel does not read as an authentication failure`() {
        assertEquals(R.string.auth_error_failed_fido2, fido2AuthFailureMessageRes(Fido2PromptEnd.NONE))
        assertEquals(R.string.auth_error_fido2_cancelled, fido2AuthFailureMessageRes(Fido2PromptEnd.CANCELLED))
        assertEquals(R.string.auth_error_fido2_timeout, fido2AuthFailureMessageRes(Fido2PromptEnd.TIMED_OUT))
        assertEquals(R.string.auth_error_fido2_key_error, fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR))
        assertNotEquals(
            fido2AuthFailureMessageRes(Fido2PromptEnd.NONE),
            fido2AuthFailureMessageRes(Fido2PromptEnd.CANCELLED),
        )
    }

    @Test
    fun `no two endings share a message`() {
        val messages = Fido2PromptEnd.entries.map { fido2AuthFailureMessageRes(it) }

        assertEquals(messages.size, messages.toSet().size, "$messages")
    }

    @Test
    fun `every ending is an authentication failure, so none of them is retried`() {
        for (end in Fido2PromptEnd.entries) {
            assertEquals(true, fido2AuthFailureCode(end).isNonRetryable(), "$end")
        }
    }

    // ── The reason of a key error reaches the message ─────────────────────────

    @Test
    fun `a key error reads as its reason, not as the same sentence for every reason`() {
        val reasons = listOf(
            HwKeyError.NoMatchingCredential,
            HwKeyError.PinIncorrect(triesRemaining = null),
            HwKeyError.PinBlocked,
            HwKeyError.PinAuthBlocked,
            HwKeyError.PinNotSet,
            HwKeyError.KeyRemoved,
            HwKeyError.UnsupportedKey,
            HwKeyError.TouchTimeout,
            HwKeyError.Refused,
            HwKeyError.PinUiUnavailable,
        )

        val messages = reasons.map { fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, it) }

        assertEquals(messages.size, messages.toSet().size, "a reason must not read as another: $messages")
        assertEquals(R.string.fido2_pin_blocked, fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, HwKeyError.PinBlocked))
        assertEquals(
            R.string.fido2_error_no_matching_credential,
            fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, HwKeyError.NoMatchingCredential),
        )
    }

    @Test
    fun `a key error without a reason, or an unexpected one, keeps the general key message`() {
        assertEquals(R.string.auth_error_fido2_key_error, fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, null))
        assertEquals(
            R.string.auth_error_fido2_key_error,
            fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, HwKeyError.Unexpected),
        )
    }

    @Test
    fun `a reason does not change what a cancel or a timeout reads`() {
        assertEquals(
            R.string.auth_error_fido2_cancelled,
            fido2AuthFailureMessageRes(Fido2PromptEnd.CANCELLED, HwKeyError.PinBlocked),
        )
        assertEquals(
            R.string.auth_error_fido2_timeout,
            fido2AuthFailureMessageRes(Fido2PromptEnd.TIMED_OUT, HwKeyError.PinBlocked),
        )
    }

    @Test
    fun `the tries left are part of the message of a refused PIN`() {
        val context = mockk<Context>()
        every { context.getString(R.string.fido2_error_pin_incorrect_tries, 5) } returns "tries:5"
        every { context.getString(R.string.fido2_error_pin_incorrect) } returns "incorrect"
        every { context.getString(R.string.fido2_pin_blocked) } returns "blocked"

        assertEquals(
            "tries:5",
            fido2AuthFailureMessage(context, Fido2PromptEnd.KEY_ERROR, HwKeyError.PinIncorrect(triesRemaining = 5)),
        )
        assertEquals(
            "incorrect",
            fido2AuthFailureMessage(context, Fido2PromptEnd.KEY_ERROR, HwKeyError.PinIncorrect(triesRemaining = null)),
        )
        assertEquals("blocked", fido2AuthFailureMessage(context, Fido2PromptEnd.KEY_ERROR, HwKeyError.PinBlocked))
    }

    @Test
    fun `a key with no PIN set is told so on a failed connection, not as an unexpected error`() {
        assertEquals(
            R.string.fido2_error_pin_not_set,
            fido2AuthFailureMessageRes(Fido2PromptEnd.KEY_ERROR, HwKeyError.PinNotSet),
        )
        assertEquals(R.string.fido2_error_pin_not_set, hwKeyErrorMessageRes(HwKeyError.PinNotSet))
        assertEquals(emptyList<Any>(), hwKeyErrorMessageArgs(HwKeyError.PinNotSet).toList())
    }

    @Test
    fun `every reason has a sentence of its own for the sheet and the connection`() {
        val reasons = listOf(
            HwKeyError.NoMatchingCredential,
            HwKeyError.PinIncorrect(triesRemaining = null),
            HwKeyError.PinIncorrect(triesRemaining = 3),
            HwKeyError.PinBlocked,
            HwKeyError.PinAuthBlocked,
            HwKeyError.PinNotSet,
            HwKeyError.KeyRemoved,
            HwKeyError.UnsupportedKey,
            HwKeyError.TouchTimeout,
            HwKeyError.Refused,
            HwKeyError.PinUiUnavailable,
            HwKeyError.Unexpected,
        )

        val sentences = reasons.map { hwKeyErrorMessageRes(it) }

        assertEquals(sentences.size, sentences.toSet().size, "$sentences")
        assertEquals(emptyList<Any>(), hwKeyErrorMessageArgs(HwKeyError.PinBlocked).toList())
        assertEquals(listOf<Any>(3), hwKeyErrorMessageArgs(HwKeyError.PinIncorrect(triesRemaining = 3)).toList())
    }
}
