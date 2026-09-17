package dev.cameleonnbss.originostoolkit.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * The corner OriginOS draws, instead of a quarter circle.
 *
 * vivo's own OriginOS 6 copy calls them *"soft G2-rounded corners"*, and the
 * difference is real: a `RoundedCornerShape` meets the straight edge at a
 * tangent where the curvature jumps from `0` to `1/r`, which the eye reads as a
 * corner filed off a rectangle. A G2 corner eases into the edge — the curvature
 * is continuous — which is what the system's panels, cards and atomic
 * components all look like.
 *
 * The geometry is a superellipse of exponent [G2Curve.EXPONENT]. Both fits are
 * exact at the tangent points (the corner spans the same `r` along each edge as
 * a circular corner of radius `r`), but between them the superellipse stays
 * nearer the corner point: at 45° it sits `0.225 r` from it against the circle's
 * `0.414 r`. That is why the radii in [OriginShapes] are a little larger than
 * the Material defaults — a G2 corner reads tighter than a circular one of the
 * same radius, so the number has to go up to keep the same silhouette.
 *
 * See `docs/ORIGINOS-LOOK.md` for the measurements this is based on.
 */
@Immutable
class G2CornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): G2CornerShape = G2CornerShape(topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        // Start/end are resolved the way RoundedCornerShape resolves them, so a
        // right-to-left layout mirrors the shape exactly as it mirrors the rest.
        val ltr = layoutDirection == LayoutDirection.Ltr
        var tl = (if (ltr) topStart else topEnd).coerceAtLeast(0f)
        var tr = (if (ltr) topEnd else topStart).coerceAtLeast(0f)
        var br = (if (ltr) bottomEnd else bottomStart).coerceAtLeast(0f)
        var bl = (if (ltr) bottomStart else bottomEnd).coerceAtLeast(0f)

        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return Outline.Rectangle(size.toRect())
        if (tl + tr + br + bl < 1f) return Outline.Rectangle(size.toRect())

        // Two corners on the same edge can never overlap: a circular corner
        // simply degenerates into a pill, but a superellipse would fold back on
        // itself, so the radii are scaled down together instead.
        val scale = minOf(
            1f,
            safeRatio(width, tl + tr),
            safeRatio(width, bl + br),
            safeRatio(height, tl + bl),
            safeRatio(height, tr + br),
        )
        if (scale < 1f) {
            tl *= scale
            tr *= scale
            br *= scale
            bl *= scale
        }

        val x0 = 0f
        val y0 = 0f
        val x1 = width
        val y1 = height

        val path = Path()
        // Clockwise from the top edge, so the corner vectors always name the
        // tangent point the path is coming from and the one it is going to.
        path.moveTo(x0 + tl, y0)
        path.lineTo(x1 - tr, y0)
        path.arc(x1, y0, Offset(-1f, 0f), Offset(0f, 1f), tr)
        path.lineTo(x1, y1 - br)
        path.arc(x1, y1, Offset(0f, -1f), Offset(-1f, 0f), br)
        path.lineTo(x0 + bl, y1)
        path.arc(x0, y1, Offset(1f, 0f), Offset(0f, -1f), bl)
        path.lineTo(x0, y0 + tl)
        path.arc(x0, y0, Offset(0f, 1f), Offset(1f, 0f), tl)
        path.close()

        return Outline.Generic(path)
    }

    /** Walks one G2 corner; the incoming tangent is already the current point. */
    private fun Path.arc(
        cornerX: Float,
        cornerY: Float,
        incoming: Offset,
        outgoing: Offset,
        radius: Float,
    ) {
        if (radius < 0.5f) return
        G2Curve.corner(Offset(cornerX, cornerY), incoming, outgoing, radius).forEach { point ->
            lineTo(point.x, point.y)
        }
    }

    override fun toString(): String =
        "G2CornerShape(topStart = $topStart, topEnd = $topEnd, " +
            "bottomEnd = $bottomEnd, bottomStart = $bottomStart)"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is G2CornerShape) return false
        return topStart == other.topStart &&
            topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd &&
            bottomStart == other.bottomStart
    }

    override fun hashCode(): Int {
        var result = topStart.hashCode()
        result = 31 * result + topEnd.hashCode()
        result = 31 * result + bottomEnd.hashCode()
        result = 31 * result + bottomStart.hashCode()
        return result
    }

    private fun safeRatio(available: Float, needed: Float): Float =
        if (needed <= 0f) 1f else available / needed
}

/** `G2CornerShape(22.dp)` — the shape the whole app is built from. */
fun G2CornerShape(size: Dp): G2CornerShape = G2CornerShape(CornerSize(size))

/** All four corners at once, for the cases that want a single value. */
fun G2CornerShape(size: CornerSize): G2CornerShape =
    G2CornerShape(size, size, size, size)

/**
 * The corner maths on its own.
 *
 * Kept apart from [G2CornerShape] because it is the part worth testing: the
 * shape above can only push points into an Android `Path`, which a JVM unit test
 * cannot inspect, while this is plain arithmetic on [Offset]s.
 */
internal object G2Curve {

    /** `n` in `|x|ⁿ + |y|ⁿ = rⁿ`. 4 is the classic squircle. */
    const val EXPONENT = 4f

    /** Enough samples that the chord error at a 40dp corner stays under 0.05px. */
    const val SEGMENTS = 24

    /**
     * The points of one corner, walking from the *incoming* tangent point to the
     * *outgoing* one.
     *
     * [incoming] and [outgoing] are unit vectors pointing away from [corner]
     * along the two edges, so the first point returned is
     * `corner + incoming · radius` and the last is `corner + outgoing · radius`.
     * Both ends therefore land exactly where a circular corner would meet the
     * edges, which is what keeps the two fits interchangeable.
     */
    fun corner(
        corner: Offset,
        incoming: Offset,
        outgoing: Offset,
        radius: Float,
        steps: Int = SEGMENTS,
    ): List<Offset> {
        if (radius <= 0f || steps <= 0) return emptyList()
        val exponent = 2f / EXPONENT
        return (steps downTo 0).map { index ->
            // φ = π/2 is the incoming tangent, φ = 0 the outgoing one.
            val phi = index * (PI / 2.0) / steps
            val alongIncoming = radius * (1f - cos(phi).toFloat().pow(exponent))
            val alongOutgoing = radius * (1f - sin(phi).toFloat().pow(exponent))
            Offset(
                x = corner.x + incoming.x * alongIncoming + outgoing.x * alongOutgoing,
                y = corner.y + incoming.y * alongIncoming + outgoing.y * alongOutgoing,
            )
        }
    }

    /**
     * How far the curve sits from the corner point at 45°.
     *
     * This is the number that separates a G2 corner from a quarter circle: a
     * circle of radius `r` passes `0.414 r` from the corner on the diagonal, the
     * superellipse only `0.225 r`.
     */
    fun diagonalDistance(radius: Float): Float {
        // With two steps the sweep is sampled at the incoming tangent, the
        // diagonal (φ = π/4) and the outgoing one, so index 1 is the point on the
        // diagonal.
        val point = corner(
            corner = Offset.Zero,
            incoming = Offset(0f, 1f),
            outgoing = Offset(1f, 0f),
            radius = radius,
            steps = 2,
        )[1]
        return hypot(point.x, point.y)
    }
}
