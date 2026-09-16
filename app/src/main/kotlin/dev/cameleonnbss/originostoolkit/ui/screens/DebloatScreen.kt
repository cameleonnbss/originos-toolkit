package dev.cameleonnbss.originostoolkit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.Badge
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.PillRow
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard

/**
 * Disable OEM and Google packages, reversibly.
 *
 * Uses `pm disable-user --user 0` — never `pm uninstall`. The APK stays on the
 * read-only partition, the data stays put, and re-enabling restores everything.
 * That is the whole reason a no-root debloat cannot brick a phone.
 */
@Composable
fun DebloatScreen(state: ToolkitUiState, viewModel: ToolkitViewModel) {
    var mode by remember { mutableStateOf("catalog") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (!state.packagesLoaded) viewModel.loadPackages()
    }

    val catalogTargets = remember(state.catalog) {
        state.catalog.byCategory("debloat")
            .flatMap { tweak -> tweak.actions.filter { it.op == "pm_disable" }.mapNotNull { it.packageName } }
            .distinct()
    }

    val packages = when (mode) {
        "catalog" -> catalogTargets
        "disabled" -> state.disabled.toList().sorted()
        else -> state.installed.toList().sorted()
    }.filter { query.isBlank() || it.contains(query, ignoreCase = true) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = "Package control",
            subtitle = "Disabled packages stay installed and keep their data. Re-enable any of them at any time.",
        ) {
            Text(
                if (!state.canWrite) {
                    "Shizuku is not running, so the package list is read-only right now."
                } else {
                    "${state.installed.size} installed · ${state.disabled.size} disabled"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.loadPackages() }) { Text("Refresh lists") }
                OutlinedButton(onClick = { viewModel.previewIds(listOf("debloat-vivo-store-browser")) }) {
                    Text("Preview a bundle")
                }
            }
        }

        PillRow(
            options = listOf(
                "catalog" to "Catalog targets (${catalogTargets.size})",
                "installed" to "All installed",
                "disabled" to "Disabled (${state.disabled.size})",
            ),
            selected = mode,
            onSelect = { mode = it ?: "catalog" },
            allLabel = "Catalog targets",
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Filter packages") },
        )

        // Bundled debloat tweaks, one card each: safer than picking packages by hand.
        if (mode == "catalog") {
            state.catalog.byCategory("debloat").forEach { tweak ->
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            tweak.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tweak.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.applyTweak(tweak) }) { Text("Disable") }
                            if (state.isApplied(tweak.id)) {
                                OutlinedButton(onClick = { viewModel.revertTweak(tweak.id) }) {
                                    Text("Restore")
                                }
                            } else {
                                OutlinedButton(onClick = { viewModel.previewTweak(tweak) }) {
                                    Text("Commands")
                                }
                            }
                        }
                    }
                }
            }
        }

        Text(
            "${packages.size} package(s)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (packages.isEmpty()) {
            EmptyHint(
                if (!state.packagesLoaded) {
                    "No package data yet. Start Shizuku, then refresh."
                } else {
                    "Nothing here. Try another filter."
                },
            )
        }

        packages.take(200).forEach { packageName ->
            PackageRow(
                packageName = packageName,
                installed = state.installed.isEmpty() || packageName in state.installed,
                disabled = packageName in state.disabled,
                canWrite = state.canWrite,
                onToggle = { enable -> viewModel.setPackageEnabled(packageName, enable) },
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PackageRow(
    packageName: String,
    installed: Boolean,
    disabled: Boolean,
    canWrite: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(enabled = canWrite && installed) { onToggle(disabled) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    when {
                        !installed -> "not on this device"
                        disabled -> "disabled — tap to restore"
                        else -> "enabled — tap to disable"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Badge(
                text = when {
                    !installed -> "ABSENT"
                    disabled -> "DISABLED"
                    else -> "ENABLED"
                },
                color = if (disabled) ColorDisabled else ColorEnabled,
            )
        }
    }
}

private val ColorEnabled = androidx.compose.ui.graphics.Color(0xFF4CAF7D)
private val ColorDisabled = androidx.compose.ui.graphics.Color(0xFF8A8F98)
