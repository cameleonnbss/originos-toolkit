package dev.cameleonnbss.originostoolkit

import dev.cameleonnbss.originostoolkit.core.FpsSampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame-rate meter is only worth having if its arithmetic is right, so the
 * parsing is tested against real `dumpsys SurfaceFlinger --latency` output —
 * including the rows that hold no frame.
 */
class FpsSamplerTest {

    /** One refresh period line, then rows of desired/actual/frameReady. */
    private fun latencyOutput(periodNanos: Long, presents: List<Long>): String = buildString {
        appendLine(periodNanos)
        presents.forEach { present ->
            val desired = present - 1_000_000
            appendLine("$desired\t$present\t$present")
        }
    }

    private val neverPresented = Long.MAX_VALUE

    @Test
    fun `counts frames and derives the rate from the present timestamps`() {
        // 60 frames at 16.667 ms: 59 intervals over one second = 60 fps.
        val step = 16_666_667L
        val presents = (0 until 60).map { index -> 1_000_000_000L + index * step }
        val timings = FpsSampler.parseLatency(latencyOutput(16_666_667L, presents))

        assertEquals(60, timings.frames)
        assertEquals(60.0, timings.fps!!, 0.2)
        assertEquals(60.0, timings.refreshRateFromPeriod!!, 0.1)
    }

    @Test
    fun `drops rows that hold no frame instead of counting them as instant ones`() {
        val step = 16_666_667L
        val presents = mutableListOf(1_000_000_000L, 1_000_000_000L + step, neverPresented)
        presents += (2 until 30).map { index -> 1_000_000_000L + index * step }
        val timings = FpsSampler.parseLatency(latencyOutput(16_666_667L, presents))

        assertEquals("the empty row is not a frame", 30, timings.frames)
        assertTrue(timings.intervalsMs.none { it <= 0.0 })
    }

    @Test
    fun `de-duplicates a frame reported in several slots`() {
        val step = 16_666_667L
        val presents = mutableListOf<Long>()
        repeat(20) { index ->
            val present = 1_000_000_000L + index * step
            // Some builds repeat the same present time across slots.
            presents += listOf(present, present, present)
        }
        val timings = FpsSampler.parseLatency(latencyOutput(16_666_667L, presents))

        assertEquals(20, timings.frames)
        assertEquals(60.0, timings.fps!!, 0.5)
    }

    @Test
    fun `reports a 1 percent low that reflects the worst frames`() {
        val step = 16_666_667L
        val presents = mutableListOf<Long>()
        var now = 1_000_000_000L
        repeat(100) { index ->
            // Every 20th frame is a hitch of three frame times.
            now += if (index % 20 == 0) step * 4 else step
            presents += now
        }
        val timings = FpsSampler.parseLatency(latencyOutput(16_666_667L, presents))

        val low = timings.onePercentLowFps
        assertNotNull(low)
        assertTrue("a 1% low of ${low!!} should be well under the average", low < timings.fps!!)
        assertTrue("the hitch is 66 ms, so the 1% low is about 15 fps", low in 12.0..20.0)
    }

    @Test
    fun `a single frame has no measurable rate`() {
        val timings = FpsSampler.parseLatency(latencyOutput(16_666_667L, listOf(1_000_000_000L)))
        assertEquals(1, timings.frames)
        assertNull(timings.fps)
        assertTrue(timings.onePercentLowFps == null)
    }

    @Test
    fun `garbage input yields nothing rather than a number`() {
        val timings = FpsSampler.parseLatency("not a surfaceflinger dump\n\n")
        assertTrue(timings.isEmpty)
        assertNull(timings.fps)
    }

    @Test
    fun `comma separated output is handled too`() {
        val output = buildString {
            appendLine("16666667")
            (0 until 10).forEach { index ->
                val present = 1_000_000_000L + index * 16_666_667L
                appendLine("${present - 1000}, $present, $present")
            }
        }
        assertEquals(10, FpsSampler.parseLatency(output).frames)
    }

    // -- layer selection ---------------------------------------------------

    private val layers = """
        com.android.systemui/com.android.systemui.ImageWallpaper#0
        SurfaceView[com.miHoYo.GenshinImpact/com.miHoYo.GetMobileInfo.MainActivity]#0
        com.miHoYo.GenshinImpact/com.miHoYo.GetMobileInfo.MainActivity
        Background for SurfaceView[com.miHoYo.GenshinImpact/...]#0
        SurfaceFlinger
        StatusBar#0
    """.trimIndent()

    @Test
    fun `prefers the SurfaceView of the foreground package`() {
        val layer = FpsSampler.chooseLayer(layers, "com.miHoYo.GenshinImpact")
        assertEquals(
            "SurfaceView[com.miHoYo.GenshinImpact/com.miHoYo.GetMobileInfo.MainActivity]#0",
            layer,
        )
    }

    @Test
    fun `never samples another package or SurfaceFlinger itself`() {
        assertNull(FpsSampler.chooseLayer(layers, "com.example.absent"))
        val systemUi = FpsSampler.chooseLayer(layers, "com.android.systemui")
        assertTrue("SystemUI is a valid target", systemUi != null && systemUi.contains("systemui"))
    }

    @Test
    fun `extracts the package out of a layer name`() {
        assertEquals(
            "com.miHoYo.GenshinImpact",
            FpsSampler.layerPackage("SurfaceView[com.miHoYo.GenshinImpact/com.miHoYo.X]#0"),
        )
        assertEquals("com.example.app", FpsSampler.layerPackage("com.example.app/com.example.app.Main"))
        assertNull(FpsSampler.layerPackage("StatusBar#0"))
    }

    // -- foreground detection ----------------------------------------------

    @Test
    fun `finds the resumed package across build formats`() {
        val modern = "  topResumedActivity=ActivityRecord{1a2b3c u0 com.example.game/.Main t42}"
        assertEquals("com.example.game", FpsSampler.foregroundPackage(modern))

        val older = "  mResumedActivity: ActivityRecord{1a2b3c u0 com.example.game/.Main t42}"
        assertEquals("com.example.game", FpsSampler.foregroundPackage(older))

        val activityRecord = "#0: ActivityRecord{7f2 u0 com.android.settings/.Settings t9}"
        assertEquals("com.android.settings", FpsSampler.foregroundPackage(activityRecord))
    }

    @Test
    fun `no resumed activity means no package rather than a guess`() {
        assertNull(FpsSampler.foregroundPackage("Activities in Current Task:\n  (none)"))
    }

    // -- presentation ------------------------------------------------------

    @Test
    fun `the overlay line says what was measured`() {
        val step = 16_666_667L
        val presents = (0 until 60).map { index -> 1_000_000_000L + index * step }
        val line = FpsSampler.describe(FpsSampler.parseLatency(latencyOutput(step, presents)))
        assertNotNull(line)
        assertTrue(line!!.contains("fps"))
        assertTrue(line.contains("1%"))
    }

    @Test
    fun `nothing measured means no line, never a zero`() {
        assertNull(FpsSampler.describe(FpsSampler.parseLatency("")))
    }
}
