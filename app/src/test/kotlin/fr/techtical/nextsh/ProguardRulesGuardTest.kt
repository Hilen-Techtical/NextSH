// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh

import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * Source-level guard for the R8 keep rule that makes Ed25519 key generation work in the release
 * build.
 *
 * `EdDSASecurityProvider` maps `KeyFactory.EdDSA`, `KeyPairGenerator.EdDSA` and
 * `Signature.NONEwithEdDSA` to implementation classes by NAME (plain strings), and the JCA loads
 * them reflectively. Nothing references those classes statically, so without a keep rule R8 drops
 * `KeyFactory` and `KeyPairGenerator` and renames `EdDSAEngine`: the lookup then fails with
 * "class configured for KeyPairGenerator (provider: EdDSA) cannot be found". Debug builds are not
 * minified, so no other test can see it. A unit test cannot run R8, hence the same compromise as
 * [fr.techtical.nextsh.data.db.dao.SyncUpsertGuardTest]: a cheap source check on
 * `proguard-rules.pro`.
 *
 * Under Gradle, `app/build.gradle.kts` declares `proguard-rules.pro` as an input of the Test task
 * and passes its path in the `proguardRules` system property. Without that, a change that only
 * deletes the rule leaves the classes untouched and the task stays UP-TO-DATE or FROM-CACHE, so
 * the guard would never run. When the property is set, a missing file FAILS the test. The
 * directory walk is only a fallback for runners that do not set it (IDE), and it skips instead.
 */
class ProguardRulesGuardTest {

    private companion object {
        const val EDDSA_PACKAGE = "net.i2p.crypto.eddsa."
        const val PATH_PROPERTY = "proguardRules"
    }

    /**
     * Locates `app/proguard-rules.pro` by walking up from the test's working directory, which
     * Gradle sets to the module directory (`app/`) but which other runners may point at the
     * repository root.
     */
    private fun walkToProguardRules(): File? {
        val fromModuleRoot = "proguard-rules.pro"
        val fromRepoRoot = "app/$fromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, fromModuleRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    private fun locateProguardRules(): File {
        val configured = System.getProperty(PATH_PROPERTY)
        if (configured != null) {
            val file = File(configured)
            assertTrue(
                file.isFile,
                "system property $PATH_PROPERTY points to $configured, which does not exist: " +
                    "app/build.gradle.kts passes the path of proguard-rules.pro to the Test task",
            )
            return file
        }
        val walked = walkToProguardRules()
        assumeTrue(
            walked != null,
            "proguard-rules.pro not found from working dir ${System.getProperty("user.dir")}: guard skipped",
        )
        return walked!!
    }

    /** The directives of the file, without comment lines (a commented-out rule must not count). */
    private fun directives(): List<String> =
        locateProguardRules().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun `i2p EdDSA classes are kept with their names and members`() {
        val keepsEdDsa = directives().any {
            Regex("""^-keep\s+class\s+net\.i2p\.crypto\.eddsa\.\*\*\s*\{\s*\*;\s*\}$""").matches(it)
        }
        assertTrue(
            keepsEdDsa,
            "proguard-rules.pro must keep `net.i2p.crypto.eddsa.**` (-keep class ... { *; }): the " +
                "EdDSA provider resolves KeyPairGenerator, KeyFactory and EdDSAEngine by name, so " +
                "without it R8 removes or renames them and Ed25519 key generation fails in release",
        )
    }

    @Test
    fun `every class the EdDSA provider names is covered by the keep rule package`() {
        val classNames = EdDSASecurityProvider().services.map { it.className }.toSet()
        // Sanity: the provider still exposes its three implementations. If this list changes after
        // a library upgrade, the keep rule must be re-checked against the new map.
        assertTrue(
            classNames.size >= 3,
            "expected the provider to name at least KeyFactory, KeyPairGenerator and EdDSAEngine, got $classNames",
        )
        val outside = classNames.filterNot { it.startsWith(EDDSA_PACKAGE) }
        assertTrue(
            outside.isEmpty(),
            "the EdDSA provider names classes outside `${EDDSA_PACKAGE}**`, which the keep rule in " +
                "proguard-rules.pro does not cover: $outside",
        )
    }

    @Test
    fun `every class the EdDSA provider names loads and has a public no-arg constructor`() {
        // The JCA instantiates these by name (Class.forName, then the no-arg constructor), so the
        // keep rule must preserve <init> as well as the class name.
        EdDSASecurityProvider().services.map { it.className }.toSet().forEach { name ->
            val cls = try {
                Class.forName(name)
            } catch (e: ClassNotFoundException) {
                throw AssertionError("the EdDSA provider names $name, which cannot be loaded", e)
            }
            assertTrue(Modifier.isPublic(cls.modifiers), "$name must be public for the JCA to instantiate it")
            try {
                cls.getConstructor()
            } catch (e: NoSuchMethodException) {
                throw AssertionError("$name has no public no-arg constructor: the JCA cannot instantiate it", e)
            }
        }
    }
}
