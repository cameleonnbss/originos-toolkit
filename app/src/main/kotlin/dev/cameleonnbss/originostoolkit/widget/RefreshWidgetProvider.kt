package dev.cameleonnbss.originostoolkit.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The 2×2 refresh-rate component.
 *
 * It shows the number and changes it: the chip applies or reverts
 * `force-max-refresh-rate` through the engine, so the change lands in the revert
 * journal like any other — a tap on the home screen can be undone from the app,
 * and the app's own Panic reset covers it too.
 */
class RefreshWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = WidgetUpdater.refreshViews(context)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WidgetUpdater.ACTION_TOGGLE_FORCE_MAX) {
            super.onReceive(context, intent)
            return
        }

        // The engine may have to answer Shizuku's binder and write settings, so
        // the result is not ready inside onReceive. `goAsync` keeps the broadcast
        // alive while a worker thread does it, and the component then redraws
        // itself from the state that actually landed on the device.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetUpdater.toggleForceMax(context)
            } finally {
                WidgetUpdater.refreshAll(context)
                pending.finish()
            }
        }
    }
}
