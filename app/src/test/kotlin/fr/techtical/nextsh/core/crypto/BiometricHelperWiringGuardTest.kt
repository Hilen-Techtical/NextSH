// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Source-level guard for the prompt of the vault: `BiometricHelper.authenticate` must take its
 * allowed authenticators from `vaultAuthenticators(`, never a hard-coded combination.
 *
 * `BIOMETRIC_STRONG or DEVICE_CREDENTIAL` is rejected by androidx.biometric on API 28 and 29
 * (`setAllowedAuthenticators` fails), so a hard-coded combination brings back the Android 10 bug:
 * no way to unlock the vault. `BiometricPrompt` needs an activity and cannot run in a unit test,
 * and the Kotlin lambda it is built with is not observable, hence the same compromise as
 * [fr.techtical.nextsh.data.db.dao.SyncUpsertGuardTest]: a cheap source check, skipped (not
 * failed) when the file cannot be located.
 */
class BiometricHelperWiringGuardTest {

    /**
     * Locates the source by walking up from the test's working directory, which Gradle sets to the
     * module directory (`app/`) but which other runners may point at the repository root.
     */
    private fun locateSource(): File? {
        val fromModuleRoot = "src/main/kotlin/fr/techtical/nextsh/core/crypto/BiometricHelper.kt"
        val fromRepoRoot = "app/$fromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, fromModuleRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /** The code of the file, without comments (a commented-out call must not count). */
    private fun code(): String {
        val source = locateSource()
        assumeTrue(
            source != null,
            "BiometricHelper.kt not found from working dir ${System.getProperty("user.dir")}: guard skipped",
        )
        return source!!.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")
    }

    @Test
    fun `every prompt of BiometricHelper takes its authenticators from vaultAuthenticators`() {
        val code = code()
        val prompts = Regex("""\.setAllowedAuthenticators\(""").findAll(code).count()
        val viaVaultAuthenticators =
            Regex("""\.setAllowedAuthenticators\(\s*vaultAuthenticators\(""").findAll(code).count()

        assertTrue(prompts >= 1, "BiometricHelper no longer builds a prompt with setAllowedAuthenticators")
        assertEquals(
            prompts,
            viaVaultAuthenticators,
            "every setAllowedAuthenticators in BiometricHelper must be given vaultAuthenticators(sdkInt): " +
                "a hard-coded combination such as BIOMETRIC_STRONG or DEVICE_CREDENTIAL is rejected by " +
                "androidx.biometric on API 28 and 29, so the vault could not be unlocked on Android 10",
        )
    }

    @Test
    fun `checkCapability asks canAuthenticate with vaultAuthenticators`() {
        val asksWithVaultAuthenticators =
            Regex("""canAuthenticateWith\(\s*vaultAuthenticators\(""").containsMatchIn(code())

        assertTrue(
            asksWithVaultAuthenticators,
            "BiometricHelper.checkCapability must test the same authenticators as the prompt " +
                "(vaultAuthenticators(sdkInt)), otherwise canAuthenticate() and the prompt disagree",
        )
    }
}
