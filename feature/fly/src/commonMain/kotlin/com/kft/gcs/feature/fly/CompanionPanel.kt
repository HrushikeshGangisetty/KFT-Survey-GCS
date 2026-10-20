package com.kft.gcs.feature.fly

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.kft.gcs.core.companion.TrackPoint
import com.kft.gcs.ui.design.KftIcons
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import com.kft.gcs.ui.design.TooltipIconButton
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * The companion diagnostics panel: the Pi's own view (VIO, ages, resets, map fix, component health, local track) on
 * top, and below it, labelled as FC data, what the flight controller reports about its estimator. Nothing here
 * commands the vehicle: the buttons only start or stop this GCS's WebSocket and edit its address (P7).
 */
@Composable
fun CompanionPanel(
    state: CompanionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onSettingsSaved: (url: String, systemId: String) -> String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MapCard(modifier.width(360.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Companion (Pi)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TooltipIconButton(KftIcons.Close, "Close companion panel", onClose)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                LineChip(state.connection, Modifier.weight(1f))
                if (state.on) TextButton(onDisconnect) { Text("Disconnect") } else TextButton(onConnect) { Text("Connect") }
            }
            Settings(state, onSettingsSaved)

            state.hiddenReason?.let { StatusChip(it, Status.WARN) }
            state.source?.let { LineChip(it) }
            state.vio?.let { LineChip(it) }
            state.rows.forEach { LineText(it) }
            if (state.health.isNotEmpty()) {
                SectionHeader("Component health")
                state.health.forEach { LineText(it) }
            }
            if (state.hiddenReason == null) {
                SectionHeader("Local track (odom, metres)")
                TrackPlot(state.track, state.currentSegment)
            }
            state.rejected?.let { LineText(it) }

            SectionHeader("FC (MAVLink, from the flight controller)")
            LineText(state.fcEkf)
            state.fcMessages.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Address and vehicle, folded away until asked for. */
@Composable
private fun Settings(state: CompanionUiState, onSaved: (String, String) -> String?) {
    var open by remember { mutableStateOf(false) }
    SectionHeader("Settings · ${state.url} · vehicle ${state.systemId}", expanded = open, onToggle = { open = !open })
    if (!open) return
    var url by remember { mutableStateOf(state.url) }
    var id by remember { mutableStateOf(state.systemId.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Companion address") }, singleLine = true)
    OutlinedTextField(
        id, { id = it; error = null }, Modifier.fillMaxWidth(), label = { Text("Vehicle system id") }, singleLine = true,
        isError = error != null, supportingText = error?.let { { Text(it) } },
    )
    TextButton(onClick = { error = onSaved(url, id); if (error == null) open = false }) { Text("Save (reconnect to use a new address)") }
}

@Composable
private fun LineChip(line: Line, modifier: Modifier = Modifier) = StatusChip("${line.label}: ${line.value}", line.status, modifier)

@Composable
private fun LineText(line: Line) {
    val c = KftTheme.status
    val color = when (line.status) {
        Status.CRITICAL -> c.critical
        Status.WARN -> c.warn
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column {
        Text(line.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line.value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/**
 * The odometry track in metres, odom x up and y right, equal scale, with a grid of 1, 2 or 5 × 10ⁿ m. Each reset
 * segment is drawn on its own (never joined across a reset); older segments are faded.
 */
@Composable
private fun TrackPlot(track: List<TrackPoint>, current: Int?) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.primary
    val spacing = gridSpacing(track)
    // Clipped: the grid runs one line past each edge so it never stops short, and must not draw over the text around it.
    Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds()) {
        if (track.isEmpty()) return@Canvas
        val view = TrackView.fit(track, size.width, size.height)
        drawGrid(view, spacing, grid)
        track.groupBy { it.segment }.forEach { (segment, points) ->
            val color = if (segment == current) line else line.copy(alpha = 0.35f)
            points.zipWithNext { a, b -> drawLine(color, view.toPx(a.x, a.y), view.toPx(b.x, b.y), strokeWidth = 2.dp.toPx()) }
        }
        track.lastOrNull()?.takeIf { it.segment == current }?.let { drawCircle(line, 4.dp.toPx(), view.toPx(it.x, it.y)) }
    }
    Text(
        "Grid ${spacing.toString().removeSuffix(".0")} m · x up, y right (odom axes, not the map)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Maps metres to pixels: centre of the track's bounds at the canvas centre, one scale for both axes. */
private class TrackView(val cx: Double, val cy: Double, val pxPerM: Double, val w: Float, val h: Float) {
    fun toPx(x: Double, y: Double) = Offset((w / 2 + (y - cy) * pxPerM).toFloat(), (h / 2 - (x - cx) * pxPerM).toFloat())

    companion object {
        fun fit(track: List<TrackPoint>, w: Float, h: Float): TrackView {
            val span = max(trackSpan(track), MIN_SPAN_M) * 1.1 // a margin, and a hover doesn't zoom to millimetres
            return TrackView(
                (track.minOf { it.x } + track.maxOf { it.x }) / 2, (track.minOf { it.y } + track.maxOf { it.y }) / 2,
                minOf(w, h) / span, w, h,
            )
        }
    }
}

private fun DrawScope.drawGrid(view: TrackView, spacing: Double, color: Color) {
    val halfM = max(size.width, size.height) / view.pxPerM / 2
    fun lines(centre: Double) = (floor((centre - halfM) / spacing).toInt()..ceil((centre + halfM) / spacing).toInt()).map { it * spacing }
    lines(view.cx).forEach { x -> drawLine(color, view.toPx(x, view.cy - halfM), view.toPx(x, view.cy + halfM)) }
    lines(view.cy).forEach { y -> drawLine(color, view.toPx(view.cx - halfM, y), view.toPx(view.cx + halfM, y)) }
}

private fun trackSpan(track: List<TrackPoint>) =
    if (track.isEmpty()) 0.0 else max(track.maxOf { it.x } - track.minOf { it.x }, track.maxOf { it.y } - track.minOf { it.y })

/** The 1, 2 or 5 × 10ⁿ m spacing that gives about four grid lines across the track. */
internal fun gridSpacing(track: List<TrackPoint>): Double {
    val raw = max(trackSpan(track), MIN_SPAN_M) / 4
    val decade = 10.0.pow(floor(log10(raw)))
    return listOf(1.0, 2.0, 5.0, 10.0).first { it * decade >= raw } * decade
}

private const val MIN_SPAN_M = 2.0
