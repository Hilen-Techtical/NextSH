// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import com.yubico.yubikit.core.YubiKeyDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** How a security key reaches the phone. */
enum class KeyTransport { USB, NFC }

/**
 * What a claim on a key is for. Declared in increasing priority: when two requests wait for the
 * key, the later one in this list goes first.
 */
enum class KeyUse {
    /** Creating a credential, from the enrollment screen. */
    ENROLLMENT,

    /** Signing in to an SSH server: a connection waits for it, so it goes before an enrollment. */
    SIGNATURE,
}

/**
 * Why no key can be used right now, for the UI to say. Only meaningful while a request waits for a
 * key; [NONE] when there is nothing to report, including on a phone without NFC (there is nothing
 * the user could do about it).
 */
enum class KeyHint {
    NONE,

    /**
     * A USB key is plugged in but Android has not let NextSH use it, yet or at all: YubiKit reports
     * the key as soon as it is plugged in, while the system prompt that grants the access is still
     * on screen, so this is the normal state until the user accepts it.
     */
    USB_ACCESS_DENIED,

    /** NFC is switched off in the settings of the phone. */
    NFC_DISABLED,
}

/**
 * The exclusive use of a [key], handed out by [SecurityKeyDiscovery.claimKey]. Close it once the
 * connection to the key is closed: the next request waiting for a key gets it then. Closing twice
 * is harmless.
 */
class KeyClaim internal constructor(
    val key: SecurityKey,
    private val onClose: () -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) onClose()
    }
}

/**
 * A security key found by [SecurityKeyDiscovery]: [device] is what YubiKit opens a connection on,
 * [transport] tells which kind of connection works (HID FIDO over USB, ISO 7816 over NFC).
 *
 * [id] names a USB attachment for as long as the key stays plugged in, so that the same key
 * reported twice (YubiKit's own scan and the manifest trampoline) is one key. An NFC tap has no
 * identity: null.
 */
class SecurityKey internal constructor(
    val transport: KeyTransport,
    internal val id: String?,
    internal val device: YubiKeyDevice,
)

/** What the discovery needs from YubiKit, so that tests can stand in for the hardware. */
internal interface SecurityKeyBackend {
    /**
     * Starts the USB discovery. Keys already plugged in are reported through
     * [UsbEvents.attached] too, and a key leaving through [UsbEvents.detached].
     */
    fun startUsb(events: UsbEvents)

    fun stopUsb()

    /**
     * Puts the NFC reader of [activity] to work and reports every tapped tag through [onTap].
     * Says why it did not start when it did not: there is no NFC, or it is switched off.
     */
    fun startNfc(activity: Activity, onTap: (SecurityKey) -> Unit): NfcStart

    fun stopNfc(activity: Activity)

    /** Wraps a USB device the manifest trampoline received, or null when it is not a YubiKey. */
    fun adoptUsbDevice(usbDevice: UsbDevice): SecurityKey?
}

/** How starting the NFC reader went. */
internal enum class NfcStart { STARTED, DISABLED, ABSENT }

internal interface UsbEvents {
    fun attached(key: SecurityKey)

    /** A key is plugged in under [id] but Android did not give NextSH the right to use it. */
    fun denied(id: String)

    fun detached(id: String)
}

