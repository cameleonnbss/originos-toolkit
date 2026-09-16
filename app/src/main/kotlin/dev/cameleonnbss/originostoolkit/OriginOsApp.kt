package dev.cameleonnbss.originostoolkit

import android.app.Application
import android.content.Context
import dev.cameleonnbss.originostoolkit.core.DeviceInfo
import dev.cameleonnbss.originostoolkit.core.JournalStore
import dev.cameleonnbss.originostoolkit.core.Prefs
import dev.cameleonnbss.originostoolkit.core.SharedPrefsJournalStore
import dev.cameleonnbss.originostoolkit.core.TweakEngine
import dev.cameleonnbss.originostoolkit.core.model.Catalog
import dev.cameleonnbss.originostoolkit.core.model.CatalogParser
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuBridge
import dev.cameleonnbss.originostoolkit.core.shell.ShellProvider
import dev.cameleonnbss.originostoolkit.core.shell.ShellRunner
import dev.cameleonnbss.originostoolkit.service.Notifications
import dev.cameleonnbss.originostoolkit.ui.AppEntry

/**
 * Manual dependency container. The app is small enough that a DI framework
 * would be more moving parts than it is worth, and this keeps the object graph
 * obvious when you read the code.
 */
class AppContainer(val appContext: Context) {

    val prefs: Prefs = Prefs(appContext)

    val journalStore: JournalStore = SharedPrefsJournalStore(appContext)

    val shellProvider = ShellProvider()

    val engine = TweakEngine(shellProvider, journalStore)

    /** The shell we currently talk to; re-resolved on every call. */
    val shell: ShellRunner get() = shellProvider.active

    var catalogError: String? = null
        private set

    /**
     * The catalog ships in assets and *is* `catalog/tweaks.json` from the
     * repository root — wired through Gradle, so the CLI and the app can never
     * disagree about what a tweak does.
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
     * System packages are filtered out: pinning a refresh rate for a background
     * service is meaningless, and the list would be unusable on OriginOS, which
     * ships a lot of them.
     */
    fun installedApps(): List<AppEntry> {
        val pm = appContext.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        return resolved
            .map { entry ->
                AppEntry(
                    packageName = entry.activityInfo.packageName,
                    label = entry.loadLabel(pm).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}

class OriginOsApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Start observing the Shizuku binder before the first UI frame so the
        // status card is never wrong-then-corrected.
        ShizukuBridge.register()
        container = AppContainer(this)
        Notifications.ensureChannels(this)
    }
}
