// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import androidx.annotation.StringRes
import fr.techtical.nextsh.core.ssh.tunnelMessageRes

/**
 * The string resource that says [message] in the language of the user, or null when [message] is
 * shown as it is.
 *
 * `SshTunnelManager` has no `Context`, so the error it stores on a tunnel card is a plain string. The
 * messages it makes up itself are fixed French literals, and the card maps them here, at display
 * time, with the table of [tunnelMessageRes] (the same one the reconnection uses when it quotes
 * such a message). The messages that come from a connection are already resolved with the
 * application `Context` when the result is built.
 *
 * The message of a reconnection that gave up ("failed after N attempts: reason") is not mapped
 * either, and does not need to be: it carries a number and the reason of the last attempt, so it is
 * made from string resources by `ReconnectTunnelsUseCase` with the application `Context`, like the
 * messages of a connection, and reaches the card already in the language of the user.
 *
 * The reasons of a start or a stop that fails (local port in use, session or host gone, an exception
 * the start did not expect) are fixed texts too, mapped by the same table. The message of an
 * exception is never stored on a tunnel, so nothing in English or naming a host reaches the card.
 */
@StringRes
internal fun tunnelErrorMessageRes(message: String): Int? = tunnelMessageRes(message)
