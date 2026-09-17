package dev.cameleonnbss.originostoolkit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.TweakResult
import dev.cameleonnbss.originostoolkit.core.model.Risk
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.Access
import dev.cameleonnbss.originostoolkit.ui.PreviewState
import dev.cameleonnbss.originostoolkit.ui.theme.brandBrush
import dev.cameleonnbss.originostoolkit.ui.theme.riskColor

/**
 * A titled card. Every screen is built from these so the app looks coherent.
 *
 * Styled as an OriginOS panel: the rounded shape comes from
 * `MaterialTheme.shapes.medium`, the fill is translucent so the aura shows
 * through, and the edge is a hairline rather than a shadow — OriginOS surfaces
 * are flat and outlined, and a drop shadow reads as stock Material instead.
 */
@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, mono: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Risk + verification + access badges for one tweak.
 *
 * The access badge is computed from the actions rather than trusted from the
 * catalog's `requires` field, so a wrong declaration can never make the app
 * promise something the engine will then refuse.
 */
@Composable
fun TweakBadges(tweak: Tweak) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Badge(tweak.risk.id.uppercase(), riskColor(tweak.risk))
        if (!tweak.verified) Badge("UNVERIFIED", Color(0xFF8A8F98))
        if (tweak.isOneShot) Badge("ONE-SHOT", Color(0xFF8A8F98))
        if (!tweak.reversible) Badge("NO UNDO", Color(0xFF8A8F98))
        if (Access.runsWithoutShizuku(tweak)) {
            Badge("NO SHIZUKU", Color(0xFF2E9E5B))
        } else {
            Badge("SHELL", MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun Badge(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Horizontally scrolling row of single-choice pills. */
@Composable
fun PillRow(
    options: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String?) -> Unit,
    allLabel: String = "All",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = { onSelect(null) }, enabled = selected != null) {
            Text(allLabel)
        }
        options.forEach { (id, label) ->
            OutlinedButton(onClick = { onSelect(id) }, enabled = selected != id) {
                Text(label)
            }
        }
    }
}

@Composable
fun BusyIndicator(busy: Boolean) {
    if (busy) {
        // The bar is the one place the brand sweep appears during normal use,
        // so progress reads as "the toolkit is working" rather than as a
        // generic Material indeterminate bar.
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    }
}

/**
 * The app's own lockup: the ring mark, the wordmark, the tagline, and the
 * brand sweep as an underline.
 *
 * This is the in-app twin of the icon mock-up — the same mark and the same two
 * lines of type — rendered where it can actually be read, since a launcher icon
 * is cropped to a 66dp circle and loses anything written at the bottom.
 */
@Composable
fun BrandLockup(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_logo_ring),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(46.dp),
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(14.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(brandBrush(), RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * The dashboard hero: the app lockup on an elevated panel.
 *
 * Elevated rather than flat on purpose — this is the one panel on the page that
 * is about the app itself, so it is allowed to sit above the rest.
 */
@Composable
fun HeroCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        BrandLockup(Modifier.padding(18.dp))
    }
}

/** The brand mark on its own, for toolbars and empty states. */
@Composable
fun BrandMark(size: Dp = 30.dp, modifier: Modifier = Modifier) {
    Icon(
        painter = painterResource(R.drawable.ic_logo_ring),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = modifier.size(size),
    )
}

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

/** Shows the exact commands a tweak would run, before anything happens. */
@Composable
fun PreviewDialog(preview: PreviewState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commands for ${preview.title}") },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                preview.commands.forEach { command ->
                    Text(
                        command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
fun MessageDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OriginOS Toolkit") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
fun CommandOutputDialog(output: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Raw output") },
        text = {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    output.take(4000),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Generic two-action confirmation used before anything destructive. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What just happened: every command, its output, and anything that failed. */
@Composable
fun ResultDialog(result: TweakResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.reverted) "Reverted: ${result.name}" else result.name) },
        text = {
            Column {
                result.skipped.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                result.steps.forEach { step ->
                    Text(
                        (if (step.ok) "✓ " else "✗ ") + step.command,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (step.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                    if (step.output.isNotBlank() && step.output != "(dry run)") {
                        Text(
                            "    ${step.output.take(400)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                result.errors.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}


