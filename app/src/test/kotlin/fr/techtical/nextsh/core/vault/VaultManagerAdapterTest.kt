// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import fr.techtical.nextsh.shared.core.sync.EnrolledDeviceSecretStore
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException as SharedVaultAuthExpiredException

/**
 * The adapter is where the Android vault meets the shared interface: the exception of an expired
 * authentication window must come out under the type that shared callers can catch, and the typed
 * deletions of the shared contract must reach exactly one slot of the Android vault (a password, a
 * private key, a certificate and a passphrase can sit under the same raw id, and none of the others
 * may be touched).
 */
class VaultManagerAdapterTest {

    private val delegate = mockk<VaultManager>()
    private val adapter = VaultManagerAdapter(delegate)
    private val expired = VaultAuthExpiredException(IllegalStateException("keystore"))

    /**
     * Makes the delegate throw [expired] from the call set up by [stub], runs [call] on the
     * adapter, and checks that the shared exception comes out, with [expired] as its cause.
     */
    private suspend fun assertTranslated(label: String, stub: () -> Unit, call: suspend () -> Unit) {
        clearMocks(delegate)
        stub()
        val thrown = try {
            call()
            null
        } catch (e: Throwable) {
            e
        }
        val shared = thrown as? SharedVaultAuthExpiredException
        assertNotNull(shared, "$label: the shared exception expected, got $thrown")
        assertSame(expired, shared!!.cause, "$label: the original exception is the cause")
        assertEquals(expired.message, shared.message, "$label: the fixed message is kept")
    }

    @Test
    fun `every call turns the expired authentication of the app vault into the shared exception`() = runTest {
        val pwd = "pwd".toCharArray()
        assertTranslated("storePassword", { every { delegate.storePassword(any(), any()) } throws expired }) {
            adapter.storePassword("c", pwd)
        }
        assertTranslated("getPassword", { every { delegate.getPassword(any()) } throws expired }) {
            adapter.getPassword("c")
        }
        assertTranslated("storePrivateKey", { every { delegate.storePrivateKey(any(), any()) } throws expired }) {
            adapter.storePrivateKey("k", "pem")
        }
        assertTranslated("getPrivateKey", { every { delegate.getPrivateKey(any()) } throws expired }) {
            adapter.getPrivateKey("k")
        }
        assertTranslated("storeCertificate", { every { delegate.storeCertificate(any(), any()) } throws expired }) {
            adapter.storeCertificate("c", "pem")
        }
        assertTranslated("getCertificate", { every { delegate.getCertificate(any()) } throws expired }) {
            adapter.getCertificate("c")
        }
        assertTranslated("deletePassword", { every { delegate.deletePassword(any()) } throws expired }) {
            adapter.deletePassword("c")
        }
        assertTranslated("deletePrivateKey", { every { delegate.deletePrivateKey(any()) } throws expired }) {
            adapter.deletePrivateKey("k")
        }
        assertTranslated("deleteCertificate", { every { delegate.deleteCertificate(any()) } throws expired }) {
            adapter.deleteCertificate("c")
        }
        assertTranslated("storeKeyPassphrase", { every { delegate.storeKeyPassphrase(any(), any()) } throws expired }) {
            adapter.storeKeyPassphrase("k", pwd)
        }
        assertTranslated("getKeyPassphrase", { every { delegate.getKeyPassphrase(any()) } throws expired }) {
            adapter.getKeyPassphrase("k")
        }
        assertTranslated("deleteKeyPassphrase", { every { delegate.deleteKeyPassphrase(any()) } throws expired }) {
            adapter.deleteKeyPassphrase("k")
        }
        assertTranslated("listStoredKeyPassphraseIds", { every { delegate.listStoredKeyPassphraseIds() } throws expired }) {
            adapter.listStoredKeyPassphraseIds()
        }
        assertTranslated("wipeVault", { every { delegate.wipeVault() } throws expired }) {
            adapter.wipeVault()
        }
        assertTranslated("isInitialized", { every { delegate.isInitialized() } throws expired }) {
            adapter.isInitialized()
        }
        assertTranslated("listStoredCredentialIds", { every { delegate.listStoredCredentialIds() } throws expired }) {
            adapter.listStoredCredentialIds()
        }
        assertTranslated("listStoredKeyIds", { every { delegate.listStoredKeyIds() } throws expired }) {
            adapter.listStoredKeyIds()
        }
        assertTranslated("listStoredCertificateIds", { every { delegate.listStoredCertificateIds() } throws expired }) {
            adapter.listStoredCertificateIds()
        }
    }

