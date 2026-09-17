package dev.cameleonnbss.originostoolkit.core

/**
 * Pure decision logic for casting other apps' notifications onto the island.
 *
 * The listener service calls this with what a `StatusBarNotification` carries;
 * the function stays free of Android classes so the wire-relevant choices —
 * which template ends up posted, what the pill reads — are testable on the JVM.
 */
object IslandRecast {

    /** What the listener should post for one incoming notification. */
    data class Decision(
        val template: OriginIsland.OriginIslandTemplate,
        val progress: Int,
        val progressMax: Int,
        /** True when the notification has nothing the island could show. */
        val skip: Boolean,
    )

    /**
     * A live progress bar (music position, navigation leg) always wins: when the
     * source notification carries a running progress pair, the post becomes the
     * progress template regardless of the app's configured one. Apps without a
     * progress keep their configured template.
     */
    fun decide(
        configured: OriginIsland.OriginIslandTemplate,
        progress: Int,
        progressMax: Int,
    ): Decision =
        if (progressMax > 0 && progress in 1 until progressMax) {
            Decision(OriginIsland.OriginIslandTemplate.PROGRESS, progress, progressMax, skip = false)
        } else {
            Decision(configured, 0, 0, skip = false)
        }

    /**
     * The pill's left side names the app. The friendly label when the package
     * manager has one, the raw package name when it does not.
     */
    fun displayTitle(label: String?, packageName: String): String =
        label?.takeIf { it.isNotBlank() } ?: packageName

    /**
     * Notification text arrives as spans; flatten it and drop the blank.
     */
    fun cleanText(raw: CharSequence?): String? {
        val text = raw?.toString()?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }
}
