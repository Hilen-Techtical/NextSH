// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.shared.domain.model.SshResult

/**
 * The start of a tunnel as the shared `StartTunnelUseCase` makes it: announced to the manager with
 * `markStarting` first, which makes it THE start of the tunnel, then handed to the `start*`. A
 * `start*` called without that announcement is a start the manager does not know, and it fades out
 * without opening anything (see `SshTunnelManager.markStarting`).
 */
internal suspend fun SshTunnelManager.startLocalAnnounced(config: TunnelConfig, sessionId: String): SshResult<Unit> {
    markStarting(config)
    return startLocalForward(config, sessionId)
}

internal suspend fun SshTunnelManager.startRemoteAnnounced(config: TunnelConfig, sessionId: String): SshResult<Unit> {
    markStarting(config)
    return startRemoteForward(config, sessionId)
}

internal suspend fun SshTunnelManager.startDynamicAnnounced(config: TunnelConfig, sessionId: String): SshResult<Unit> {
    markStarting(config)
    return startDynamicForward(config, sessionId)
}
