package dev.cameleonnbss.originostoolkit.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/**
 * The 4×2 status component: read-only by design.
 *
 * It answers "is this phone actually set up, and what has been changed on it"
 * without offering a second way to change anything — the refresh component is
 * the one place a tap does something, and one is enough.
 */
class ToolkitWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = WidgetUpdater.toolkitViews(context)
        ids.forEach { id -> manager.updateAppWidget(id, views) }
    }
}