/**
 * The one place where security keys are looked for, shared by the FIDO2 prompt (SSH sign-in) and
 * the enrollment screen. Two YubiKit managers would fight over the same USB and NFC events.
 *
 * On demand only. Whoever needs the keys takes a lease ([acquire]) and gives it back when done;
 * nothing is watched while no lease is held. The NFC reader mode of Android takes every tag of the
 * foreground Activity, contactless payment cards included, so it must not run all the time.
 *
 * - USB runs while a lease is held, whatever the Activity is doing: the system dialog that asks
 *   to use the key, and the invisible trampoline, put MainActivity on pause for a moment.
 * - NFC runs only while a lease is held AND an Activity is in the foreground
 *   ([onActivityResumed] / [onActivityPaused]). The Activity is kept from resume to pause and
 *   never longer.
 * - Pausing or stopping the discovery never fails a wait ([claimKey]): the wait goes on and the
 *   next resume picks the NFC reader up again. Only cancelling the waiting coroutine ends a wait.
 *
 * Talking to a key is exclusive. The FIDO2 prompt of a connection and the enrollment screen can be
 * alive together (the prompt is drawn over the whole app, for a tunnel that reconnects, say), and
 * both would open the HID interface of the same USB key, or take the same NFC tap. A request gets
 * the key through [claimKey] and keeps it until it closes the claim; the others wait, the sign-in
 * request first ([KeyUse]).
 *
 * A key already plugged in when a lease starts is found by the scan YubiKit does at start, and is
 * remembered until it is unplugged, so that a request finds it at once. Several keys can be plugged
 * in at once: they are told apart by their attachment, the most recent one still plugged in is the
 * one served, and unplugging a key leaves the others alone. A tap on an NFC tag is an event, not a
 * state: it goes to the request that is waiting, and is dropped when none is.
 *
 * Threads: YubiKit calls back on the main thread (USB) and on the NFC reader thread. The state is
 * guarded by [lock]; the backend is only driven from the main thread through [mainExecutor].
 * A waiting request is always resumed outside [lock]: its code may run on the thread that completes
 * it, and must be free to call back into the discovery.
 *
 * A failure of YubiKit while starting or stopping a reader is logged and goes no further: the
 * leases stay what the callers hold, and the next reconciliation (a new lease, a resume of the
 * Activity) tries again.
 */
