// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.browser

import fr.techtical.nextsh.desktop.captureLogs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * When [KcefInitializer] publishes [KcefState.Ready]: only once Chromium is really
 * initialized, and never after the engine reported an error or a required restart.
 *
 * These tests take the Acquired path of the lock and run the whole `initialize()`
 * flow, which is safe only because every external dependency is a fake: the lock
 * lambda answers Acquired without touching the disk, the engine starter is a fake
 * that records its call (so the real `KCEF.init` is never used), and the readiness
 * wait is a lambda (so `CefApp` is never touched).
 */
class KcefInitializerReadyTest {

    private lateinit var tempDir: File
    private lateinit var scope: CoroutineScope

    private val lockCalls = AtomicInteger()
    private val starterCalls = AtomicInteger()
    private val readyCalls = AtomicInteger()

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("nextsh-kcef-ready-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        tempDir.deleteRecursively()
    }

    private fun newInitializer(
        starter: suspend (onError: (Throwable?) -> Unit, onRestartRequired: () -> Unit) -> Unit,
        awaitEngineReady: suspend () -> Boolean,
    ) = KcefInitializer(
        bundleDir = File(tempDir, "kcef"),
        profileDir = File(tempDir, "browser"),
        coroutineScope = scope,
        lockProfile = {
            lockCalls.incrementAndGet()
            LockResult.Acquired
        },
        chromiumStarter = ChromiumStarter { _, onError, onRestartRequired ->
            starterCalls.incrementAndGet()
            starter(onError, onRestartRequired)
        },
        awaitEngineReady = {
            readyCalls.incrementAndGet()
            awaitEngineReady()
        },
    )

    /** Waits for the whole initialize() coroutine to finish, so assertions are deterministic. */
    private fun awaitInitializeDone() = runBlocking {
        withTimeout(10_000) {
            scope.coroutineContext.job.children.toList().forEach { it.join() }
        }
    }

    @Test
    fun `Ready is published only after the engine is really initialized`() {
        val stateWhileWaiting = AtomicReference<KcefState>()
        lateinit var initializer: KcefInitializer
        initializer = newInitializer(
            starter = { _, _ -> },
            awaitEngineReady = {
                stateWhileWaiting.set(initializer.state.value)
                true
            },
        )

        initializer.initialize()
        awaitInitializeDone()

        assertEquals(KcefState.Ready, initializer.state.value)
        assertIs<KcefState.Initializing>(stateWhileWaiting.get(), "Ready must not be visible while waiting")
        assertEquals(1, lockCalls.get())
        assertEquals(1, starterCalls.get())
        assertEquals(1, readyCalls.get())
    }

    @Test
    fun `an engine that never initializes ends in Failed with an explicit message`() {
        val initializer = newInitializer(starter = { _, _ -> }, awaitEngineReady = { false })

        initializer.initialize()
        awaitInitializeDone()

        val failed = assertIs<KcefState.Failed>(initializer.state.value)
        assertEquals(KcefFailure.Timeout, failed.failure)
        assertEquals(1, starterCalls.get())
        assertEquals(1, readyCalls.get())
    }

    @Test
    fun `an engine error stays Failed and the readiness is never awaited`() {
        val initializer = newInitializer(
            starter = { onError, _ -> onError(RuntimeException("x")) },
            awaitEngineReady = { true },
        )

        initializer.initialize()
        awaitInitializeDone()

        val failed = assertIs<KcefState.Failed>(initializer.state.value)
        assertEquals(KcefFailure.EngineError, failed.failure)
        assertEquals("x", failed.detail)
        assertEquals(1, starterCalls.get())
        assertEquals(0, readyCalls.get())
    }

    @Test
    fun `a required restart stays Failed and the readiness is never awaited`() {
        val initializer = newInitializer(
            starter = { _, onRestartRequired -> onRestartRequired() },
            awaitEngineReady = { true },
        )

        initializer.initialize()
        awaitInitializeDone()

        val failed = assertIs<KcefState.Failed>(initializer.state.value)
        assertEquals(KcefFailure.RestartRequired, failed.failure)
        assertEquals(1, starterCalls.get())
        assertEquals(0, readyCalls.get())
    }

    @Test
    fun `an exception thrown by the engine ends in Failed and the readiness is never awaited`() {
        val initializer = newInitializer(
            starter = { _, _ -> throw IllegalStateException("boom") },
            awaitEngineReady = { true },
        )

        initializer.initialize()
        awaitInitializeDone()

        val failed = assertIs<KcefState.Failed>(initializer.state.value)
        assertEquals(KcefFailure.EngineError, failed.failure)
        assertEquals("boom", failed.detail)
        assertEquals(0, readyCalls.get())
    }

    @Test
    fun `an engine failure reaches the state with its message but the log keeps only the exception class`() {
        val secretish = "bundle unreadable at corpshare-backup"
        val viaCallback = newInitializer(
            starter = { onError, _ -> onError(IllegalStateException(secretish)) },
            awaitEngineReady = { true },
        )
        val viaThrow = newInitializer(
            starter = { _, _ -> throw IllegalArgumentException(secretish) },
            awaitEngineReady = { true },
        )

        val lines = captureLogs {
            viaCallback.initialize()
            awaitInitializeDone()
            viaThrow.initialize()
            awaitInitializeDone()
        }

        // The user still sees the reason...
        assertEquals(secretish, assertIs<KcefState.Failed>(viaCallback.state.value).detail)
        assertEquals(secretish, assertIs<KcefState.Failed>(viaThrow.state.value).detail)
        // ...the log carries the class chain, not the message.
        assertTrue(lines.any { it.contains("KCEF init failed") && it.contains("IllegalStateException") }, "got $lines")
        assertTrue(lines.any { it.contains("KCEF init exception") && it.contains("IllegalArgumentException") }, "got $lines")
        assertTrue(lines.none { it.contains("corpshare") || it.contains("unreadable") }, "the message leaked: $lines")
    }
}
