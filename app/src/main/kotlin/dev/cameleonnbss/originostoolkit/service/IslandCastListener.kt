package dev.cameleonnbss.originostoolkit.service

import android.app.Notification
import android.graphics.drawable.Icon
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.cameleonnbss.originostoolkit.OriginOsApp
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.IslandRecast
import dev.cameleonnbss.originostoolkit.core.OriginIsland

/**
 * The island re-caster: picked apps' notifications ride the island.
 *
 * The flow is deliberately boring. Every incoming notification is matched
 * against the user's pick list; anything else is dropped without so much as a
 * log line. A picked app's latest notification replaces the previous cast —
 * the island is a *place*, not a feed — and the removed hook unmounts it
 * instead of leaving the last payload hanging in the pill.
 *
 * Media notifications (a music player's) get the OriginOS treatment: the
 * source app's own icon rides the island bundles, and the artist–title line
 * is preferred over whatever the shade would have shown, because that is the
 * line a pill is for.
 *
 * Permission reality: `NotificationListenerService` needs the user to grant
 * *notification access* in system settings, and OriginOS will suspend the
 * listener on some builds unless the app is exempted from battery
 * optimisation — the same tax CunnyPlayground documents for its recaster.
 * Nothing here works around either; the screen links the grant.
 */
class IslandCastListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        OriginIslandSender.ensureChannel(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        cast(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val manager = castManager() ?: return
        if (!manager.prefs.islandRecastEnabled) return
        if (sbn.packageName in manager.prefs.islandApps()) {
            OriginIslandSender.cancel(this, OriginIslandSender.ID_RECAST)
        }
    }

    private fun cast(sbn: StatusBarNotification) {
        // The app's own casts come back through the listener — never re-cast
        // them into themselves.
        if (sbn.packageName == packageName) return

        val manager = castManager() ?: return
        if (!manager.prefs.islandRecastEnabled) return
        if (sbn.packageName !in manager.prefs.islandApps()) return

        val n: Notification = sbn.notification
        val extras = n.extras

        // Media sessions put the artist–title line in bigText and leave the
        // shade text as a fragment; the pill wants the full line.
        val bigText = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val text = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_TEXT))
        val content = bigText ?: text ?: return
        val title = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_TITLE))
        val subText = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))

        val decision = IslandRecast.decide(
            configured = manager.prefs.islandTemplateFor(sbn.packageName),
            progress = extras.getInt(Notification.EXTRA_PROGRESS),
            progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX),
        )
        if (decision.skip) return

        OriginIslandSender.post(
            this,
            OriginIsland.Request(
                id = OriginIslandSender.ID_RECAST,
                title = title ?: IslandRecast.displayTitle(null, sbn.packageName),
                content = content,
                subText = subText,
                template = decision.template,
                progress = decision.progress,
                progressMax = decision.progressMax,
                leftText = title,
                rightText = content,
            ),
            id = OriginIslandSender.ID_RECAST,
            sourceIcon = sourceIcon(sbn.packageName),
        )
    }

    /**
     * The casted app's launcher icon, drawn once into a bitmap so the island
     * bundles carry a self-contained `Icon`. Loaded defensively: a cast without
     * an icon is better than a listener crash on a weird app.
     */
    private fun sourceIcon(packageName: String): Icon? = runCatching {
        val drawable = packageManager.getApplicationIcon(packageName)
        val bitmap = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, 96, 96)
        drawable.draw(canvas)
        Icon.createWithBitmap(bitmap)
    }.getOrNull()

    /**
     * The Application owns the object graph; the container is read per cast so a
     * service process restart always sees the current graph, never a captured one.
     */
    private fun castManager(): AppContainer? = (application as? OriginOsApp)?.containerOrNull
}
