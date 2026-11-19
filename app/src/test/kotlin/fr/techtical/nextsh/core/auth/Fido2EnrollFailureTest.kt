// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandException
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.PinInvalidClientError
import com.yubico.yubikit.fido.client.PinRequiredClientError
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException

/**
 * What the failure of an enrollment on a key is told as: [enrollFailureOf] turns what YubiKit
 * throws (a CTAP error, a client error that wraps one, a lost link) into a reason the screen
 * turns into a sentence of the user's language.
 */
class Fido2EnrollFailureTest {

    private fun ctap(code: Byte) = CtapException(code)

    @Test
    fun `the CTAP errors a key answers with are told by their meaning`() {
        val expected = mapOf(
            CtapException.ERR_PIN_REQUIRED to EnrollFailure.PinRequired,
            CtapException.ERR_PIN_INVALID to EnrollFailure.PinIncorrect(triesRemaining = null),
            CtapException.ERR_PIN_NOT_SET to EnrollFailure.PinNotSet,
            CtapException.ERR_PIN_BLOCKED to EnrollFailure.PinBlocked,
            CtapException.ERR_PIN_AUTH_BLOCKED to EnrollFailure.PinAuthBlocked,
            CtapException.ERR_USER_ACTION_TIMEOUT to EnrollFailure.TouchTimeout,
            CtapException.ERR_ACTION_TIMEOUT to EnrollFailure.TouchTimeout,
            CtapException.ERR_OPERATION_DENIED to EnrollFailure.Refused,
            CtapException.ERR_UP_REQUIRED to EnrollFailure.Refused,
            CtapException.ERR_UNSUPPORTED_ALGORITHM to EnrollFailure.UnsupportedKey,
            CtapException.ERR_UNSUPPORTED_EXTENSION to EnrollFailure.UnsupportedKey,
            CtapException.ERR_UNSUPPORTED_OPTION to EnrollFailure.UnsupportedKey,
            CtapException.ERR_INVALID_OPTION to EnrollFailure.UnsupportedKey,
            CtapException.ERR_OTHER to EnrollFailure.Unexpected,
        )

        expected.forEach { (code, failure) ->
            assertEquals(failure, enrollFailureOf(ctap(code)), "CTAP error $code")
        }
    }

    @Test
    fun `a CTAP error wrapped in a client error is told by its meaning`() {
        // The WebAuthn client wraps what the key answers (but the wrong PIN) in a ClientError.
        assertEquals(
            EnrollFailure.PinBlocked,
            enrollFailureOf(ClientError(ClientError.Code.OTHER_ERROR, ctap(CtapException.ERR_PIN_BLOCKED))),
        )
        assertEquals(
            EnrollFailure.UnsupportedKey,
            enrollFailureOf(ClientError(ClientError.Code.OTHER_ERROR, ctap(CtapException.ERR_UNSUPPORTED_ALGORITHM))),
        )
    }

    @Test
    fun `a CTAP error the table does not know falls back on the code the client sorted it under`() {
        // ERR_TIMEOUT (0x05) reaches the client as a TIMEOUT: it was told as a touch timeout without
        // its cause, and an unexpected failure with it.
        assertEquals(
            EnrollFailure.TouchTimeout,
            enrollFailureOf(ClientError(ClientError.Code.TIMEOUT, ctap(CtapException.ERR_TIMEOUT))),
        )
        // ERR_NO_CREDENTIALS (0x2E) is a DEVICE_INELIGIBLE for the client.
        assertEquals(
            EnrollFailure.UnsupportedKey,
            enrollFailureOf(ClientError(ClientError.Code.DEVICE_INELIGIBLE, ctap(0x2E))),
        )
        // Nothing more to read in the code either.
        assertEquals(
            EnrollFailure.Unexpected,
            enrollFailureOf(ClientError(ClientError.Code.OTHER_ERROR, ctap(CtapException.ERR_OTHER))),
        )
    }

