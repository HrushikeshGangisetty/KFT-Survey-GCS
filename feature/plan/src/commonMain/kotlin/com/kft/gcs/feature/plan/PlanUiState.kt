package com.kft.gcs.feature.plan

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.SurveyWarning
import com.kft.gcs.core.planning.SurveyPlan
import com.kft.gcs.core.planning.MIN_CORRIDOR_SIDE_OVERLAP
import com.kft.gcs.core.planning.corridorOutline
import com.kft.gcs.core.planning.entryCornerPositions
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionSyncState
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.sameMission
import com.kft.gcs.core.vehicle.vehicleLabels
import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.MarkerStyle
import com.kft.gcs.ui.map.RouteStyle
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
    /** "Import as survey areas or waypoints?", after a file is read. */
    val importDialog: ImportDialog? = null,
    /** Where the map should look: set after an import, so the imported area is on screen. */
    val cameraRequest: CameraRequest? = null,
    /** The job's missions with their vehicles and checks; empty while the plan is one mission. */
    val missions: List<MissionRow> = emptyList(),
    /** The vehicles a mission can be given to. */
    val vehicleChoices: List<VehicleChoice> = emptyList(),
    /** The upload-check limits, for the settings fields. */
    val maxAltitudeM: Double? = null,
    val maxDistanceM: Double? = null,
)

/** @property mission "M2" when the plan has several missions; [missionChoices] are the missions the group can move to. */
data class GroupHeader(
    val index: Int, val name: String, val kind: String, val summary: String, val selected: Boolean,
    val mission: String? = null, val missionChoices: List<Pair<Int, String>> = emptyList(),
)

/**
 * One mission of a job. [vehicle] is who flies it ("Vehicle 2", "Not assigned"); [problems] are the upload checks
 * that didn't pass, already as sentences.
 */
data class MissionRow(val id: Int, val tag: String, val name: String, val summary: String, val vehicle: String, val problems: List<String>)

data class VehicleChoice(val id: VehicleId, val label: String)

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
    /** "south-west": where the chosen start corner is, as seen on the map; null until the survey can be planned. */
    val startCorner: String?,
)

data class CameraChoice(val name: String, val unverified: Boolean, val custom: Boolean)

/**
 * What an upload will send, one line per item, seq 0 (home) first, plus everything worth a second look. For a job,
 * each mission is a block of [lines] under a heading that names its vehicle.
 */
data class UploadPreview(val lines: List<String>, val warnings: List<String>, val title: String = "Upload ${lines.size - 1} items?")

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
    val importChoice: ImportChoice? = null,
    val camera: CameraRequest? = null,
    /** Which vehicle each mission is for, by mission number ([MissionGroup.mission]). Not part of the undo history. */
    val assignments: Map<Int, VehicleId> = emptyMap(),
)

/** Marker ids for the map: the group index and the row, corner or [EntryCorner] index. */
internal const val HOME_MARKER = "home"
internal fun markerId(group: Int, index: Int) = "wp-$group-$index"
internal fun cornerId(group: Int, index: Int) = "corner-$group-$index"
internal fun entryId(group: Int, entry: EntryCorner) = "entry-$group-${entry.ordinal}"

