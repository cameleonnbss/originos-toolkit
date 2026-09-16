package dev.cameleonnbss.originostoolkit.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.CatalogParser
import dev.cameleonnbss.originostoolkit.core.shell.ShellProvider
import dev.cameleonnbss.originostoolkit.core.shell.ShellRunner

/** An installed, launchable app — the unit the per-app refresh picker works on. */
data class AppEntry(val packageName: String, val label: String)

/**
 * Manual dependency container.
 *
 * The app is small enough that a DI framework would be more moving parts than it
 * is worth, and holding the object graph in one readable class is a feature when
 * you are auditing something that has shell privileges.
 */
class AppContainer(val appContext: Context) {

    val prefs: Prefs = Prefs(appContext)

    val journalStore: JournalStore = SharedPrefsJournalStore(appContext)

    val shellProvider = ShellProvider(appContext)

    val engine = TweakEngine(shellProvider, journalStore)

    /** The shell we currently talk to; re-resolved on every call. */
    val shell: ShellRunner get() = shellProvider.active

    var catalogError: String? = null
        private set

    /**
     * The catalog ships in assets and *is* the repository's `catalog` directory,
     * wired through Gradle, so the CLI and the app can never disagree about what
     * a tweak does.
     */
    val catalog: Catalog = runCatching { CatalogParser.parseAssets(appContext.assets) }
        .onFailure { error -> catalogError = error.message ?: "catalog could not be parsed" }
        .getOrElse {
            Catalog(
                version = 0,
                updatedAt = "",
                categories = emptyList(),
                tweaks = emptyList(),
                profiles = emptyList(),
                awesome = emptyList(),
            )
        }

    fun currentRefreshRate(): Float? = DeviceInfo.currentRefreshRate(appContext)

    fun supportedRefreshRates(): List<Float> = DeviceInfo.supportedRefreshRates(appContext)

    /**
     * Every launchable app, for the per-app refresh picker.
     *
     * Resolved through the launcher intent rather than the raw package list:
     * pinning a refresh rate for a background service is meaningless, and
     * OriginOS ships a lot of them.
     */
    fun installedApps(): List<AppEntry> {
        val manager: PackageManager = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0)
            .map { resolved ->
                AppEntry(
                    packageName = resolved.activityInfo.packageName,
                    label = resolved.loadLabel(manager).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
