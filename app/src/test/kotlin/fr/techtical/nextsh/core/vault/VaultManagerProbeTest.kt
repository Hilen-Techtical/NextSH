// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.vault

import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import java.security.GeneralSecurityException
import java.security.Key
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Sonde de la clé maître et traduction en [VaultAuthExpiredException].
 *
 * Contexte : avec Tink 1.8, une fenêtre d'authentification expirée à la première création
 * d'EncryptedSharedPreferences ne remontait jamais (Tink avalait l'exception et poursuivait, par
 * exemple en relisant le jeu de clés chiffré comme du clair). La sonde ([MasterKeyProbe]) la fait
 * remonter avant Tink.
 *
 * Le Keystore Android n'existe pas sur la JVM : le gestionnaire est monté avec une sonde et un
 * ouvreur factices, et [KeystoreMasterKeyProbe] avec des étapes injectées. Le vrai comportement du
 * Keystore (fenêtre réelle, StrongBox) ne se vérifie que sur appareil.
 */
class VaultManagerProbeTest {

    /** Journal commun aux factices, pour vérifier l'ordre exact des étapes. */
    private val calls = mutableListOf<String>()

    private val prefs = mockk<SharedPreferences>(relaxed = true)

    private val validKey = SecretKeySpec(ByteArray(32), "AES")

    /** Panne que la sonde factice lève ; null = fenêtre ouverte. */
    private var probeFailure: Throwable? = null

    /** Panne que l'ouvreur factice lève à la création des préférences ; null = succès. */
    private var openFailure: Throwable? = null

    /** Nombre de pauses avant un nouvel essai demandées à la sonde réelle (aucune attente réelle). */
    private var pauses = 0

    private val probe = MasterKeyProbe {
        calls += "probe"
        probeFailure?.let { throw it }
    }

    private val opener = object : VaultPrefsOpener {
        override fun ensureMasterKey() {
            calls += "ensureMasterKey"
        }

        override fun open(): SharedPreferences {
            calls += "open"
            openFailure?.let { throw it }
            return prefs
        }
    }

    private fun newManager(probe: MasterKeyProbe = this.probe) = VaultManager(probe, opener)

    /** Causes reçues par la fabrique d'erreur d'authentification (le `android.jar` JVM perd la cause). */
    private val authRequiredCauses = mutableListOf<Throwable>()

    private fun newProbe(
        loadKey: (String) -> Key? = { validKey },
        dryEncrypt: (Key) -> Unit = {},
    ) = KeystoreMasterKeyProbe(
        loadKey = loadKey,
        dryEncrypt = dryEncrypt,
        pauseBeforeRetry = { pauses++ },
        authRequired = { cause ->
            authRequiredCauses += cause
            UserNotAuthenticatedException(AUTH_REQUIRED_MESSAGE, cause)
        },
    )

    private fun expiredWindow() = UserNotAuthenticatedException("window expired")

    private fun op(name: String, block: (VaultManager) -> Unit): Pair<String, (VaultManager) -> Unit> = name to block

    // ── Fenêtre expirée ─────────────────────────────────────────────────────────

    @Test
    fun `expired window at first opening raises VaultAuthExpiredException and prefs are never created`() {
        val expired = expiredWindow()
        probeFailure = expired

        val thrown = assertThrows(VaultAuthExpiredException::class.java) {
            newManager().getPassword("cred-1")
        }

        assertSame(expired, thrown.cause, "la cause doit rester l'exception du Keystore")
        assertEquals(listOf("ensureMasterKey", "probe"), calls)
    }

    @Test
    fun `master key is ensured before it is probed`() {
        newManager().isInitialized()

        assertEquals(listOf("ensureMasterKey", "probe", "open"), calls)
    }

