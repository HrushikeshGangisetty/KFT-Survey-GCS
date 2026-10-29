package com.kft.gcs.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.decodeQgcPlan
import com.kft.gcs.core.geoio.decodeWaypoints
import com.kft.gcs.core.geoio.encodeQgcPlan
import com.kft.gcs.core.geoio.encodeWaypoints
import com.kft.gcs.core.geoio.importGeometry
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.planning.altitudeForGsdM
import com.kft.gcs.core.planning.gsdM
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.terrain.Elevations
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.vehicleLabels
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The survey panel's number fields, each with the range it accepts. */
enum class SurveyField(val range: ClosedFloatingPointRange<Double>) {
    ALTITUDE(1.0..1000.0),
    GSD(0.1..100.0),
    SIDE_OVERLAP(0.0..95.0),
    FRONT_OVERLAP(0.0..95.0),
    GRID_ANGLE(-360.0..360.0),
    SPEED(1.0..50.0),
    TURNAROUND(0.0..500.0),
    CROSSHATCH_OFFSET(-500.0..500.0),
    LEFT_WIDTH(0.0..2000.0),
    RIGHT_WIDTH(0.0..2000.0),
    CORRIDOR_LINES(1.0..50.0),
}

enum class ExportFormat(val extension: String) { QGC_PLAN("plan"), WAYPOINTS("waypoints") }

/**
 * The Plan screen: a mission made of groups (waypoints, surveys), edited on the shared map and in the side panel,
 * with undo/redo, an upload preview, and plan files. It never flies anything: uploading only stores the mission, and
 * the pilot starts it by switching to AUTO on the RC (spec S9).
 *
 * @param vehicle the selected vehicle's live state (home, vehicle kind, mission progress, connected).
 * @param missions transfers with the selected vehicle. It also notes in [sync] what that vehicle holds after each
 *   one (`Fleet.missions`), because only it knows which vehicle a transfer went to (ADR-005 §5).
 * @param sync shared with the Fly view: this screen writes the plan into it.
 * @param fleet every vehicle, for a job: which vehicles a mission can be given to, and each one's home and fence.
 * @param missionsOf transfers with one named vehicle (`Fleet.missionsOf`), for a job's upload.
 * @param records the progress of every uploaded mission, saved as it happens. This screen starts a record after
 *   each upload and builds the mission for what is left of a stopped one. Null: nothing is recorded (most tests).
 * @param elevations ground heights for the terrain profile. Null: no profile (most tests).
 */
class PlanViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val missions: MissionRepository,
    private val sync: MissionSync,
    private val settings: PlanSettingsRepository,
    private val files: PlanFiles,
    private val fleet: StateFlow<List<FleetVehicle>> = MutableStateFlow(emptyList()),
    private val missionsOf: (VehicleId) -> MissionRepository = { missions },
    private val records: MissionRecords? = null,
    private val elevations: Elevations? = null,
) : ViewModel(), PlanActions {

    private val edit = MutableStateFlow(PlanEdit())
    private var transferJob: Job? = null

    private val _effects = Channel<String>(Channel.BUFFERED)

    /** One-shot messages for a snackbar: transfer and file results, and errors. */
    val effects: Flow<String> = _effects.receiveAsFlow()

    // The stopped missions and their photographed lines change only when a record does (about once per photo), so
    // they are worked out here once, not on every telemetry update like the rest of the state.
    private val flown: StateFlow<Flown> = (records?.records ?: MutableStateFlow(emptyList()))
        .let { r -> combine(r, fleet, settings.settings, ::flownOf) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Flown())

    private val terrain = MutableStateFlow<TerrainUi?>(null)

    val state: StateFlow<PlanUiState> =
        // Terrain rides in the inner combine with the other slow inputs, so an edit still reaches the screen in one hop.
        combine(edit, vehicle, settings.settings, sync.state, combine(fleet, flown, terrain, ::Triple)) { e, v, s, sy, (f, fl, t) ->
            buildPlanUiState(e, v, s, sy, f, fl).copy(terrain = t)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildPlanUiState(edit.value, vehicle.value, settings.settings.value, sync.state.value, fleet.value))

    // "The plan" is the mission meant for the selected vehicle, so it follows the selection and the assignments.
    private val planned: Flow<FlatMission> = combine(
        edit.map { it.groups to it.assignments }.distinctUntilChanged(),
        vehicle.map { it.vehicleKind }.distinctUntilChanged(),
        settings.settings,
        sync.state.map { it.selected }.distinctUntilChanged(),
    ) { (groups, assignments), kind, s, selected ->
        val flat = flatten(groups, kind, s.limitsFor(kind))
        missionFor(selected, flat.missions, assignments)?.let(flat::only) ?: FlatMission(emptyList())
    }

    init {
        // Keep the shared plan (for Fly's "≠ plan" warning and photo count) up to date, even while this tab is hidden.
        viewModelScope.launch { planned.collect { sync.planChanged(it.items, it.plannedPhotos) } }
        elevations?.let { source -> viewModelScope.launch { followTerrain(source) } }
    }

    /**
     * Keeps [terrain] in step with the plan. Debounced, so dragging a waypoint asks the elevation source once when the
     * drag settles rather than on every frame; collectLatest drops a lookup that a newer edit has made stale.
     */
    @OptIn(FlowPreview::class)
    private suspend fun followTerrain(source: Elevations) {
        combine(planned.map { it.items }.distinctUntilChanged(), vehicle.map { it.home }.distinctUntilChanged(), ::Pair)
            .debounce(TERRAIN_DEBOUNCE_MS)
            .collectLatest { (items, home) ->
                val points = pathPoints(home?.position, items)
                if (points.isEmpty()) {
                    terrain.value = null
                    return@collectLatest
                }
                // Keep the old chart while loading, so an edit doesn't make the panel blink.
                terrain.update { TerrainUi(it?.profile, "Loading terrain…") }
                terrain.value = try {
                    TerrainUi(terrainProfile(points, source.elevationsM(points.map { it.at }), home?.altitudeMslM), null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TerrainUi(null, "Terrain unavailable: ${e.message ?: "no answer from the elevation server"}")
                }
            }
    }

    // ---- map ----

    fun onMapClick(at: LatLon) = edit.update { e ->
        val g = e.selectedGroup ?: return@update e
        when (val group = e.groups[g]) {
            is WaypointGroup -> {
                val items = group.items.addWaypoint(at, vehicle.value.vehicleKind, startsMission = e.groups.take(g).none { it.mission == group.mission && hasItems(it) })
                e.changed(null, e.groups.replace(g, group.copy(items = items))).selectRow(items.lastIndex)
            }
            is SurveyGroup -> {
                // A corridor's centre line grows at its end, in click order; an area's corner goes into the nearest side.
                val polygon = if (group.survey.isCorridor) group.survey.polygon + at else insertCorner(group.survey.polygon, at)
                e.changed(null, e.groups.replace(g, group.withSurvey { it.copy(polygon = polygon) }))
                    .copy(selectedItem = polygon.indexOf(at), form = null)
            }
        }
    }

    /** A waypoint or corner: select it. A start-corner option (shown only on the selected survey): start there. */
    fun onMarkerClick(id: String) {
        val (kind, g, i) = parseMarker(id) ?: return
        if (kind == "entry") {
            EntryCorner.entries.getOrNull(i)?.let(::onEntrySelected)
            return
        }
        edit.update { e ->
            val selected = e.copy(selectedGroup = g, lastEdit = null)
            if (kind == "wp") selected.selectRow(i) else selected.copy(selectedItem = i, form = null)
        }
    }

    fun onMarkerDragged(id: String, to: LatLon) = edit.update { e ->
        val (kind, g, i) = parseMarker(id) ?: return@update e
        when (val group = e.groups.getOrNull(g)) {
            is WaypointGroup -> {
                val item = group.items.getOrNull(i)?.takeIf { it.editable } ?: return@update e
                e.changed("drag $id", e.groups.replace(g, group.copy(items = group.items.replace(i, item.copy(position = to)))))
                    .copy(selectedGroup = g).selectRow(i)
            }
            is SurveyGroup -> {
                if (kind != "corner" || i !in group.survey.polygon.indices) return@update e
                e.changed("drag $id", e.groups.replace(g, group.withSurvey { it.copy(polygon = it.polygon.replace(i, to)) }))
                    .copy(selectedGroup = g, selectedItem = i)
            }
            null -> e
        }
    }

    /** The finger or mouse let go: the next drag, even of the same marker, is a new undo step. */
    fun onMarkerDragFinished() = edit.update { it.copy(lastEdit = null) }

    // ---- groups ----

    override fun onAddWaypointsClicked() = addGroup(WaypointGroup(nextName("Waypoints")))

    override fun onAddSurveyClicked() = addGroup(SurveyGroup(nextName("Survey"), defaultSurvey(vehicle.value.vehicleKind, settings.settings.value.cameras.first())))

    /** A corridor: the survey defaults, 25 m each side and 3 lines; the panel shows the side overlap that gives. */
    override fun onAddCorridorClicked() = addGroup(
        SurveyGroup(nextName("Corridor"), defaultSurvey(vehicle.value.vehicleKind, settings.settings.value.cameras.first()).copy(pattern = SurveyPattern.CORRIDOR)),
    )

    override fun onCrosshatchChanged(on: Boolean) = editSurvey(null) { s, _ -> s.copy(crosshatch = on) }

    override fun onCentreLineChanged(on: Boolean) = editSurvey(null) { s, _ -> s.copy(includeCentreLine = on) }

    override fun onGroupSelected(index: Int) = edit.update { e ->
        if (index !in e.groups.indices) e else e.copy(selectedGroup = index, selectedItem = null, form = null, lastEdit = null)
    }

    override fun onGroupRenamed(index: Int, name: String) = edit.update { e ->
        val group = e.groups.getOrNull(index) ?: return@update e
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@update e
        e.changed(null, e.groups.replace(index, group.renamed(trimmed)))
    }

    /** Moves a group to another mission of the job; [mission] null starts a new mission with it. */
    override fun onGroupMissionSelected(index: Int, mission: Int?) = edit.update { e ->
        val group = e.groups.getOrNull(index) ?: return@update e
        val to = mission ?: ((e.groups.maxOf { it.mission }) + 1)
        e.changed(null, e.groups.replace(index, group.inMission(to))).dropStaleAssignments()
    }

    /** Gives a mission to a vehicle ([vehicle] null: to nobody). A vehicle flies one mission, so it loses any other. */
    override fun onMissionVehicleSelected(mission: Int, vehicle: VehicleId?) = edit.update { e ->
        val others = e.assignments.filter { (m, v) -> m != mission && v != vehicle }
        e.copy(assignments = if (vehicle == null) others else others + (mission to vehicle))
    }

    /**
     * Adds the rest of a stopped mission to the plan as a new mission ([remainderOf]), ready to be given to the same
     * vehicle (after a battery change) or another one. [cameraName] is the camera of the drone that will fly it, when
     * that isn't the camera the survey was planned with; null keeps the camera.
     */
    override fun onResumeClicked(recordId: String, cameraName: String?) {
        val record = records?.records?.value?.find { it.id == recordId } ?: return
        val camera = cameraName?.let { name -> settings.settings.value.cameras.firstOrNull { it.name == name } }
        val kind = fleet.value.find { it.id == record.vehicle }?.state?.vehicleKind ?: vehicle.value.vehicleKind
        runCatching { decodePlan(record.plan) }
            .mapCatching { remainderOf(it, record.photos, record.lastReached, kind, settings.settings.value.limitsFor(kind), camera).getOrThrow() }
            .onSuccess { rest ->
                edit.update { e ->
                    // A mission of its own, after everything already in the plan.
                    val mission = if (e.groups.none(::hasItems)) e.groups.firstOrNull()?.mission ?: 0 else e.groups.maxOf { it.mission } + 1
                    val groups = e.groups + rest.groups.map { it.inMission(mission) }
                    e.changed(null, groups).copy(selectedGroup = e.groups.size, selectedItem = null, form = null)
                }
                _effects.trySend("Added the rest of ${record.name} as a mission. ${rest.note} Give it a vehicle and upload.")
            }
            .onFailure { _effects.trySend("Can't resume ${record.name}: ${it.message}") }
    }

    override fun onStoppedDismissed(recordId: String) {
        records?.dismiss(recordId)
    }

    /**
     * Splits the selected survey area into parts, each a mission of its own ([splitSurvey]). [parts] null = as many
     * as it takes for each to fit one battery. One undo step puts the whole area back.
     */
    override fun onSplitSurvey(parts: Int?, altitudeStepM: Double) {
        val e = edit.value
        val g = e.selectedGroup ?: return
        val group = e.groups.getOrNull(g) as? SurveyGroup ?: return
        val kind = vehicle.value.vehicleKind
        splitSurvey(group, kind, settings.settings.value.limitsFor(kind), parts, altitudeStepM)
            .onSuccess { split ->
                // The first part stays in the survey's mission; each of the others starts a new one.
                val next = e.groups.maxOf { it.mission } + 1
                val tagged = split.mapIndexed { i, s -> if (i == 0) s else s.inMission(next + i - 1) }
                edit.update { now ->
                    if (now.groups != e.groups) return@update now // edited meanwhile: don't overwrite it
                    now.changed(null, now.groups.take(g) + tagged + now.groups.drop(g + 1)).copy(selectedGroup = g, selectedItem = null, form = null)
                }
                _effects.trySend("Split ${group.name} into ${split.size} missions. Give each a vehicle under Missions.")
            }
            .onFailure { _effects.trySend(it.message ?: "Couldn't split the survey.") }
    }

    override fun onGroupDeleted(index: Int) = edit.update { e ->
        if (index !in e.groups.indices) return@update e
        val groups = e.groups.filterIndexed { i, _ -> i != index }
        e.changed(null, groups).copy(selectedGroup = groups.indices.lastOrNull()?.coerceAtMost(index), selectedItem = null, form = null).dropStaleAssignments()
    }

    // ---- waypoint rows ----

    override fun onRowSelected(index: Int) = edit.update { it.selectRow(index) }

    override fun onDeleteClicked(index: Int) = edit.update { e ->
        val g = e.selectedGroup ?: return@update e
        val group = e.groups[g] as? WaypointGroup ?: return@update e
        if (index !in group.items.indices) return@update e
        e.changed(null, e.groups.replace(g, group.copy(items = group.items.filterIndexed { i, _ -> i != index }))).selectRow(null)
    }

    override fun onAltitudeChanged(text: String) = editForm { it.copy(altitude = text) }

    override fun onSpeedChanged(text: String) = editForm { it.copy(speed = text) }

    // ---- survey ----

    override fun onDeleteCornerClicked() = editSurvey(null) { s, e ->
        val i = e.selectedItem?.takeIf { it in s.polygon.indices } ?: return@editSurvey s
        s.copy(polygon = s.polygon.filterIndexed { k, _ -> k != i })
    }

    /** A number field. Out-of-range values are ignored (the field shows why); repeats of one field are one undo step. */
    override fun onSurveyNumberChanged(field: SurveyField, value: Double) {
        if (value !in field.range) return
        editSurvey("survey $field") { s, _ ->
            when (field) {
                SurveyField.ALTITUDE -> s.copy(altitudeM = value)
                SurveyField.GSD -> s.copy(gsdCm = value)
                SurveyField.SIDE_OVERLAP -> s.copy(sideOverlapPct = value)
                SurveyField.FRONT_OVERLAP -> s.copy(frontOverlapPct = value)
                SurveyField.GRID_ANGLE -> s.copy(gridAngleDeg = value)
                SurveyField.SPEED -> s.copy(speedMs = value)
                SurveyField.TURNAROUND -> s.copy(turnaroundM = value)
                SurveyField.CROSSHATCH_OFFSET -> s.copy(crosshatchOffsetM = value)
                SurveyField.LEFT_WIDTH -> s.copy(leftWidthM = value)
                SurveyField.RIGHT_WIDTH -> s.copy(rightWidthM = value)
                // Whole lines only: 3.7 isn't a number of lines, so it waits for the operator to finish typing.
                SurveyField.CORRIDOR_LINES -> if (value % 1.0 == 0.0) s.copy(corridorLines = value.toInt()) else s
            }
        }
    }

    /**
     * Altitude-first ↔ GSD-first. The value switched to starts at what the other one gives now, so the survey itself
     * doesn't jump when the operator changes which number they want to type.
     */
    override fun onHeightModeSelected(mode: HeightMode) = editSurvey(null) { s, _ ->
        if (s.heightMode == mode) return@editSurvey s
        val altitude = if (s.heightMode == HeightMode.ALTITUDE) s.altitudeM else s.camera.altitudeForGsdM(s.gsdCm / 100)
        s.copy(heightMode = mode, altitudeM = altitude, gsdCm = s.camera.gsdM(altitude) * 100)
    }

    override fun onCameraSelected(name: String) {
        val camera = settings.settings.value.cameras.firstOrNull { it.name == name } ?: return
        editSurvey(null) { s, _ -> s.copy(camera = camera) }
    }

    override fun onOrientationSelected(orientation: CameraOrientation) = editSurvey(null) { s, _ -> s.copy(orientation = orientation) }

    fun onEntrySelected(entry: EntryCorner) = editSurvey(null) { s, _ -> s.copy(entry = entry) }

    override fun onReturnHomeChanged(on: Boolean) = editSurvey(null) { s, _ -> s.copy(returnHome = on) }

    /** Saves a custom camera (same name = replace) and uses it in the selected survey. */
    override fun onCameraSaved(camera: Camera) {
        settings.saveCamera(camera)
        editSurvey(null) { s, _ -> s.copy(camera = camera) }
    }

    override fun onCameraDeleted(name: String) = settings.deleteCamera(name)

    override fun onBatteryMinutesChanged(minutes: Double?) =
        settings.setBatteryMinutes(vehicle.value.vehicleKind.orCopter(), minutes?.takeIf { it > 0 })

    override fun onMaxGsdChanged(cm: Double?) = settings.setMaxGsdCm(cm?.takeIf { it > 0 })

    override fun onMaxAltitudeChanged(m: Double?) = settings.setMaxAltitudeM(m?.takeIf { it > 0 })

    override fun onMaxDistanceChanged(m: Double?) = settings.setMaxDistanceM(m?.takeIf { it > 0 })

    // ---- undo / redo ----

    override fun onUndoClicked() = edit.update { e ->
        val previous = e.past.lastOrNull() ?: return@update e
        e.copy(groups = previous, past = e.past.dropLast(1), future = e.future + listOf(e.groups), lastEdit = null).clampSelection()
    }

    override fun onRedoClicked() = edit.update { e ->
        val next = e.future.lastOrNull() ?: return@update e
        e.copy(groups = next, future = e.future.dropLast(1), past = e.past + listOf(e.groups), lastEdit = null).clampSelection()
    }

    // ---- vehicle ----

    /** Opens the preview. Nothing is sent until [onUploadConfirmed]. */
    override fun onUploadClicked() =
        edit.update { it.copy(preview = uploadPreview(it, vehicle.value, settings.settings.value, sync.state.value.selected, fleet.value)) }

    override fun onPreviewDismissed() = edit.update { it.copy(preview = null) }

    /**
     * Sends each mission to its vehicle, one after another. One mission for the selected vehicle is the plain upload
     * of before. In a job, a mission that fails doesn't stop the others; the message says which went where.
     */
    override fun onUploadConfirmed() {
        edit.update { it.copy(preview = null) }
        val targets = uploadTargets(edit.value, currentFlat(), sync.state.value.selected)
        val only = targets.singleOrNull()?.takeIf { it.vehicle == null }
        if (only != null) {
            return transfer("Uploading") { progress ->
                missions.upload(only.items, progress).map {
                    sync.state.value.selected?.let { record(only, it) }
                    "Uploaded ${only.items.size} items. Switch to AUTO on the RC to fly it."
                }
            }
        }
        val labels = vehicleLabels(fleet.value.map { it.id })
        transfer("Uploading") { _ ->
            val results = targets.map { t ->
                val vehicle = t.vehicle!! // a job's targets all name their vehicle (uploadTargets)
                val name = "${t.label} → vehicle ${labels[vehicle] ?: vehicle.systemId}"
                val result = missionsOf(vehicle).upload(t.items) { done, total -> edit.update { it.copy(transfer = "Uploading $name: $done / $total…") } }
                result.onSuccess { record(t, vehicle) }
                result.fold({ "$name: ${t.items.size} items" }, { "$name FAILED: ${it.message}" })
            }
            val failed = results.count { "FAILED" in it }
            Result.success(
                (if (failed == 0) "Uploaded ${results.size} mission${if (results.size == 1) "" else "s"}. " else "$failed of ${results.size} uploads failed. ") +
                    results.joinToString("; ") + if (failed == 0) ". Each pilot switches to AUTO on the RC." else ".",
            )
        }
    }

    /** Starts the progress record of a mission just uploaded: its groups as plan text, so a resume can rebuild it. */
    private fun record(target: UploadTarget, vehicle: VehicleId) {
        val groups = edit.value.groups.filter { it.mission == target.mission }.map { it.inMission(0) }
        val photos = flatten(groups, this.vehicle.value.vehicleKind, settings.settings.value.limitsFor(this.vehicle.value.vehicleKind)).plannedPhotos
        records?.started(vehicle, missionName(edit.value.groups, target.mission), encodePlan(groups), target.items.size, photos)
    }

    override fun onReadClicked() = transfer("Reading") { progress ->
        missions.download(progress).map { mission ->
            replacePlan(listOf(WaypointGroup("From vehicle", fromMissionItems(mission.items))))
            "Read ${mission.items.size} items from the vehicle."
        }
    }

    /** Clears the vehicle's mission and, once that succeeds, the editor. The screen asks for confirmation first. */
    override fun onClearClicked() = transfer("Clearing") { _ ->
        missions.clear().map {
            replacePlan(listOf(WaypointGroup("Waypoints")))
            "Mission cleared on the vehicle."
        }
    }

    override fun onCancelTransferClicked() {
        transferJob?.cancel()
    }

    // ---- files ----

    override fun onSaveClicked() {
        viewModelScope.launch {
            val name = files.save("mission.kftplan", encodePlan(edit.value.groups, edit.value.assignments)) ?: return@launch
            _effects.send("Saved $name.")
        }
    }

    /**
     * Exports the flat items (no survey parameters: other tools can't use them) with the vehicle's home as seq 0.
     * QGC and Mission Planner files hold one mission, so from a job it is the selected group's mission.
     */
    override fun onExportClicked(format: ExportFormat) {
        viewModelScope.launch {
            val flat = currentFlat()
            val one = edit.value.selectedGroup?.let(edit.value.groups::getOrNull)?.mission ?: flat.missions.firstOrNull()
            val mission = Mission(vehicle.value.home, one?.let(flat::only)?.items ?: emptyList())
            val text = when (format) {
                ExportFormat.QGC_PLAN -> encodeQgcPlan(mission, plane = vehicle.value.vehicleKind == VehicleKind.PLANE)
                ExportFormat.WAYPOINTS -> encodeWaypoints(mission)
            }
            val name = files.save("mission.${format.extension}", text) ?: return@launch
            _effects.send("Exported $name as plain items.")
        }
    }

    /** Opens our own plan, a QGC `.plan` or an MP `.waypoints`, told apart by content, not by the file name. */
    override fun onOpenClicked() {
        viewModelScope.launch {
            val file = files.open() ?: return@launch
            val message = try {
                when {
                    isOwnPlan(file.text) -> decodeJob(file.text).let { job ->
                        replacePlan(job.groups, job.assignments)
                        "Opened ${file.name}." + if (missionIds(job.groups).size > 1) " It is a job of ${missionIds(job.groups).size} missions." else ""
                    }
                    file.text.trimStart().startsWith("QGC WPL") -> importItems(file.name, decodeWaypoints(file.text).mission, 0)
                    else -> decodeQgcPlan(file.text).let { importItems(file.name, it.mission, it.skipped) }
                }
            } catch (e: IllegalArgumentException) {
                "Couldn't open ${file.name}: ${e.message}"
            }
            _effects.send(message)
        }
    }

    /** Reads the file, then asks survey-or-waypoints ([PlanUiState.importDialog]); a file it can't use says why. */
    override fun onImportClicked() {
        viewModelScope.launch {
            val file = files.openForImport() ?: return@launch
            val shapes = try {
                importGeometry(file)
            } catch (e: IllegalArgumentException) {
                _effects.send("Couldn't import ${file.name}: ${e.message}")
                return@launch
            }
            edit.update { it.copy(importChoice = ImportChoice(file.name, shapes)) }
        }
    }

    override fun onImportAsSurveys() {
        val choice = edit.value.importChoice ?: return
        val template = defaultSurvey(vehicle.value.vehicleKind, settings.settings.value.cameras.first())
        addImported(choice, surveyGroupsFrom(choice.shapes, choice.fileName, template))
    }

    /**
     * One survey per area, each a mission of its own (a KML of three fields becomes three missions for three drones).
     * If nothing is planned yet, the first area takes the plan's own mission; otherwise every area gets a new one.
     */
    override fun onImportAsMissions() {
        val choice = edit.value.importChoice ?: return
        val template = defaultSurvey(vehicle.value.vehicleKind, settings.settings.value.cameras.first())
        val existing = edit.value.groups
        val first = if (existing.none(::hasItems)) existing.firstOrNull()?.mission ?: 0 else existing.maxOf { it.mission } + 1
        addImported(choice, surveyGroupsFrom(choice.shapes, choice.fileName, template).mapIndexed { i, g -> g.inMission(first + i) })
    }

    override fun onImportAsWaypoints() {
        val choice = edit.value.importChoice ?: return
        val startsMission = edit.value.groups.none { it.mission == 0 && hasItems(it) }
        addImported(choice, waypointGroupsFrom(choice.shapes, choice.fileName, vehicle.value.vehicleKind, startsMission))
    }

    override fun onImportDismissed() = edit.update { it.copy(importChoice = null) }

    /** Appends the new groups as one undoable edit, selects the first, and moves the map to show them. */
    private fun addImported(choice: ImportChoice, added: List<MissionGroup>) {
        edit.update { e ->
            val groups = e.groups + added
            e.changed(null, groups).copy(
                selectedGroup = e.groups.size, selectedItem = null, form = null, importChoice = null,
                camera = fitCamera(choice.shapes.allPoints(), (e.camera?.id ?: 0) + 1),
            )
        }
        val what = if (added.first() is SurveyGroup) "survey" else "waypoint group"
        val notes = choice.shapes.notes.joinToString(" ", prefix = " ").trimEnd()
        val job = if (missionIds(added).size > 1) " Each is a mission of its own: give each a vehicle under Missions." else ""
        _effects.trySend("Added ${added.size} $what${if (added.size == 1) "" else "s"} from ${choice.fileName}.$notes$job")
    }

    private fun importItems(name: String, mission: Mission, skipped: Int): String {
        replacePlan(listOf(WaypointGroup(name.substringBeforeLast('.'), fromMissionItems(mission.items))))
        val note = if (skipped > 0) " $skipped QGC survey/complex items were skipped: only QGC can expand them." else ""
        return "Imported ${mission.items.size} items from $name.$note"
    }

    // ---- helpers ----

    private fun currentFlat() = flatten(edit.value.groups, vehicle.value.vehicleKind, settings.settings.value.limitsFor(vehicle.value.vehicleKind))

    /** Replaces the whole plan (read, clear, open) and its vehicle assignments. The groups are undoable, like any edit. */
    private fun replacePlan(groups: List<MissionGroup>, assignments: Map<Int, VehicleId> = emptyMap()) =
        edit.update { it.changed(null, groups).copy(selectedGroup = 0, selectedItem = null, form = null, assignments = assignments) }

    /** A new group joins the mission of the selected group, so "add a survey" in mission 2 stays in mission 2. */
    private fun addGroup(new: MissionGroup) = edit.update { e ->
        val group = new.inMission(e.selectedGroup?.let(e.groups::getOrNull)?.mission ?: e.groups.lastOrNull()?.mission ?: 0)
        val groups = e.groups + group
        e.changed(null, groups).copy(selectedGroup = groups.lastIndex, selectedItem = null, form = null)
    }

    private fun nextName(base: String) = "$base ${edit.value.groups.count { it.name.startsWith(base) } + 1}"

    /** Edits the selected survey's settings; `null` from [change] or no selected survey leaves everything as it was. */
    private fun editSurvey(key: String?, change: (SurveySettings, PlanEdit) -> SurveySettings) = edit.update { e ->
        val g = e.selectedGroup ?: return@update e
        val group = e.groups[g] as? SurveyGroup ?: return@update e
        val survey = change(group.survey, e).withoutStaleResume(group.survey)
        if (survey == group.survey) return@update e
        val next = e.changed(key?.let { "$it $g" }, e.groups.replace(g, group.copy(survey = survey)))
        if (survey.polygon.size != group.survey.polygon.size) next.copy(selectedItem = null) else next
    }

    /**
     * Runs one transfer at a time and reports its progress in [PlanUiState.transfer]. Cancelling the job cancels
     * the protocol, which tells the vehicle (MissionProtocol).
     */
    private fun transfer(verb: String, block: suspend (progress: (Int, Int) -> Unit) -> Result<String>) {
        if (transferJob?.isActive == true) return
        transferJob = viewModelScope.launch {
            edit.update { it.copy(transfer = "$verb…") }
            try {
                val result = block { done, total -> edit.update { it.copy(transfer = "$verb $done / $total…") } }
                _effects.send(result.getOrElse { "$verb failed: ${it.message}" })
            } catch (e: CancellationException) {
                // trySend: this coroutine is cancelled, so a suspending send would throw instead of delivering.
                _effects.trySend("$verb cancelled. Read the mission back before flying: it may be incomplete.")
                throw e
            } finally {
                edit.update { it.copy(transfer = null) }
            }
        }
    }

    /** Applies a text edit; the item changes only when both fields parse, otherwise the form shows why. */
    private fun editForm(change: (ItemForm) -> ItemForm) = edit.update { e ->
        val form = e.form?.let(change) ?: return@update e
        val g = e.selectedGroup ?: return@update e
        val group = e.groups[g] as? WaypointGroup ?: return@update e
        val parsed = parseForm(form.altitude, form.speed)
        val item = group.items.getOrNull(form.index)
        if (parsed.isFailure || item == null) return@update e.copy(form = form.copy(error = parsed.exceptionOrNull()?.message))
        val (altitude, speed) = parsed.getOrThrow()
        val items = group.items.replace(form.index, item.copy(altitudeM = altitude, speedMs = speed))
        e.changed("form $g ${form.index}", e.groups.replace(g, group.copy(items = items))).copy(form = form.copy(error = null))
    }
}

/** How many undo steps are kept. Each is a list of small immutable groups, so 100 costs little memory. */
private const val HISTORY = 100

/**
 * The plan becomes [groups], with the old plan pushed onto the undo history, unless [key] repeats the previous edit's
 * key: then this change joins that undo step (one drag = one undo, however many move events it sent).
 */
private fun PlanEdit.changed(key: String?, groups: List<MissionGroup>): PlanEdit {
    if (groups == this.groups) return this
    val joins = key != null && key == lastEdit
    return copy(
        groups = groups,
        past = if (joins) past else (past + listOf(this.groups)).takeLast(HISTORY),
        future = emptyList(),
        lastEdit = key,
    )
}

/** Selecting a row resets the form to that row's values. Passthrough rows can be selected but not edited. */
private fun PlanEdit.selectRow(index: Int?): PlanEdit {
    val group = selectedGroup?.let(groups::getOrNull) as? WaypointGroup
    val item = index?.let { group?.items?.getOrNull(it) }
    return copy(selectedItem = index.takeIf { item != null }, form = item?.takeIf { it.editable }?.let { formFor(index!!, it) })
}

/** An assignment for a mission that no longer has a group (it was deleted, or its last group moved) is dropped. */
private fun PlanEdit.dropStaleAssignments() = copy(assignments = assignments.filterKeys { m -> groups.any { it.mission == m } })

/** After undo/redo the selected group may be gone; the item selection is dropped (its index may mean another item). */
private fun PlanEdit.clampSelection() =
    copy(selectedGroup = selectedGroup?.coerceAtMost(groups.lastIndex)?.takeIf { it >= 0 }, selectedItem = null, form = null)

private fun SurveyGroup.withSurvey(change: (SurveySettings) -> SurveySettings) = copy(survey = change(survey).withoutStaleResume(survey))

private fun <T> List<T>.replace(index: Int, item: T) = mapIndexed { i, old -> if (i == index) item else old }

/** How long the plan must stay unchanged before the terrain is looked up again. */
internal const val TERRAIN_DEBOUNCE_MS = 600L
