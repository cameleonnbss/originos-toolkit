package dev.cameleonnbss.originostoolkit.core

import android.content.Context
import org.json.JSONObject

/** Small typed wrapper over SharedPreferences; no dependency, no surprises. */
class Prefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Experimental tweaks stay locked until the user opts in. */
    var experimentalEnabled: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPERIMENTAL, value).apply()

    /** Hide community-submitted entries that were never verified on hardware. */
    var verifiedOnly: Boolean
        get() = prefs.getBoolean(KEY_VERIFIED_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_VERIFIED_ONLY, value).apply()

    var overlayEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OVERLAY, value).apply()

    var refreshWatcherEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATCHER, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCHER, value).apply()

    /** Refresh rate (Hz) the watcher pins to apps that have no explicit entry. */
    var defaultRefreshRate: Int
        get() = prefs.getInt(KEY_DEFAULT_HZ, 0)
        set(value) = prefs.edit().putInt(KEY_DEFAULT_HZ, value).apply()

    /** The last app the user picked in the per-app list. */
    var lastRefreshTargetHz: Int
        get() = prefs.getInt(KEY_LAST_HZ, 120)
        set(value) = prefs.edit().putInt(KEY_LAST_HZ, value).apply()

    /** package name -> refresh rate, for the per-app watcher. */
    fun perAppRates(): Map<String, Int> {
        val raw = prefs.getString(KEY_PER_APP, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optInt(it) }
        }.getOrElse { emptyMap() }
    }

    fun setPerAppRate(packageName: String, rate: Int?) {
        val current = perAppRates().toMutableMap()
        if (rate == null || rate <= 0) current.remove(packageName) else current[packageName] = rate
        val json = JSONObject()
        current.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_PER_APP, json.toString()).apply()
    }

    fun clearPerAppRates() = prefs.edit().remove(KEY_PER_APP).apply()

    /**
     * The refresh-rate values that were in place when the watcher started.
     *
     * The watcher deliberately does *not* journal its writes: it changes the
     * refresh rate dozens of times an hour and would drown the revert journal.
     * It keeps its own two originals instead and puts them back on stop.
     */
    var watcherOriginalPeak: String?
        get() = prefs.getString(KEY_WATCHER_PEAK, null)
        set(value) = prefs.edit().putString(KEY_WATCHER_PEAK, value).apply()

    var watcherOriginalMin: String?
        get() = prefs.getString(KEY_WATCHER_MIN, null)
        set(value) = prefs.edit().putString(KEY_WATCHER_MIN, value).apply()

    /**
     * BCP-47 tag of the interface language, or "" to follow Android's own
     * setting. Applied in `MainActivity.attachBaseContext`, so a change only
     * takes effect after the activity is recreated.
     */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, AppLanguage.SYSTEM).orEmpty()
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // -- OriginIsland recast -------------------------------------------------

    /** Whether picked apps' notifications are cast onto the island at all. */
    var islandRecastEnabled: Boolean
        get() = prefs.getBoolean(KEY_ISLAND_RECAST, false)
        set(value) = prefs.edit().putBoolean(KEY_ISLAND_RECAST, value).apply()

    /** Package names whose notifications ride the island, in pick order. */
    fun islandApps(): List<String> {
        val raw = prefs.getString(KEY_ISLAND_APPS, null) ?: return emptyList()
        return runCatching {
            val json = org.json.JSONArray(raw)
            (0 until json.length()).mapNotNull { json.optString(it).takeIf(String::isNotEmpty) }
        }.getOrElse { emptyList() }
    }

    fun setIslandApps(apps: List<String>) {
        val json = org.json.JSONArray()
        apps.forEach { json.put(it) }
        prefs.edit().putString(KEY_ISLAND_APPS, json.toString()).apply()
    }

    /** package name -> right-template id for the cast; defaults to capsule. */
    fun islandAppTemplates(): Map<String, Int> {
        val raw = prefs.getString(KEY_ISLAND_TEMPLATES, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optInt(it) }
        }.getOrElse { emptyMap() }
    }

    fun setIslandTemplate(packageName: String, templateId: Int) {
        val current = islandAppTemplates().toMutableMap()
        current[packageName] = templateId
        val json = JSONObject()
        current.forEach { (key, value) -> json.put(key, value) }
        prefs.edit().putString(KEY_ISLAND_TEMPLATES, json.toString()).apply()
    }

    fun islandTemplateFor(packageName: String): OriginIsland.OriginIslandTemplate =
        OriginIsland.OriginIslandTemplate.fromId(islandAppTemplates()[packageName] ?: OriginIsland.OriginIslandTemplate.CAPSULE.id)

    /** Friendly label for a stored refresh rate; 0 means "leave it alone". */
    fun describeDefaultRate(): String =
        if (defaultRefreshRate <= 0) "system default" else "$defaultRefreshRate Hz"

    private companion object {
        const val FILE = "settings"
        const val KEY_EXPERIMENTAL = "experimental_enabled"
        const val KEY_VERIFIED_ONLY = "verified_only"
        const val KEY_OVERLAY = "overlay_enabled"
        const val KEY_WATCHER = "refresh_watcher_enabled"
        const val KEY_DEFAULT_HZ = "default_refresh_rate"
        const val KEY_LAST_HZ = "last_refresh_target_hz"
        const val KEY_PER_APP = "per_app_rates"
        const val KEY_WATCHER_PEAK = "watcher_original_peak"
        const val KEY_WATCHER_MIN = "watcher_original_min"
        const val KEY_LANGUAGE = "interface_language"
        const val KEY_ISLAND_RECAST = "island_recast_enabled"
        const val KEY_ISLAND_APPS = "island_apps"
        const val KEY_ISLAND_TEMPLATES = "island_app_templates"
    }
}
