// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** [LiveTunnelLookup]: `getById` of both repositories hides tombstones, found means live. */
class LiveTunnelLookupTest {

    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var hostRepository: HostRepository
    private lateinit var lookup: LiveTunnelLookup

    private val tunnel = TunnelConfig(
        id = "t1",
        label = "Web",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    private val host = Host(
        id = "h1",
        label = "Prod",
        hostname = "prod.example.org",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-1",
    )

    @BeforeEach
    fun setUp() {
        tunnelRepository = mockk()
        hostRepository = mockk()
        lookup = LiveTunnelLookup(tunnelRepository, hostRepository)
    }

    @Test
    fun `a live tunnel on a live host is returned as stored`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns tunnel
        coEvery { hostRepository.getById("h1") } returns host

        assertEquals(tunnel, lookup.find("t1"))
    }

    @Test
    fun `a deleted tunnel is not found and its host is not even read`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns null

        assertNull(lookup.find("t1"))
        coVerify(exactly = 0) { hostRepository.getById(any()) }
    }

    @Test
    fun `a live tunnel under a deleted host is not found`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns tunnel
        coEvery { hostRepository.getById("h1") } returns null

        assertNull(lookup.find("t1"))
    }

    @Test
    fun `the host is read from the stored row, not from a stale copy`() = runTest {
        val moved = tunnel.copy(hostId = "h2")
        coEvery { tunnelRepository.getById("t1") } returns moved
        coEvery { hostRepository.getById("h2") } returns host.copy(id = "h2")

        assertEquals(moved, lookup.find("t1"))
        coVerify(exactly = 0) { hostRepository.getById("h1") }
    }

    @Test
    fun `findLive gives the host row that was read to check the tunnel`() = runTest {
        val optedOut = host.copy(autoReconnect = false)
        coEvery { tunnelRepository.getById("t1") } returns tunnel
        coEvery { hostRepository.getById("h1") } returns optedOut

        val live = lookup.findLive("t1")

        assertEquals(LiveTunnel(tunnel, optedOut), live)
        coVerify(exactly = 1) { hostRepository.getById("h1") }
    }

    @Test
    fun `findLive answers null for a deleted tunnel or a deleted host, like find`() = runTest {
        coEvery { tunnelRepository.getById("t1") } returns null
        assertNull(lookup.findLive("t1"))

        coEvery { tunnelRepository.getById("t1") } returns tunnel
        coEvery { hostRepository.getById("h1") } returns null
        assertNull(lookup.findLive("t1"))
    }

    @Test
    fun `isHostLive follows the host row`() = runTest {
        coEvery { hostRepository.getById("h1") } returns host
        coEvery { hostRepository.getById("gone") } returns null

        assertTrue(lookup.isHostLive("h1"))
        assertFalse(lookup.isHostLive("gone"))
    }
}
