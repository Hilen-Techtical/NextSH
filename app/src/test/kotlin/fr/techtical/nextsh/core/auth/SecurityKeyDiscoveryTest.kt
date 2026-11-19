// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import android.hardware.usb.UsbDevice
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The discovery watches the keys only while someone asks, ties the NFC reader to the Activity on
 * screen, and never lets a pause or a stop break a wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SecurityKeyDiscoveryTest {

    private val backend = FakeSecurityKeyBackend()
    private val discovery = SecurityKeyDiscovery(backend, directExecutor)
    private val activity = mockk<Activity>()

    // ── On demand ─────────────────────────────────────────────────────────────

    @Test
    fun `nothing is watched while no lease is held`() {
        discovery.onActivityResumed(activity)

        assertFalse(backend.usbRunning)
        assertEquals(0, backend.usbStarts)
        assertTrue(backend.nfcStarts.isEmpty())
    }

    @Test
    fun `a lease starts the USB discovery and giving it back stops it`() {
        val lease = discovery.acquire()
        assertTrue(backend.usbRunning)

        lease.close()
        assertFalse(backend.usbRunning)
        assertEquals(1, backend.usbStarts)
        assertEquals(1, backend.usbStops)
    }

    @Test
    fun `the discovery keeps running until the last lease is given back`() {
        val first = discovery.acquire()
        val second = discovery.acquire()
        assertEquals(1, backend.usbStarts, "a second lease must not restart the discovery")

        first.close()
        assertTrue(backend.usbRunning)
        second.close()
        assertFalse(backend.usbRunning)
    }

    @Test
    fun `closing a lease twice gives it back once`() {
        val first = discovery.acquire()
        val second = discovery.acquire()

        first.close()
        first.close()

        assertTrue(backend.usbRunning, "the second lease is still held")
        second.close()
        assertFalse(backend.usbRunning)
    }

    // ── NFC: lease AND foreground ─────────────────────────────────────────────

    @Test
    fun `NFC runs only with a lease and an Activity in the foreground`() {
        // Foreground without a lease: the reader would catch every tag for nothing.
        discovery.onActivityResumed(activity)
        assertTrue(backend.nfcStarts.isEmpty())

        // Both: the reader is given to that Activity.
        val lease = discovery.acquire()
        assertEquals(listOf(activity), backend.nfcStarts)

        // Lease given back: the reader is released.
        lease.close()
        assertEquals(listOf(activity), backend.nfcStops)
        assertNull(backend.nfcActivity)
    }

    @Test
    fun `a lease without a foreground Activity does not start NFC`() {
        discovery.acquire()

        assertTrue(backend.usbRunning, "USB needs no Activity")
        assertTrue(backend.nfcStarts.isEmpty())
    }

    @Test
    fun `NFC follows the Activity through pause and resume`() {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        assertSame(activity, backend.nfcActivity)

        discovery.onActivityPaused(activity)
        assertNull(backend.nfcActivity)
        assertEquals(listOf(activity), backend.nfcStops)
        assertTrue(backend.usbRunning, "USB goes on while the Activity is paused")

        discovery.onActivityResumed(activity)
        assertSame(activity, backend.nfcActivity)
        assertEquals(2, backend.nfcStarts.size)
    }

    @Test
    fun `a paused Activity is forgotten`() {
        discovery.onActivityResumed(activity)
        discovery.onActivityPaused(activity)

        // A lease taken after the pause finds no Activity to hand the reader to.
        discovery.acquire()

        assertTrue(backend.nfcStarts.isEmpty())
    }

    @Test
    fun `pausing another Activity leaves the foreground one alone`() {
        val other = mockk<Activity>()
        discovery.acquire()
        discovery.onActivityResumed(activity)

        discovery.onActivityPaused(other)

        assertSame(activity, backend.nfcActivity)
    }

    @Test
    fun `unavailable NFC is retried at the next resume`() {
        backend.nfc = NfcStart.ABSENT
        discovery.acquire()
        discovery.onActivityResumed(activity)
        assertNull(backend.nfcActivity)

        backend.nfc = NfcStart.STARTED
        discovery.onActivityPaused(activity)
        discovery.onActivityResumed(activity)

        assertSame(activity, backend.nfcActivity)
    }

    // ── Waiting for a key ─────────────────────────────────────────────────────

    @Test
    fun `a key already plugged in is found at once`() = runTest {
        val key = usbKey()
        backend.pluggedAtStart += key
        discovery.acquire()

        assertSame(key, discovery.firstKey())
    }

    @Test
    fun `a key plugged in while waiting ends the wait`() = runTest {
        discovery.acquire()
        val waiting = async { discovery.firstKey() }
        runCurrent()
        assertFalse(waiting.isCompleted)

        val key = usbKey()
        backend.plug(key)
        runCurrent()

        assertSame(key, waiting.await())
    }

    @Test
    fun `a plugged key is remembered for a request that comes later`() = runTest {
        discovery.acquire()
        val key = usbKey()
        backend.plug(key)

        assertSame(key, discovery.firstKey())
        assertSame(key, discovery.firstKey(), "the key is still there for the next request")
    }

    @Test
    fun `an unplugged key is forgotten`() = runTest {
        discovery.acquire()
        backend.plug(usbKey("usb-1"))
        backend.unplug("usb-1")

        val waiting = async { discovery.firstKey() }
        runCurrent()

        assertFalse(waiting.isCompleted, "a key that is gone must not answer")
        waiting.cancel()
    }

    @Test
    fun `unplugging another key keeps the current one`() = runTest {
        discovery.acquire()
        val key = usbKey("usb-1")
        backend.plug(key)
        backend.unplug("usb-2")

        assertSame(key, discovery.firstKey())
    }

    // ── Several USB keys ──────────────────────────────────────────────────────

    @Test
    fun `with several keys plugged in the most recent one is served`() = runTest {
        discovery.acquire()
        backend.plug(usbKey("usb-1"))
        val latest = usbKey("usb-2")
        backend.plug(latest)

        assertSame(latest, discovery.firstKey())
    }

    @Test
    fun `unplugging the served key falls back on the one still plugged in`() = runTest {
        discovery.acquire()
        val older = usbKey("usb-1")
        backend.plug(older)
        backend.plug(usbKey("usb-2"))

        backend.unplug("usb-2")

        assertSame(older, discovery.firstKey(), "unplugging one key must not erase the other")
    }

    @Test
    fun `unplugging an older key keeps the most recent one`() = runTest {
        discovery.acquire()
        backend.plug(usbKey("usb-1"))
        val latest = usbKey("usb-2")
        backend.plug(latest)

        backend.unplug("usb-1")

        assertSame(latest, discovery.firstKey())
    }

    @Test
    fun `no key is left once every plugged key is unplugged`() = runTest {
        discovery.acquire()
        backend.plug(usbKey("usb-1"))
        backend.plug(usbKey("usb-2"))
        backend.unplug("usb-1")
        backend.unplug("usb-2")

        val waiting = async { discovery.firstKey() }
        runCurrent()

        assertFalse(waiting.isCompleted)
        waiting.cancel()
    }

    @Test
    fun `the remembered key is dropped when the discovery stops`() = runTest {
        val key = usbKey()
        backend.pluggedAtStart += key
        discovery.acquire().close()

        // YubiKit closed its devices at the stop, and the key was unplugged in the meantime.
        backend.pluggedAtStart.clear()
        discovery.acquire()
        val waiting = async { discovery.firstKey() }
        runCurrent()

        assertFalse(waiting.isCompleted)
        waiting.cancel()
    }

    @Test
    fun `a key still plugged in is found again at the next start`() = runTest {
        val key = usbKey()
        backend.pluggedAtStart += key
        discovery.acquire().close()

        discovery.acquire()

        assertSame(key, discovery.firstKey())
    }

    @Test
    fun `an NFC tap ends the wait`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val waiting = async { discovery.firstKey() }
        runCurrent()

        val tag = nfcKey()
        backend.tap(tag)
        runCurrent()

        assertSame(tag, waiting.await())
    }

    @Test
    fun `an NFC tap nobody waits for is dropped`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        backend.tap(nfcKey())

        val waiting = async { discovery.firstKey() }
        runCurrent()

        assertFalse(waiting.isCompleted, "a tap is an event: it is not kept for a request that comes later")
        waiting.cancel()
    }

    @Test
    fun `an NFC tap goes to one request only`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val first = async { discovery.firstKey() }
        runCurrent()
        val second = async { discovery.firstKey() }
        runCurrent()

        val tag = nfcKey()
        backend.tap(tag)
        runCurrent()

        assertSame(tag, first.await())
        assertFalse(second.isCompleted)
        second.cancel()
    }

    @Test
    fun `a plugged key answers every request that waits`() = runTest {
        discovery.acquire()
        val first = async { discovery.firstKey() }
        val second = async { discovery.firstKey() }
        runCurrent()

        val key = usbKey()
        backend.plug(key)
        runCurrent()

        assertSame(key, first.await())
        assertSame(key, second.await())
    }

    @Test
    fun `the same key reported twice is one key`() = runTest {
        discovery.acquire()
        val first = usbKey("usb-1")
        backend.plug(first)
        backend.plug(usbKey("usb-1"))

        assertSame(first, discovery.firstKey())
    }

    // ── A pause never breaks a wait ───────────────────────────────────────────

    @Test
    fun `pausing and resuming the Activity does not fail the wait`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val waiting = async { discovery.firstKey() }
        runCurrent()

        // The system dialog for the USB permission, or the trampoline, pauses MainActivity.
        discovery.onActivityPaused(activity)
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(waiting.isCancelled)

        discovery.onActivityResumed(activity)
        runCurrent()
        assertFalse(waiting.isCompleted)

        val tag = nfcKey()
        backend.tap(tag)
        runCurrent()
        assertSame(tag, waiting.await())
    }

    @Test
    fun `giving the lease back for a moment does not fail the wait`() = runTest {
        val lease = discovery.acquire()
        val waiting = async { discovery.firstKey() }
        runCurrent()

        // The enrollment screen is rotated: it gives the lease back and takes it again.
        lease.close()
        discovery.acquire()
        runCurrent()
        assertFalse(waiting.isCompleted)
        assertFalse(waiting.isCancelled)

        val key = usbKey()
        backend.plug(key)
        runCurrent()
        assertSame(key, waiting.await())
    }

    // ── Cancelling ────────────────────────────────────────────────────────────

    @Test
    fun `cancelling the wait leaves no waiter behind`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val cancelled = async { discovery.firstKey() }
        runCurrent()
        cancelled.cancel()
        runCurrent()
        assertTrue(runCatching { cancelled.await() }.exceptionOrNull() is CancellationException)

        // The tap that follows is not swallowed by the dead waiter: the next request gets it.
        val live = async { discovery.firstKey() }
        runCurrent()
        val tag = nfcKey()
        backend.tap(tag)
        runCurrent()

        assertSame(tag, live.await())
    }

    // ── Exclusive use of the key ──────────────────────────────────────────────

    @Test
    fun `a key in use is not given to a second request until it is released`() = runTest {
        discovery.acquire()
        val key = usbKey()
        backend.plug(key)
        val first = discovery.claimKey(KeyUse.SIGNATURE)
        assertSame(key, first.key)

        val second = async { discovery.claimKey(KeyUse.ENROLLMENT) }
        runCurrent()
        assertFalse(second.isCompleted, "two requests must not talk to the same key together")

        first.close()
        runCurrent()

        assertSame(key, second.await().key)
    }

    @Test
    fun `closing a claim twice releases the key once`() = runTest {
        discovery.acquire()
        backend.plug(usbKey())
        val first = discovery.claimKey(KeyUse.SIGNATURE)
        val second = async { discovery.claimKey(KeyUse.SIGNATURE) }
        val third = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()

        first.close()
        runCurrent()
        first.close() // the second claim is out: a stale second close must not free it
        runCurrent()

        assertTrue(second.isCompleted)
        assertFalse(third.isCompleted, "the key is held by the second request")
        third.cancel()
    }

    @Test
    fun `an NFC tap goes to the sign-in request before the enrollment that waited longer`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val enrollment = async { discovery.claimKey(KeyUse.ENROLLMENT) }
        runCurrent()
        val signature = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()

        val tag = nfcKey()
        backend.tap(tag)
        runCurrent()

        assertSame(tag, signature.await().key, "a tap meant for the prompt must not create a key")
        assertFalse(enrollment.isCompleted)
        enrollment.cancel()
    }

    @Test
    fun `a released USB key goes to the sign-in request before the enrollment that waited longer`() = runTest {
        discovery.acquire()
        backend.plug(usbKey())
        val holder = discovery.claimKey(KeyUse.ENROLLMENT)
        val enrollment = async { discovery.claimKey(KeyUse.ENROLLMENT) }
        runCurrent()
        val signature = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()

        holder.close()
        runCurrent()

        assertTrue(signature.isCompleted)
        assertFalse(enrollment.isCompleted)
        enrollment.cancel()
    }

    @Test
    fun `requests of the same kind are served in the order they came`() = runTest {
        discovery.acquire()
        backend.plug(usbKey())
        val holder = discovery.claimKey(KeyUse.SIGNATURE)
        val first = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        val second = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()

        holder.close()
        runCurrent()

        assertTrue(first.isCompleted)
        assertFalse(second.isCompleted)
        second.cancel()
    }

    @Test
    fun `an NFC tap that comes while a key is in use is dropped`() = runTest {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        val holding = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        backend.tap(nfcKey())
        runCurrent()
        val claim = holding.await()
        val waiting = async { discovery.claimKey(KeyUse.ENROLLMENT) }
        runCurrent()

        backend.tap(nfcKey()) // the user moves the tag around while the key is being used
        runCurrent()
        assertFalse(waiting.isCompleted, "the tag in use must not be handed to a second request")

        claim.close()
        runCurrent()
        assertFalse(waiting.isCompleted, "a tag is an event: it is not kept for the next request")
        waiting.cancel()
    }

    @Test
    fun `a request cancelled while waiting for a busy key leaves it to the next one`() = runTest {
        discovery.acquire()
        backend.plug(usbKey())
        val holder = discovery.claimKey(KeyUse.SIGNATURE)
        val cancelled = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        val next = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        cancelled.cancel()
        runCurrent()

        holder.close()
        runCurrent()

        assertTrue(next.isCompleted, "a request that is gone must not keep a place in the queue")
    }

    @Test
    fun `a key granted to a request that is cancelled at that very moment is passed on`() = runTest {
        discovery.acquire()
        backend.plug(usbKey())
        val holder = discovery.claimKey(KeyUse.SIGNATURE)
        val dying = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        val next = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()

        // The key goes to the first waiter, which is cancelled before it ever resumes with it.
        holder.close()
        dying.cancel()
        runCurrent()

        assertTrue(next.isCompleted, "the key granted to a cancelled request must not stay claimed for ever")
    }

    @Test
    fun `a plugged key is not claimed by a request that was cancelled while it waited`() = runTest {
        discovery.acquire()
        val cancelled = async { discovery.claimKey(KeyUse.SIGNATURE) }
        runCurrent()
        cancelled.cancel()
        runCurrent()

        val key = usbKey()
        backend.plug(key)

        assertSame(key, discovery.firstKey(), "the dead request must not have kept the key claimed")
    }

    // ── What stands in the way of a key ───────────────────────────────────────

    @Test
    fun `nothing is reported while all is well`() {
        discovery.acquire()
        discovery.onActivityResumed(activity)

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `a USB key that Android keeps from NextSH is reported`() {
        discovery.acquire()

        backend.deny("usb-1")

        assertEquals(KeyHint.USB_ACCESS_DENIED, discovery.hint.value)
    }

    @Test
    fun `a key found without access at the start of the scan is reported too`() {
        backend.deniedAtStart += "usb-1"

        discovery.acquire()

        assertEquals(KeyHint.USB_ACCESS_DENIED, discovery.hint.value)
    }

    @Test
    fun `the report goes away when the same key comes back with access`() {
        discovery.acquire()
        backend.deny("usb-1")

        backend.plug(usbKey("usb-1"))

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `the report goes away when the trampoline hands the key over with access`() {
        discovery.acquire()
        backend.deny("usb-1")
        backend.adopted = usbKey("usb-1")

        discovery.onUsbAttachIntent(mockk<UsbDevice>())

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `the report goes away when the refused key is unplugged`() {
        discovery.acquire()
        backend.deny("usb-1")

        backend.unplug("usb-1")

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `unplugging another key keeps the report of the refused one`() {
        discovery.acquire()
        backend.deny("usb-1")

        backend.unplug("usb-2")

        assertEquals(KeyHint.USB_ACCESS_DENIED, discovery.hint.value)
    }

    @Test
    fun `a refused key says nothing once another one can be used`() {
        discovery.acquire()
        backend.deny("usb-1")

        backend.plug(usbKey("usb-2"))

        assertEquals(KeyHint.NONE, discovery.hint.value, "nobody waits when a key can be used")
    }

    @Test
    fun `nothing is reported once the last lease is given back`() {
        val lease = discovery.acquire()
        backend.deny("usb-1")

        lease.close()

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `a refusal reported after the last lease is given back is ignored`() {
        discovery.acquire().close()

        backend.lastUsbEvents!!.denied("usb-1") // a late callback of a stopped discovery

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    @Test
    fun `switched off NFC is reported when the Activity is in the foreground`() {
        backend.nfc = NfcStart.DISABLED
        discovery.acquire()
        assertEquals(KeyHint.NONE, discovery.hint.value, "no Activity yet: the reader was not tried")

        discovery.onActivityResumed(activity)

        assertEquals(KeyHint.NFC_DISABLED, discovery.hint.value)
    }

    @Test
    fun `a phone without NFC says nothing`() {
        backend.nfc = NfcStart.ABSENT
        discovery.acquire()

        discovery.onActivityResumed(activity)

        assertEquals(KeyHint.NONE, discovery.hint.value, "the user cannot do anything about it")
    }

    @Test
    fun `the report of switched off NFC goes away at the resume that finds it on`() {
        backend.nfc = NfcStart.DISABLED
        discovery.acquire()
        discovery.onActivityResumed(activity)

        backend.nfc = NfcStart.STARTED // the user came back from the settings
        discovery.onActivityPaused(activity)
        discovery.onActivityResumed(activity)

        assertEquals(KeyHint.NONE, discovery.hint.value)
        assertSame(activity, backend.nfcActivity)
    }

    @Test
    fun `a refused USB key is reported before switched off NFC`() {
        backend.nfc = NfcStart.DISABLED
        discovery.acquire()
        discovery.onActivityResumed(activity)

        backend.deny("usb-1")

        assertEquals(KeyHint.USB_ACCESS_DENIED, discovery.hint.value, "a key is there: that is what to fix")
    }

    @Test
    fun `switched off NFC is not reported when the lease is given back`() {
        backend.nfc = NfcStart.DISABLED
        val lease = discovery.acquire()
        discovery.onActivityResumed(activity)

        lease.close()

        assertEquals(KeyHint.NONE, discovery.hint.value)
    }

    // ── YubiKit failing ───────────────────────────────────────────────────────

    @Test
    fun `a USB discovery that cannot start does not break the lease and is retried at the next resume`() {
        backend.startUsbFailure = IllegalStateException("no receiver")

        val lease = discovery.acquire() // must not throw: the caller would never get its lease
        assertFalse(backend.usbRunning)

        backend.startUsbFailure = null
        discovery.onActivityResumed(activity)
        assertTrue(backend.usbRunning, "the resume of the Activity tries again")

        lease.close()
        assertFalse(backend.usbRunning, "the lease the caller holds is the one that counts")
    }

    @Test
    fun `an NFC reader that cannot start leaves USB on and is retried at the next resume`() {
        backend.startNfcFailure = IllegalStateException("adapter")
        discovery.acquire()

        discovery.onActivityResumed(activity) // must not throw into the lifecycle callback
        assertTrue(backend.usbRunning)
        assertNull(backend.nfcActivity)

        backend.startNfcFailure = null
        discovery.onActivityPaused(activity)
        discovery.onActivityResumed(activity)
        assertSame(activity, backend.nfcActivity)
    }

    @Test
    fun `a stop that fails still counts as stopped and the next lease starts again`() {
        backend.stopUsbFailure = IllegalStateException("receiver already gone")
        discovery.acquire().close() // must not throw

        backend.stopUsbFailure = null
        discovery.acquire()

        assertEquals(2, backend.usbStarts)
        assertTrue(backend.usbRunning)
    }

    @Test
    fun `an NFC reader that cannot stop does not stop the next one from starting`() {
        discovery.acquire()
        discovery.onActivityResumed(activity)
        backend.stopNfcFailure = IllegalStateException("reader")

        discovery.onActivityPaused(activity) // must not throw
        backend.stopNfcFailure = null
        discovery.onActivityResumed(activity)

        assertSame(activity, backend.nfcActivity)
    }

    // ── Resuming a request ────────────────────────────────────────────────────

    @Test
    fun `a waiting request is resumed outside the lock`() = runBlocking {
        discovery.acquire()
        val reentered = CompletableDeferred<Boolean>()
        // Unconfined: the request resumes on the thread that completes it, the one that plugs the
        // key. Another thread asking the discovery for something meanwhile must not be locked out.
        val waiting = launch(Dispatchers.Unconfined) {
            discovery.firstKey()
            val other = thread { discovery.acquire() }
            other.join(2_000)
            reentered.complete(!other.isAlive)
        }

        backend.plug(usbKey())

        assertTrue(withTimeout(5_000) { reentered.await() }, "the discovery stayed locked while resuming")
        waiting.join()
    }

    // ── Manifest trampoline ───────────────────────────────────────────────────

    @Test
    fun `the trampoline hands the device over when a lease is held`() = runTest {
        val key = usbKey("usb-7")
        backend.adopted = key
        discovery.acquire()
        val device = mockk<UsbDevice>()

        discovery.onUsbAttachIntent(device)

        assertEquals(listOf(device), backend.adoptedDevices)
        assertSame(key, discovery.firstKey())
    }

    @Test
    fun `the trampoline ignores the device when nobody looks for a key`() {
        backend.adopted = usbKey()

        discovery.onUsbAttachIntent(mockk<UsbDevice>())

        assertTrue(backend.adoptedDevices.isEmpty(), "NextSH must not even look at a key nobody asked for")
    }

    @Test
    fun `a device the trampoline cannot use does not become a key`() = runTest {
        backend.adopted = null
        discovery.acquire()

        discovery.onUsbAttachIntent(mockk<UsbDevice>())

        val waiting = async { discovery.firstKey() }
        runCurrent()
        assertFalse(waiting.isCompleted)
        waiting.cancel()
    }

    @Test
    fun `a key from YubiKit and the same key from the trampoline are one key`() = runTest {
        discovery.acquire()
        val fromYubiKit = usbKey("usb-1")
        backend.plug(fromYubiKit)
        backend.adopted = usbKey("usb-1")

        discovery.onUsbAttachIntent(mockk<UsbDevice>())

        assertSame(fromYubiKit, discovery.firstKey())
    }

    @Test
    fun `a USB event that comes after the last lease is given back is ignored`() = runTest {
        discovery.acquire().close()

        // A late callback of a discovery that has been stopped.
        backend.lastUsbEvents!!.attached(usbKey())

        discovery.acquire()
        val waiting = async { discovery.firstKey() }
        runCurrent()
        assertFalse(waiting.isCompleted, "nobody wanted that key")
        waiting.cancel()
    }
}
