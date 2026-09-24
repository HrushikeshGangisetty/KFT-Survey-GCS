package com.kft.gcs.feature.fly

import androidx.compose.runtime.Immutable
import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.vehicle.GpsFix
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
    /** The HUD strip: "Mode Loiter", "Alt 12.3 m", … Always the same items in the same order, so nothing jumps. */
    val hud: List<HudItem>,
    val message: MessageUi?,
    val overlays: List<MapOverlay>,
    val basemaps: List<TileSourceConfig>,
    val selectedBasemap: TileSourceConfig,
    val cameraRequest: CameraRequest?,
    /** True once there is a position to centre on. */
    val canCenter: Boolean,
)

data class HudItem(val label: String, val value: String, val warning: Boolean = false)

data class MessageUi(val text: String, val severity: Severity)

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
)

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
