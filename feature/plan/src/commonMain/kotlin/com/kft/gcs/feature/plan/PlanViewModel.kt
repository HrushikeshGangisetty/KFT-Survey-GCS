package com.kft.gcs.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The waypoint editor: click the map to add, drag to move, edit altitude and speed, and move the plan to and from
 * the vehicle. It never flies anything: uploading only stores the mission, and the pilot starts it by switching to
 * AUTO on the RC (spec S9).
 *
 * @param vehicle the live vehicle state (home, vehicle kind, mission progress, connected).
 */
class PlanViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val missions: MissionRepository,
) : ViewModel() {

    /** Screen-only state. The plan lives here, not in a repository: it's the editor's working copy. */
    private data class Local(
        val items: List<PlanItem> = emptyList(),
        val selected: Int? = null,
        val form: ItemForm? = null,
        val transfer: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var transferJob: Job? = null

    private val _effects = Channel<String>(Channel.BUFFERED)

    /** One-shot messages for a snackbar: transfer results and errors. */
    val effects: Flow<String> = _effects.receiveAsFlow()

    // ponytail: the plan lives in memory only and resets on restart. Save/load arrives with `.plan` files (weeks 4–5).
    val state: StateFlow<PlanUiState> = combine(vehicle, local) { v, l -> buildPlanUiState(l.items, l.selected, l.form, l.transfer, v) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), buildPlanUiState(emptyList(), null, null, null, vehicle.value))

    fun onMapClick(at: LatLon) = local.update { l ->
        val items = l.items.addWaypoint(at, vehicle.value.vehicleKind)
        l.select(items, items.lastIndex)
    }

    fun onMarkerClick(id: String) {
        val index = indexOfMarker(id) ?: return
        onRowSelected(index)
    }

    fun onMarkerDragged(id: String, to: LatLon) = local.update { l ->
        val index = indexOfMarker(id) ?: return@update l
        val item = l.items.getOrNull(index)?.takeIf { it.editable } ?: return@update l
        l.select(l.items.replace(index, item.copy(position = to)), index)
    }

    fun onRowSelected(index: Int) = local.update { l -> l.select(l.items, index.takeIf { it in l.items.indices }) }

    fun onDeleteClicked(index: Int) = local.update { l ->
        if (index !in l.items.indices) return@update l
        l.select(l.items.filterIndexed { i, _ -> i != index }, null)
    }

    fun onAltitudeChanged(text: String) = editForm { it.copy(altitude = text) }

    fun onSpeedChanged(text: String) = editForm { it.copy(speed = text) }

    fun onUploadClicked() = transfer("Uploading") { progress ->
        val items = toMissionItems(local.value.items)
        missions.upload(items, progress).map { "Uploaded ${items.size} items. Switch to AUTO on the RC to fly it." }
    }

    fun onReadClicked() = transfer("Reading") { progress ->
        missions.download(progress).map { mission ->
            val rows = fromMissionItems(mission.items)
            local.update { it.select(rows, null) }
            "Read ${mission.items.size} items from the vehicle."
        }
    }

    /** Clears the vehicle's mission and, once that succeeds, the editor. The screen asks for confirmation first. */
    fun onClearClicked() = transfer("Clearing") { _ ->
        missions.clear().map {
            local.update { it.select(emptyList(), null) }
            "Mission cleared on the vehicle."
        }
    }

    fun onCancelTransferClicked() {
        transferJob?.cancel()
    }

    /**
     * Runs one transfer at a time and reports its progress in [PlanUiState.transfer]. Cancelling the job cancels
     * the protocol, which tells the vehicle (MissionProtocol).
     */
    private fun transfer(verb: String, block: suspend (progress: (Int, Int) -> Unit) -> Result<String>) {
        if (transferJob?.isActive == true) return
        transferJob = viewModelScope.launch {
            local.update { it.copy(transfer = "$verb…") }
            try {
                val result = block { done, total -> local.update { it.copy(transfer = "$verb $done / $total…") } }
                _effects.send(result.getOrElse { "$verb failed: ${it.message}" })
            } catch (e: CancellationException) {
                // trySend: this coroutine is cancelled, so a suspending send would throw instead of delivering.
                _effects.trySend("$verb cancelled. Read the mission back before flying: it may be incomplete.")
                throw e
            } finally {
                local.update { it.copy(transfer = null) }
            }
        }
    }

    /** Applies a text edit; the item changes only when both fields parse, otherwise the form shows why. */
    private fun editForm(change: (ItemForm) -> ItemForm) = local.update { l ->
        val form = l.form?.let(change) ?: return@update l
        val parsed = parseForm(form.altitude, form.speed)
        val item = l.items.getOrNull(form.index)
        if (parsed.isFailure || item == null) return@update l.copy(form = form.copy(error = parsed.exceptionOrNull()?.message))
        val (altitude, speed) = parsed.getOrThrow()
        l.copy(items = l.items.replace(form.index, item.copy(altitudeM = altitude, speedMs = speed)), form = form.copy(error = null))
    }

    /** Selecting a row resets the form to that row's values. Passthrough rows can be selected but not edited. */
    private fun Local.select(items: List<PlanItem>, index: Int?): Local {
        val item = index?.let(items::getOrNull)
        return copy(items = items, selected = index, form = item?.takeIf { it.editable }?.let { formFor(index, it) })
    }

    private fun List<PlanItem>.replace(index: Int, item: PlanItem) = mapIndexed { i, old -> if (i == index) item else old }
}
