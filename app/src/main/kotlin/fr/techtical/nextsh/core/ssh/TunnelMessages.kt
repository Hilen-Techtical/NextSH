// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.ssh

import androidx.annotation.StringRes
import fr.techtical.nextsh.R

/**
 * The string resource that says [message] in the language of the user, or null when [message] is
 * not one of the fixed texts a tunnel carries.
 *
 * [SshTunnelManager] has no `Context`, so the error it stores on a tunnel is a plain string, and the
 * ones it makes up itself are French literals ([TUNNEL_START_CANCELLED_MESSAGE],
 * [TUNNEL_RECONNECT_INTERRUPTED_MESSAGE], [TUNNEL_AUTH_REQUIRED_MESSAGE], [TUNNEL_SESSION_LOST_MESSAGE],
 * [TUNNEL_LISTENER_STOPPED_MESSAGE], [TUNNEL_SOCKS_STOPPED_MESSAGE], [TUNNEL_NO_ANSWER_MESSAGE],
 * [TUNNEL_NETWORK_LOST_MESSAGE], and the reasons of a start or a
 * stop that fails: [TUNNEL_START_FAILED_MESSAGE], [TUNNEL_PORT_IN_USE_MESSAGE],
 * [TUNNEL_SESSION_NOT_FOUND_MESSAGE], [TUNNEL_HOST_NOT_FOUND_MESSAGE], [TUNNEL_CONNECT_UNEXPECTED_MESSAGE],
 * [TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE], [TUNNEL_NOT_FOUND_MESSAGE], [TUNNEL_STOP_FAILED_MESSAGE], the ports
 * a tunnel cannot use: [TUNNEL_LOCAL_PORT_INVALID_MESSAGE], [TUNNEL_REMOTE_PORT_INVALID_MESSAGE],
 * [TUNNEL_LOCAL_PORT_RESERVED_MESSAGE], the refusal of a remote forward: [TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE],
 * and [TUNNEL_START_STOPPED_MESSAGE], a result that is never shown but has its text all the same) that
 * would be shown raw to an English-speaking user. They are mapped here, at display time: by the card
 * of the tunnel, and by the reconnection when it quotes the reason of the last attempt in its own
 * message. The messages that come from a connection are already resolved with the application
 * `Context` when the result is built.
 *
 * None of these texts carries a port, an identifier or the message of an exception: the message of an
 * exception comes from SSHJ or the system, in English, and can name a host or an address. The class of
 * the exception is in the log, and the card says that the tunnel could not start.
 */
@StringRes
fun tunnelMessageRes(message: String): Int? = when (message) {
    TUNNEL_START_CANCELLED_MESSAGE -> R.string.tunnel_error_start_cancelled
    TUNNEL_RECONNECT_INTERRUPTED_MESSAGE -> R.string.tunnel_error_reconnect_interrupted
    TUNNEL_AUTH_REQUIRED_MESSAGE -> R.string.tunnel_error_auth_required
    TUNNEL_SESSION_LOST_MESSAGE -> R.string.tunnel_error_session_lost
    TUNNEL_NO_ANSWER_MESSAGE -> R.string.tunnel_error_no_answer
    TUNNEL_NETWORK_LOST_MESSAGE -> R.string.tunnel_error_network_lost
    TUNNEL_LISTENER_STOPPED_MESSAGE -> R.string.tunnel_error_listener_stopped
    TUNNEL_SOCKS_STOPPED_MESSAGE -> R.string.tunnel_error_socks_stopped
    TUNNEL_START_FAILED_MESSAGE -> R.string.tunnel_error_start_failed
    TUNNEL_PORT_IN_USE_MESSAGE -> R.string.tunnel_error_port_in_use
    TUNNEL_SESSION_NOT_FOUND_MESSAGE -> R.string.tunnel_error_session_not_found
    TUNNEL_HOST_NOT_FOUND_MESSAGE -> R.string.tunnel_error_host_not_found
    TUNNEL_CONNECT_UNEXPECTED_MESSAGE -> R.string.tunnel_error_connect_unexpected
    TUNNEL_VAULT_AUTH_EXPIRED_MESSAGE -> R.string.tunnel_error_vault_auth_expired
    TUNNEL_NOT_FOUND_MESSAGE -> R.string.tunnel_error_not_found
    TUNNEL_STOP_FAILED_MESSAGE -> R.string.tunnel_error_stop_failed
    TUNNEL_LOCAL_PORT_INVALID_MESSAGE -> R.string.tunnel_error_local_port_invalid
    TUNNEL_REMOTE_PORT_INVALID_MESSAGE -> R.string.tunnel_error_remote_port_invalid
    TUNNEL_LOCAL_PORT_RESERVED_MESSAGE -> R.string.tunnel_error_local_port_reserved
    TUNNEL_REMOTE_FORWARD_REFUSED_MESSAGE -> R.string.tunnel_error_remote_forward_refused
    TUNNEL_START_STOPPED_MESSAGE -> R.string.tunnel_error_start_stopped
    else -> null
}
