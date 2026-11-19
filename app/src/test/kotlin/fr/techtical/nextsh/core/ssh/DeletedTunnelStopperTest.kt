// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.SshErrorCode
import android.util.Log
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType
import fr.techtical.nextsh.service.DeletedTunnelNotifier
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.testutil.RecordingTree
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import timber.log.Timber

class DeletedTunnelStopperTest {

    private lateinit var tunnelManager: SshTunnelManager
    private lateinit var notifier: DeletedTunnelNotifier
    private lateinit var stopper: DeletedTunnelStopper

    @BeforeEach
    fun setUp() {
        tunnelManager = mockk()
        notifier = mockk(relaxed = true)
        // By default a tunnel is in a state that was seen running (ACTIVE).
        every { tunnelManager.getTunnelState(any()) } answers {
            TunnelState(tunnel(firstArg()), TunnelStatus.ACTIVE)
        }
        stopper = DeletedTunnelStopper(tunnelManager, notifier)
    }

    private fun tunnel(id: String) = TunnelConfig(
        id = id,
        label = "tunnel-$id",
        hostId = "h1",
        type = TunnelType.LOCAL_FORWARD,
        localPort = 8080,
        remoteHost = "127.0.0.1",
        remotePort = 80,
    )

    @Test
    fun `every tunnel is stopped through the manager and counted`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)

        val stopped = stopper.stop(listOf(tunnel("a"), tunnel("b")))

        assertEquals(2, stopped)
        coVerify(exactly = 1) { tunnelManager.stopTunnel("a") }
        coVerify(exactly = 1) { tunnelManager.stopTunnel("b") }
    }

    @Test
    fun `a tunnel another path already stopped is not counted`() = runTest {
        coEvery { tunnelManager.stopTunnel("a") } returns SshResult.Success(Unit)
        coEvery { tunnelManager.stopTunnel("b") } returns SshResult.Error(SshErrorCode.UNKNOWN, "introuvable")

        assertEquals(1, stopper.stop(listOf(tunnel("a"), tunnel("b"))))
    }

    @Test
    fun `nothing to stop, nothing called`() = runTest {
        assertEquals(0, stopper.stop(emptyList()))
        coVerify(exactly = 0) { tunnelManager.stopTunnel(any()) }
        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    // ── The user is told, once, for the tunnels that really stopped ─────────────────

    @Test
    fun `the tunnels stopped together are announced in one notification`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)

        stopper.stop(listOf(tunnel("a"), tunnel("b"), tunnel("c")))

        verify(exactly = 1) { notifier.notifyStopped(listOf("tunnel-a", "tunnel-b", "tunnel-c")) }
    }

    @Test
    fun `a tunnel that did not stop is not announced`() = runTest {
        coEvery { tunnelManager.stopTunnel("a") } returns SshResult.Success(Unit)
        coEvery { tunnelManager.stopTunnel("b") } returns SshResult.Error(SshErrorCode.UNKNOWN, "introuvable")

        stopper.stop(listOf(tunnel("a"), tunnel("b")))

        verify(exactly = 1) { notifier.notifyStopped(listOf("tunnel-a")) }
    }

    @Test
    fun `nothing stopped, nothing announced`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Error(SshErrorCode.UNKNOWN, "introuvable")

        assertEquals(0, stopper.stop(listOf(tunnel("a"))))

        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    @Test
    fun `a notification that fails does not fail the stop`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        every { notifier.notifyStopped(any()) } throws IllegalStateException("no channel")

        assertEquals(1, stopper.stop(listOf(tunnel("a"))))
    }

    // ── Only a tunnel that was really running is announced ──────────────────────────

    private fun stateOf(id: String, status: TunnelStatus) {
        every { tunnelManager.getTunnelState(id) } returns TunnelState(tunnel(id), status)
    }

    @Test
    fun `a tunnel still starting is stopped but not announced`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        stateOf("a", TunnelStatus.STARTING)

        val stopped = stopper.stop(listOf(tunnel("a")))

        assertEquals(1, stopped, "it is stopped")
        coVerify(exactly = 1) { tunnelManager.stopTunnel("a") }
        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    @Test
    fun `a tunnel in error is stopped silently`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        stateOf("a", TunnelStatus.ERROR)

        val stopped = stopper.stop(listOf(tunnel("a")))

        assertEquals(1, stopped, "it is stopped")
        coVerify(exactly = 1) { tunnelManager.stopTunnel("a") }
        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    @Test
    fun `an active and a reconnecting tunnel are both announced`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        stateOf("active", TunnelStatus.ACTIVE)
        stateOf("reconnecting", TunnelStatus.RECONNECTING)

        stopper.stop(listOf(tunnel("active"), tunnel("reconnecting")))

        verify(exactly = 1) { notifier.notifyStopped(listOf("tunnel-active", "tunnel-reconnecting")) }
    }

    @Test
    fun `in a mixed batch only the tunnels that were running are announced`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        stateOf("starting", TunnelStatus.STARTING)
        stateOf("active", TunnelStatus.ACTIVE)
        stateOf("reconnecting", TunnelStatus.RECONNECTING)
        stateOf("error", TunnelStatus.ERROR)

        val stopped = stopper.stop(
            listOf(tunnel("starting"), tunnel("active"), tunnel("reconnecting"), tunnel("error")),
        )

        assertEquals(4, stopped, "all four are stopped")
        coVerify(exactly = 1) { tunnelManager.stopTunnel("starting") }
        coVerify(exactly = 1) { tunnelManager.stopTunnel("error") }
        verify(exactly = 1) { notifier.notifyStopped(listOf("tunnel-active", "tunnel-reconnecting")) }
    }

    @Test
    fun `the state is read before the stop removes it`() = runTest {
        // As the real manager does: the state is gone once the tunnel is stopped.
        var stopDone = false
        every { tunnelManager.getTunnelState("a") } answers {
            if (stopDone) null else TunnelState(tunnel("a"), TunnelStatus.STARTING)
        }
        coEvery { tunnelManager.stopTunnel("a") } coAnswers {
            stopDone = true
            SshResult.Success(Unit)
        }

        stopper.stop(listOf(tunnel("a")))

        verify(exactly = 0) { notifier.notifyStopped(any()) }
    }

    // ── Failed stops are counted in the log, never named ────────────────────────────

    @Test
    fun `failed stops are logged as a count without any label`() = runTest {
        coEvery { tunnelManager.stopTunnel("a") } returns SshResult.Success(Unit)
        coEvery { tunnelManager.stopTunnel("b") } returns SshResult.Error(SshErrorCode.UNKNOWN, "introuvable")
        coEvery { tunnelManager.stopTunnel("c") } returns SshResult.Error(SshErrorCode.UNKNOWN, "teardown failed")
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            stopper.stop(listOf(tunnel("a"), tunnel("b"), tunnel("c")))
        } finally {
            Timber.uproot(tree)
        }

        val warnings = tree.entries.filter { it.priority == Log.WARN }.map { it.message }
        assertEquals(listOf("DeletedTunnelStopper: 2 stop(s) failed"), warnings)
        assertTrue(
            tree.entries.none { entry -> listOf("tunnel-a", "tunnel-b", "tunnel-c").any { it in entry.message } },
            "no label in the log",
        )
    }

    @Test
    fun `no failure, no warning`() = runTest {
        coEvery { tunnelManager.stopTunnel(any()) } returns SshResult.Success(Unit)
        val tree = RecordingTree()
        Timber.plant(tree)
        try {
            stopper.stop(listOf(tunnel("a")))
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(tree.entries.none { it.priority == Log.WARN })
    }
}
