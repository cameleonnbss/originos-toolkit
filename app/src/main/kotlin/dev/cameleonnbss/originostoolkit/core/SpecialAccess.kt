package dev.cameleonnbss.originostoolkit.core

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.Settings

/**
 * The two non-runtime permissions this app needs, and no more.
 *
 * Both are "special access" grants the user has to confirm in Settings, which
 * is deliberate: an overlay drawn on top of other apps, and the ability to see
 * which app is in the foreground, are exactly the kind of power that deserves
 * an explicit switch.
 */
object SpecialAccess {

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlaySettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun appDetailsIntent(context: Context): Intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * The *modify system settings* grant.
     *
     * This is the one that lets the app write the `system` namespace itself,
     * which is how the toolkit works without Shizuku, without root and without
     * a computer. It is a normal per-app special access, granted from Settings.
     */
    fun canWriteSettings(context: Context): Boolean =
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    fun writeSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))

    /** Shizuku's own app, if it is installed, so we can deep-link the user to it. */
    fun shizukuPackage(): String = "moe.shizuku.privileged.api"
}
