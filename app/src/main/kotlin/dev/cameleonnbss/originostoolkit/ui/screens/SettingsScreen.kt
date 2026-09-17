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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.BuildConfig
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.AppLanguage
import dev.cameleonnbss.originostoolkit.core.Prefs
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.ConfirmDialog
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.KeyValueRow
import dev.cameleonnbss.originostoolkit.ui.components.PillRow
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard

@Composable
fun SettingsScreen(
    state: ToolkitUiState,
    viewModel: ToolkitViewModel,
    prefs: Prefs,
    onOpenUrl: (String) -> Unit,
    onRequestOverlayAccess: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    permissions: PermissionsState,
) {
    var confirmPanic by remember { mutableStateOf(false) }
    var confirmRevertAll by remember { mutableStateOf(false) }

    // The chosen language is not part of the UI state, because a change restarts
    // the activity: holding it in the view model would only ever be read once.
    val context = LocalContext.current
    var language by remember { mutableStateOf(prefs.languageTag) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(
            title = "Special access",
            subtitle = "Two grants, both revocable from the system settings screen.",
        ) {
            PermissionRow(
                label = "Display over other apps",
                granted = permissions.overlay,
                onGrant = onRequestOverlayAccess,
            )
            Spacer(Modifier.height(10.dp))
            PermissionRow(
                label = "Usage access (which app is in front)",
                granted = permissions.usage,
                onGrant = onRequestUsageAccess,
            )
        }

        SectionCard(
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_summary),
        ) {
            PillRow(
                options = AppLanguage.CHOICES,
                selected = language.ifBlank { null },
                onSelect = { tag ->
                    val next = tag ?: AppLanguage.SYSTEM
                    if (next != language) {
                        language = next
                        AppLanguage.apply(context, prefs, next)
                    }
                },
                allLabel = stringResource(R.string.settings_language_system),
            )
        }

        SectionCard(title = "How the no-root part works") {
            Text(
                "This app never requests root. Every privileged action is a command sent " +
                    "through Shizuku, which runs it as the shell user — the same identity as " +
                    "`adb shell`. Anything applied can be undone, and uninstalling the app " +
                    "leaves the system exactly as it found it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onOpenUrl("https://shizuku.rikka.app/guide/setup/") }) {
                    Text("Setup guide")
                }
                OutlinedButton(onClick = { viewModel.sampleSurfaceFlinger() }) {
                    Text("Run a diagnostic")
                }
            }
        }

        SectionCard(title = "Catalog") {
            KeyValueRow("Tweaks", state.catalog.tweaks.size.toString())
            KeyValueRow("Profiles", state.catalog.profiles.size.toString())
            KeyValueRow("Curated entries", state.catalog.awesome.sumOf { it.entries.size }.toString())
            KeyValueRow("Catalog version", state.catalog.version.toString())
            KeyValueRow("Updated", state.catalog.updatedAt.ifBlank { "—" })
            state.catalogError?.let {
                KeyValueRow("Error", it)
            }
        }

        SectionCard(
            title = "Experimental tweaks",
            subtitle = "Real commands whose outcome depends on your OriginOS build.",
        ) {
            SwitchRow(
                label = "Unlock the experimental category",
                description = "They run the same probe-then-journal dance as everything else, " +
                    "but they are not verified on every build.",
                checked = state.experimentalEnabled,
                onChange = { viewModel.setExperimental(it) },
            )
        }

        SectionCard(
            title = "Revert journal",
            subtitle = "The exact inverse of every change made from this app.",
        ) {
            if (state.journal.isEmpty()) {
                EmptyHint(
                    "Empty. Applying a tweak records its previous value here *before* anything " +
                        "is written, so a revert is always exact.",
                )
            } else {
                state.journal.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${entry.tweakId} · ${entry.appliedAt} · ${entry.inverse.size} inverse op(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { viewModel.revertTweak(entry.tweakId) }) { Text("Revert") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { confirmRevertAll = true }) { Text("Revert everything") }
            }
        }

        SectionCard(
            title = "Panic reset",
            subtitle = "For the one bad case: a density that makes the UI hard to read.",
        ) {
            Text(
                "Restores factory display density and window size, then replays the whole " +
                    "journal in reverse. Safe to run twice.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = { confirmPanic = true }) { Text("Run panic reset") }
        }

        SectionCard(title = "About") {
            KeyValueRow("Version", BuildConfig.VERSION_NAME)
            KeyValueRow("Package", BuildConfig.APPLICATION_ID)
            KeyValueRow("Network permission", "none")
            KeyValueRow("Telemetry", "none")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onOpenUrl("https://github.com/cameleonnbss/originos-toolkit") },
                ) { Text("Source code") }
                OutlinedButton(
                    onClick = {
                        onOpenUrl("https://github.com/cameleonnbss/originos-toolkit/blob/main/LICENSE")
                    },
                ) { Text("MIT licence") }
            }
        }

        Spacer(Modifier.height(8.dp))
    }

    if (confirmPanic) {
        ConfirmDialog(
            title = "Panic reset?",
            body = "Display metrics go back to factory values and every journaled tweak is " +
                "reverted. Nothing else on the device is touched.",
            confirmLabel = "Reset",
            onConfirm = {
                viewModel.panicReset()
                confirmPanic = false
            },
            onDismiss = { confirmPanic = false },
        )
    }

    if (confirmRevertAll) {
        ConfirmDialog(
            title = "Revert everything?",
            body = "Each recorded tweak is undone with the exact values captured before it was " +
                "applied.",
            confirmLabel = "Revert all",
            onConfirm = {
                viewModel.revertAll()
                confirmRevertAll = false
            },
            onDismiss = { confirmRevertAll = false },
        )
    }
}

/** Whether the two special permissions are currently granted. */
data class PermissionsState(val overlay: Boolean, val usage: Boolean)

@Composable
private fun PermissionRow(label: String, granted: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (granted) "granted" else "not granted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!granted) {
            OutlinedButton(onClick = onGrant) { Text("Grant") }
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
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
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
