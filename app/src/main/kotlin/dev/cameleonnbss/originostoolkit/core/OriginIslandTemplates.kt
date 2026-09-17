package dev.cameleonnbss.originostoolkit.core

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Bundle
import java.util.ArrayList

/**
 * The vivo OriginIsland payload, as CunnyPlayground's `originos-experimental`
 * branch reverse-engineered it.
 *
 * OriginOS 6 draws an island around the camera cutout for notifications that
 * carry a particular set of extras — all of them namespaced
 * `notification.superx.*` and `island.superx.*`, because vivo's internal name
 * for the feature is "Super Island". None of this is in the public SDK: the
 * keys below were recovered from the framework by the upstream project, and
 * this file is a faithful, lightly restructured port of its sender. Every key
 * is optional to the *platform* — an unrecognised extra is ignored — so on a
 * phone without the island this code posts an ordinary notification and
 * nothing else happens.
 *
 * The bundle layout, faithfully:
 *
 * - `notification.superx.operation` — 0 = show, 2 = unmount gracefully;
 * - `notification.superx.showNotify` — the island may actually appear;
 * - `notification.superx.template`   — 1 = base info card, 2 = progress card;
 * - `notification.superx.scene`      — a scene name; upstream uses "TRAIN";
 * - `notification.superx.baseInfos`  — title, content, optional icon and subtext;
 * - `notification.superx.capsule`    — state + content + icon: the collapsed pill
 *   and the hint the template resolver needs to fire at all;
 * - `notification.superx.infos`      — per-template payload (progress percent /
 *   color and placeholder nodes on the progress template, describe / coreInfo /
 *   image otherwise);
 * - `notification.superx.shortInfos` — the same three fields, "Short" suffixes,
 *   for the card expansion parity the framework expects;
 * - `notification.superx.island`     — `island.superx.leftTemplate` (upstream
 *   always sends 1), `island.superx.rightTemplate` (one of [OriginIslandTemplate]),
 *   and the matching `leftInfo` / `rightInfo` bundles.
 *
 * The writes go through [SuperXSink], not through `Bundle` directly, so the
 * JVM test suite can record them and pin the wire format — `android.os.Bundle`
 * is a stub with default-valued methods on the JVM and can verify nothing.
 *
 * The one deliberate addition over upstream: a device-support probe
 * (`vivoBrand()`, `originOsVersion()`), because a payload the phone will
 * ignore is worth warning about rather than silently no-oping.
 */
object OriginIsland {

    /** Operation codes for `notification.superx.operation`. */
    const val OP_SHOW = 0
    const val OP_END = 2

    /** The scene name upstream uses; the framework matches on it to resolve templates. */
    const val SCENE = "TRAIN"

    /** What the right side of the island renders. Values are the upstream ones. */
    enum class OriginIslandTemplate(val id: Int, val key: String) {
        /** Animated rhythm pulse with a configurable color. */
        RHYTHM(1, "rhythm"),

        /** Progress ring driven by `rightInfo.progressValue` (0–100). */
        PROGRESS(2, "progress"),

        /** Indeterminate loading spinner with a configurable color. */
        LOADING(3, "loading"),

        /** Text on the left, icon on the right. */
        TEXT_ICON(4, "text_icon"),

        /** Icon on the left, text on the right. */
        ICON_TEXT(5, "icon_text"),

        /** Symmetric capsule with a colored background. */
        CAPSULE(6, "capsule");

        companion object {
            fun fromId(id: Int): OriginIslandTemplate = entries.firstOrNull { it.id == id } ?: CAPSULE
        }
    }

    /** Everything one island notification carries. */
    data class Request(
        val id: Int,
        val title: String,
        val content: String,
        val subText: String? = null,
        /** The right-side template. Upstream defaults to 6 (capsule symmetry). */
        val template: OriginIslandTemplate = OriginIslandTemplate.CAPSULE,
        /** Progress 0..progressMax; only used when [template] is [OriginIslandTemplate.PROGRESS]. */
        val progress: Int = 0,
        val progressMax: Int = 100,
        /** Text for the left side of the island; upstream falls back to [title]. */
        val leftText: String? = null,
        /** Text for the right side on the text-bearing templates; upstream falls back to [content]. */
        val rightText: String? = null,
        /** Icon color hint (ARGB), applied where the template takes one. */
        val accentColor: Int? = null,
    ) {
        /** 0–100 clamped, for the progress templates. */
        val progressPercent: Int
            get() = if (progressMax > 0) ((progress * 100) / progressMax).coerceIn(0, 100) else 0

        /** Upstream switches to the progress template when progress is meaningfully underway. */
        val isProgressMode: Boolean
            get() = template == OriginIslandTemplate.PROGRESS && progressMax > 0 && progress in 1 until progressMax
    }

