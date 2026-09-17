package dev.cameleonnbss.originostoolkit.ui.screens

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.core.model.Tweak
import dev.cameleonnbss.originostoolkit.core.ops.Access
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.PillRow
import dev.cameleonnbss.originostoolkit.ui.components.TweakBadges
import dev.cameleonnbss.originostoolkit.ui.theme.stateColor

@Composable
fun TweaksScreen(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    var category by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var noShizukuOnly by remember { mutableStateOf(false) }

    val categories = state.catalog.categories.filter {
        it.id != ToolkitUiState.EXPERIMENTAL_CATEGORY || state.experimentalEnabled
    }
    val tweaks = state.visibleTweaks(category)
        .filter { !noShizukuOnly || Access.runsWithoutShizuku(it) }
        .filter {
            query.isBlank() ||
                it.name.contains(query, ignoreCase = true) ||
                it.id.contains(query, ignoreCase = true) ||
                it.summary.contains(query, ignoreCase = true)
        }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.catalog.tweaks.isEmpty()) {
            EmptyHint(
                state.catalogError?.let { "The catalog could not be read: $it" }
                    ?: "The catalog is empty.",
            )
            return@Column
        }

        PillRow(
            options = categories.map { it.id to it.name },
            selected = category,
            onSelect = { category = it },
            allLabel = "All (${state.catalog.tweaks.size})",
        )

        state.catalog.categories.firstOrNull { it.id == category }?.let { selected ->
            Text(
                selected.blurb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        QueryField(query, { query = it })

        NoShizukuToggle(
            checked = noShizukuOnly,
            onChange = { noShizukuOnly = it },
            count = state.catalog.tweaks.count { Access.runsWithoutShizuku(it) },
        )

        Text(
            "${tweaks.size} tweak(s)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        tweaks.forEach { tweak ->
            TweakCard(tweak, state, viewModel)
        }

        if (tweaks.isEmpty()) {
            EmptyHint("Nothing matches. Clear the search or pick another category.")
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Filters the list down to what works with no Shizuku and no root.
 *
 * These tweaks only touch the `system` namespace, so one special-access toggle
 * is the entire setup. The filter exists because "do I have to install Shizuku
 * for this?" is the first question anyone asks.
 */
@Composable
private fun NoShizukuToggle(checked: Boolean, onChange: (Boolean) -> Unit, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Works without Shizuku", style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count tweak(s) only need the \"modify system settings\" grant.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun QueryField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextFieldRow(query, onQueryChange)
}

@Composable
private fun OutlinedTextFieldRow(query: String, onQueryChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text("Search tweaks") },
    )
}

@Composable
private fun TweakCard(tweak: Tweak, state: ToolkitUiState, viewModel: ToolkitViewModel) {
    var expanded by remember(tweak.id) { mutableStateOf(false) }
    val applied = state.isApplied(tweak.id)
    val hint = state.accessHint(tweak)
    val runnable = hint == null

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (applied) "●" else "○",
                    color = stateColor(applied),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    tweak.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(6.dp))
            TweakBadges(tweak)

            Spacer(Modifier.height(8.dp))
            Text(
                tweak.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(tweak.details, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "OriginOS: ${tweak.originOs.joinToString(", ").ifEmpty { "any" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (applied) {
                    Button(onClick = { viewModel.revertTweak(tweak.id) }, enabled = runnable) {
                        Text("Revert")
                    }
                    OutlinedButton(onClick = { viewModel.applyTweak(tweak, force = true) }) {
                        Text("Re-apply")
                    }
                } else {
                    Button(onClick = { viewModel.applyTweak(tweak) }, enabled = runnable) {
                        Text("Apply")
                    }
                    // Previewing stays enabled: the commands are worth seeing
                    // even when this build cannot run them yet.
                    OutlinedButton(onClick = { viewModel.previewTweak(tweak) }) { Text("Commands") }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Less" else "Details")
                }
            }

            // Says what this specific tweak is missing, not "Shizuku is off".
            hint?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
