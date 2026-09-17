package dev.cameleonnbss.originostoolkit.core

/**
 * Real frame timings, parsed from `dumpsys SurfaceFlinger --latency <layer>`.
 *
 * The distinction this file exists to preserve: **a vsync callback is not a
 * frame**. Counting `Choreographer` callbacks tells you the panel's refresh
 * rate — the rate at which the compositor *could* present — and nothing at all
 * about how many frames the app in front actually produced. Only the display
 * pipeline's own present timestamps can answer that, which is why a real
 * frame-rate meter reads SurfaceFlinger.
 *
 * `--latency` prints a refresh period in nanoseconds, then up to 128 rows of
 * `desiredPresentTime / actualPresentTime / frameReadyTime`. A row that was
 * never presented carries `Long.MAX_VALUE` as its present time; those are
 * gaps, not frames, and are dropped rather than counted as zero-length frames.
 */
data class FrameTimings(
    /** The refresh period SurfaceFlinger reported, in nanoseconds. */
    val refreshPeriodNanos: Long? = null,
    /** Present timestamps in nanoseconds, ascending, de-duplicated. */
    val presentTimesNanos: List<Long> = emptyList(),
) {
    val frames: Int get() = presentTimesNanos.size

    /** How long the sample covers, in seconds. */
    val spanSeconds: Double?
        get() = if (frames < 2) null else (last() - first()) / 1_000_000_000.0

    /**
     * Frames per second over the sample.
     *
     * `frames - 1` intervals span `last - first`, which is why the count is
     * decremented: the window between the first and last present is what was
     * measured, not the window between the first present and now.
     */
    val fps: Double?
        get() {
            val span = spanSeconds ?: return null
            if (span <= 0.0) return null
            return (frames - 1) / span
        }

    /** Frame intervals in milliseconds, in presentation order. */
    val intervalsMs: List<Double>
        get() = presentTimesNanos.zipWithNext { previous, next ->
            (next - previous) / 1_000_000.0
        }

    /**
     * The 1% low: the frame rate you get during the worst 1% of frames.
     *
     * This is the number that matches what a game feels like. A steady 60 fps
     * with a hundred-millisecond hitch is a stutter the average never shows.
     * Computed as `1000 / p99(interval)`, which is the standard definition.
     */
    val onePercentLowFps: Double?
        get() {
            val intervals = intervalsMs
            if (intervals.size < 10) return null
            val sorted = intervals.sorted()
            val index = ((sorted.size - 1) * 0.99).toInt().coerceAtLeast(0)
            val worst = sorted[index]
            if (worst <= 0.0) return null
            return 1000.0 / worst
        }

    /** The panel's refresh rate, taken from the reported period. */
    val refreshRateFromPeriod: Double?
        get() {
            val period = refreshPeriodNanos ?: return null
            if (period <= 0L) return null
            return 1_000_000_000.0 / period
        }

    val isEmpty: Boolean get() = frames == 0

    private fun first() = presentTimesNanos.first()

    private fun last() = presentTimesNanos.last()
}

/**
 * Reads SurfaceFlinger's own statistics.
 *
 * Pure parsing, no Android types: the formats below are stable AOSP output, and
 * keeping this testable is the only way to be sure a meter is not lying.
 */
object FpsSampler {

    /** The value AOSP writes for a row that holds no presented frame. */
    private const val NOT_PRESENTED = Long.MAX_VALUE

    /** Layer names we never want: our own overlay, and SurfaceFlinger's own. */
    private val NOISE = listOf("SurfaceFlinger", "Background for", "Bounds for", "Cursor", "Sprite")

    /**
     * Parses `dumpsys SurfaceFlinger --latency`.
     *
     * Rows are de-duplicated by present time: the same frame is reported in
     * several slots on some builds, and counting it twice invents frames.
     */
    fun parseLatency(output: String): FrameTimings {
        var period: Long? = null
        val presents = sortedSetOf<Long>()

        output.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim('\r', ' ', '\t')
            if (line.isEmpty()) return@forEachIndexed

            val columns = line.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }

            // The first line is the refresh period, alone.
            if (index == 0 && columns.size == 1) {
                period = columns[0].toLongOrNull()
                return@forEachIndexed
            }

            if (columns.size < 3) return@forEachIndexed
            val present = columns[1].toLongOrNull() ?: return@forEachIndexed
            if (present <= 0L || present == NOT_PRESENTED) return@forEachIndexed
            presents.add(present)
        }

        return FrameTimings(refreshPeriodNanos = period, presentTimesNanos = presents.toList())
    }

    /**
     * Picks the layer to sample for [packageName] out of `dumpsys SurfaceFlinger
     * --list`.
     *
     * Preference order, most informative first:
     *  1. `SurfaceView[<pkg>/...]` — the surface games and video players draw
     *     into, i.e. the one whose frames you want to measure;
     *  2. any layer carrying the package name, which covers Compose and View
     *     hierarchies;
     *  3. the package's own activity surface.
     */
    fun chooseLayer(listOutput: String, packageName: String): String? {
        val layers = listOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(packageName) }
            .filter { layer -> NOISE.none { layer.contains(it, ignoreCase = false) } }
            .toList()

        if (layers.isEmpty()) return null

        return layers.firstOrNull { it.startsWith("SurfaceView[") && layerPackage(it) == packageName }
            ?: layers.firstOrNull { layerPackage(it) == packageName && it.contains("/") }
            ?: layers.firstOrNull { layerPackage(it) == packageName }
            ?: layers.first()
    }

    /** Extracts the package name a layer reports, if it reports one. */
    fun layerPackage(layer: String): String? {
        val start = layer.indexOf('[').let { if (it >= 0) it + 1 else 0 }
        val body = layer.substring(start)
        val candidate = body.substringBefore('/').substringBefore(']').substringBefore('(')
        val trimmed = candidate.trim()
        return trimmed.takeIf { it.contains('.') && it.none { ch -> ch.isWhitespace() } }
    }

    /**
     * The foreground package, from `dumpsys activity activities`.
     *
     * Used instead of usage-stats access: when we can sample SurfaceFlinger we
     * already have the shell user, so we can read the truth directly and do not
     * have to ask the user for a second grant.
     */
    fun foregroundPackage(activityOutput: String): String? {
        val patterns = listOf(
            Regex("topResumedActivity.*?\\s([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)"),
            Regex("mResumedActivity.*?\\s([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)"),
            Regex("ResumedActivity.*?\\s([a-zA-Z0-9_.]+)/"),
            Regex("realActivity=([a-zA-Z0-9_.]+)/"),
            // Last resort: every build prints the task's own ActivityRecord, even
            // when the resumed-activity field is named something new. It is
            // listed last precisely because it is the least specific, and a
            // wrong choice only costs a stale sample, never a wrong number:
            // SurfaceFlinger only reports frames the layer actually presented.
            Regex("ActivityRecord\\{[^}]*\\bu0\\s+([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(activityOutput) ?: continue
            val pkg = match.groupValues[1]
            if (pkg.isNotBlank() && pkg.contains('.')) return pkg
        }
        return null
    }

    /** One line for the overlay: what was measured, and how. */
    fun describe(timings: FrameTimings, label: String? = null): String? {
        if (timings.isEmpty) return null
        val fps = timings.fps ?: return null
        val low = timings.onePercentLowFps
        val head = "%.0f fps".format(fps)
        val lowPart = if (low != null) " · 1%% low %.0f".format(low) else ""
        val tag = if (label.isNullOrBlank()) "" else " · $label"
        return head + lowPart + tag
    }
}
