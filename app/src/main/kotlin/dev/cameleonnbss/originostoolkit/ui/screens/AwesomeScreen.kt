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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.core.model.AwesomeEntry
import dev.cameleonnbss.originostoolkit.ui.ToolkitUiState
import dev.cameleonnbss.originostoolkit.ui.ToolkitViewModel
import dev.cameleonnbss.originostoolkit.ui.components.Badge
// ToolkitViewModel is kept in the signature so the screen can grow actions later.
import dev.cameleonnbss.originostoolkit.ui.components.EmptyHint
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard

/**
 * The curated index, shipped inside the app.
 *
 * This is the "awesome OriginOS" list: every entry says up front what it needs
 * (nothing, ADB, Shizuku, or root) so nobody installs a rooted tool by mistake.
 * Tapping an entry opens the upstream repository in a browser.
 */
@Composable
fun AwesomeScreen(
    state: ToolkitUiState,
    viewModel: ToolkitViewModel,
    onOpenUrl: (String) -> Unit,
) {
    // Local to this screen: a browsing filter, not a stored preference.
    var noRootOnly by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SectionCard(
            title = "Awesome OriginOS",
            subtitle = "Community-curated no-root tooling. Links go straight to the upstream project.",
        ) {
            Text(
                "Nothing in this list is bundled or modified by this app. Access requirements " +
                    "are shown as badges: NO-ROOT, ADB, SHIZUKU or ROOT.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("No-root entries only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Hides the tools that need root, Magisk or LSPosed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = noRootOnly, onCheckedChange = { noRootOnly = it })
            }
        }

        if (state.catalog.awesome.isEmpty()) {
            EmptyHint("The curated list is missing from the catalog assets.")
            return@Column
        }

        state.catalog.awesome.forEach { section ->
            val entries = section.entries.filter { !noRootOnly || it.access != "root" }
            if (entries.isEmpty()) return@forEach

            SectionCard(title = section.name, subtitle = section.blurb.ifBlank { null }) {
                entries.forEach { entry ->
                    EntryRow(entry, onOpenUrl)
                    Spacer(Modifier.height(10.dp))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun EntryRow(entry: AwesomeEntry, onOpenUrl: (String) -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(entry.url) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Badge(entry.access.uppercase(), accessColor(entry.access))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                entry.repo.ifBlank { entry.url.removePrefix("https://github.com/") },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun accessColor(access: String): Color = when (access) {
    "no-root" -> Color(0xFF4CAF7D)
    "adb" -> Color(0xFF4CC2FF)
    "shizuku" -> Color(0xFF9C7BFF)
    "root" -> Color(0xFFE05C5C)
    else -> Color(0xFF8A8F98)
}
