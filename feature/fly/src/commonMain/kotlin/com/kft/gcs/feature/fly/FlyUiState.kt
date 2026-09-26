package com.kft.gcs.feature.fly

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.vehicle.GpsFix
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.CameraRequest
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.TileSourceConfig
import kotlin.math.roundToInt

/** Everything the Fly screen draws. */
@Immutable
data class FlyUiState(
    val connected: Boolean,
    /** e.g. "ArduPilot 4.6.3", once AUTOPILOT_VERSION has arrived. */
    val firmware: String?,
    /** "KFT login: OK" etc. (spec S12), null while no vehicle is heard. [login] warning = the operator must act. */
    val login: LoginUi?,
    /** The HUD strip: "Mode Loiter", "Alt 12.3 m", … Always the same items in the same order, so nothing jumps. */
    val hud: List<HudItem>,
    val message: MessageUi?,
    /**
     * "Vehicle mission ≠ plan" when the vehicle is known to hold something other than the Plan tab's plan. A warning
     * only: the GCS can't start or change the flight (spec S9), so the operator re-uploads from Plan if needed.
     */
    val missionWarning: String?,
    val overlays: List<MapOverlay>,
    val basemaps: List<TileSourceConfig>,
    val selectedBasemap: TileSourceConfig,
    val cameraRequest: CameraRequest?,
    /** True once there is a position to centre on. */
    val canCenter: Boolean,
)

data class HudItem(val label: String, val value: String, val warning: Boolean = false)

data class MessageUi(val text: String, val severity: Severity)

data class LoginUi(val text: String, val warning: Boolean)

/** The strings for the HUD strip. A dash means "not reported", never a fake zero. */
fun hudItems(v: VehicleState): List<HudItem> = listOf(
    HudItem("Mode", v.flightMode ?: DASH),
    HudItem("State", if (v.armed) "ARMED" else "Disarmed", warning = v.armed),
    HudItem("Alt", v.altitudeRelativeM?.let { "${it.oneDecimal()} m" } ?: DASH),
    HudItem("Speed", v.groundspeedMs?.let { "${it.oneDecimal()} m/s" } ?: DASH),
    HudItem("Heading", v.headingDeg?.let { "${it.roundToInt() % 360}°" } ?: DASH),
    HudItem("GPS", "${v.gpsFix.label}${v.satellites?.let { " · $it sats" } ?: ""}", warning = v.gpsFix < GpsFix.FIX_3D),
    HudItem(
        "Battery",
        listOfNotNull(v.batteryVolts?.let { "${it.oneDecimal()} V" }, v.batteryPercent?.let { "$it%" }).joinToString(" · ").ifEmpty { DASH },
        // ponytail: fixed 20% warning. Make it a setting (and add a voltage-per-cell rule) with the pre-flight checklist.
        warning = (v.batteryPercent ?: 100) < 20,
    ),
    HudItem("Mission", missionText(v.mission)),
)

/**
 * "Photos 12 / 144": taken (CAMERA_FEEDBACK since the last Clear track) out of what the plan expects. Without a
 * planned count (no survey in the plan) just the number taken.
 */
internal fun photosItem(taken: Int, planned: Int) = HudItem("Photos", if (planned > 0) "$taken / $planned" else "$taken")

/** "3 / 5" while flying the mission, "Done" at the end. Item numbers are the vehicle's: 1 is the first after home. */
internal fun missionText(m: MissionProgress?): String = when {
    m == null -> DASH
    m.complete -> "Done"
    else -> "${m.current}${m.total?.let { " / $it" } ?: ""}"
}

/**
 * Adds [point] to the flown track if the vehicle moved at least [minSpacingM] since the last point, keeping at
 * most [maxPoints] (oldest dropped). Spacing stops a hovering copter from piling thousands of identical points
 * into the GeoJSON the map redraws on every update.
 */
fun List<LatLon>.appendTrack(point: LatLon, minSpacingM: Double = 1.0, maxPoints: Int = 2_000): List<LatLon> {
    val last = lastOrNull()
    if (last != null && Geodesy.distanceMeters(last, point) < minSpacingM) return this
    return (this + point).takeLast(maxPoints)
}

private const val DASH = "–"

private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()
