// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.auth

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yubico.yubikit.android.YubiKitManager
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable
import com.yubico.yubikit.android.transport.usb.UsbConfiguration
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice
import timber.log.Timber

/**
 * [SecurityKeyBackend] on top of YubiKit 2.4.0.
 *
 * One [YubiKitManager], built on the application context: it keeps no Activity. The Activity of
 * the NFC reader is handed in by [SecurityKeyDiscovery] and given back when the reader stops.
 *
 * USB permission is not asked for by YubiKit (`handlePermissions(false)`), as the enrollment
 * has always done: the system dialog of the manifest filter already asks on attach, and a second
 * request from YubiKit next to it gave two dialogs for one key. The invisible trampoline hands
 * over the device that dialog allowed. Without `handlePermissions`, YubiKit reports a key as soon
 * as it is plugged in, whether or not Android let NextSH use it. A key reported without the
 * permission is not used, but it is reported as denied so that the prompt can say what to do. That
 * is the normal state while the dialog is on screen (the user has to accept it), and it stays so
 * when the dialog was refused or missed: unplugging the key and plugging it back brings it again.
 *
 * YubiKit 2.4.0 has no unplugging callback in its discovery: it only closes its own device object
 * (`UsbYubiKeyDevice.setOnClosed` would tell, on a worker thread, and only for the devices YubiKit
 * built). The ACTION_USB_DEVICE_DETACHED broadcast is listened to directly instead, which also
 * covers the devices the trampoline built itself, that YubiKit does not know.
 */
internal class YubiKitBackend(context: Context) : SecurityKeyBackend {

    private val appContext: Context = context.applicationContext
    private val manager: YubiKitManager by lazy { YubiKitManager(appContext) }

    private var detachReceiver: BroadcastReceiver? = null

    override fun startUsb(events: UsbEvents) {
        registerDetachReceiver(events)
        manager.startUsbDiscovery(UsbConfiguration().handlePermissions(false)) { device ->
            if (!device.hasPermission()) {
                // Reported, so that the prompt can say what to do: the system dialog may be on screen
                // right now. The device name only names the attachment: it is not logged.
                Timber.d("SecurityKeyDiscovery: USB key found without permission, left aside")
                events.denied(device.usbDevice.deviceName)
                return@startUsbDiscovery
            }
            events.attached(usbKey(device))
        }
    }

    override fun stopUsb() {
        manager.stopUsbDiscovery()
        unregisterDetachReceiver()
    }

    override fun startNfc(activity: Activity, onTap: (SecurityKey) -> Unit): NfcStart = try {
        manager.startNfcDiscovery(
            // A PIN assertion takes three CTAP2 round trips over NFC, 200 to 500 ms each. The
            // default timeout of 1 s loses the tag in the middle of the sequence.
            NfcConfiguration().timeout(NFC_TIMEOUT_MS),
            activity,
        ) { device ->
            Timber.d("SecurityKeyDiscovery: NFC tag tapped")
            onTap(SecurityKey(KeyTransport.NFC, id = null, device = device))
        }
        NfcStart.STARTED
    } catch (e: NfcNotAvailable) {
        // YubiKit tells a phone without NFC from one that has it switched off: only the second is
        // something the user can fix.
        if (e.isDisabled) {
            Timber.d("SecurityKeyDiscovery: NFC is switched off")
            NfcStart.DISABLED
        } else {
            Timber.d("SecurityKeyDiscovery: NFC is not available on this phone")
            NfcStart.ABSENT
        }
    }

    override fun stopNfc(activity: Activity) {
        manager.stopNfcDiscovery(activity)
    }

    override fun adoptUsbDevice(usbDevice: UsbDevice): SecurityKey? {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = try {
            UsbYubiKeyDevice(usbManager, usbDevice)
        } catch (e: IllegalArgumentException) {
            Timber.d("SecurityKeyDiscovery: the USB device is not a YubiKey, ignored")
            return null
        }
        if (!device.hasPermission()) {
            Timber.d("SecurityKeyDiscovery: USB key delivered without permission, ignored")
            return null
        }
        return usbKey(device)
    }

    private fun usbKey(device: UsbYubiKeyDevice) =
        SecurityKey(KeyTransport.USB, id = device.usbDevice.deviceName, device = device)

    private fun registerDetachReceiver(events: UsbEvents) {
        unregisterDetachReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val usbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                usbDevice?.let { events.detached(it.deviceName) }
            }
        }
        // A system broadcast: not exported is enough to receive it.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        detachReceiver = receiver
    }

    private fun unregisterDetachReceiver() {
        val receiver = detachReceiver ?: return
        detachReceiver = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Already gone: nothing left to release.
        }
    }

    private companion object {
        const val NFC_TIMEOUT_MS = 10_000
    }
}
