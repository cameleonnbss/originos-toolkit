package dev.cameleonnbss.originostoolkit.core

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import dev.cameleonnbss.originostoolkit.core.shell.ShellRunner

/**
 * What we can learn about this phone without any privilege at all.
 *
 * `getprop` needs nothing special, which is why the dashboard still says
 * something useful before Shizuku is running.
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
) {
    /** OriginOS devices expose `ro.vivo.os.version`; others do not. */
    val isOriginOs: Boolean get() = !originOsVersion.isNullOrBlank()

    val isVivo: Boolean get() = brand.contains("vivo", ignoreCase = true) ||
        brand.contains("iqoo", ignoreCase = true) ||
        model.contains("vivo", ignoreCase = true) ||
        model.contains("iqoo", ignoreCase = true)
}

object DeviceInfo {

    private val PROPS = listOf(
        "ro.product.model",
        "ro.product.brand",
        "ro.build.version.release",
        "ro.build.version.sdk",
        "ro.vivo.os.version",
        "ro.vivo.os.build.display.id",
        "ro.vivo.product.version",
    )

    fun read(runner: ShellRunner): DeviceSnapshot {
        val props = readProps(runner)
        return DeviceSnapshot(
            model = props["ro.product.model"] ?: "unknown",
            brand = props["ro.product.brand"] ?: "unknown",
            androidRelease = props["ro.build.version.release"] ?: "?",
            sdk = props["ro.build.version.sdk"]?.toIntOrNull() ?: 0,
            originOsVersion = props["ro.vivo.os.version"]?.takeIf { it.isNotBlank() && it != "unknown" },
            originOsBuild = props["ro.vivo.os.build.display.id"]?.takeIf { it.isNotBlank() },
            funTouchVersion = props["ro.vivo.product.version"]?.takeIf { it.isNotBlank() },
            density = runner.exec("wm density").stdout.let { output ->
                // "Physical density: 460" / "Override density: 440"
                Regex("(?i)override density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("(?i)physical density:\\s*(\\d+)").find(output)?.groupValues?.get(1)?.toIntOrNull()
            },
            resolution = runner.exec("wm size").stdout
                .let { output ->
                    Regex("(?i)(override|physical) size:\\s*(\\d+x\\d+)")
                        .findAll(output).lastOrNull()?.groupValues?.get(2)
                },
            peakRefreshRate = runner.exec("settings get system peak_refresh_rate").stdout
                .trim().takeIf { it.isNotEmpty() && it != "null" },
            minRefreshRate = runner.exec("settings get system min_refresh_rate").stdout
                .trim().takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    /** One `getprop` call instead of seven. */
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

    /** The refresh rate of the mode the panel is actually using right now. */
    fun currentRefreshRate(context: Context): Float? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return display.mode?.refreshRate
    }

    /** Every distinct refresh rate the panel advertises, ascending. */
    fun supportedRefreshRates(context: Context): List<Float> {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return emptyList()
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        return display.supportedModes.map { it.refreshRate }.distinct().sorted()
    }
}
