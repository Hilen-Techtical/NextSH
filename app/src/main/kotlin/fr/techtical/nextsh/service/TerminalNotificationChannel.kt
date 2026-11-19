// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import fr.techtical.nextsh.R

/**
 * Channel of the notification of the terminal sessions service. Not the tunnel channel: the user
 * can mute or hide the sessions without touching the tunnels, and the other way round.
 */
internal const val TERMINAL_CHANNEL_ID = "terminal_channel"

/**
 * Creates the terminal channel when it does not exist yet (the call is idempotent).
 *
 * Low importance on purpose: silent, no heads-up. The notification only says that sessions are
 * kept open and carries the "close all" action.
 */
internal fun ensureTerminalNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        TERMINAL_CHANNEL_ID,
        context.getString(R.string.terminal_channel_name),
        NotificationManager.IMPORTANCE_LOW,
    ).apply {
        description = context.getString(R.string.terminal_channel_description)
        setShowBadge(false)
    }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}
