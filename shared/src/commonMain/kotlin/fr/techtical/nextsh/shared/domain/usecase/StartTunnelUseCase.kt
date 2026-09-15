// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SessionStatus
import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.ssh.SshSessionManager
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager
import kotlinx.coroutines.flow.first

/**
 * UseCase : démarrer un tunnel SSH.
 * Vérifie qu'une session SSH est active vers le host du tunnel,
 * puis délègue au SshTunnelManager.
 */
class StartTunnelUseCase(
    private val tunnelManager: SshTunnelManager,
    private val sessionManager: SshSessionManager,
    private val tunnelRepository: TunnelRepository,
    private val hostRepository: HostRepository,
    private val connectSession: ConnectSessionUseCase,
) {
    suspend operator fun invoke(config: TunnelConfig): SshResult<Unit> {
        val host = hostRepository.getById(config.hostId)
            ?: run {
                val msg = "Hôte ${config.hostId} introuvable"
                tunnelManager.markError(config, msg)
                return SshResult.Error(SshErrorCode.UNKNOWN, msg)
            }

        // Marque STARTING dès le début pour que l'UI reflète la tentative
        // (StatusPill "DÉMARRAGE") AVANT l'auto-connexion SSH éventuelle.
        // Sinon un échec de connexion en amont reste invisible côté UI.
        tunnelManager.markStarting(config)

        // Find an active connected session for this host
        val currentSessions = sessionManager.sessions.first()
        val activeSession = currentSessions.firstOrNull {
            it.host.id == config.hostId && it.status == SessionStatus.CONNECTED
        }

        val sessionId = if (activeSession != null) {
            activeSession.id
        } else {
            // Auto-connect if no active session
            val connectResult = connectSession(host)
            if (connectResult is SshResult.Error) {
                tunnelManager.markError(config, connectResult.message)
                return SshResult.Error(connectResult.code, connectResult.message)
            }
            (connectResult as SshResult.Success).data.id
        }

        return when (config.type) {
            TunnelType.LOCAL_FORWARD   -> tunnelManager.startLocalForward(config, sessionId)
            TunnelType.REMOTE_FORWARD  -> tunnelManager.startRemoteForward(config, sessionId)
            TunnelType.DYNAMIC_SOCKS5  -> tunnelManager.startDynamicForward(config, sessionId)
        }
    }
}
