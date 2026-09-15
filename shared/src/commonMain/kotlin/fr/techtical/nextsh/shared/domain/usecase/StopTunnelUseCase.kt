// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.shared.domain.ssh.SshTunnelManager

class StopTunnelUseCase(
    private val tunnelManager: SshTunnelManager,
) {
    suspend operator fun invoke(tunnelId: String): SshResult<Unit> = tunnelManager.stopTunnel(tunnelId)
}
