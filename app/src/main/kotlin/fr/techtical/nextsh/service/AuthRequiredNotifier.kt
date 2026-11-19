// Copyright (c) 2026 Thibaud Maneval - Techtical (https://www.techtical.fr)
// SPDX-License-Identifier: MIT

package fr.techtical.nextsh.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.techtical.nextsh.MainActivity
import fr.techtical.nextsh.R
import fr.techtical.nextsh.core.AuthResumeNotice
import fr.techtical.nextsh.core.PendingAuthResume
import fr.techtical.nextsh.core.WaitingChange
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user that tunnels stopped reconnecting because their host needs them in front of the
 * phone (a security key to touch, a fingerprint to present, the identity of a server to confirm)
 * and the app was not on screen. Behind an interface so the reconnection can be tested without
 * Android.
 */
interface AuthRequiredNotifier {

    /**
     * Adds the tunnel to the one notification (updated, not duplicated, when more tunnels wait) and
     * posts it. Does nothing when the system does not allow notifications: the tunnel is held in
     * error with its message either way, this only tells the user sooner.
     *
     * [label] is user text: it goes to the notification only, never to a log.
     */
    fun notifyAuthRequired(tunnelId: String, label: String)

    /** Takes the notification away, once the user came back to start the tunnels. */
    fun cancel()

    /**
     * Tells which tunnels are in error now ([erroredIds]). A tunnel that was reported and is not any
     * more (started again by any path, stopped, deleted) is not waiting for the user: it leaves the
     * notification's list. The notification goes when none is left, and while it is on screen with
     * some left it is posted again, silently, with the tunnels that remain (it must not keep naming
     * and counting the ones that are gone). Cheap enough to call at every change of the tunnel
     * states.
     */
    fun retainWaiting(erroredIds: Set<String>)
}

/**
 * The texts of the notification, resolved from resources so that the language follows the device.
 * A pure function of [Resources]: tested on the JVM with a mocked [Resources].
 *
 * @property lines one line per tunnel for the expanded notification, empty for a single tunnel
 *   (the text already names it).
 * @property more "+N more" when [lines] had to be cut, null otherwise.
 * @property publicText what a locked screen shows instead of [text]: it names no tunnel.
 *
 * [text] and [lines] carry tunnel names, which are user text: [toString] hides them so that a log
 * line or a debugger view cannot (see also `AuthResumeNotice`).
 */
internal data class AuthRequiredMessage(
    val title: String,
    val text: String,
    val lines: List<String>,
    val more: String?,
    val publicText: String,
) {
    override fun toString(): String = "AuthRequiredMessage"

    companion object {
        /** Lines shown in the expanded notification, like the foreground notification. */
        const val MAX_LINES = 5

        fun of(resources: Resources, labels: List<String>): AuthRequiredMessage {
            val count = labels.size
            val title = resources.getString(R.string.auth_required_title)
            val publicText = resources.getString(R.string.auth_required_public_text)
            if (count == 1) {
                return AuthRequiredMessage(
                    title = title,
                    text = resources.getString(R.string.auth_required_text_single, labels[0]),
                    lines = emptyList(),
                    more = null,
                    publicText = publicText,
                )
            }
            return AuthRequiredMessage(
                title = title,
                text = resources.getQuantityString(R.plurals.auth_required_text_count, count, count),
                lines = labels.take(MAX_LINES),
                more = if (count > MAX_LINES) {
                    resources.getString(R.string.tunnel_notification_more, count - MAX_LINES)
                } else {
                    null
                },
                publicText = publicText,
            )
        }
    }
}

/**
 * Open for the test of its decisions (when to post, when to leave alone): the three members that
 * touch the system, [mayPost], [isShowing] and [show], are the ones a test replaces.
 */
