package com.kft.gcs.feature.replay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.StatTile
import com.kft.gcs.ui.design.TooltipIconButton
import com.kft.gcs.ui.map.VehicleColors

/** The Replay tab, reading the window-scoped [viewModel] that also feeds the map (see App.kt). */
@Composable
fun ReplayRoute(viewModel: ReplayViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReplayScreen(
        state,
        onOpenLog = viewModel::onOpenLogClicked,
        onOpenRecording = viewModel::onOpenRecordingClicked,
        onPlayPause = viewModel::onPlayPauseClicked,
        onSeek = viewModel::onSeek,
        onSpeed = viewModel::onSpeedSelected,
        onFollow = viewModel::onFollowToggled,
        onFit = viewModel::onFitClicked,
    )
}

/**
 * Stats and events on the right, the error chart and playback controls along the bottom; the map shows through the
 * rest, so it can still be panned and zoomed.
 */
@Composable
fun ReplayScreen(
    state: ReplayUiState,
    onOpenLog: () -> Unit,
    onOpenRecording: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSpeed: (Int) -> Unit,
    onFollow: () -> Unit,
    onFit: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(Spacing.s)) {
        MapCard(Modifier.align(Alignment.TopEnd).width(360.dp).fillMaxHeight().padding(bottom = 196.dp)) {
            InfoPanel(state, onOpenLog, onOpenRecording, onSeek)
        }
        MapCard(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(188.dp)) {
            ErrorChartView(state, onSeek, Modifier.weight(1f).fillMaxWidth())
            PlaybackBar(state, onPlayPause, onSeek, onSpeed, onFollow, onFit)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.InfoPanel(state: ReplayUiState, onOpenLog: () -> Unit, onOpenRecording: () -> Unit, onSeek: (Double) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        OutlinedButton(onOpenLog) { Text("Open FC log…") }
        OutlinedButton(onOpenRecording) { Text(if (state.odometryName == null) "Add Pi odometry…" else "Replace Pi odometry…") }
    }
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.s))
    state.error?.let { Text(it, Modifier.padding(top = Spacing.s), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (state.title == null) {
        Text(
            "Open the FC's DataFlash log (.bin) to replay a flight: the truth (GPS, or SITL truth), the vision positions " +
                "the FC received (VISP), and their error. Add the Pi recording's odometry.jsonl to compare the VIO's own output.",
            Modifier.padding(top = Spacing.m),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.odometryName?.let { Text("Pi odometry loaded: $it", style = MaterialTheme.typography.bodySmall) }
        return
    }
    Text(state.title, Modifier.padding(top = Spacing.m), style = MaterialTheme.typography.titleMedium)
    Text(state.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    // Stats at their natural height; the events list takes what is left and scrolls on its own.
    Column {
        SectionHeader("At the playhead", Modifier.padding(top = Spacing.s))
        StatGrid(state.playhead)
        SectionHeader("Whole flight", Modifier.padding(top = Spacing.s))
        StatGrid(state.whole)
    }
    SectionHeader("Events", Modifier.padding(top = Spacing.s))
    LazyColumn(Modifier.weight(1f)) {
        items(state.events) { e ->
            Row(Modifier.fillMaxWidth().clickable { onSeek(e.t) }.padding(vertical = 2.dp)) {
                Text(e.time, Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.text, style = MaterialTheme.typography.bodySmall, color = if (e.t <= state.timeS) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatGrid(stats: List<Stat>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        stats.forEach { stat ->
            Row(Modifier.width(156.dp), verticalAlignment = Alignment.Top) {
                stat.slot?.let { Swatch(it, Modifier.padding(top = 5.dp, end = Spacing.xs)) }
                StatTile(stat.label, stat.value)
            }
        }
    }
}

/** The map colour a number belongs to, so the tile and its track read as one thing. */
@Composable
private fun Swatch(slot: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(width = 12.dp, height = 4.dp).background(VehicleColors[slot]))
}

/**
 * Error against truth over the whole flight (VISP teal, VIO orange) with the playhead as a line; a click seeks there.
 * Drawn on a Canvas: two polylines don't need a chart library.
 */
@Composable
private fun ErrorChartView(state: ReplayUiState, onSeek: (Double) -> Unit, modifier: Modifier) {
    val span = (state.endS - state.startS).coerceAtLeast(1e-6)
    val axis = MaterialTheme.colorScheme.outline
    val playhead = MaterialTheme.colorScheme.onSurface
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Text("Error vs truth, 0–${kotlin.math.ceil(state.chart.maxM).toLong()} m", style = MaterialTheme.typography.labelMedium)
            Swatch(ReplayViewModel.PUBLISHED_SLOT); Text("VISP (sent to FC)", style = MaterialTheme.typography.labelSmall)
            if (state.chart.vio.isNotEmpty()) { Swatch(ReplayViewModel.VIO_SLOT); Text("Pi VIO", style = MaterialTheme.typography.labelSmall) }
        }
        var width by remember { mutableStateOf(1f) }
        Canvas(
            Modifier.fillMaxSize().padding(vertical = Spacing.xs).clickableAt { x -> onSeek(state.startS + x / width * span) },
        ) {
            width = size.width
            fun x(t: Double) = ((t - state.startS) / span * size.width).toFloat()
            fun y(m: Double) = (size.height - m / state.chart.maxM * size.height).toFloat()
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height))
            listOf(state.chart.vio to VehicleColors[ReplayViewModel.VIO_SLOT], state.chart.published to VehicleColors[ReplayViewModel.PUBLISHED_SLOT]).forEach { (series, color) ->
                if (series.size < 2) return@forEach
                val path = Path()
                series.forEachIndexed { i, (t, m) -> if (i == 0) path.moveTo(x(t), y(m)) else path.lineTo(x(t), y(m)) }
                drawPath(path, color, style = Stroke(1.5.dp.toPx()))
            }
            if (state.title != null) drawLine(playhead, Offset(x(state.timeS), 0f), Offset(x(state.timeS), size.height))
        }
    }
}

/** A tap or click on the chart, reported as its x position. */
private fun Modifier.clickableAt(onX: (Float) -> Unit) = pointerInput(Unit) { detectTapGestures(onTap = { onX(it.x) }) }

@Composable
private fun PlaybackBar(
    state: ReplayUiState,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onSpeed: (Int) -> Unit,
    onFollow: () -> Unit,
    onFit: () -> Unit,
) {
    val loaded = state.title != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        TooltipIconButton(if (state.playing) KftIcons.Pause else KftIcons.Resume, if (state.playing) "Pause" else "Play", onPlayPause, enabled = loaded)
        Slider(
            value = state.timeS.toFloat(),
            onValueChange = { onSeek(it.toDouble()) },
            valueRange = state.startS.toFloat()..maxOf(state.endS, state.startS + 1).toFloat(),
            enabled = loaded,
            modifier = Modifier.weight(1f),
        )
        Text("${secs(state.timeS - state.startS)} / ${secs(state.endS - state.startS)} s", style = MaterialTheme.typography.labelMedium)
        var speedMenu by remember { mutableStateOf(false) }
        Box {
            TextButton({ speedMenu = true }, enabled = loaded) { Text("${state.speed}×") }
            DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                ReplayViewModel.SPEEDS.forEach { s -> DropdownMenuItem(text = { Text("$s×") }, onClick = { speedMenu = false; onSpeed(s) }) }
            }
        }
        FilterChip(selected = state.follow, onClick = onFollow, label = { Text("Follow") }, enabled = loaded)
        OutlinedButton(onFit, enabled = loaded) { Text("Fit") }
        Spacer(Modifier.width(Spacing.xs))
    }
}

private fun secs(s: Double) = ((s * 10).toLong() / 10.0).toString()
