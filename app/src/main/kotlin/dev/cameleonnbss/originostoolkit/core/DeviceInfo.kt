package dev.cameleonnbss.originostoolkit.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.core.shell.ShellRunner

/**
 * What we can learn about this phone, with or without privilege.
 *
 * Almost everything here comes from the app's own APIs — `Build`, the display
 * manager, and the `system` settings namespace, which any app may read. Only the
 * Vivo-specific `ro.vivo.os.*` properties need the shell user, and when they are
 * unavailable we say so instead of guessing: an unreadable OriginOS version is
 * `null`, never a fabricated one.
 */
data class DeviceSnapshot(
    val model: String = "unknown",
    val brand: String = "unknown",
    val androidRelease: String = "?",
    val sdk: Int = 0,
    val originOsVersion: String? = null,
    val originOsBuild: String? = null,
    val funTouchVersion: String? = null,
    val density: Int? = null,
    val resolution: String? = null,
    val peakRefreshRate: String? = null,
    val minRefreshRate: String? = null,
    /** True when the values came from the shell user rather than the app itself. */
    val viaShell: Boolean = false,
    /** True when the OS is Vivo's but we could not read its version without a shell. */
    val originOsVersionUnreadable: Boolean = false,
) {
    /** OriginOS devices expose `ro.vivo.os.version`; others do not. */
    val isOriginOs: Boolean get() = !originOsVersion.isNullOrBlank()

    val isVivo: Boolean get() = brand.contains("vivo", ignoreCase = true) ||
        brand.contains("iqoo", ignoreCase = true) ||
        model.contains("vivo", ignoreCase = true) ||
        model.contains("iqoo", ignoreCase = true)

    /**
     * Likely OriginOS without being able to prove the version. Vivo ships only
     * OriginOS and FuntouchOS, so on a Vivo build this is a strong hint — and it
     * is labelled as a hint everywhere it is shown.
     */
    val likelyOriginOs: Boolean get() = isVivo && !isOriginOs
}

object DeviceInfo {

    private val PROPS = listOf(
        "ro.vivo.os.version",
        "ro.vivo.os.build.display.id",
        "ro.vivo.product.version",
    )

    fun read(context: Context, runner: ShellRunner): DeviceSnapshot {
        val hasShell = runner.level.covers(AccessLevel.SHELL)
        val props = if (hasShell) readProps(runner) else emptyMap()
        val resolver = context.contentResolver

        val density = if (hasShell) {
            runner.exec("wm density").stdout.let(::parseDensity)
        } else {
            runCatching { context.resources.displayMetrics.densityDpi }.getOrNull()
        }

        val resolution = if (hasShell) {
            parseResolution(runner.exec("wm size").stdout)
        } else {
            currentMode(context)?.let { "${it.physicalWidth}x${it.physicalHeight}" }
        }

        return DeviceSnapshot(
            model = Build.MODEL ?: "unknown",
            brand = Build.BRAND ?: "unknown",
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            originOsVersion = props["ro.vivo.os.version"]?.takeIf { it.isNotBlank() && it != "unknown" },
            originOsBuild = props["ro.vivo.os.build.display.id"]?.takeIf { it.isNotBlank() },
            funTouchVersion = props["ro.vivo.product.version"]?.takeIf { it.isNotBlank() },
            density = density,
            resolution = resolution,
            // Reads of the `system` namespace need no permission, so these work
            // in every mode.
            peakRefreshRate = readSetting(context, "peak_refresh_rate"),
            minRefreshRate = readSetting(context, "min_refresh_rate"),
            viaShell = hasShell,
            originOsVersionUnreadable = !hasShell && isVivo(Build.BRAND ?: "", Build.MODEL ?: ""),
        )
    }

    private fun isVivo(brand: String, model: String): Boolean =
        brand.contains("vivo", ignoreCase = true) ||
            brand.contains("iqoo", ignoreCase = true) ||
            model.contains("vivo", ignoreCase = true) ||
            model.contains("iqoo", ignoreCase = true)

    /** One `getprop` call instead of three; needs the shell user. */
    private fun readProps(runner: ShellRunner): Map<String, String> {
        val result = runner.exec("getprop")
        if (!result.ok && result.stdout.isBlank()) return emptyMap()
        val wanted = PROPS.toSet()
        val out = mutableMapOf<String, String>()
        Regex("\\[([^\\]]+)]:\\s*\\[(.*)]").findAll(result.stdout).forEach { match ->
            val key = match.groupValues[1]
            if (key in wanted) out[key] = match.groupValues[2]
        }
        return out
    }

    private fun readSetting(context: Context, key: String): String? = runCatching {
        Settings.System.getString(context.contentResolver, key)
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    /** Prefers the override, which is the density the user actually sees. */
    fun parseDensity(output: String): Int? {
        var override: Int? = null
        var physical: Int? = null
        output.lineSequence().forEach { line ->
            val digits = line.filter { it.isDigit() }
            if (digits.isEmpty()) return@forEach
            val value = digits.toIntOrNull() ?: return@forEach
            when {
                line.contains("override", ignoreCase = true) -> override = value
                line.contains("physical", ignoreCase = true) -> physical = value
            }
        }
        return override ?: physical
    }

    fun parseResolution(output: String): String? =
        Regex("(?i)(override|physical) size:\\s*(\\d+x\\d+)")
            .findAll(output).lastOrNull()?.groupValues?.get(2)

    private fun currentMode(context: Context): Display.Mode? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        return manager.getDisplay(Display.DEFAULT_DISPLAY)?.mode
    }

    /** The refresh rate of the mode the panel is actually using right now. */
    fun currentRefreshRate(context: Context): Float? = currentMode(context)?.refreshRate

    /** Every distinct refresh rate the panel advertises, ascending. */
    fun supportedRefreshRates(context: Context): List<Float> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return display.supportedModes.map { it.refreshRate }.distinct().sorted()
    }
}
