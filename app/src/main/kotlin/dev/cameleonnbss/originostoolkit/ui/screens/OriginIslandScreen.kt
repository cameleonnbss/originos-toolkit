package dev.cameleonnbss.originostoolkit.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.OriginIsland
import dev.cameleonnbss.originostoolkit.core.Prefs
import dev.cameleonnbss.originostoolkit.service.OriginIslandSender
import dev.cameleonnbss.originostoolkit.ui.components.Badge
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Island tab, OriginOS-style: two quiet cards. The first decides *what*
 * rides the island — an app picker with per-app templates behind a single
 * switch. The second is the payload playground, reduced to title, content and
 * template row; the verbose protocol prose lives in docs/ORIGINISLAND.md and
 * off the screen.
 */
@Composable
fun OriginIslandScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    // -- recast state ---------------------------------------------------------
    var recastOn by remember { mutableStateOf(prefs.islandRecastEnabled) }
    var picked by remember { mutableStateOf(prefs.islandApps()) }
    var templateFor by remember { mutableStateOf(prefs.islandAppTemplates()) }
    var templateTarget by remember { mutableStateOf<String?>(null) }
    var listenerGranted by remember { mutableStateOf(listenerEnabled(context)) }

    // -- playground state -----------------------------------------------------
    var title by remember { mutableStateOf("OriginOS Toolkit") }
    var content by remember { mutableStateOf("Hello from the island") }
    var template by remember { mutableStateOf(OriginIsland.OriginIslandTemplate.CAPSULE) }
    var progress by remember { mutableFloatStateOf(60f) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecastCard(
            recastOn = recastOn,
            onToggle = { on ->
                recastOn = on
                prefs.islandRecastEnabled = on
            },
            listenerGranted = listenerGranted,
            onGrantListener = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            picked = picked,
            onPick = { pkg ->
                picked = (if (pkg in picked) picked - pkg else picked + pkg).also {
                    prefs.setIslandApps(it)
                }
            },
            onTemplateRequest = { pkg -> templateTarget = pkg },
            installedApps = remember { loadApps(context) },
            iconFor = { pkg -> appIcon(context, pkg) },
            labelFor = { pkg -> OriginIslandSender.appLabel(context, pkg) },
        )

        if (templateTarget != null) {
            TemplateSheet(
                current = templateFor[templateTarget] ?: OriginIsland.OriginIslandTemplate.CAPSULE.id,
                onPick = { id ->
                    templateTarget?.let { pkg ->
                        templateFor = templateFor + (pkg to id)
                        prefs.setIslandTemplate(pkg, id)
                    }
                    templateTarget = null
                },
                onDismiss = { templateTarget = null },
            )
        }

        PlaygroundCard(
            title = title,
            onTitle = { title = it },
            content = content,
            onContent = { content = it },
            template = template,
            onTemplate = { template = it },
            progress = progress,
            onProgress = { progress = it },
            onSend = {
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        OriginIslandSender.post(
                            context,
                            OriginIsland.Request(
                                id = OriginIslandSender.ID_ISLAND,
                                title = title.ifBlank { "OriginOS Toolkit" },
                                content = content,
                                template = template,
                                progress = progress.toInt(),
                                progressMax = 100,
                            ),
                        )
                    }
                    android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onUnmount = {
                scope.launch { withContext(Dispatchers.IO) { OriginIslandSender.cancel(context) } }
            },
        )
    }
}

@Composable
private fun RecastCard(
    recastOn: Boolean,
    onToggle: (Boolean) -> Unit,
    listenerGranted: Boolean,
    onGrantListener: () -> Unit,
    picked: List<String>,
    onPick: (String) -> Unit,
    onTemplateRequest: (String) -> Unit,
    installedApps: List<AppRow>,
    iconFor: (String) -> ImageBitmap?,
    labelFor: (String) -> String,
) {
    SectionCard(title = stringResource(R.string.island_recast_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.island_recast_switch),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = recastOn, onCheckedChange = onToggle)
        }
        if (recastOn && !listenerGranted) {
            TextButton(onClick = onGrantListener) {
                Text(stringResource(R.string.island_grant_listener))
            }
        }
        if (recastOn) {
            Spacer(Modifier.height(8.dp))
            AppPickerChips(
                picked = picked,
                onPick = onPick,
                onTemplateRequest = onTemplateRequest,
                installedApps = installedApps,
                iconFor = iconFor,
                labelFor = labelFor,
            )
        }
    }
}

