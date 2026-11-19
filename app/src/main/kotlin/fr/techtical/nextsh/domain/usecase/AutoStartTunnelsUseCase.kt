// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.domain.usecase

import fr.techtical.nextsh.shared.domain.model.SshResult
import fr.techtical.nextsh.core.ssh.SshTunnelManager
import fr.techtical.nextsh.core.ssh.TUNNEL_START_CANCELLED_MESSAGE
import fr.techtical.nextsh.core.ssh.TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE
import fr.techtical.nextsh.core.ssh.isStartWithdrawn
import fr.techtical.nextsh.domain.model.SshErrorCode
import fr.techtical.nextsh.domain.model.TunnelConfig
import fr.techtical.nextsh.domain.repository.TunnelRepository
import fr.techtical.nextsh.shared.domain.vault.VaultAuthExpiredException
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject

/**
 * Starts the auto-start tunnels, one after the other.
 *
 * It can run more than once in the life of the app (each new screen asks for it), so a tunnel that
 * already runs (ACTIVE, STARTING or RECONNECTING) is left alone and has no result: starting it
 * again would restart it, and for a local forward fail with PORT_IN_USE on its own port.
 *
 * A non-retryable failure (see `isNonRetryable`: a key prompt cancelled or expired, a refused
 * credential, a host key that changed) is the host's, not the tunnel's. The answer comes from the
 * user or from the credentials, so the next tunnel of the same host would get the same answer after
 * asking the same question again, a second key sheet for a FIDO2 host. Once one tunnel of a host
 * failed that way, the other tunnels of that host are not attempted: each gets the same error
 * result, is marked in error with the same message, and is held out of the automatic reconnection
 * like the first one. The other hosts are not concerned, and a retryable failure (network, timeout,
 * expired authentication window) stays the tunnel's own: the next tunnel still tries.
 *
 * The rules above are those of [startTunnels], which anything that starts a list of tunnels one after
 * the other goes through: the auto-start, and the restart of the tunnels that waited for the user
 * (see [ResumeTunnelsUseCase]). Only the auto-start is once per process (see [AutoStartTunnelsGate],
 * which the screen applies and this class does not).
 */
