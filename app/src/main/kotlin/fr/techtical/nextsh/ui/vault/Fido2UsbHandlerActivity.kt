// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import fr.techtical.nextsh.core.auth.Fido2EnrollManager
import javax.inject.Inject

/**
 * Activity invisible (theme NoDisplay) déclarée dans le manifest avec un
 * intent filter USB_DEVICE_ATTACHED. Son rôle : capturer l'attach d'une
 * YubiKey sans lancer MainActivity en cold-start (qui monopoliserait la
 * clé au détriment d'autres apps comme Bitwarden ou un navigateur).
 *
 * Si la MainActivity est déjà ouverte (Fido2EnrollManager a un listener
 * actif via yubiKitManager != null), le device est forwardé.
 * Sinon, l'attach est ignoré silencieusement : NextSH ne s'ouvre pas.
 *
 * Ne fait JAMAIS setContent : l'activity finit immédiatement.
 */
@AndroidEntryPoint
class Fido2UsbHandlerActivity : ComponentActivity() {

    @Inject lateinit var fido2EnrollManager: Fido2EnrollManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            if (device != null) {
                fido2EnrollManager.handleUsbAttachIntent(applicationContext, device)
            }
        }

        finish()  // jamais setContentView, jamais visible
    }
}
