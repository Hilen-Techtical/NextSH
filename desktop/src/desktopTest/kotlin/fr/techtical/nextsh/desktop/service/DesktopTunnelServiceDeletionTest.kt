// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.core.network.DesktopNetworkMonitor
import fr.techtical.nextsh.desktop.core.ssh.DesktopTunnelManager
import fr.techtical.nextsh.desktop.core.ssh.SshClientFactory
import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_deleted
import fr.techtical.nextsh.desktop.generated.resources.tunnels_error_unreadable
import fr.techtical.nextsh.shared.core.sync.SyncEntry
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import org.jetbrains.compose.resources.getString
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [DesktopTunnelService] and the tunnels deleted from under it: the orphan repair that runs before the
 * auto-start, the start guard, the reconnect guard and the watch that stops what runs on a deleted row.
 *
 * Real time (`runBlocking`), like [DesktopTunnelServiceTest]: the network monitor polls in a loop that a
 * virtual-time scheduler never drains. The repositories are MUTABLE fakes here, the scenarios change the
 * rows while tunnels run. A fake keeps two views of its rows: what `getById` reads (the truth) and what
 * `observeAll` has emitted (the observers, which can lag behind), so that the reconnect guard and the
 * confirmation read of the watch can be exercised on their own.
 */
class DesktopTunnelServiceDeletionTest {

    // -- Fakes -------------------------------------------------------------------

    /** Shared, ordered log of what the fakes and the callbacks saw. */
    private val events = CopyOnWriteArrayList<String>()

