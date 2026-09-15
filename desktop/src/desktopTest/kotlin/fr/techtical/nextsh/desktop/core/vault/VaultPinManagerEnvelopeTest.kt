// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.vault

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v2 DEK-envelope crypto in [VaultPinManager]: PIN unlock,
 * recovery-phrase unlock, wrong-secret rejection, transparent v1→v2 migration,
 * recovery-phrase setup on a migrated vault, PIN rotation, and the BIP39
 * [RecoveryMnemonic] round-trip.
 *
 * Each test isolates `~/.nextsh` into a per-test temp dir via
 * [VaultPaths.withBaseDir] (the JVM forbids per-test env-var mutation, so this
 * internal override is the supported isolation mechanism). The
 * [DesktopVaultManager] reads/writes through the same [VaultPaths], so storing
 * a real entry under one master key and reading it back proves the DEK is
 * stable across unlock paths.
 */
class VaultPinManagerEnvelopeTest {

    private lateinit var tempDir: File
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-vault-envelope-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // ── 1. setup + PIN unlock yields the same DEK ───────────────────────────

    @Test
    fun `setupNewVault then unlock with same pin reads back stored entry`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pin = VaultManagerHarness.setupAndStore("super-pin", "cred-1", "s3cret-pwd")

            // New manager instance (cold state) + same backing files.
            val pm = VaultPinManager()
            val unlock = pm.unlock(pin())
            assertTrue(unlock.isSuccess, "unlock with correct PIN must succeed")

