package dev.cameleonnbss.originostoolkit.service

import android.app.Notification
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
        val content = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_TEXT))
            ?: return
        val title = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_TITLE))
        val subText = IslandRecast.cleanText(extras.getCharSequence(Notification.EXTRA_SUB_TEXT))

        // Progress lives in the extras, not on Notification itself.
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
        )
    }

    /**
     * The Application owns the object graph; the container is read per cast so a
     * service process restart always sees the current graph, never a captured one.
     */
    private fun castManager(): AppContainer? = (application as? OriginOsApp)?.containerOrNull
}