@Composable
private fun AppPickerChips(
    picked: List<String>,
    onPick: (String) -> Unit,
    onTemplateRequest: (String) -> Unit,
    installedApps: List<AppRow>,
    iconFor: (String) -> ImageBitmap?,
    labelFor: (String) -> String,
) {
    // Picked apps first, then everything else; a single horizontal row keeps
    // the card at OriginOS compactness instead of a scrolling settings list.
    val ordered = picked.mapNotNull { pkg -> installedApps.firstOrNull { it.pkg == pkg } } +
        installedApps.filter { it.pkg !in picked }

    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ordered.forEach { app ->
            val isPicked = app.pkg in picked
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onPick(app.pkg) }
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isPicked) 0.9f else 0.45f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                val icon = iconFor(app.pkg)
                if (icon != null) {
                    Image(icon, contentDescription = null, modifier = Modifier.size(34.dp))
                } else {
                    Box(
                        Modifier
                            .size(34.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    app.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                if (isPicked) {
                    Text(
                        stringResource(R.string.island_tap_template),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/** The per-app template chooser, as a compact dialog. */
@Composable
private fun TemplateSheet(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.island_template_pick_title)) },
        text = {
            Column {
                OriginIsland.OriginIslandTemplate.entries.forEach { candidate ->
                    TextButton(onClick = { onPick(candidate.id) }) {
                        Text(
                            templateLabel(candidate) +
                                if (candidate.id == current) " ✓" else "",
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.island_close)) } },
    )
}

@Composable
private fun PlaygroundCard(
    title: String,
    onTitle: (String) -> Unit,
    content: String,
    onContent: (String) -> Unit,
    template: OriginIsland.OriginIslandTemplate,
    onTemplate: (OriginIsland.OriginIslandTemplate) -> Unit,
    progress: Float,
    onProgress: (Float) -> Unit,
    onSend: () -> Unit,
    onUnmount: () -> Unit,
) {
    SectionCard(title = stringResource(R.string.island_playground_title)) {
        IslandPreview(template, title, content, progress.toInt())

        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = title,
            onValueChange = onTitle,
            label = { Text(stringResource(R.string.island_field_title)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = content,
            onValueChange = onContent,
            label = { Text(stringResource(R.string.island_field_content)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OriginIsland.OriginIslandTemplate.entries.forEach { candidate ->
                androidx.compose.material3.FilterChip(
                    selected = template == candidate,
                    onClick = { onTemplate(candidate) },
                    label = { Text(templateLabel(candidate)) },
                )
            }
        }

        if (template == OriginIsland.OriginIslandTemplate.PROGRESS) {
            Text(
                stringResource(R.string.island_progress_value, progress.toInt()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(value = progress, onValueChange = onProgress, valueRange = 0f..100f)
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.Button(onClick = onSend) {
                Text(stringResource(R.string.island_send))
            }
            TextButton(onClick = onUnmount) {
                Text(stringResource(R.string.island_unmount))
            }
        }
    }
}

@Composable
private fun templateLabel(template: OriginIsland.OriginIslandTemplate): String = when (template) {
    OriginIsland.OriginIslandTemplate.RHYTHM -> stringResource(R.string.island_tpl_rhythm)
    OriginIsland.OriginIslandTemplate.PROGRESS -> stringResource(R.string.island_tpl_progress)
    OriginIsland.OriginIslandTemplate.LOADING -> stringResource(R.string.island_tpl_loading)
    OriginIsland.OriginIslandTemplate.TEXT_ICON -> stringResource(R.string.island_tpl_text_icon)
    OriginIsland.OriginIslandTemplate.ICON_TEXT -> stringResource(R.string.island_tpl_icon_text)
    OriginIsland.OriginIslandTemplate.CAPSULE -> stringResource(R.string.island_tpl_capsule)
}

/** One app in the picker: package plus the label the chip shows. */
private data class AppRow(val pkg: String, val label: String)

private fun loadApps(context: android.content.Context): List<AppRow> {
    val manager = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return manager.queryIntentActivities(intent, 0)
        .map { resolved ->
            AppRow(
                pkg = resolved.activityInfo.packageName,
                label = resolved.loadLabel(manager).toString(),
            )
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
}

private fun appIcon(context: android.content.Context, pkg: String): androidx.compose.ui.graphics.ImageBitmap? =
    runCatching {
        context.packageManager.getApplicationIcon(pkg).toBitmap(64, 64).asImageBitmap()
    }.getOrNull()

private fun listenerEnabled(context: android.content.Context): Boolean =
    ComponentName.unflattenFromString(
        Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: "",
    )?.packageName == context.packageName

/**
 * The island preview: a dark pill with a camera-cutout notch, the fixed left
 * side (icon + title), and the right side drawn per template. Shape only —
 * the real renderer is vivo's.
 */
@Composable
private fun IslandPreview(
    template: OriginIsland.OriginIslandTemplate,
    title: String,
    content: String,
    progress: Int,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF101418), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The camera cutout sits between the two sides, as on the device.
        Box(
            Modifier
                .size(18.dp)
                .background(Color(0xFF05070A), CircleShape),
        )
        Spacer(Modifier.size(10.dp))
        Text(
            title.take(16),
            color = Color(0xFFF2F4F7),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.size(10.dp))
        when (template) {
            OriginIsland.OriginIslandTemplate.RHYTHM -> {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(4) { index ->
                        Box(
                            Modifier
                                .size(width = 4.dp, height = (8 + (index % 3) * 4).dp)
                                .background(Color(0xFF80CBC4), RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }

            OriginIsland.OriginIslandTemplate.PROGRESS -> {
                Text(
                    "$progress%",
                    color = Color(0xFF80CBC4),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OriginIsland.OriginIslandTemplate.LOADING -> {
                Box(
                    Modifier
                        .size(14.dp)
                        .background(Color(0xFF80CBC4), CircleShape),
                )
            }

            OriginIsland.OriginIslandTemplate.TEXT_ICON -> {
                Text(content.take(12), color = Color(0xFFF2F4F7), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Spacer(Modifier.size(6.dp))
                Box(Modifier.size(12.dp).background(Color(0xFF80CBC4), CircleShape))
            }

            OriginIsland.OriginIslandTemplate.ICON_TEXT -> {
                Box(Modifier.size(12.dp).background(Color(0xFF80CBC4), CircleShape))
                Spacer(Modifier.size(6.dp))
                Text(content.take(12), color = Color(0xFFF2F4F7), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }

            OriginIsland.OriginIslandTemplate.CAPSULE -> {
                Text(
                    content.take(18),
                    color = Color(0xFF06231F),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    modifier = Modifier
                        .background(Color(0xFF80CBC4), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}
