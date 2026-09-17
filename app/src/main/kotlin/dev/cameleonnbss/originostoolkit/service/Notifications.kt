package dev.cameleonnbss.originostoolkit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.cameleonnbss.originostoolkit.R

/**
 * Notification plumbing for the two foreground services.
 *
 * Both services must post a notification to exist at all on modern Android, so
 * the notifications are written to be genuinely informative rather than
 * boilerplate: the overlay one shows the live frame rate, the refresh watcher
 * shows which app it is currently pinning and to what.
 */
object Notifications {

    const val CHANNEL_OVERLAY = "overlay"
    const val CHANNEL_REFRESH = "refresh"

    const val ID_OVERLAY = 1001
    const val ID_REFRESH = 1002

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                context.getString(R.string.channel_overlay_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Shows the live frame rate over other apps." },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REFRESH,
                context.getString(R.string.channel_refresh_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the display refresh rate in sync with each app." },
        )
    }

    fun build(
        context: Context,
        channel: String,
        title: String,
        text: String,
    ): Notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(title)
        .setContentText(text)
        .setOngoing(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    /** Updates an existing notification in place, so the text can track state. */
    fun update(context: Context, id: Int, channel: String, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(id, build(context, channel, title, text)) }
    }
}
