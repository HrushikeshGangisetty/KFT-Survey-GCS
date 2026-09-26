package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.CameraEntry
import com.kft.gcs.core.geoio.toEntry
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.vehicle.AltitudeFrame
import com.kft.gcs.core.vehicle.MissionItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Our own plan file (`.kftplan`, JSON): the groups with their survey parameters, so a saved survey reopens as a
 * survey, not as 200 loose waypoints. QGC `.plan` / MP `.waypoints` export only the flat items (core:geo-io).
 *
 * The file shapes below are separate from the editor's types on purpose: the editor can change freely, and only a
 * change here changes the file format (then bump [PlanFile.version] and keep reading the old one).
 */
@Serializable
private data class PlanFile(val format: String = FORMAT, val version: Int = 1, val groups: List<GroupEntry>)

@Serializable
private sealed interface GroupEntry {
    val name: String
}

@Serializable @SerialName("waypoints")
private data class WaypointsEntry(override val name: String, val items: List<ItemEntry>) : GroupEntry

@Serializable @SerialName("survey")
private data class SurveyEntry(
    override val name: String,
    /** Corners as [lat, lon] pairs: latitude first like [LatLon] (GeoJSON is the other way round, lon first). */
    val polygon: List<List<Double>>,
    val camera: CameraEntry,
    val heightMode: HeightMode,
    val altitudeM: Double,
    val gsdCm: Double,
    val orientation: CameraOrientation,
    val sideOverlapPct: Double,
    val frontOverlapPct: Double,
    val gridAngleDeg: Double,
    val entry: EntryCorner,
    val speedMs: Double,
    val turnaroundM: Double,
    val returnHome: Boolean,
) : GroupEntry

/** An editor row. A passthrough row (read from the vehicle, not editable) keeps its raw mission item fields. */
@Serializable
private data class ItemEntry(
    val command: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitudeM: Double = 0.0,
    val speedMs: Double? = null,
    val passthrough: Boolean = false,
    val frame: AltitudeFrame = AltitudeFrame.RELATIVE,
    val params: List<Float> = listOf(0f, 0f, 0f, 0f),
)

private const val FORMAT = "kft-plan"

// allowSpecialFloatingPointValues: a passthrough item can carry NaN params ("leave unchanged").
private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true }

internal fun encodePlan(groups: List<MissionGroup>): String = json.encodeToString(PlanFile(groups = groups.map { it.toEntry() }))

/** True when [text] looks like our own format, so Open can tell it from a QGC or MP file without trusting the name. */
internal fun isOwnPlan(text: String) = text.contains("\"$FORMAT\"")

/** @throws IllegalArgumentException with a message for the operator when [text] isn't a readable plan file. */
internal fun decodePlan(text: String): List<MissionGroup> {
    val file = try {
        json.decodeFromString<PlanFile>(text)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("Not a valid KFT plan file: ${e.message}", e)
    }
    require(file.format == FORMAT) { "Not a KFT plan file" }
    require(file.version <= 1) { "This plan was saved by a newer version of the app (format ${file.version})" }
    return file.groups.map { it.toGroup() }
}

private fun MissionGroup.toEntry(): GroupEntry = when (this) {
    is WaypointGroup -> WaypointsEntry(name, items.map { it.toEntry() })
    is SurveyGroup -> with(survey) {
        SurveyEntry(
            name, polygon.map { listOf(it.latitude, it.longitude) }, camera.toEntry(), heightMode, altitudeM, gsdCm,
            orientation, sideOverlapPct, frontOverlapPct, gridAngleDeg, entry, speedMs, turnaroundM, returnHome,
        )
    }
}

private fun GroupEntry.toGroup(): MissionGroup = when (this) {
    is WaypointsEntry -> WaypointGroup(name, items.map { it.toItem() })
    is SurveyEntry -> SurveyGroup(
        name,
        SurveySettings(
            polygon.map { LatLon(it[0], it[1]) }, camera.toCamera(), heightMode, altitudeM, gsdCm, orientation,
            sideOverlapPct, frontOverlapPct, gridAngleDeg, entry, speedMs, turnaroundM, returnHome,
        ),
    )
}

private fun PlanItem.toEntry(): ItemEntry {
    val raw = passthrough
    return if (raw == null) {
        ItemEntry(command, position?.latitude, position?.longitude, altitudeM, speedMs)
    } else {
        ItemEntry(raw.command, raw.position?.latitude, raw.position?.longitude, raw.altitudeM, null, true, raw.frame, listOf(raw.param1, raw.param2, raw.param3, raw.param4))
    }
}

private fun ItemEntry.toItem(): PlanItem {
    val position = if (lat != null && lon != null) LatLon(lat, lon) else null
    if (!passthrough) return PlanItem(command, position, altitudeM, speedMs)
    require(params.size == 4) { "a passthrough item needs 4 params" }
    return PlanItem(command, position, altitudeM, passthrough = MissionItem(command, position, altitudeM, frame, params[0], params[1], params[2], params[3]))
}

/**
 * Where a new corner goes. The first three are added in click order. After that, the corner is put into the edge
 * whose detour it lengthens least (|a→p| + |p→b| − |a→b|), which is almost always the edge the operator clicked next
 * to, so clicking outside a side grows that side instead of drawing a line across the area.
 */
internal fun insertCorner(polygon: List<LatLon>, at: LatLon): List<LatLon> {
    if (polygon.size < 3) return polygon + at
    val edge = polygon.indices.minBy { i ->
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        Geodesy.distanceMeters(a, at) + Geodesy.distanceMeters(at, b) - Geodesy.distanceMeters(a, b)
    }
    return polygon.subList(0, edge + 1) + at + polygon.subList(edge + 1, polygon.size)
}
