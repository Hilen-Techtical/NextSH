// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.fido.ctap.ClientPin
import com.yubico.yubikit.fido.ctap.Ctap2Session
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What the PIN flow sends to the key (the scope of the token, the data it authenticates, the
 * protocol), and that the PIN, the token and the proof are zeroed on every way out, the failures
 * and the cancellations included. All PINs are test values.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YubiKitAuthenticatorPinSecurityTest {

    private val rpId = "ssh:work"
    private val credentialId = ByteArray(16) { 0xAB.toByte() }

    private val backend = FakeSecurityKeyBackend()
    private val discovery = SecurityKeyDiscovery(backend, directExecutor)
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val statuses: MutableList<HwKeyStatus> = Collections.synchronizedList(mutableListOf())

    /** Every array handed out as a PIN, to look at them once the request is over. */
    private val typedPins = Collections.synchronizedList(mutableListOf<CharArray>())

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun TestScope.authenticator(vararg sessions: Ctap2Session) =
        YubiKitAuthenticator(discovery, FakeConnector(sessions.toList(), log), StandardTestDispatcher(testScheduler))

    private suspend fun YubiKitAuthenticator.request(
        hash: ByteArray = ByteArray(32) { (it + 1).toByte() },
        pinProvider: (suspend (PinPrompt) -> CharArray?)?,
    ): HwKeyResult = getAssertion(
        rpId = rpId,
        clientDataHash = hash,
        allowedCredentials = listOf(credentialId),
        requireUserVerification = false,
        onStatus = { statuses += it },
        pinProvider = pinProvider,
    )

    /** A key that wants a PIN before it signs. */
    private fun keyWithPin(protocols: List<Int> = listOf(2, 1)): Ctap2Session =
        fakeCtap(protocols).also { it.answers(credentialId, withoutPin = Reply.Fail(CtapException.ERR_PIN_REQUIRED)) }

    /** Answers the prompt with each PIN of [pins] in turn, keeping every array it hands out. */
    private fun typing(vararg pins: String): suspend (PinPrompt) -> CharArray? {
        val remaining = ArrayDeque(pins.toList())
        return {
            remaining.removeFirst().toCharArray().also { typedPins += it }
        }
    }

    private fun assertEveryPinWiped(message: String = "a PIN must not outlive the request") {
        assertTrue(typedPins.isNotEmpty(), "the test never asked for a PIN")
        typedPins.forEach { pin -> assertTrue(pin.all { it == '\u0000' }, message) }
    }

    private fun assertSecretsWiped(pin: FakePin) {
        assertTrue(pin.tokens.isNotEmpty(), "the key never gave a token")
        pin.tokens.forEach { token -> assertTrue(token.all { it == 0.toByte() }, "a PIN token must be wiped") }
        pin.proofs.forEach { proof -> assertTrue(proof.all { it == 0.toByte() }, "a PIN proof must be wiped") }
    }

    // ── What is sent to the key ───────────────────────────────────────────────

    @Test
    fun `the PIN token is scoped to getAssertion for the relying party`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()

        authenticator(keyWithPin()).request(pinProvider = typing(FakePin.CORRECT_PIN))

        assertEquals(listOf(ClientPin.PIN_PERMISSION_GA to rpId), pin.tokenRequests)
    }

    @Test
    fun `the proof authenticates the hash that is signed, and the assertion carries both`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        val key = keyWithPin()
        var hashOfAssertion: ByteArray? = null
        var proofOfAssertion: ByteArray? = null
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val proof = arg<ByteArray?>(5)
            if (proof == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            hashOfAssertion = arg<ByteArray>(1).copyOf()
            proofOfAssertion = proof.copyOf()
            listOf(fakeAssertion(credentialId))
        }
        val hash = ByteArray(32) { (it * 5 + 2).toByte() }

        authenticator(key).request(hash = hash.copyOf(), pinProvider = typing(FakePin.CORRECT_PIN))

        val (version, token, authenticatedData) = pin.authenticated.single()
        assertEquals(2, version)
        assertArrayEquals(ByteArray(32) { 0x11 }, token, "authenticated with the token the key gave")
        assertArrayEquals(hash, authenticatedData, "the proof covers the hash that is signed")
        assertArrayEquals(hash, hashOfAssertion, "and the same hash goes to the key")
        assertArrayEquals(ByteArray(16) { 0x22 }, proofOfAssertion, "with the proof")
    }

    @Test
    fun `a key that only speaks PIN protocol 1 is talked to in protocol 1`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        val key = keyWithPin(protocols = listOf(1))

        val result = authenticator(key).request(pinProvider = typing(FakePin.CORRECT_PIN))

        assertInstanceOf(HwKeyResult.Success::class.java, result)
        assertEquals(listOf(1), pin.authenticated.map { it.first }, "the proof is made with protocol 1")
        verify(exactly = 1) {
            // The attempt with the proof says which protocol made it.
            key.getAssertions(any(), any(), any(), any(), mapOf("up" to true), ofType<ByteArray>(), 1, any())
        }
    }

    @Test
    fun `a key that speaks both is talked to in protocol 2`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()

        authenticator(keyWithPin(protocols = listOf(1, 2))).request(pinProvider = typing(FakePin.CORRECT_PIN))

        assertEquals(listOf(2), pin.authenticated.map { it.first })
    }

    // ── Wiped on every way out ────────────────────────────────────────────────

    @Test
    fun `the PIN, the token and the proof are wiped once a signature is made`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()

        authenticator(keyWithPin()).request(pinProvider = typing(FakePin.CORRECT_PIN))

        assertEveryPinWiped()
        assertSecretsWiped(pin)
    }

    @Test
    fun `three wrong PINs are all wiped`() = runTest {
        val pin = FakePin(accepts = { false })
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(pinProvider = typing("nope1", "nope2", "nope3"))

        assertInstanceOf(HwKeyResult.Error::class.java, result)
        assertEquals(3, typedPins.size)
        assertEveryPinWiped()
        assertEquals(0, pin.tokens.size, "no token for a PIN the key refused")
    }

    @Test
    fun `a PIN the key blocks is wiped`() = runTest {
        FakePin(failWith = CtapException.ERR_PIN_BLOCKED)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(pinProvider = typing("whatever"))

        assertEquals(HwKeyResult.Error(HwKeyError.PinBlocked), result)
        assertEveryPinWiped()
    }

    @Test
    fun `a PIN the key locks until it is power cycled is wiped`() = runTest {
        FakePin(failWith = CtapException.ERR_PIN_AUTH_BLOCKED)
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(pinProvider = typing("whatever"))

        assertEquals(HwKeyResult.Error(HwKeyError.PinAuthBlocked), result)
        assertEveryPinWiped()
    }

    @Test
    fun `a key pulled out after the PIN went out leaves nothing behind`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        val key = keyWithPin()
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<ByteArray?>(5) == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            throw IOException("unplugged")
        }

        val result = authenticator(key).request(pinProvider = typing(FakePin.CORRECT_PIN))

        assertEquals(HwKeyResult.Error(HwKeyError.KeyRemoved), result)
        assertEveryPinWiped()
        assertSecretsWiped(pin)
    }

    @Test
    fun `a cancelled PIN dialog hands out nothing to wipe and leaves no token`() = runTest {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()

        val result = authenticator(keyWithPin()).request(pinProvider = { null })

        assertInstanceOf(HwKeyResult.UserCancelled::class.java, result)
        assertTrue(pin.tokens.isEmpty())
    }

    @Test
    fun `cancelling while the key waits for the touch wipes the PIN, the token and the proof`() = runBlocking {
        val pin = FakePin()
        backend.pluggedAtStart += usbKey()
        val touching = CompletableDeferred<Unit>()
        val key = keyWithPin()
        every { key.getAssertions(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            if (arg<ByteArray?>(5) == null) throw CtapException(CtapException.ERR_PIN_REQUIRED)
            touching.complete(Unit)
            val state = arg<com.yubico.yubikit.core.application.CommandState>(7)
            while (!state.waitForCancel(0)) Thread.sleep(10)
            throw CtapException(CtapException.ERR_KEEPALIVE_CANCEL)
        }
        val auth = YubiKitAuthenticator(discovery, FakeConnector(listOf(key), log), Dispatchers.IO)
        val job = launch(Dispatchers.Default) {
            auth.getAssertion(rpId, ByteArray(32), listOf(credentialId), false, {}, typing(FakePin.CORRECT_PIN))
        }

        withTimeout(5_000) { touching.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }

        assertEveryPinWiped()
        assertSecretsWiped(pin)
    }

    @Test
    fun `an NFC contact that fails to connect wipes the PIN carried to it`() = runTest {
        FakePin()
        discovery.onActivityResumed(mockk<Activity>())
        val connector = FakeConnector(listOf(keyWithPin(), fakeCtap()), log)
        // The first contact works; the second, the one that is to carry the PIN, cannot connect.
        connector.beforeConnect = {
            if (connector.connected.size == 1) connector.failure = IOException("tag lost")
        }
        val auth = YubiKitAuthenticator(discovery, connector, StandardTestDispatcher(testScheduler))
        val result = async { auth.request(pinProvider = typing(FakePin.CORRECT_PIN)) }
        runCurrent()

        backend.tap(nfcKey())
        runCurrent()
        backend.tap(nfcKey())
        runCurrent()

        assertEquals(HwKeyResult.Error(HwKeyError.KeyRemoved), result.await())
        assertEveryPinWiped("a PIN carried to a contact that never came up must be wiped")
    }

    @Test
    fun `a request cancelled as the second NFC contact comes up wipes the PIN it carries`() = runBlocking {
        FakePin()
        discovery.onActivityResumed(mockk<Activity>())
        val connecting = CompletableDeferred<Unit>()
        val letConnect = CountDownLatch(1)
        val connector = FakeConnector(listOf(keyWithPin(), fakeCtap().also { it.answers(credentialId) }), log)
        connector.beforeConnect = {
            if (connector.connected.size == 1) {
                // The second contact: the key takes its time, and the request is cancelled meanwhile.
                connecting.complete(Unit)
                check(letConnect.await(5, TimeUnit.SECONDS)) { "the test never let the connection through" }
            }
        }
        val auth = YubiKitAuthenticator(discovery, connector, Dispatchers.IO)
        val outcome = CompletableDeferred<Result<HwKeyResult>>()
        val job = launch(Dispatchers.Default) {
            outcome.complete(runCatching { auth.request(pinProvider = typing(FakePin.CORRECT_PIN)) })
        }

        // A tap is only taken by a request that waits: tap until each contact is made.
        withTimeout(5_000) {
            while (connector.connected.isEmpty()) {
                backend.tap(nfcKey())
                delay(5)
            }
            while (!connecting.isCompleted) {
                backend.tap(nfcKey())
                delay(5)
            }
        }
        job.cancel()
        letConnect.countDown()
        withTimeout(5_000) { job.join() }

        assertTrue(withTimeout(5_000) { outcome.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEveryPinWiped("the PIN waiting for the new contact is the request's to wipe")
        assertFalse(backend.usbRunning)
    }
}
