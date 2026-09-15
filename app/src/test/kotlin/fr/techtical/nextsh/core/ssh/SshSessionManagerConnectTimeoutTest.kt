// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [resolveConnectTimeoutMs] is the pure computation backing
 * `SshSessionManager.createSshClient`: extracted so the effective
 * `SSHClient.connectTimeout` (derived from the user's "Timeout de connexion"
 * setting) is verifiable without opening a real socket.
 *
 * The function intentionally has no `Host` parameter: it cannot regress into
 * reading `host.keepAliveSeconds` (the bug this feature fixes on Desktop,
 * see `DesktopSshSessionManager`/`VaultSshClientFactory`), because that value
 * is simply not in scope here.
 *
 * ## Known limitation: where the real guard lives
 *
 * The behavioural tests below exercise the pure function IN ISOLATION. They
 * cannot, on their own, prove that production still *calls* it: if someone
 * rewrote `createSshClient` as `client.connectTimeout = 10_000`, every
 * assertion here would keep passing while the Settings slider went back to
 * being a placebo.
 *
 * That gap is structural on Android, not an oversight:
 * `SshSessionManager.createSshClient` is `private` and constructs its
 * `SSHClient` inline: there is no injection point, no callback, and no way to
 * observe the configured client without either opening a socket or changing
 * production code (explicitly out of scope). The Desktop counterpart HAS such
 * a seam (`VaultSshClientFactory`'s `onCreated` callback, invoked after
 * `connectTimeout` is set and before `connect()`), which is why
 * `desktop/.../VaultSshClientFactoryConnectTimeoutTest` can assert the real
 * client end-to-end and this suite cannot.
 *
 * The compromise is [productionStillRoutesThroughTheResolver] below: a
 * source-level guard, cheap and non-invasive, that fails when the call site
 * disappears or a numeric literal reappears. It is a smoke alarm, not a proof:
 * the day `SshSessionManager` grows a testable seam, replace it with a real
 * end-to-end assertion mirroring the Desktop one.
 */
class SshSessionManagerConnectTimeoutTest {

    @Test
    fun `value within 5-30s range is converted to milliseconds unchanged`() {
        assertEquals(10_000, resolveConnectTimeoutMs(10))
        assertEquals(15_000, resolveConnectTimeoutMs(15))
        assertEquals(5_000, resolveConnectTimeoutMs(5))
        assertEquals(30_000, resolveConnectTimeoutMs(30))
    }

    @Test
    fun `value below the slider minimum is clamped to 5s`() {
        assertEquals(5_000, resolveConnectTimeoutMs(0))
        assertEquals(5_000, resolveConnectTimeoutMs(-42))
    }

    @Test
    fun `value above the slider maximum is clamped to 30s`() {
        assertEquals(30_000, resolveConnectTimeoutMs(31))
        assertEquals(30_000, resolveConnectTimeoutMs(999))
    }

    // ── Source-level guard (see the class KDoc for why) ───────────────────────

    /**
     * Locates `SshSessionManager.kt` by walking up from the test's working
     * directory, which Gradle sets to the module directory (`app/`) but which
     * other runners (IDE, CI wrappers) may point at the repository root
     * instead. Returns null if neither layout resolves.
     */
    private fun locateSshSessionManagerSource(): File? {
        val fromModuleRoot = "src/main/kotlin/fr/techtical/nextsh/core/ssh/SshSessionManager.kt"
        val fromRepoRoot = "app/$fromModuleRoot"
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, fromModuleRoot).takeIf { it.isFile }?.let { return it }
            File(dir, fromRepoRoot).takeIf { it.isFile }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /**
     * Guards the wiring the unit tests above cannot reach: `createSshClient`
     * must still route the user setting through [resolveConnectTimeoutMs], and
     * `connectTimeout` must never be assigned a hard-coded number again.
     *
     * Deliberately loose about the exact shape of the call site (any call
     * anywhere in the file counts) so a legitimate refactor (extracting a
     * local, moving the assignment into a builder) doesn't trip it. What it
     * does catch is the actual regression: the call vanishing, or a literal
     * such as `connectTimeout = 10_000` coming back.
     *
     * Skips rather than fails when the source file can't be located, so an
     * unusual runner layout degrades to "no coverage" instead of a red build
     * that has nothing to do with the code under test.
     */
    @Test
    fun productionStillRoutesThroughTheResolver() {
        val source = locateSshSessionManagerSource()
        assumeTrue(
            source != null,
            "SshSessionManager.kt not found from working dir ${System.getProperty("user.dir")}: guard skipped",
        )
        val text = source!!.readText()

        // At least one CALL (the `(?<!fun )` lookbehind excludes the
        // declaration itself, which would otherwise satisfy a naive contains).
        val callSites = Regex("""(?<!fun )resolveConnectTimeoutMs\(""").findAll(text).count()
        assertTrue(
            callSites >= 1,
            "SshSessionManager no longer calls resolveConnectTimeoutMs: the " +
                "\"Timeout de connexion\" setting is very likely a placebo again",
        )

        // …and no literal ever assigned to connectTimeout. (`client.timeout =
        // 30_000`, the handshake bound, is a different property and stays legal.)
        val hardCoded = Regex("""connectTimeout\s*=\s*[0-9][0-9_]*""").findAll(text).map { it.value }.toList()
        assertTrue(
            hardCoded.isEmpty(),
            "connectTimeout must be derived from the user setting, found hard-coded: $hardCoded",
        )
    }
}
