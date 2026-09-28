package com.kft.gcs.feature.fly

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.TooltipIconButton

/**
 * "Video": pick the source (or off), delete an own profile, add an RTSP camera by URL. Picking a source shows the
 * picture-in-picture window, which is also how a hidden window comes back.
 */
@Composable
fun VideoDialog(
    state: VideoUiState,
    onSelect: (String?) -> Unit,
    onAddRtsp: (name: String, url: String) -> String?,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("rtsp://") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { androidx.compose.material3.Icon(KftIcons.Video, contentDescription = null) },
        title = { Text("Video") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SourceRow("Off", "No video", selected = state.selected == null, onClick = { onSelect(null); onDismiss() })
                state.profiles.forEach { p ->
                    SourceRow(p.name, p.summary, selected = p.name == state.selected, onClick = { onSelect(p.name); onDismiss() }) {
                        if (p.custom) TooltipIconButton(KftIcons.Delete, "Delete ${p.name}", { onDelete(p.name) })
                    }
                }
                SectionHeader("Add an RTSP camera")
                OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(
                    url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text("URL") }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(it) } },
                )
                TextButton(
                    onClick = { error = onAddRtsp(name, url); if (error == null) onDismiss() },
                    modifier = Modifier.align(Alignment.End),
                ) { Text("Add and show") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SourceRow(title: String, summary: String, selected: Boolean, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}
