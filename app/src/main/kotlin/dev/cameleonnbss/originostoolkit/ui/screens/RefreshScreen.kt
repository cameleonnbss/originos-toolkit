package dev.cameleonnbss.originostoolkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.core.AppEntry
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.KeyValueRow
import dev.cameleonnbss.originostoolkit.ui.components.PillRow
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard

/**
 * The flagship screen: pick a refresh rate per app.
 *
 * OriginOS decides for you which apps are allowed to run above 60/90 Hz. This
 * lets you decide instead, and keeps the battery honest by restoring the
 * original values the moment you leave the app.
 */
@Composable
fun RefreshScreen(
    state: ToolkitUiState,
    viewModel: ToolkitViewModel,
    onRequestUsageAccess: () -> Unit,
) {
    var editing by remember { mutableStateOf<AppEntry?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (state.apps.isEmpty()) viewModel.loadApps()
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(title = "Panel") {
            KeyValueRow("Refresh now", state.currentRefreshRate?.let { "%.0f Hz".format(it) } ?: "—")
            KeyValueRow(
                "Supported",
                state.supportedRates.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") { "%.0f".format(it) } ?: "—",
            )
            KeyValueRow("Peak setting", state.snapshot?.peakRefreshRate ?: "system default")
            KeyValueRow("Minimum setting", state.snapshot?.minRefreshRate ?: "system default")
        }

        SectionCard(
            title = "Watcher",
            subtitle = "Pins the refresh rate of the app in the foreground, and puts it back when you leave.",
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Per-app refresh rate", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when {
                            state.watcherEnabled -> "Running"
                            !state.canWrite -> "Needs Shizuku"
                            else -> "Stopped"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.watcherEnabled,
                    onCheckedChange = { if (!viewModel.toggleWatcher()) onRequestUsageAccess() },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Default rate for apps without their own entry",
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(8.dp))
            val options = buildList {
                add("0" to "System default")
                state.supportedRates.forEach { add(it.toInt().toString() to "${it.toInt()} Hz") }
                if (state.supportedRates.none { it >= 144f }) add("144" to "144 Hz")
            }
            PillRow(
                options = options,
                selected = state.defaultRefreshRate.toString(),
                onSelect = { value -> viewModel.setDefaultRefreshRate(value?.toIntOrNull() ?: 0) },
                allLabel = "System default",
            )
        }

        SectionCard(
            title = "Per-app overrides",
            subtitle = "${state.perAppRates.size} app(s) pinned. Tap an app to change or remove its rate.",
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search installed apps") },
            )
            Spacer(Modifier.height(10.dp))

            if (state.apps.isEmpty()) {
                EmptyHint("No app list yet. Tap refresh to read the launcher apps.")
                Button(onClick = { viewModel.loadApps() }) { Text("Load apps") }
            } else {
                val filtered = state.apps.filter {
                    query.isBlank() ||
                        it.label.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
                }
                if (filtered.isEmpty()) {
                    EmptyHint("Nothing matches \"$query\".")
                }
                filtered.take(120).forEach { app ->
                    AppRow(
                        app = app,
                        rate = state.perAppRates[app.packageName],
                        onClick = { editing = app },
                    )
                }
                if (state.perAppRates.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { viewModel.clearPerAppRates() }) {
                        Text("Clear every override")
                    }
                }
            }
        }
    }

    editing?.let { app ->
        RatePickerDialog(
            app = app,
            rates = state.supportedRates.map { it.toInt() }.ifEmpty { listOf(60, 90, 120, 144) },
            current = state.perAppRates[app.packageName],
            onPick = { rate ->
                viewModel.setPerAppRate(app.packageName, rate)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun AppRow(app: AppEntry, rate: Int?, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.displayLabel(), style = MaterialTheme.typography.bodyLarge)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                rate?.let { "$it Hz" } ?: "default",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (rate != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun RatePickerDialog(
    app: AppEntry,
    rates: List<Int>,
    current: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(app.displayLabel()) },
        text = {
            val options: List<Pair<Int?, String>> =
                listOf(null to "System default") + rates.distinct().sorted().map { it to "$it Hz" }
            Column {
                options.forEach { option ->
                    val selected = option.first == current
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option.first) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (selected) "● " else "○ ") + option.second,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
