// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import android.hardware.usb.UsbDevice
import com.yubico.yubikit.core.YubiKeyDevice
import io.mockk.mockk
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertSame
import java.util.concurrent.Executor

/** A [SecurityKey] over USB, backed by a device mock. */
internal fun usbKey(id: String = "usb-1") = SecurityKey(KeyTransport.USB, id, mockk<YubiKeyDevice>())

/** A [SecurityKey] from an NFC tap, backed by a device mock. */
internal fun nfcKey() = SecurityKey(KeyTransport.NFC, null, mockk<YubiKeyDevice>())

/** Runs the work where it is called: the "main thread" of the tests is the test thread. */
internal val directExecutor = Executor { it.run() }

/**
 * Stands in for YubiKit: records what the discovery asks of it and lets a test plug a key, unplug
 * it, or tap an NFC tag.
 */
internal class FakeSecurityKeyBackend : SecurityKeyBackend {

    /** Keys already plugged in: reported as soon as the USB discovery starts, like YubiKit's scan. */
    val pluggedAtStart = mutableListOf<SecurityKey>()

    /** Attachments YubiKit reports without the permission to use them, at start too. */
    val deniedAtStart = mutableListOf<String>()

    /** What starting the NFC reader does: it works, NFC is switched off, or there is none. */
    var nfc = NfcStart.STARTED

    /** Makes the next calls of the matching method throw this, when set: YubiKit failing. */
    var startUsbFailure: RuntimeException? = null
    var stopUsbFailure: RuntimeException? = null
    var startNfcFailure: RuntimeException? = null
    var stopNfcFailure: RuntimeException? = null

    /** What [adoptUsbDevice] returns: the key the trampoline's device turns into. */
    var adopted: SecurityKey? = null
    val adoptedDevices = mutableListOf<UsbDevice>()

    var usbStarts = 0
        private set
    var usbStops = 0
        private set
    val nfcStarts = mutableListOf<Activity>()
    val nfcStops = mutableListOf<Activity>()

    private var usbEvents: UsbEvents? = null
    private var nfcTap: ((SecurityKey) -> Unit)? = null

    /** The listener of the last start, kept after the stop to simulate a late callback. */
    var lastUsbEvents: UsbEvents? = null
        private set

    val usbRunning: Boolean get() = usbEvents != null

    /** The Activity the NFC reader is on now, or null. */
    var nfcActivity: Activity? = null
        private set

    override fun startUsb(events: UsbEvents) {
        usbStarts++
        startUsbFailure?.let { throw it }
        usbEvents = events
        lastUsbEvents = events
        pluggedAtStart.toList().forEach { events.attached(it) }
        deniedAtStart.toList().forEach { events.denied(it) }
    }

    override fun stopUsb() {
        usbStops++
        usbEvents = null
        stopUsbFailure?.let { throw it }
    }

    override fun startNfc(activity: Activity, onTap: (SecurityKey) -> Unit): NfcStart {
        startNfcFailure?.let { throw it }
        if (nfc != NfcStart.STARTED) return nfc
        nfcStarts += activity
        nfcActivity = activity
        nfcTap = onTap
        return NfcStart.STARTED
    }

    override fun stopNfc(activity: Activity) {
        nfcStops += activity
        nfcActivity = null
        nfcTap = null
        stopNfcFailure?.let { throw it }
    }

    override fun adoptUsbDevice(usbDevice: UsbDevice): SecurityKey? {
        adoptedDevices += usbDevice
        return adopted
    }

    /** A key is plugged in while the discovery runs. */
    fun plug(key: SecurityKey) {
        usbEvents!!.attached(key)
    }

    /** Android refuses a key to NextSH while the discovery runs. */
    fun deny(id: String) {
        usbEvents!!.denied(id)
    }

    fun unplug(id: String) {
        usbEvents!!.detached(id)
    }

    /** An NFC tag is tapped; does nothing when the reader is not running, like the real one. */
    fun tap(key: SecurityKey) {
        nfcTap?.invoke(key)
    }
}

/**
 * Claims the key and gives it back at once: the key that answered. For tests that only care which
 * key a request is given, not for how long it keeps it.
 */
internal suspend fun SecurityKeyDiscovery.firstKey(use: KeyUse = KeyUse.SIGNATURE): SecurityKey =
    claimKey(use).use { it.key }

/**
 * Checks that [key] is free again, the way the next request would find out: it claims it and must
 * get it at once. A request that ended without closing its claim makes this wait, and time out.
 * Call it with a lease held by the test, so that the discovery still knows the key.
 */
internal suspend fun SecurityKeyDiscovery.assertKeyFree(key: SecurityKey) {
    val claim = withTimeout(5_000) { claimKey(KeyUse.SIGNATURE) }
    try {
        assertSame(key, claim.key)
    } finally {
        claim.close()
    }
}
