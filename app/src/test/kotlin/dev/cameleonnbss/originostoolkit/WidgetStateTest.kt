package dev.cameleonnbss.originostoolkit

import androidx.compose.ui.geometry.Offset
import dev.cameleonnbss.originostoolkit.core.ops.AccessLevel
import dev.cameleonnbss.originostoolkit.ui.theme.G2Curve
import dev.cameleonnbss.originostoolkit.widget.RefreshWidgetState
import dev.cameleonnbss.originostoolkit.widget.ToolkitWidgetState
import dev.cameleonnbss.originostoolkit.widget.UNKNOWN
import dev.cameleonnbss.originostoolkit.widget.WidgetAction
import dev.cameleonnbss.originostoolkit.widget.hzText
import dev.cameleonnbss.originostoolkit.widget.parseHz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow

/**
 * The two home-screen components decide what to show and what a tap does.
 *
 * All of that is `RemoteViews` on the device, which a JVM test cannot look at —
 * so the decisions live in `WidgetState` and are pinned here, and the view
 * builder is left with nothing but `setTextViewText`.
 */
class WidgetStateTest {

    private fun state(
        panel: Float? = 144f,
        peak: String? = "60.0",
        min: String? = "60.0",
        journaled: Boolean = false,
        canWrite: Boolean = true,
    ) = RefreshWidgetState(
        panelHz = panel,
        peakSetting = peak,
        minSetting = min,
        journaled = journaled,
        canWrite = canWrite,
    )

    @Test
    fun `a journaled tweak is forced and offers to go back`() {
        val value = state(journaled = true)
        assertTrue(value.forced)
        assertEquals(WidgetAction.REVERT, value.action)
    }

    @Test
    fun `an untouched phone offers to pin the panel maximum`() {
        val value = state()
        assertFalse(value.forced)
        assertEquals(WidgetAction.APPLY, value.action)
        // The big number is what the tap would produce, not the current 60.
        assertEquals("144", value.headline)
    }

    @Test
    fun `pinning is read from the setting even when the app did not write it`() {
        // Another tool, or an older install, may have left the value at the top:
        // offering to "pin" an already-pinned rate would be a lie.
        val value = state(peak = "144.0", journaled = false)
        assertTrue(value.forced)
        assertEquals("144", value.headline)
    }

    @Test
    fun `a value the panel cannot reach still reads as forced`() {
        // 165 is in the catalog; a 120 Hz panel will clamp it. Reporting 165 as
        // the pinned number is honest about what was asked for, and the panel
        // maximum is printed on the line underneath.
        val value = state(panel = 120f, peak = "165.0")
        assertTrue(value.forced)
        assertEquals("165", value.headline)
        assertEquals("120", value.panelText)
    }

    @Test
    fun `without write access the tap is not an action`() {
        val value = state(canWrite = false)
        assertEquals(WidgetAction.NONE, value.action)
    }

    @Test
    fun `an unreadable panel falls back to what is pinned`() {
        val value = state(panel = null, peak = "90.0")
        assertEquals("90", value.headline)
        assertEquals(UNKNOWN, value.panelText)
    }

    @Test
    fun `nothing readable is a dash, never a zero`() {
        val value = state(panel = null, peak = null, min = null)
        assertEquals(UNKNOWN, value.headline)
        assertEquals(UNKNOWN, value.panelText)
        assertEquals(UNKNOWN, value.minText)
    }

    @Test
    fun `rates are printed the way Android stores them`() {
        assertEquals("120", hzText(120f))
        assertEquals("144", hzText(144.0f))
        // 59.94 is a real refresh rate; rounding it to 60 would hide the point.
        assertEquals("59.94", hzText(59.94f))
        assertEquals("60", hzText(60.0f))
    }

    @Test
    fun `settings values are parsed, and null is not a rate`() {
        assertEquals(144f, parseHz("144.0")!!, 0.001f)
        assertEquals(60f, parseHz(" 60 ")!!, 0.001f)
        assertNull(parseHz("null"))
        assertNull(parseHz(""))
        assertNull(parseHz(null))
        assertNull(parseHz("not a number"))
    }

    @Test
    fun `the min line follows the setting`() {
        assertEquals("60", state(min = "60.0").minText)
        assertEquals("59.94", state(min = "59.94").minText)
    }

