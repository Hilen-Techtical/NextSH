// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import fr.techtical.nextsh.R

/**
 * Channel of the "action required" notification (a key to touch, a finger to present, a server to
 * confirm), apart from the silent tunnel one.
 */
internal const val AUTH_REQUIRED_CHANNEL_ID = "auth_required_channel"

/**
 * Creates the action-required channel when it does not exist yet (the call is idempotent).
 *
 * Default importance, unlike [ensureTunnelNotificationChannel]: this one asks the user to do
 * something (open the app and touch a key or a finger, or confirm the identity of a server), so it
 * makes a sound and shows in the status bar. The foreground notification and the "stopped" alerts
 * stay silent.
 */
internal fun ensureAuthRequiredNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        AUTH_REQUIRED_CHANNEL_ID,
        context.getString(R.string.auth_required_channel_name),
        NotificationManager.IMPORTANCE_DEFAULT,
    ).apply {
        description = context.getString(R.string.auth_required_channel_description)
    }
    context.getSystemService(NotificationManager::class.java)
        .createNotificationChannel(channel)
}
