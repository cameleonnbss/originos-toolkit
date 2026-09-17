package dev.cameleonnbss.originostoolkit.service

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.cameleonnbss.originostoolkit.OriginOsApp
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The headline feature: **per-app refresh rate**.
 *
 * OriginOS already has a high-refresh whitelist, but it is not editable. This
 * watcher polls which app is in the foreground and pins `peak_refresh_rate` /
 * `min_refresh_rate` to the rate you chose for that app, restoring the original
 * values the moment you leave it.
 *
 * It writes `peak_refresh_rate` / `min_refresh_rate`, which live in the `system`
 * namespace — so with the *modify system settings* grant it works with no
 * Shizuku at all, and with Shizuku it works with no grant. Because it changes a
 * value repeatedly by design, it keeps its own two originals in [Prefs] instead
 * of writing to the revert journal — the journal is for deliberate, one-off
 * changes you want to see listed and undo.
 */
class PerAppRefreshService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private lateinit var container: AppContainer
    private var appliedPackage: String? = null
    private var appliedRate = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        container = (application as OriginOsApp).container
        container.prefs.refreshWatcherEnabled = true

        startForeground(
            Notifications.ID_REFRESH,
            Notifications.build(
                this,
                Notifications.CHANNEL_REFRESH,
                getString(R.string.refresh_watcher_on),
                getString(R.string.refresh_watcher_on),
            ),
        )

        captureOriginals()
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        restoreOriginals()
        if (::container.isInitialized) {
            container.prefs.refreshWatcherEnabled = false
        }
        super.onDestroy()
    }

    // -- polling -----------------------------------------------------------

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun tick() {
        // Either route will do: Shizuku, or the settings grant. This used to
        // insist on Shizuku, which was simply wrong for a system-namespace key.
        if (!container.shell.canWrite) {
            notify(
                "Write access needed",
                "Grant \"modify system settings\" to this app, or start Shizuku.",
            )
            return
        }

        val foreground = currentForegroundPackage() ?: return
        if (foreground == packageName) return

        val rate = container.prefs.perAppRates()[foreground]
            ?: container.prefs.defaultRefreshRate

        if (rate <= 0) {
            if (appliedRate != 0) {
                restoreOriginals()
                appliedPackage = null
                appliedRate = 0
                notify(getString(R.string.refresh_watcher_on), "System default")
            }
            return
        }

        if (foreground == appliedPackage && rate == appliedRate) return

        if (applyRate(rate)) {
            appliedPackage = foreground
            appliedRate = rate
            notify(label(foreground), "$rate Hz")
        }
    }

    /**
     * Most recently resumed activity wins — the same signal the system uses to
     * decide what "foreground" means.
     */
    private fun currentForegroundPackage(): String? {
        val manager = getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - EVENT_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                latest = event.packageName
            }
        }
        return latest
    }

    // -- writing -----------------------------------------------------------

    private fun captureOriginals() {
        if (container.prefs.watcherOriginalPeak == null) {
            container.prefs.watcherOriginalPeak =
                container.shell.exec("settings get system peak_refresh_rate").stdout.trim()
        }
        if (container.prefs.watcherOriginalMin == null) {
            container.prefs.watcherOriginalMin =
                container.shell.exec("settings get system min_refresh_rate").stdout.trim()
        }
    }

    private fun applyRate(rate: Int): Boolean {
        val peak = container.shell.exec("settings put system peak_refresh_rate ${rate}.0")
        val min = container.shell.exec("settings put system min_refresh_rate ${rate}.0")
        return peak.ok && min.ok
    }

    private fun restoreOriginals() {
        val peak = container.prefs.watcherOriginalPeak
        val min = container.prefs.watcherOriginalMin
        if (peak != null) {
            if (peak.isEmpty() || peak == "null") {
                container.shell.exec("settings delete system peak_refresh_rate")
            } else {
                container.shell.exec("settings put system peak_refresh_rate $peak")
            }
        }
        if (min != null) {
            if (min.isEmpty() || min == "null") {
                container.shell.exec("settings delete system min_refresh_rate")
            } else {
                container.shell.exec("settings put system min_refresh_rate $min")
            }
        }
        container.prefs.watcherOriginalPeak = null
        container.prefs.watcherOriginalMin = null
    }

    // -- notification ------------------------------------------------------

    private fun notify(title: String, text: String) {
        Notifications.update(this, Notifications.ID_REFRESH, Notifications.CHANNEL_REFRESH, title, text)
    }

    private fun label(pkg: String): String = runCatching {
        val info = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(info).toString()
    }.getOrElse { pkg }

    companion object {
        private const val POLL_INTERVAL_MS = 2500L
        private const val EVENT_LOOKBACK_MS = 60_000L

        const val ACTION_START = "dev.cameleonnbss.originostoolkit.REFRESH_START"
        const val ACTION_STOP = "dev.cameleonnbss.originostoolkit.REFRESH_STOP"

        fun start(context: Context) {
            val intent = Intent(context, PerAppRefreshService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, PerAppRefreshService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
