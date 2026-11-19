// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core.shortcut

import android.content.Intent
import timber.log.Timber

/**
 * What a launcher shortcut asks the app to do, once its intent was checked: the host to connect to,
 * the tunnel to start, or the terminal to open. Only [readShortcutRequest] makes one, from an intent
 * that carries the token of this install, so a value of this type is never a forged request.
 */
sealed interface ShortcutRequest {
    data class ConnectHost(val hostId: String) : ShortcutRequest
    data class StartTunnel(val tunnelId: String) : ShortcutRequest

    /**
     * Open the terminal on the tabs that exist, without a host. Not a launcher shortcut: it is the
     * tap on the notification of the terminal service, which takes the same checked path because
     * the activity is exported and the notification must not be forgeable either (see
     * [ShortcutUpdater.openTerminalIntent]). It carries nothing, so there is nothing to validate
     * beyond the token.
     */
    data object OpenTerminal : ShortcutRequest
}

/**
 * What `MainActivity` takes from an incoming intent as far as the launcher shortcuts are
 * concerned. A function of its inputs so that it is tested without an activity.
 *
 * The activity is exported, so any app can send it the extras of a shortcut: only an intent that
 * carries the token of this install ([ShortcutTokenStore]) is a shortcut the app published. A
 * missing or wrong token, an unknown action or a missing id gives null, and the app just opens. A
 * rejected intent is logged as a warning, without the token or the ids.
 *
 * - The shortcut extras, token included, are taken out of [intent] whatever the outcome. The
 *   activity keeps this very intent across a rotation, so what stays in it would act again at every
 *   recreation, and the token has no business staying in it. The intent is cleaned in place and not
 *   copied for `setIntent`: the system hands the original object to the next instance after a
 *   rotation, `setIntent` does not reach it.
 * - Nothing is read, only cleaned, when the intent is a *redelivery* of one that was already
 *   handled: the activity is being restored ([restored], `savedInstanceState != null`: after the
 *   death of the process the system hands the original intent back), or it was started from the
 *   recents list, which relaunches the task with its first intent. Connecting again then would be a
 *   surprise.
 *
 * Returns the request, or null when there is none to act on.
 */
internal fun readShortcutRequest(
    intent: Intent?,
    tokens: ShortcutTokenStore,
    restored: Boolean,
): ShortcutRequest? {
    if (intent == null) return null
    val action = intent.getStringExtra(ShortcutUpdater.EXTRA_SHORTCUT_ACTION)
    val hostId = intent.getStringExtra(ShortcutUpdater.EXTRA_HOST_ID)
    val tunnelId = intent.getStringExtra(ShortcutUpdater.EXTRA_TUNNEL_ID)
    val token = intent.getStringExtra(ShortcutUpdater.EXTRA_SHORTCUT_TOKEN)
    intent.removeExtra(ShortcutUpdater.EXTRA_SHORTCUT_ACTION)
    intent.removeExtra(ShortcutUpdater.EXTRA_HOST_ID)
    intent.removeExtra(ShortcutUpdater.EXTRA_TUNNEL_ID)
    intent.removeExtra(ShortcutUpdater.EXTRA_SHORTCUT_TOKEN)

    // Not a shortcut at all (the launcher icon, a notification that carries no shortcut): nothing to say.
    if (action == null) return null
    val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
    if (restored || fromHistory) return null

    if (!tokens.matches(token)) {
        Timber.w("Shortcut ignored: missing or invalid token")
        return null
    }
    val request = when (action) {
        ShortcutUpdater.ACTION_CONNECT_HOST -> hostId?.takeIf { it.isNotBlank() }?.let(ShortcutRequest::ConnectHost)
        ShortcutUpdater.ACTION_START_TUNNEL -> tunnelId?.takeIf { it.isNotBlank() }?.let(ShortcutRequest::StartTunnel)
        ShortcutUpdater.ACTION_OPEN_TERMINAL -> ShortcutRequest.OpenTerminal
        else -> null
    }
    if (request == null) Timber.w("Shortcut ignored: unknown action or missing target")
    return request
}
