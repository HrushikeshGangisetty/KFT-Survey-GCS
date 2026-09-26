package com.kft.gcs.feature.plan

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.SurveyWarning
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import com.kft.gcs.core.vehicle.MissionSyncState
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.sameMission
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.MarkerStyle
import kotlin.math.roundToInt

/** Everything the Plan screen draws, plus what it hands to the shared map. */
@Immutable
data class PlanUiState(
    /** The mission's groups, in flight order, with their header lines. */
    val groups: List<GroupHeader>,
    /** The rows of the selected waypoint group (empty when a survey or nothing is selected). */
    val rows: List<PlanRow>,
    /** The editing form for the selected row, or null when nothing (or a passthrough row) is selected. */
    val form: ItemForm?,
    /** The survey panel, when a survey group is selected. */
    val survey: SurveyPanel?,
    /** Home, the route, markers, survey areas and corners. The map in `App()` draws these (ADR-001 F10). */
    val overlays: List<MapOverlay>,
    /** "Uploading 3 / 7…" while a transfer runs, else null. */
    val transfer: String?,
    val canUpload: Boolean,
    val canRead: Boolean,
    val canClear: Boolean,
    /** Clear always asks first. While armed the text says what it does to the mission being flown. */
    val clearWarning: String,
    /** One line telling the operator what to do next. */
    val hint: String,
    /** "Not uploaded" / "On vehicle" / "Vehicle mission changed elsewhere", or null for an empty, never-sent plan. */
    val sync: SyncUi?,
    val canUndo: Boolean,
    val canRedo: Boolean,
    /** The upload preview dialog, while it's open. */
    val preview: UploadPreview?,
)

data class GroupHeader(val index: Int, val name: String, val kind: String, val summary: String, val selected: Boolean)

/** A row in the waypoint list. [seq] is the vehicle's item number (S11: 1 = first after home). */
data class PlanRow(val index: Int, val seq: Int, val title: String, val detail: String, val selected: Boolean)

/** Raw text of the selected row's fields, so typing "1" on the way to "12" isn't fought by the parser. */
data class ItemForm(val index: Int, val title: String, val altitude: String, val speed: String, val error: String? = null)

data class SyncUi(val text: String, val warning: Boolean)

/**
 * The survey panel's content.
 * @property altitudeM / [gsdCm] the values the plan uses: the one the operator typed and the one computed from it.
 */
data class SurveyPanel(
    val settings: SurveySettings,
    val cameras: List<CameraChoice>,
    val isPlane: Boolean,
    val altitudeM: Double?,
    val gsdCm: Double?,
    val stats: List<Pair<String, String>>,
    val warnings: List<String>,
    val error: String?,
    val cornerCount: Int,
    val cornerSelected: Boolean,
    val batteryMinutes: Double?,
    val maxGsdCm: Double?,
)

data class CameraChoice(val name: String, val unverified: Boolean, val custom: Boolean)

/** What an upload will send, one line per item, seq 0 (home) first, plus everything worth a second look. */
data class UploadPreview(val lines: List<String>, val warnings: List<String>)

/**
 * The editor's own state: the plan (as groups), the selection, the undo history and the dialogs. The ViewModel keeps
 * it in one immutable value, so undo is "put the previous groups back" and nothing else can drift.
 * @property selectedItem a row of the selected waypoint group, or a corner of the selected survey.
 * @property lastEdit what the latest edit was ("drag wp-0-2", "survey 1 SPEED"). A repeat of the same edit (a drag's
 *   many small moves, typing into one field) joins the same undo step instead of making one per event.
 */
internal data class PlanEdit(
    val groups: List<MissionGroup> = listOf(WaypointGroup("Waypoints")),
    val selectedGroup: Int? = 0,
    val selectedItem: Int? = null,
    val form: ItemForm? = null,
    val transfer: String? = null,
    val past: List<List<MissionGroup>> = emptyList(),
    val future: List<List<MissionGroup>> = emptyList(),
    val lastEdit: String? = null,
    val preview: UploadPreview? = null,
)

