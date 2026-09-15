// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.core.network

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DesktopNetworkMonitor].
 *
 * The `networkPredicate` constructor parameter makes the monitor deterministic:
 * no real `NetworkInterface` probing is needed. The test scheduler's virtual
 * time makes polling cycles explicit via [advanceTimeBy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopNetworkMonitorTest {

    @Test
    fun `initial isConnected reflects predicate`() {
        val alwaysTrue = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 100L)
        assertTrue(alwaysTrue.isConnected.value)

        val alwaysFalse = DesktopNetworkMonitor(networkPredicate = { false }, pollIntervalMs = 100L)
        assertFalse(alwaysFalse.isConnected.value)
    }

    @Test
    fun `polling transitions isConnected when predicate result changes`() = runTest(UnconfinedTestDispatcher()) {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        var connected = true
        val monitor = DesktopNetworkMonitor(
            networkPredicate = { connected },
            pollIntervalMs = 100L,
        )
        monitor.start(testScope)

        // Initial value
        assertTrue(monitor.isConnected.first())

        // Flip predicate and advance past one poll cycle
        connected = false
        advanceTimeBy(250L)
        assertFalse(monitor.isConnected.value)

        // Flip back
        connected = true
        advanceTimeBy(250L)
        assertTrue(monitor.isConnected.value)

        monitor.stop()
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `stop cancels the polling job`() = runTest(UnconfinedTestDispatcher()) {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        var connected = true
        val monitor = DesktopNetworkMonitor(
            networkPredicate = { connected },
            pollIntervalMs = 50L,
        )
        monitor.start(testScope)
        advanceTimeBy(120L)
        monitor.stop()

        // After stop, flipping the predicate should NOT be reflected in the flow.
        connected = false
        advanceTimeBy(500L)
        assertTrue(monitor.isConnected.value, "monitor must stay on the last value after stop")
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `start is idempotent`() = runTest(UnconfinedTestDispatcher()) {
        val testScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + SupervisorJob())
        val monitor = DesktopNetworkMonitor(networkPredicate = { true }, pollIntervalMs = 100L)
        monitor.start(testScope)
        monitor.start(testScope)
        monitor.start(testScope)
        // No assertion on internal jobs, just verify nothing throws and the
        // monitor is still functional.
        advanceTimeBy(150L)
        assertEquals(true, monitor.isConnected.value)
        monitor.stop()
        testScope.coroutineContext[Job]?.cancel()
    }

    @Test
    fun `defaultPredicate does not throw on real network`() {
        // Smoke test: the default predicate is allowed to return true or false,
        // but must not throw on the JVM this test runs on. We never assert the
        // value (CI runners might have no network up).
        DesktopNetworkMonitor.defaultPredicate()
    }
}