class AutoStartTunnelsUseCase @Inject constructor(
    private val tunnelRepository: TunnelRepository,
    private val startTunnel: StartTunnelUseCase,
    private val liveTunnels: LiveTunnelLookup,
    private val tunnelManager: SshTunnelManager,
) {
    suspend operator fun invoke(): List<Pair<String, SshResult<Unit>>> =
        startTunnels(tunnelRepository.getAutoStartTunnels())

    /**
     * Starts [tunnels], one after the other, by the rules of this class: a tunnel that already runs
     * or is gone has no result, and a non-retryable failure of a host is the result of the other
     * tunnels of that host too, without a new prompt. No gate: the caller decides when it may run.
     */
    suspend fun startTunnels(tunnels: List<TunnelConfig>): List<Pair<String, SshResult<Unit>>> {
        if (tunnels.isEmpty()) return emptyList()

        Timber.i("Démarrage de ${tunnels.size} tunnel(s)")

        // Per host id, the non-retryable failure that ended the first tunnel tried on it.
        val refusedHosts = HashMap<String, SshResult.Error>()

        return tunnels.mapNotNull { listed ->
            // The query already leaves out the tunnels of a deleted host, but a row or a host can
            // be deleted between the query and this start. Start from the stored row, and skip a
            // tunnel that is gone: no ghost start, and no error to show for a tunnel that no
            // longer exists.
            val config = liveTunnels.find(listed.id) ?: return@mapNotNull null
            // "Already running" and the reservation of this start with the manager are one
            // decision. A stop by hand that lands after it, while the shared StartTunnelUseCase
            // reads the host (a suspension) before its `markStarting`, is seen by the manager: this
            // start then lifts no exclusion, writes no state, and fades out at the manager's
            // `launch*` (the connection will have been opened, then given back: a window of a few
            // milliseconds). A start by hand that lands there, for instance from a shortcut launched
            // together with this auto-start, is the more recent one and the only one that goes on.
            if (!tunnelManager.reserveAutoStart(config)) return@mapNotNull null
            // A port that cannot be used (a tunnel that came from the sync or an import) is refused
            // before any connection, so before any prompt: the tunnel goes to error with the reason
            // and is held out of the reconnection. It is the tunnel's own failure, not the host's:
            // the next tunnels of the same host are tried as usual.
            tunnelManager.refuseInvalidPorts(config)?.let { invalid ->
                Timber.w("Auto-start tunnel refused: invalid port")
                // Stopped by hand in the meantime: nothing was written, so nothing to tell.
                return@mapNotNull if (tunnelManager.isStartAbandoned(config)) null else config.id to invalid
            }
            val refused = refusedHosts[config.hostId]
            if (refused != null) {
                // Not attempted. The error and the hold are what the first tunnel of the host
                // ended with, so that its card shows the same thing and no network event retries.
                Timber.w("Auto-start tunnel skipped, host-wide failure already seen (${refused.code})")
                tunnelManager.markError(config, refused.message)
                // Stopped by hand since the reservation: the manager wrote nothing, and the user is
                // not told about a tunnel they closed.
                if (tunnelManager.isStartAbandoned(config)) return@mapNotNull null
                tunnelManager.holdReconnectIfNonRetryable(config.id, refused)
                return@mapNotNull config.id to refused
            }
            val result = try {
                startTunnel(config)
            } catch (e: VaultAuthExpiredException) {
                // A last guard, not expected on this path: the shared start reads no secret itself,
                // the connection turns the vault's expiry into an AUTH_EXPIRED result, and the
                // session provider folds any other exception into an error result. It catches the
                // shared exception, the one a shared collaborator would throw. If it happened all
                // the same, the shared start has already marked the tunnel STARTING and is gone:
                // the card is put in error here, so that it never stays STARTING. Not held out of
                // the reconnection, like any AUTH_EXPIRED (see isNonRetryable).
                Timber.w("Auto-start tunnel: vault auth expired")
                // Fixed text, translated at display time by the card of the tunnel.
                val message = TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE
                tunnelManager.markError(config, message)
                SshResult.Error(SshErrorCode.AUTH_EXPIRED, message)
            } catch (e: CancellationException) {
                // The shared start sets no error on a cancellation: the card would stay in STARTING
                // for ever. Error, held out of the reconnection (an abandoned start must not come
                // back with a key sheet at the next network event), then the cancellation goes on.
                tunnelManager.markStartCancelled(config, TUNNEL_START_CANCELLED_MESSAGE, hold = true)
                throw e
            } finally {
                // An exception before the shared start announced the tunnel (the read of the host) would
                // leave the reservation as a start "on its way" for ever, and the card of a tunnel in
                // error could not be cleared. A no-op once the start was announced or finished.
                tunnelManager.releaseReservation(config)
            }
            if (result is SshResult.Error) {
                // Stopped by hand (or replaced by a start the user made) while it connected: the
                // tunnel is not this start's any more, so there is no result to tell, no failure to
                // log or count, and no hold (the manager wrote nothing for it). Either the manager
                // says so, or the result is the one of a start that faded out (see isStartWithdrawn).
                if (tunnelManager.isStartWithdrawn(config, result)) return@mapNotNull null
                // The code only: the message can name a host or a port.
                Timber.w("Échec auto-démarrage tunnel (${result.code})")
                // A refused or cancelled authentication is not retried by the network events, and
                // it is the host's: the next tunnels of this host are not attempted.
                if (tunnelManager.holdReconnectIfNonRetryable(config.id, result)) {
                    refusedHosts[config.hostId] = result
                }
            }
            config.id to result
        }
    }
}
