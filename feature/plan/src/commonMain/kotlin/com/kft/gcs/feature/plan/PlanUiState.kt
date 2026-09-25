package com.kft.gcs.feature.plan

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.MarkerStyle
import kotlin.math.roundToInt

/** Everything the Plan screen draws, plus what it hands to the shared map. */
@Immutable
data class PlanUiState(
    val rows: List<PlanRow>,
    /** The editing form for the selected row, or null when nothing (or a passthrough row) is selected. */
    val form: ItemForm?,
    /** Home, the route and one marker per positioned row. The map in `App()` draws these (ADR-001 F10). */
    val overlays: List<MapOverlay>,
    /** "Uploading 3 / 7…" while a transfer runs, else null. */
    val transfer: String?,
    val canUpload: Boolean,
    val canRead: Boolean,
    val canClear: Boolean,
    /**
     * Non-null while the vehicle is armed: the screen asks for confirmation before uploading, with this text. Not a
     * block: the operator may have a good reason (fixing a mission before the pilot switches to AUTO).
     */
    val uploadWarning: String?,
    /** Clear always asks first. While armed the text says what it does to the mission being flown. */
    val clearWarning: String,
    /** One line telling the operator what to do next. */
    val hint: String,
)

/** A row in the list. [seq] is the vehicle's item number (S11: 1 = first after home). */
data class PlanRow(val index: Int, val seq: Int, val title: String, val detail: String, val selected: Boolean)

/** Raw text of the selected row's fields, so typing "1" on the way to "12" isn't fought by the parser. */
data class ItemForm(val index: Int, val title: String, val altitude: String, val speed: String, val error: String? = null)

/** Marker ids for the map. The index is the row index. */
internal const val HOME_MARKER = "home"
internal fun markerId(index: Int) = "wp-$index"
internal fun indexOfMarker(id: String): Int? = id.removePrefix("wp-").takeIf { id.startsWith("wp-") }?.toIntOrNull()

/** Pure: editor data and vehicle state in, screen state out. */
internal fun buildPlanUiState(
    items: List<PlanItem>,
    selected: Int?,
    form: ItemForm?,
    transfer: String?,
    vehicle: VehicleState,
): PlanUiState {
    val seqs = seqNumbers(items)
    val busy = transfer != null
    // While the mission runs, the item the vehicle is flying to is highlighted, on the Fly view too.
    val flying = vehicle.mission?.takeIf { !it.complete }?.current
    val home = vehicle.home?.position
    // "AUTO", not "Auto": the warning names the mode the way the pilot's OSD and MAVProxy show it.
    val armedIn = if (vehicle.connected && vehicle.armed) "Vehicle is ARMED in ${vehicle.flightMode?.uppercase() ?: "an unknown mode"}" else null
    val markers = items.mapIndexedNotNull { i, item ->
        val at = item.position ?: return@mapIndexedNotNull null
        val style = when {
            i == selected -> MarkerStyle.SELECTED
            seqs[i] == flying -> MarkerStyle.CURRENT
            else -> MarkerStyle.WAYPOINT
        }
        MapOverlay.Marker(markerId(i), at, seqs[i].toString(), style, draggable = item.editable)
    }
    return PlanUiState(
        rows = items.mapIndexed { i, item -> PlanRow(i, seqs[i], title(item), detail(item), i == selected) },
        form = form,
        overlays = listOfNotNull(
            MapOverlay.Route(listOfNotNull(home) + items.mapNotNull { it.position }),
            home?.let { MapOverlay.Marker(HOME_MARKER, it, "H", MarkerStyle.HOME) },
        ) + markers,
        transfer = transfer,
        canUpload = !busy && vehicle.connected && items.isNotEmpty(),
        canRead = !busy && vehicle.connected,
        canClear = !busy && vehicle.connected,
        uploadWarning = armedIn?.let { "$it: uploading replaces the mission it is flying." },
        clearWarning = armedIn?.let { "$it: clearing deletes the mission it is flying." }
            ?: "This deletes the mission on the vehicle and empties the editor.",
        hint = when {
            !vehicle.connected -> "No vehicle: plan now, connect on Links to upload."
            items.isEmpty() -> "Click the map to add waypoints."
            else -> "Drag a numbered marker to move it. Click one to edit it."
        },
    )
}

internal fun title(item: PlanItem): String = when (item.command) {
    MissionCommand.TAKEOFF -> "Takeoff"
    MissionCommand.WAYPOINT -> "Waypoint"
    MissionCommand.RETURN_TO_LAUNCH -> "Return to launch"
    MissionCommand.LAND -> "Land"
    MissionCommand.DO_CHANGE_SPEED -> "Change speed"
    else -> "Command ${item.command}"
}

internal fun detail(item: PlanItem): String {
    if (!item.editable) return "read from vehicle"
    val speed = item.speedMs?.let { " · ${oneDecimal(it)} m/s" } ?: ""
    return "${oneDecimal(item.altitudeM)} m$speed"
}

/** Blank speed = "keep the current speed". Limits are sanity bounds, not the aircraft's envelope. */
internal fun parseForm(altitude: String, speed: String): Result<Pair<Double, Double?>> {
    val alt = altitude.trim().toDoubleOrNull()
    val spd = speed.trim().takeIf { it.isNotEmpty() }?.let { it.toDoubleOrNull() ?: return Result.failure(IllegalArgumentException("Speed must be a number")) }
    return when {
        alt == null -> Result.failure(IllegalArgumentException("Altitude must be a number"))
        alt !in 1.0..1000.0 -> Result.failure(IllegalArgumentException("Altitude must be 1–1000 m above home"))
        spd != null && spd !in 1.0..50.0 -> Result.failure(IllegalArgumentException("Speed must be 1–50 m/s, or blank to keep it"))
        else -> Result.success(alt to spd)
    }
}

internal fun formFor(index: Int, item: PlanItem) =
    ItemForm(index, title(item), oneDecimal(item.altitudeM), item.speedMs?.let(::oneDecimal) ?: "")

private fun oneDecimal(v: Double): String = ((v * 10).roundToInt() / 10.0).toString()
