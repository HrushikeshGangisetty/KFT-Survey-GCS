package com.kft.gcs.feature.fly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.MissionSyncState
import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.core.vehicle.VehicleState
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
 * The Fly view: vehicle on the map, HUD strip, basemap choice. Monitoring only, by design: the pilot arms, takes
 * off, changes mode and lands on the RC, never from the GCS (spec S9), so this screen has no flight-action buttons.
 *
 * @param basemaps what this build can show; passed in (not read from the platform here) so tests are deterministic.
 * @param sync the Plan tab's plan next to what the vehicle holds, for the "≠ plan" warning.
 */
class FlyViewModel(
    private val vehicles: VehicleRepository,
    private val basemaps: List<TileSourceConfig>,
    private val sync: MissionSync,
) : ViewModel() {

    /** Screen-only state: which basemap, the track drawn so far, and the last camera move asked for. */
    private data class Local(
        val basemap: TileSourceConfig,
        val track: List<LatLon> = emptyList(),
        val camera: CameraRequest? = null,
        val cameraRequests: Long = 0,
    )

    private val local = MutableStateFlow(Local(basemaps.first()))

    val state: StateFlow<FlyUiState> = combine(vehicles.state, local, sync.state, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(VehicleState(), local.value, sync.state.value))

    init {
        // The track and the first auto-centre follow the vehicle even while the screen is hidden, so returning to
        // the Fly view shows the whole flight so far, not just the part flown while it was visible.
        viewModelScope.launch {
            vehicles.state.collect { v ->
                val position = v.position ?: return@collect
                local.update { l ->
                    val first = l.camera == null
                    l.copy(
                        track = l.track.appendTrack(position),
                        camera = if (first) CameraRequest(position, FOLLOW_ZOOM, l.cameraRequests + 1) else l.camera,
                        cameraRequests = if (first) l.cameraRequests + 1 else l.cameraRequests,
                    )
                }
            }
        }
    }

    fun onBasemapSelected(id: String) {
        val chosen = basemaps.firstOrNull { it.id == id } ?: return
        local.update { it.copy(basemap = chosen) }
    }

    fun onCenterClicked() {
        val position = vehicles.state.value.position ?: return
        local.update { it.copy(camera = CameraRequest(position, FOLLOW_ZOOM, it.cameraRequests + 1), cameraRequests = it.cameraRequests + 1) }
    }

    fun onClearTrackClicked() = local.update { it.copy(track = emptyList()) }

    private fun build(v: VehicleState, l: Local, s: MissionSyncState) = FlyUiState(
        connected = v.connected,
        firmware = v.firmwareVersion?.let { "ArduPilot $it" },
        login = v.login?.let { LoginUi(it.label, it.warning) },
        hud = hudItems(v),
        message = v.lastMessage?.let { MessageUi(it.text, it.severity) },
        missionWarning = if (v.connected && s.differsFrom(v.mission?.total)) "Vehicle mission ≠ plan" else null,
        overlays = listOfNotNull(
            MapOverlay.Track(l.track),
            v.position?.let { MapOverlay.Vehicle(it, v.headingDeg) },
        ),
        basemaps = basemaps,
        selectedBasemap = l.basemap,
        cameraRequest = l.camera,
        canCenter = v.position != null,
    )

    companion object {
        /** Close enough to see a survey block and the vehicle's direction; about 1 px per metre. */
        const val FOLLOW_ZOOM = 17.0
    }
}
