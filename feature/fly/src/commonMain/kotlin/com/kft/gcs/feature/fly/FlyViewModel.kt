package com.kft.gcs.feature.fly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.MissionSyncState
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.vehicleLabels
import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.TileSourceConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The Fly view: every vehicle on the map, the selected one's HUD, basemap choice. Monitoring only, by design: the
 * pilot arms, takes off, changes mode and lands on the RC, never from the GCS (spec S9), so this screen has no
 * flight-action buttons.
 *
 * The fleet is passed in as plain flows rather than the `Fleet` object, so tests can script vehicles without links.
 *
 * @param vehicles every known vehicle with its latest state (`Fleet.vehicles`).
 * @param selectedId the vehicle the HUD shows and Plan and Params act on (`Fleet.selectedId`).
 * @param select changes the selection (`Fleet.select`).
 * @param basemaps what this device can show: the built-in maps, then imported MBTiles files, which come and go while
 *   the app runs (the Maps tab). Passed in, not read from the platform here, so tests are deterministic.
 * @param sync the Plan tab's plan next to what the vehicle holds, for the "≠ plan" warning.
 */
class FlyViewModel(
    private val vehicles: StateFlow<List<FleetVehicle>>,
    private val selectedId: StateFlow<VehicleId?>,
    private val select: (VehicleId) -> Unit,
    private val basemaps: StateFlow<List<TileSourceConfig>>,
    private val sync: MissionSync,
) : ViewModel() {

    /**
     * Screen-only state: which basemap (by id), each vehicle's track so far, and the last camera move asked for.
     * [photosCleared] is how many of each vehicle's photos Clear track hid, so a new flight counts from 0.
     */
    private data class Local(
        val basemapId: String,
        val tracks: Map<VehicleId, List<LatLon>> = emptyMap(),
        val photosCleared: Map<VehicleId, Int> = emptyMap(),
        val camera: CameraRequest? = null,
        val cameraRequests: Long = 0,
    )

    private val local = MutableStateFlow(Local(basemaps.value.first().id))

    val state: StateFlow<FlyUiState> = combine(vehicles, selectedId, local, sync.state, basemaps, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(vehicles.value, selectedId.value, local.value, sync.state.value, basemaps.value))

    init {
        // The tracks and the first auto-centre follow the vehicles even while the screen is hidden, so returning to
        // the Fly view shows the whole flight so far, not just the part flown while it was visible.
        viewModelScope.launch {
            combine(vehicles, selectedId, ::Pair).collect { (all, selected) ->
                local.update { l ->
                    // A duplicated system id is two aircraft reporting in turn: its position is not a track.
                    val tracks = all.filter { !it.duplicateId }.fold(l.tracks) { tracks, v ->
                        val position = v.state.position ?: return@fold tracks
                        tracks + (v.id to tracks[v.id].orEmpty().appendTrack(position))
                    }
                    // Centre once, on the selected vehicle's first position.
                    val first = all.find { it.id == selected }?.state?.position?.takeIf { l.camera == null }
                    l.copy(
                        tracks = tracks,
                        camera = first?.let { CameraRequest(it, FOLLOW_ZOOM, l.cameraRequests + 1) } ?: l.camera,
                        cameraRequests = if (first != null) l.cameraRequests + 1 else l.cameraRequests,
                    )
                }
            }
        }
    }

    /** The vehicle switcher: Fly's HUD, Plan's transfers and Params now act on [id]. */
    fun onVehicleSelected(id: VehicleId) = select(id)

    fun onBasemapSelected(id: String) {
        if (basemaps.value.none { it.id == id }) return
        local.update { it.copy(basemapId = id) }
    }

    fun onCenterClicked() {
        val position = vehicles.value.find { it.id == selectedId.value }?.state?.position ?: return
        local.update { it.copy(camera = CameraRequest(position, FOLLOW_ZOOM, it.cameraRequests + 1), cameraRequests = it.cameraRequests + 1) }
    }

    /** Clears every vehicle's track and photo dots, for the next flight. */
    fun onClearTrackClicked() = local.update { it.copy(tracks = emptyMap(), photosCleared = vehicles.value.associate { v -> v.id to v.state.photos.size }) }

    private fun build(all: List<FleetVehicle>, selectedId: VehicleId?, l: Local, s: MissionSyncState, maps: List<TileSourceConfig>): FlyUiState {
        val selected = all.find { it.id == selectedId }
        val v = selected?.state ?: VehicleState()
        // A new link starts a vehicle's photo list again from 0; then nothing is hidden any more.
        fun photosOf(vehicle: FleetVehicle) = vehicle.state.photos.let { p -> p.drop((l.photosCleared[vehicle.id] ?: 0).takeIf { it <= p.size } ?: 0) }
        val labels = vehicleLabels(all.map { it.id })
        return FlyUiState(
            connected = v.connected,
            firmware = v.firmwareVersion?.let { "ArduPilot $it" },
            login = v.login?.let { LoginUi(it.label, it.warning) },
            hud = hudItems(v) + photosItem(selected?.let(::photosOf)?.size ?: 0, s.plannedPhotos),
            gps = gpsItem(v),
            message = v.lastMessage?.let { MessageUi(it.text + morePreArm(v), it.severity) },
            missionWarning = if (v.connected && s.differsFrom(v.mission?.total)) "Vehicle mission ≠ plan" else null,
            vehicleProblem = selected?.takeIf { it.duplicateId }?.let { "Two vehicles are using system id ${it.id.systemId}: nothing can be sent" },
            vehicles = all.map { VehicleChipUi(it.id, labels.getValue(it.id), it.slot, selected = it.id == selectedId, connected = it.state.connected) },
            // One track per vehicle (an empty one when there is no vehicle, so the overlay list keeps its shape),
            // each vehicle's photo dots, then the vehicles. With more than one vehicle, each one's track and dots are
            // in its own colour and it carries a label; a single vehicle keeps the usual magenta and lime.
            overlays = all.map { MapOverlay.Track(l.tracks[it.id].orEmpty(), it.slot.takeIf { all.size > 1 }) }.ifEmpty { listOf(MapOverlay.Track(emptyList())) } +
                (if (all.size > 1) all.map { MapOverlay.Photos(photosOf(it), it.slot) } else listOf(MapOverlay.Photos(all.flatMap(::photosOf)))) +
                all.filter { !it.duplicateId }.mapNotNull { fv ->
                    fv.state.position?.let { MapOverlay.Vehicle(it, fv.state.headingDeg, fv.slot, labels[fv.id].takeIf { all.size > 1 }, selected = fv.id == selectedId) }
                },
            basemaps = maps,
            // An imported map deleted on the Maps tab while it was showing: back to the first (Street).
            selectedBasemap = maps.find { it.id == l.basemapId } ?: maps.first(),
            cameraRequest = l.camera,
            canCenter = v.position != null && selected?.duplicateId != true,
            signing = v.signing.takeIf { v.connected },
            fcSignatureFailures = v.fcSignatureFailures,
        )
    }

    companion object {
        /** Close enough to see a survey block and the vehicle's direction; about 1 px per metre. */
        const val FOLLOW_ZOOM = 17.0
    }
}
