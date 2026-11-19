// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.core

import android.content.Intent

/**
 * What `MainActivity` does with an incoming intent as far as the "authentication required"
 * notification is concerned. A function of its inputs so that it is tested without an activity.
 *
 * The activity is exported, so any app can send it any extras: the intent of the notification
 * carries a random token and no tunnel id, and only a token that [resume] issued and has not spent
 * yet gets the waiting tunnels started (see [PendingAuthResume]). A wrong, old or replayed token
 * does nothing. After the death of the process there is nothing to spend, and the tap just opens
 * the app.
 *
 * - A request that was validated by a tap and then waited too long is forgotten first
 *   ([PendingAuthResume.dropExpired]), for any intent: a launcher shortcut that comes hours after a
 *   tap must not meet a stale request. A notification that is tapped late is another matter: the
 *   tap is the consent, however long the notification waited.
 * - The token is taken out of [intent] whatever the outcome: a recreation of the activity (a
 *   rotation) hands the same intent to the next instance, and the token has no business staying in
 *   it. Nothing else of the intent is touched (the launcher shortcut extras are read elsewhere).
 * - [cancelNotification] is called only when the token was good: the notification has done its job.
 *
 * Returns true when the token was good.
 */
internal fun spendAuthResumeToken(
    intent: Intent?,
    resume: PendingAuthResume,
    cancelNotification: () -> Unit,
): Boolean {
    resume.dropExpired()
    val token = intent?.getStringExtra(PendingAuthResume.EXTRA_TOKEN) ?: return false
    intent.removeExtra(PendingAuthResume.EXTRA_TOKEN)
    if (resume.consume(token) == null) return false
    cancelNotification()
    return true
}
