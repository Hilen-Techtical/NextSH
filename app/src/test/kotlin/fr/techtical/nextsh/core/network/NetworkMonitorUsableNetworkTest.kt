// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [NetworkMonitor.hasUsableNetwork] is the offline gate of the tunnel reconnection: true as soon as the
 * ACTIVE network can carry a connection, whatever else is up or lost, and without asking the system to
 * have validated it (a Wi-Fi without Internet still routes to an SSH server on the local network).
 */
class NetworkMonitorUsableNetworkTest {

    private val network = mockk<Network>()

    private fun capabilities(internet: Boolean, validated: Boolean = false) = mockk<NetworkCapabilities> {
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns internet
        every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns validated
    }

    @Test
    fun `an active network with the internet capability is usable, validated or not`() {
        for (validated in listOf(true, false)) {
            val manager = mockk<ConnectivityManager> {
                every { activeNetwork } returns network
                every { getNetworkCapabilities(network) } returns capabilities(internet = true, validated = validated)
            }

            assertTrue(manager.activeNetworkHasInternet(), "validated=$validated")
        }
    }

    @Test
    fun `the validation of the network is never asked for`() {
        val caps = capabilities(internet = true, validated = false)
        val manager = mockk<ConnectivityManager> {
            every { activeNetwork } returns network
            every { getNetworkCapabilities(network) } returns caps
        }

        assertTrue(manager.activeNetworkHasInternet())

        verify(exactly = 0) { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
    }

    @Test
    fun `no active network is no network`() {
        val manager = mockk<ConnectivityManager> {
            every { activeNetwork } returns null
        }

        assertFalse(manager.activeNetworkHasInternet())
    }

    @Test
    fun `an active network without the internet capability is no network`() {
        val manager = mockk<ConnectivityManager> {
            every { activeNetwork } returns network
            every { getNetworkCapabilities(network) } returns capabilities(internet = false)
        }

        assertFalse(manager.activeNetworkHasInternet())
    }

    @Test
    fun `an active network whose capabilities are unknown is no network`() {
        val manager = mockk<ConnectivityManager> {
            every { activeNetwork } returns network
            every { getNetworkCapabilities(network) } returns null
        }

        assertFalse(manager.activeNetworkHasInternet())
    }

    @Test
    fun `a lookup that throws is no network, not a crash of the reconnection`() {
        val manager = mockk<ConnectivityManager> {
            every { activeNetwork } throws SecurityException("no permission")
        }

        assertFalse(manager.activeNetworkHasInternet())
    }
}
