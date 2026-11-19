// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import android.content.Context
import androidx.biometric.BiometricManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A biometric SSH key is created in the Keystore with a user authentication requirement that only
 * a strong biometric satisfies: the device PIN must not make the check pass, or the phone would
 * be declared ready and the Keystore would still refuse the key.
 */
class StrongBiometricStatusTest {

    private val context = mockk<Context>()
    private val manager = mockk<BiometricManager>()

    @BeforeEach
    fun setUp() {
        mockkStatic(BiometricManager::class)
        every { BiometricManager.from(context) } returns manager
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(BiometricManager::class)
    }

    @Test
    fun `the result of the check maps to one status each`() {
        assertEquals(StrongBiometricStatus.AVAILABLE, strongBiometricStatusFor(BiometricManager.BIOMETRIC_SUCCESS))
        assertEquals(StrongBiometricStatus.NO_HARDWARE, strongBiometricStatusFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE))
        assertEquals(StrongBiometricStatus.NOT_ENROLLED, strongBiometricStatusFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED))
    }

    @Test
    fun `every other result is a temporary unavailability`() {
        listOf(
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            12345,
        ).forEach { code ->
            assertEquals(StrongBiometricStatus.UNAVAILABLE, strongBiometricStatusFor(code), "code $code")
        }
    }

    @Test
    fun `the helper asks for a strong biometric alone, never with the device credential`() {
        // Only this exact request is stubbed: one that adds DEVICE_CREDENTIAL has no answer and throws.
        every { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns
            BiometricManager.BIOMETRIC_SUCCESS

        assertEquals(StrongBiometricStatus.AVAILABLE, BiometricHelper(context).checkStrongBiometric())
    }

    @Test
    fun `a phone with only a PIN is not ready for a biometric key`() {
        // With a PIN and no fingerprint, the combined request would succeed; the strong one does not.
        every { manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) } returns
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

        assertEquals(StrongBiometricStatus.NOT_ENROLLED, BiometricHelper(context).checkStrongBiometric())
    }
}
