// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV1
import com.yubico.yubikit.fido.ctap.PinUvAuthProtocolV2
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import java.io.IOException

/** How long a YubiKey waits for the touch before it gives up: about 30 s, in nanoseconds. */
internal const val KEY_TOUCH_WAIT_NANOS = 30_000_000_000L

/** Hands out the prepared [Ctap2Session]s in order, one per connection, and keeps a log of the exchange. */
internal class FakeConnector(
    sessions: List<Ctap2Session>,
    private val log: MutableList<String> = mutableListOf(),
) : Ctap2Connector {

    private val queue = ArrayDeque(sessions)
    val connected = mutableListOf<SecurityKey>()

    /** Fails the next connections with this, when set. */
    var failure: Exception? = null

    /** Makes closing a connection fail with this, when set (the key is already gone). */
    var closeFailure: IOException? = null

    /** Runs first in every connection attempt, on the thread that connects: a key slow to answer. */
    var beforeConnect: (() -> Unit)? = null

    override fun connect(key: SecurityKey): Ctap2Link {
        beforeConnect?.invoke()
        failure?.let { throw it }
        connected += key
        log += "connect:${key.transport}"
        val connection = mockk<YubiKeyConnection>(relaxed = true)
        every { connection.close() } answers {
            log += "close"
            closeFailure?.let { throw it }
        }
        return Ctap2Link(queue.removeFirst(), connection)
    }
}

/** What a fake key answers to an assertion request. */
internal sealed interface Reply {
    data object Assertion : Reply
    data class Fail(val code: Byte) : Reply
}

/** A [Ctap2Session] mock of a key that speaks the PIN/UV [protocols] (2 and 1 by default). */
internal fun fakeCtap(protocols: List<Int> = listOf(2, 1)): Ctap2Session {
    val info = mockk<Ctap2Session.InfoData>(relaxed = true)
    every { info.pinUvAuthProtocols } returns protocols
    val ctap = mockk<Ctap2Session>(relaxed = true)
    every { ctap.cachedInfo } returns info
    return ctap
}

/** An assertion as the key would return it: user present, counter 7, a signature of 64 bytes. */
internal fun fakeAssertion(credentialId: ByteArray): Ctap2Session.AssertionData {
    val authData = ByteArray(37).also {
        it[32] = 0x01
        it[36] = 0x07
    }
    val assertion = mockk<Ctap2Session.AssertionData>(relaxed = true)
    every { assertion.authenticatorData } returns authData
    every { assertion.signature } returns ByteArray(64) { 0x42 }
    every { assertion.getCredentialId(any()) } returns credentialId
    return assertion
}

/**
 * Makes the key answer [withoutPin] to the first request (no PIN proof) and [withPin] to the one
 * that carries a pinUvAuthParam.
 */
internal fun Ctap2Session.answers(
    credentialId: ByteArray,
    withoutPin: Reply = Reply.Assertion,
    withPin: Reply = Reply.Assertion,
) {
    every { getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
        // Arguments: rpId, clientDataHash, allowList, extensions, options, pinUvAuthParam, protocol, state.
        val reply = if (arg<ByteArray?>(5) == null) withoutPin else withPin
        when (reply) {
            is Reply.Fail -> throw CtapException(reply.code)
            Reply.Assertion -> listOf(fakeAssertion(credentialId))
        }
    }
}

/**
 * Stands in for the PIN side of the key. [ClientPin] and the protocol are built inside the
 * authenticator, so their constructors are the seam. Call [unmockkAll] afterwards.
 */
internal class FakePin(
    /** Tries the key reports. */
    var retries: Int = 8,
    /** Whether a PIN is the right one; a wrong one makes the key answer ERR_PIN_INVALID. */
    var accepts: (String) -> Boolean = { it == CORRECT_PIN },
    /** Set to make the key answer with this error whatever the PIN is. */
    var failWith: Byte? = null,
) {
    /** PINs that reached the key, in order (test values only). */
    val received = mutableListOf<String>()

    /** PINs the library refused before they went out (under 4 characters), in order. */
    val refusedByLibrary = mutableListOf<String>()

    /** The permissions and relying party of every PIN token asked for, in order. */
    val tokenRequests = mutableListOf<Pair<Int, String?>>()

    /** The PIN tokens handed out (the very arrays, to check that they are wiped). */
    val tokens = mutableListOf<ByteArray>()

    /** Copies of what was authenticated, with the protocol version: the token, then the data. */
    val authenticated = mutableListOf<Triple<Int, ByteArray, ByteArray>>()

    /** The proofs handed out (the very arrays, to check that they are wiped). */
    val proofs = mutableListOf<ByteArray>()

    init {
        mockkConstructor(PinUvAuthProtocolV1::class)
        mockkConstructor(PinUvAuthProtocolV2::class)
        mockkConstructor(ClientPin::class)
        every { anyConstructed<PinUvAuthProtocolV1>().version } returns 1
        every { anyConstructed<PinUvAuthProtocolV1>().authenticate(any(), any()) } answers {
            authenticated += Triple(1, arg<ByteArray>(0).copyOf(), arg<ByteArray>(1).copyOf())
            ByteArray(16) { 0x11 }.also { proofs += it }
        }
        every { anyConstructed<PinUvAuthProtocolV2>().version } returns 2
        every { anyConstructed<PinUvAuthProtocolV2>().authenticate(any(), any()) } answers {
            authenticated += Triple(2, arg<ByteArray>(0).copyOf(), arg<ByteArray>(1).copyOf())
            ByteArray(16) { 0x22 }.also { proofs += it }
        }
        val reportedRetries = mockk<ClientPin.PinRetries>()
        every { reportedRetries.count } answers { retries }
        every { anyConstructed<ClientPin>().getPinRetries() } returns reportedRetries
        every { anyConstructed<ClientPin>().getPinToken(any(), any(), any()) } answers {
            val pin = String(arg<CharArray>(0))
            // ClientPin.preparePin throws before anything is sent to the key.
            if (pin.length < 4) {
                refusedByLibrary += pin
                throw IllegalArgumentException("PIN must be at least 4 characters")
            }
            received += pin
            tokenRequests += arg<Int>(1) to arg<String?>(2)
            failWith?.let { throw CtapException(it) }
            if (!accepts(pin)) throw CtapException(CtapException.ERR_PIN_INVALID)
            ByteArray(32) { 0x11 }.also { tokens += it }
        }
    }

    companion object {
        const val CORRECT_PIN = "correct-pin"
    }
}
