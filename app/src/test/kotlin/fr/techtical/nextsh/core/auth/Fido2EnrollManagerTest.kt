// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import com.yubico.yubikit.core.YubiKeyDevice
import com.yubico.yubikit.core.application.CommandState
import com.yubico.yubikit.core.fido.CtapException
import com.yubico.yubikit.core.fido.FidoConnection
import com.yubico.yubikit.fido.client.BasicWebAuthnClient
import com.yubico.yubikit.fido.client.ClientError
import com.yubico.yubikit.fido.client.PinInvalidClientError
import com.yubico.yubikit.fido.client.PinRequiredClientError
import fr.techtical.nextsh.shared.core.ssh.fido2.SkKeyType
import fr.techtical.nextsh.shared.core.ssh.fido2.SkSshPublicKey
import fr.techtical.nextsh.testutil.LEAK_MARKER
import fr.techtical.nextsh.testutil.RecordingTree
import fr.techtical.nextsh.testutil.assertNoPayloadLeak
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.io.IOException
import java.util.Collections

/**
 * Tests unitaires pour [Fido2EnrollManager].
 *
 * Note : l'appel CTAP2 réel ne peut pas être testé en unit test. Il nécessite un device
 * physique ou un émulateur. La découverte USB/NFC est testée dans [SecurityKeyDiscoveryTest],
 * avec un faux YubiKit. Le parsing CBOR est délégué à YubiKit et n'est pas re-testé ici.
 *
 * Ces tests couvrent :
 *   - Le round-trip publicKey → SkSshPublicKey → authorized_keys format
 *   - Le wipe sécurisé de [EnrollResult]
 *   - Le bail pris sur la découverte pour la seule durée de l'opération, rendu dans tous les cas
 *   - Ce que dit la clé devient une [EnrollFailure] (la table complète est dans [Fido2EnrollFailureTest])
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Fido2EnrollManagerTest {

    // ── Test : round-trip publicKey → authorized_keys ─────────────────────────

    @Test
    fun `EnrollResult publicKey produces valid SkSshPublicKey authorized_keys line`() {
        val rawPubKey = ByteArray(32) { it.toByte() }
        val credentialId = ByteArray(16) { 0xAA.toByte() }

        val enrollResult = Fido2EnrollManager.EnrollResult(
            credentialId = credentialId,
            application = "ssh:",
            publicKey = rawPubKey,
            keyType = SkKeyType.SK_ED25519,
            transport = Fido2EnrollManager.Transport.USB,
        )

        val skPubKey = SkSshPublicKey(
            keyType = SkKeyType.SK_ED25519,
            rawKeyData = enrollResult.publicKey,
            application = enrollResult.application,
        )
        val line = skPubKey.toAuthorizedKeysLine()

        assertTrue(line.startsWith("sk-ssh-ed25519@openssh.com "), "Line should start with sk type: $line")

        // Round-trip : parse la ligne en SkSshPublicKey
        val parsed = SkSshPublicKey.fromAuthorizedKeysLine(line)
        assertNotNull(parsed)
        assertEquals(SkKeyType.SK_ED25519, parsed!!.keyType)
        assertArrayEquals(rawPubKey, parsed.rawKeyData)
        assertEquals("ssh:", parsed.application)

        skPubKey.close()
        parsed.close()
    }

    @Test
    fun `EnrollResult wipe fills credentialId and publicKey with zeros`() {
        val credId = ByteArray(16) { 0xFF.toByte() }
        val pubKey = ByteArray(32) { 0xAB.toByte() }
        val result = Fido2EnrollManager.EnrollResult(
            credentialId = credId,
            application = "ssh:",
            publicKey = pubKey,
            keyType = SkKeyType.SK_ED25519,
            transport = Fido2EnrollManager.Transport.USB,
        )

        result.wipe()

        assertTrue(credId.all { it == 0.toByte() }, "credentialId should be zeroed")
        assertTrue(pubKey.all { it == 0.toByte() }, "publicKey should be zeroed")
    }

    // ── The lease belongs to the operation ────────────────────────────────────

    private val backend = FakeSecurityKeyBackend()
    private val discovery = SecurityKeyDiscovery(backend, directExecutor)
    private val activity = mockk<Activity>()

    private fun TestScope.manager(keyWaitMs: Long = 60_000L) =
        Fido2EnrollManager(discovery, StandardTestDispatcher(testScheduler), keyWaitMs)

    @Test
    fun `the lease is taken for the wait and given back when the wait is cancelled`() = runTest {
        val enrolling = async { manager().detectAndEnroll("label") }
        runCurrent()
        assertTrue(backend.usbRunning, "the operation is waiting for a key: the keys are looked for")

        enrolling.cancel()
        runCurrent()

        assertFalse(backend.usbRunning, "nothing is watched once the operation is over")
        assertEquals(1, backend.usbStops)
    }

    @Test
    fun `no key in time ends in a timeout, gives the lease back and zeroes the PIN`() = runTest {
        val pin = "1234".toCharArray()
        val enrolling = async {
            runCatching { manager(keyWaitMs = 1_000L).detectAndEnroll("label", pin = pin) }.exceptionOrNull()
        }
        runCurrent()
        assertTrue(backend.usbRunning)

        advanceTimeBy(1_001L)
        runCurrent()

        val failure = enrolling.await()
        assertTrue(failure is Fido2EnrollManager.EnrollError, "$failure")
        assertEquals(EnrollFailure.NoKeyInTime, (failure as Fido2EnrollManager.EnrollError).failure)
        assertFalse(backend.usbRunning)
        assertTrue(pin.all { it == '\u0000' }, "the PIN must not outlive a wait that came to nothing")
    }

    @Test
    fun `the PIN is zeroed when the wait is cancelled`() = runTest {
        val pin = "1234".toCharArray()
        val enrolling = async { manager().detectAndEnroll("label", pin = pin) }
        runCurrent()

        enrolling.cancel()
        runCurrent()

        assertTrue(pin.all { it == '\u0000' })
    }

    @Test
    fun `the PIN is zeroed when the operation is cancelled before its block starts`() = runTest {
        val pin = "1234".toCharArray()
        // Started at once, so that the operation is under way (its block queued on the dispatcher)
        // when it is cancelled: the block is then skipped, and so is anything written inside it.
        val enrolling = async(start = CoroutineStart.UNDISPATCHED) { manager().detectAndEnroll("label", pin = pin) }
        enrolling.cancel()

        runCurrent()

        assertTrue(enrolling.isCancelled)
        assertEquals(0, backend.usbStarts, "the block never ran: no lease was taken")
        assertTrue(pin.all { it == '\u0000' }, "the PIN must not outlive an operation that never started")
    }

    @Test
    fun `the NFC reader runs for the operation only`() = runTest {
        // The enrollment screen is open and idle: the Activity is in the foreground, nobody waits.
        discovery.onActivityResumed(activity)
        assertTrue(backend.nfcStarts.isEmpty(), "an idle screen must not catch every tag of the phone")

        val enrolling = async { manager().detectAndEnroll("label") }
        runCurrent()
        assertSame(activity, backend.nfcActivity, "the reader works while the operation waits")

        enrolling.cancel()
        runCurrent()
        assertNull(backend.nfcActivity, "and stops with it")
    }

    @Test
    fun `a key that cannot be opened fails the operation, frees the key and gives the lease back`() = runTest {
        val device = mockk<YubiKeyDevice>()
        every { device.openConnection(FidoConnection::class.java) } throws IOException("busy")
        val key = SecurityKey(KeyTransport.USB, "usb-1", device)
        backend.pluggedAtStart += key
        val other = discovery.acquire() // keeps the discovery on, as a sign-in request would

        val failure = runCatching { manager().detectAndEnroll("label") }.exceptionOrNull()

        assertTrue(failure is Fido2EnrollManager.EnrollError, "$failure")
        assertEquals(EnrollFailure.KeyRemoved, (failure as Fido2EnrollManager.EnrollError).failure)
        assertSame(key, discovery.firstKey(), "a failed enrollment must not keep the key claimed")
        other.close()
        assertFalse(backend.usbRunning)
    }

    @Test
    fun `a claim granted as the enrollment is cancelled is given back`() = runTest {
        var claimClosed = false
        lateinit var enrolling: Job
        val granting = mockk<SecurityKeyDiscovery>()
        every { granting.acquire() } returns AutoCloseable { }
        coEvery { granting.claimKey(KeyUse.ENROLLMENT) } coAnswers {
            // The key is granted and the caller is cancelled in the same instant: the timeout block
            // is filled, and throws all the same when it ends.
            enrolling.cancel()
            KeyClaim(usbKey()) { claimClosed = true }
        }
        enrolling = async { Fido2EnrollManager(granting, StandardTestDispatcher(testScheduler), 60_000L).detectAndEnroll("label") }

        runCurrent()

        assertTrue(enrolling.isCancelled, "the cancellation goes on its way")
        assertTrue(claimClosed, "a claim nobody is going to use must not keep the key taken")
    }

    @Test
    fun `cancelling an enrollment tells the key to stop waiting for the touch and frees it`() = runBlocking {
        val key = usbKey()
        backend.pluggedAtStart += key
        val started = CompletableDeferred<CommandState>()
        val client = mockk<BasicWebAuthnClient>()
        every { client.makeCredential(any(), any(), any(), any(), any(), any()) } answers {
            val state = arg<CommandState>(5)
            started.complete(state)
            // Like the CTAP HID layer: poll the state while the key waits for the touch.
            var polls = 0
            while (!state.waitForCancel(0)) {
                check(polls++ < 500) { "the command was never cancelled" }
                Thread.sleep(10)
            }
            throw CtapException(CtapException.ERR_KEEPALIVE_CANCEL)
        }
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val manager = Fido2EnrollManager(discovery, Dispatchers.IO, 60_000L, FakeConnector(listOf(fakeCtap()), log)) { client }
        val outcome = CompletableDeferred<Result<Fido2EnrollManager.EnrollResult>>()
        val job = launch(Dispatchers.Default) { outcome.complete(runCatching { manager.detectAndEnroll("label") }) }

        val state = withTimeout(5_000) { started.await() }
        assertFalse(state.waitForCancel(0), "not cancelled while the caller waits")
        job.cancel()
        withTimeout(5_000) { job.join() }

        // The key answers the cancellation with an error: it must not be taken for a failure of the
        // key, the cancellation goes on its way.
        val failure = withTimeout(5_000) { outcome.await() }.exceptionOrNull()
        assertTrue(failure is CancellationException, "a cancellation must not become an enrollment error: $failure")
        assertTrue(state.waitForCancel(0), "the key must be told to stop waiting for the touch")
        assertEquals(listOf("connect:USB", "close"), log)
        assertFalse(backend.usbRunning)
        // And the key is free for the next request.
        val lease = discovery.acquire()
        assertSame(key, withTimeout(5_000) { discovery.firstKey() })
        lease.close()
    }

    @Test
    fun `two enrollments one after the other each take their own lease`() = runTest {
        repeat(2) {
            val enrolling = async { manager().detectAndEnroll("label") }
            runCurrent()
            assertTrue(backend.usbRunning)
            enrolling.cancel()
            runCurrent()
            assertFalse(backend.usbRunning)
        }
        assertEquals(2, backend.usbStarts)
    }

    // ── What the key says becomes a reason ────────────────────────────────────

    /** One USB key plugged in when the discovery starts, whatever an earlier call of the test plugged. */
    private fun pluggedKey() {
        backend.pluggedAtStart.clear()
        backend.pluggedAtStart += usbKey()
    }

    /**
     * Enrolls on a USB key whose WebAuthn client fails with [error] at MakeCredential, and returns the
     * failure. [pinConfigured] is what the key says of its PIN, [client] the client the key answers with.
     */
    private fun enrollFailingWith(
        error: Throwable,
        pin: CharArray? = null,
        pinConfigured: Boolean = true,
        client: BasicWebAuthnClient = mockk(),
    ): Throwable {
        pluggedKey()
        every { client.isPinConfigured } returns pinConfigured
        every { client.makeCredential(any(), any(), any(), any(), any(), any()) } throws error
        val manager = Fido2EnrollManager(discovery, Dispatchers.IO, 60_000L, FakeConnector(listOf(fakeCtap()))) { client }
        return runBlocking {
            checkNotNull(runCatching { withTimeout(5_000) { manager.detectAndEnroll("label", pin = pin) } }.exceptionOrNull())
        }
    }

    private fun failureOf(error: Throwable): EnrollFailure {
        assertTrue(error is Fido2EnrollManager.EnrollError, "$error")
        return (error as Fido2EnrollManager.EnrollError).failure
    }

    @Test
    fun `a key that wants a PIN nobody gave ends in PinRequired`() {
        assertEquals(EnrollFailure.PinRequired, failureOf(enrollFailingWith(PinRequiredClientError())))
    }

    @Test
    fun `a refused PIN says how many tries are left and zeroes the PIN`() {
        val pin = "1234".toCharArray()

        val failure = failureOf(
            enrollFailingWith(PinInvalidClientError(CtapException(CtapException.ERR_PIN_INVALID), 5), pin),
        )

        assertEquals(EnrollFailure.PinIncorrect(5), failure)
        assertTrue(pin.all { it == '\u0000' }, "the PIN must not outlive the enrollment")
    }

    @Test
    fun `a PIN typed for a key that has none ends in PinNotSet before the key is asked for a touch`() {
        val pin = "1234".toCharArray()
        val client = mockk<BasicWebAuthnClient>()

        val failure = failureOf(enrollFailingWith(IllegalStateException("not reached"), pin, pinConfigured = false, client = client))

        assertEquals(EnrollFailure.PinNotSet, failure)
        verify(exactly = 0) { client.makeCredential(any(), any(), any(), any(), any(), any()) }
        assertTrue(pin.all { it == '\u0000' }, "the PIN must not outlive the enrollment")
        assertFalse(backend.usbRunning, "the lease is given back")
    }

    @Test
    fun `no PIN typed on a key that has none goes on to the key, without a PIN`() {
        for (typed in listOf<CharArray?>(null, CharArray(0))) {
            val client = mockk<BasicWebAuthnClient>()
            val received = mutableListOf<CharArray?>()
            pluggedKey()
            every { client.isPinConfigured } returns false
            every { client.makeCredential(any(), any(), any(), any(), any(), any()) } answers {
                received += arg<CharArray?>(3)
                throw IllegalStateException("stop here")
            }
            val manager = Fido2EnrollManager(discovery, Dispatchers.IO, 60_000L, FakeConnector(listOf(fakeCtap()))) { client }

            val failure = runBlocking {
                checkNotNull(runCatching { withTimeout(5_000) { manager.detectAndEnroll("label", pin = typed) } }.exceptionOrNull())
            }

            assertEquals(EnrollFailure.Unexpected, failureOf(failure), "the key was asked, not turned down for a PIN")
            assertEquals(listOf<CharArray?>(null), received, "an empty PIN is no PIN")
        }
    }

    @Test
    fun `a key that says it has no PIN is told as PinNotSet, wrapped by the client or not`() {
        val ctap = CtapException(CtapException.ERR_PIN_NOT_SET)

        assertEquals(EnrollFailure.PinNotSet, failureOf(enrollFailingWith(ClientError(ClientError.Code.BAD_REQUEST, ctap))))
        assertEquals(EnrollFailure.PinNotSet, failureOf(enrollFailingWith(ctap)))
    }

    @Test
    fun `a blocked PIN, wrapped by the client, is told as blocked and not as a client error`() {
        // YubiKit's client wraps every CTAP error but the wrong PIN in a ClientError: reading only
        // the top of the exception turned a blocked key into a vague failure.
        val wrapped = ClientError(ClientError.Code.OTHER_ERROR, CtapException(CtapException.ERR_PIN_BLOCKED))

        assertEquals(EnrollFailure.PinBlocked, failureOf(enrollFailingWith(wrapped)))
    }

    @Test
    fun `a failure with nothing to say for it ends as an unexpected one, without its message`() {
        val error = enrollFailingWith(IllegalStateException("the PIN is $LEAK_MARKER"))

        assertEquals(EnrollFailure.Unexpected, failureOf(error))
        assertNoPayloadLeak(error)
    }

    @Test
    fun `the log of a failed enrollment holds codes and kinds, never the message of the failure`() {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            enrollFailingWith(ClientError(ClientError.Code.OTHER_ERROR, "the PIN is $LEAK_MARKER"))
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(tree.entries.isNotEmpty(), "the failure is logged")
        tree.entries.forEach {
            assertFalse(it.message.contains(LEAK_MARKER), "the log leaks the message: ${it.message}")
            assertNull(it.throwable, "a throwable would print its message")
        }
    }
}
