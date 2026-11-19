// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The events of the network reach every collector, and `tryEmit` never fails because one is slow. */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkEventFlowTest {

    @Test
    fun `a tryEmit succeeds although a subscriber is stuck on the previous event`() = runTest {
        val flow = newNetworkEventFlow()
        // A collector that never finishes handling its first event: with a buffer of 1 and no
        // overflow policy the third emission already failed, for every subscriber.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { awaitCancellation() }
        }

        repeat(40) { assertTrue(flow.tryEmit(NetworkEvent.Connected), "emission $it") }
    }

    @Test
    fun `a healthy subscriber keeps getting the events while another is stuck`() = runTest {
        val flow = newNetworkEventFlow()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { awaitCancellation() }
        }
        val seen = mutableListOf<NetworkEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            flow.collect { seen += it }
        }

        flow.tryEmit(NetworkEvent.Lost)
        flow.tryEmit(NetworkEvent.Connected)
        flow.tryEmit(NetworkEvent.Connected)

        assertEquals(listOf(NetworkEvent.Lost, NetworkEvent.Connected, NetworkEvent.Connected), seen)
    }
}
