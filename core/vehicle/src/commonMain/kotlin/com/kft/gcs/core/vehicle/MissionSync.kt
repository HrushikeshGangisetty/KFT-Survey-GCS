package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.geo.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The plan being edited next to what the vehicle holds, so the Plan screen can say "Not uploaded" and the Fly view
 * can warn "Vehicle mission ≠ plan". Features can't import each other (CLAUDE.md §2), so this app-wide object in
 * `core:vehicle` is where Plan writes and Fly reads.
 *
 * "What the vehicle holds" is only what this GCS last uploaded or read. Another GCS (or MAVProxy) can change it
 * behind our back. [MissionSyncState.differsFrom] catches the common case of that through the item count the vehicle
 * reports in MISSION_CURRENT.
 */
class MissionSync {
    private val _state = MutableStateFlow(MissionSyncState())
    val state: StateFlow<MissionSyncState> = _state.asStateFlow()

    /** The plan changed: its flat items (home excluded, S11) and how many photos it plans. */
    fun planChanged(items: List<MissionItem>, plannedPhotos: Int) = _state.update { it.copy(planned = items, plannedPhotos = plannedPhotos) }

    /** An upload, read or clear succeeded: the vehicle now holds exactly [items]. */
    fun vehicleHolds(items: List<MissionItem>) = _state.update { it.copy(onVehicle = items) }
}

/**
 * @property onVehicle null = unknown (nothing uploaded or read since the app started).
 */
data class MissionSyncState(
    val planned: List<MissionItem> = emptyList(),
    val plannedPhotos: Int = 0,
    val onVehicle: List<MissionItem>? = null,
) {
    /**
     * True when we *know* the vehicle's mission isn't the plan: either what we last sent or read differs from the
     * plan, or the vehicle reports a different item count ([reportedTotal], from MISSION_CURRENT) than it had then.
     * Unknown is not "differs": with nothing uploaded yet there's nothing to warn about.
     */
    fun differsFrom(reportedTotal: Int?): Boolean {
        val held = onVehicle ?: return false
        return !sameMission(planned, held) || (reportedTotal != null && reportedTotal != held.size)
    }
}

/**
 * Whether two item lists are the same mission as the vehicle stores it:
 * - positions compared in the 1e-7° steps MISSION_ITEM_INT carries;
 * - altitudes and params compared as the Float they travel as;
 * - the frame compared only for items with a position. ArduPilot reads DO_ commands back as AMSL whatever was sent
 *   (see [MissionItem]).
 */
fun sameMission(a: List<MissionItem>, b: List<MissionItem>): Boolean = a.map { it.onWire() } == b.map { it.onWire() }

private fun MissionItem.onWire() = copy(
    position = position?.let { LatLon.fromE7(it.latE7, it.lonE7) },
    altitudeM = altitudeM.toFloat().toDouble(),
    frame = if (position == null) AltitudeFrame.RELATIVE else frame,
)