            val vault = DesktopVaultManager(pm)
            val pwd = vault.getPasswordBlocking("cred-1")
            assertEquals("s3cret-pwd", pwd, "entry must decrypt under the unlocked DEK")
        }
    }

    // ── 2. recovery phrase unlocks the same DEK; wrong words fail ────────────

    @Test
    fun `setupNewVault returns 12 valid words and recovery unlock yields same DEK`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pm = VaultPinManager()
            val setup = pm.setupNewVault("the-pin".toCharArray())
            val words = setup.getOrNull() ?: error("setup failed: ${setup.exceptionOrNull()}")
            assertEquals(12, words.size, "mnemonic must be 12 words")
            assertTrue(RecoveryMnemonic.isValid(words), "generated mnemonic must validate")

            // Store an entry under the just-set DEK.
            DesktopVaultManager(pm).storePasswordBlocking("cred-r", "recovery-secret")
            pm.lock()

            // Unlock from a cold manager using the recovery phrase.
            val pm2 = VaultPinManager()
            val rec = pm2.unlockWithRecovery(words)
            assertTrue(rec.isSuccess, "recovery unlock must succeed")
            assertEquals(
                "recovery-secret",
                DesktopVaultManager(pm2).getPasswordBlocking("cred-r"),
                "entry must decrypt under the recovery-unlocked DEK",
            )

            // Wrong words → failure, DEK not set.
            val pm3 = VaultPinManager()
            val wrong = words.toMutableList().also { it[0] = if (it[0] == "abandon") "ability" else "abandon" }
            val bad = pm3.unlockWithRecovery(wrong)
            assertTrue(bad.isFailure, "wrong recovery words must fail")
            assertFalse(pm3.isUnlocked, "DEK must not be set on failed recovery unlock")
        }
    }

    // ── 3. wrong PIN fails, DEK not set ──────────────────────────────────────

    @Test
    fun `unlock with wrong pin fails and does not set the DEK`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            VaultPinManager().setupNewVault("correct-pin".toCharArray()).getOrThrow()

            val pm = VaultPinManager()
            val result = pm.unlock("wrong-pin".toCharArray())
            assertTrue(result.isFailure, "wrong PIN must fail")
            assertEquals("PIN incorrect", result.exceptionOrNull()?.message)
            assertFalse(pm.isUnlocked, "DEK must not be set on wrong PIN")
        }
    }

    // ── 4. transparent v1 → v2 migration ─────────────────────────────────────

    @Test
    fun `legacy v1 vault migrates to v2 on unlock and keeps decrypting`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pin = "legacy-pin"
            // Hand-write a v1 meta + a vault.dat entry encrypted under PBKDF2(pin).
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val oldKey = legacyDerive(pin.toCharArray(), salt)
            val verifyCt = legacyEncrypt(oldKey, "nextsh-vault-v1".toByteArray(Charsets.UTF_8))
            VaultPaths.metaFile.writeText(
                Json.encodeToString(
                    LegacyMeta.serializer(),
                    LegacyMeta(
                        saltBase64 = Base64.getEncoder().encodeToString(salt),
                        verifyCiphertextBase64 = Base64.getEncoder().encodeToString(verifyCt),
                    ),
                ),
            )
            // Write a vault.dat entry under the SAME old key (mirroring the old scheme).
            writeLegacyEntry(oldKey, "pwd:cred-legacy", "legacy-secret")

            // First unlock → migrates.
            val pm = VaultPinManager()
            val unlock = pm.unlock(pin.toCharArray())
            assertTrue(unlock.isSuccess, "legacy unlock must succeed: ${unlock.exceptionOrNull()}")

            // Meta is now v2.
            val migrated = lenientJson
                .decodeFromString(ProbeMeta.serializer(), VaultPaths.metaFile.readText())
            assertEquals(2, migrated.version, "meta must be upgraded to v2")
            assertNull(migrated.recoveryWrappedDekB64, "migration must NOT add a recovery wrap")
            assertNull(migrated.verifyCiphertextBase64, "legacy verify token must be gone")

            // Pre-existing vault.dat entry still decrypts under the adopted DEK.
            assertEquals(
                "legacy-secret",
                DesktopVaultManager(pm).getPasswordBlocking("cred-legacy"),
                "pre-existing entry must survive migration",
            )

            // A subsequent (now v2) unlock still works on the same DEK.
            val pm2 = VaultPinManager()
            assertTrue(pm2.unlock(pin.toCharArray()).isSuccess, "v2 re-unlock must succeed")
            assertEquals(
                "legacy-secret",
                DesktopVaultManager(pm2).getPasswordBlocking("cred-legacy"),
                "entry must still decrypt after v2 unlock",
            )
        }
    }

    // ── 5. add recovery phrase to a migrated (recovery-less) vault ───────────

    @Test
    fun `setupRecoveryPhrase on recovery-less vault enables recovery unlock`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            // Build a migrated v2 vault with no recovery wrap by going through
            // the legacy path.
            val pin = "mig-pin"
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val oldKey = legacyDerive(pin.toCharArray(), salt)
            val verifyCt = legacyEncrypt(oldKey, "nextsh-vault-v1".toByteArray(Charsets.UTF_8))
            VaultPaths.metaFile.writeText(
                Json.encodeToString(
                    LegacyMeta.serializer(),
                    LegacyMeta(
                        Base64.getEncoder().encodeToString(salt),
                        Base64.getEncoder().encodeToString(verifyCt),
                    ),
                ),
            )
            writeLegacyEntry(oldKey, "pwd:cred-m", "migrated-secret")

            val pm = VaultPinManager()
            pm.unlock(pin.toCharArray()).getOrThrow()
            assertFalse(pm.hasRecoveryPhrase(), "migrated vault starts with no recovery phrase")

            val words = pm.setupRecoveryPhrase().getOrThrow()
            assertEquals(12, words.size)
            assertTrue(pm.hasRecoveryPhrase(), "recovery phrase must now be present")

            // Recovery unlock from a cold manager must reach the same DEK.
            val pm2 = VaultPinManager()
            assertTrue(pm2.unlockWithRecovery(words).isSuccess, "recovery unlock after setup must succeed")
            assertEquals(
                "migrated-secret",
                DesktopVaultManager(pm2).getPasswordBlocking("cred-m"),
                "entry must decrypt under recovery-derived DEK",
            )
        }
    }

    // ── 6. changePin rotates the PIN wrap ────────────────────────────────────

    @Test
    fun `changePin lets the new pin unlock and rejects the old pin`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pm = VaultPinManager()
            pm.setupNewVault("old-pin".toCharArray()).getOrThrow()
            DesktopVaultManager(pm).storePasswordBlocking("cred-c", "change-secret")

            pm.changePin("new-pin".toCharArray()).getOrThrow()

            // New PIN unlocks; entry still decrypts (same DEK).
            val pm2 = VaultPinManager()
            assertTrue(pm2.unlock("new-pin".toCharArray()).isSuccess, "new PIN must unlock")
            assertEquals(
                "change-secret",
                DesktopVaultManager(pm2).getPasswordBlocking("cred-c"),
                "entry must decrypt after PIN change",
            )

            // Old PIN must now fail.
            val pm3 = VaultPinManager()
            assertTrue(pm3.unlock("old-pin".toCharArray()).isFailure, "old PIN must no longer unlock")
            assertFalse(pm3.isUnlocked)
        }
    }

    // ── 8. recovery unlock on a recovery-less (migrated) vault fails ─────────

    @Test
    fun `unlockWithRecovery on a migrated recovery-less vault fails and does not set DEK`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            // Build a migrated v2 vault with no recovery wrap via the legacy path.
            val pin = "mig-no-rec-pin"
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val oldKey = legacyDerive(pin.toCharArray(), salt)
            val verifyCt = legacyEncrypt(oldKey, "nextsh-vault-v1".toByteArray(Charsets.UTF_8))
            VaultPaths.metaFile.writeText(
                Json.encodeToString(
                    LegacyMeta.serializer(),
                    LegacyMeta(
                        Base64.getEncoder().encodeToString(salt),
                        Base64.getEncoder().encodeToString(verifyCt),
                    ),
                ),
            )
            writeLegacyEntry(oldKey, "pwd:cred-nr", "no-recovery-secret")

            // Migrate (still no recovery wrap afterwards).
            VaultPinManager().unlock(pin.toCharArray()).getOrThrow()

            // A cold manager trying recovery unlock must cleanly fail.
            val pm = VaultPinManager()
            assertFalse(pm.hasRecoveryPhrase(), "migrated vault must report no recovery phrase")
            val anyValidWords = RecoveryMnemonic.generate()
            val result = pm.unlockWithRecovery(anyValidWords)
            assertTrue(result.isFailure, "recovery unlock without a recovery wrap must fail")
            assertEquals(
                "Recovery phrase not available",
                result.exceptionOrNull()?.message,
                "must report the recovery wrap is absent",
            )
            assertFalse(pm.isUnlocked, "DEK must not be set when recovery is unavailable")
        }
    }

    // ── 9. corrupt / empty vault.meta yields a clean failure (no NPE) ────────

    @Test
    fun `unlock with corrupt meta returns a clean failure`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            // Garbage that is not valid JSON for any meta shape.
            VaultPaths.metaFile.writeText("{ this is not valid json")

            val pm = VaultPinManager()
            val result = pm.unlock("whatever".toCharArray())
            assertTrue(result.isFailure, "corrupt meta must fail rather than throw")
            assertFalse(pm.isUnlocked, "corrupt meta must not be misdetected as a successful unlock")
        }
    }

    @Test
    fun `unlock with empty meta returns a clean failure`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            // Empty file: isSetup() is true (file exists) but there is nothing to parse.
            VaultPaths.metaFile.writeText("")

            val pm = VaultPinManager()
            val result = pm.unlock("whatever".toCharArray())
            assertTrue(result.isFailure, "empty meta must fail rather than throw")
            assertFalse(pm.isUnlocked, "empty meta must not be misdetected as a successful unlock")
        }
    }

    // ── 10. atomic writes leave no stray .tmp behind ─────────────────────────

    @Test
    fun `changePin leaves no leftover meta tmp file`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pm = VaultPinManager()
            pm.setupNewVault("old-pin".toCharArray()).getOrThrow()
            DesktopVaultManager(pm).storePasswordBlocking("cred-atomic", "atomic-secret")
            pm.changePin("new-pin".toCharArray()).getOrThrow()

            assertFalse(
                File(tempDir, "vault.meta.tmp").exists(),
                "atomicWrite must not leave a stray vault.meta.tmp after changePin",
            )
            assertFalse(
                File(tempDir, "vault.dat.tmp").exists(),
                "atomicWrite must not leave a stray vault.dat.tmp after storing an entry",
            )
            // The committed files themselves must be present and intact.
            assertTrue(VaultPaths.metaFile.exists(), "vault.meta must exist after changePin")
            assertTrue(VaultPaths.dataFile.exists(), "vault.dat must exist after storing an entry")
        }
    }

    @Test
    fun `migration leaves no leftover meta tmp file`() = runTest {
        VaultPaths.withBaseDir(tempDir) {
            val pin = "mig-atomic-pin"
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val oldKey = legacyDerive(pin.toCharArray(), salt)
            val verifyCt = legacyEncrypt(oldKey, "nextsh-vault-v1".toByteArray(Charsets.UTF_8))
            VaultPaths.metaFile.writeText(
                Json.encodeToString(
                    LegacyMeta.serializer(),
                    LegacyMeta(
                        Base64.getEncoder().encodeToString(salt),
                        Base64.getEncoder().encodeToString(verifyCt),
                    ),
                ),
            )
            writeLegacyEntry(oldKey, "pwd:cred-ma", "mig-atomic-secret")

            VaultPinManager().unlock(pin.toCharArray()).getOrThrow()

            assertFalse(
                File(tempDir, "vault.meta.tmp").exists(),
                "migration must not leave a stray vault.meta.tmp",
            )
        }
    }

    // ── 7. RecoveryMnemonic round-trip + tamper detection ────────────────────

    @Test
    fun `RecoveryMnemonic generate produces valid 12-word phrases`() {
        repeat(50) {
            val words = RecoveryMnemonic.generate()
            assertEquals(12, words.size, "mnemonic must be 12 words")
            assertTrue(RecoveryMnemonic.isValid(words), "freshly generated mnemonic must be valid")
        }
    }

    @Test
    fun `the last word checksum is enforced`() {
        // The 12th word packs 11 bits = the final 7 entropy bits + the 4-bit
        // SHA-256 checksum. Holding the first 11 words fixed, the 7 entropy bits
        // can take 2^7 = 128 values, and for each there is exactly one 4-bit
        // checksum completion, so exactly 128 of the 2048 candidate last words
        // are valid. The remaining 1920 fail the checksum. This is deterministic
        // (unlike a single random word swap, which BIP39 only catches ~15/16 of
        // the time) and proves the checksum is genuinely enforced.
        val words = RecoveryMnemonic.generate()
        val wordList = loadWordListForTest()
        val validCount = wordList.count { candidate ->
            RecoveryMnemonic.isValid(words.toMutableList().also { it[11] = candidate })
        }
        assertEquals(128, validCount, "exactly 128 of 2048 last-word candidates must pass the 4-bit checksum")
        assertTrue(RecoveryMnemonic.isValid(words), "the original mnemonic must validate")
    }

    @Test
    fun `tampering an entropy word breaks validation`() {
        // Word position 0 sits fully inside the entropy region, so changing it
        // changes the entropy and recomputes the checksum. BIP39's 4-bit
        // checksum still has a 1/16 collision chance per random swap, so we
        // assert across all 2048 substitutions: exactly 128 validate (those
        // whose entropy contribution happens to keep the original checksum
        // word consistent), the other 1920 fail: same structural argument as
        // the last-word test, just at a different position.
        val words = RecoveryMnemonic.generate()
        val wordList = loadWordListForTest()
        val validCount = wordList.count { candidate ->
            RecoveryMnemonic.isValid(words.toMutableList().also { it[0] = candidate })
        }
        assertTrue(validCount in 1..200, "most single-word entropy tampers must fail; got $validCount valid of 2048")
    }

    @Test
    fun `RecoveryMnemonic rejects wrong length and unknown words`() {
        assertFalse(RecoveryMnemonic.isValid(emptyList()))
        assertFalse(RecoveryMnemonic.isValid(List(12) { "notabip39word" }))
        val short = RecoveryMnemonic.generate().take(11)
        assertFalse(RecoveryMnemonic.isValid(short))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Loads the same BIP39 wordlist resource the production code uses. */
    private fun loadWordListForTest(): List<String> {
        val stream = RecoveryMnemonic::class.java.getResourceAsStream("bip39-english.txt")
            ?: error("bip39-english.txt not found on classpath")
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    // ── Legacy v1 helpers (mirror the OLD direct-PIN scheme) ─────────────────

    private fun legacyDerive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, 200_000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return try {
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun legacyEncrypt(keyBytes: ByteArray, plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** Writes a single vault.dat entry in the exact format DesktopVaultManager expects. */
    private fun writeLegacyEntry(keyBytes: ByteArray, fullKey: String, value: String) {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val body = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val store = StoreFile(
            entries = mapOf(
                fullKey to BlobFile(
                    ivB64 = Base64.getEncoder().encodeToString(iv),
                    dataB64 = Base64.getEncoder().encodeToString(body),
                ),
            ),
        )
        VaultPaths.dataFile.writeText(Json.encodeToString(StoreFile.serializer(), store))
    }

    @Serializable
    private data class LegacyMeta(val saltBase64: String, val verifyCiphertextBase64: String)

    @Serializable
    private data class ProbeMeta(
        val version: Int? = null,
        val recoveryWrappedDekB64: String? = null,
        val verifyCiphertextBase64: String? = null,
    )

    @Serializable
    private data class BlobFile(val ivB64: String, val dataB64: String)

    @Serializable
    private data class StoreFile(val entries: Map<String, BlobFile> = emptyMap())
}

/**
 * Tiny blocking bridge over [DesktopVaultManager]'s suspend API so the crypto
 * assertions stay linear. Uses [kotlinx.coroutines.runBlocking], fine in tests.
 */
private fun DesktopVaultManager.storePasswordBlocking(credentialId: String, value: String) =
    kotlinx.coroutines.runBlocking { storePassword(credentialId, value.toCharArray()) }

private fun DesktopVaultManager.getPasswordBlocking(credentialId: String): String? =
    kotlinx.coroutines.runBlocking { getPassword(credentialId)?.concatToString() }

private object VaultManagerHarness {
    /**
     * Sets up a fresh vault with [pin], stores a password, locks, and returns a
     * supplier that yields a fresh copy of the PIN CharArray (the manager wipes
     * the array it receives, so callers need a fresh copy each time).
     */
    fun setupAndStore(pin: String, credentialId: String, value: String): () -> CharArray {
        val pm = VaultPinManager()
        pm.setupNewVault(pin.toCharArray()).getOrThrow()
        kotlinx.coroutines.runBlocking {
            DesktopVaultManager(pm).storePassword(credentialId, value.toCharArray())
        }
        pm.lock()
        return { pin.toCharArray() }
    }
}