    @Test
    fun `every vault operation goes through the probe and maps an expired window`() {
        probeFailure = expiredWindow()
        val secret = { charArrayOf('x') }
        val operations = listOf(
            op("storePassword") { it.storePassword("id", secret()) },
            op("getPassword") { it.getPassword("id") },
            op("deletePassword") { it.deletePassword("id") },
            op("storePrivateKey") { it.storePrivateKey("id", "pem") },
            op("getPrivateKey") { it.getPrivateKey("id") },
            op("deletePrivateKey") { it.deletePrivateKey("id") },
            op("storeKeyPassphrase") { it.storeKeyPassphrase("id", secret()) },
            op("getKeyPassphrase") { it.getKeyPassphrase("id") },
            op("deleteKeyPassphrase") { it.deleteKeyPassphrase("id") },
            op("listStoredKeyPassphraseIds") { it.listStoredKeyPassphraseIds() },
            op("storeCertificate") { it.storeCertificate("id", "pem") },
            op("getCertificate") { it.getCertificate("id") },
            op("deleteCertificate") { it.deleteCertificate("id") },
            op("isInitialized") { it.isInitialized() },
            op("wipeVault") { it.wipeVault() },
            op("listStoredCredentialIds") { it.listStoredCredentialIds() },
            op("listStoredKeyIds") { it.listStoredKeyIds() },
            op("listStoredCertificateIds") { it.listStoredCertificateIds() },
        )
        for ((name, operation) in operations) {
            val manager = newManager()
            assertThrows(
                VaultAuthExpiredException::class.java,
                Executable { operation(manager) },
                "$name doit lever VaultAuthExpiredException",
            )
        }
        assertFalse("open" in calls, "EncryptedSharedPreferences ne doit jamais être créé")
    }

    @Test
    fun `isAuthRequired is true while the window is expired`() {
        probeFailure = expiredWindow()

        assertTrue(newManager().isAuthRequired())
        assertFalse("open" in calls)
    }

    // ── Le lazy n'est pas empoisonné ────────────────────────────────────────────

    @Test
    fun `after an expired window a later access with an open window proceeds`() {
        every { prefs.getString("pwd_cred-1", null) } returns "secret"
        val manager = newManager()

        probeFailure = expiredWindow()
        assertThrows(VaultAuthExpiredException::class.java) { manager.getPassword("cred-1") }
        assertTrue(manager.isAuthRequired())

        // L'utilisateur s'authentifie : la fenêtre est rouverte.
        probeFailure = null
        assertEquals("secret", String(manager.getPassword("cred-1")!!))
        assertFalse(manager.isAuthRequired())

        assertEquals(
            listOf(
                "ensureMasterKey", "probe", // getPassword, expiré
                "ensureMasterKey", "probe", // isAuthRequired, expiré
                "ensureMasterKey", "probe", "open", // getPassword, réussi
            ),
            calls,
        )
    }

    @Test
    fun `once opened the vault is neither probed nor reopened again`() {
        val manager = newManager()
        manager.isInitialized()

        // Le Keystore n'est plus sollicité après l'ouverture : une fenêtre expirée ensuite est sans effet.
        probeFailure = expiredWindow()
        manager.isInitialized()
        manager.getPrivateKey("k")

        assertFalse(manager.isAuthRequired())
        assertEquals(listOf("ensureMasterKey", "probe", "open"), calls)
    }

    // ── Course entre la sonde et la création des préférences ────────────────────

    @Test
    fun `a failing open is probed again and the expired window wins with the original suppressed`() {
        val original = IllegalStateException("open failed")
        val expired = expiredWindow()
        openFailure = original
        var probeCalls = 0
        val manager = newManager(
            MasterKeyProbe {
                calls += "probe"
                // Première sonde : fenêtre ouverte. Seconde, après l'échec de open() : expirée.
                if (++probeCalls == 2) throw expired
            },
        )

        val thrown = assertThrows(VaultAuthExpiredException::class.java) { manager.getPassword("cred-1") }

        assertSame(expired, thrown.cause)
        assertEquals(listOf<Throwable>(original), expired.suppressed.toList())
        assertEquals(listOf("ensureMasterKey", "probe", "open", "probe"), calls)
    }

    @Test
    fun `a failing open with the window still open propagates the original exception`() {
        val original = IllegalStateException("open failed")
        openFailure = original
        val manager = newManager()

        val thrown = assertThrows(IllegalStateException::class.java) { manager.getPassword("cred-1") }

        assertSame(original, thrown)
        assertEquals(0, original.suppressed.size)
        assertEquals(listOf("ensureMasterKey", "probe", "open", "probe"), calls)
    }

