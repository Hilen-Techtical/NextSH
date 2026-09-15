// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.sync

import fr.techtical.nextsh.desktop.data.preferences.DesktopSettingsStore
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import java.net.SocketException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit test for [LanSyncServerLifecycle]: the B3 self-heal retry/backoff and gating.
 *
 * A [FakeSyncServer] stands in for the real [LanSyncServer]: it records the number
 * of [start] calls and lets each test script what [start] transitions the state to
 * (Listening = success, Error = transient failure). The lifecycle's retry coroutine
 * runs on the [TestScope] so [advanceUntilIdle] drains the backoff delays virtually.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LanSyncServerLifecycleTest {

    /**
     * Fake [SyncServerControl] driven by a scripted list of outcomes: one per
     * [start] call. Each outcome sets the state synchronously, mirroring how the
     * real server settles to Listening or Error (the lifecycle's await-settled loop
     * just observes the resulting state).
     */
    private class FakeSyncServer(
        private val outcomes: List<LanSyncServer.State>,
    ) : SyncServerControl {
        private val _state = MutableStateFlow<LanSyncServer.State>(LanSyncServer.State.Stopped)
        override val state: StateFlow<LanSyncServer.State> = _state.asStateFlow()

        var startCount = 0
            private set
        var stopCount = 0
            private set
        var ensureDiscoveryCount = 0
            private set

        /** Scripted outcomes consumed so far: only advanced when a bind actually happens. */
        private var consumed = 0

        override fun ensureDiscoveryRunning() {
            ensureDiscoveryCount++
        }

        override fun start(shouldRun: () -> Boolean) {
            startCount++
            // Honour the predicate exactly like the real server's at-bind re-check: if the gate is
            // closed at the moment of bind, the endpoint is never exposed (state stays Stopped) and
            // no scripted outcome is burned (no bind = no settle).
            if (!shouldRun()) {
                _state.value = LanSyncServer.State.Stopped
                return
            }
            // A real bind happened: consume the next scripted outcome; once exhausted, stay Error.
            val outcome = outcomes.getOrElse(consumed) { LanSyncServer.State.Error("exhausted") }
            consumed++
            _state.value = outcome
        }

        override fun stop() {
            stopCount++
            _state.value = LanSyncServer.State.Stopped
        }
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private fun settingsStore(syncEnabled: Boolean): DesktopSettingsStore {
        val store = mockk<DesktopSettingsStore>(relaxed = true)
        every { store.syncEnabled } returns syncEnabled
        return store
    }

    /** [LanAddressDetector] is a real singleton hitting actual NetworkInterface APIs, mocked per-test for the watcher tests below. Always unmocked afterwards since mockkObject is a JVM-global transform. */
    @AfterTest
    fun unmockLanAddressDetector() {
        unmockkObject(LanAddressDetector)
    }

    private fun lanInterface(ip: String) = LanAddressDetector.LanInterface(
        address = InetAddress.getByName(ip),
        displayName = "eth-test",
    )

    @Test
    fun `onVaultUnlocked starts server when sync enabled and succeeds first try`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("192.168.1.10", 47731)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        assertEquals(1, server.startCount, "should start exactly once on first-try success")
        assertTrue(server.state.value is LanSyncServer.State.Listening)
    }

    @Test
    fun `does not start when sync disabled`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("a", 1)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = false), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        assertEquals(0, server.startCount, "must not start when sync is disabled")
    }

    @Test
    fun `retries on transient error then succeeds`() = runTest {
        // First two attempts fail, third succeeds: the lifecycle should retry through the backoff.
        val server = FakeSyncServer(
            listOf(
                LanSyncServer.State.Error("Aucune interface LAN détectée"),
                LanSyncServer.State.Error("Aucune interface LAN détectée"),
                LanSyncServer.State.Listening("192.168.1.10", 47731),
            ),
        )
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        assertEquals(3, server.startCount, "should retry until it reaches Listening")
        assertTrue(server.state.value is LanSyncServer.State.Listening)
    }

    @Test
    fun `gives up after max attempts and leaves server in error`() = runTest {
        // Always fails: the bounded loop must stop and not spin forever.
        val server = FakeSyncServer(List(20) { LanSyncServer.State.Error("bind failed") })
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        // MAX_START_ATTEMPTS == 6 in the lifecycle.
        assertEquals(6, server.startCount, "should stop after the bounded number of attempts")
        assertTrue(server.state.value is LanSyncServer.State.Error)
    }

    @Test
    fun `ensureRunning is no-op when server already listening`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("a", 1)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(1, server.startCount)

        // Already Listening → ensureRunning must not start again.
        lifecycle.ensureRunning()
        advanceUntilIdle()
        assertEquals(1, server.startCount, "ensureRunning must be idempotent when already running")
    }

    @Test
    fun `ensureRunning restarts a server that died`() = runTest {
        // Boot: first 6 attempts all fail (transient), exhausting the retry budget
        // and leaving the server in Error. Attempt 7 (driven by ensureRunning once the
        // network is up) succeeds. The scripted list provides the success at index 6.
        val outcomes = List(6) { LanSyncServer.State.Error("boot race") as LanSyncServer.State } +
            LanSyncServer.State.Listening("192.168.1.10", 47731)
        val server = FakeSyncServer(outcomes)
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(6, server.startCount, "boot retry budget exhausted")
        assertTrue(server.state.value is LanSyncServer.State.Error)

        // Network is up now: opening Settings (ensureRunning) re-checks and restarts.
        lifecycle.ensureRunning()
        advanceUntilIdle()
        assertEquals(7, server.startCount, "ensureRunning re-triggers a start when the server is down")
        assertTrue(server.state.value is LanSyncServer.State.Listening)
    }

    @Test
    fun `ensureRunning is no-op when sync disabled`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("a", 1)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = false), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        lifecycle.ensureRunning()
        advanceUntilIdle()

        assertEquals(0, server.startCount, "ensureRunning must not start when sync is disabled")
    }

    @Test
    fun `disabling sync cancels retry and stops server`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("a", 1)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(1, server.startCount)

        lifecycle.applySettingsChange(enabled = false)
        advanceUntilIdle()
        assertEquals(1, server.stopCount, "disabling sync must stop the server")
    }

    // NOTE: the HIGH-1 "gate closed at bind" regression test used to live here, driving
    // FakeSyncServer.start() directly, which only asserted that the fake honoured its own
    // scripted predicate branch, never that the production server does. It now lives in
    // LanSyncServerTest, against the real LanSyncServer.

    @Test
    fun `vault lock cancels retry and stops server`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("a", 1)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        lifecycle.onVaultLocked()
        advanceUntilIdle()
        assertEquals(1, server.stopCount, "locking the vault must stop the server")
    }

    // ── C4: network re-bind watcher ──────────────────────────────────────────
    //
    // startNetworkWatcher() launches an infinite `while (isActive) { delay(...) ; tick() }` loop
    // on the shared appScope. It is deliberately NOT exercised via advanceUntilIdle(). That
    // would never return once the watcher's own next tick is the only thing left pending on the
    // TestScope (see startNetworkWatcher's kdoc). Every test below drains virtual time with a
    // bounded advanceTimeBy()+runCurrent() instead, and stops the watcher before returning so no
    // coroutine is left dangling at the end of the test (runTest requires the main test scope to
    // fully complete).

    /** Mirrors the private NETWORK_WATCH_INTERVAL_MS tick interval in LanSyncServerLifecycle. */
    private val networkWatchIntervalMs = 20_000L

    @Test
    fun `network watcher rebinds when the detected IP changes`() = runTest {
        val server = FakeSyncServer(
            listOf(
                LanSyncServer.State.Listening("192.168.1.10", 47731),
                LanSyncServer.State.Listening("192.168.1.20", 47731),
            ),
        )
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))
        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(1, server.startCount, "initial bind")

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.20")

        lifecycle.startNetworkWatcher()
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()

        assertEquals(1, server.stopCount, "IP change must stop the stale bind")
        assertEquals(2, server.startCount, "IP change must trigger a rebind")
        val listening = server.state.value as? LanSyncServer.State.Listening
        assertEquals("192.168.1.20", listening?.addr, "the re-emitted state must carry the new address")

        lifecycle.stopNetworkWatcher()
    }

    @Test
    fun `network watcher stops the server when the LAN interface disappears, and resumes when it returns`() = runTest {
        val server = FakeSyncServer(
            listOf(
                LanSyncServer.State.Listening("192.168.1.10", 47731),
                LanSyncServer.State.Listening("192.168.1.10", 47731),
            ),
        )
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))
        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(1, server.startCount)

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns null

        lifecycle.startNetworkWatcher()
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()

        assertEquals(1, server.stopCount, "cable-unplugged tick must stop the server")
        assertEquals(1, server.startCount, "no address to bind to yet: must not attempt a restart")
        assertTrue(server.state.value is LanSyncServer.State.Stopped)

        // Network comes back on the next tick.
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.10")
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()

        assertEquals(2, server.startCount, "network back: the watcher must resume the server")
        assertTrue(server.state.value is LanSyncServer.State.Listening)

        lifecycle.stopNetworkWatcher()
    }

    @Test
    fun `network watcher does not scan the network while the gate is closed`() = runTest {
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("192.168.1.10", 47731)))
        // Vault never unlocked -> gate stays closed for the whole test.
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.99")

        lifecycle.startNetworkWatcher()
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()

        assertEquals(0, server.startCount)
        assertEquals(0, server.stopCount)
        verify(exactly = 0) { LanAddressDetector.detect() }

        lifecycle.stopNetworkWatcher()
    }

    @Test
    fun `stopNetworkWatcher is idempotent and actually halts further ticks`() = runTest {
        val server = FakeSyncServer(
            listOf(
                LanSyncServer.State.Listening("192.168.1.10", 47731),
                LanSyncServer.State.Listening("192.168.1.20", 47731),
            ),
        )
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))
        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.20")

        lifecycle.startNetworkWatcher()
        lifecycle.stopNetworkWatcher()
        lifecycle.stopNetworkWatcher() // must not throw

        advanceTimeBy(networkWatchIntervalMs * 3)
        runCurrent()

        assertEquals(1, server.startCount, "watcher stopped before its first tick: no rebind should happen")
        assertEquals(0, server.stopCount)
    }

    @Test
    fun `a throwing tick does not kill the watcher`() = runTest {
        // LanAddressDetector reads live OS state and can throw while an adapter is switching.
        // The watcher is the only self-healing path once the bounded retry budget is spent, so a
        // single failing tick must not end the loop, silently, and precisely under the degraded
        // conditions it exists for.
        val server = FakeSyncServer(
            listOf(
                LanSyncServer.State.Listening("192.168.1.10", 47731),
                LanSyncServer.State.Listening("192.168.1.20", 47731),
            ),
        )
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))
        lifecycle.onVaultUnlocked()
        advanceUntilIdle()
        assertEquals(1, server.startCount)

        mockkObject(LanAddressDetector)
        var call = 0
        every { LanAddressDetector.detect() } answers {
            call++
            if (call == 1) throw SocketException("adapter switching") else lanInterface("192.168.1.20")
        }

        lifecycle.startNetworkWatcher()

        // Tick 1 throws: nothing happens, but the loop must survive.
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()
        assertEquals(1, server.startCount, "the throwing tick must not have rebound anything")
        assertEquals(0, server.stopCount)

        // Tick 2 sees the new address and rebinds: proof the loop is still alive.
        advanceTimeBy(networkWatchIntervalMs)
        runCurrent()
        assertEquals(1, server.stopCount, "the watcher must still be running on the next tick")
        assertEquals(2, server.startCount)

        lifecycle.stopNetworkWatcher()
    }

    @Test
    fun `the watcher does not re-arm the backoff while a retry loop is still running`() = runTest {
        // startWithRetry() cancels-and-replaces, so a watcher tick calling ensureRunning() while
        // a bounded loop is mid-backoff would reset the budget every 20 s: an unbounded storm of
        // bind attempts (and firewall prompts) instead of MAX_START_ATTEMPTS.
        val server = FakeSyncServer(List(20) { LanSyncServer.State.Error("bind failed") })
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.10")

        // Deliberately NOT advanceUntilIdle(): the retry loop must still be mid-backoff when the
        // watcher ticks. Attempts land at t=0/1/3/7/15 s (1s backoff, doubling), so at t=19 s the
        // loop is idle-waiting for its 6th attempt at t=30 s.
        lifecycle.onVaultUnlocked()
        lifecycle.startNetworkWatcher()
        runCurrent() // first attempt, at t=0

        advanceTimeBy(19_000)
        runCurrent()
        val startsBeforeTick = server.startCount
        assertTrue(startsBeforeTick > 0, "the retry loop should have made attempts by now")

        // The watcher's first tick fires at t=20 s, on a server in Error with an address available.
        advanceTimeBy(1_500)
        runCurrent()

        assertEquals(
            startsBeforeTick,
            server.startCount,
            "the tick must defer to the running retry loop instead of re-arming it",
        )

        lifecycle.stopNetworkWatcher()
        advanceUntilIdle()
    }

    @Test
    fun `a steady-state tick re-checks the UDP discovery bind`() = runTest {
        // The TCP endpoint can be up while the UDP discovery bind failed (port taken, firewall
        // refusal). Nothing else would ever retry it, so the watcher's quiet tick does.
        val server = FakeSyncServer(listOf(LanSyncServer.State.Listening("192.168.1.10", 47731)))
        val lifecycle = LanSyncServerLifecycle(server, settingsStore(syncEnabled = true), TestAppScope(this))
        lifecycle.onVaultUnlocked()
        advanceUntilIdle()

        mockkObject(LanAddressDetector)
        every { LanAddressDetector.detect() } returns lanInterface("192.168.1.10") // unchanged

        lifecycle.startNetworkWatcher()
        advanceTimeBy(networkWatchIntervalMs + 500)
        runCurrent()

        assertEquals(1, server.ensureDiscoveryCount, "a steady-state tick must re-check the discovery bind")
        assertEquals(1, server.startCount, "and must not touch the TCP endpoint")
        assertEquals(0, server.stopCount)

        lifecycle.stopNetworkWatcher()
    }
}
