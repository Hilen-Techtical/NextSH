// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import io.mockk.mockk
import io.mockk.verifySequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cef.callback.CefCommandLine
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * The early exits of [KcefInitializer.initialize] that must happen BEFORE Chromium
 * starts (no profile directory, profile held by another instance), and the Chromium
 * command line.
 *
 * No test here can reach the real `KCEF.init`: the engine starter is replaced by a
 * fake that records the call and throws, and the lock is a lambda that only ever
 * answers [LockResult.Busy]. The Acquired and Error paths of the lock are never taken
 * through [KcefInitializer]; the lock itself is covered by [BrowserProfileTest].
 */
class KcefInitializerProfileTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope
    private val chromiumStarted = AtomicBoolean(false)

    /** Fake engine starter: reaching it means a guard regressed, and fails the test. */
    private val forbiddenStarter = ChromiumStarter { _, _, _ ->
        chromiumStarted.set(true)
        error("the Chromium engine must never be started in these tests")
    }

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-kcef-profile-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun initializer(
        profileDir: File?,
        lockProfile: (File) -> LockResult,
    ) = KcefInitializer(
        bundleDir = File(tempDir, "kcef"),
        profileDir = profileDir,
        coroutineScope = scope,
        lockProfile = lockProfile,
        chromiumStarter = forbiddenStarter,
        awaitEngineReady = {
            chromiumStarted.set(true)
            error("the engine readiness must never be awaited in these tests")
        },
    )

    private fun awaitFailed(initializer: KcefInitializer): KcefState.Failed = runBlocking {
        withTimeout(10_000) {
            initializer.state.first { it is KcefState.Failed } as KcefState.Failed
        }
    }

    private fun awaitCalls(calls: AtomicInteger, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (calls.get() < expected) {
            if (System.nanoTime() > deadline) fail("the lock was called ${calls.get()} time(s), expected $expected")
            Thread.sleep(10)
        }
    }

    @Test
    fun `missing profile directory fails with an explicit message and never locks`() {
        val lockCalls = AtomicInteger()
        val initializer = initializer(profileDir = null) {
            lockCalls.incrementAndGet()
            LockResult.Busy
        }

        initializer.initialize()

        val failed = awaitFailed(initializer)
        assertEquals("Le dossier du profil du navigateur n'a pas pu être déterminé.", failed.reason)
        assertEquals("Impossible d'initialiser Chromium", failed.title)
        assertEquals(0, lockCalls.get())
        assertFalse(chromiumStarted.get(), "Chromium must not start without a profile directory")
    }

    @Test
    fun `profile held by another instance fails with an explicit message and skips Chromium`() {
        val initializer = initializer(profileDir = File(tempDir, "browser")) { LockResult.Busy }

        initializer.initialize()

        val failed = awaitFailed(initializer)
        assertEquals(
            "Le navigateur intégré est déjà utilisé par une autre instance de NextSH, " +
                "peut-être réduite dans la zone de notification. " +
                "Quittez-la, puis rouvrez le navigateur.",
            failed.reason,
        )
        assertEquals("Navigateur intégré indisponible", failed.title)
        assertFalse(chromiumStarted.get(), "Chromium must not start while the profile is busy")
    }

    @Test
    fun `a busy profile can be retried without restarting the app`() {
        val lockCalls = AtomicInteger()
        val initializer = initializer(profileDir = File(tempDir, "browser")) {
            lockCalls.incrementAndGet()
            LockResult.Busy
        }

        initializer.initialize()
        awaitFailed(initializer)
        assertEquals(1, lockCalls.get())

        // Second activation (the user reopened the browser) with the other instance
        // still alive: the lock is asked again, and the state ends up Failed again.
        initializer.initialize()
        awaitCalls(lockCalls, expected = 2)
        val failedAgain = awaitFailed(initializer)

        assertEquals(2, lockCalls.get())
        assertEquals("Navigateur intégré indisponible", failedAgain.title)
        assertFalse(chromiumStarted.get(), "Chromium must not start while the profile is busy")
    }

    // ── configureChromiumCommandLine ──────────────────────────────────────────

    @Test
    fun `command line keeps the existing switches and pins the profile directory`() {
        val profileDir = File(tempDir, "browser")
        val commandLine = mockk<CefCommandLine>(relaxed = true)

        configureChromiumCommandLine(commandLine, profileDir)

        verifySequence {
            commandLine.appendSwitch("disable-gpu")
            commandLine.appendSwitch("disable-gpu-compositing")
            commandLine.appendSwitch("disable-gpu-rasterization")
            commandLine.appendSwitch("disable-software-rasterizer")
            commandLine.appendSwitch("disable-webgl")
            commandLine.appendSwitchWithValue("disable-features", "PaintHolding,BackForwardCache")
            commandLine.appendSwitchWithValue("user-data-dir", profileDir.canonicalPath)
        }
    }
}
