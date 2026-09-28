package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.ImportedShapes
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.ui.map.CameraRequest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln

/** A file read and waiting for the operator's answer: add it as survey areas or as waypoints? */
internal data class ImportChoice(val fileName: String, val shapes: ImportedShapes)

/** The "Import as…" dialog. [canSurvey] only when the file has areas: a survey needs an outline. */
data class ImportDialog(val title: String, val summary: String, val canSurvey: Boolean)

internal fun ImportChoice.dialog() = ImportDialog(
    title = "Import $fileName",
    summary = shapes.summary(),
    canSurvey = shapes.areas.isNotEmpty(),
)

/** "2 areas, 1 line, 3 points". */
internal fun ImportedShapes.summary(): String = listOf(areas.size to "area", lines.size to "line", points.size to "point")
    .filter { it.first > 0 }
    .joinToString { (n, word) -> "$n $word${if (n == 1) "" else "s"}" }

/**
 * One survey per area, each with [template]'s settings (the defaults a new survey gets). Named after the area in the
 * file, else after the file ("farm", or "farm 1", "farm 2" when there are several).
 */
internal fun surveyGroupsFrom(shapes: ImportedShapes, fileName: String, template: SurveySettings): List<SurveyGroup> {
    val base = fileName.substringBeforeLast('.')
    return shapes.areas.mapIndexed { i, area ->
        val name = area.name ?: if (shapes.areas.size == 1) base else "$base ${i + 1}"
        SurveyGroup(name, template.copy(polygon = area.points))
    }
}

/**
 * Waypoint groups: all the points as one group, and each line as its own group. A file with neither gives each
 * area's outline instead, so an import is never empty. The waypoints get the editor's default altitude above home:
 * altitudes in these files are usually above sea level or clamped to the ground, and a wrong reading of them would
 * send the aircraft into the ground or far too high, so they're never used.
 * [startsMission]: no earlier group has items, so a Copter's first group also gets its takeoff ([addWaypoint]).
 */
internal fun waypointGroupsFrom(shapes: ImportedShapes, fileName: String, kind: VehicleKind?, startsMission: Boolean): List<WaypointGroup> {
    val base = fileName.substringBeforeLast('.')
    val paths = buildList {
        if (shapes.points.isNotEmpty()) add((if (shapes.lines.isEmpty()) base else "$base points") to shapes.points.map { it.position })
        shapes.lines.forEachIndexed { i, l -> add((l.name ?: "$base line ${i + 1}") to l.points) }
        if (isEmpty()) shapes.areas.forEachIndexed { i, a -> add((a.name ?: "$base ${i + 1}") to a.points) }
    }
    return paths.mapIndexed { g, (name, points) ->
        val items = points.fold(emptyList<PlanItem>()) { acc, p -> acc.addWaypoint(p, kind, startsMission = startsMission && g == 0) }
        WaypointGroup(name, items)
    }
}

/**
 * A camera move that shows all of [points], the way a GCS jumps to an imported area. A Web Mercator map at zoom z
 * is 256·2^z dp around, so a span of s degrees of longitude fills 256·2^z·s/360 dp. Solving for 500 dp (what's left of
 * a laptop-sized map beside the 360 dp panel, with a margin) gives z = log2(500·360 / (256·s)) = log2(703 / s); a
 * latitude span counts 1/cos(lat) wider. 700 dp was tried first and cut the field off under the panel (Pass 24 run).
 */
internal fun fitCamera(points: List<LatLon>, id: Long): CameraRequest? {
    if (points.isEmpty()) return null
    val south = points.minOf { it.latitude }
    val north = points.maxOf { it.latitude }
    val west = points.minOf { it.longitude }
    val east = points.maxOf { it.longitude }
    val middle = LatLon((south + north) / 2, (west + east) / 2)
    val span = maxOf(east - west, (north - south) / cos(middle.latitude * PI / 180), 1e-6)
    val zoom = (ln(703 / span) / ln(2.0)).coerceIn(2.0, 18.0)
    return CameraRequest(middle, zoom, id)
}

internal fun ImportedShapes.allPoints(): List<LatLon> =
    areas.flatMap { it.points } + lines.flatMap { it.points } + points.map { it.position }
