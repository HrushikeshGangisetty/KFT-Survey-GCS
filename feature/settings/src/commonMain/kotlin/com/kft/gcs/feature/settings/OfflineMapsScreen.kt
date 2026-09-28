package com.kft.gcs.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.ui.design.ConfirmDialog
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.SegmentedChoice
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.TooltipIconButton
import kotlin.math.roundToInt

/**
 * Wires the stateless [OfflineMapsScreen] to its ViewModel. The ViewModel comes from `App()`, like Plan's, because
 * the map there needs this tab's overlays and reports the view to it.
 */
@Composable
fun OfflineMapsRoute(viewModel: OfflineMapsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OfflineMapsEffect.ShowMessage -> snackbar.showSnackbar(effect.text)
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        OfflineMapsScreen(
            state,
            OfflineMapsActions(
                onSourceSelected = viewModel::onSourceSelected,
                onZoomRangeChanged = viewModel::onZoomRangeChanged,
                onDownload = viewModel::onDownloadClicked,
                onPause = viewModel::onPauseClicked,
                onResume = viewModel::onResumeClicked,
                onDeleteRegion = viewModel::onDeleteRegionConfirmed,
                onImport = viewModel::onImportClicked,
                onDeleteImported = viewModel::onDeleteImportedConfirmed,
                onClearCache = viewModel::onClearCacheClicked,
            ),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** The screen's events, grouped so the screen takes two arguments. */
class OfflineMapsActions(
    val onSourceSelected: (String) -> Unit = {},
    val onZoomRangeChanged: (Int, Int) -> Unit = { _, _ -> },
    val onDownload: () -> Unit = {},
    val onPause: (Long) -> Unit = {},
    val onResume: (Long) -> Unit = {},
    val onDeleteRegion: (Long) -> Unit = {},
    val onImport: () -> Unit = {},
    val onDeleteImported: (String) -> Unit = {},
    val onClearCache: () -> Unit = {},
)

/**
 * Stateless, map-first like Plan: one panel on the right, the map visible (and movable) everywhere else, because
 * the map's view is the area that gets downloaded. Deleting asks first: a deleted area needs a network to get back.
 */
@Composable
fun OfflineMapsScreen(state: OfflineMapsUiState, actions: OfflineMapsActions) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    Box(Modifier.fillMaxSize()) {
        MapCard(Modifier.align(Alignment.TopEnd).padding(Spacing.m).width(PANEL_WIDTH).fillMaxHeight(), padding = false) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text("Offline maps", style = MaterialTheme.typography.titleLarge)
                DownloadSection(state, actions)
                SectionHeader("On this device", Modifier.padding(top = Spacing.m))
                if (state.regions.isEmpty() && state.imported.isEmpty()) {
                    Text("Nothing yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.regions.forEach { r ->
                    RegionLine(r, actions, onDelete = { confirm = "Delete “${r.name}”?" to { actions.onDeleteRegion(r.id) } })
                }
                state.imported.forEach { m ->
                    ItemLine(m.name, "Imported MBTiles · ${m.size}") {
                        TooltipIconButton(KftIcons.Delete, "Delete", { confirm = "Delete “${m.name}”?" to { actions.onDeleteImported(m.id) } })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = actions.onImport) { Text("Import MBTiles…") }
                    TextButton(onClick = actions.onClearCache) { Text("Clear browsing cache") }
                }
            }
        }
    }
    confirm?.let { (title, onConfirm) ->
        ConfirmDialog(
            title = title,
            text = "Its tiles are removed from this device. Getting them back needs a network.",
            confirmLabel = "Delete",
            onConfirm = { onConfirm(); confirm = null },
            onDismiss = { confirm = null },
            dismissLabel = "Keep",
            destructive = true,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadSection(state: OfflineMapsUiState, actions: OfflineMapsActions) {
    SectionHeader("Download the area on screen")
    Text(
        "Move and zoom the map to the area you need. The Street map then works there with no network.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (state.sources.size > 1) {
        SegmentedChoice(state.sources.map { it.name to it.id }, state.sourceId, actions.onSourceSelected, Modifier.fillMaxWidth())
    }
    Text("Zoom ${state.minZoom} to ${state.maxZoom}", style = MaterialTheme.typography.bodyMedium)
    RangeSlider(
        value = state.minZoom.toFloat()..state.maxZoom.toFloat(),
        onValueChange = { actions.onZoomRangeChanged(it.start.roundToInt(), it.endInclusive.roundToInt()) },
        valueRange = 0f..OfflineMapsViewModel.MAX_ZOOM.toFloat(),
        steps = OfflineMapsViewModel.MAX_ZOOM - 1,
    )
    state.zoomNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    state.area?.let { Text("Area: $it", style = MaterialTheme.typography.bodyMedium) }
    state.estimate?.let { Text("Estimate: $it", style = MaterialTheme.typography.bodyMedium) }
    state.blockReason?.let { StatusChip(it, Status.WARN) }
    Button(onClick = actions.onDownload, enabled = state.canDownload, modifier = Modifier.fillMaxWidth()) { Text("Download") }
}

@Composable
private fun RegionLine(r: RegionRow, actions: OfflineMapsActions, onDelete: () -> Unit) {
    ItemLine(r.name, r.detail, r.fraction) {
        if (r.canPause) TooltipIconButton(KftIcons.Pause, "Pause", { actions.onPause(r.id) })
        if (r.canResume) TooltipIconButton(KftIcons.Resume, "Resume", { actions.onResume(r.id) })
        TooltipIconButton(KftIcons.Delete, "Delete", onDelete)
    }
}

/** A name, a detail line, an optional progress bar, and buttons at the end. */
@Composable
private fun ItemLine(name: String, detail: String, fraction: Float? = null, buttons: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            fraction?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        }
        buttons()
    }
}

private val PANEL_WIDTH = 360.dp
