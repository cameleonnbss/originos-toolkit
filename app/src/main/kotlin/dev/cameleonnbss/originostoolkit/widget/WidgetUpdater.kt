package dev.cameleonnbss.originostoolkit.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import dev.cameleonnbss.originostoolkit.MainActivity
import dev.cameleonnbss.originostoolkit.OriginOsApp
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.AppContainer
import dev.cameleonnbss.originostoolkit.core.DeviceInfo
import dev.cameleonnbss.originostoolkit.core.TweakResult
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.shell.ShizukuBridge

/**
 * Draws the two home-screen components and handles the one thing they do.
 *
 * The state they show is read here, with the app's own APIs only:
 *
 *  * the panel's rates come from `DisplayManager`, not from the shell;
 *  * the two settings values come from the `system` namespace, which any app may
 *    read;
 *  * the access level is Shizuku's binder state and `Settings.System.canWrite`,
 *    both of which are cheap to ask;
 *  * the applied count is the revert journal's own length.
 *
 * Deliberately *not* here: anything that would make Shizuku spin up its helper
 * process to draw a label. A home-screen tile must be free to render, and the
 * one place a binder is genuinely needed — applying the tweak — is on the other
 * side of an explicit tap.
 */
object WidgetUpdater {

    /** The tweak the 2×2 component switches: the app's headline feature. */
    const val FORCE_MAX_TWEAK = "force-max-refresh-rate"

    /** Internal broadcast the component's chip sends. */
    const val ACTION_TOGGLE_FORCE_MAX = "dev.cameleonnbss.originostoolkit.widget.TOGGLE_FORCE_MAX"

    private const val REQUEST_TOGGLE = 91
    private const val REQUEST_OPEN_REFRESH = 92
    private const val REQUEST_OPEN_REFRESH_BLOCKED = 93
    private const val REQUEST_OPEN_HOME = 94

    /** `FLAG_IMMUTABLE` is mandatory from API 31; nothing here is mutated later. */
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    // -- entry points ------------------------------------------------------

    /** Redraws every placed component. Cheap and safe to call when none exists. */
    fun refreshAll(context: Context) {
        update(context, RefreshWidgetProvider::class.java, ::refreshViews)
        update(context, ToolkitWidgetProvider::class.java, ::toolkitViews)
    }

    /**
     * Applies or reverts [FORCE_MAX_TWEAK] through the real engine.
     *
     * The journal is written by the engine before the first setting is touched,
     * so a tap on the home screen is exactly as revertible as one in the app —
     * the widget is a second door into the same room, not a shortcut around it.
     */
    fun toggleForceMax(context: Context): TweakResult? {
        val container = container(context) ?: return null
        val tweak = container.catalog.tweak(FORCE_MAX_TWEAK) ?: return null
        return runCatching {
            if (container.engine.isApplied(tweak.id)) {
                container.engine.revert(tweak, tweak.id)
            } else {
                container.engine.apply(tweak)
            }
        }.getOrNull()
    }

    // -- views -------------------------------------------------------------

    fun refreshViews(context: Context): RemoteViews {
        val state = refreshState(context)
        val views = RemoteViews(context.packageName, R.layout.widget_refresh)

        views.setTextViewText(R.id.widget_refresh_value, state.headline)
        views.setTextViewText(
            R.id.widget_refresh_summary,
            context.getString(R.string.widget_refresh_summary, state.panelText, state.minText),
        )

        val acts = state.action != WidgetAction.NONE
        views.setTextViewText(R.id.widget_refresh_chip, chipLabel(context, state.action))
        views.setInt(
            R.id.widget_refresh_chip,
            "setBackgroundResource",
            if (acts) R.drawable.widget_action else R.drawable.widget_chip,
        )
        views.setTextColor(
            R.id.widget_refresh_chip,
            context.getColor(if (acts) R.color.widget_action_text else R.color.widget_muted),
        )

        views.setOnClickPendingIntent(
            R.id.widget_refresh_root,
            openApp(context, MainActivity.ROUTE_REFRESH, REQUEST_OPEN_REFRESH),
        )
        // Without write access the chip is not a dead end: it opens the screen
        // that asks for the grant, which is the only way to fix it.
        views.setOnClickPendingIntent(
            R.id.widget_refresh_chip,
            if (acts) {
                toggle(context)
            } else {
                openApp(context, MainActivity.ROUTE_REFRESH, REQUEST_OPEN_REFRESH_BLOCKED)
            },
        )
        return views
    }