/** Marker ids for the map: the group index and the row or corner index. */
internal const val HOME_MARKER = "home"
internal fun markerId(group: Int, index: Int) = "wp-$group-$index"
internal fun cornerId(group: Int, index: Int) = "corner-$group-$index"

/** (kind, group, index) from a marker id, or null for home or a foreign id. */
internal fun parseMarker(id: String): Triple<String, Int, Int>? {
    val parts = id.split('-')
    if (parts.size != 3 || parts[0] !in setOf("wp", "corner")) return null
    return Triple(parts[0], parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
}

/** The survey warnings and battery estimate use the settings for the vehicle kind; unknown counts as a copter. */
internal fun PlanSettings.limitsFor(kind: VehicleKind?) = SurveyLimits(
    usableFlightTimeS = batteryMinutes[kind.orCopter()]?.let { it * 60 },
    maxGsdM = maxGsdCm?.let { it / 100 },
)

internal fun VehicleKind?.orCopter() = if (this == VehicleKind.PLANE) VehicleKind.PLANE else VehicleKind.COPTER

/** Pure: editor data, vehicle, settings and sync state in, screen state out. */
internal fun buildPlanUiState(e: PlanEdit, vehicle: VehicleState, settings: PlanSettings, sync: MissionSyncState): PlanUiState {
    val kind = vehicle.vehicleKind
    val flat = flatten(e.groups, kind, settings.limitsFor(kind))
    val busy = e.transfer != null
    val flying = vehicle.mission?.takeIf { !it.complete }?.current
    val home = vehicle.home?.position
    val selected = e.selectedGroup?.let(flat.groups::getOrNull)
    // "AUTO", not "Auto": the warning names the mode the way the pilot's OSD and MAVProxy show it.
    val armedIn = if (vehicle.connected && vehicle.armed) "Vehicle is ARMED in ${vehicle.flightMode?.uppercase() ?: "an unknown mode"}" else null

    val overlays = buildList<MapOverlay> {
        add(MapOverlay.Route(listOfNotNull(home) + flat.items.mapNotNull { it.position }))
        home?.let { add(MapOverlay.Marker(HOME_MARKER, it, "H", MarkerStyle.HOME)) }
        flat.groups.forEachIndexed { g, fg ->
            when (val group = fg.group) {
                is WaypointGroup -> {
                    val seqs = seqNumbers(group.items).map { it + fg.firstSeq - 1 }
                    group.items.forEachIndexed { i, item ->
                        val at = item.position ?: return@forEachIndexed
                        val style = when {
                            g == e.selectedGroup && i == e.selectedItem -> MarkerStyle.SELECTED
                            seqs[i] == flying -> MarkerStyle.CURRENT
                            else -> MarkerStyle.WAYPOINT
                        }
                        add(MapOverlay.Marker(markerId(g, i), at, seqs[i].toString(), style, draggable = item.editable))
                    }
                }
                is SurveyGroup -> {
                    add(MapOverlay.Polygon(group.survey.polygon, selected = g == e.selectedGroup))
                    // Corners are handles for the survey being edited only; others show just their outline.
                    if (g == e.selectedGroup) group.survey.polygon.forEachIndexed { i, at ->
                        val style = if (i == e.selectedItem) MarkerStyle.SELECTED else MarkerStyle.CORNER
                        add(MapOverlay.Marker(cornerId(g, i), at, "", style, draggable = true))
                    }
                }
            }
        }
    }

    val waypoints = selected?.group as? WaypointGroup
    val rows = waypoints?.let { group ->
        val seqs = seqNumbers(group.items).map { it + selected.firstSeq - 1 }
        group.items.mapIndexed { i, item -> PlanRow(i, seqs[i], title(item), detail(item), i == e.selectedItem) }
    } ?: emptyList()

    val onVehicle = sync.onVehicle
    val syncUi = when {
        onVehicle == null && flat.items.isEmpty() -> null
        onVehicle != null && sameMission(flat.items, onVehicle) ->
            if (sync.differsFrom(vehicle.mission?.total) && vehicle.connected) SyncUi("Vehicle mission changed elsewhere", true)
            else SyncUi("On vehicle", false)
        else -> SyncUi("Not uploaded", true)
    }

    val surveyGroup = selected?.group as? SurveyGroup
    return PlanUiState(
        groups = flat.groups.mapIndexed { i, fg ->
            GroupHeader(i, fg.group.name, if (fg.group is SurveyGroup) "Survey" else "Waypoints", groupSummary(fg, kind), i == e.selectedGroup)
        },
        rows = rows,
        form = e.form,
        survey = surveyGroup?.let { surveyPanel(it, selected, e.selectedItem, kind, settings) },
        overlays = overlays,
        transfer = e.transfer,
        canUpload = !busy && vehicle.connected && flat.items.isNotEmpty(),
        canRead = !busy && vehicle.connected,
        canClear = !busy && vehicle.connected,
        clearWarning = armedIn?.let { "$it: clearing deletes the mission it is flying." }
            ?: "This deletes the mission on the vehicle and empties the editor.",
        hint = when {
            !vehicle.connected -> "No vehicle: plan now, connect on Links to upload."
            selected == null -> "Add a group: + Waypoints or + Survey."
            surveyGroup != null && surveyGroup.survey.polygon.size < 3 -> "Click the map to add the area's corners (at least 3)."
            surveyGroup != null -> "Click the map to add a corner, drag one to move it."
            rows.isEmpty() -> "Click the map to add waypoints."
            else -> "Drag a numbered marker to move it. Click one to edit it."
        },
        sync = syncUi,
        canUndo = e.past.isNotEmpty(),
        canRedo = e.future.isNotEmpty(),
        preview = e.preview,
    )
}

/** The preview: every item with its seq, and the warnings (survey checks, armed vehicle, unknown home). */
internal fun uploadPreview(e: PlanEdit, vehicle: VehicleState, settings: PlanSettings): UploadPreview {
    val flat = flatten(e.groups, vehicle.vehicleKind, settings.limitsFor(vehicle.vehicleKind))
    val lines = listOf("0  Home (the vehicle's own, sent as seq 0)") + flat.items.mapIndexed { i, item -> "${i + 1}  ${itemLine(item)}" }
    val warnings = buildList {
        if (vehicle.connected && vehicle.armed) add("Vehicle is ARMED in ${vehicle.flightMode?.uppercase() ?: "an unknown mode"}: uploading replaces the mission it is flying.")
        if (vehicle.home == null) add("Home isn't known yet: the upload will fail until the vehicle has a GPS fix.")
        flat.groups.forEach { fg ->
            fg.error?.let { add("${fg.group.name}: not included. $it") }
            fg.plan?.warnings?.forEach { add("${fg.group.name}: ${warningText(it)}") }
        }
    }
    return UploadPreview(lines, warnings)
}

private fun surveyPanel(group: SurveyGroup, flat: FlatGroup, selectedItem: Int?, kind: VehicleKind?, settings: PlanSettings): SurveyPanel {
    val plan = flat.plan
    val s = group.survey
    val stats = plan?.let { p ->
        listOf(
            "Area" to "${oneDecimalText(p.stats.areaM2 / 10_000)} ha",
            "Lines" to "${p.stats.lineCount}${if (p.grid.lineSkip > 1) ", flown every ${ordinal(p.grid.lineSkip)} line" else ""}",
            "Line spacing" to "${oneDecimalText(p.lineSpacingM)} m",
            "Photo every" to "${oneDecimalText(p.triggerDistanceM)} m (${oneDecimalText(p.triggerDistanceM / s.speedMs)} s)",
            "Photos" to "${p.stats.photoCount}",
            "Distance" to distanceText(p.stats.distanceM),
            "Flight time" to durationText(p.stats.flightTimeS),
            "Batteries" to (p.batteries?.toString() ?: "set the battery time below"),
            "Data" to if (p.dataMb > 0) dataText(p.dataMb) else "–",
        )
    } ?: emptyList()
    return SurveyPanel(
        settings = s,
        cameras = settings.cameras.map { CameraChoice(it.name, it.unverified, it in settings.customCameras) },
        isPlane = kind == VehicleKind.PLANE,
        altitudeM = plan?.altitudeM,
        gsdCm = plan?.gsdM?.times(100),
        stats = stats,
        warnings = plan?.warnings?.map(::warningText) ?: emptyList(),
        error = flat.error,
        cornerCount = s.polygon.size,
        cornerSelected = selectedItem != null && selectedItem in s.polygon.indices,
        batteryMinutes = settings.batteryMinutes[kind.orCopter()],
        maxGsdCm = settings.maxGsdCm,
    )
}

internal fun warningText(w: SurveyWarning): String = when (w) {
    is SurveyWarning.PhotoIntervalTooShort ->
        "photos would be ${oneDecimalText(w.intervalS)} s apart, but the camera needs ${oneDecimalText(w.minIntervalS)} s. " +
            "Fly at most ${oneDecimalText(w.maxSpeedMs)} m/s, or lower the front overlap."
    is SurveyWarning.GsdAboveLimit -> "GSD ${oneDecimalText(w.gsdM * 100)} cm/px is coarser than your ${oneDecimalText(w.limitM * 100)} cm/px limit."
    is SurveyWarning.PlaneLoopTurns -> "${w.count} turns are tighter than the plane's turn radius and need a loop. Widen the spacing or add lines."
}

private fun ordinal(n: Int) = when (n) { 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

private fun dataText(mb: Double) = if (mb < 1000) "${mb.roundToInt()} MB" else "${oneDecimalText(mb / 1000)} GB"

/** One preview line: "Waypoint · -35.36210, 149.16600 · 50.0 m", "Camera every 20.0 m", … */
private fun itemLine(item: MissionItem): String = when (item.command) {
    MissionCommand.DO_SET_CAM_TRIGG_DIST -> if (item.param1 > 0f) "Camera: a photo every ${oneDecimalText(item.param1.toDouble())} m" else "Camera: stop"
    MissionCommand.DO_CHANGE_SPEED -> "Change speed · ${oneDecimalText(item.param2.toDouble())} m/s"
    else -> listOfNotNull(
        title(PlanItem(item.command)),
        item.position?.let { "${fiveDecimals(it.latitude)}, ${fiveDecimals(it.longitude)}" },
        item.position?.let { "${oneDecimalText(item.altitudeM)} m" } ?: item.altitudeM.takeIf { it > 0 }?.let { "${oneDecimalText(it)} m" },
    ).joinToString(" · ")
}

private fun fiveDecimals(v: Double) = ((v * 100_000).roundToInt() / 100_000.0).toString()

internal fun title(item: PlanItem): String = when (item.command) {
    MissionCommand.TAKEOFF -> "Takeoff"
    MissionCommand.WAYPOINT -> "Waypoint"
    MissionCommand.RETURN_TO_LAUNCH -> "Return to launch"
    MissionCommand.LAND -> "Land"
    MissionCommand.DO_CHANGE_SPEED -> "Change speed"
    MissionCommand.DO_SET_CAM_TRIGG_DIST -> "Camera trigger"
    else -> "Command ${item.command}"
}

internal fun detail(item: PlanItem): String {
    if (!item.editable) return "read from vehicle"
    val speed = item.speedMs?.let { " · ${oneDecimalText(it)} m/s" } ?: ""
    return "${oneDecimalText(item.altitudeM)} m$speed"
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
    ItemForm(index, title(item), oneDecimalText(item.altitudeM), item.speedMs?.let(::oneDecimalText) ?: "")