    @Test
    fun `the status tile shows what it can actually read`() {
        val value = ToolkitWidgetState(
            model = "V2337A",
            osLabel = "Android 16",
            densityDpi = 460,
            panelHz = 165f,
            appliedCount = 3,
            level = AccessLevel.NONE,
        )
        assertEquals("460", value.densityText)
        assertEquals("165", value.panelText)
        assertEquals("3", value.appliedText)
        assertEquals(UNKNOWN, ToolkitWidgetState().densityText)
    }

    // -- the G2 corner -----------------------------------------------------

    /**
     * The corner is a superellipse of exponent 4, and the property that makes it
     * G2 is where it sits on the diagonal: `0.225 r` from the corner point,
     * against a quarter circle's `0.414 r`. If this ever drifts, the shape has
     * quietly become a rounded rectangle again.
     */
    @Test
    fun `a G2 corner cuts far less deep than a circle of the same radius`() {
        for (radius in listOf(6f, 16f, 24f, 60f)) {
            val g2 = G2Curve.diagonalDistance(radius)
            val circle = (1f - 1f / kotlin.math.sqrt(2f)) * radius * kotlin.math.sqrt(2f)
            assertEquals(0.225f * radius, g2, 0.01f * radius)
            assertEquals(0.414f * radius, circle, 0.01f * radius)
            assertTrue("G2 must stay nearer the corner than a circle", g2 < circle)
        }
    }

    @Test
    fun `a corner starts and ends exactly where a circular one would`() {
        val corner = Offset(10f, 20f)
        val incoming = Offset(0f, 1f)
        val outgoing = Offset(1f, 0f)
        val points = G2Curve.corner(corner, incoming, outgoing, radius = 12f)

        assertEquals(25, points.size)
        assertEquals(10f, points.first().x, 0.001f)
        assertEquals(32f, points.first().y, 0.001f)
        assertEquals(22f, points.last().x, 0.001f)
        assertEquals(20f, points.last().y, 0.001f)
    }

    @Test
    fun `every sample stays inside the corner square`() {
        val corner = Offset.Zero
        G2Curve.corner(corner, Offset(0f, 1f), Offset(1f, 0f), radius = 24f).forEach { point ->
            assertTrue(point.x in 0f..24.001f)
            assertTrue(point.y in 0f..24.001f)
        }
    }

    @Test
    fun `every sample sits on the superellipse of exponent four`() {
        val radius = 24f
        val points = G2Curve.corner(Offset.Zero, Offset(0f, 1f), Offset(1f, 0f), radius)

        points.zipWithNext().forEach { (first, second) ->
            assertTrue("x must never step back", second.x >= first.x - 0.0001f)
            assertTrue("y must never step back", second.y <= first.y + 0.0001f)
        }

        // The defining equation, written in the corner's own frame: with `a` the
        // distance along one edge from the corner point and `b` the distance along
        // the other, |r-a|ⁿ + |r-b|ⁿ = rⁿ. On this corner the two edges are the y
        // and x axes, so the projections are just the coordinates.
        points.forEach { point ->
            val alongEdge = 1f - point.y / radius
            val alongOther = 1f - point.x / radius
            val value = alongEdge.pow(4) + alongOther.pow(4)
            assertEquals("sample off the curve at $point", 1f, value, 0.001f)
        }
    }

    @Test
    fun `the corner cuts about a third of what a circle would`() {
        val radius = 24f
        val polygon = listOf(Offset.Zero) +
            G2Curve.corner(Offset.Zero, Offset(0f, 1f), Offset(1f, 0f), radius) +
            Offset.Zero

        // Shoelace over the corner point and the samples: the region between the
        // corner and the curve, which is the area the eye reads as roundness.
        var twiceArea = 0f
        polygon.zipWithNext().forEach { (first, second) ->
            twiceArea += first.x * second.y - second.x * first.y
        }
        val g2Area = abs(twiceArea) / 2f
        val circleArea = radius * radius * (1f - (PI / 4f).toFloat())

        assertTrue("a G2 corner must cut less than a circle", g2Area < circleArea)
        assertTrue(
            "the cut should be roughly a third of the circle's: $g2Area vs $circleArea",
            g2Area > 0.2f * circleArea && g2Area < 0.5f * circleArea,
        )
    }

    @Test
    fun `a zero radius produces no points at all`() {
        assertTrue(G2Curve.corner(Offset.Zero, Offset(0f, 1f), Offset(1f, 0f), 0f).isEmpty())
    }
}
