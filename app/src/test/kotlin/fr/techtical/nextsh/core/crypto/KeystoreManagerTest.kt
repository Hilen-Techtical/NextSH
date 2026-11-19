// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import fr.techtical.nextsh.testutil.RecordingTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import timber.log.Timber
import java.security.KeyStoreException

/**
 * `deleteBiometricKey` receives an alias read from the database, which can come from a backup or
 * a sync payload. It must delete nothing unless the alias has exactly the shape of the aliases the
 * app generates, so that a crafted payload can never make the app delete the vault master key.
 *
 * These tests run on a plain JVM, where the "AndroidKeyStore" provider does not exist: any code
 * path that reaches the Keystore throws [KeyStoreException]. A refused alias must therefore
 * return without throwing, which proves it never reached the Keystore.
 */
class KeystoreManagerTest {

    /** android.util.Log.WARN, inlined: the android.jar stubs are not usable in a plain JVM test. */
    private val WARN = 5

    private val manager = KeystoreManager()
    private val validAlias = BiometricKeyAlias.forKeyId("0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")

    private val refused = listOf(
        "nextsh_vault_auth_key",
        "",
        "nextsh_bio_",
        "nextsh_bio_not-a-uuid",
        "nextsh_bio_../nextsh_vault_auth_key",
        "nextsh_bio_0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\nnextsh_vault_auth_key",
        "nextsh_bio_0b1f2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d_extra",
        "NEXTSH_BIO_0B1F2C3D-4E5F-4A6B-8C7D-9E0F1A2B3C4D",
        "some_other_alias",
    )

    @Test
    fun `precondition - on this JVM a valid alias reaches the Keystore and fails there`() {
        // If this stops failing (an AndroidKeyStore stand-in was added to the test classpath),
        // the refusal tests below no longer prove anything and must be rewritten with a fake.
        assertThrows(KeyStoreException::class.java) { manager.deleteBiometricKey(validAlias) }
    }

    @Test
    fun `an alias that is not a biometric key alias is refused without touching the Keystore`() {
        for (alias in refused) {
            // Would throw KeyStoreException if the Keystore were reached.
            manager.deleteBiometricKey(alias)
        }
    }

    @Test
    fun `the vault master key alias is refused`() {
        manager.deleteBiometricKey("nextsh_vault_auth_key")
    }

    @Test
    fun `a refusal is logged as a warning without the alias`() {
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            for (alias in refused.filter { it.isNotEmpty() }) {
                manager.deleteBiometricKey(alias)
            }
        } finally {
            Timber.uproot(tree)
        }

        val warnings = tree.entries.filter { it.priority == WARN }
        assertEquals(refused.count { it.isNotEmpty() }, warnings.size)
        for (entry in warnings) {
            assertNull(entry.throwable)
            assertTrue(entry.message.contains("refused"), entry.message)
            // The alias is untrusted input: neither it nor any part of it goes to the log.
            assertFalse(refused.any { it.isNotEmpty() && entry.message.contains(it) }, entry.message)
            assertFalse(entry.message.contains("nextsh_vault_auth_key"), entry.message)
        }
    }

    // ── Reading a key: same format guard as the deletion ─────────────────────

    @Test
    fun `getBiometricPrivateKey refuses an alias that is not a biometric key alias`() {
        for (alias in refused) {
            val e = assertThrows(KeyStoreException::class.java, { manager.getBiometricPrivateKey(alias) }, "[$alias]")

            // The guard's own fixed text, which proves the Keystore was not reached (there the
            // failure on this JVM is a missing provider), and never the untrusted alias.
            assertEquals(NOT_A_BIOMETRIC_ALIAS_MESSAGE, e.message, "[$alias]")
            assertNull(e.cause)
        }
    }

    @Test
    fun `getSignatureForBiometricKey refuses an alias that is not a biometric key alias`() {
        for (alias in refused) {
            val e = assertThrows(KeyStoreException::class.java, { manager.getSignatureForBiometricKey(alias) }, "[$alias]")

            assertEquals(NOT_A_BIOMETRIC_ALIAS_MESSAGE, e.message, "[$alias]")
            assertNull(e.cause)
        }
    }

    @Test
    fun `getBiometricPublicKey refuses an alias that is not a biometric key alias`() {
        for (alias in refused) {
            val e = assertThrows(KeyStoreException::class.java, { manager.getBiometricPublicKey(alias) }, "[$alias]")

            assertEquals(NOT_A_BIOMETRIC_ALIAS_MESSAGE, e.message, "[$alias]")
            assertNull(e.cause)
        }
    }

    @Test
    fun `a valid alias passes the guard and reaches the Keystore`() {
        // On this JVM the Keystore does not exist, so it fails there, with another message than
        // the guard's: the guard does not get in the way of the aliases the app generates.
        val privateKey = assertThrows(KeyStoreException::class.java) { manager.getBiometricPrivateKey(validAlias) }
        val publicKey = assertThrows(KeyStoreException::class.java) { manager.getBiometricPublicKey(validAlias) }
        val signature = assertThrows(KeyStoreException::class.java) { manager.getSignatureForBiometricKey(validAlias) }

        assertFalse(privateKey.message == NOT_A_BIOMETRIC_ALIAS_MESSAGE, privateKey.message)
        assertFalse(publicKey.message == NOT_A_BIOMETRIC_ALIAS_MESSAGE, publicKey.message)
        assertFalse(signature.message == NOT_A_BIOMETRIC_ALIAS_MESSAGE, signature.message)
    }

    @Test
    fun `the failure texts never quote the alias`() {
        // The alias comes from the database, so from a backup or a sync: it is not echoed.
        for (alias in refused.filter { it.isNotEmpty() }) {
            for (read in listOf<(String) -> Unit>(
                { manager.getBiometricPublicKey(it) },
                { manager.getBiometricPrivateKey(it) },
                { manager.getSignatureForBiometricKey(it) },
            )) {
                val e = assertThrows(KeyStoreException::class.java) { read(alias) }
                assertFalse(e.message.orEmpty().contains(alias), "[$alias]: ${e.message}")
            }
        }
    }
}
