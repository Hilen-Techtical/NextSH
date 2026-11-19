// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshErrorCode
import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.TunnelType
import fr.techtical.nextsh.shared.domain.repository.HostRepository
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager
import fr.techtical.nextsh.shared.domain.ssh.TunnelSessionProvider

/**
 * UseCase: start an SSH tunnel.
 * Asks the [TunnelSessionProvider] for a connected session to the tunnel's host (the platform
 * reuses a live one or opens it with its own connection flow; a host whose authentication it
 * cannot complete comes back as an error result), then hands over to the SshTunnelManager.
 *
 * A cancellation of the coroutine (CancellationException) goes up to the caller untouched. It is
 * not a failure, so the tunnel is not marked in error here: the caller knows why it cancels, and
 * puts the tunnel state right.
 */
class StartTunnelUseCase(
    private val tunnelManager: SshTunnelManager,
    private val hostRepository: HostRepository,
    private val sessionProvider: TunnelSessionProvider,
) {
    suspend operator fun invoke(config: TunnelConfig): SshResult<Unit> {
        val host = hostRepository.getById(config.hostId)
            ?: run {
                // Fixed text: the message reaches the screen, and an internal id means nothing there.
                val msg = HOST_NOT_FOUND_MESSAGE
                tunnelManager.markError(config, msg)
                return SshResult.Error(SshErrorCode.UNKNOWN, msg)
            }

        // Marque STARTING dès le début pour que l'UI reflète la tentative
        // (StatusPill "DÉMARRAGE") AVANT l'auto-connexion SSH éventuelle.
        // Sinon un échec de connexion en amont reste invisible côté UI.
        tunnelManager.markStarting(config)

        val sessionId = when (val session = sessionProvider.sessionFor(host)) {
            is SshResult.Success -> session.data
            is SshResult.Error -> {
                tunnelManager.markError(config, session.message)
                return SshResult.Error(session.code, session.message)
            }
        }

        return when (config.type) {
            TunnelType.LOCAL_FORWARD   -> tunnelManager.startLocalForward(config, sessionId)
            TunnelType.REMOTE_FORWARD  -> tunnelManager.startRemoteForward(config, sessionId)
            TunnelType.DYNAMIC_SOCKS5  -> tunnelManager.startDynamicForward(config, sessionId)
        }
    }

    private companion object {
        const val HOST_NOT_FOUND_MESSAGE = "Hôte introuvable"
    }
}