/** (kind, group, index) from a marker id, or null for home or a foreign id. */
internal fun parseMarker(id: String): Triple<String, Int, Int>? {
    val parts = id.split('-')
    if (parts.size != 3 || parts[0] !in setOf("wp", "corner", "entry")) return null
    return Triple(parts[0], parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
}

/** The survey warnings and battery estimate use the settings for the vehicle kind; unknown counts as a copter. */
internal fun PlanSettings.limitsFor(kind: VehicleKind?) = SurveyLimits(
    usableFlightTimeS = batteryMinutes[kind.orCopter()]?.let { it * 60 },
    maxGsdM = maxGsdCm?.let { it / 100 },
)

internal fun VehicleKind?.orCopter() = if (this == VehicleKind.PLANE) VehicleKind.PLANE else VehicleKind.COPTER

/** The vehicle a mission will be uploaded to: the one it is assigned to, or (a plan with one mission) the selected one. */
internal fun targetOf(mission: Int, ids: List<Int>, assignments: Map<Int, VehicleId>, selected: VehicleId?): VehicleId? =
    assignments[mission] ?: selected.takeIf { ids.size == 1 }

/** The checks for one mission flown by a vehicle in [state] (its home, kind and fence). */
internal fun checkMission(items: List<MissionItem>, state: VehicleState, settings: PlanSettings): List<String> {
    val kind = state.vehicleKind
    val limits = MissionLimits(settings.maxAltitudeM, settings.maxDistanceM, settings.batteryMinutes[kind.orCopter()]?.let { it * 60 }, state.fence)
    return missionProblems(estimateMission(items, state.home?.position, kind), limits)
}

/**
 * Pure: editor data, the selected vehicle, settings, sync state and the fleet in, screen state out.
 * [vehicle] is the selected vehicle's state; [fleet] is every vehicle, for a job's assignments (empty in a
 * single-vehicle setup and in most tests).
 */
internal fun buildPlanUiState(e: PlanEdit, vehicle: VehicleState, settings: PlanSettings, sync: MissionSyncState, fleet: List<FleetVehicle> = emptyList()): PlanUiState {
    val kind = vehicle.vehicleKind
    val flat = flatten(e.groups, kind, settings.limitsFor(kind))
    val busy = e.transfer != null
    val ids = flat.missions
    // The mission the selected vehicle is to fly: its progress and its "on vehicle" state are about that one.
    val own = missionFor(sync.selected, ids, e.assignments)
    val flying = vehicle.mission?.takeIf { !it.complete }?.current
    val home = vehicle.home?.position
    val labels = vehicleLabels(fleet.map { it.id })
    fun stateOf(mission: Int): VehicleState? = when (val id = targetOf(mission, ids, e.assignments, sync.selected)) {
        null -> vehicle.takeIf { ids.size == 1 }
        sync.selected -> vehicle
        else -> fleet.find { it.id == id }?.state
    }
    val selected = e.selectedGroup?.let(flat.groups::getOrNull)
    val surveyGroup = selected?.group as? SurveyGroup
    val starts = selected?.plan?.takeIf { surveyGroup?.survey?.isCorridor == false }?.let { plan ->
        // Half a line spacing out: off the corner handles, and scaled to the survey like everything else on it.
        surveyGroup?.let { entryCornerPositions(it.survey.polygon, it.survey.gridAngleDeg, outsetM = plan.lineSpacingM / 2) }
    } ?: emptyMap()
    // "AUTO", not "Auto": the warning names the mode the way the pilot's OSD and MAVProxy show it.
    val armedIn = if (vehicle.connected && vehicle.armed) "Vehicle is ARMED in ${vehicle.flightMode?.uppercase() ?: "an unknown mode"}" else null

    val overlays = buildList<MapOverlay> {
        // Each mission's path starts and ends at its own vehicle's home.
        val homes = ids.associateWith { stateOf(it)?.home?.position }
        ids.forEach { addAll(routeOverlays(homes[it], flat.only(it))) }
        home?.let { add(MapOverlay.Marker(HOME_MARKER, it, "H", MarkerStyle.HOME)) }
        homes.values.filterNotNull().filter { it != home }.distinct().forEachIndexed { i, at -> add(MapOverlay.Marker("$HOME_MARKER-other$i", at, "H", MarkerStyle.HOME)) }
        flat.groups.forEachIndexed { g, fg ->
            when (val group = fg.group) {
                is WaypointGroup -> {
                    val seqs = seqNumbers(group.items).map { it + fg.firstSeq - 1 }
                    group.items.forEachIndexed { i, item ->
                        val at = item.position ?: return@forEachIndexed
                        val style = when {
                            g == e.selectedGroup && i == e.selectedItem -> MarkerStyle.SELECTED
                            seqs[i] == flying && group.mission == own -> MarkerStyle.CURRENT
                            else -> MarkerStyle.WAYPOINT
                        }
                        add(MapOverlay.Marker(markerId(g, i), at, seqs[i].toString(), style, draggable = item.editable))
                    }
                }
                is SurveyGroup -> {
                    // An area's outline is its corners; a corridor's is its edges, worked out from the centre line.
                    val outline = group.survey.corridorShape()?.let { corridorOutline(group.survey.polygon, it) } ?: group.survey.polygon.takeIf { !group.survey.isCorridor }
                    outline?.let { add(MapOverlay.Polygon(it, selected = g == e.selectedGroup)) }
                    // Where the survey starts and ends: its first and last position (entry of the first line, exit
                    // of the last). Labels only; the map doesn't let them be grabbed.
                    val path = fg.items.mapNotNull { it.position }
                    path.firstOrNull()?.let { add(MapOverlay.Marker("survey-start-$g", it, "S", MarkerStyle.START)) }
                    path.lastOrNull()?.let { add(MapOverlay.Marker("survey-end-$g", it, "E", MarkerStyle.END)) }
                    // Corners are handles for the survey being edited only; others show just their outline.
                    if (g == e.selectedGroup) group.survey.polygon.forEachIndexed { i, at ->
                        val style = if (i == e.selectedItem) MarkerStyle.SELECTED else MarkerStyle.CORNER
                        add(MapOverlay.Marker(cornerId(g, i), at, "", style, draggable = true))
                    }
                    // The start corner is chosen on the map: the chosen one highlighted, the others tappable.
                    if (g == e.selectedGroup) starts.forEach { (entry, at) ->
                        val style = if (entry == group.survey.entry) MarkerStyle.START_CORNER else MarkerStyle.START_OPTION
                        add(MapOverlay.Marker(entryId(g, entry), at, "", style))
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
    val ownItems = own?.let { flat.only(it).items } ?: emptyList()
    val syncUi = when {
        onVehicle == null && ownItems.isEmpty() -> null
        onVehicle != null && sameMission(ownItems, onVehicle) ->
            if (sync.differsFrom(vehicle.mission?.total) && vehicle.connected) SyncUi("Vehicle mission changed elsewhere", true)
            else SyncUi("On vehicle", false)
        else -> SyncUi("Not uploaded", true)
    }

    return PlanUiState(
        groups = flat.groups.mapIndexed { i, fg ->
            // The mission tag and "move to" menu appear once there is something to tell apart or to move between.
            val choices = if (e.groups.size > 1) ids.map { it to missionTag(ids, it) } else emptyList()
            GroupHeader(i, fg.group.name, groupKind(fg.group), groupSummary(fg, kind), i == e.selectedGroup, missionTag(ids, fg.group.mission).takeIf { ids.size > 1 }, choices)
        },
        rows = rows,
        form = e.form,
        survey = surveyGroup?.let { surveyPanel(it, selected, e.selectedItem, kind, settings, starts[it.survey.entry]) },
        overlays = overlays,
        transfer = e.transfer,
        canUpload = !busy && flat.items.isNotEmpty() && (vehicle.connected || fleet.any { it.state.connected && it.id in e.assignments.values }),
        canRead = !busy && vehicle.connected,
        canClear = !busy && vehicle.connected,
        clearWarning = armedIn?.let { "$it: clearing deletes the mission it is flying." }
            ?: "This deletes the mission on the vehicle and empties the editor.",
        hint = when {
            !vehicle.connected -> "No vehicle: plan now, connect on Links to upload."
            selected == null -> "Add a group: + Waypoints or + Survey."
            surveyGroup != null && surveyGroup.survey.isCorridor && surveyGroup.survey.polygon.size < 2 -> "Click the map to draw the corridor's centre line, point by point."
            surveyGroup != null && surveyGroup.survey.isCorridor -> "Click the map to extend the centre line, drag a point to move it."
            surveyGroup != null && surveyGroup.survey.polygon.size < 3 -> "Click the map to add the area's corners (at least 3)."
            surveyGroup != null -> "Click the map to add a corner, drag one to move it. Tap a grey dot to start there."
            rows.isEmpty() -> "Click the map to add waypoints."
            else -> "Drag a numbered marker to move it. Click one to edit it."
        },
        sync = syncUi,
        canUndo = e.past.isNotEmpty(),
        canRedo = e.future.isNotEmpty(),
        preview = e.preview,
        importDialog = e.importChoice?.dialog(),
        cameraRequest = e.camera,
        missions = if (ids.size < 2) emptyList() else ids.map { m ->
            val items = flat.only(m).items
            val state = stateOf(m)
            val estimate = estimateMission(items, state?.home?.position, kind)
            MissionRow(
                id = m,
                tag = missionTag(ids, m),
                name = missionName(e.groups, m),
                summary = "${items.size} items · ${distanceText(estimate.distanceM)} · ≈${durationText(estimate.timeS)}",
                vehicle = e.assignments[m]?.let { "Vehicle ${labels[it] ?: it.systemId}" + if (fleet.none { v -> v.id == it }) " (not connected)" else "" } ?: "Not assigned",
                problems = if (state == null) emptyList() else checkMission(items, state, settings),
            )
        },
        vehicleChoices = fleet.map { VehicleChoice(it.id, "Vehicle ${labels.getValue(it.id)}") },
        maxAltitudeM = settings.maxAltitudeM,
        maxDistanceM = settings.maxDistanceM,
    )
}

/**
 * The planned path as styled pieces ([RouteStyle]), walking the items in flight order and tracking the camera:
 * - The first leg, from home, and the leg back to home for a RETURN_TO_LAUNCH item: TRANSIT.
 * - A leg flown while DO_SET_CAM_TRIGG_DIST has the camera on (it runs at the NAV item before it, so the camera is
 *   on from a line's photoStart to its camera-off point, see [surveyItems]): PHOTO.
 * - Any other leg that ends at a survey item (run-in, run-out, lead-in, the turn to the next line): TURN.
 * - Legs between hand-placed waypoints: PLAN.
 * Neighbouring legs of the same style are joined into one line, so a survey is a few long lines rather than
 * hundreds of two-point ones. Without a known home the path starts at the first item.
 */
internal fun routeOverlays(home: LatLon?, flat: FlatMission): List<MapOverlay.Route> {
    val routes = mutableListOf<MapOverlay.Route>()
    fun leg(from: LatLon, to: LatLon, style: RouteStyle) {
        val last = routes.lastOrNull()
        if (last != null && last.style == style && last.points.last() == from) routes[routes.lastIndex] = last.copy(points = last.points + to)
        else routes += MapOverlay.Route(listOf(from, to), style)
    }
    var at = home
    var firstLeg = true
    var cameraOn = false
    flat.groups.forEach { fg ->
        val survey = fg.group is SurveyGroup
        fg.items.forEach { item ->
            when {
                item.command == MissionCommand.DO_SET_CAM_TRIGG_DIST -> cameraOn = item.param1 > 0f
                item.command == MissionCommand.RETURN_TO_LAUNCH -> {
                    val from = at
                    if (from != null && home != null) leg(from, home, RouteStyle.TRANSIT)
                    at = home
                }
                else -> item.position?.let { to ->
                    val from = at
                    if (from != null) {
                        val style = when {
                            firstLeg && from == home -> RouteStyle.TRANSIT
                            cameraOn -> RouteStyle.PHOTO
                            survey -> RouteStyle.TURN
                            else -> RouteStyle.PLAN
                        }
                        leg(from, to, style)
                        firstLeg = false
                    }
                    at = to
                }
            }
        }
    }
    return routes
}

/** One mission of an upload: where it goes and what is sent. [vehicle] null = the selected vehicle (a one-mission plan). */
internal data class UploadTarget(val mission: Int, val label: String, val vehicle: VehicleId?, val items: List<MissionItem>)

/**
 * What Upload will send where: every mission that has items and a vehicle. In a job, a mission without a vehicle is
 * left out (the preview says so); a plan with one unassigned mission goes to the selected vehicle.
 */
internal fun uploadTargets(e: PlanEdit, flat: FlatMission, selected: VehicleId?): List<UploadTarget> {
    val ids = flat.missions
    return ids.mapNotNull { m ->
        val items = flat.only(m).items.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val vehicle = e.assignments[m]
        if (vehicle == null && ids.size > 1) null else UploadTarget(m, "${missionTag(ids, m)} ${missionName(e.groups, m)}", vehicle, items)
    }
}

/**
 * The preview: every item with its seq, and the warnings (survey checks, armed vehicle, unknown home, and the
 * per-mission checks of [checkMission]). With several missions, each is a block headed by its vehicle, and each
 * warning starts with its mission.
 */
internal fun uploadPreview(e: PlanEdit, vehicle: VehicleState, settings: PlanSettings, selected: VehicleId? = null, fleet: List<FleetVehicle> = emptyList()): UploadPreview {
    val flat = flatten(e.groups, vehicle.vehicleKind, settings.limitsFor(vehicle.vehicleKind))
    val ids = flat.missions
    val job = ids.size > 1
    val labels = vehicleLabels(fleet.map { it.id })
    val lines = mutableListOf<String>()
    val warnings = mutableListOf<String>()
    for (m in ids) {
        val mission = flat.only(m)
        val name = "${missionTag(ids, m)} ${missionName(e.groups, m)}"
        val prefix = if (job) "$name: " else ""
        val target = e.assignments[m]
        val state = if (target == null || target == selected) vehicle.takeIf { target != null || !job } else fleet.find { it.id == target }?.state
        if (job) {
            lines += "— $name → ${target?.let { "vehicle ${labels[it] ?: it.systemId}" } ?: "no vehicle"} · ${mission.items.size} items —"
            if (target == null) warnings += "${prefix}no vehicle assigned: it will not be uploaded."
            else if (state?.connected != true) warnings += "${prefix}its vehicle is not connected: the upload will fail."
        }
        lines += "0  Home (the vehicle's own, sent as seq 0)"
        lines += mission.items.mapIndexed { i, item -> "${i + 1}  ${itemLine(item)}" }
        if (state != null) {
            if (state.connected && state.armed) warnings += "${prefix}Vehicle is ARMED in ${state.flightMode?.uppercase() ?: "an unknown mode"}: uploading replaces the mission it is flying."
            if (state.home == null) warnings += "${prefix}Home isn't known yet: the upload will fail until the vehicle has a GPS fix."
            // The job is planned for the selected vehicle's kind (takeoff item, turns). ponytail: one kind per job;
            // plan a mixed copter/plane job as two plans until someone flies one.
            val (plannedFor, flownBy) = vehicle.vehicleKind to state.vehicleKind
            if (plannedFor != null && flownBy != null && plannedFor != flownBy) {
                warnings += "${prefix}planned for a ${plannedFor.name.lowercase()}, but its vehicle is a ${flownBy.name.lowercase()}. Select that vehicle and check the plan."
            }
        }
        mission.groups.forEach { fg ->
            fg.error?.let { warnings += "${fg.group.name}: not included. $it" }
            fg.plan?.warnings?.forEach { warnings += "${fg.group.name}: ${warningText(it)}" }
        }
        if (state != null) checkMission(mission.items, state, settings).forEach { warnings += "${prefix.ifEmpty { "The mission " }}$it" }
    }
    val sent = uploadTargets(e, flat, selected).size
    return if (job) UploadPreview(lines, warnings, "Upload $sent of ${ids.size} missions?") else UploadPreview(lines, warnings)
}

private fun surveyPanel(group: SurveyGroup, flat: FlatGroup, selectedItem: Int?, kind: VehicleKind?, settings: PlanSettings, start: LatLon?): SurveyPanel {
    val plan = flat.plan
    val s = group.survey
    val stats = plan?.let { p ->
        listOf(
            "Area" to "${oneDecimalText(p.stats.areaM2 / 10_000)} ha",
            "Lines" to linesText(p),
            "Line spacing" to "${oneDecimalText(p.lineSpacingM)} m",
            // A corridor's side overlap follows from its width and line count, so it's shown, not typed.
            *(if (s.isCorridor) arrayOf("Side overlap" to "${(100 * (1 - p.lineSpacingM / p.footprint.acrossM)).roundToInt()} %") else emptyArray()),
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
        startCorner = start?.let { compassWord(Geodesy.initialBearingDeg(centroid(s.polygon), it)) },
    )
}

/** The mean corner: good enough as "the middle" of a survey area to say which way a corner lies from it. */
private fun centroid(polygon: List<LatLon>) = LatLon(polygon.sumOf { it.latitude } / polygon.size, polygon.sumOf { it.longitude } / polygon.size)

private val COMPASS = listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")

/** A bearing in [0, 360) as one of 8 compass words; each covers ±22.5° around its direction. */
internal fun compassWord(bearingDeg: Double) = COMPASS[((bearingDeg + 22.5) / 45).toInt() % 8]

internal fun warningText(w: SurveyWarning): String = when (w) {
    is SurveyWarning.PhotoIntervalTooShort ->
        "photos would be ${oneDecimalText(w.intervalS)} s apart, but the camera needs ${oneDecimalText(w.minIntervalS)} s. " +
            "Fly at most ${oneDecimalText(w.maxSpeedMs)} m/s, or lower the front overlap."
    is SurveyWarning.GsdAboveLimit -> "GSD ${oneDecimalText(w.gsdM * 100)} cm/px is coarser than your ${oneDecimalText(w.limitM * 100)} cm/px limit."
    is SurveyWarning.PlaneLoopTurns -> "${w.count} turns are tighter than the plane's turn radius and need a loop. Widen the spacing or add lines."
    is SurveyWarning.CorridorSideOverlapLow ->
        "the lines give only ${(w.overlap * 100).roundToInt()} % side overlap (${(MIN_CORRIDOR_SIDE_OVERLAP * 100).roundToInt()} % is the least for mapping). Add lines or fly higher."
}

/** "Survey", "Corridor" or "Waypoints", for the group list. */
internal fun groupKind(group: MissionGroup) = when (group) {
    is WaypointGroup -> "Waypoints"
    is SurveyGroup -> if (group.survey.isCorridor) "Corridor" else "Survey"
}

/** "7", "7, flown every 2nd line", or with a crosshatch "7 + 5" (first pass + crossing pass). */
private fun linesText(p: SurveyPlan): String {
    val first = "${p.grid.lineCount}${if (p.grid.lineSkip > 1) ", flown every ${ordinal(p.grid.lineSkip)} line" else ""}"
    return p.crosshatch?.let { "${p.grid.lineCount} + ${it.grid.lineCount}" } ?: first
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