    fun toolkitViews(context: Context): RemoteViews {
        val state = toolkitState(context)
        val views = RemoteViews(context.packageName, R.layout.widget_toolkit)

        views.setTextViewText(R.id.widget_toolkit_title, state.model)
        views.setTextViewText(
            R.id.widget_toolkit_subtitle,
            context.getString(R.string.widget_toolkit_subtitle, state.osLabel, state.panelText),
        )
        views.setTextViewText(R.id.widget_toolkit_density_value, state.densityText)
        views.setTextViewText(R.id.widget_toolkit_panel_value, state.panelText)
        views.setTextViewText(R.id.widget_toolkit_applied_value, state.appliedText)
        views.setTextViewText(R.id.widget_toolkit_access, accessLabel(context, state.level))
        views.setTextColor(
            R.id.widget_toolkit_access,
            context.getColor(if (state.level == AccessLevel.NONE) R.color.widget_muted else R.color.widget_accent),
        )
        views.setOnClickPendingIntent(
            R.id.widget_toolkit_root,
            openApp(context, MainActivity.ROUTE_HOME, REQUEST_OPEN_HOME),
        )
        return views
    }

    // -- state -------------------------------------------------------------

    fun refreshState(context: Context): RefreshWidgetState {
        val container = container(context)
        return RefreshWidgetState(
            panelHz = panelMaxHz(context),
            peakSetting = systemSetting(context, "peak_refresh_rate"),
            minSetting = systemSetting(context, "min_refresh_rate"),
            journaled = runCatching { container?.engine?.isApplied(FORCE_MAX_TWEAK) == true }
                .getOrDefault(false),
            canWrite = accessLevel(context) != AccessLevel.NONE,
        )
    }

    fun toolkitState(context: Context): ToolkitWidgetState {
        val container = container(context)
        return ToolkitWidgetState(
            model = Build.MODEL.orEmpty().ifBlank { UNKNOWN },
            // The `ro.vivo.os.*` properties need the shell user, so the component
            // says which Android it is on instead of guessing an OriginOS number.
            osLabel = "Android ${Build.VERSION.RELEASE}",
            densityDpi = context.resources.displayMetrics.densityDpi,
            panelHz = panelMaxHz(context),
            appliedCount = runCatching { container?.engine?.journal?.size ?: 0 }.getOrDefault(0),
            level = accessLevel(context),
        )
    }

    /** The strongest identity the toolkit currently has, by the app's own rules. */
    fun accessLevel(context: Context): AccessLevel = when {
        ShizukuBridge.state.value.ready -> AccessLevel.SHELL
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false) -> AccessLevel.SETTINGS
        else -> AccessLevel.NONE
    }

    private fun panelMaxHz(context: Context): Float? =
        DeviceInfo.supportedRefreshRates(context).maxOrNull() ?: DeviceInfo.currentRefreshRate(context)

    private fun systemSetting(context: Context, key: String): String? =
        runCatching { Settings.System.getString(context.contentResolver, key) }.getOrNull()

    private fun container(context: Context): AppContainer? =
        (context.applicationContext as? OriginOsApp)?.container

    // -- labels and intents ------------------------------------------------

    private fun chipLabel(context: Context, action: WidgetAction): String = when (action) {
        WidgetAction.APPLY -> context.getString(R.string.widget_refresh_action_pin)
        WidgetAction.REVERT -> context.getString(R.string.widget_refresh_action_unpin)
        WidgetAction.NONE -> context.getString(R.string.widget_refresh_action_blocked)
    }

    private fun accessLabel(context: Context, level: AccessLevel): String = when (level) {
        AccessLevel.SHELL -> context.getString(R.string.widget_access_shell)
        AccessLevel.SETTINGS -> context.getString(R.string.widget_access_settings)
        AccessLevel.NONE -> context.getString(R.string.widget_access_none)
    }

    private fun update(context: Context, provider: Class<*>, build: (Context) -> RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        // No instance placed: do not build a RemoteViews nobody will see.
        if (ids.isEmpty()) return
        val views = build(context)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    private fun toggle(context: Context): PendingIntent {
        val intent = Intent(context, RefreshWidgetProvider::class.java)
            .setAction(ACTION_TOGGLE_FORCE_MAX)
        return PendingIntent.getBroadcast(context, REQUEST_TOGGLE, intent, FLAGS)
    }

    private fun openApp(context: Context, route: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, FLAGS)
    }
}
