// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.shared.domain.model.AuthType
import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelStatus
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.util.AppScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DesktopTunnelService.onVaultWiped]: every tunnel stops for good before the vault and the database are
 * erased, nothing reconnects afterwards, and the user is not told that a tunnel was "deleted on another
 * device". Real time (`runBlocking`), like [DesktopTunnelServiceTest]: the network monitor polls in a loop.
 */
class DesktopTunnelServiceVaultWipeTest {

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    private val host = Host(
        id = "host-1", label = "h1", hostname = "a.example.com", port = 22,
        username = "u", authType = AuthType.PASSWORD, credentialId = "c1",
    )

    private fun tunnel(id: String) = TunnelConfig(
        id = id, label = id, hostId = host.id,
        type = TunnelType.LOCAL_FORWARD,
        localPort = ServerSocket(0).use { it.localPort }, remoteHost = "127.0.0.1", remotePort = 80,
        autoStart = true,
    )

    private fun mockClient(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns mockk<LocalPortForwarder>(relaxed = true)
        return client
    }

    private inner class Rig(configs: List<TunnelConfig>) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val online = AtomicBoolean(true)
        val factoryCalls = AtomicInteger(0)
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var uncancellable = false
        val rows = MutableStateFlow(configs)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalls.incrementAndGet()
            if (uncancellable) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { gate?.await() } else gate?.await()
            if (!online.get()) SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "offline") else SshResult.Success(mockClient())
        }
        private val tunnelRepository = mockk<TunnelRepository>(relaxed = true).also { repo ->
            every { repo.observeAll() } returns rows
            coEvery { repo.getById(any()) } answers { rows.value.firstOrNull { it.id == firstArg<String>() } }
            coEvery { repo.getAutoStartTunnels() } answers { rows.value.filter { it.autoStart } }
        }
        private val hostRepository = mockk<HostRepository>(relaxed = true).also { repo ->
            every { repo.observeAll() } returns MutableStateFlow(listOf(host))
            coEvery { repo.getById(any()) } returns host
        }
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { online.get() }, pollIntervalMs = 50L),
            tunnelRepository = tunnelRepository,
            hostRepository = hostRepository,
            appScope = TestAppScope(scope),
        )

        fun status(id: String): TunnelStatus? = manager.getTunnelState(id)?.status

        suspend fun close() {
            service.onAppExitSuspend()
            scope.cancel()
        }
    }

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25L)
        }
        return condition()
    }

    @Test
    fun `onVaultWiped stops active tunnels and forgets their states`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1"), tunnel("t2")))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE && rig.status("t2") == TunnelStatus.ACTIVE })

        rig.service.onVaultWiped()

        assertNull(rig.manager.getTunnelState("t1"))
        assertNull(rig.manager.getTunnelState("t2"))
        assertTrue(rig.service.tunnelStates.value.isEmpty())
        rig.close()
    }

    @Test
    fun `a tunnel that was reconnecting is stopped and never reconnects afterwards`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1")))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        rig.online.set(false)
        assertTrue(waitFor(3_000L) { rig.status("t1") == TunnelStatus.RECONNECTING })

        rig.service.onVaultWiped()
        val callsAfterWipe = rig.factoryCalls.get()
        // The network comes back and the backoff of a live loop (1 s) would elapse: nothing may start.
        rig.online.set(true)
        delay(1_800L)

        assertEquals(callsAfterWipe, rig.factoryCalls.get(), "no reconnect attempt after the wipe")
        assertNull(rig.manager.getTunnelState("t1"))
        rig.close()
    }

    @Test
    fun `no deleted elsewhere announcement is made, even when the rows disappear after`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1")))
        val announced = CopyOnWriteArrayList<Any>()
        val collector = rig.scope.launch { rig.service.deletionStops.collect { announced += it } }
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })

        rig.service.onVaultWiped()
        // What the wipe does to the database a moment later: the rows vanish.
        rig.rows.value = emptyList()
        delay(500L)

        assertTrue(announced.isEmpty(), "the user asked for the wipe, no tunnel is reported as deleted elsewhere")
        assertTrue(rig.service.deletionNotice.value.isEmpty())
        collector.cancel()
        rig.close()
    }

    @Test
    fun `a tunnel still starting during the wipe never becomes active and leaves no error behind`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1")))
        val gate = CompletableDeferred<Unit>()
        rig.gate = gate
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.STARTING }, "the auto-start is in its handshake")

        rig.service.onVaultWiped()
        gate.complete(Unit) // the handshake comes back after the wipe
        delay(700L)

        val after = rig.status("t1")
        assertTrue(after != TunnelStatus.ACTIVE && after != TunnelStatus.ERROR, "state after the wipe: $after")
        rig.close()
    }

    @Test
    fun `an auto-start stuck in a handshake that ignores the cancellation does not hold the wipe for ever`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1")))
        val gate = CompletableDeferred<Unit>()
        rig.gate = gate
        rig.uncancellable = true
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.factoryCalls.get() == 1 }, "the handshake is blocked")

        val started = System.currentTimeMillis()
        rig.service.onVaultWiped(autoStartJoinTimeoutMs = 300L) // cancel, stop, then a BOUNDED join: the loop never ends by itself
        val elapsed = System.currentTimeMillis() - started

        assertTrue(elapsed < 3_000L, "the wipe went on after the bounded wait, it took $elapsed ms")
        gate.complete(Unit)
        delay(500L)
        assertTrue(rig.status("t1") != TunnelStatus.ACTIVE)
        rig.close()
    }

    @Test
    fun `no start is accepted after the wipe until the next unlock`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(listOf(config))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        rig.service.onVaultWiped()
        val calls = rig.factoryCalls.get()

        val refused = rig.service.startTunnel(config)

        assertTrue(refused is SshResult.Error)
        assertEquals(calls, rig.factoryCalls.get(), "the factory is not even asked")
        assertNull(rig.manager.getTunnelState("t1"))
        rig.close()
    }

    @Test
    fun `a later unlock arms the watches and the auto-start again`() = runBlocking {
        val rig = Rig(listOf(tunnel("t1")))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        rig.service.onVaultWiped()
        assertNull(rig.manager.getTunnelState("t1"))

        // The first session after the new vault: the unlock path is not a no-op any more.
        rig.service.onVaultUnlocked()

        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE }, "started is back to false")
        rig.close()
    }
}