    /**
     * The write surface the payload builder needs. `Bundle` covers every method
     * here on the device; the JVM tests implement it with a recording map, which
     * is what makes the wire format assertable off-device.
     */
    interface SuperXSink {
        /** A fresh sink of the same kind, for the nested `baseInfos`-style bundles. */
        fun child(): SuperXSink

        fun putInt(key: String, value: Int)
        fun putBoolean(key: String, value: Boolean)
        fun putString(key: String, value: String?)
        fun putCharSequence(key: String, value: CharSequence?)
        fun putIntList(key: String, value: List<Int>?)
        fun putIcon(key: String, value: Icon?)
        fun putIconList(key: String, value: List<Icon>?)

        /** Nest a completed child bundle under [key]. */
        fun putBundle(key: String, value: SuperXSink)
    }

    /** The production sink: writes straight into the real [Bundle] the extras ship in. */
    open class BundleSink(protected val bundle: Bundle) : SuperXSink {
        override fun child(): SuperXSink = BundleSink(Bundle())

        override fun putInt(key: String, value: Int) = bundle.putInt(key, value)
        override fun putBoolean(key: String, value: Boolean) = bundle.putBoolean(key, value)
        override fun putString(key: String, value: String?) = bundle.putString(key, value)
        override fun putCharSequence(key: String, value: CharSequence?) = bundle.putCharSequence(key, value)
        override fun putIntList(key: String, value: List<Int>?) =
            bundle.putIntegerArrayList(key, value?.let { ArrayList(it) })

        override fun putIcon(key: String, value: Icon?) {
            if (value != null) bundle.putParcelable(key, value)
        }

        override fun putIconList(key: String, value: List<Icon>?) {
            if (value != null) bundle.putParcelableArrayList(key, ArrayList(value))
        }

        override fun putBundle(key: String, value: SuperXSink) {
            val nested = (value as? BundleSink)?.bundle ?: return
            bundle.putBundle(key, nested)
        }

        fun toBundle(): Bundle = bundle
    }

    /** True on vivo / iQOO hardware, where the island exists at all. */
    fun vivoBrand(): Boolean {
        // Platform-type fields: null on the JVM, real values on the device.
        val brand = android.os.Build.BRAND.orEmpty()
        val manufacturer = android.os.Build.MANUFACTURER.orEmpty()
        return brand.contains("vivo", ignoreCase = true) ||
            brand.contains("iqoo", ignoreCase = true) ||
            manufacturer.contains("vivo", ignoreCase = true)
    }