    @Test
    fun `a failing open does not poison the lazy`() {
        every { prefs.getString("pwd_cred-1", null) } returns "secret"
        openFailure = IllegalStateException("open failed")
        val manager = newManager()
        assertThrows(IllegalStateException::class.java) { manager.getPassword("cred-1") }

        openFailure = null

        assertEquals("secret", String(manager.getPassword("cred-1")!!))
    }

    @Test
    fun `an Error from open is not intercepted and is not probed again`() {
        val error = NoSuchMethodError("boom")
        openFailure = error

        val thrown = assertThrows(NoSuchMethodError::class.java) { newManager().getPassword("cred-1") }

        assertSame(error, thrown)
        assertEquals(listOf("ensureMasterKey", "probe", "open"), calls)
    }

    // ── Clé invalidée ───────────────────────────────────────────────────────────

    @Test
    fun `permanently invalidated key propagates and is not mapped to an expired window`() {
        val invalidated = KeyPermanentlyInvalidatedException("key invalidated")
        probeFailure = invalidated

        val thrown = assertThrows(KeyPermanentlyInvalidatedException::class.java) {
            newManager().getPassword("cred-1")
        }

        assertSame(invalidated, thrown)
        assertEquals(listOf("ensureMasterKey", "probe"), calls)
    }

    @Test
    fun `isAuthRequired stays true for a permanently invalidated key`() {
        probeFailure = KeyPermanentlyInvalidatedException("key invalidated")

        assertTrue(newManager().isAuthRequired())
    }

    // ── Clé absente : échec fermé ───────────────────────────────────────────────

    @Test
    fun `a missing key makes the probe fail closed with a fixed message`() {
        var dryEncryptionRun = false
        val probe = newProbe(loadKey = { null }, dryEncrypt = { dryEncryptionRun = true })

        val thrown = assertThrows(KeyStoreException::class.java) { probe.probe() }

        assertEquals(MASTER_KEY_MISSING_MESSAGE, thrown.message)
        assertFalse(dryEncryptionRun, "sans clé, aucun chiffrement à blanc")
        assertEquals(0, pauses, "une clé absente n'est pas une erreur transitoire")
    }

    @Test
    fun `a missing key stops the opening of the vault before any prefs are created`() {
        val manager = newManager(newProbe(loadKey = { null }))

        val thrown = assertThrows(KeyStoreException::class.java) { manager.getPassword("cred-1") }

        assertEquals(MASTER_KEY_MISSING_MESSAGE, thrown.message)
        assertEquals(listOf("ensureMasterKey"), calls)
        assertTrue(manager.isAuthRequired())
    }

    // ── KeystoreMasterKeyProbe ──────────────────────────────────────────────────

    @Test
    fun `probe loads the vault master key by its alias and dry encrypts with it`() {
        var loadedAlias: String? = null
        var encryptedWith: Key? = null
        val probe = newProbe(
            loadKey = { alias ->
                loadedAlias = alias
                validKey
            },
            dryEncrypt = { encryptedWith = it },
        )

        probe.probe()

        assertEquals("nextsh_vault_auth_key", AUTH_MASTER_KEY_ALIAS, "renommer l'alias orphelinerait le vault")
        assertEquals(AUTH_MASTER_KEY_ALIAS, loadedAlias)
        assertSame(validKey, encryptedWith)
        assertEquals(0, pauses)
    }

