// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.shared.domain.ssh

import fr.techtical.nextsh.shared.domain.model.Host
import fr.techtical.nextsh.shared.domain.model.SshResult

/**
 * Supplies the SSH session a tunnel runs on.
 *
 * A tunnel needs a connected session to its host, and how that session is obtained depends on how
 * the host authenticates. A password or a stored key can be used from anywhere, but a FIDO2 host
 * needs a security key prompt and a biometric key needs the platform's biometric dialog: both are
 * user interface, which this module cannot drive. So `StartTunnelUseCase` does not connect by
 * itself. It asks the platform, which reuses a live session of the host or opens one with its own
 * connection flow.
 *
 * Contract:
 * - A success carries the id of a session that is CONNECTED to [Host] at the time of the answer.
 * - A failure is an [SshResult.Error] whose message is safe to show to the user, and must never
 *   hold a secret (password, passphrase, key material).
 * - A cancellation of the calling coroutine ends the call with a `CancellationException`. An
 *   implementation never turns it into an [SshResult.Error], so that a start the user abandoned is
 *   not reported as a failure.
 * - An implementation throws nothing but that `CancellationException`. Every other failure, an
 *   unexpected exception included, comes back as an [SshResult.Error] with a fixed message that
 *   holds no detail of the exception. `StartTunnelUseCase` has no generic `catch`: a stray
 *   exception would reach its caller with the tunnel still marked as starting.
 */
fun interface TunnelSessionProvider {
    suspend fun sessionFor(host: Host): SshResult<String>
}
