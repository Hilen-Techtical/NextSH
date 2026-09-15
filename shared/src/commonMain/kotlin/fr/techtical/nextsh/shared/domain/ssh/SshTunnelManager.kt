// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.ssh

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelState
import kotlinx.coroutines.flow.Flow

interface SshTunnelManager {
    val tunnels: Flow<List<TunnelState>>
    suspend fun startLocalForward(config: TunnelConfig, sessionId: String): SshResult<Unit>
    suspend fun startRemoteForward(config: TunnelConfig, sessionId: String): SshResult<Unit>
    suspend fun startDynamicForward(config: TunnelConfig, sessionId: String): SshResult<Unit>
    suspend fun stopTunnel(tunnelId: String): SshResult<Unit>
    fun getTunnelState(tunnelId: String): TunnelState?

    /**
     * Marque le tunnel comme en cours de démarrage (STARTING), appelé
     * par le `StartTunnelUseCase` AVANT toute tentative coûteuse (auto-
     * connexion SSH du host etc.) pour que l'UI reflète la tentative
     * dès le tap "Démarrer". Sans ce hook, un échec de connexion SSH
     * en amont rendait le use case `SshResult.Error` SANS jamais
     * passer par les `start*Forward` qui font les transitions d'état :
     * la StatusPill restait à ARRÊTÉ et l'utilisateur ne voyait que le
     * snackbar d'erreur (silent failure visible).
     */
    fun markStarting(config: TunnelConfig)

    /**
     * Marque le tunnel comme en erreur (ERROR + message). Appelé par le
     * `StartTunnelUseCase` quand l'auto-connexion SSH échoue avant
     * d'atteindre la phase port-forwarder.
     */
    fun markError(config: TunnelConfig, message: String)
}
