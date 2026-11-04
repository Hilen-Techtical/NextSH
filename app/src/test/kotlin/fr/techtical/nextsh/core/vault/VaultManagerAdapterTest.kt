// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The typed deletions of the shared contract must reach exactly one slot of the
 * Android vault: a password, a private key, a certificate and a passphrase can sit
 * under the same raw id, and none of the others may be touched.
 */
class VaultManagerAdapterTest {

    private val delegate = mockk<VaultManager>(relaxed = true)
    private val adapter = VaultManagerAdapter(delegate)

    @Test
    fun `deletePassword delegates to deletePassword only`() = runTest {
        adapter.deletePassword("X")

        verify(exactly = 1) { delegate.deletePassword("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deletePrivateKey delegates to deletePrivateKey only`() = runTest {
        adapter.deletePrivateKey("X")

        verify(exactly = 1) { delegate.deletePrivateKey("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deleteCertificate delegates to deleteCertificate only`() = runTest {
        adapter.deleteCertificate("X")

        verify(exactly = 1) { delegate.deleteCertificate("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deleteKeyPassphrase delegates to deleteKeyPassphrase only`() = runTest {
        adapter.deleteKeyPassphrase("X")

        verify(exactly = 1) { delegate.deleteKeyPassphrase("X") }
        confirmVerified(delegate)
    }
}
