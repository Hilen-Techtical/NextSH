// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.Host
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.repository.HostRepository
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.domain.usecase.LiveTunnelLookup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stops the running tunnels whose row or host is deleted behind their back.
 *
 * A running tunnel lives in memory ([SshTunnelManager.tunnelStates], with the config frozen at
 * start), independently of the database. A sync from another device, a conflict resolution or
 * the orphan repair can delete the tunnel or its host meanwhile, and nothing noticed: the tunnel
 * kept running with no card to stop it, and one in error was restarted on the next network
 * event. This watches both tables for as long as a tunnel is known to the manager and stops the
 * ones that are no longer backed by live rows, through [DeletedTunnelStopper].
 *
 * A local delete from the UI stops the tunnel before it tombstones the row, so it never reaches
 * this class.
 *
 * App-scoped, started from `App.onCreate` like `ShortcutUpdater`.
 */
@Singleton
class TunnelReconciler @Inject constructor(
    private val tunnelManager: SshTunnelManager,
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
    private val liveTunnels: LiveTunnelLookup,
    private val deletedTunnelStopper: DeletedTunnelStopper,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun startObserving() {
        scope.launch { observe() }
    }

    private class Snapshot(
        val states: Map<String, TunnelState>,
        val tunnels: List<TunnelConfig>,
        val hosts: List<Host>,
    )

    /**
     * Collects until cancelled. The two tables are observed only while the manager holds at
     * least one tunnel: with none, the app keeps no extra database observer alive.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal suspend fun observe() {
        tunnelManager.tunnelStates
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .flatMapLatest { anyTunnel ->
                if (!anyTunnel) {
                    emptyFlow<Snapshot>()
                } else {
                    combine(
                        tunnelManager.tunnelStates,
                        tunnelRepository.observeAll(),
                        hostRepository.observeAll(),
                    ) { states, tunnels, hosts -> Snapshot(states, tunnels, hosts) }
                }
            }
            // A pass reads the database and stops tunnels: skip the intermediate snapshots that
            // pile up meanwhile (a sync writes many rows) and handle the latest one.
            .conflate()
            .collect { snapshot -> reconcilePass(snapshot.states, snapshot.tunnels, snapshot.hosts) }
    }

    /**
     * One pass, which must never end the collection: a failure is logged by class name only and
     * the next change of any of the three sources runs a new pass.
     */
    internal suspend fun reconcilePass(
        states: Map<String, TunnelState>,
        tunnels: List<TunnelConfig>,
        hosts: List<Host>,
    ) {
        try {
            reconcile(states, tunnels, hosts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w("TunnelReconciler pass failed: ${e::class.java.simpleName}")
        }
    }

    private suspend fun reconcile(
        states: Map<String, TunnelState>,
        tunnels: List<TunnelConfig>,
        hosts: List<Host>,
    ) {
        val liveTunnelsById = tunnels.associateBy { it.id }
        val liveHostIds = hosts.mapTo(HashSet()) { it.id }

        // The frozen config tells which host the tunnel was started against, the stored row
        // which host it points at now (an edit can change it while the tunnel runs): both must
        // be live.
        val suspects = states.values
            .map { it.config }
            .filter { config ->
                val row = liveTunnelsById[config.id]
                row == null || row.hostId !in liveHostIds || config.hostId !in liveHostIds
            }
        if (suspects.isEmpty()) return

        // The three lists come from three Room flows that do not emit together: a snapshot can
        // be stale by a write or two. Never stop a tunnel on that alone, ask the database.
        val confirmed = suspects.filter { config ->
            liveTunnels.find(config.id) == null || !liveTunnels.isHostLive(config.hostId)
        }
        if (confirmed.isEmpty()) return

        deletedTunnelStopper.stop(confirmed)
    }
}
