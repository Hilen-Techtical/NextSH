// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.core.auth.SecurityKeyDiscovery
import timber.log.Timber
import javax.inject.Inject

/**
 * Activity invisible (theme NoDisplay) déclarée dans le manifest avec un
 * intent filter USB_DEVICE_ATTACHED. Son rôle : capturer l'attach d'une
 * YubiKey sans lancer MainActivity en cold-start (qui monopoliserait la
 * clé au détriment d'autres apps comme Bitwarden ou un navigateur).
 *
 * Quand quelqu'un cherche une clé (un bail est pris sur [SecurityKeyDiscovery] : demande de
 * signature FIDO2 ou écran d'enrôlement), le device est transmis à la découverte. L'intent porte
 * la permission d'utiliser la clé, accordée par le dialogue système du filtre manifest.
 * Sinon, l'attach est ignoré silencieusement : NextSH ne s'ouvre pas.
 *
 * Ne fait JAMAIS setContent : l'activity finit immédiatement.
 *
 * Exportée (le système doit pouvoir la lancer) : n'importe quelle app peut donc lui envoyer
 * n'importe quel intent, extras compris. Rien de ce qu'elle lit ne doit la faire planter, car NextSH
 * mourrait avec elle (les tunnels tournent dans le même processus). Elle finit toujours.
 */
@AndroidEntryPoint
class Fido2UsbHandlerActivity : ComponentActivity() {

    @Inject lateinit var securityKeyDiscovery: SecurityKeyDiscovery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            usbDeviceOfAttachIntent(intent)?.let(securityKeyDiscovery::onUsbAttachIntent)
        } catch (e: Exception) {
            // Fixed text: nothing of the intent (device name, extras) goes to the log.
            Timber.w("Fido2UsbHandlerActivity: the USB attach could not be handled (%s)", e.javaClass.simpleName)
        } finally {
            finish()  // jamais setContentView, jamais visible
        }
    }
}

/**
 * The USB device of a USB_DEVICE_ATTACHED [intent], or null when it is not that, carries no device,
 * or carries something that is not a device.
 *
 * The extra is read with care because the activity is exported and the extra comes from whoever
 * sent the intent. Before API 33 the cast of the deprecated getParcelableExtra happens at the call
 * site and throws ClassCastException when the extra is of another type; a malformed parcel makes it
 * throw BadParcelableException, on every version. Both are RuntimeExceptions, and both end as null.
 */
internal fun usbDeviceOfAttachIntent(intent: Intent?): UsbDevice? {
    if (intent == null || intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        }
    } catch (e: RuntimeException) {
        Timber.w("Fido2UsbHandlerActivity: the device extra is unreadable (%s)", e.javaClass.simpleName)
        null
    }
}
