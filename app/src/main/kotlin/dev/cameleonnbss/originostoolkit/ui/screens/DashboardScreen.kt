package dev.cameleonnbss.originostoolkit.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.core.model.Profile
import dev.cameleonnbss.originostoolkit.ui.AppEntry
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.KeyValueRow
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard

@Composable
fun DashboardScreen(
    state: ToolkitUiState,
    viewModel: ToolkitViewModel,
    onNavigate: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRequestOverlayAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val snapshot = state.snapshot

        SectionCard(title = "Device") {
            if (snapshot == null) {
                EmptyHint("Reading device properties…")
            } else {
                KeyValueRow("Model", snapshot.model)
                KeyValueRow("Brand", snapshot.brand)
                KeyValueRow("Android", "${snapshot.androidRelease} (API ${snapshot.sdk})")
                KeyValueRow("OriginOS", snapshot.originOsVersion ?: "—")
                snapshot.originOsBuild?.let { KeyValueRow("Build", it) }
                snapshot.funTouchVersion?.let { KeyValueRow("FuntouchOS", it) }
                KeyValueRow("Density", snapshot.density?.toString() ?: "—")
                KeyValueRow("Resolution", snapshot.resolution ?: "—")
                KeyValueRow("Peak refresh", snapshot.peakRefreshRate ?: "system default")
                state.currentRefreshRate?.let { KeyValueRow("Refresh now", "%.0f Hz".format(it)) }

                if (!snapshot.isOriginOs) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "No `ro.vivo.os.version` on this device. The OriginOS-specific notes " +
                            "may not apply, but every generic Android tweak still works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(
            title = "Shizuku",
            subtitle = "The only way this app can change a system setting without root.",
        ) {
            when {
                !state.shizuku.binderAlive -> {
                    Text(
                        "Not running. Start Shizuku (wireless debugging or a one-time ADB " +
                            "connection), then come back here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onOpenUrl("https://shizuku.rikka.app/guide/setup/") }) {
                            Text("Setup guide")
                        }
                        OutlinedButton(onClick = { onOpenUrl("https://github.com/RikkaApps/Shizuku") }) {
                            Text("Install Shizuku")
                        }
                    }
                }

                !state.shizuku.permissionGranted -> {
                    Text(
                        "Shizuku is running but this app is not authorised yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { viewModel.requestShizukuPermission() }) { Text("Grant access") }
                }

                else -> {
                    Text(
                        "Ready — commands run as the shell user (uid 2000), exactly like " +
                            "`adb shell`. Nothing is escalated to root and nothing is permanent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        SectionCard(title = "Live tools") {
            SwitchRow(
                label = "Frame-rate overlay",
                description = "Shows the vsync rate this device is actually running at, above every app.",
                checked = state.overlayEnabled,
                onChange = { if (!viewModel.toggleOverlay()) onRequestOverlayAccess() },
            )
            Spacer(Modifier.height(10.dp))
            SwitchRow(
                label = "Per-app refresh rate",
                description = "Watches the foreground app and pins its refresh rate. Needs usage access.",
                checked = state.watcherEnabled,
                onChange = { if (!viewModel.toggleWatcher()) onRequestUsageAccess() },
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { viewModel.sampleSurfaceFlinger() }) {
                Text("Sample real frame timings")
            }
        }

        SectionCard(
            title = "Profiles",
            subtitle = "Curated bundles. Preview shows every command first; nothing is a high-risk tweak.",
        ) {
            if (state.catalog.profiles.isEmpty()) {
                EmptyHint("No profiles in the catalog.")
            }
            state.catalog.profiles.forEach { profile ->
                ProfileRow(profile, state, viewModel)
                Spacer(Modifier.height(10.dp))
            }
        }

        SectionCard(title = "Discover") {
            Text(
                "A curated index of no-root tools for OriginOS: debloaters, audio, firewalls, " +
                    "the Shizuku ecosystem and the FPS unlockers this toolkit was inspired by.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { onNavigate("awesome") }) { Text("Open the list") }
        }

        SectionCard(title = "Revert journal") {
            if (state.journal.isEmpty()) {
                EmptyHint(
                    "Nothing applied yet. Everything you apply is recorded here so it can be " +
                        "undone exactly — including settings that did not exist before.",
                )
            } else {
                Text(
                    "${state.journal.size} tweak(s) recorded",
                    style = MaterialTheme.typography.bodyMedium,
                )
                state.journal.takeLast(5).forEach { entry ->
                    Text(
                        "• ${entry.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { viewModel.revertAll() }) { Text("Revert everything") }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = { onChange() })
    }
}

@Composable
private fun ProfileRow(profile: Profile, state: ToolkitUiState, viewModel: ToolkitViewModel) {
    val resolved = state.catalog.resolve(listOf(profile.id))
    Column {
        Text(profile.name, style = MaterialTheme.typography.titleSmall)
        Text(
            profile.blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.applyIds(listOf(profile.id)) }) { Text("Apply") }
            OutlinedButton(onClick = { viewModel.previewIds(listOf(profile.id)) }) {
                Text("Preview (${resolved.size})")
            }
        }
    }
}

/** Kept here so the debloat screen and the refresh picker label apps the same way. */
fun AppEntry.displayLabel(): String = if (label.isBlank()) packageName else label
