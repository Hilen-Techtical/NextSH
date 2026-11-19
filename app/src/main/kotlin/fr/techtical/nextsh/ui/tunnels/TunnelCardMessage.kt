// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import fr.techtical.nextsh.domain.model.TunnelState
import fr.techtical.nextsh.domain.model.TunnelStatus

/** What the message area of a tunnel card shows in place of the diagram. */
internal sealed interface TunnelCardMessage {
    /** Neither a reason nor an error: the card shows its diagram. */
    data object None : TunnelCardMessage

    /** The reason of a reconnection (RECONNECTING, or STARTING of a retry): amber, no dismiss button. */
    data class Reconnecting(val message: String) : TunnelCardMessage

    /** The tunnel gave up (ERROR): red, with a dismiss button. */
    data class Failed(val message: String) : TunnelCardMessage
}

/**
 * Picks the banner of a tunnel card from its state. A STARTING launched by the user has no message
 * and keeps the diagram; a blank message is the same as none.
 */
internal fun tunnelCardMessage(state: TunnelState?): TunnelCardMessage {
    val message = state?.errorMessage
    return when (state?.status) {
        TunnelStatus.ERROR ->
            if (message != null) TunnelCardMessage.Failed(message) else TunnelCardMessage.None
        TunnelStatus.RECONNECTING, TunnelStatus.STARTING ->
            if (!message.isNullOrBlank()) TunnelCardMessage.Reconnecting(message) else TunnelCardMessage.None
        else -> TunnelCardMessage.None
    }
}
