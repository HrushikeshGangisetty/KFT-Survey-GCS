package com.kft.gcs.feature.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.MinTouchTarget
import com.kft.gcs.ui.design.Spacing

/** The flight-log page's events. */
class LogsActions(
    val onList: () -> Unit = {},
    val onDownload: (Int) -> Unit = {},
    val onCancel: () -> Unit = {},
)

/** "Read the list", one row per log (newest first) with its own Download, and the running download's progress. */
@Composable
internal fun LogsPage(state: LogsUiState, actions: LogsActions) {
    SetupCard(
        "Logs on the flight controller",
        "The flight controller's own logs (.bin): camera-trigger positions for geotagging, raw GNSS for PPK, and everything " +
            "for incident analysis. Downloading copies a log here; it stays on the vehicle. Over a telemetry radio a large " +
            "log takes a long time: USB or Wi-Fi is much faster.",
    ) {
        Button(onClick = actions.onList, enabled = state.canList) {
            Icon(KftIcons.Sync, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(if (state.listed) "Read the list again" else "Read the list")
        }
        if (state.listing) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    state.download?.let { d ->
        SetupCard("Downloading ${d.title}") {
            LinearProgressIndicator(progress = { d.fraction }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = actions.onCancel) { Text("Cancel") }
            }
        }
    }
    if (state.listed) {
        SetupCard("${state.rows.size} logs", if (state.rows.isEmpty()) "The flight controller has no logs." else "Newest first. Times are UTC, as the flight controller logged them.") {
            state.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${row.title} · ${row.size}", style = MaterialTheme.typography.bodyLarge)
                        Text(row.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        row.saved?.let { Text("Saved: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                    }
                    OutlinedButton(onClick = { actions.onDownload(row.id) }, enabled = state.canDownload) {
                        Icon(KftIcons.Download, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text("Download")
                    }
                }
            }
        }
    }
}
