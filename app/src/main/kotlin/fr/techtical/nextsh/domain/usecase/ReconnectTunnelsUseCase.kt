// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.core.network.NetworkEvent
import fr.techtical.nextsh.core.network.NetworkMonitor
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.domain.model.TunnelStatus
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject

class ReconnectTunnelsUseCase @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val tunnelManager: SshTunnelManager,
    private val startTunnel: StartTunnelUseCase,
) {
    companion object {
        private const val MAX_RETRIES = 5
        private const val INITIAL_DELAY_MS = 1_000L
        private const val MAX_DELAY_MS = 30_000L
    }

    suspend fun observe() {
        networkMonitor.networkEvents.collect { event ->
            if (event is NetworkEvent.Connected) {
                reconnectErrorTunnels()
            }
        }
    }

    private suspend fun reconnectErrorTunnels() {
        val tunnels = tunnelManager.getTunnelsForReconnect()
        if (tunnels.isEmpty()) return

        Timber.i("Tentative de reconnexion de ${tunnels.size} tunnel(s)")

        for (config in tunnels) {
            tunnelManager.updateTunnelStatus(config.id, TunnelStatus.RECONNECTING)

            var delayMs = INITIAL_DELAY_MS
            var success = false

            for (attempt in 1..MAX_RETRIES) {
                delay(delayMs)

                val result = startTunnel(config)
                if (result is SshResult.Success) {
                    Timber.i("Tunnel ${config.label} reconnecté (tentative $attempt)")
                    success = true
                    break
                }

                Timber.w("Échec reconnexion ${config.label} tentative $attempt/$MAX_RETRIES")
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
            }

            if (!success) {
                tunnelManager.updateTunnelStatus(
                    config.id,
                    TunnelStatus.ERROR,
                    "Reconnexion échouée après $MAX_RETRIES tentatives"
                )
            }
        }
    }
}
