package dev.cameleonnbss.originostoolkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.core.model.Risk

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------
//
// The app's colours are the OriginOS 6 ring, taken literally. The mark sweeps
// through six hues, so the theme is built around that sweep rather than around
// one hue with tints: `BrandSweep` is the ring itself, and the two ends of it —
// coral and violet — are what the background aura and the hero surfaces use.
//
// Controls stay a single flat blue. That is not an oversight: OriginOS system
// screens are near-monochrome cards plus exactly one accent (the switches in
// *SIM settings* are one blue, nothing else competes), and the gradient is
// reserved for identity — the wordmark, the progress bar, the risk ramp.

/** The ring, sampled in the same order the launcher icon sweeps it. */
val BrandSweep: List<Color> = listOf(
    Color(0xFFFF8F35), // amber
    Color(0xFFFF5E4A), // coral
    Color(0xFFEF4E86), // rose
    Color(0xFFA94ED6), // violet
    Color(0xFF4E63E6), // indigo
    Color(0xFF3E9EE6), // azure
)

/** Warm end of the sweep — the top-right glow of the aura and the app's "hot" state. */
val BrandCoral = Color(0xFFFF5E4A)

/** Cool end of the sweep — the bottom-left glow of the aura. */
val BrandViolet = Color(0xFFA94ED6)

/** The single accent used for interactive controls, light theme. */
private val AccentLight = Color(0xFF1A6DFF)

/** The same accent lifted for dark surfaces, where the flat blue is too heavy. */
private val AccentDark = Color(0xFF5B9DFF)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00214D),
    primaryContainer = Color(0xFF0F3D78),
    onPrimaryContainer = Color(0xFFD3E3FF),
    inversePrimary = AccentLight,

    secondary = Color(0xFFB9A8FF),
    onSecondary = Color(0xFF2A1B5E),
    secondaryContainer = Color(0xFF3B2B78),
    onSecondaryContainer = Color(0xFFE6DEFF),

    tertiary = Color(0xFFFF8A78),
    onTertiary = Color(0xFF4E1006),
    tertiaryContainer = Color(0xFF6E2314),
    onTertiaryContainer = Color(0xFFFFDBD3),

    // Near-black, the way OriginOS renders a dark system app: the cards carry
    // the structure, so the page itself can go almost to zero.
    background = Color(0xFF07090C),
    onBackground = Color(0xFFE8ECF1),
    surface = Color(0xFF11151A),
    onSurface = Color(0xFFE8ECF1),
    surfaceVariant = Color(0xFF1E242C),
    onSurfaceVariant = Color(0xFFAEB8C4),

    // Translucent card fills, so the aura behind the page bleeds through the
    // panels instead of being covered by them.
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = Color(0xE60D1116),
    surfaceContainer = Color(0xE6141922),
    surfaceContainerHigh = Color(0xE61B2129),
    surfaceContainerHighest = Color(0xFF232A33),
    surfaceBright = Color(0xFF2B333D),
    surfaceDim = Color(0xFF07090C),

    outline = Color(0xFF3C444F),
    outlineVariant = Color(0xFF262D36),
    inverseSurface = Color(0xFFE8ECF1),
    inverseOnSurface = Color(0xFF11151A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF00306B),
    inversePrimary = AccentDark,

    secondary = Color(0xFF6B58DE),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E2FF),
    onSecondaryContainer = Color(0xFF21105C),

    tertiary = Color(0xFFD93F2B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDDD6),
    onTertiaryContainer = Color(0xFF5C1508),

    // The pale grey OriginOS uses behind white grouped cards, rather than pure
    // white — which is what makes the grouped-card layout read as a system app.
    background = Color(0xFFF1F3F6),
    onBackground = Color(0xFF101418),
    surface = Color(0xFFFCFDFE),
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE9EEF4),
    onSurfaceVariant = Color(0xFF59616E),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xF2F7F9FC),
    surfaceContainer = Color(0xE6FFFFFF),
    surfaceContainerHigh = Color(0xF2FFFFFF),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE4E8EE),

    outline = Color(0xFFBFC7D2),
    outlineVariant = Color(0xFFDCE3EB),
    inverseSurface = Color(0xFF2B3138),
    inverseOnSurface = Color(0xFFF1F3F6),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color(0xFF000000),
)

/**
 * The shapes every surface is cut with: G2 corners, and rounder than stock
 * Material. `medium` is what every `Card` picks up, so the values here are what
 * make the whole app look like one system.
 *
 * The numbers are pinned to what the official OriginOS 6 plates measure — a
 * panel's corner spans roughly a tenth to an eighth of its own width
 * (`docs/ORIGINOS-LOOK.md`) — and then nudged up, because a G2 corner reads
 * tighter than a circular one of the same radius: at 45° it is `0.225 r` from
 * the corner point against the circle's `0.414 r`.
 */
private val OriginShapes = Shapes(
    extraSmall = G2CornerShape(12.dp),
    small = G2CornerShape(16.dp),
    medium = G2CornerShape(24.dp),
    large = G2CornerShape(30.dp),
    extraLarge = G2CornerShape(38.dp),
)

@Composable
fun ToolkitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        shapes = OriginShapes,
        content = content,
    )
}

/** True when the active scheme is the dark one. */
@Composable
private fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

/**
 * The OriginOS aura: two large, very soft radial glows — warm top-right, cool
 * bottom-left — over the page background. It is the flat, printable stand-in
 * for an OriginOS wallpaper, and it is deliberately faint: it should read as
 * lighting on the panels, never as a colour of its own.
 *
 * Wrap the whole navigation surface in this and let scaffolds draw transparent.
 */
@Composable
fun AuraBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isDarkScheme()
    val warm = BrandCoral.copy(alpha = if (dark) 0.20f else 0.13f)
    val cool = BrandViolet.copy(alpha = if (dark) 0.18f else 0.10f)

    Box(
        modifier
            .fillMaxSize()
            .drawWithCache {
                val warmBrush = Brush.radialGradient(
                    colors = listOf(warm, Color.Transparent),
                    center = Offset(size.width * 0.94f, size.height * 0.02f),
                    radius = size.maxDimension * 0.85f,
                )
                val coolBrush = Brush.radialGradient(
                    colors = listOf(cool, Color.Transparent),
                    center = Offset(size.width * 0.04f, size.height * 0.94f),
                    radius = size.maxDimension * 0.80f,
                )
                onDrawBehind {
                    drawRect(warmBrush)
                    drawRect(coolBrush)
                }
            },
        content = content,
    )
}

/**
 * The brand sweep as a brush.
 *
 * Horizontal only, because that is the single place it is used — the underline
 * of the wordmark. Anything vertical can be built from [BrandSweep] directly,
 * which is public for exactly that reason.
 */
fun brandBrush(): Brush = Brush.horizontalGradient(BrandSweep)

// ---------------------------------------------------------------------------
// Semantic colours
// ---------------------------------------------------------------------------

/** Traffic-light colour for a catalog risk level, tuned for the OriginOS greys. */
@Composable
fun riskColor(risk: Risk): Color = when (risk) {
    Risk.LOW -> Color(0xFF2FA36B)
    Risk.MEDIUM -> Color(0xFFD98E1F)
    Risk.HIGH -> Color(0xFFD9423A)
}

/** Colour for the "applied / not applied / unknown" marker. */
@Composable
fun stateColor(applied: Boolean?): Color = when (applied) {
    true -> Color(0xFF2FA36B)
    false -> MaterialTheme.colorScheme.outline
    null -> Color(0xFFD98E1F)
}