@Singleton
class SecurityKeyDiscovery internal constructor(
    private val backend: SecurityKeyBackend,
    private val mainExecutor: Executor,
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(YubiKitBackend(context), MainThreadExecutor)

    private class Waiter(val use: KeyUse) {
        val deferred = CompletableDeferred<SecurityKey>()

        /** True once a key was granted to this request. Guarded by [lock]. */
        var granted = false
    }

    private class Grant(val waiter: Waiter, val key: SecurityKey)

    /** Guards the fields below. */
    private val lock = Any()
    private var leaseCount = 0
    private var foreground: Activity? = null

    /** The USB keys plugged in, by attachment, in the order they came: the most recent is last. */
    private val usbKeys = LinkedHashMap<String, SecurityKey>()
    private val waiters = ArrayList<Waiter>()

    /** A claim is out: nobody else may talk to a key until it is given back. */
    private var claimed = false

    /** The USB attachments Android did not let NextSH use. */
    private val deniedUsb = LinkedHashSet<String>()

    /** The NFC reader could not start because NFC is switched off. */
    private var nfcDisabled = false

    // What the backend is doing now. Only touched on the main thread, by [reconcile].
    private var usbRunning = false
    private var nfcActivity: Activity? = null

    private val _hint = MutableStateFlow(KeyHint.NONE)

    /**
     * What stands in the way of a key being found, for the UI to tell a request that waits: a USB
     * key that Android has not let NextSH use (the user has to accept the system prompt, which is
     * on screen when the key is plugged in; unplugging the key and plugging it back brings it again
     * when it was refused or missed), or NFC switched off. A usable key hides it, since nobody
     * waits then.
     * Retried at every resume of the Activity, which is when the user comes back from the settings.
     */
    val hint: StateFlow<KeyHint> = _hint.asStateFlow()

    private val usbEvents = object : UsbEvents {
        override fun attached(key: SecurityKey) = publishUsb(key)
        override fun denied(id: String) = refuseUsb(id)
        override fun detached(id: String) = forgetUsb(id)
    }

    /**
     * Asks for the keys to be watched until the returned lease is closed. Closing twice is
     * harmless. Always close it, in a `finally`.
     */
    fun acquire(): AutoCloseable {
        synchronized(lock) { leaseCount++ }
        mainExecutor.execute(::reconcile)
        return Lease()
    }

    private inner class Lease : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                synchronized(lock) { leaseCount-- }
                mainExecutor.execute(::reconcile)
            }
        }
    }

    /**
     * Suspends until a key is there and is free, then hands it over for the caller alone: a USB key
     * already plugged in answers at once (the most recent one when there are several), otherwise
     * the next USB key or NFC tap does. Only a cancellation of the caller ends the wait without a
     * key. Nothing is watched without a lease: the caller holds one.
     *
     * The key is exclusive until the returned claim is closed: nobody else gets it meanwhile, so
     * two requests never open a connection on the same key together (the second would fail to claim
     * the USB interface), and an NFC tap meant for one is never taken by the other. Close the claim
     * after the connection to the key is closed, in a `finally`.
     *
     * When several requests wait, the one with the higher [use] goes first, then the oldest. An NFC
     * tap that comes while a claim is out is dropped: the user is busy with the key in use, and a
     * tag is an event that is not kept.
     */
    suspend fun claimKey(use: KeyUse): KeyClaim {
        val waiter = Waiter(use)
        val grant = synchronized(lock) {
            waiters.add(waiter)
            grantLocked(tap = null)
        }
        grant?.let(::deliver)
        val key = try {
            waiter.deferred.await()
        } catch (e: CancellationException) {
            withdraw(waiter)
            throw e
        }
        return KeyClaim(key, ::releaseClaim)
    }

    /**
     * Gives the key to the request that has to go first, if it is free and there is a key. [tap] is
     * the tag that just came, if any. Call under [lock], and [deliver] the result outside of it.
     */
    private fun grantLocked(tap: SecurityKey?): Grant? {
        if (claimed) return null
        var best: Waiter? = null
        for (waiter in waiters) {
            // Strictly higher only: among equals the oldest, which comes first in the list, stays.
            if (best == null || waiter.use > best.use) best = waiter
        }
        val waiter = best ?: return null
        val key = usbKeys.values.lastOrNull() ?: tap ?: return null
        waiters.remove(waiter)
        waiter.granted = true
        claimed = true
        return Grant(waiter, key)
    }

    private fun deliver(grant: Grant) {
        grant.waiter.deferred.complete(grant.key)
    }

    /** A request that waits is cancelled: it leaves the queue, and a key that came to it at that very moment goes to the next. */
    private fun withdraw(waiter: Waiter) {
        val next = synchronized(lock) {
            if (waiter.granted) {
                claimed = false
                grantLocked(tap = null)
            } else {
                waiters.remove(waiter)
                null
            }
        }
        next?.let(::deliver)
    }

    private fun releaseClaim() {
        val next = synchronized(lock) {
            claimed = false
            // The tag of an NFC claim is not kept: only a plugged key can go to the next request.
            grantLocked(tap = null)
        }
        next?.let(::deliver)
    }

    /** MainActivity is in the foreground: the NFC reader can be given to it. */
    fun onActivityResumed(activity: Activity) {
        synchronized(lock) { foreground = activity }
        mainExecutor.execute(::reconcile)
    }

    /** MainActivity leaves the foreground: the NFC reader is released and the Activity forgotten. */
    fun onActivityPaused(activity: Activity) {
        synchronized(lock) { if (foreground === activity) foreground = null }
        mainExecutor.execute(::reconcile)
    }

    /**
     * Called by the manifest trampoline when Android hands it a USB_DEVICE_ATTACHED intent, which
     * comes with the permission to use the key. Ignored when nobody holds a lease: NextSH does not
     * wake up for a key nobody asked for.
     */
    fun onUsbAttachIntent(usbDevice: UsbDevice) {
        val wanted = synchronized(lock) { leaseCount > 0 }
        if (!wanted) {
            Timber.d("SecurityKeyDiscovery: USB attach received but nobody is looking for a key, ignored")
            return
        }
        val key = backend.adoptUsbDevice(usbDevice) ?: return
        Timber.d("SecurityKeyDiscovery: USB key delivered through the manifest intent")
        publishUsb(key)
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    /** Brings what the backend does in line with the leases and the foreground. Main thread only. */
    private fun reconcile() {
        val wantUsb: Boolean
        val wantNfc: Activity?
        synchronized(lock) {
            wantUsb = leaseCount > 0
            wantNfc = if (wantUsb) foreground else null
        }

        // What YubiKit does can fail (a receiver that cannot be registered, a reader that cannot be
        // enabled). Nothing of that may reach the caller of acquire() or the lifecycle callback of
        // the Activity: it is logged, and the state below only says what really runs.
        if (wantUsb && !usbRunning) {
            try {
                backend.startUsb(usbEvents)
                usbRunning = true
            } catch (e: Exception) {
                Timber.w("SecurityKeyDiscovery: the USB discovery could not start (%s)", e.javaClass.simpleName)
                // Whatever it did before failing is undone, so that a retry starts from nothing.
                try {
                    backend.stopUsb()
                } catch (_: Exception) {
                    // Nothing more can be done: the next start tries again.
                }
            }
        } else if (!wantUsb && usbRunning) {
            usbRunning = false
            try {
                backend.stopUsb()
            } catch (e: Exception) {
                Timber.w("SecurityKeyDiscovery: the USB discovery could not stop (%s)", e.javaClass.simpleName)
            }
        }
        if (!wantUsb) {
            // YubiKit closes the devices it knows when the discovery stops: the keys are found
            // again by the scan of the next start. Nothing is reported while nobody looks.
            synchronized(lock) {
                usbKeys.clear()
                deniedUsb.clear()
                nfcDisabled = false
                updateHintLocked()
            }
        }

        val running = nfcActivity
        if (running !== wantNfc) {
            if (running != null) {
                nfcActivity = null
                try {
                    backend.stopNfc(running)
                } catch (e: Exception) {
                    Timber.w("SecurityKeyDiscovery: the NFC reader could not stop (%s)", e.javaClass.simpleName)
                }
            }
            var disabled = false
            if (wantNfc != null) {
                try {
                    when (backend.startNfc(wantNfc, ::onNfcTap)) {
                        NfcStart.STARTED -> nfcActivity = wantNfc
                        NfcStart.DISABLED -> disabled = true
                        // No NFC in this phone: nothing to tell, the user cannot change it.
                        NfcStart.ABSENT -> Unit
                    }
                } catch (e: Exception) {
                    Timber.w("SecurityKeyDiscovery: the NFC reader could not start (%s)", e.javaClass.simpleName)
                }
            }
            synchronized(lock) {
                nfcDisabled = disabled
                updateHintLocked()
            }
        }
    }

    /** Recomputes [hint] from what is known. Call under [lock]. */
    private fun updateHintLocked() {
        _hint.value = when {
            // A usable key: nobody waits for one, nothing to say.
            usbKeys.isNotEmpty() -> KeyHint.NONE
            deniedUsb.isNotEmpty() -> KeyHint.USB_ACCESS_DENIED
            nfcDisabled -> KeyHint.NFC_DISABLED
            else -> KeyHint.NONE
        }
    }

    // ── Events from the backend ──────────────────────────────────────────────

    private fun publishUsb(key: SecurityKey) {
        val id = key.id ?: return
        val grant = synchronized(lock) {
            if (leaseCount == 0) return
            // Android let NextSH use it after all (the system prompt was accepted).
            deniedUsb.remove(id)
            // The same key reported twice (YubiKit's scan, then the trampoline) is one key.
            if (id in usbKeys) {
                updateHintLocked()
                return
            }
            usbKeys[id] = key
            updateHintLocked()
            // A plugged key is a state: the request that goes first can take it, if it is free.
            grantLocked(tap = null)
        }
        grant?.let(::deliver)
    }

    private fun refuseUsb(id: String) {
        synchronized(lock) {
            if (leaseCount == 0 || id in usbKeys) return
            deniedUsb.add(id)
            updateHintLocked()
        }
    }

    private fun forgetUsb(id: String) {
        synchronized(lock) {
            usbKeys.remove(id)
            deniedUsb.remove(id)
            updateHintLocked()
        }
    }

    private fun onNfcTap(key: SecurityKey) {
        // A tap is an event, and it goes to one request only: the one that goes first.
        val grant = synchronized(lock) { grantLocked(tap = key) }
        if (grant == null) {
            Timber.d("SecurityKeyDiscovery: NFC tap dropped, no request can take a key now")
            return
        }
        deliver(grant)
    }

    /** Runs on the main thread: inline when already there, posted otherwise. */
    private object MainThreadExecutor : Executor {
        private val handler by lazy { Handler(Looper.getMainLooper()) }

        override fun execute(command: Runnable) {
            if (Looper.myLooper() === Looper.getMainLooper()) command.run() else handler.post(command)
        }
    }
}
