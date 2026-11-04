// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.content.Context
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
import androidx.biometric.BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED
import androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
import fr.techtical.nextsh.core.crypto.BiometricHelper.BiometricCapability
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BiometricHelperTest {

    private val strongOrCredential = BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    private val weakOrCredential = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * Appareil simulé qui répond comme `BiometricManager.canAuthenticate` d'androidx.biometric
     * 1.2.0-alpha05 : `STRONG or DEVICE_CREDENTIAL` est non pris en charge sur les API 28 et 29,
     * `DEVICE_CREDENTIAL` seul avant l'API 30, `WEAK or DEVICE_CREDENTIAL` partout. Dès que
     * `DEVICE_CREDENTIAL` fait partie de la demande, seule la présence d'un code compte : la
     * biométrie n'est pas regardée, donc l'appareil n'a pas besoin de la modéliser.
     */
    private class FakeDevice(
        private val sdkInt: Int,
        private val hasCode: Boolean,
        private val resultWithoutCode: Int = BIOMETRIC_ERROR_NONE_ENROLLED,
    ) {
        val requested = mutableListOf<Int>()

        fun canAuthenticate(authenticators: Int): Int {
            requested += authenticators
            if (!isSupportedCombination(authenticators)) return BIOMETRIC_ERROR_UNSUPPORTED
            val codeAllowed = (authenticators and DEVICE_CREDENTIAL) != 0
            return if (codeAllowed && hasCode) BIOMETRIC_SUCCESS else resultWithoutCode
        }

        private fun isSupportedCombination(authenticators: Int): Boolean = when (authenticators) {
            0, BIOMETRIC_STRONG, BIOMETRIC_WEAK, BIOMETRIC_WEAK or DEVICE_CREDENTIAL -> true
            DEVICE_CREDENTIAL -> sdkInt >= 30
            BIOMETRIC_STRONG or DEVICE_CREDENTIAL -> sdkInt !in 28..29
            else -> false
        }
    }

    private fun helperFor(device: FakeDevice, sdkInt: Int) = BiometricHelper(
        context = mockk<Context>(),
        sdkInt = sdkInt,
        canAuthenticateWith = device::canAuthenticate,
    )

    // vaultAuthenticators

    @Test
    fun `vaultAuthenticators on API 29 is weak biometric or device credential`() {
        assertEquals(weakOrCredential, vaultAuthenticators(29))
    }

    @Test
    fun `vaultAuthenticators on API 30 is strong biometric or device credential`() {
        assertEquals(strongOrCredential, vaultAuthenticators(30))
    }

    @Test
    fun `vaultAuthenticators on API 36 is strong biometric or device credential`() {
        assertEquals(strongOrCredential, vaultAuthenticators(36))
    }

    @Test
    fun `vaultAuthenticators never asks for a combination androidx rejects on API 29`() {
        // BIOMETRIC_STRONG or DEVICE_CREDENTIAL et DEVICE_CREDENTIAL seul sont non pris en charge ici.
        assertNotEquals(strongOrCredential, vaultAuthenticators(29))
        assertNotEquals(DEVICE_CREDENTIAL, vaultAuthenticators(29))
    }

    // checkCapability sur l'API 29

    @Test
    fun `API 29 with only a screen lock is available and never UNAVAILABLE`() {
        val device = FakeDevice(sdkInt = 29, hasCode = true)
        val helper = helperFor(device, sdkInt = 29)

        val capability = helper.checkCapability()

        assertEquals(BiometricCapability.AVAILABLE, capability)
        assertNotEquals(BiometricCapability.UNAVAILABLE, capability)
        assertTrue(helper.canAuthenticate())
    }

    @Test
    fun `API 29 only asks for weak biometric or device credential`() {
        val device = FakeDevice(sdkInt = 29, hasCode = true)

        helperFor(device, sdkInt = 29).checkCapability()

        assertEquals(listOf(weakOrCredential), device.requested)
    }

    @Test
    fun `API 29 does not retry device credential alone when nothing is enrolled`() {
        val device = FakeDevice(sdkInt = 29, hasCode = false)
        val helper = helperFor(device, sdkInt = 29)

        assertEquals(BiometricCapability.NOT_ENROLLED, helper.checkCapability())
        assertEquals(listOf(weakOrCredential), device.requested)
        assertFalse(helper.canAuthenticate())
    }

    @Test
    fun `API 29 reports no hardware when androidx says so`() {
        val device = FakeDevice(sdkInt = 29, hasCode = false, resultWithoutCode = BIOMETRIC_ERROR_NO_HARDWARE)

        assertEquals(BiometricCapability.NO_HARDWARE, helperFor(device, sdkInt = 29).checkCapability())
    }

    @Test
    fun `API 29 maps any other androidx error to UNAVAILABLE`() {
        val device = FakeDevice(sdkInt = 29, hasCode = false, resultWithoutCode = BIOMETRIC_ERROR_HW_UNAVAILABLE)

        assertEquals(BiometricCapability.UNAVAILABLE, helperFor(device, sdkInt = 29).checkCapability())
    }

    // checkCapability sur l'API 30 et plus : comportement inchangé

    @Test
    fun `API 30 and above with a screen lock is available with a single check`() {
        for (sdkInt in listOf(30, 33, 36)) {
            val device = FakeDevice(sdkInt = sdkInt, hasCode = true)
            val helper = helperFor(device, sdkInt = sdkInt)

            assertEquals(BiometricCapability.AVAILABLE, helper.checkCapability(), "API $sdkInt")
            assertEquals(listOf(strongOrCredential), device.requested, "API $sdkInt")
            assertTrue(helper.canAuthenticate(), "API $sdkInt")
        }
    }

    @Test
    fun `API 30 and above falls back to device credential alone when the combination fails`() {
        for (sdkInt in listOf(30, 36)) {
            // Combinaison refusée, code seul accepté : le repli reste tenté à partir de l'API 30.
            val requested = mutableListOf<Int>()
            val helper = BiometricHelper(
                context = mockk<Context>(),
                sdkInt = sdkInt,
                canAuthenticateWith = { authenticators ->
                    requested += authenticators
                    if (authenticators == DEVICE_CREDENTIAL) BIOMETRIC_SUCCESS else BIOMETRIC_ERROR_NONE_ENROLLED
                },
            )

            assertEquals(BiometricCapability.DEVICE_CREDENTIAL_ONLY, helper.checkCapability(), "API $sdkInt")
            assertEquals(listOf(strongOrCredential, DEVICE_CREDENTIAL), requested, "API $sdkInt")
            assertTrue(helper.canAuthenticate(), "API $sdkInt")
        }
    }

    @Test
    fun `API 30 and above maps errors from the combined check as before`() {
        for (sdkInt in listOf(30, 36)) {
            val cases = mapOf(
                BIOMETRIC_ERROR_NO_HARDWARE to BiometricCapability.NO_HARDWARE,
                BIOMETRIC_ERROR_NONE_ENROLLED to BiometricCapability.NOT_ENROLLED,
                BIOMETRIC_ERROR_HW_UNAVAILABLE to BiometricCapability.UNAVAILABLE,
            )
            for ((error, expected) in cases) {
                val device = FakeDevice(sdkInt = sdkInt, hasCode = false, resultWithoutCode = error)

                assertEquals(expected, helperFor(device, sdkInt = sdkInt).checkCapability(), "API $sdkInt, erreur $error")
            }
        }
    }
}