    @Test
    fun `the sync secret store of the shared code sees the shared exception when the vault is locked`() = runTest {
        // EnrollmentScanViewModel stores the secret of a new device through this chain and shows
        // "Vault verrouillé" on exactly this type; LanDiscoveryClient and LanSyncClient read it
        // through the same chain. The view model itself builds its HTTP client and parses the QR
        // with android.net.Uri, so it cannot run here: the type reaching it is what is pinned.
        val store = EnrolledDeviceSecretStore(adapter)
        every { delegate.storePassword(any(), any()) } throws expired
        every { delegate.getPassword(any()) } throws expired

        val onStore = try {
            store.store("device-1", ByteArray(32) { 1 })
            null
        } catch (e: Throwable) {
            e
        }
        val onRetrieve = try {
            store.retrieve("device-1")
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue(onStore is SharedVaultAuthExpiredException, "store: $onStore")
        assertTrue(onRetrieve is SharedVaultAuthExpiredException, "retrieve: $onRetrieve")
    }

    @Test
    fun `an exception that is not the expired authentication goes through unchanged`() = runTest {
        val boom = IllegalStateException("not the window")
        every { delegate.getPrivateKey(any()) } throws boom

        val thrown = try {
            adapter.getPrivateKey("k")
            null
        } catch (e: Throwable) {
            e
        }

        assertSame(boom, thrown)
    }

    @Test
    fun `results and arguments go through when the vault answers`() = runTest {
        val pwd = "secret".toCharArray()
        every { delegate.getPassword("c") } returns pwd
        every { delegate.getPrivateKey("k") } returns "pem"
        every { delegate.listStoredKeyIds() } returns listOf("k1", "k2")
        every { delegate.isInitialized() } returns true
        every { delegate.storeCertificate("c", "cert") } returns Unit

        assertSame(pwd, adapter.getPassword("c"))
        assertEquals("pem", adapter.getPrivateKey("k"))
        assertEquals(listOf("k1", "k2"), adapter.listStoredKeyIds())
        assertEquals(true, adapter.isInitialized())
        adapter.storeCertificate("c", "cert")
        verify(exactly = 1) { delegate.storeCertificate("c", "cert") }
    }

    @Test
    fun `deletePassword delegates to deletePassword only`() = runTest {
        clearMocks(delegate)
        every { delegate.deletePassword("X") } returns Unit

        adapter.deletePassword("X")

        verify(exactly = 1) { delegate.deletePassword("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deletePrivateKey delegates to deletePrivateKey only`() = runTest {
        clearMocks(delegate)
        every { delegate.deletePrivateKey("X") } returns Unit

        adapter.deletePrivateKey("X")

        verify(exactly = 1) { delegate.deletePrivateKey("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deleteCertificate delegates to deleteCertificate only`() = runTest {
        clearMocks(delegate)
        every { delegate.deleteCertificate("X") } returns Unit

        adapter.deleteCertificate("X")

        verify(exactly = 1) { delegate.deleteCertificate("X") }
        confirmVerified(delegate)
    }

    @Test
    fun `deleteKeyPassphrase delegates to deleteKeyPassphrase only`() = runTest {
        clearMocks(delegate)
        every { delegate.deleteKeyPassphrase("X") } returns Unit

        adapter.deleteKeyPassphrase("X")

        verify(exactly = 1) { delegate.deleteKeyPassphrase("X") }
        confirmVerified(delegate)
    }
}
