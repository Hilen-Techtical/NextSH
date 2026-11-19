// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.vault

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The invisible trampoline is exported: whoever sends it an intent chooses its extras. Reading them
 * must never throw, or NextSH (and the tunnels it runs) dies with the activity.
 *
 * Unit tests run with the SDK level of the stub android.jar (0), which is the branch of the
 * deprecated getParcelableExtra: the one that throws on a wrong type.
 */
class Fido2UsbHandlerIntentTest {

    private fun attachIntent(read: () -> UsbDevice?): Intent {
        val intent = mockk<Intent>()
        every { intent.action } returns UsbManager.ACTION_USB_DEVICE_ATTACHED
        @Suppress("DEPRECATION")
        every { intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) } answers { read() }
        return intent
    }

    @Test
    fun `the device of an attach intent is returned`() {
        val device = mockk<UsbDevice>()

        assertSame(device, usbDeviceOfAttachIntent(attachIntent { device }))
    }

    @Test
    fun `an extra of another type is no device and does not throw`() {
        // The cast of the deprecated getParcelableExtra, at the call site, before API 33.
        val intent = attachIntent { throw ClassCastException("Bundle cannot be cast to UsbDevice") }

        assertNull(usbDeviceOfAttachIntent(intent))
    }

    @Test
    fun `a malformed parcel is no device and does not throw`() {
        // BadParcelableException, on every version, is a RuntimeException.
        val intent = attachIntent { throw RuntimeException("ClassNotFoundException when unmarshalling") }

        assertNull(usbDeviceOfAttachIntent(intent))
    }

    @Test
    fun `an intent without a device gives none`() {
        assertNull(usbDeviceOfAttachIntent(attachIntent { null }))
    }

    @Test
    fun `any other action is ignored without reading the extras`() {
        val intent = mockk<Intent>()
        every { intent.action } returns Intent.ACTION_VIEW

        assertNull(usbDeviceOfAttachIntent(intent))
    }

    @Test
    fun `no intent gives no device`() {
        assertNull(usbDeviceOfAttachIntent(null))
    }
}
