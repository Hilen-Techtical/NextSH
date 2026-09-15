// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.core.vault.VaultAuthExpiredException
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.repository.TunnelRepository
import timber.log.Timber
import javax.inject.Inject

class AutoStartTunnelsUseCase @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val startTunnel: StartTunnelUseCase,
) {
    suspend operator fun invoke(): List<Pair<String, SshResult<Unit>>> {
        val tunnels = tunnelRepository.getAutoStartTunnels()
        if (tunnels.isEmpty()) return emptyList()

        Timber.i("Auto-démarrage de ${tunnels.size} tunnel(s)")

        return tunnels.map { config ->
            val result = try {
                startTunnel(config)
            } catch (e: VaultAuthExpiredException) {
                Timber.w("Auto-start tunnel ${config.label}: vault auth expired")
                SshResult.Error(
                    SshErrorCode.AUTH_EXPIRED,
                    "Authentification vault expirée"
                )
            }
            if (result is SshResult.Error) {
                Timber.w("Échec auto-démarrage tunnel ${config.label}: ${result.message}")
            }
            config.id to result
        }
    }
}
