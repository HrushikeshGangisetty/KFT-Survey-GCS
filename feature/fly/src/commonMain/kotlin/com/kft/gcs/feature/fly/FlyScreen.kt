package com.kft.gcs.feature.fly

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.core.vehicle.Severity
import org.koin.compose.viewmodel.koinViewModel

/**
 * Collects the ViewModel's state and hands it to [FlyScreen]. `App()` passes the ViewModel in, because it also feeds
 * the same state (overlays, basemap, camera) to the one shared map (ADR-001 F10).
 */
@Composable
fun FlyRoute(viewModel: FlyViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FlyScreen(state, viewModel::onBasemapSelected, viewModel::onCenterClicked, viewModel::onClearTrackClicked)
}

/**
 * Map-first layout, as in every GCS: the map (drawn underneath by `App()`) fills the screen, the HUD strip floats
 * top-left, map controls top-right, and the latest vehicle message bottom-left. Translucent panels keep the map
 * visible. Monitoring only: no flight-action buttons (spec S9).
 */
@Composable
fun FlyScreen(
    state: FlyUiState,
    onBasemapSelected: (String) -> Unit,
    onCenter: () -> Unit,
    onClearTrack: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Panel(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            if (!state.connected) {
                Text("No vehicle", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleSmall)
            }
            state.firmware?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                state.hud.forEach { item ->
                    Column {
                        Text(item.label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.7f))
                        Text(
                            item.value,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (item.warning) MaterialTheme.colorScheme.secondary else Color.White,
                        )
                    }
                }
            }
        }

        Panel(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.basemaps.size > 1) {
                    state.basemaps.forEach { b ->
                        FilterChip(selected = b == state.selectedBasemap, onClick = { onBasemapSelected(b.id) }, label = { Text(b.name) })
                    }
                }
                FilledTonalButton(onClick = onCenter, enabled = state.canCenter) { Text("Centre") }
                FilledTonalButton(onClick = onClearTrack) { Text("Clear track") }
            }
        }

        state.message?.let { message ->
            Panel(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 40.dp)) {
                Text(
                    message.text,
                    color = when (message.severity) {
                        Severity.ERROR -> MaterialTheme.colorScheme.error
                        Severity.WARNING -> MaterialTheme.colorScheme.secondary
                        Severity.INFO -> Color.White
                    },
                )
            }
        }
    }
}

/**
 * A translucent dark panel so text stays readable over both street and satellite maps. Children stack
 * vertically (a Box would draw them on top of each other).
 */
@Composable
private fun Panel(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(12.dp)) { content() }
}
