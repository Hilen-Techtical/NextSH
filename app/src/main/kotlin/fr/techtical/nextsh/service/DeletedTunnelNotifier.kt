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
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user that tunnels were stopped because they, or their host, were deleted on another
 * device. Behind an interface so the code that stops the tunnels can be tested without Android.
 */
interface DeletedTunnelNotifier {

    /**
     * Posts one notification for all of [labels] (a batch stopped together is grouped, never one
     * notification per tunnel). Does nothing for an empty list, and nothing when the system does
     * not allow notifications: the tunnel is stopped either way, this is only information.
     *
     * The labels are user text: they go to the notification only, never to a log.
     */
    fun notifyStopped(labels: List<String>)

    /**
     * Takes down the notifications this notifier posted and that are still up: they name tunnels, which
     * are private data that the "Wipe the vault" action erases. No-op by default.
     */
    fun cancelAll() {}
}

/**
 * The texts of the notification, resolved from resources so that the language follows the
 * device. A pure function of [Resources]: tested on the JVM with a mocked [Resources].
 *
 * @property lines one line per tunnel for the expanded notification, empty for a single tunnel
 *   (the text already names it).
 * @property more "+N more" when [lines] had to be cut, null otherwise.
 */
internal data class DeletedTunnelMessage(
    val title: String,
    val text: String,
    val lines: List<String>,
    val more: String?,
) {
    companion object {
        /** Lines shown in the expanded notification, like the foreground notification. */
        const val MAX_LINES = 5

        fun of(resources: Resources, labels: List<String>): DeletedTunnelMessage {
            val count = labels.size
            val title = resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_title, count)
            if (count == 1) {
                return DeletedTunnelMessage(
                    title = title,
                    text = resources.getString(R.string.tunnel_deleted_elsewhere_text_single, labels[0]),
                    lines = emptyList(),
                    more = null,
                )
            }
            return DeletedTunnelMessage(
                title = title,
                text = resources.getQuantityString(R.plurals.tunnel_deleted_elsewhere_text_count, count, count),
                lines = labels.take(MAX_LINES),
                more = if (count > MAX_LINES) {
                    resources.getString(R.string.tunnel_notification_more, count - MAX_LINES)
                } else {
                    null
                },
            )
        }
    }
}

/**
 * Whether a notification may be posted. From Android 13 it needs the runtime permission
 * `POST_NOTIFICATIONS` (asked from the tunnel list when the first tunnel starts); before, the
 * system allows it. Not granted: nothing is posted, the permission is not asked from the
 * background.
 *
 * The SDK check lives here only, and [permissionGranted] is not called below Android 13.
 */
internal fun mayPostNotifications(sdkInt: Int, permissionGranted: () -> Boolean): Boolean =
    sdkInt < Build.VERSION_CODES.TIRAMISU || permissionGranted()

@Singleton
class AndroidDeletedTunnelNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeletedTunnelNotifier {

    private val nextNotificationId = AtomicInteger(FIRST_NOTIFICATION_ID)

    // InlinedApi: POST_NOTIFICATIONS is a plain String constant, inlined at compile time, and the
    // lambda that reads it only runs from Android 13 (see mayPostNotifications).
    @SuppressLint("InlinedApi")
    override fun notifyStopped(labels: List<String>) {
        if (labels.isEmpty()) return

        val allowed = mayPostNotifications(Build.VERSION.SDK_INT) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!allowed) {
            Timber.d("DeletedTunnelNotifier: notifications not allowed, nothing posted")
            return
        }

        try {
            ensureTunnelNotificationChannel(context)
            val message = DeletedTunnelMessage.of(context.resources, labels)
            val builder = NotificationCompat.Builder(context, TUNNEL_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tunnel)
                .setContentTitle(message.title)
                .setContentText(message.text)
                .setContentIntent(contentIntent())
                .setAutoCancel(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                // The tunnel names are private: hidden on a locked screen, like the foreground one.
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            if (message.lines.isNotEmpty()) {
                val style = NotificationCompat.InboxStyle()
                message.lines.forEach { style.addLine(it) }
                message.more?.let { style.setSummaryText(it) }
                builder.setStyle(style)
            }
            NotificationManagerCompat.from(context)
                .notify(nextNotificationId.getAndIncrement(), builder.build())
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the call.
            Timber.w("DeletedTunnelNotifier: notification refused (${e::class.java.simpleName})")
        }
    }

    /**
     * Cancels the notifications of the batches that were posted and not read. The ids start again at
     * [FIRST_NOTIFICATION_ID] at each process, so the counter is no guide: the active notifications of
     * the channel with an id of at least [FIRST_NOTIFICATION_ID] are taken down (the foreground
     * notification of the tunnels has an id below, in the same channel).
     */
    override fun cancelAll() {
        val manager = NotificationManagerCompat.from(context)
        for (active in manager.activeNotifications) {
            if (isBatchNotification(active.id, active.notification.channelId)) manager.cancel(active.id)
        }
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    internal companion object {
        // Above the foreground notification (1001). One id per batch: a later batch must not
        // replace an earlier one the user has not read yet.
        const val FIRST_NOTIFICATION_ID = 2000

        /** Whether an active notification is one of the batches of this notifier, see [cancelAll]. */
        fun isBatchNotification(id: Int, channelId: String?): Boolean =
            id >= FIRST_NOTIFICATION_ID && channelId == TUNNEL_CHANNEL_ID
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DeletedTunnelNotifierModule {
    @Binds
    @Singleton
    abstract fun bindDeletedTunnelNotifier(
        impl: AndroidDeletedTunnelNotifier,
    ): DeletedTunnelNotifier
}