@Singleton
open class AndroidAuthRequiredNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pendingResume: PendingAuthResume,
) : AuthRequiredNotifier {

    /**
     * Makes each change of the waiting list and the posting of what it left one step. The list has
     * its own lock, but a notice is computed under it and shown after: two threads (the reconnection
     * reports a tunnel while the service prunes the list) could compute A then B and show B then A,
     * leaving the older notice on screen, naming tunnels that no longer wait or missing one that
     * does. Held across the Android calls, which are quick and make no call back into this class.
     */
    private val postLock = Any()

    override fun notifyAuthRequired(tunnelId: String, label: String) {
        synchronized(postLock) {
            if (!mayPost()) {
                Timber.d("AuthRequiredNotifier: notifications not allowed, nothing posted")
                return
            }
            // A new token with each registration: the intent of an earlier notification is void.
            show(pendingResume.register(tunnelId, label))
        }
    }

    override fun cancel() {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    override fun retainWaiting(erroredIds: Set<String>) {
        synchronized(postLock) {
            when (val change = pendingResume.retainWaiting(erroredIds)) {
                WaitingChange.Unchanged -> Unit
                WaitingChange.Emptied -> cancel()
                // Only an update of what is on screen: a notification the user dismissed, or one that
                // was never posted, is not brought back (and would sound again) for a smaller list.
                is WaitingChange.Narrowed -> if (mayPost() && isShowing()) show(change.notice)
            }
        }
    }

    // InlinedApi: POST_NOTIFICATIONS is a plain String constant, inlined at compile time, and the
    // lambda that reads it only runs from Android 13 (see mayPostNotifications).
    @SuppressLint("InlinedApi")
    internal open fun mayPost(): Boolean = mayPostNotifications(Build.VERSION.SDK_INT) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** True while the notification is in the shade. */
    internal open fun isShowing(): Boolean = try {
        NotificationManagerCompat.from(context).activeNotifications.any { it.id == NOTIFICATION_ID }
    } catch (e: SecurityException) {
        false
    }

    /**
     * Posts the notification for [notice], or updates the one that is there: same id, and
     * `setOnlyAlertOnce` keeps an update silent.
     */
    internal open fun show(notice: AuthResumeNotice) {
        try {
            ensureAuthRequiredNotificationChannel(context)
            val message = AuthRequiredMessage.of(context.resources, notice.labels)
            val publicVersion = NotificationCompat.Builder(context, AUTH_REQUIRED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tunnel)
                .setContentTitle(message.title)
                .setContentText(message.publicText)
                .build()
            val builder = NotificationCompat.Builder(context, AUTH_REQUIRED_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tunnel)
                .setContentTitle(message.title)
                .setContentText(message.text)
                .setContentIntent(contentIntent(notice.token))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                // Adding or removing a tunnel updates the notification without sounding again; once
                // the user dismissed it, the next post is a new notification and does.
                .setOnlyAlertOnce(true)
                // The tunnel names are private: a locked screen shows the public version.
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
            if (message.lines.isNotEmpty()) {
                val style = NotificationCompat.InboxStyle()
                message.lines.forEach { style.addLine(it) }
                message.more?.let { style.setSummaryText(it) }
                builder.setStyle(style)
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            Timber.w("AuthRequiredNotifier: notification refused (${e::class.java.simpleName})")
        }
    }

    /**
     * Opens the app with the one-time token, never with tunnel ids: `MainActivity` is exported, so
     * anything it reads from its intent can be forged by another app (see [PendingAuthResume]).
     * FLAG_UPDATE_CURRENT puts the new token in the intent of the notification that is already
     * there, FLAG_IMMUTABLE keeps another app from filling in the intent.
     */
    private fun contentIntent(token: String): PendingIntent = PendingIntent.getActivity(
        context, REQUEST_CODE,
        Intent(context, MainActivity::class.java).putExtra(PendingAuthResume.EXTRA_TOKEN, token),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        // Next to the foreground notification (1001) and the transfer one (1002), away from the
        // 2000 range of the "stopped" alerts. One id: the notification is updated, never stacked.
        const val NOTIFICATION_ID = 1003

        // PendingIntent identity ignores the extras: two intents for the same component with the same
        // request code are the same PendingIntent, and FLAG_UPDATE_CURRENT would then put the token
        // into the intent of another notification. 0 is the request code of the other notifications
        // that open MainActivity (the foreground one, the transfer one, the "stopped" alerts), so
        // this one takes its own, and keeps it across posts: the extras are replaced in place,
        // which is what voids the old token.
        const val REQUEST_CODE = 1
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthRequiredNotifierModule {
    @Binds
    @Singleton
    abstract fun bindAuthRequiredNotifier(
        impl: AndroidAuthRequiredNotifier,
    ): AuthRequiredNotifier
}