    private class MutableTunnelRepository(initial: List<TunnelConfig>, private val events: MutableList<String>) :
        TunnelRepository {
        private val rows = MutableStateFlow(initial)

        /** What `observeAll` has emitted. Lags behind [rows] until [publish] when a change is not notified. */
        val observed = MutableStateFlow(initial)

        /** When set, getById throws it: a database that cannot be read. */
        @Volatile
        var getByIdFailure: Exception? = null

        fun remove(id: String, notifyObservers: Boolean = true) {
            rows.value = rows.value.filterNot { it.id == id }
            if (notifyObservers) publish()
        }

        fun replace(config: TunnelConfig, notifyObservers: Boolean = true) {
            rows.value = rows.value.map { if (it.id == config.id) config else it }
            if (notifyObservers) publish()
        }

        fun publish() { observed.value = rows.value }

        override fun observeAll(): Flow<List<TunnelConfig>> = observed.asStateFlow()
        override fun observeByHost(hostId: String): Flow<List<TunnelConfig>> =
            MutableStateFlow(rows.value.filter { it.hostId == hostId }).asStateFlow()
        override suspend fun getById(id: String): TunnelConfig? {
            getByIdFailure?.let { throw it }
            return rows.value.firstOrNull { it.id == id }
        }
        override suspend fun getAutoStartTunnels(): List<TunnelConfig> {
            events += "autostart-read"
            return rows.value.filter { it.autoStart }
        }
        override suspend fun save(config: TunnelConfig) { rows.value = rows.value + config; publish() }
        override suspend fun update(config: TunnelConfig) = replace(config)
        override suspend fun delete(id: String) = remove(id)
        override fun observeFavorites(): Flow<List<TunnelConfig>> = observed.asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<TunnelConfig>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<TunnelConfig>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class MutableHostRepository(initial: List<Host>) : HostRepository {
        private val rows = MutableStateFlow(initial)

        /** What `observeAll` has emitted. Lags behind [rows] until [publish] when a change is not notified. */
        val observed = MutableStateFlow(initial)

        /** When set, getById throws it: a database that cannot be read. */
        @Volatile
        var getByIdFailure: Exception? = null

        fun remove(id: String, notifyObservers: Boolean = true) {
            rows.value = rows.value.filterNot { it.id == id }
            if (notifyObservers) publish()
        }

        fun publish() { observed.value = rows.value }

        /** How many upcoming emissions of `observeAll` throw, like a source that cannot decode a row. */
        @Volatile
        var failingEmissions = 0

        override fun observeAll(): Flow<List<Host>> = observed.asStateFlow().onEach {
            if (failingEmissions > 0) {
                failingEmissions--
                throw IllegalStateException("cannot decode a row")
            }
        }
        override fun observeByGroup(group: String): Flow<List<Host>> = observed.asStateFlow()
        override fun observeGroups(): Flow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
        override suspend fun getById(id: String): Host? {
            getByIdFailure?.let { throw it }
            return rows.value.firstOrNull { it.id == id }
        }
        override suspend fun save(host: Host) { rows.value = rows.value + host; publish() }
        override suspend fun update(host: Host) = Unit
        override suspend fun delete(id: String) = remove(id)
        override suspend fun updateLastConnected(id: String) = Unit
        override fun observeFavorites(): Flow<List<Host>> = MutableStateFlow(emptyList<Host>()).asStateFlow()
        override suspend fun setFavorite(id: String, isFavorite: Boolean) = Unit
        override suspend fun getAllSyncEntries(): List<SyncEntry<Host>> = emptyList()
        override suspend fun upsertSyncEntry(entry: SyncEntry<Host>) = Unit
        override suspend fun hardDelete(id: String) = Unit
    }

    private class TestAppScope(scope: CoroutineScope) : AppScope {
        override val coroutineScope: CoroutineScope = scope
        override fun onDestroy() = Unit
    }

    /** Everything a scenario needs, wired the way DesktopContainer wires the service. */
    private inner class Rig(
        tunnelList: List<TunnelConfig>,
        hostList: List<Host> = listOf(host1),
        repairOrphanTunnels: suspend () -> Unit = {},
        /** When set, the factory waits for it before answering: the tunnel stays STARTING. */
        initialFactoryGate: CompletableDeferred<Unit>? = null,
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        /** Flips the network (and makes the scripted factory fail while offline). */
        val online = AtomicBoolean(true)

        /** When set, the factory waits for it before answering: the tunnel stays STARTING. */
        @Volatile
        var factoryGate: CompletableDeferred<Unit>? = initialFactoryGate

        /** Every call of the factory, failed or not. */
        val factoryCalls = AtomicInteger(0)

        /** Successful opens only. */
        val openedClients = AtomicInteger(0)

        val tunnelRepository = MutableTunnelRepository(tunnelList, events)
        val hostRepository = MutableHostRepository(hostList)
        val manager = DesktopTunnelManager(scope) { _, _ ->
            factoryCalls.incrementAndGet()
            factoryGate?.await()
            if (!online.get()) {
                SshResult.Error(SshErrorCode.HOST_UNREACHABLE, "offline")
            } else {
                openedClients.incrementAndGet()
                SshResult.Success(buildMockClient())
            }
        }
        val service = DesktopTunnelService(
            tunnelManager = manager,
            networkMonitor = DesktopNetworkMonitor(networkPredicate = { online.get() }, pollIntervalMs = 50L),
            tunnelRepository = tunnelRepository,
            hostRepository = hostRepository,
            appScope = TestAppScope(scope),
            repairOrphanTunnels = repairOrphanTunnels,
        )

        fun status(id: String): TunnelStatus? = manager.getTunnelState(id)?.status

        suspend fun close() {
            service.onAppExitSuspend()
            scope.cancel()
        }
    }

    private val openedServerSockets = mutableListOf<ServerSocket>()

    @AfterTest
    fun tearDown() {
        openedServerSockets.forEach { try { it.close() } catch (_: Exception) {} }
        openedServerSockets.clear()
    }

    // -- Helpers -------------------------------------------------------------------

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun buildMockClient(): SSHClient {
        val client = mockk<SSHClient>(relaxed = true)
        val forwarderMock = mockk<LocalPortForwarder>(relaxed = true)
        every { client.newLocalPortForwarder(any(), any()) } returns forwarderMock
        return client
    }

    private suspend fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(25L)
        }
        return condition()
    }

    private val host1 = Host(
        id = "host-1", label = "h1", hostname = "a.example.com", port = 22,
        username = "u", authType = AuthType.PASSWORD, credentialId = "c1",
    )
    private val host2 = host1.copy(id = "host-2", label = "h2")

    private fun tunnel(id: String, host: Host = host1, autoStart: Boolean = true) = TunnelConfig(
        id = id, label = id, hostId = host.id,
        type = TunnelType.LOCAL_FORWARD,
        localPort = freePort(), remoteHost = "127.0.0.1", remotePort = 80,
        autoStart = autoStart,
    )

    /** Brings [id] to ACTIVE, then drops the network until it is RECONNECTING (its reconnect loop runs). */
    private suspend fun Rig.reachReconnecting(id: String) {
        service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { status(id) == TunnelStatus.ACTIVE }, "$id must become ACTIVE")
        online.set(false)
        assertTrue(waitFor(3_000L) { status(id) == TunnelStatus.RECONNECTING }, "$id must become RECONNECTING")
    }

    // -- Orphan repair at unlock ---------------------------------------------------

    @Test
    fun `onVaultUnlocked runs the orphan repair before the auto-start reads the list`() = runBlocking {
        val orphan = tunnel("orphan")
        val healthy = tunnel("healthy")
        lateinit var rig: Rig
        rig = Rig(
            tunnelList = listOf(orphan, healthy),
            repairOrphanTunnels = {
                events += "repair"
                // What the real repair does to the database: the orphan stops being a live tunnel.
                rig.tunnelRepository.remove("orphan")
            },
        )

        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("healthy") == TunnelStatus.ACTIVE })

        assertEquals(listOf("repair", "autostart-read"), events.toList())
        assertEquals(1, rig.openedClients.get(), "the repaired orphan must not open a connection")
        assertEquals(null, rig.manager.getTunnelState("orphan"), "and must leave no error state behind")

        rig.close()
    }

    @Test
    fun `a failing orphan repair does not prevent the auto-start`() = runBlocking {
        val rig = Rig(
            tunnelList = listOf(tunnel("t1")),
            repairOrphanTunnels = { throw IllegalStateException("disk I/O error") },
        )

        rig.service.onVaultUnlocked()

        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        rig.close()
    }

    // -- Start guard ---------------------------------------------------------------

    @Test
    fun `startTunnel refuses a tunnel whose row is gone and leaves no state behind`() = runBlocking {
        val gone = tunnel("gone")
        val rig = Rig(tunnelList = emptyList())

        val result = rig.service.startTunnel(gone)

        val error = assertIs<SshResult.Error>(result)
        assertEquals(getString(Res.string.tunnels_error_deleted), error.message)
        assertEquals(null, rig.manager.getTunnelState("gone"), "no ERROR state that would count as a running tunnel")
        assertEquals(0, rig.factoryCalls.get())

        rig.close()
    }

    @Test
    fun `startTunnel refuses a tunnel whose host is deleted`() = runBlocking {
        val orphan = tunnel("orphan", host2)
        val rig = Rig(tunnelList = listOf(orphan), hostList = listOf(host1))

        val result = rig.service.startTunnel(orphan)

        assertIs<SshResult.Error>(result)
        assertEquals(null, rig.manager.getTunnelState("orphan"))
        assertEquals(0, rig.factoryCalls.get())

        rig.close()
    }

    @Test
    fun `startTunnel refuses when the database cannot be read and starts once it can`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.tunnelRepository.getByIdFailure = IllegalStateException("disk I/O error")

        val error = assertIs<SshResult.Error>(rig.service.startTunnel(config))
        // Its own message: neither the "deleted" one (nothing is known to be deleted) nor a generic SSH error.
        assertEquals(getString(Res.string.tunnels_error_unreadable), error.message)
        assertTrue(error.message != getString(Res.string.tunnels_error_deleted))
        assertEquals(null, rig.manager.getTunnelState("t1"))

        rig.tunnelRepository.getByIdFailure = null
        assertIs<SshResult.Success<*>>(rig.service.startTunnel(config))
        assertEquals(TunnelStatus.ACTIVE, rig.status("t1"))

        rig.close()
    }

    @Test
    fun `startTunnel starts from the current row, not from a stale copy`() = runBlocking {
        val original = tunnel("t1")
        val edited = original.copy(localPort = freePort())
        val rig = Rig(tunnelList = listOf(edited))

        assertIs<SshResult.Success<*>>(rig.service.startTunnel(original))

        assertEquals(edited.localPort, rig.manager.getTunnelState("t1")?.config?.localPort)

        rig.close()
    }

    @Test
    fun `startAllTunnels reports a tunnel of a deleted host as an error and starts the others`() = runBlocking {
        val ok = tunnel("ok")
        val orphan = tunnel("orphan", host2)
        val rig = Rig(tunnelList = listOf(ok, orphan), hostList = listOf(host1))

        val results = rig.service.startAllTunnels().toMap()

        assertIs<SshResult.Success<*>>(results.getValue("ok"))
        assertIs<SshResult.Error>(results.getValue("orphan"))
        assertEquals(null, rig.manager.getTunnelState("orphan"))

        rig.close()
    }

    // -- Reconnect guard -----------------------------------------------------------

    @Test
    fun `a host deleted during the reconnect ends the loop, the tunnel is STOPPED and nothing retries`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")

        // The deletion reaches the database but not the observers: only the guard of the loop can see it.
        rig.hostRepository.remove(host1.id, notifyObservers = false)

        assertTrue(waitFor(9_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")
        val callsAtStop = rig.factoryCalls.get()
        delay(2_500L)
        assertEquals(callsAtStop, rig.factoryCalls.get(), "no retry once the host is gone")
        assertEquals(TunnelStatus.STOPPED, rig.status("t1"))
        assertTrue(rig.manager.wasManuallyStopped("t1"))

        rig.close()
    }

    @Test
    fun `a tunnel deleted during the reconnect is not resurrected when the network comes back`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")
        val opensBefore = rig.openedClients.get()

        // The row is tombstoned, its host is alive, the network is back: the old loop would reconnect it.
        rig.tunnelRepository.remove("t1", notifyObservers = false)
        rig.online.set(true)

        assertTrue(waitFor(9_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")
        delay(1_500L)
        assertEquals(opensBefore, rig.openedClients.get(), "a deleted tunnel must never reconnect")
        assertEquals(TunnelStatus.STOPPED, rig.status("t1"))

        rig.close()
    }

    @Test
    fun `an unreadable database does not end the reconnect loop, the tunnel reconnects once it can be read`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")
        val opensBefore = rig.openedClients.get()

        rig.tunnelRepository.getByIdFailure = IllegalStateException("disk I/O error")
        rig.online.set(true)
        delay(1_500L)
        assertEquals(TunnelStatus.RECONNECTING, rig.status("t1"), "an unreadable row decides nothing")
        assertEquals(opensBefore, rig.openedClients.get())

        rig.tunnelRepository.getByIdFailure = null
        assertTrue(waitFor(8_000L) { rig.status("t1") == TunnelStatus.ACTIVE }, "status=${rig.status("t1")}")
        assertTrue(rig.openedClients.get() > opensBefore)

        rig.close()
    }

    @Test
    fun `the reconnect starts from the current row of the tunnel`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")
        val edited = config.copy(localPort = freePort())

        rig.tunnelRepository.replace(edited, notifyObservers = false)
        rig.online.set(true)

        assertTrue(waitFor(8_000L) { rig.status("t1") == TunnelStatus.ACTIVE }, "status=${rig.status("t1")}")
        assertEquals(edited.localPort, rig.manager.getTunnelState("t1")?.config?.localPort)

        rig.close()
    }

    // -- Deletion watch ------------------------------------------------------------

    @Test
    fun `a host deleted elsewhere stops the running tunnels of that host and leaves the others`() = runBlocking {
        val onHost1 = tunnel("on-host-1", host1)
        val onHost2 = tunnel("on-host-2", host2)
        val rig = Rig(tunnelList = listOf(onHost1, onHost2), hostList = listOf(host1, host2))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { rig.status("on-host-1") == TunnelStatus.STOPPED }, "status=${rig.status("on-host-1")}")
        assertEquals(TunnelStatus.ACTIVE, rig.status("on-host-2"))
        assertEquals(setOf("on-host-2"), rig.manager.activeIds())

        rig.close()
    }

    @Test
    fun `a tunnel deleted elsewhere is stopped and the other tunnels of its host keep running`() = runBlocking {
        val doomed = tunnel("doomed")
        val stays = tunnel("stays")
        val rig = Rig(tunnelList = listOf(doomed, stays))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })

        rig.tunnelRepository.remove("doomed")

        assertTrue(waitFor(3_000L) { rig.status("doomed") == TunnelStatus.STOPPED })
        assertEquals(TunnelStatus.ACTIVE, rig.status("stays"))

        rig.close()
    }

    @Test
    fun `a RECONNECTING tunnel whose host is deleted is stopped and its reconnect loop is cancelled`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")
        val callsAtStop = rig.factoryCalls.get()
        rig.online.set(true)
        delay(2_500L)
        assertEquals(callsAtStop, rig.factoryCalls.get(), "the loop must not try again")
        assertEquals(TunnelStatus.STOPPED, rig.status("t1"))

        rig.close()
    }

    @Test
    fun `a tunnel moved to another host while it runs is stopped when the host it runs through is deleted`() = runBlocking {
        val moved = tunnel("moved", host1)
        val rig = Rig(tunnelList = listOf(moved), hostList = listOf(host1, host2))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("moved") == TunnelStatus.ACTIVE })
        // The edit moves the row to host-2; the running state still holds host-1.
        rig.tunnelRepository.replace(moved.copy(hostId = host2.id))
        delay(200L)
        assertEquals(TunnelStatus.ACTIVE, rig.status("moved"), "its row and the host it runs through are alive")

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { rig.status("moved") == TunnelStatus.STOPPED })

        rig.close()
    }

    @Test
    fun `the watch confirms with a direct read, a row that the observers have not emitted yet is not stopped`() = runBlocking {
        val fresh = tunnel("fresh", autoStart = false)
        val rig = Rig(tunnelList = emptyList())
        rig.service.onVaultUnlocked()
        // Created and started in the same second: the lists the observers emitted do not hold it yet.
        rig.tunnelRepository.save(fresh)
        rig.tunnelRepository.observed.value = emptyList()
        assertIs<SshResult.Success<*>>(rig.service.startTunnel(fresh))

        delay(400L)

        assertEquals(TunnelStatus.ACTIVE, rig.status("fresh"))
        rig.close()
    }

    @Test
    fun `a failing confirmation read decides nothing, the tunnel keeps running and is stopped at the next look`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })

        rig.tunnelRepository.getByIdFailure = IllegalStateException("disk I/O error")
        rig.tunnelRepository.remove("t1")
        delay(400L)
        assertEquals(TunnelStatus.ACTIVE, rig.status("t1"), "a read failure is transient")

        rig.tunnelRepository.getByIdFailure = null
        // Any new emission makes the watch look again (a host renamed, here).
        rig.hostRepository.observed.value = listOf(host1.copy(label = "renamed"))

        assertTrue(waitFor(3_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")

        rig.close()
    }

    @Test
    fun `the watch keeps working after one failing emission of its source`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })

        // A synced row the source cannot decode: its next emission throws.
        rig.hostRepository.failingEmissions = 1
        rig.hostRepository.observed.value = listOf(host1.copy(label = "renamed"))
        assertTrue(waitFor(2_000L) { rig.hostRepository.failingEmissions == 0 }, "the emission must have thrown")
        // The watch is now waiting out its retry delay. The deletion happens during that wait.
        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(6_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")

        rig.close()
    }

    @Test
    fun `the watch also recovers when the very first emission of its source fails`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.hostRepository.failingEmissions = 1
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        assertTrue(waitFor(2_000L) { rig.hostRepository.failingEmissions == 0 })

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(6_000L) { rig.status("t1") == TunnelStatus.STOPPED }, "status=${rig.status("t1")}")

        rig.close()
    }

    @Test
    fun `the deletion watch is cancelled on app exit, both variants, and runs again after a new unlock`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        assertTrue(rig.hostRepository.observed.subscriptionCount.value > 0, "the watch collects the hosts")
        assertTrue(rig.tunnelRepository.observed.subscriptionCount.value > 0, "and the tunnels")

        rig.service.onAppExitSuspend()
        assertTrue(waitFor(2_000L) { rig.hostRepository.observed.subscriptionCount.value == 0 })
        assertEquals(0, rig.tunnelRepository.observed.subscriptionCount.value)

        // Unlock again, then the fire-and-forget exit.
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(2_000L) { rig.hostRepository.observed.subscriptionCount.value > 0 }, "restarted by the unlock")
        rig.service.onAppExit()
        assertTrue(waitFor(2_000L) { rig.hostRepository.observed.subscriptionCount.value == 0 })
        assertEquals(0, rig.tunnelRepository.observed.subscriptionCount.value)

        rig.scope.cancel()
    }

    // -- Deletion events -----------------------------------------------------------

    /** Collects the deletion events from now on. The collector is subscribed when this returns. */
    private fun Rig.collectDeletionStops(): List<TunnelDeletionStop> {
        val received = CopyOnWriteArrayList<TunnelDeletionStop>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { service.deletionStops.collect { received += it } }
        return received
    }

    @Test
    fun `a host deleted elsewhere emits ONE event that groups every tunnel it stopped`() = runBlocking {
        val a = tunnel("a", host1)
        val b = tunnel("b", host1)
        val other = tunnel("other", host2)
        val rig = Rig(tunnelList = listOf(a, b, other), hostList = listOf(host1, host2))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 3 })
        val received = rig.collectDeletionStops()

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { received.isNotEmpty() })
        delay(500L)
        val event = received.single()
        assertEquals(setOf("a", "b"), event.tunnels.map { it.id }.toSet())
        assertEquals(setOf("a", "b"), event.tunnels.map { it.label }.toSet())
        assertEquals(event.tunnels.toSet(), rig.service.deletionNotice.value.toSet())

        rig.close()
    }

    @Test
    fun `a tunnel deleted elsewhere emits an event for that tunnel only`() = runBlocking {
        val doomed = tunnel("doomed")
        val stays = tunnel("stays")
        val rig = Rig(tunnelList = listOf(doomed, stays))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })
        val received = rig.collectDeletionStops()

        rig.tunnelRepository.remove("doomed")

        assertTrue(waitFor(3_000L) { received.isNotEmpty() })
        assertEquals(listOf(StoppedTunnel(id = "doomed", label = "doomed")), received.single().tunnels)

        rig.close()
    }

    @Test
    fun `the reconnect loop reports the tunnel it stops`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")
        val received = rig.collectDeletionStops()

        // Not notified to the observers: only the guard of the loop can see it.
        rig.hostRepository.remove(host1.id, notifyObservers = false)

        assertTrue(waitFor(9_000L) { received.isNotEmpty() })
        assertEquals(listOf(StoppedTunnel(id = "t1", label = "t1")), received.single().tunnels)
        assertEquals(TunnelStatus.STOPPED, rig.status("t1"))

        rig.close()
    }

    @Test
    fun `the watch and the reconnect loop never report the same tunnel twice`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.reachReconnecting("t1")
        val received = rig.collectDeletionStops()

        // Observers notified: the watch stops it at once. The loop, if it was already on its way, must not report again.
        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { received.isNotEmpty() })
        delay(2_500L)
        assertEquals(1, received.size, "one report for one stop")
        assertEquals(1, rig.service.deletionNotice.value.size)

        rig.close()
    }

    @Test
    fun `a tunnel in ERROR whose row is deleted elsewhere is stopped silently`() = runBlocking {
        val failed = tunnel("failed")
        val rig = Rig(tunnelList = listOf(failed))
        rig.online.set(false) // the auto-start cannot connect: ERROR, no reconnect loop
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("failed") == TunnelStatus.ERROR }, "status=${rig.status("failed")}")
        val received = rig.collectDeletionStops()

        rig.tunnelRepository.remove("failed")

        assertTrue(waitFor(3_000L) { rig.status("failed") == TunnelStatus.STOPPED }, "status=${rig.status("failed")}")
        delay(500L)
        assertTrue(received.isEmpty(), "it never ran: no message")
        assertTrue(rig.service.deletionNotice.value.isEmpty(), "and no banner entry")

        rig.close()
    }

    @Test
    fun `a tunnel still STARTING whose host is deleted elsewhere is stopped silently`() = runBlocking {
        val connecting = tunnel("connecting")
        val gate = CompletableDeferred<Unit>()
        val rig = Rig(tunnelList = listOf(connecting), initialFactoryGate = gate)
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("connecting") == TunnelStatus.STARTING }, "status=${rig.status("connecting")}")
        val received = rig.collectDeletionStops()

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { rig.status("connecting") == TunnelStatus.STOPPED }, "status=${rig.status("connecting")}")
        gate.complete(Unit) // the connect finishes after the stop: the manager discards it
        delay(500L)
        assertEquals(TunnelStatus.STOPPED, rig.status("connecting"))
        assertTrue(received.isEmpty(), "it never reached ACTIVE: no message")
        assertTrue(rig.service.deletionNotice.value.isEmpty())

        rig.close()
    }

    @Test
    fun `a tunnel caught in a reconnect attempt was running before the link dropped and is announced`() = runBlocking {
        val config = tunnel("t1")
        val rig = Rig(tunnelList = listOf(config))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.ACTIVE })
        // The next connection attempt hangs: during a reconnect the status of the attempt is STARTING.
        val gate = CompletableDeferred<Unit>()
        rig.factoryGate = gate
        rig.online.set(false)
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.RECONNECTING }, "status=${rig.status("t1")}")
        // Back online: the loop makes no attempt while the monitor says offline.
        rig.online.set(true)
        assertTrue(waitFor(5_000L) { rig.status("t1") == TunnelStatus.STARTING }, "status=${rig.status("t1")}")
        val received = rig.collectDeletionStops()

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { received.isNotEmpty() })
        assertEquals(listOf(StoppedTunnel("t1", "t1")), received.single().tunnels)
        assertEquals(TunnelStatus.STOPPED, rig.status("t1"))
        gate.complete(Unit)

        rig.close()
    }

    @Test
    fun `in one pass only the tunnels that were really running are announced`() = runBlocking {
        val running = tunnel("running")
        val failed = tunnel("failed", autoStart = false)
        val rig = Rig(tunnelList = listOf(running, failed))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.status("running") == TunnelStatus.ACTIVE })
        // The link drops: "running" is RECONNECTING (it was up), "failed" cannot even connect (ERROR).
        rig.online.set(false)
        assertTrue(waitFor(3_000L) { rig.status("running") == TunnelStatus.RECONNECTING })
        assertIs<SshResult.Error>(rig.service.startTunnel(failed))
        assertEquals(TunnelStatus.ERROR, rig.status("failed"))
        val received = rig.collectDeletionStops()

        rig.hostRepository.remove(host1.id)

        assertTrue(waitFor(3_000L) { received.isNotEmpty() })
        delay(500L)
        assertEquals(listOf(StoppedTunnel("running", "running")), received.single().tunnels)
        assertEquals(TunnelStatus.STOPPED, rig.status("running"))
        assertEquals(TunnelStatus.STOPPED, rig.status("failed"), "the silent one is stopped all the same")

        rig.close()
    }

    @Test
    fun `a stop the user asked for and a local host deletion emit nothing`() = runBlocking {
        val a = tunnel("a", host1)
        val b = tunnel("b", host2)
        val rig = Rig(tunnelList = listOf(a, b), hostList = listOf(host1, host2))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })
        val received = rig.collectDeletionStops()

        // What the app does itself: stop first, delete after.
        rig.service.stopTunnel("a")
        rig.service.stopTunnelsOfHost(host2.id)
        rig.hostRepository.remove(host2.id)
        rig.tunnelRepository.remove("a")
        rig.tunnelRepository.remove("b")
        delay(600L)

        assertEquals(TunnelStatus.STOPPED, rig.status("a"))
        assertEquals(TunnelStatus.STOPPED, rig.status("b"))
        assertTrue(received.isEmpty(), "nothing was deleted from under a running tunnel")
        assertTrue(rig.service.deletionNotice.value.isEmpty())

        rig.close()
    }

    @Test
    fun `a notice stays until dismissed and a later stop shows only the new tunnels`() = runBlocking {
        val first = tunnel("first")
        val second = tunnel("second")
        val rig = Rig(tunnelList = listOf(first, second))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })

        rig.tunnelRepository.remove("first")
        assertTrue(waitFor(3_000L) { rig.service.deletionNotice.value.isNotEmpty() })
        // No collector was there: the event is lost, the notice is not.
        assertEquals(listOf(StoppedTunnel("first", "first")), rig.service.deletionNotice.value)

        rig.service.dismissDeletionNotice(rig.service.deletionNotice.value.map { it.id })
        assertTrue(rig.service.deletionNotice.value.isEmpty())

        rig.tunnelRepository.remove("second")
        assertTrue(waitFor(3_000L) { rig.service.deletionNotice.value.isNotEmpty() })
        assertEquals(listOf(StoppedTunnel("second", "second")), rig.service.deletionNotice.value)

        rig.close()
    }

    @Test
    fun `a notice that arrives between the render and the click is not dismissed with the displayed ones`() = runBlocking {
        val shown = tunnel("shown")
        val late = tunnel("late")
        val rig = Rig(tunnelList = listOf(shown, late))
        rig.service.onVaultUnlocked()
        assertTrue(waitFor(5_000L) { rig.manager.activeIds().size == 2 })

        rig.tunnelRepository.remove("shown")
        assertTrue(waitFor(3_000L) { rig.service.deletionNotice.value.isNotEmpty() })
        val displayed = rig.service.deletionNotice.value.map { it.id } // what the banner was drawn with

        rig.tunnelRepository.remove("late") // arrives before the click
        assertTrue(waitFor(3_000L) { rig.service.deletionNotice.value.size == 2 })
        rig.service.dismissDeletionNotice(displayed)

        assertEquals(listOf(StoppedTunnel("late", "late")), rig.service.deletionNotice.value)

        // Dismissing the ones that are left, and dismissing nothing, are both harmless.
        rig.service.dismissDeletionNotice(emptyList())
        assertEquals(1, rig.service.deletionNotice.value.size)
        rig.service.dismissDeletionNotice(listOf("late", "unknown"))
        assertTrue(rig.service.deletionNotice.value.isEmpty())

        rig.close()
    }
}
