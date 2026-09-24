package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
import com.divpundir.mavlink.definitions.ardupilotmega.PlaneMode
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.GpsRawInt
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.common.SysStatus
import com.divpundir.mavlink.definitions.common.VfrHud
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.math.PI

/**
 * Everything the GCS knows about the vehicle, in plain units (metres, m/s, degrees, volts). Null means "not
 * reported yet" or "the vehicle says it doesn't know"; the UI shows a dash instead of a misleading zero.
 * No MAVLink types in here: screens and ViewModels only ever see this.
 */
data class VehicleState(
    /** True while the vehicle's heartbeat is arriving. Everything else keeps its last value when this goes false. */
    val connected: Boolean = false,
    val position: LatLon? = null,
    /** Height above home (the takeoff point). */
    val altitudeRelativeM: Double? = null,
    val altitudeMslM: Double? = null,
    /** Compass heading, 0..360, 0 = north. */
    val headingDeg: Double? = null,
    val groundspeedMs: Double? = null,
    val climbRateMs: Double? = null,
    val rollDeg: Double? = null,
    val pitchDeg: Double? = null,
    val gpsFix: GpsFix = GpsFix.NO_GPS,
    val satellites: Int? = null,
    val batteryVolts: Double? = null,
    val batteryPercent: Int? = null,
    val armed: Boolean = false,
    val flightMode: String? = null,
    /** The latest STATUSTEXT, e.g. "PreArm: GPS not healthy". */
    val lastMessage: StatusMessage? = null,
)

/** GPS fix quality, from GPS_RAW_INT.fix_type. Survey work wants at least 3D, ideally RTK. */
enum class GpsFix(val label: String) {
    NO_GPS("No GPS"), NO_FIX("No fix"), FIX_2D("2D"), FIX_3D("3D"), DGPS("DGPS"), RTK_FLOAT("RTK float"), RTK_FIXED("RTK fixed"),
}

data class StatusMessage(val text: String, val severity: Severity)

/** MAV_SEVERITY collapsed to what the UI colours differently. */
enum class Severity { ERROR, WARNING, INFO }

/**
 * Applies one telemetry message to the state. Pure, so each conversion (units, "unknown" sentinels) is tested
 * with hand-calculated values. Messages it doesn't use leave the state unchanged.
 */
internal fun VehicleState.reduce(message: MavMessage<*>): VehicleState = when (message) {
    is GlobalPositionInt -> copy(
        // ArduPilot sends 0,0 before it has a position; a vehicle at exactly 0°N 0°E is not a case we plan for.
        position = if (message.lat == 0 && message.lon == 0) null else LatLon.fromE7(message.lat, message.lon),
        altitudeMslM = message.alt / 1000.0,           // mm -> m
        altitudeRelativeM = message.relativeAlt / 1000.0,
        headingDeg = if (message.hdg == UINT16_UNKNOWN) headingDeg else message.hdg.toInt() / 100.0, // cdeg -> deg
    )
    is VfrHud -> copy(groundspeedMs = message.groundspeed.toDouble(), climbRateMs = message.climb.toDouble())
    is Attitude -> copy(rollDeg = message.roll.toDegrees(), pitchDeg = message.pitch.toDegrees())
    is GpsRawInt -> copy(
        gpsFix = gpsFixOf(message.fixType.value),
        satellites = message.satellitesVisible.toInt().takeIf { it != 255 },
    )
    is SysStatus -> copy(
        batteryVolts = if (message.voltageBattery == UINT16_UNKNOWN) null else message.voltageBattery.toInt() / 1000.0, // mV -> V
        batteryPercent = message.batteryRemaining.toInt().takeIf { it >= 0 },  // -1 = not estimated
    )
    is Statustext -> copy(lastMessage = StatusMessage(message.text.trimEnd('\u0000'), severityOf(message.severity.value)))
    else -> this
}

/** ArduPilot mode name for a custom mode number, e.g. Copter 5 -> "Loiter", Plane 10 -> "Auto". */
fun flightModeName(kind: VehicleKind, customMode: UInt): String {
    val raw = when (kind) {
        VehicleKind.COPTER -> CopterMode.getEntryFromValueOrNull(customMode)?.name
        VehicleKind.PLANE -> PlaneMode.getEntryFromValueOrNull(customMode)?.name
        VehicleKind.UNKNOWN -> null
    } ?: return "Mode $customMode"
    return raw.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

/** GPS_FIX_TYPE values. STATIC (7) and PPP (8) aren't RTK, so they count as a plain 3D fix. */
private fun gpsFixOf(value: UInt) = when (value) {
    0u -> GpsFix.NO_GPS
    1u -> GpsFix.NO_FIX
    2u -> GpsFix.FIX_2D
    3u, 7u, 8u -> GpsFix.FIX_3D
    4u -> GpsFix.DGPS
    5u -> GpsFix.RTK_FLOAT
    6u -> GpsFix.RTK_FIXED
    else -> GpsFix.NO_GPS
}

private const val UINT16_UNKNOWN: UShort = 65535u

private fun Float.toDegrees() = this * 180.0 / PI

// MAV_SEVERITY: 0-3 are emergency..error, 4 warning, 5+ notice/info/debug.
private fun severityOf(value: UInt) = when {
    value <= 3u -> Severity.ERROR
    value == 4u -> Severity.WARNING
    else -> Severity.INFO
}
