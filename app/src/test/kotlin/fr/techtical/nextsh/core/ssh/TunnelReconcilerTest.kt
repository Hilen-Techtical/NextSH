// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.AuthType
import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.LiveTunnelLookup
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [TunnelReconciler]: running tunnels whose row or host was deleted elsewhere are stopped, and
 * only after the database confirms it. All collaborators are mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunnelReconcilerTest {

    private lateinit var tunnelManager: SshTunnelManager
    private lateinit var tunnelRepository: TunnelRepository
    private lateinit var hostRepository: HostRepository
    private lateinit var liveTunnels: LiveTunnelLookup
    private lateinit var stopper: DeletedTunnelStopper
    private lateinit var reconciler: TunnelReconciler

    private val states = MutableStateFlow<Map<String, TunnelState>>(emptyMap())
    private val tunnelRows = MutableStateFlow<List<TunnelConfig>>(emptyList())
    private val hostRows = MutableStateFlow<List<Host>>(emptyList())

    /** How many collectors of each table are active right now. */
    private var tunnelObservers = 0
    private var hostObservers = 0

    @BeforeEach
    fun setUp() {
        tunnelManager = mockk()
        tunnelRepository = mockk()
        hostRepository = mockk()
        liveTunnels = mockk()
        stopper = mockk()
        every { tunnelManager.tunnelStates } returns states
        every { tunnelRepository.observeAll() } returns
            tunnelRows.onStart { tunnelObservers++ }.onCompletion { tunnelObservers-- }
        every { hostRepository.observeAll() } returns
            hostRows.onStart { hostObservers++ }.onCompletion { hostObservers-- }
        coEvery { stopper.stop(any()) } answers { firstArg<List<TunnelConfig>>().size }
        reconciler = TunnelReconciler(tunnelManager, tunnelRepository, hostRepository, liveTunnels, stopper)
    }

    private fun tunnel(id: String, hostId: String = "h1") = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = hostId,
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    private fun host(id: String) = Host(
        id = id,
        label = "host-$id",
        hostname = "$id.example.org",
        username = "root",
        authType = AuthType.PASSWORD,
        credentialId = "cred-$id",
    )

    private fun running(vararg configs: TunnelConfig, status: TunnelStatus = TunnelStatus.ACTIVE) =
        configs.associate { it.id to TunnelState(it, status) }

    // ── One pass ────────────────────────────────────────────────────────────────────

    @Test
    fun `a running tunnel whose row is gone is stopped once the database confirms it`() = runTest {
        val t1 = tunnel("t1")
        coEvery { liveTunnels.find("t1") } returns null
        coEvery { liveTunnels.isHostLive("h1") } returns true

        reconciler.reconcilePass(running(t1), tunnels = emptyList(), hosts = listOf(host("h1")))

        coVerify(exactly = 1) { stopper.stop(listOf(t1)) }
    }

    @Test
    fun `a running tunnel whose host is gone is stopped, ERROR state included`() = runTest {
        val t1 = tunnel("t1")
        coEvery { liveTunnels.find("t1") } returns null

        reconciler.reconcilePass(
            running(t1, status = TunnelStatus.ERROR),
            tunnels = listOf(t1),
            hosts = emptyList(),
        )

        coVerify(exactly = 1) { stopper.stop(listOf(t1)) }
    }

    @Test
    fun `a tunnel started on a host that is gone is stopped even if its row now points elsewhere`() = runTest {
        // Edited to another host while it ran: the stored row is fine, the frozen config is not.
        val frozen = tunnel("t1", hostId = "h-old")
        val stored = tunnel("t1", hostId = "h-new")
        coEvery { liveTunnels.find("t1") } returns stored
        coEvery { liveTunnels.isHostLive("h-old") } returns false

        reconciler.reconcilePass(running(frozen), tunnels = listOf(stored), hosts = listOf(host("h-new")))

        coVerify(exactly = 1) { stopper.stop(listOf(frozen)) }
    }

    @Test
    fun `a healthy tunnel is neither looked up nor stopped`() = runTest {
        val t1 = tunnel("t1")

        reconciler.reconcilePass(running(t1), tunnels = listOf(t1), hosts = listOf(host("h1")))

        coVerify(exactly = 0) { liveTunnels.find(any()) }
        coVerify(exactly = 0) { stopper.stop(any()) }
    }

    @Test
    fun `a stale snapshot is not trusted, the database has the last word`() = runTest {
        val t1 = tunnel("t1")
        // The tables flow says the row is missing, the database says it is there.
        coEvery { liveTunnels.find("t1") } returns t1
        coEvery { liveTunnels.isHostLive("h1") } returns true

        reconciler.reconcilePass(running(t1), tunnels = emptyList(), hosts = listOf(host("h1")))

        coVerify(exactly = 1) { liveTunnels.find("t1") }
        coVerify(exactly = 0) { stopper.stop(any()) }
    }

    @Test
    fun `only the tunnels that are gone are stopped`() = runTest {
        val kept = tunnel("kept")
        val gone = tunnel("gone")
        coEvery { liveTunnels.find("gone") } returns null

        reconciler.reconcilePass(running(kept, gone), tunnels = listOf(kept), hosts = listOf(host("h1")))

        coVerify(exactly = 1) { stopper.stop(listOf(gone)) }
        coVerify(exactly = 0) { liveTunnels.find("kept") }
    }

    @Test
    fun `a failing pass is swallowed`() = runTest {
        coEvery { liveTunnels.find(any()) } throws IllegalStateException("database closed")

        reconciler.reconcilePass(running(tunnel("t1")), tunnels = emptyList(), hosts = emptyList())

        coVerify(exactly = 0) { stopper.stop(any()) }
    }

    @Test
    fun `a cancellation is not swallowed`() = runTest {
        coEvery { liveTunnels.find(any()) } throws CancellationException("cancelled")

        var rethrown = false
        try {
            reconciler.reconcilePass(running(tunnel("t1")), tunnels = emptyList(), hosts = emptyList())
        } catch (e: CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
    }

    // ── Observation ─────────────────────────────────────────────────────────────────

    @Test
    fun `nothing is observed while no tunnel is known`() = runTest {
        backgroundScope.launch { reconciler.observe() }
        runCurrent()

        assertEquals(0, tunnelObservers)
        assertEquals(0, hostObservers)
        coVerify(exactly = 0) { stopper.stop(any()) }
    }

    @Test
    fun `the tables are observed while a tunnel is known and released after the last one`() = runTest {
        val t1 = tunnel("t1")
        tunnelRows.value = listOf(t1)
        hostRows.value = listOf(host("h1"))
        backgroundScope.launch { reconciler.observe() }
        runCurrent()
        assertEquals(0, tunnelObservers)

        states.value = running(t1)
        runCurrent()
        assertEquals(1, tunnelObservers)
        assertEquals(1, hostObservers)

        states.value = emptyMap()
        runCurrent()
        assertEquals(0, tunnelObservers, "no database observer without a tunnel")
        assertEquals(0, hostObservers)
    }

    @Test
    fun `a tunnel deleted elsewhere while it runs is stopped`() = runTest {
        val t1 = tunnel("t1")
        tunnelRows.value = listOf(t1)
        hostRows.value = listOf(host("h1"))
        states.value = running(t1)
        coEvery { liveTunnels.find("t1") } returns null
        coEvery { liveTunnels.isHostLive("h1") } returns true
        backgroundScope.launch { reconciler.observe() }
        runCurrent()
        coVerify(exactly = 0) { stopper.stop(any()) }

        // A sync tombstones the row: the observed list loses it.
        tunnelRows.value = emptyList()
        runCurrent()

        coVerify(exactly = 1) { stopper.stop(listOf(t1)) }
    }

    @Test
    fun `a host deleted elsewhere while its tunnel runs stops the tunnel`() = runTest {
        val t1 = tunnel("t1")
        tunnelRows.value = listOf(t1)
        hostRows.value = listOf(host("h1"))
        states.value = running(t1)
        coEvery { liveTunnels.find("t1") } returns null
        backgroundScope.launch { reconciler.observe() }
        runCurrent()

        hostRows.value = emptyList()
        runCurrent()

        coVerify(exactly = 1) { stopper.stop(listOf(t1)) }
    }

    @Test
    fun `a failed pass does not end the observation`() = runTest {
        val t1 = tunnel("t1")
        tunnelRows.value = listOf(t1)
        hostRows.value = listOf(host("h1"))
        states.value = running(t1)
        coEvery { liveTunnels.find("t1") } throws IllegalStateException("database closed") andThen null
        backgroundScope.launch { reconciler.observe() }
        runCurrent()

        tunnelRows.value = emptyList()
        runCurrent()
        coVerify(exactly = 0) { stopper.stop(any()) }

        // The next change runs a new pass, which works.
        hostRows.value = listOf(host("h1"), host("h2"))
        runCurrent()

        coVerify(exactly = 1) { stopper.stop(listOf(t1)) }
    }
}
