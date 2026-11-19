// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import com.yubico.yubikit.core.YubiKeyConnection
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.ApplicationNotAvailableException
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.core.smartcard.SmartCardConnection
import com.yubico.yubikit.fido.ctap.Ctap2Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.Collections

/**
 * [YubiKitAuthenticator] without a key: which connection serves which transport, what is asked of
 * the key, how a cancellation and every failure end, and what the user is told along the way.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YubiKitAuthenticatorTest {

    private val rpId = "ssh:"
    private val credentialId = ByteArray(16) { 0xAB.toByte() }

    private val backend = FakeSecurityKeyBackend()
    private val discovery = SecurityKeyDiscovery(backend, directExecutor)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val statuses = mutableListOf<HwKeyStatus>()
    private lateinit var connector: FakeConnector

    /** The monotonic clock of the authenticator, in nanoseconds: it only moves when a test says so. */
    private val clock = AtomicLong()
    private val nanoTime: () -> Long = { clock.get() }

    private fun TestScope.authenticator(vararg sessions: Ctap2Session): YubiKitAuthenticator {
        connector = FakeConnector(sessions.toList(), log)
        return YubiKitAuthenticator(discovery, connector, StandardTestDispatcher(testScheduler), nanoTime)
    }

    private suspend fun YubiKitAuthenticator.request(
        clientDataHash: ByteArray = ByteArray(32) { it.toByte() },
        requireUserVerification: Boolean = false,
    ): HwKeyResult = getAssertion(
        rpId = rpId,
        clientDataHash = clientDataHash,
        allowedCredentials = listOf(credentialId),
        requireUserVerification = requireUserVerification,
        onStatus = { statuses += it },
        pinProvider = null,
    )

    /** A key plugged in over USB whose assertion request fails with [error]. */
    private fun failingKey(error: Throwable): Ctap2Session {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } throws error
        return ctap
    }

    private fun assertError(expected: HwKeyError, result: HwKeyResult) {
        assertInstanceOf(HwKeyResult.Error::class.java, result)
        assertEquals(expected, (result as HwKeyResult.Error).reason)
    }

    // ── What is asked of the key ──────────────────────────────────────────────

    @Test
    fun `a plugged USB key signs and the lease is given back`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap().also { it.answers(credentialId) }

        val result = authenticator(ctap).request()

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        val success = result as HwKeyResult.Success
        assertEquals(0x01.toByte(), success.flags)
        assertEquals(7u, success.counter)
        assertEquals(KeyTransport.USB, connector.connected.single().transport)
        assertFalse(backend.usbRunning, "the discovery must not stay on after the request")
        assertEquals(listOf("connect:USB", "close"), log)
    }

    @Test
    fun `a signature stands even if the connection cannot be closed afterwards`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap().also { it.answers(credentialId) }
        val auth = authenticator(ctap)
        connector.closeFailure = IOException("key already unplugged")

        val result = auth.request()

        assertInstanceOf(HwKeyResult.Success::class.java, result)
    }

    @Test
    fun `the allow list of the request is sent to the key`() = runTest {
        backend.pluggedAtStart += usbKey()
        val allowList = slot<List<Map<String, *>>>()
        val ctap = fakeCtap()
        every {
            ctap.getAssertions(any(), any(), capture(allowList), any(), any(), any(), any(), any())
        } returns listOf(fakeAssertion(credentialId))

        authenticator(ctap).request()

        val sent = allowList.captured.single()
        assertTrue((sent["id"] as ByteArray).contentEquals(credentialId), "the credential id must be sent as is")
    }

    @Test
    fun `the relying party and the hash to sign reach the key as given`() = runTest {
        backend.pluggedAtStart += usbKey()
        var relyingParty: String? = null
        var hashSeen: ByteArray? = null
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            relyingParty = arg<String>(0)
            // A copy: the request zeroes its own array when it ends.
            hashSeen = arg<ByteArray>(1).copyOf()
            listOf(fakeAssertion(credentialId))
        }
        val hash = ByteArray(32) { (it * 7 + 3).toByte() }

        authenticator(ctap).getAssertion("ssh:work", hash.copyOf(), listOf(credentialId), false, {}, null)

        assertEquals("ssh:work", relyingParty, "the key checks the credential against this relying party")
        assertArrayEquals(hash, hashSeen, "and signs exactly this hash")
    }

    @Test
    fun `no allow list lets the key choose`() = runTest {
        backend.pluggedAtStart += usbKey()
        val allowList = slot<List<Map<String, *>>?>()
        val ctap = fakeCtap()
        every {
            ctap.getAssertions(any(), any(), captureNullable(allowList), any(), any(), any(), any(), any())
        } returns listOf(fakeAssertion(credentialId))
        val auth = authenticator(ctap)

        auth.getAssertion(rpId, ByteArray(32), emptyList(), false, {}, null)

        assertEquals(null, allowList.captured)
    }

    @Test
    fun `user presence only is asked unless verification is required`() = runTest {
        backend.pluggedAtStart += usbKey()
        val options = mutableListOf<Map<String, *>>()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            options += arg<Map<String, *>>(4)
            listOf(fakeAssertion(credentialId))
        }
        val auth = authenticator(ctap, ctap) // one session per request

        auth.request(requireUserVerification = false)
        auth.request(requireUserVerification = true)

        assertEquals(mapOf("up" to true), options[0])
        assertEquals(mapOf("up" to true, "uv" to true), options[1])
    }

    @Test
    fun `the client data hash is zeroed when the request ends`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap().also { it.answers(credentialId) }
        val hash = ByteArray(32) { 1 }

        authenticator(ctap).request(clientDataHash = hash)

        assertTrue(hash.all { it == 0.toByte() })
    }

    // ── Status ────────────────────────────────────────────────────────────────

    @Test
    fun `the status goes from waiting to talking`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap().also { it.answers(credentialId) }

        authenticator(ctap).request()

        assertEquals(listOf(HwKeyStatus.WAITING_FOR_KEY, HwKeyStatus.TALKING_TO_KEY), statuses)
    }

    @Test
    fun `the status asks for a touch when the key waits for one`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<CommandState>(7).onKeepAliveStatus(CommandState.STATUS_UPNEEDED)
            listOf(fakeAssertion(credentialId))
        }

        authenticator(ctap).request()

        assertEquals(
            listOf(HwKeyStatus.WAITING_FOR_KEY, HwKeyStatus.TALKING_TO_KEY, HwKeyStatus.TOUCH_KEY),
            statuses,
        )
    }

    @Test
    fun `a key that is only processing does not ask for a touch`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<CommandState>(7).onKeepAliveStatus(CommandState.STATUS_PROCESSING)
            listOf(fakeAssertion(credentialId))
        }

        authenticator(ctap).request()

        assertFalse(HwKeyStatus.TOUCH_KEY in statuses)
    }

    // ── Telling the user what stands in the way ───────────────────────────────

    @Test
    fun `a USB key Android refused is said while waiting, and the sentence is withdrawn once it works`() = runTest {
        backend.deniedAtStart += "usb-1"
        val ctap = fakeCtap().also { it.answers(credentialId) }
        val result = async { authenticator(ctap).request() }
        runCurrent()
        assertEquals(
            listOf(HwKeyStatus.USB_ACCESS_DENIED),
            statuses,
            "said from the start, with no plain waiting sentence first: the scan already found the key",
        )
        assertFalse(result.isCompleted, "the request keeps waiting for a key it can use")

        // The user unplugged the key, plugged it back and accepted the prompt of Android.
        backend.unplug("usb-1")
        backend.plug(usbKey("usb-1"))
        runCurrent()

        assertInstanceOf(HwKeyResult.Success::class.java, result.await())
        assertEquals(HwKeyStatus.TALKING_TO_KEY, statuses.last())
        assertEquals(HwKeyStatus.WAITING_FOR_KEY, statuses[statuses.lastIndex - 1], "the refusal is no longer said")
    }

    @Test
    fun `switched off NFC is said while waiting`() = runTest {
        backend.nfc = NfcStart.DISABLED
        discovery.onActivityResumed(mockk<Activity>())
        val result = async { authenticator(fakeCtap()).request() }
        runCurrent()

        assertEquals(listOf(HwKeyStatus.NFC_DISABLED), statuses)
        result.cancel()
    }

    @Test
    fun `a phone without NFC keeps the plain waiting sentence`() = runTest {
        backend.nfc = NfcStart.ABSENT
        discovery.onActivityResumed(mockk<Activity>())
        val result = async { authenticator(fakeCtap()).request() }
        runCurrent()

        assertEquals(listOf(HwKeyStatus.WAITING_FOR_KEY), statuses)
        result.cancel()
    }

    @Test
    fun `the sentence follows the settings when the user comes back with NFC on`() = runTest {
        backend.nfc = NfcStart.DISABLED
        val activity = mockk<Activity>()
        discovery.onActivityResumed(activity)
        val result = async { authenticator(fakeCtap()).request() }
        runCurrent()

        backend.nfc = NfcStart.STARTED
        discovery.onActivityPaused(activity)
        discovery.onActivityResumed(activity)
        runCurrent()

        assertEquals(listOf(HwKeyStatus.NFC_DISABLED, HwKeyStatus.WAITING_FOR_KEY), statuses)
        result.cancel()
    }

    // ── No clock of its own ───────────────────────────────────────────────────

    @Test
    fun `waiting for a key has no timeout of its own`() = runTest {
        val result = async { authenticator(fakeCtap()).request() }
        runCurrent()

        advanceTimeBy(10 * 60_000L)
        runCurrent()

        assertFalse(result.isCompleted, "the prompt ends the wait by cancelling, not this")
        result.cancel()
    }

    // ── Cancelling ────────────────────────────────────────────────────────────

    @Test
    fun `cancelling while waiting for a key is rethrown and gives the lease back`() = runTest {
        val job = async { authenticator(fakeCtap()).request() }
        runCurrent()
        assertTrue(backend.usbRunning, "the discovery works while the request waits")

        job.cancel()
        runCurrent()

        val failure = runCatching { job.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "a cancellation must not become a result: $failure")
        assertFalse(backend.usbRunning)
    }

    @Test
    fun `cancelling during the touch cancels the command on the key and closes the connection`() = runBlocking {
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire() // a lease of the test, so that the discovery still knows the key
        val started = CompletableDeferred<CommandState>()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val state = arg<CommandState>(7)
            started.complete(state)
            // Like the CTAP HID layer: poll the state while the key waits for the touch.
            var polls = 0
            while (!state.waitForCancel(0)) {
                check(polls++ < 500) { "the command was never cancelled" }
                Thread.sleep(10)
            }
            throw CtapException(CtapException.ERR_KEEPALIVE_CANCEL)
        }
        val auth = YubiKitAuthenticator(discovery, FakeConnector(listOf(ctap), log), Dispatchers.IO)
        // What getAssertion ends with is what is looked at, not the state of the Job: a job that is
        // cancelled while its body returns a value is cancelled all the same.
        val outcome = CompletableDeferred<Result<HwKeyResult>>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { auth.getAssertion(rpId, ByteArray(32), listOf(credentialId), false, {}, null) })
        }

        val state = withTimeout(5_000) { started.await() }
        assertFalse(state.waitForCancel(0), "not cancelled while the caller waits")
        job.cancel()
        withTimeout(5_000) { job.join() }

        // The key answers the cancellation with an error (CTAP2_ERR_KEEPALIVE_CANCEL): it must
        // not be taken for a failure of the key, the cancellation goes on its way.
        val failure = withTimeout(5_000) { outcome.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "a cancellation must not become a result: $failure")
        assertTrue(state.waitForCancel(0), "the key must be told to stop waiting for the touch")
        assertEquals(listOf("connect:USB", "close"), log)
        discovery.assertKeyFree(key)
        keep.close()
        assertFalse(backend.usbRunning)
    }

    @Test
    fun `a key that drops the link while the request is cancelled is a cancellation too`() = runBlocking {
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire()
        val started = CompletableDeferred<Unit>()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            started.complete(Unit)
            val state = arg<CommandState>(7)
            while (!state.waitForCancel(0)) Thread.sleep(10)
            throw IOException("the link broke as the request ended")
        }
        val auth = YubiKitAuthenticator(discovery, FakeConnector(listOf(ctap), log), Dispatchers.IO)
        val outcome = CompletableDeferred<Result<HwKeyResult>>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { auth.getAssertion(rpId, ByteArray(32), listOf(credentialId), false, {}, null) })
        }

        withTimeout(5_000) { started.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }

        val failure = withTimeout(5_000) { outcome.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "a cancellation must not become KeyRemoved: $failure")
        discovery.assertKeyFree(key)
        keep.close()
    }

    @Test
    fun `a connection that comes up while the request is cancelled is closed`() = runBlocking {
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire()
        val connecting = CompletableDeferred<Unit>()
        val letConnect = CountDownLatch(1)
        val connector = FakeConnector(listOf(fakeCtap()), log)
        connector.beforeConnect = {
            connecting.complete(Unit)
            // The key takes its time to answer: the request is cancelled meanwhile.
            check(letConnect.await(5, TimeUnit.SECONDS)) { "the test never let the connection through" }
        }
        val auth = YubiKitAuthenticator(discovery, connector, Dispatchers.IO)
        val outcome = CompletableDeferred<Result<HwKeyResult>>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { auth.getAssertion(rpId, ByteArray(32), listOf(credentialId), false, {}, null) })
        }

        withTimeout(5_000) { connecting.await() }
        job.cancel()
        letConnect.countDown()
        withTimeout(5_000) { job.join() }

        val failure = withTimeout(5_000) { outcome.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "$failure")
        assertEquals(listOf("connect:USB", "close"), log, "a connection nobody uses must not be left open")
        // And the key is free for the next request.
        discovery.assertKeyFree(key)
        keep.close()
        assertFalse(backend.usbRunning)
    }

    // ── The touch times out on the key before the prompt does ─────────────────

    @Test
    fun `a key that stops waiting for the touch is asked again`() = runTest {
        backend.pluggedAtStart += usbKey()
        var calls = 0
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            // The YubiKey gives up after about 30 s, the prompt after 60: it blinks again.
            clock.addAndGet(KEY_TOUCH_WAIT_NANOS)
            if (calls++ < 2) throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
            listOf(fakeAssertion(credentialId))
        }

        val result = authenticator(ctap).request()

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(3, calls)
        assertEquals(listOf("connect:USB", "close"), log, "on the same connection")
    }

    @Test
    fun `the touch is asked for again until the request is cancelled, then it is a cancellation`() = runBlocking {
        backend.pluggedAtStart += usbKey()
        val timeouts = AtomicInteger()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            Thread.sleep(2) // a key that never gets its touch
            timeouts.incrementAndGet()
            clock.addAndGet(KEY_TOUCH_WAIT_NANOS) // and that really waited for it each time
            val state = arg<CommandState>(7)
            if (state.waitForCancel(0)) throw CtapException(CtapException.ERR_KEEPALIVE_CANCEL)
            throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
        }
        val auth = YubiKitAuthenticator(discovery, FakeConnector(listOf(ctap), log), Dispatchers.IO, nanoTime)
        val outcome = CompletableDeferred<Result<HwKeyResult>>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { auth.getAssertion(rpId, ByteArray(32), listOf(credentialId), false, {}, null) })
        }

        withTimeout(5_000) { while (timeouts.get() < 5) delay(5) }
        assertFalse(outcome.isCompleted, "a touch timeout of the key must not end the request")
        job.cancel()
        withTimeout(5_000) { job.join() }

        val failure = withTimeout(5_000) { outcome.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "$failure")
        assertEquals(listOf("connect:USB", "close"), log)
    }

    @Test
    fun `a key that answers the touch timeout at once, again and again, is not asked for ever`() = runTest {
        backend.pluggedAtStart += usbKey()
        var calls = 0
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            calls++
            clock.addAndGet(100_000_000L) // 100 ms: it did not wait for anything
            throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
        }

        val result = authenticator(ctap).request()

        assertError(HwKeyError.TouchTimeout, result)
        assertEquals(3, calls, "three quick answers in a row end it, not one transaction per turn until the deadline")
        assertEquals(listOf("connect:USB", "close"), log)
    }

    @Test
    fun `a key that really waited for the touch is asked again however many times`() = runTest {
        backend.pluggedAtStart += usbKey()
        var calls = 0
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            clock.addAndGet(KEY_TOUCH_WAIT_NANOS)
            if (calls++ < 5) throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
            listOf(fakeAssertion(credentialId))
        }

        val result = authenticator(ctap).request()

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(6, calls)
    }

    @Test
    fun `quick touch timeouts that are not in a row do not add up`() = runTest {
        backend.pluggedAtStart += usbKey()
        // Two quick answers, one real wait, two quick answers: never three quick ones in a row.
        val waits = ArrayDeque(listOf(100_000_000L, 100_000_000L, KEY_TOUCH_WAIT_NANOS, 100_000_000L, 100_000_000L))
        var calls = 0
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            calls++
            if (waits.isEmpty()) return@answers listOf(fakeAssertion(credentialId))
            clock.addAndGet(waits.removeFirst())
            throw CtapException(CtapException.ERR_USER_ACTION_TIMEOUT)
        }

        val result = authenticator(ctap).request()

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(6, calls)
    }

    @Test
    fun `a timeout of the key that is not about the touch is not asked again`() = runTest {
        backend.pluggedAtStart += usbKey()
        var calls = 0
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            calls++
            throw CtapException(CtapException.ERR_ACTION_TIMEOUT)
        }

        assertError(HwKeyError.TouchTimeout, authenticator(ctap).request())
        assertEquals(1, calls)
    }

    // ── Failures ──────────────────────────────────────────────────────────────

    @Test
    fun `a key that does not hold the credential`() = runTest {
        val key = failingKey(CtapException(CtapException.ERR_NO_CREDENTIALS))

        assertError(HwKeyError.NoMatchingCredential, authenticator(key).request())
    }

    @Test
    fun `a key pulled out during the exchange`() = runTest {
        val key = failingKey(IOException("read failed"))

        assertError(HwKeyError.KeyRemoved, authenticator(key).request())
        assertEquals(listOf("connect:USB", "close"), log, "the connection is closed even on failure")
    }

    @Test
    fun `a key whose connection cannot be opened`() = runTest {
        backend.pluggedAtStart += usbKey()
        val auth = authenticator()
        connector.failure = IOException("no link")

        assertError(HwKeyError.KeyRemoved, auth.request())
    }

    @Test
    fun `a tag without a FIDO2 application is not a supported key`() = runTest {
        backend.pluggedAtStart += usbKey()
        val auth = authenticator()
        connector.failure = ApplicationNotAvailableException("The application couldn't be selected")

        assertError(HwKeyError.UnsupportedKey, auth.request())
    }

    @Test
    fun `any other failure is an unexpected error`() = runTest {
        val key = failingKey(IllegalStateException("surprise"))

        assertError(HwKeyError.Unexpected, authenticator(key).request())
    }

    @Test
    fun `an empty answer from the key is an unexpected error`() = runTest {
        backend.pluggedAtStart += usbKey()
        val ctap = fakeCtap()
        every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()

        assertError(HwKeyError.Unexpected, authenticator(ctap).request())
    }

    @Test
    fun `a failure also gives the lease back`() = runTest {
        val key = failingKey(CtapException(CtapException.ERR_NO_CREDENTIALS))

        authenticator(key).request()

        assertFalse(backend.usbRunning)
    }

    @Test
    fun `after a failure the key is free for the next request`() = runTest {
        val key = usbKey()
        backend.pluggedAtStart += key
        val keep = discovery.acquire() // a lease of the test, so that the discovery still knows the key
        val failures = listOf(
            CtapException(CtapException.ERR_NO_CREDENTIALS) to HwKeyError.NoMatchingCredential,
            IOException("read failed") to HwKeyError.KeyRemoved,
            IllegalStateException("surprise") to HwKeyError.Unexpected,
        )

        failures.forEach { (failure, reason) ->
            val ctap = fakeCtap()
            every { ctap.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } throws failure
            assertError(reason, authenticator(ctap).request())
            discovery.assertKeyFree(key)
        }

        // And a connection that cannot even be opened.
        val auth = authenticator()
        connector.failure = IOException("no link")
        assertError(HwKeyError.KeyRemoved, auth.request())
        discovery.assertKeyFree(key)
        keep.close()
    }

    @Test
    fun `every CTAP error code the user can meet has its reason`() = runTest {
        val auth = authenticator()
        fun reason(code: Byte) = auth.errorFor(CtapException(code))

        assertEquals(HwKeyError.NoMatchingCredential, reason(CtapException.ERR_NO_CREDENTIALS))
        assertEquals(HwKeyError.PinBlocked, reason(CtapException.ERR_PIN_BLOCKED))
        assertEquals(HwKeyError.PinAuthBlocked, reason(CtapException.ERR_PIN_AUTH_BLOCKED))
        assertEquals(HwKeyError.PinIncorrect(null), reason(CtapException.ERR_PIN_INVALID))
        assertEquals(HwKeyError.PinNotSet, reason(CtapException.ERR_PIN_NOT_SET))
        assertEquals(HwKeyError.TouchTimeout, reason(CtapException.ERR_USER_ACTION_TIMEOUT))
        assertEquals(HwKeyError.TouchTimeout, reason(CtapException.ERR_ACTION_TIMEOUT))
        assertEquals(HwKeyError.UnsupportedKey, reason(CtapException.ERR_UNSUPPORTED_ALGORITHM))
        assertEquals(HwKeyError.UnsupportedKey, reason(CtapException.ERR_UNSUPPORTED_OPTION))
        assertEquals(HwKeyError.Refused, reason(CtapException.ERR_OPERATION_DENIED))
        assertEquals(HwKeyError.Refused, reason(CtapException.ERR_UP_REQUIRED))
        assertEquals(HwKeyError.Unexpected, reason(CtapException.ERR_OTHER))
    }

    // ── The connection that serves each transport ─────────────────────────────

    @Test
    fun `USB opens a FIDO connection and NFC a smart card connection`() {
        val fido = mockk<FidoConnection>(relaxed = true)
        val smartCard = mockk<SmartCardConnection>(relaxed = true)
        val device = mockk<YubiKeyDevice>()
        every { device.openConnection(FidoConnection::class.java) } returns fido
        every { device.openConnection(SmartCardConnection::class.java) } returns smartCard
        val opened = mutableListOf<YubiKeyConnection>()
        val connector = YubiKitCtap2Connector(newSession = { connection ->
            opened += connection
            mockk<Ctap2Session>(relaxed = true)
        })

        connector.connect(SecurityKey(KeyTransport.USB, "usb-1", device)).close()
        connector.connect(SecurityKey(KeyTransport.NFC, null, device)).close()

        assertSame(fido, opened[0], "a key plugged in is spoken to over HID FIDO")
        assertSame(smartCard, opened[1], "a tapped tag is spoken to over ISO 7816")
        verify(exactly = 1) { device.openConnection(FidoConnection::class.java) }
        verify(exactly = 1) { device.openConnection(SmartCardConnection::class.java) }
        verify(exactly = 1) { fido.close() }
        verify(exactly = 1) { smartCard.close() }
    }

    @Test
    fun `the connection is closed when the session cannot be created`() {
        val fido = mockk<FidoConnection>(relaxed = true)
        val device = mockk<YubiKeyDevice>()
        every { device.openConnection(FidoConnection::class.java) } returns fido
        val connector = YubiKitCtap2Connector(newSession = { throw IOException("no answer") })

        val failure = runCatching { connector.connect(SecurityKey(KeyTransport.USB, "usb-1", device)) }

        assertTrue(failure.exceptionOrNull() is IOException)
        verify(exactly = 1) { fido.close() }
    }
}
