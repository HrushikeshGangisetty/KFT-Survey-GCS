package com.kft.gcs.feature.plan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.kft.gcs.ui.design.KftTheme
import com.kft.gcs.ui.design.MapCard
import com.kft.gcs.ui.design.SectionHeader
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The terrain profile, like QGC's: distance along the route across, height above sea level up. The ground is a filled
 * area; the planned path is a line, drawn in the warn colour where its clearance is under
 * [TerrainProfile.LOW_CLEARANCE_M] and critical where it is under the ground. Pointing at the chart (hover, or touch)
 * reads out the heights at that distance. Collapsible, because on a small tablet it covers part of the map.
 */
@Composable
internal fun TerrainPanel(terrain: TerrainUi, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(true) }
    MapCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Terrain profile", Modifier.weight(1f), expanded = open, onToggle = { open = !open })
            terrain.profile?.let { p ->
                val clearance = "${p.lowestClearanceM.roundToInt()} m"
                when (p.status) {
                    ClearanceStatus.OK -> StatusChip("Lowest clearance $clearance · ${where(p.lowestAt)}", Status.OK)
                    ClearanceStatus.LOW -> StatusChip("Low clearance $clearance · ${where(p.lowestAt)}", Status.WARN)
                    ClearanceStatus.BELOW_GROUND -> StatusChip("Path below ground · ${where(p.lowestAt)}", Status.CRITICAL)
                }
            }
        }
        terrain.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (open) terrain.profile?.let { ProfileChart(it, Modifier.fillMaxWidth().height(CHART_HEIGHT)) }
    }
}

// The lowest point is on the leg after this waypoint (or on it), so "after" names where to look.
private fun where(mark: String) = if (mark == "H") "after home" else "after item $mark"

@Composable
private fun ProfileChart(profile: TerrainProfile, modifier: Modifier) {
    val ground = MaterialTheme.colorScheme.onSurfaceVariant
    val path = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val text = MaterialTheme.colorScheme.onSurface
    val status = KftTheme.status
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = ground)
    val measurer = rememberTextMeasurer()
    var pointer by remember { mutableStateOf<Float?>(null) }
    val samples = profile.samples
    val heights = samples.flatMap { listOf(it.terrainM, it.pathM) }
    // Round the axis to 10 m steps with some headroom, so the lines never touch the edges.
    val lo = floor((heights.min() - 5) / 10) * 10
    val hi = ceil((heights.max() + 5) / 10) * 10
    val length = samples.last().distanceM.coerceAtLeast(1.0)

    Canvas(
        modifier.pointerInput(profile) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    pointer = if (e.type == PointerEventType.Exit) null else e.changes.first().position.x
                }
            }
        },
    ) {
        val left = 44.dp.toPx()
        val bottom = size.height - 16.dp.toPx()
        val top = 24.dp.toPx() // below the row of waypoint numbers
        fun x(d: Double) = left + ((d / length) * (size.width - left)).toFloat()
        fun y(h: Double) = bottom - ((h - lo) / (hi - lo) * (bottom - top)).toFloat()

        // Height grid: the bottom, middle and top of the axis, labelled in metres AMSL.
        listOf(lo, (lo + hi) / 2, hi).forEach { h ->
            drawLine(grid, Offset(left, y(h)), Offset(size.width, y(h)), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
            drawText(measurer, "${h.roundToInt()} m", Offset(0f, y(h) - 7.dp.toPx()), labelStyle)
        }
        drawText(measurer, "0", Offset(left, bottom + 2.dp.toPx()), labelStyle)
        val end = measurer.measure(distanceLabel(length), labelStyle)
        drawText(end, topLeft = Offset(size.width - end.size.width, bottom + 2.dp.toPx()))

        val area = Path().apply {
            moveTo(x(samples.first().distanceM), bottom)
            samples.forEach { lineTo(x(it.distanceM), y(it.terrainM)) }
            lineTo(x(samples.last().distanceM), bottom)
            close()
        }
        drawPath(area, ground.copy(alpha = 0.22f))
        drawPath(Path().apply { samples.forEachIndexed { i, s -> if (i == 0) moveTo(x(s.distanceM), y(s.terrainM)) else lineTo(x(s.distanceM), y(s.terrainM)) } }, ground, style = Stroke(1.5f))

        samples.zipWithNext { a, b ->
            val clearance = minOf(a.pathM - a.terrainM, b.pathM - b.terrainM)
            val c = when {
                clearance < 0 -> status.critical
                clearance < TerrainProfile.LOW_CLEARANCE_M -> status.warn
                else -> path
            }
            drawLine(c, Offset(x(a.distanceM), y(a.pathM)), Offset(x(b.distanceM), y(b.pathM)), 2.5f)
        }
        samples.filter { it.mark != null }.forEach { s ->
            drawCircle(path, 3.dp.toPx(), Offset(x(s.distanceM), y(s.pathM)))
            drawText(measurer, s.mark!!, Offset(x(s.distanceM) - 3.dp.toPx(), 0f), labelStyle)
        }

        // Readout at the pointer: the nearest sample's heights.
        pointer?.takeIf { it >= left }?.let { px ->
            val d = (px - left) / (size.width - left) * length
            val s = samples.minBy { kotlin.math.abs(it.distanceM - d) }
            drawLine(text.copy(alpha = 0.5f), Offset(x(s.distanceM), top), Offset(x(s.distanceM), bottom), 1f)
            val label = "${distanceLabel(s.distanceM)} · ground ${s.terrainM.roundToInt()} m · path ${s.pathM.roundToInt()} m · clearance ${(s.pathM - s.terrainM).roundToInt()} m"
            val layout = measurer.measure(label, labelStyle.copy(color = text))
            val lx = (x(s.distanceM) + 6.dp.toPx()).coerceAtMost(size.width - layout.size.width)
            drawText(layout, topLeft = Offset(lx, top))
        }
    }
}

private fun distanceLabel(m: Double) = if (m < 1000) "${m.roundToInt()} m" else "${(m / 100).roundToInt() / 10.0} km"

private val CHART_HEIGHT = 150.dp
