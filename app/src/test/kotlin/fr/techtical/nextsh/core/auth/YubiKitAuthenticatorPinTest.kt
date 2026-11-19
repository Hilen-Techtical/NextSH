// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.Ctap2Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * The PIN side of [YubiKitAuthenticator]: over USB the PIN is asked on the open connection, over
 * NFC the connection is closed while it is typed and a new contact carries it.
 *
 * The keys are fakes (see AssertionTestSupport): the real exchange with a key is for a device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YubiKitAuthenticatorPinTest {

    private val rpId = "ssh:"
    private val credentialId = ByteArray(16) { 0xAB.toByte() }
    private val clientDataHash = ByteArray(32) { it.toByte() }

    private val backend = FakeSecurityKeyBackend()
    private val discovery = SecurityKeyDiscovery(backend, directExecutor)
    private val log = mutableListOf<String>()
    private val statuses = mutableListOf<HwKeyStatus>()
    private val prompts = mutableListOf<PinPrompt>()

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /** The monotonic clock of the authenticator, in nanoseconds: it only moves when a test says so. */
    private val clock = AtomicLong()

    private fun TestScope.authenticator(vararg sessions: Ctap2Session) =
        YubiKitAuthenticator(
            discovery,
            FakeConnector(sessions.toList(), log),
            StandardTestDispatcher(testScheduler),
            { clock.get() },
        )

    private suspend fun YubiKitAuthenticator.request(
        pinProvider: (suspend (PinPrompt) -> CharArray?)?,
    ): HwKeyResult = getAssertion(
        rpId = rpId,
        clientDataHash = clientDataHash.copyOf(),
        allowedCredentials = listOf(credentialId),
        requireUserVerification = false,
        onStatus = { statuses += it },
        pinProvider = pinProvider,
    )

    /** A key that wants a PIN before it signs. */
    private fun keyWithPin(): Ctap2Session =
        fakeCtap().also { it.answers(credentialId, withoutPin = Reply.Fail(CtapException.ERR_PIN_REQUIRED)) }

    /** Answers the prompt with each PIN of [pins] in turn, logging "pin" and remembering the prompt. */
    private fun typing(vararg pins: String): suspend (PinPrompt) -> CharArray? {
        val remaining = ArrayDeque(pins.toList())
        return { prompt ->
            prompts += prompt
            log += "pin"
            remaining.removeFirst().toCharArray()
        }
    }

    private fun assertError(expected: HwKeyError, result: HwKeyResult) {
        assertInstanceOf(HwKeyResult.Error::class.java, result)
        assertEquals(expected, (result as HwKeyResult.Error).reason)
    }

    // ── Assertion data ───────────────────────────────────────────────────────

    @Test
    fun `parseAssertionData extracts flags counter and signature correctly`() {
        val authData = ByteArray(37)
        authData[32] = 0x05.toByte()          // flags
        authData[33] = 0x00; authData[34] = 0x00; authData[35] = 0x00; authData[36] = 0x07 // counter = 7
        val sig = ByteArray(64) { 0xBE.toByte() }

        val mockData = mockk<Ctap2Session.AssertionData>()
        every { mockData.authenticatorData } returns authData
        every { mockData.signature } returns sig
        every { mockData.getCredentialId(any()) } returns credentialId

        val authenticator = YubiKitAuthenticator(discovery, FakeConnector(emptyList()), Dispatchers.Unconfined)
        val result = authenticator.parseAssertionData(mockData, emptyList(), listOf(credentialId))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        val success = result as HwKeyResult.Success
        assertEquals(0x05.toByte(), success.flags, "flags mismatch")
        assertEquals(7u, success.counter, "counter mismatch")
        assertTrue(success.signature.contentEquals(sig), "signature mismatch")
    }

    @Test
    fun `an answer without the user present flag is an unexpected error, not a signature`() {
        val authData = ByteArray(37)
        authData[32] = 0x04.toByte() // user verified, but not user present
        val mockData = mockk<Ctap2Session.AssertionData>()
        every { mockData.authenticatorData } returns authData
        every { mockData.signature } returns ByteArray(64) { 0xBE.toByte() }
        every { mockData.getCredentialId(any()) } returns credentialId

        val authenticator = YubiKitAuthenticator(discovery, FakeConnector(emptyList()), Dispatchers.Unconfined)
        val result = authenticator.parseAssertionData(mockData, emptyList(), listOf(credentialId))

        assertError(HwKeyError.Unexpected, result)
        assertTrue(authData.all { it == 0.toByte() }, "the authenticator data is wiped on this path too")
    }

    // ── USB: the PIN is asked on the open connection ──────────────────────────

    @Test
    fun `USB asks the PIN while the connection is open and signs with it`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(typing(FakePin.CORRECT_PIN))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(listOf("connect:USB", "pin", "close"), log)
        assertEquals(listOf(PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false)), prompts)
    }

    @Test
    fun `the key is not given to another request while the PIN is typed over USB`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        discovery.acquire() // the enrollment screen's own lease
        val typing = CompletableDeferred<CharArray?>()
        val signing = async { authenticator(keyWithPin()).request { typing.await() } }
        runCurrent()
        assertEquals(listOf("connect:USB"), log, "the connection is open while the PIN is typed")

        val enrolling = async { discovery.claimKey(KeyUse.ENROLLMENT) }
        runCurrent()
        assertFalse(enrolling.isCompleted, "an enrollment must not open a second connection on the key")

        typing.complete(FakePin.CORRECT_PIN.toCharArray())
        runCurrent()

        assertInstanceOf(HwKeyResult.Success::class.java, signing.await())
        assertEquals(listOf("connect:USB", "close"), log)
        assertTrue(enrolling.isCompleted, "the key is free once the connection is closed")
        enrolling.await().close()
    }

    @Test
    fun `cancelling while the PIN is typed over USB closes the connection and frees the key`() = runTest {
        FakePin()
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire() // a lease of the test, so that the discovery still knows the key
        val typing = CompletableDeferred<CharArray?>()
        val signing = async { authenticator(keyWithPin()).request { typing.await() } }
        runCurrent()
        assertEquals(listOf("connect:USB"), log, "the connection is open while the PIN is typed")

        signing.cancel()
        runCurrent()

        assertTrue(signing.isCancelled, "a cancellation must not become a result")
        assertEquals(listOf("connect:USB", "close"), log)
        discovery.assertKeyFree(key)
        keep.close()
    }

    @Test
    fun `a cancelled PIN dialog and a wrong PIN also free the key`() = runTest {
        FakePin(accepts = { false })
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire()

        assertInstanceOf(HwKeyResult.UserCancelled::class.java, authenticator(keyWithPin()).request { null })
        discovery.assertKeyFree(key)

        val result = authenticator(keyWithPin()).request { "nope".toCharArray() }
        assertInstanceOf(HwKeyResult.Error::class.java, result)
        discovery.assertKeyFree(key)
        keep.close()
    }

    @Test
    fun `the PIN proof is sent without uv`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        val key = keyWithPin()

        authenticator(key).request(typing(FakePin.CORRECT_PIN))

        verify(exactly = 1) {
            // The attempt with the proof: options hold up only, protocol 2.
            key.getAssertions(any(), any(), any(), any(), mapOf("up" to true), ofType<ByteArray>(), 2, any())
        }
    }

    @Test
    fun `a cancelled PIN dialog is a cancellation`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request { null }

        assertInstanceOf(HwKeyResult.UserCancelled::class.java, result)
        assertEquals(listOf("connect:USB", "close"), log)
    }

    @Test
    fun `a key that wants a PIN without any way to ask is an error of the PIN interface`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(null)

        assertError(HwKeyError.PinUiUnavailable, result)
    }

    @Test
    fun `a key that wants a PIN but has none set says so, not an unexpected error`() = runTest {
        // CTAP2_ERR_PIN_NOT_SET comes back when the PIN token is asked: nothing the user types can fix it.
        FakePin(failWith = CtapException.ERR_PIN_NOT_SET)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(typing(FakePin.CORRECT_PIN))

        assertError(HwKeyError.PinNotSet, result)
        assertEquals(listOf("connect:USB", "pin", "close"), log)
    }

    @Test
    fun `a wrong PIN is asked again with the tries left`() = runTest {
        val pin = FakePin(accepts = { it == "right" })
        backend.pluggedAtStart += usbKey()
        var calls = 0
        val provider: suspend (PinPrompt) -> CharArray? = { prompt ->
            prompts += prompt
            if (calls++ == 0) {
                pin.retries = 7 // what the key will report after refusing the first PIN
                "wrong".toCharArray()
            } else {
                "right".toCharArray()
            }
        }

        val result = authenticator(keyWithPin()).request(provider)

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(
            listOf(
                PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false),
                PinPrompt(triesRemaining = 7, incorrect = true, secondContact = false),
            ),
            prompts,
        )
        assertEquals(listOf("wrong", "right"), pin.received)
    }

    @Test
    fun `a touch that times out on the key does not ask for the PIN again while the token holds`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        var proofs = 0
        val key = fakeCtap()
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<ByteArray?>(5) == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            // With the PIN proof: the key gives up on the touch once, then gets it.
            clock.addAndGet(KEY_TOUCH_WAIT_NANOS)
            if (proofs++ == 0) throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
            listOf(fakeAssertion(credentialId))
        }

        val result = authenticator(key).request(typing(FakePin.CORRECT_PIN))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(1, prompts.size, "the PIN is not asked for again")
        assertEquals(listOf(FakePin.CORRECT_PIN), pin.received, "and the token is not asked for again")
        assertEquals(2, proofs)
    }

    @Test
    fun `a key that answers the touch timeout at once after the PIN is not asked for ever`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        var proofs = 0
        val key = fakeCtap()
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<ByteArray?>(5) == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            proofs++
            clock.addAndGet(100_000_000L) // 100 ms: it did not wait for anything
            throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
        }

        val result = authenticator(key).request(typing(FakePin.CORRECT_PIN))

        assertError(HwKeyError.TouchTimeout, result)
        assertEquals(3, proofs, "three quick answers in a row end it")
        assertEquals(1, prompts.size, "and the PIN is not asked for again")
        assertEquals(listOf(FakePin.CORRECT_PIN), pin.received)
    }

    @Test
    fun `a token the key dropped after the timeout asks for the PIN again and spends no try`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        var proofs = 0
        val key = fakeCtap()
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<ByteArray?>(5) == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            when (proofs++) {
                0 -> throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
                // The retry: the key no longer honours the token (it expired).
                1 -> throw CtapException(CtapException.ERR_PIN_AUTH_INVALID)
                else -> listOf(fakeAssertion(credentialId))
            }
        }

        val result = authenticator(key).request(typing(FakePin.CORRECT_PIN, FakePin.CORRECT_PIN))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(
            listOf(
                PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false),
                // A lost token is not a wrong PIN: no "incorrect", no try spent.
                PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false),
            ),
            prompts,
        )
        assertEquals(2, pin.received.size, "a new token was asked for")
    }

    @Test
    fun `a PIN the library refuses as too short is asked again and spends no try`() = runTest {
        val pin = FakePin(accepts = { it == "right" })
        backend.pluggedAtStart += usbKey()
        val typed = ArrayDeque(listOf("abc", "right"))
        val provider: suspend (PinPrompt) -> CharArray? = { prompt ->
            prompts += prompt
            typed.removeFirst().toCharArray()
        }

        val result = authenticator(keyWithPin()).request(provider)

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(listOf("abc"), pin.refusedByLibrary)
        assertEquals(listOf("right"), pin.received, "the key only saw the PIN that went out")
        assertEquals(
            listOf(
                PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false),
                // Not a retry after a wrong PIN: the key refused nothing, no try was spent.
                PinPrompt(triesRemaining = 8, incorrect = false, secondContact = false),
            ),
            prompts,
        )
    }

    @Test
    fun `a PIN that is never usable is not asked for ever`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        val provider: suspend (PinPrompt) -> CharArray? = { prompt ->
            prompts += prompt
            "abc".toCharArray()
        }

        val result = authenticator(keyWithPin()).request(provider)

        assertError(HwKeyError.Unexpected, result)
        assertEquals(3, prompts.size)
    }

    @Test
    fun `a PIN the library refuses is wiped`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        val typed = "abc".toCharArray()
        val provider: suspend (PinPrompt) -> CharArray? = { typed }

        authenticator(keyWithPin()).request(provider)

        assertTrue(typed.all { it == '\u0000' })
    }

    @Test
    fun `three wrong PINs end as an incorrect PIN with the tries left`() = runTest {
        val pin = FakePin(accepts = { false })
        backend.pluggedAtStart += usbKey()
        val provider: suspend (PinPrompt) -> CharArray? = { prompt ->
            prompts += prompt
            pin.retries--
            "nope".toCharArray()
        }

        val result = authenticator(keyWithPin()).request(provider)

        assertError(HwKeyError.PinIncorrect(triesRemaining = 5), result)
        assertEquals(3, prompts.size, "the key itself locks the PIN after three tries in a row")
    }

    @Test
    fun `a key that blocks the PIN ends the request as blocked`() = runTest {
        FakePin(failWith = CtapException.ERR_PIN_BLOCKED)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(typing("whatever"))

        assertError(HwKeyError.PinBlocked, result)
        assertEquals(listOf("connect:USB", "pin", "close"), log)
    }

    @Test
    fun `a key that needs a power cycle ends the request as auth blocked`() = runTest {
        FakePin(failWith = CtapException.ERR_PIN_AUTH_BLOCKED)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(typing("whatever"))

        assertError(HwKeyError.PinAuthBlocked, result)
    }

    @Test
    fun `a PIN already blocked is reported without asking for one`() = runTest {
        FakePin(retries = 0)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(typing("never asked"))

        assertError(HwKeyError.PinBlocked, result)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun `a PIN blocked at the very first try is reported as blocked`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        val key = fakeCtap().also {
            it.answers(credentialId, withoutPin = Reply.Fail(CtapException.ERR_PIN_BLOCKED))
        }

        val result = authenticator(key).request(typing("never asked"))

        assertError(HwKeyError.PinBlocked, result)
        assertTrue(prompts.isEmpty())
    }

    @Test
    fun `the PIN is wiped once it has been used`() = runTest {
        FakePin()
        backend.pluggedAtStart += usbKey()
        val typed = FakePin.CORRECT_PIN.toCharArray()

        authenticator(keyWithPin()).request { typed }

        assertTrue(typed.all { it == '\u0000' }, "the PIN must not stay in memory")
    }

    // ── NFC: a second contact carries the PIN ─────────────────────────────────

    private fun nfcReady() {
        discovery.onActivityResumed(mockk<Activity>())
    }

    @Test
    fun `NFC closes the connection while the PIN is typed and signs on a new contact`() = runTest {
        FakePin()
        nfcReady()
        val first = keyWithPin()
        val second = fakeCtap().also { it.answers(credentialId) }
        val result = async { authenticator(first, second).request(typing(FakePin.CORRECT_PIN)) }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()
        // The PIN has been asked, with the link already cut, and a new contact is awaited.
        assertEquals(listOf("connect:NFC", "close", "pin"), log)
        assertFalse(result.isCompleted)
        assertEquals(HwKeyStatus.REMOVE_AND_REAPPROACH, statuses.last())

        backend.tap(nfcKey())
        runCurrent()
        assertInstanceOf(HwKeyResult.Success::class.java, result.await())
        assertEquals(listOf("connect:NFC", "close", "pin", "connect:NFC", "close"), log)
        assertEquals(listOf(PinPrompt(triesRemaining = 8, incorrect = false, secondContact = true)), prompts)
        // The first contact only tried without PIN; the PIN proof came in the new connection.
        verify(exactly = 1) { first.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { second.getAssertions(any(), any(), any(), any(), any(), ofType<ByteArray>(), any(), any()) }
    }

    @Test
    fun `NFC says to take the key away before the PIN is typed, not after`() = runTest {
        FakePin()
        nfcReady()
        var shownWhenAsked: HwKeyStatus? = null
        val provider: suspend (PinPrompt) -> CharArray? = {
            // The statuses the user has read by the time the dialog opens.
            shownWhenAsked = statuses.last()
            FakePin.CORRECT_PIN.toCharArray()
        }
        val result = async { authenticator(keyWithPin(), fakeCtap().also { it.answers(credentialId) }).request(provider) }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()

        assertEquals(
            HwKeyStatus.PIN_THEN_REAPPROACH,
            shownWhenAsked,
            "the link is already cut: it must not still read that the key should not be removed",
        )
        assertEquals(
            listOf(
                HwKeyStatus.WAITING_FOR_KEY,
                HwKeyStatus.TALKING_TO_KEY,
                HwKeyStatus.PIN_THEN_REAPPROACH,
                // Once the PIN is typed, what is left to do is to bring the key back.
                HwKeyStatus.REMOVE_AND_REAPPROACH,
            ),
            statuses,
        )
        result.cancel()
    }

    @Test
    fun `NFC waits for a new tap, the tag left in the field does not count`() = runTest {
        FakePin()
        nfcReady()
        val result = async {
            authenticator(keyWithPin(), fakeCtap().also { it.answers(credentialId) }).request(typing(FakePin.CORRECT_PIN))
        }
        runCurrent()
        backend.tap(nfcKey())
        runCurrent()

        // No new tap: nothing connects, the request waits and keeps telling what to do.
        runCurrent()
        assertFalse(result.isCompleted)
        assertEquals(1, log.count { it.startsWith("connect") })
        result.cancel()
    }

    @Test
    fun `NFC asks again after a wrong PIN, on yet another contact`() = runTest {
        val pin = FakePin(accepts = { it == "right" })
        nfcReady()
        val sessions = arrayOf(keyWithPin(), fakeCtap().also { it.answers(credentialId) }, fakeCtap().also { it.answers(credentialId) })
        val result = async { authenticator(*sessions).request(typing("wrong", "right")) }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()
        backend.tap(nfcKey())
        runCurrent()
        // The second contact refused the PIN: the link is cut again and the PIN asked once more.
        assertFalse(result.isCompleted)
        assertEquals(listOf("connect:NFC", "close", "pin", "connect:NFC", "close", "pin"), log)
        assertEquals(PinPrompt(triesRemaining = 8, incorrect = true, secondContact = true), prompts.last())

        backend.tap(nfcKey())
        runCurrent()
        assertInstanceOf(HwKeyResult.Success::class.java, result.await())
        assertEquals(listOf("wrong", "right"), pin.received)
    }

    @Test
    fun `NFC cancelling the PIN ends the request without another contact`() = runTest {
        FakePin()
        nfcReady()
        val result = async { authenticator(keyWithPin()).request { null } }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()

        assertInstanceOf(HwKeyResult.UserCancelled::class.java, result.await())
        assertEquals(listOf("connect:NFC", "close"), log)
    }

    @Test
    fun `a USB key plugged in while the PIN is typed over NFC finishes the request`() = runTest {
        FakePin()
        nfcReady()
        val usbSession = fakeCtap().also { it.answers(credentialId) }
        val provider: suspend (PinPrompt) -> CharArray? = { prompt ->
            prompts += prompt
            log += "pin"
            backend.plug(usbKey())
            FakePin.CORRECT_PIN.toCharArray()
        }
        val result = async { authenticator(keyWithPin(), usbSession).request(provider) }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()

        assertInstanceOf(HwKeyResult.Success::class.java, result.await())
        assertEquals(listOf("connect:NFC", "close", "pin", "connect:USB", "close"), log)
    }

    @Test
    fun `the PIN typed over NFC is wiped if the new contact never comes`() = runTest {
        FakePin()
        nfcReady()
        val typed = FakePin.CORRECT_PIN.toCharArray()
        val result = async { authenticator(keyWithPin()).request { typed } }
        runCurrent()
        backend.tap(nfcKey())
        runCurrent()
        assertFalse(result.isCompleted)

        result.cancel()
        runCurrent()

        assertTrue(typed.all { it == '\u0000' }, "a PIN waiting for a contact must not outlive the request")
    }
}
