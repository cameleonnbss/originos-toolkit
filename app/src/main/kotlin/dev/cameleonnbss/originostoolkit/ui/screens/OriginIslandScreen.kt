package dev.cameleonnbss.originostoolkit.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.cameleonnbss.originostoolkit.R
import dev.cameleonnbss.originostoolkit.core.OriginIsland
import dev.cameleonnbss.originostoolkit.ui.components.Badge
import dev.cameleonnbss.originostoolkit.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The OriginIsland playground — this app's take on what CunnyPlayground does
 * for the HyperIsland: type a payload, pick one of the six right templates the
 * framework resolves, and send it to the island around the camera cutout.
 *
 * The preview is an honest mock: it draws the *shape* of the chosen template
 * with the values you typed, not what vivo's renderer will actually draw —
 * that part is closed-source and differs between OriginOS builds.
 */
@Composable
fun OriginIslandScreen() {
    var title by remember { mutableStateOf("OriginOS Toolkit") }
    var content by remember { mutableStateOf("Hello from the island") }
    var subText by remember { mutableStateOf("") }
    var leftText by remember { mutableStateOf("") }
    var rightText by remember { mutableStateOf("") }
    var template by remember { mutableStateOf(OriginIsland.OriginIslandTemplate.CAPSULE) }
    var progress by remember { mutableFloatStateOf(60f) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val supported = remember { OriginIsland.vivoBrand() }
    val osVersion = remember { OriginIsland.originOsVersion() }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(
            title = stringResource(R.string.originisland_status_title),
            subtitle = stringResource(R.string.originisland_status_subtitle),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Badge(
                    if (supported) "VIVO" else "GENERIC ANDROID",
                    if (supported) Color(0xFF2E9E5B) else Color(0xFF8A8F98),
                )
                Badge(
                    text = "OriginOS: " + (osVersion ?: stringResource(R.string.originisland_os_unknown)),
                    color = if (osVersion != null) MaterialTheme.colorScheme.primary else Color(0xFF8A8F98),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(
                    if (supported) R.string.originisland_status_supported else R.string.originisland_status_unsupported,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = stringResource(R.string.originisland_preview_title)) {
            IslandPreview(template, title, content, progress.toInt())
        }

        SectionCard(title = stringResource(R.string.originisland_payload_title)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.originisland_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = content,
                onValueChange = { content = it },
                label = { Text(stringResource(R.string.originisland_field_content)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = subText,
                onValueChange = { subText = it },
                label = { Text(stringResource(R.string.originisland_field_subtext)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = stringResource(R.string.originisland_template_title)) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OriginIsland.OriginIslandTemplate.entries.forEach { candidate ->
                    OutlinedButton(
                        onClick = { template = candidate },
                        enabled = template != candidate,
                    ) {
                        Text(
                            when (candidate) {
                                OriginIsland.OriginIslandTemplate.RHYTHM -> stringResource(R.string.originisland_tpl_rhythm)
                                OriginIsland.OriginIslandTemplate.PROGRESS -> stringResource(R.string.originisland_tpl_progress)
                                OriginIsland.OriginIslandTemplate.LOADING -> stringResource(R.string.originisland_tpl_loading)
                                OriginIsland.OriginIslandTemplate.TEXT_ICON -> stringResource(R.string.originisland_tpl_text_icon)
                                OriginIsland.OriginIslandTemplate.ICON_TEXT -> stringResource(R.string.originisland_tpl_icon_text)
                                OriginIsland.OriginIslandTemplate.CAPSULE -> stringResource(R.string.originisland_tpl_capsule)
                            },
                        )
                    }
                }
            }
            if (template == OriginIsland.OriginIslandTemplate.PROGRESS) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.originisland_progress_label, progress.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(value = progress, onValueChange = { progress = it }, valueRange = 0f..100f)
            }
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = leftText,
                onValueChange = { leftText = it },
                label = { Text(stringResource(R.string.originisland_field_left)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rightText,
                onValueChange = { rightText = it },
                label = { Text(stringResource(R.string.originisland_field_right)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = stringResource(R.string.originisland_actions_title)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                OriginIslandSenderPost(context, template, title, content, subText, leftText, rightText, progress.toInt())
                            }
                            android.widget.Toast.makeText(context, result, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                ) {
                    Text(stringResource(R.string.originisland_send))
                }
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { OriginIslandSenderCancel(context) } } }) {
                    Text(stringResource(R.string.originisland_cancel))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.originisland_actions_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            stringResource(R.string.originisland_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}

/** Bridge helpers so the composable stays free of sender details. */
private suspend fun OriginIslandSenderPost(
    context: android.content.Context,
    template: OriginIsland.OriginIslandTemplate,
    title: String,
    content: String,
    subText: String,
    leftText: String,
    rightText: String,
    progress: Int,
): String {
    val request = OriginIsland.Request(
        id = dev.cameleonnbss.originostoolkit.service.OriginIslandSender.ID_ISLAND,
        title = title.ifBlank { "OriginOS Toolkit" },
        content = content,
        subText = subText.ifBlank { null },
        template = template,
        progress = progress,
        progressMax = 100,
        leftText = leftText.ifBlank { null },
        rightText = rightText.ifBlank { null },
    )
    return dev.cameleonnbss.originostoolkit.service.OriginIslandSender.post(context, request)
}

private suspend fun OriginIslandSenderCancel(context: android.content.Context) {
    dev.cameleonnbss.originostoolkit.service.OriginIslandSender.cancel(context)
}

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
