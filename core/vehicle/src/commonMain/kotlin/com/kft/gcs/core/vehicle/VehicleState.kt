package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
import com.divpundir.mavlink.definitions.ardupilotmega.PlaneMode
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.GpsRawInt
import com.divpundir.mavlink.definitions.common.HomePosition
import com.divpundir.mavlink.definitions.common.MissionCurrent
import com.divpundir.mavlink.definitions.common.MissionItemReached
import com.divpundir.mavlink.definitions.common.MissionState
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.common.SysStatus
import com.divpundir.mavlink.definitions.common.VfrHud
import com.divpundir.mavlink.definitions.standard.AutopilotVersion
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
    /** Copter or Plane, from the heartbeat. Kept after the link drops, like the mode, so a plan keeps its defaults. */
    val vehicleKind: VehicleKind? = null,
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
    /** Where the vehicle will return to, and what mission seq 0 is (spec S11). Null until HOME_POSITION arrives. */
    val home: Home? = null,
    /** Firmware version from AUTOPILOT_VERSION, e.g. "4.6.3" or "4.7.0-dev". */
    val firmwareVersion: String? = null,
    /** Where the vehicle is in its mission. Null until the first MISSION_CURRENT. */
    val mission: MissionProgress? = null,
    /** The KFT login (spec S12) for this link. Null until a vehicle is heard. */
    val login: KftLoginStatus? = null,
)

/**
 * Mission progress. Seq numbers are the vehicle's, so item 1 is the first item after home (spec S11).
 * @property total items excluding home, or null if the firmware doesn't report it.
 * @property lastReached the last item the vehicle reported reaching (MISSION_ITEM_REACHED), if any.
 */
data class MissionProgress(val current: Int, val total: Int?, val lastReached: Int?, val complete: Boolean)

/** The vehicle's home. [altitudeMslM] is above mean sea level, which is what mission seq 0 carries. */
data class Home(val position: LatLon, val altitudeMslM: Double)

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
    // KFTCH1:/KFTCH2: are the KFT login challenge (KftLogin reads them); they're protocol, not a message for the pilot.
    is Statustext -> message.text.trimEnd('\u0000').let { text ->
        if (text.startsWith("KFTCH")) this else copy(lastMessage = StatusMessage(text, severityOf(message.severity.value)))
    }
    // HOME_POSITION (common.xml). ArduPilot sends it only once home is set, on request, and on every change (for
    // Copter, arming moves home to where it armed), plus at the interval VehicleRepository asks for.
    is HomePosition -> copy(home = Home(LatLon.fromE7(message.latitude, message.longitude), message.altitude / 1000.0)) // mm -> m
    // MISSION_CURRENT (common.xml), in ArduPilot's EXTENDED_STATUS stream group. ArduPilot fills `total` with the
    // item count minus home (GCS_Common.cpp `send_mission_current`); 0 means "not supported", 65535 "no mission".
    is MissionCurrent -> copy(
        mission = MissionProgress(
            current = message.seq.toInt(),
            total = message.total.toInt().takeIf { it != 0 && it != 65535 },
            lastReached = mission?.lastReached,
            complete = message.missionState.value == MissionState.COMPLETE.value,
        ),
    )
    // MISSION_ITEM_REACHED (common.xml): ArduPilot sends it once per item, as an event, not in a stream.
    is MissionItemReached -> copy(
        mission = (mission ?: MissionProgress(message.seq.toInt(), null, null, false)).copy(lastReached = message.seq.toInt()),
    )
    // AUTOPILOT_VERSION is in standard.xml.
    is AutopilotVersion -> copy(firmwareVersion = firmwareVersionName(message.flightSwVersion))
    else -> this
}

/**
 * Decodes AUTOPILOT_VERSION.flight_sw_version. ArduPilot packs it as major, minor, patch, FIRMWARE_VERSION_TYPE,
 * one byte each from the top (GCS_Common.cpp `send_autopilot_version`). Type 255 is an official release.
 */
internal fun firmwareVersionName(packed: UInt): String {
    val number = "${packed shr 24}.${(packed shr 16) and 0xFFu}.${(packed shr 8) and 0xFFu}"
    val suffix = when (packed and 0xFFu) {
        0u -> "-dev"
        64u -> "-alpha"
        128u -> "-beta"
        192u -> "-rc"
        else -> ""
    }
    return number + suffix
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
