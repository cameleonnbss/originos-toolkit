package dev.cameleonnbss.originostoolkit.widget

import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the two home-screen atomic components show, with no Android in sight.
 *
 * The view side of a widget is `RemoteViews`, which a JVM unit test cannot
 * inspect at all — so every decision that could be wrong (is this state
 * "forced"? does the tap apply or revert? which number goes in the big slot?)
 * lives here as plain arithmetic, and the updater is left with nothing but
 * `setTextViewText` calls.
 */

/** What tapping the refresh component's chip does. */
enum class WidgetAction {
    APPLY,
    REVERT,

    /** No write is possible: the tap opens the app so the user can grant it. */
    NONE,
}

data class RefreshWidgetState(
    /** The highest rate the panel advertises, or `null` when it cannot be read. */
    val panelHz: Float? = null,
    /** Raw value of `peak_refresh_rate`, e.g. `"60.0"`; `null` when unset. */
    val peakSetting: String? = null,
    /** Raw value of `min_refresh_rate`. */
    val minSetting: String? = null,
    /** True when `force-max-refresh-rate` is in the revert journal. */
    val journaled: Boolean = false,
    /** True when a write would be accepted right now. */
    val canWrite: Boolean = false,
) {

    val panelInt: Int? get() = panelHz?.roundToInt()

    /** The rate the peak setting asks for, whatever is actually possible. */
    val pinnedHz: Int? get() = parseHz(peakSetting)?.roundToInt()

    /**
     * True when there is no headroom left above the current peak rate.
     *
     * The journal is the honest source — it says this app set the value — but a
     * value the user (or another tool) left at the panel maximum counts too,
     * otherwise the component would offer to "pin" something already pinned.
     *
     * The comparison is *at or above*, not equal: the catalog asks for 144 Hz and
     * a 120 Hz panel will clamp it, which leaves no headroom either way, and a
     * tile offering to raise a rate that cannot be raised would be worse than
     * useless. A value below the panel maximum is a cap, not a pin, and keeps the
     * offer to go higher.
     */
    val forced: Boolean get() = journaled || atOrAbovePanel()

    val action: WidgetAction get() = when {
        !canWrite -> WidgetAction.NONE
        forced -> WidgetAction.REVERT
        else -> WidgetAction.APPLY
    }

    /**
     * The big number: what the phone is pinned to when it is pinned, and what
     * the panel can do when it is not — the value the tap would produce.
     */
    val headline: String get() = when {
        forced -> (pinnedHz ?: panelInt)?.let(::hzText) ?: UNKNOWN
        else -> panelInt?.let(::hzText) ?: pinnedHz?.let(::hzText) ?: UNKNOWN
    }

    /** The panel maximum as text, for the line under the number. */
    val panelText: String get() = panelInt?.let(::hzText) ?: UNKNOWN

    /**
     * The floor the system currently applies, or `—` when it is unset.
     *
     * Not rounded: 59.94 Hz is a real rate and printing it as 60 would be the
     * kind of small lie the rest of this app goes out of its way to avoid.
     */
    val minText: String get() = parseHz(minSetting)?.let(::hzText) ?: UNKNOWN

    private fun atOrAbovePanel(): Boolean {
        val panel = panelHz ?: return false
        val peak = parseHz(peakSetting) ?: return false
        return peak >= panel - HZ_TOLERANCE
    }

    companion object {
        /** `144.0` and `144` mean the same setting; a whole Hz is close enough. */
        const val HZ_TOLERANCE = 0.6f
    }
}

data class ToolkitWidgetState(
    val model: String = "",
    /** `OriginOS 6` when the version is readable, otherwise `Android 16`. */
    val osLabel: String = "",
    val densityDpi: Int? = null,
    val panelHz: Float? = null,
    val appliedCount: Int = 0,
    val level: AccessLevel = AccessLevel.NONE,
) {
    val densityText: String get() = densityDpi?.toString() ?: UNKNOWN
    val panelText: String get() = panelHz?.roundToInt()?.let(::hzText) ?: UNKNOWN
    val appliedText: String get() = appliedCount.toString()
}

/** `—`, never a zero or a blank, when something could not be read. */
const val UNKNOWN = "—"

/**
 * Refresh rates are floats in Android's settings and in the display modes —
 * `120.0`, `59.94`, `165.0` — and no one wants to read `120.0 Hz` on a home
 * screen tile. A whole number is printed as one; anything genuinely fractional
 * keeps two decimals, because that is the difference between 60 and 59.94 and
 * pretending otherwise would be the kind of lie this app avoids elsewhere.
 */
fun hzText(value: Int): String = value.toString()

fun hzText(value: Float): String = if (abs(value - value.roundToInt()) < 0.01f) {
    value.roundToInt().toString()
} else {
    String.format(java.util.Locale.US, "%.2f", value)
}

/** Parses a settings value (`"144.0"`, `"60"`, `"null"`) into a rate. */
fun parseHz(raw: String?): Float? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return null
    return trimmed.toFloatOrNull()
}
