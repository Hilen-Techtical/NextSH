// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.ui.tunnels

import androidx.annotation.StringRes
import fr.techtical.nextsh.R
import fr.techtical.nextsh.domain.model.TunnelStatus
import fr.techtical.nextsh.domain.model.TunnelType

/**
 * Les mots de l'écran des tunnels qui dépendent d'un énuméré de `:shared`.
 *
 * `:shared` ne connaît pas les ressources Android : le statut et le type d'un tunnel sont dits ici,
 * en ressource, dans la langue de l'utilisateur. Ce sont des mots en casse de phrase ("Arrêté") ;
 * la pastille et le badge les mettent en capitales à l'affichage.
 */
@StringRes
internal fun tunnelStatusLabelRes(status: TunnelStatus): Int = when (status) {
    TunnelStatus.ACTIVE -> R.string.tunnel_status_active
    TunnelStatus.STARTING -> R.string.tunnel_status_starting
    TunnelStatus.RECONNECTING -> R.string.tunnel_status_reconnecting
    TunnelStatus.ERROR -> R.string.tunnel_status_error
    TunnelStatus.STOPPED -> R.string.tunnel_status_stopped
}

/** Le nom court d'un type de tunnel : la pastille de la carte et le sélecteur du formulaire. */
@StringRes
internal fun tunnelTypeLabelRes(type: TunnelType): Int = when (type) {
    TunnelType.LOCAL_FORWARD -> R.string.tunnel_type_local
    TunnelType.REMOTE_FORWARD -> R.string.tunnel_type_remote
    TunnelType.DYNAMIC_SOCKS5 -> R.string.tunnel_type_socks5
}

/** La phrase d'aide sous le sélecteur de type du formulaire. */
@StringRes
internal fun tunnelTypeHelperRes(type: TunnelType): Int = when (type) {
    TunnelType.LOCAL_FORWARD -> R.string.helper_tunnel_type_local
    TunnelType.REMOTE_FORWARD -> R.string.helper_tunnel_type_remote
    TunnelType.DYNAMIC_SOCKS5 -> R.string.helper_tunnel_type_socks5
}
