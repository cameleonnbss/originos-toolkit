package dev.cameleonnbss.originostoolkit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import androidx.core.app.NotificationCompat
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.OriginIsland

/**
 * The OriginIsland caster: posts notifications carrying the
 * `notification.superx.*` extras built by [OriginIsland].
 *
 * Icon sharing is the load-bearing detail that is easy to get wrong: the same
 * `Icon` instance goes into `baseInfos`, `capsule`, `infos`, `shortInfos` and
 * both island sides, the way upstream sends it. The framework loads the icon
 * from *its* side, against this package; a per-bundle copy is not needed and a
 * per-bundle mismatch is how you get an island with one blank side.
 *
 * Everything runs inside runCatching-style guards upstream because the extras
 * are opaque to ordinary platform code — they cannot throw here either, but the
 * guards keep a partially supported ROM from turning a test into a crash.
 */
object OriginIslandSender {

    const val CHANNEL_ISLAND = "originisland"

    /**
     * A fixed notification id, on purpose: an island is a *place*, not a list
     * entry — posting at another id would put a second pill next to the first.
     */
    const val ID_ISLAND = 3001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ISLAND,
            context.getString(R.string.channel_originisland_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows content in the OriginIsland around the camera cutout."
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Posts (or updates) the island notification for [request]. Returns the
     * framework's answer, or an error string — never throws.
     */
    fun post(context: Context, request: OriginIsland.Request): String {
        ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return "no notification manager"

        // Vivo's own apps register for the scene list through this hidden
        // NotificationManager method before their first island; upstream calls
        // it too, on the principle of doing what the platform's own clients do.
        registerScene(context)

        val icon = Icon.createWithResource(context, R.drawable.ic_notification)
        val builder = NotificationCompat.Builder(context, CHANNEL_ISLAND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(request.title)
            .setContentText(request.content)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        builder.extras.putAll(OriginIsland.buildExtras(request, smallIcon = icon))

        return try {
            manager.notify(ID_ISLAND, builder.build())
            "posted"
        } catch (e: Exception) {
            "error: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Removes the island the way the framework expects: one last write with
     * `operation = 2` so the pill unmounts gracefully, then the cancel.
     */
    fun cancel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ISLAND)
                .setSmallIcon(R.drawable.ic_notification)
                .addExtras(OriginIsland.buildEndExtras())
            manager.notify(ID_ISLAND, builder.build())
        } catch (_: Exception) {
            // The unmount hint is best-effort; the plain cancel below is the guarantee.
        }
        manager.cancel(ID_ISLAND)
    }

    private fun registerScene(context: Context) {
        try {
            val method = NotificationManager::class.java.getMethod(
                "setSuperXInfosSceneList",
                MutableList::class.java,
                MutableList::class.java,
                MutableList::class.java,
                MutableList::class.java,
            )
            method.invoke(
                context.getSystemService(Context.NOTIFICATION_SERVICE),
                arrayListOf(OriginIsland.SCENE),
                arrayListOf("true"),
                arrayListOf(context.packageName),
                arrayListOf("true"),
            )
        } catch (_: Exception) {
            // Method absent on non-vivo ROMs: the extras alone still carry the payload.
        }
    }
}
