package dev.cameleonnbss.originostoolkit.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.cameleonnbss.originostoolkit.core.model.Risk

private val Accent = Color(0xFF4CC2FF)
private val AccentDark = Color(0xFF0B6E99)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00212F),
    primaryContainer = Color(0xFF00405C),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB6C4D0),
    background = Color(0xFF10131A),
    onBackground = Color(0xFFE2E6EB),
    surface = Color(0xFF151922),
    onSurface = Color(0xFFE2E6EB),
    surfaceVariant = Color(0xFF232833),
    onSurfaceVariant = Color(0xFFC2C7CF),
    outline = Color(0xFF8C9199),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = AccentDark,
    secondary = Color(0xFF4A6267),
    background = Color(0xFFF6F9FC),
    surface = Color(0xFFFFFFFF),
)

@Composable
fun ToolkitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}

/** Traffic-light colour for a catalog risk level. */
@Composable
fun riskColor(risk: Risk): Color = when (risk) {
    Risk.LOW -> Color(0xFF4CAF7D)
    Risk.MEDIUM -> Color(0xFFE0A63C)
    Risk.HIGH -> Color(0xFFE05C5C)
}

/** Colour for the "applied / not applied / unknown" marker. */
@Composable
fun stateColor(applied: Boolean?): Color = when (applied) {
    true -> Color(0xFF4CAF7D)
    false -> MaterialTheme.colorScheme.outline
    null -> Color(0xFFE0A63C)
}
