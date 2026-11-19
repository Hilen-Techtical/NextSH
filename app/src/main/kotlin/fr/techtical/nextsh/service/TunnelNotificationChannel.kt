// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import fr.techtical.nextsh.R

/** Channel of the tunnel notifications: the foreground service and the "stopped" alerts. */
internal const val TUNNEL_CHANNEL_ID = "tunnel_channel"

/**
 * Creates the tunnel channel when it does not exist yet (the call is idempotent).
 *
 * Low importance on purpose: silent, no heads-up. The foreground notification must not make a
 * sound every time a tunnel changes state, and the alert that a tunnel was stopped is
 * information, not an emergency.
 */
internal fun ensureTunnelNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        TUNNEL_CHANNEL_ID,
        context.getString(R.string.tunnel_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.tunnel_channel_description)
        setShowBadge(false)
    }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}
