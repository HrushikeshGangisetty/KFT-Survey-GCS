package com.kft.gcs.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.decodeQgcPlan
import com.kft.gcs.core.geoio.decodeWaypoints
import com.kft.gcs.core.geoio.encodeQgcPlan
import com.kft.gcs.core.geoio.encodeWaypoints
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.planning.altitudeForGsdM
import com.kft.gcs.core.planning.gsdM
import com.kft.gcs.core.vehicle.Mission
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
}

enum class ExportFormat(val extension: String) { QGC_PLAN("plan"), WAYPOINTS("waypoints") }

/**
 * The Plan screen: a mission made of groups (waypoints, surveys), edited on the shared map and in the side panel,
 * with undo/redo, an upload preview, and plan files. It never flies anything: uploading only stores the mission, and
 * the pilot starts it by switching to AUTO on the RC (spec S9).
 *
 * @param vehicle the live vehicle state (home, vehicle kind, mission progress, connected).
 * @param sync shared with the Fly view: this screen writes the plan and what the vehicle holds after each transfer.
 */
class PlanViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val missions: MissionRepository,
    private val sync: MissionSync,
    private val settings: PlanSettingsRepository,
    private val files: PlanFiles,
) : ViewModel(), PlanActions {

    private val edit = MutableStateFlow(PlanEdit())
    private var transferJob: Job? = null

    private val _effects = Channel<String>(Channel.BUFFERED)

    /** One-shot messages for a snackbar: transfer and file results, and errors. */
    val effects: Flow<String> = _effects.receiveAsFlow()

    val state: StateFlow<PlanUiState> = combine(edit, vehicle, settings.settings, sync.state, ::buildPlanUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildPlanUiState(edit.value, vehicle.value, settings.settings.value, sync.state.value))

    init {
        // Keep the shared plan (for Fly's "≠ plan" warning and photo count) up to date, even while this tab is hidden.
        viewModelScope.launch {
            combine(edit.map { it.groups }, vehicle.map { it.vehicleKind }.distinctUntilChanged(), settings.settings) { groups, kind, s ->
                flatten(groups, kind, s.limitsFor(kind))
            }.collect { sync.planChanged(it.items, it.plannedPhotos) }
        }
    }

    // ---- map ----

    fun onMapClick(at: LatLon) = edit.update { e ->
        val g = e.selectedGroup ?: return@update e
        when (val group = e.groups[g]) {
            is WaypointGroup -> {
                val items = group.items.addWaypoint(at, vehicle.value.vehicleKind, startsMission = e.groups.take(g).none(::hasItems))
                e.changed(null, e.groups.replace(g, group.copy(items = items))).selectRow(items.lastIndex)
            }
            is SurveyGroup -> {
                val polygon = insertCorner(group.survey.polygon, at)
                e.changed(null, e.groups.replace(g, group.withSurvey { it.copy(polygon = polygon) }))
                    .copy(selectedItem = polygon.indexOf(at), form = null)
            }
        }
    }

    fun onMarkerClick(id: String) = edit.update { e ->
        val (kind, g, i) = parseMarker(id) ?: return@update e
        val selected = e.copy(selectedGroup = g, lastEdit = null)
        if (kind == "wp") selected.selectRow(i) else selected.copy(selectedItem = i, form = null)
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

    override fun onGroupSelected(index: Int) = edit.update { e ->
        if (index !in e.groups.indices) e else e.copy(selectedGroup = index, selectedItem = null, form = null, lastEdit = null)
    }

    override fun onGroupRenamed(index: Int, name: String) = edit.update { e ->
        val group = e.groups.getOrNull(index) ?: return@update e
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return@update e
        e.changed(null, e.groups.replace(index, group.renamed(trimmed)))
    }

    override fun onGroupDeleted(index: Int) = edit.update { e ->
        if (index !in e.groups.indices) return@update e
        val groups = e.groups.filterIndexed { i, _ -> i != index }
        e.changed(null, groups).copy(selectedGroup = groups.indices.lastOrNull()?.coerceAtMost(index), selectedItem = null, form = null)
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

    override fun onEntrySelected(entry: EntryCorner) = editSurvey(null) { s, _ -> s.copy(entry = entry) }

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
    override fun onUploadClicked() = edit.update { it.copy(preview = uploadPreview(it, vehicle.value, settings.settings.value)) }

    override fun onPreviewDismissed() = edit.update { it.copy(preview = null) }

    override fun onUploadConfirmed() {
        edit.update { it.copy(preview = null) }
        val items = currentFlat().items
        transfer("Uploading") { progress ->
            missions.upload(items, progress).map {
                sync.vehicleHolds(items)
                "Uploaded ${items.size} items. Switch to AUTO on the RC to fly it."
            }
        }
    }

    override fun onReadClicked() = transfer("Reading") { progress ->
        missions.download(progress).map { mission ->
            replacePlan(listOf(WaypointGroup("From vehicle", fromMissionItems(mission.items))))
            sync.vehicleHolds(mission.items)
            "Read ${mission.items.size} items from the vehicle."
        }
    }

    /** Clears the vehicle's mission and, once that succeeds, the editor. The screen asks for confirmation first. */
    override fun onClearClicked() = transfer("Clearing") { _ ->
        missions.clear().map {
            replacePlan(listOf(WaypointGroup("Waypoints")))
            sync.vehicleHolds(emptyList())
            "Mission cleared on the vehicle."
        }
    }

    override fun onCancelTransferClicked() {
        transferJob?.cancel()
    }

    // ---- files ----

    override fun onSaveClicked() {
        viewModelScope.launch {
            val name = files.save("mission.kftplan", encodePlan(edit.value.groups)) ?: return@launch
            _effects.send("Saved $name.")
        }
    }

    /** Exports the flat items (no survey parameters: other tools can't use them) with the vehicle's home as seq 0. */
    override fun onExportClicked(format: ExportFormat) {
        viewModelScope.launch {
            val mission = Mission(vehicle.value.home, currentFlat().items)
            val text = when (format) {
                ExportFormat.QGC_PLAN -> encodeQgcPlan(mission, vehicle.value.vehicleKind)
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
                    isOwnPlan(file.text) -> decodePlan(file.text).also(::replacePlan).let { "Opened ${file.name}." }
                    file.text.trimStart().startsWith("QGC WPL") -> importItems(file.name, decodeWaypoints(file.text).mission, 0)
                    else -> decodeQgcPlan(file.text).let { importItems(file.name, it.mission, it.skipped) }
                }
            } catch (e: IllegalArgumentException) {
                "Couldn't open ${file.name}: ${e.message}"
            }
            _effects.send(message)
        }
    }

    private fun importItems(name: String, mission: Mission, skipped: Int): String {
        replacePlan(listOf(WaypointGroup(name.substringBeforeLast('.'), fromMissionItems(mission.items))))
        val note = if (skipped > 0) " $skipped QGC survey/complex items were skipped: only QGC can expand them." else ""
        return "Imported ${mission.items.size} items from $name.$note"
    }

    // ---- helpers ----

    private fun currentFlat() = flatten(edit.value.groups, vehicle.value.vehicleKind, settings.settings.value.limitsFor(vehicle.value.vehicleKind))

    /** Replaces the whole plan (read, clear, open). Undoable, like any edit. */
    private fun replacePlan(groups: List<MissionGroup>) = edit.update { it.changed(null, groups).copy(selectedGroup = 0, selectedItem = null, form = null) }

    private fun addGroup(group: MissionGroup) = edit.update { e ->
        val groups = e.groups + group
        e.changed(null, groups).copy(selectedGroup = groups.lastIndex, selectedItem = null, form = null)
    }

    private fun nextName(base: String) = "$base ${edit.value.groups.count { it.name.startsWith(base) } + 1}"

    /** Edits the selected survey's settings; `null` from [change] or no selected survey leaves everything as it was. */
    private fun editSurvey(key: String?, change: (SurveySettings, PlanEdit) -> SurveySettings) = edit.update { e ->
        val g = e.selectedGroup ?: return@update e
        val group = e.groups[g] as? SurveyGroup ?: return@update e
        val survey = change(group.survey, e)
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

/** After undo/redo the selected group may be gone; the item selection is dropped (its index may mean another item). */
private fun PlanEdit.clampSelection() =
    copy(selectedGroup = selectedGroup?.coerceAtMost(groups.lastIndex)?.takeIf { it >= 0 }, selectedItem = null, form = null)

private fun hasItems(group: MissionGroup) = when (group) {
    is WaypointGroup -> group.items.isNotEmpty()
    is SurveyGroup -> group.survey.polygon.size >= 3
}

private fun SurveyGroup.withSurvey(change: (SurveySettings) -> SurveySettings) = copy(survey = change(survey))

private fun <T> List<T>.replace(index: Int, item: T) = mapIndexed { i, old -> if (i == index) item else old }