    @Test
    fun `probe lets an expired window through unchanged and does not retry it`() {
        val expired = expiredWindow()
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            attempts++
            throw expired
        })

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) { probe.probe() }

        assertSame(expired, thrown)
        assertEquals(1, attempts)
        assertEquals(0, pauses)
    }

    @Test
    fun `probe lets a permanently invalidated key through unchanged and does not retry it`() {
        val invalidated = KeyPermanentlyInvalidatedException("key invalidated")
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            attempts++
            throw invalidated
        })

        val thrown = assertThrows(KeyPermanentlyInvalidatedException::class.java) { probe.probe() }

        assertSame(invalidated, thrown)
        assertEquals(1, attempts)
        assertEquals(0, pauses)
    }

    // ── Erreurs transitoires du Keystore : un seul nouvel essai ─────────────────

    @Test
    fun `a transient failure of the dry encryption is retried once after a pause and then passes`() {
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            if (++attempts == 1) throw ProviderException("transient")
        })

        probe.probe()

        assertEquals(2, attempts)
        assertEquals(1, pauses)
    }

    @Test
    fun `a transient NullPointerException while loading the key is retried once and then passes`() {
        var loads = 0
        val probe = newProbe(loadKey = {
            if (++loads == 1) throw NullPointerException("transient")
            validKey
        })

        probe.probe()

        assertEquals(2, loads)
        assertEquals(1, pauses)
    }

    @Test
    fun `a transient ProviderException while loading the key is retried once and then passes`() {
        var loads = 0
        val probe = newProbe(loadKey = {
            if (++loads == 1) throw ProviderException("transient")
            validKey
        })

        probe.probe()

        assertEquals(2, loads)
        assertEquals(1, pauses)
    }

    @Test
    fun `two ProviderExceptions from the dry encryption propagate the second one with the first suppressed`() {
        val first = ProviderException("first")
        val second = ProviderException("second")
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            throw if (++attempts == 1) first else second
        })

        val thrown = assertThrows(ProviderException::class.java) { probe.probe() }

        assertSame(second, thrown)
        assertEquals(listOf<Throwable>(first), thrown.suppressed.toList())
        assertEquals(2, attempts)
        assertEquals(1, pauses)
    }

    @Test
    fun `two NullPointerExceptions while loading the key propagate the second one as is`() {
        val first = NullPointerException("first")
        val second = NullPointerException("second")
        var loads = 0
        val probe = newProbe(loadKey = {
            throw if (++loads == 1) first else second
        })

        val thrown = assertThrows(NullPointerException::class.java) { probe.probe() }

        assertSame(second, thrown)
        assertEquals(listOf<Throwable>(first), thrown.suppressed.toList())
        assertEquals(2, loads)
        assertEquals(1, pauses)
    }

    // ── NullPointerException du chiffrement : authentification requise ──────────

    @Test
    fun `a NullPointerException from the dry encryption becomes an authentication required error without retry`() {
        val npe = NullPointerException("vendor keystore")
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            attempts++
            throw npe
        })

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) { probe.probe() }

        assertEquals(listOf<Throwable>(npe), authRequiredCauses, "la NullPointerException doit être la cause")
        assertEquals(1, attempts, "un seul essai")
        assertEquals(0, pauses, "aucune pause")
    }

    @Test
    fun `a NullPointerException from the dry encryption ends as VaultAuthExpiredException through the vault`() {
        val npe = NullPointerException("vendor keystore")
        val manager = newManager(newProbe(dryEncrypt = { throw npe }))

        val thrown = assertThrows(VaultAuthExpiredException::class.java) { manager.getPassword("cred-1") }

        assertTrue(thrown.cause is UserNotAuthenticatedException)
        assertEquals(listOf<Throwable>(npe), authRequiredCauses, "la NullPointerException est la cause de l'erreur d'authentification")
        assertEquals(listOf("ensureMasterKey"), calls, "les préférences ne sont pas créées")
        assertEquals(0, pauses)
        assertTrue(manager.isAuthRequired())
    }

    @Test
    fun `a ProviderException then a NullPointerException from the dry encryption ends as an authentication required error`() {
        val first = ProviderException("transient")
        val npe = NullPointerException("vendor keystore")
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            throw if (++attempts == 1) first else npe
        })

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) { probe.probe() }

        assertEquals(listOf<Throwable>(npe), authRequiredCauses)
        assertEquals(listOf<Throwable>(first), thrown.suppressed.toList())
        assertEquals(2, attempts)
        assertEquals(1, pauses)
    }

    @Test
    fun `a NullPointerException while loading the key is not translated into an authentication error`() {
        val npe = NullPointerException("transient")
        val probe = newProbe(loadKey = { throw npe })

        val thrown = assertThrows(NullPointerException::class.java) { probe.probe() }

        assertSame(npe, thrown)
        assertEquals(emptyList<Throwable>(), authRequiredCauses)
    }

    @Test
    fun `the default authentication required error is a UserNotAuthenticatedException`() {
        val npe = NullPointerException("vendor keystore")

        assertTrue(authRequiredFrom(npe) is UserNotAuthenticatedException)
    }
    @Test
    fun `the same transient instance thrown twice still propagates`() {
        val broken = ProviderException("keystore broken")
        val probe = newProbe(dryEncrypt = { throw broken })

        val thrown = assertThrows(ProviderException::class.java) { probe.probe() }

        assertSame(broken, thrown)
        assertEquals(1, pauses)
    }

    @Test
    fun `a transient failure then an expired window propagates the expired window`() {
        val expired = expiredWindow()
        var attempts = 0
        val probe = newProbe(dryEncrypt = {
            throw if (++attempts == 1) ProviderException("transient") else expired
        })

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) { probe.probe() }

        assertSame(expired, thrown)
        assertEquals(2, attempts)
    }

    @Test
    fun `an unexpected non transient keystore failure is not retried`() {
        val broken = KeyStoreException("keystore broken")
        var attempts = 0
        val probe = newProbe(loadKey = {
            attempts++
            throw broken
        })

        val thrown = assertThrows(KeyStoreException::class.java) { probe.probe() }

        assertSame(broken, thrown)
        assertEquals(1, attempts)
        assertEquals(0, pauses)
    }

    @Test
    fun `an unexpected keystore failure propagates through the vault without being mapped`() {
        val broken = IllegalStateException("keystore broken")

        val thrown = assertThrows(IllegalStateException::class.java) {
            newManager(newProbe(dryEncrypt = { throw broken })).getPassword("cred-1")
        }

        assertSame(broken, thrown)
        assertEquals(listOf("ensureMasterKey"), calls)
    }

    // ── Chiffrement à blanc : init puis fin de l'opération ──────────────────────

    @Test
    fun `dry encryption initialises the cipher then finishes the operation on a one byte input`() {
        val cipher = mockk<Cipher>(relaxed = true)

        runDryEncryption(cipher, validKey)

        verifyOrder {
            cipher.init(Cipher.ENCRYPT_MODE, validKey)
            cipher.doFinal(match<ByteArray> { it.size == 1 })
        }
    }

    @Test
    fun `dry encryption lets an expired window raised at init through and does not finish`() {
        val expired = expiredWindow()
        val cipher = mockk<Cipher>(relaxed = true)
        every { cipher.init(Cipher.ENCRYPT_MODE, validKey) } throws expired

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) {
            runDryEncryption(cipher, validKey)
        }

        assertSame(expired, thrown)
        verify(exactly = 0) { cipher.doFinal(any<ByteArray>()) }
    }

    @Test
    fun `dry encryption lets an expired window raised at finish through unchanged`() {
        val expired = expiredWindow()
        val cipher = mockk<Cipher>(relaxed = true)
        every { cipher.doFinal(any<ByteArray>()) } throws expired

        val thrown = assertThrows(UserNotAuthenticatedException::class.java) {
            runDryEncryption(cipher, validKey)
        }

        assertSame(expired, thrown)
    }

    @Test
    fun `dry encryption lets a permanently invalidated key raised at finish through unchanged`() {
        val invalidated = KeyPermanentlyInvalidatedException("key invalidated")
        val cipher = mockk<Cipher>(relaxed = true)
        every { cipher.doFinal(any<ByteArray>()) } throws invalidated

        val thrown = assertThrows(KeyPermanentlyInvalidatedException::class.java) {
            runDryEncryption(cipher, validKey)
        }

        assertSame(invalidated, thrown)
    }

    @Test
    fun `dry encryption does not swallow any other failure of the finish step`() {
        val broken = ProviderException("keystore broken")
        val cipher = mockk<Cipher>(relaxed = true)
        every { cipher.doFinal(any<ByteArray>()) } throws broken

        val thrown = assertThrows(ProviderException::class.java) {
            runDryEncryption(cipher, validKey)
        }

        assertSame(broken, thrown)
    }

    @Test
    fun `default dry encryption runs AES GCM to the end with a valid key`() {
        dryEncryptAesGcm(validKey)
    }

    @Test
    fun `default dry encryption is not silenced when the key cannot encrypt`() {
        assertThrows(GeneralSecurityException::class.java) {
            dryEncryptAesGcm(SecretKeySpec(ByteArray(5), "AES"))
        }
    }
}