    @Test
    fun `what the key said goes before the code the client sorted it under`() {
        assertEquals(
            EnrollFailure.PinBlocked,
            enrollFailureOf(ClientError(ClientError.Code.TIMEOUT, ctap(CtapException.ERR_PIN_BLOCKED))),
        )
    }

    @Test
    fun `a full key is an unexpected failure, not a key that cannot do SSH`() {
        // YubiKit files KEY_STORE_FULL under CONFIGURATION_UNSUPPORTED.
        assertEquals(
            EnrollFailure.Unexpected,
            enrollFailureOf(ClientError(ClientError.Code.CONFIGURATION_UNSUPPORTED, ctap(CtapException.ERR_KEY_STORE_FULL))),
        )
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(ctap(CtapException.ERR_KEY_STORE_FULL)))
    }

    @Test
    fun `a client error without a CTAP error is told by its own code`() {
        assertEquals(EnrollFailure.TouchTimeout, enrollFailureOf(ClientError(ClientError.Code.TIMEOUT, "x")))
        assertEquals(
            EnrollFailure.UnsupportedKey,
            enrollFailureOf(ClientError(ClientError.Code.CONFIGURATION_UNSUPPORTED, "x")),
        )
        assertEquals(
            EnrollFailure.UnsupportedKey,
            enrollFailureOf(ClientError(ClientError.Code.DEVICE_INELIGIBLE, "x")),
        )
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(ClientError(ClientError.Code.BAD_REQUEST, "x")))
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(ClientError(ClientError.Code.OTHER_ERROR, "x")))
    }

    @Test
    fun `a PIN the key refuses carries the tries it has left`() {
        val refused = PinInvalidClientError(ctap(CtapException.ERR_PIN_INVALID), 6)

        assertEquals(EnrollFailure.PinIncorrect(6), enrollFailureOf(refused))
    }

    @Test
    fun `a PIN refused with no try left is a blocked key, not an incorrect PIN`() {
        val refused = PinInvalidClientError(ctap(CtapException.ERR_PIN_INVALID), 0)

        assertEquals(EnrollFailure.PinBlocked, enrollFailureOf(refused))
    }

    @Test
    fun `a key that wants a PIN nobody gave is a PinRequired`() {
        assertEquals(EnrollFailure.PinRequired, enrollFailureOf(PinRequiredClientError()))
    }

    @Test
    fun `a link that breaks is a key that went away`() {
        assertEquals(EnrollFailure.KeyRemoved, enrollFailureOf(IOException("Tag was lost")))
    }

    @Test
    fun `a key with no FIDO2 application is not a key to enroll on`() {
        assertEquals(EnrollFailure.UnsupportedKey, enrollFailureOf(ApplicationNotAvailableException("no FIDO2")))
    }

    @Test
    fun `any other failure is an unexpected one`() {
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(CommandException("x")))
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(IllegalArgumentException("x")))
        assertEquals(EnrollFailure.Unexpected, enrollFailureOf(IllegalStateException("x")))
    }

    @Test
    fun `the log says the kind and the code of a failure, never its message`() {
        val failures = listOf(
            ctap(CtapException.ERR_PIN_BLOCKED),
            ClientError(ClientError.Code.OTHER_ERROR, "the PIN is $LEAK_MARKER"),
            PinInvalidClientError(ctap(CtapException.ERR_PIN_INVALID), 2),
            IOException("the PIN is $LEAK_MARKER"),
            IllegalStateException("the PIN is $LEAK_MARKER"),
        )
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            failures.forEach { enrollFailureOf(it) }
        } finally {
            Timber.uproot(tree)
        }

        assertEquals(failures.size, tree.entries.size, "one line per failure")
        tree.entries.forEach {
            assertFalse(it.message.contains(LEAK_MARKER), "the log leaks the message: ${it.message}")
            assertNull(it.throwable, "a throwable would print its message")
        }
        assertTrue(tree.entries.any { it.message.contains("0x32") }, "the CTAP code is logged: ${tree.entries}")
    }
}
