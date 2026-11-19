// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.desktop.service

import fr.techtical.nextsh.desktop.generated.resources.Res
import fr.techtical.nextsh.desktop.generated.resources.tray_stopped_deleted_title_many
import fr.techtical.nextsh.desktop.generated.resources.tray_stopped_deleted_title_one
import fr.techtical.nextsh.desktop.generated.resources.tunnels_stopped_deleted_many
import fr.techtical.nextsh.desktop.generated.resources.tunnels_stopped_deleted_one
import fr.techtical.nextsh.shared.util.Logger
import fr.techtical.nextsh.shared.util.errorClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

private const val TAG = "TunnelDeletionNotifier"

/** A balloon is a few lines of text: a label longer than this is cut. */
private const val MAX_LABEL_LENGTH = 80

/** What [TunnelDeletionNotifier] needs from the system tray: implemented by [DesktopTunnelTray]. */
interface TrayMessenger {
    /** False while the tray icon is not registered with the OS (unsupported platform, not started yet). */
    val isAvailable: Boolean

    /** Shows an informational system notification. */
    fun displayInfo(title: String, message: String)
}

/**
 * Tells the user, with a system notification from the tray, that tunnels were stopped because they, or
 * their host, were deleted on another device ([DesktopTunnelService.deletionStops]).
 *
 * One notification per event: a host deleted elsewhere stops all its tunnels in one pass, the event holds
 * them all, and they get one message (the name of the tunnel when there is one, the count otherwise).
 * Without a tray there is nothing to show and nothing is queued: the banner of the tunnel list covers it.
 *
 * The tray is behind [TrayMessenger] so that the grouping and the unavailable case can be tested without
 * AWT. Texts are localized (compose-resources) and carry labels: they are never logged.
 */
class TunnelDeletionNotifier(private val messenger: TrayMessenger) {

    /** Shows the notification for [stop], or nothing when it is empty or the tray is not available. */
    suspend fun notify(stop: TunnelDeletionStop) {
        if (stop.tunnels.isEmpty() || !messenger.isAvailable) return
        val single = stop.tunnels.singleOrNull()
        val title: String
        val message: String
        if (single != null) {
            title = getString(Res.string.tray_stopped_deleted_title_one)
            message = getString(Res.string.tunnels_stopped_deleted_one, single.label.truncated())
        } else {
            title = getString(Res.string.tray_stopped_deleted_title_many)
            message = getString(Res.string.tunnels_stopped_deleted_many, stop.tunnels.size)
        }
        messenger.displayInfo(title, message)
    }

    /**
     * Notifies for every event of [stops] until the returned job is cancelled. A failure on one event
     * (a tray that refuses the message) is logged by class name and does not end the observation.
     */
    fun observe(scope: CoroutineScope, stops: Flow<TunnelDeletionStop>): Job = scope.launch {
        stops.collect { stop ->
            try {
                notify(stop)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(TAG, "notification failed (${errorClass(e)})")
            }
        }
    }

    /**
     * Cut by code points, not by chars: a label with emoji must not be cut between the two halves of a
     * surrogate pair, which would leave a lone surrogate in the balloon.
     */
    private fun String.truncated(): String =
        if (codePointCount(0, length) <= MAX_LABEL_LENGTH) this
        else substring(0, offsetByCodePoints(0, MAX_LABEL_LENGTH - 1)).trimEnd() + "…"
}