    /**
     * Best-effort read of the OriginOS version property. `ro.vivo.os.version`
     * is system-visible only to privileged callers, so this returns `null`
     * from an ordinary app — the UI reports it as unknown rather than guessing.
     */
    fun originOsVersion(): String? = runCatching {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        (get.invoke(null, "ro.vivo.os.version") as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /**
     * The full `notification.superx.*` bundle for [request].
     *
     * Pure with respect to [Request]: same input, same output, no device reads —
     * which is what makes it unit-testable on the JVM. Icons are optional and
     * only attached when supplied, so the payload can be built without an
     * Android graphics stack.
     */
    fun buildExtras(
        request: Request,
        smallIcon: Icon? = null,
        accentColor: Int? = request.accentColor ?: FALLBACK_ACCENT,
    ): Bundle {
        val sink = BundleSink(Bundle())
        writeExtras(request, smallIcon, accentColor, sink)
        return sink.toBundle()
    }

    /** The end-of-island payload: tells the framework to unmount the pill before the notification is cancelled. */
    fun buildEndExtras(): Bundle {
        val sink = BundleSink(Bundle())
        writeEndExtras(sink)
        return sink.toBundle()
    }

    /**
     * The payload writer — the actual port. Every key below is upstream's; the
     * sink indirection is the only structural liberty taken.
     */
    fun writeExtras(
        request: Request,
        smallIcon: Icon?,
        accentColor: Int? = request.accentColor ?: FALLBACK_ACCENT,
        out: SuperXSink,
    ) {
        val progressMode = request.isProgressMode

        out.putInt("notification.superx.operation", OP_SHOW)
        out.putBoolean("notification.superx.showNotify", true)
        out.putInt("notification.superx.template", if (progressMode) 2 else 1)
        out.putString("notification.superx.scene", SCENE)
        out.putInt("notification.superx.changedRecord", 0)

        // -- baseInfos -------------------------------------------------------
        val baseInfos = out.child()
        baseInfos.putCharSequence("notification.superx.baseInfos.title", request.title)
        baseInfos.putCharSequence("notification.superx.baseInfos.content", request.content)
        baseInfos.putIcon("notification.superx.baseInfos.icon", smallIcon)
        request.subText?.takeIf { it.isNotBlank() }?.let { sub ->
            baseInfos.putInt("notification.superx.baseInfos.subInfo", 1)
            baseInfos.putString("notification.superx.baseInfos.subText", sub)
        }
        out.putBundle("notification.superx.baseInfos", baseInfos)

        // -- capsule: required for the framework's template resolution -------
        val capsule = out.child()
        capsule.putInt("notification.superx.capsule.state", 1)
        capsule.putCharSequence("notification.superx.capsule.content", request.title)
        capsule.putIcon("notification.superx.capsule.icon", smallIcon)
        out.putBundle("notification.superx.capsule", capsule)

        // -- infos: the template-specific payload -----------------------------
        val infos = out.child()
        if (progressMode) {
            infos.putInt("notification.superx.infos.progress", request.progressPercent)
            accentColor?.let { infos.putInt("notification.superx.infos.progressColor", it) }
            // The progress template draws node icons along the track; upstream
            // found two is the minimum the renderer accepts.
            smallIcon?.let { icon ->
                infos.putIconList("notification.superx.infos.nodeIcon", listOf(icon, icon))
                infos.putIcon("notification.superx.infos.indicatorIcon", icon)
                infos.putInt("notification.superx.infos.indicatorLoc", 1)
            }
        } else {
            infos.putString(
                "notification.superx.infos.describe",
                request.subText?.takeIf { it.isNotBlank() } ?: request.title,
            )
            infos.putString("notification.superx.infos.coreInfo", request.content)
            infos.putIcon("notification.superx.infos.image", smallIcon)
        }
        out.putBundle("notification.superx.infos", infos)

        // -- shortInfos: same fields again, for card expansion parity ---------
        val shortInfos = out.child()
        shortInfos.putString(
            "notification.superx.shortInfos.describeShort",
            request.subText?.takeIf { it.isNotBlank() } ?: request.title,
        )
        shortInfos.putString("notification.superx.shortInfos.coreInfoShort", request.content)
        shortInfos.putIcon("notification.superx.shortInfos.image", smallIcon)
        out.putBundle("notification.superx.shortInfos", shortInfos)

        // -- island: left template is fixed upstream; right is the choice -----
        val island = out.child()
        island.putInt("island.superx.leftTemplate", 1)
        island.putInt("island.superx.rightTemplate", request.template.id)

        val left = island.child()
        left.putString(
            "island.superx.leftInfo.content",
            request.leftText?.takeIf { it.isNotBlank() } ?: request.title,
        )
        left.putIcon("island.superx.leftInfo.icon", smallIcon)
        island.putBundle("island.superx.leftInfo", left)

        val right = island.child()
        when (request.template) {
            OriginIslandTemplate.RHYTHM -> {
                right.putInt("island.superx.rightInfo.waveState", 1)
                accentColor?.let { color ->
                    right.putIntList("island.superx.rightInfo.waveColor", listOf(color))
                }
            }

            OriginIslandTemplate.PROGRESS -> {
                right.putInt("island.superx.rightInfo.progressValue", request.progressPercent)
                right.putInt("island.superx.rightInfo.progressState", 0)
                accentColor?.let { right.putInt("island.superx.rightInfo.progressColor", it) }
            }

            OriginIslandTemplate.LOADING -> {
                accentColor?.let { right.putInt("island.superx.rightInfo.loadingColor", it) }
            }

            OriginIslandTemplate.TEXT_ICON, OriginIslandTemplate.ICON_TEXT -> {
                right.putString(
                    "island.superx.rightInfo.content",
                    request.rightText?.takeIf { it.isNotBlank() } ?: request.content,
                )
                right.putIcon("island.superx.rightInfo.icon", smallIcon)
            }

            OriginIslandTemplate.CAPSULE -> {
                right.putString(
                    "island.superx.rightInfo.capsuleContent",
                    request.rightText?.takeIf { it.isNotBlank() } ?: request.content,
                )
                accentColor?.let { right.putInt("island.superx.rightInfo.capsuleBgColor", it) }
            }
        }
        island.putBundle("island.superx.rightInfo", right)
        out.putBundle("notification.superx.island", island)
    }

    /** The graceful unmount write: nothing but the end operation. */
    fun writeEndExtras(out: SuperXSink) {
        out.putInt("notification.superx.operation", OP_END)
    }

    /** Upstream uses teal (0xff80cbc4) when the template takes a color and none was configured. */
    private const val FALLBACK_ACCENT: Int = 0xFF80CBC4.toInt()
}
