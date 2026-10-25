package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.Home
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CameraFeedback
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
import com.divpundir.mavlink.definitions.ardupilotmega.EkfStatusReport
import com.divpundir.mavlink.definitions.ardupilotmega.PlaneMode
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.GpsRawInt
import com.divpundir.mavlink.definitions.common.HomePosition
import com.divpundir.mavlink.definitions.common.MissionCurrent
import com.divpundir.mavlink.definitions.common.MissionItemReached
import com.divpundir.mavlink.definitions.common.MissionState
import com.divpundir.mavlink.definitions.common.NamedValueInt
import com.divpundir.mavlink.definitions.common.ParamValue
import com.divpundir.mavlink.definitions.common.RcChannels
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.common.SysStatus
import com.divpundir.mavlink.definitions.common.VfrHud
import com.divpundir.mavlink.definitions.standard.AutopilotVersion
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mavlink.signing.SigningStatus
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
    /** Where the camera took each photo since the link came up, oldest first (CAMERA_FEEDBACK), at most [MAX_PHOTOS]. */
    val photos: List<LatLon> = emptyList(),
    /** The few parameters the GCS itself reads ([WATCHED_PARAMS]), as last reported in a PARAM_VALUE. */
    val watchedParams: Map<String, Float> = emptyMap(),
    /** EKF_STATUS_REPORT.flags (ardupilotmega.xml EKF_STATUS_FLAGS bitmask), as the FC reports it. Null until the first. */
    val ekfFlags: UInt? = null,
    /** The latest estimator STATUSTEXTs ([isEstimatorText]), oldest first, at most [MAX_ESTIMATOR_MESSAGES]. */
    val estimatorMessages: List<StatusMessage> = emptyList(),
    /** How this vehicle signs its traffic on its link (PRD FR-8), from the link's signing layer. */
    val signing: SigningStatus = SigningStatus.Unsigned,
    /**
     * How many frames the FC itself has rejected for a bad or missing signature, from its NAMED_VALUE_INT "SIGFAIL"
     * (the S8 firmware change). Null until the FC reports one, which a stock ArduPilot never does.
     */
    val fcSignatureFailures: Int? = null,
    /**
     * What the RC receiver gives the FC, channel 1 first, in µs (RC_CHANNELS). As many channels as the receiver
     * provides; empty when there is no RC input.
     */
    val rcChannels: List<Int> = emptyList(),
    /** Board and firmware identity from AUTOPILOT_VERSION. Null until it arrives (it is requested at connect). */
    val autopilot: AutopilotIdentity? = null,
) {
    /**
     * The vehicle's simple geofence, or null when it is off or not known yet. ArduPilot's AC_Fence parameters (Copter
     * and Plane, parameter docs 4.5+): FENCE_ENABLE 0/1; FENCE_TYPE is a bitmask, bit 0 = maximum altitude, bit 1 =
     * circle centred on home; FENCE_ALT_MAX and FENCE_RADIUS are metres.
     * ponytail: polygon fences (FENCE_TYPE bit 2) are stored as a fence "mission", which the GCS doesn't read.
     */
    val fence: Fence?
        get() {
            if ((watchedParams["FENCE_ENABLE"] ?: 0f) < 1f) return null
            val type = watchedParams["FENCE_TYPE"]?.toInt() ?: return null
            return Fence(
                altMaxM = watchedParams["FENCE_ALT_MAX"]?.toDouble()?.takeIf { type and 1 != 0 },
                radiusM = watchedParams["FENCE_RADIUS"]?.toDouble()?.takeIf { type and 2 != 0 },
            )
        }
}

/**
 * The voltage under which the vehicle's own low-battery failsafe acts (ArduPilot BATT_LOW_VOLT; 0 = that failsafe is
 * off), or null when the vehicle hasn't reported it.
 */
val VehicleState.batteryLowVolts: Double? get() = watchedParams["BATT_LOW_VOLT"]?.toDouble()?.takeIf { it > 0 }

/** An enabled fence's limits; a null limit is one the fence doesn't have. Altitude is above home. */
data class Fence(val altMaxM: Double?, val radiusM: Double?)

/**
 * STATUSTEXTs about the FC's position estimate, kept apart so a source change isn't lost behind later messages.
 * The prefixes are ArduPilot's own (KFT fork 23c97b96): "EKF3 IMU0 is using external nav data", "EKF3 lane switch 1"
 * (AP_NavEKF3), "AHRS: EKF3 active" (AP_AHRS), "VisOdom: reset" (AP_VisualOdom), "Using EKF Source Set 2"
 * (RC_Channel.cpp) and the source-switching Lua applets ("Auto switched to Source 2").
 */
fun isEstimatorText(text: String) =
    text.startsWith("EKF") || text.startsWith("AHRS:") || text.startsWith("VisOdom") || "Source" in text

const val MAX_ESTIMATOR_MESSAGES = 5

/** Parameters the GCS reads for its own checks; asked for at connect ([VehicleRepository]) and kept in [VehicleState.watchedParams]. */
val WATCHED_PARAMS = listOf("FENCE_ENABLE", "FENCE_TYPE", "FENCE_ALT_MAX", "FENCE_RADIUS", "BATT_LOW_VOLT")

/**
 * Photos kept for the map. A day's survey is a few thousand; beyond this the oldest are dropped from the map.
 * ponytail: the count shown in Fly is then capped too; count img_idx instead if a single flight ever takes more.
 */
const val MAX_PHOTOS = 10_000

/** The NAMED_VALUE_INT name under which the KFT firmware reports how many frames it rejected for their signature (S8). */
const val SIGFAIL_NAME = "SIGFAIL"

/**
 * Mission progress. Seq numbers are the vehicle's, so item 1 is the first item after home (spec S11).
 * @property total items excluding home, or null if the firmware doesn't report it.
 * @property lastReached the last item the vehicle reported reaching (MISSION_ITEM_REACHED), if any.
 */
data class MissionProgress(val current: Int, val total: Int?, val lastReached: Int?, val complete: Boolean)

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
        if (text.startsWith("KFTCH")) this else StatusMessage(text, severityOf(message.severity.value)).let { m ->
            copy(
                lastMessage = m,
                estimatorMessages = if (isEstimatorText(text)) (estimatorMessages + m).takeLast(MAX_ESTIMATOR_MESSAGES) else estimatorMessages,
            )
        }
    }
    // EKF_STATUS_REPORT (ardupilotmega.xml #193). ArduPilot streams it in the EXTRA3 group (ArduCopter and ArduPlane
    // GCS_Mavlink.cpp, KFT fork 23c97b96), which the REQUEST_DATA_STREAM(ALL) at connect turns on.
    is EkfStatusReport -> copy(ekfFlags = message.flags.value)
    // The KFT firmware reports its signature-failure counter as a named value (S8); any other name is not ours to read.
    is NamedValueInt -> if (message.name.trimEnd(' ') == SIGFAIL_NAME) copy(fcSignatureFailures = message.value) else this
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
    // CAMERA_FEEDBACK (ardupilotmega.xml #180). ArduPilot sends one per photo taken (AP_Camera_Backend::log_picture,
    // master 2026-09): straight away when no feedback pin is set, or when the hot-shoe pin confirms the shot.
    // lat/lng are the vehicle's position from the AHRS at that moment; 0,0 means it had none.
    is CameraFeedback -> if (message.lat == 0 && message.lng == 0) this
        else copy(photos = (photos + LatLon.fromE7(message.lat, message.lng)).takeLast(MAX_PHOTOS))
    // PARAM_VALUE (common.xml #22): the answer to a read, a set or a list request, whoever asked.
    is ParamValue -> message.paramId.trimEnd('\u0000').let { name ->
        if (name in WATCHED_PARAMS) copy(watchedParams = watchedParams + (name to message.paramValue)) else this
    }
    // AUTOPILOT_VERSION is in standard.xml.
    is AutopilotVersion -> copy(firmwareVersion = firmwareVersionName(message.flightSwVersion), autopilot = autopilotIdentity(message))
    // RC_CHANNELS (common.xml #65), in ArduPilot's RC_CHANNELS stream group. chancount is how many channels the
    // receiver provides (RC_Channels::get_valid_channel_count, 0 = no RC); the 18 raw fields follow in order.
    is RcChannels -> with(message) {
        val all = listOf(chan1Raw, chan2Raw, chan3Raw, chan4Raw, chan5Raw, chan6Raw, chan7Raw, chan8Raw, chan9Raw,
            chan10Raw, chan11Raw, chan12Raw, chan13Raw, chan14Raw, chan15Raw, chan16Raw, chan17Raw, chan18Raw)
        copy(rcChannels = all.take(chancount.toInt()).map { it.toInt() })
    }
    else -> this
}

/**
 * Who the flight controller is, for the Hardware ID page.
 * @property gitHash the firmware's git commit (first 8 characters), so a KFT build can be traced to its source.
 * @property boardId ArduPilot's board type number (APJ_BOARD_ID, e.g. 140 = CubeOrange), or null when the build has none.
 * @property usbId the board's USB vendor:product ids in hex ("2dae:1016"), or null when the build doesn't set them.
 * @property uid the processor's unique id in hex: the same board always shows the same id, whatever firmware it runs.
 */
data class AutopilotIdentity(val firmware: String, val gitHash: String?, val boardId: Int?, val usbId: String?, val uid: String?)

/**
 * ArduPilot fills AUTOPILOT_VERSION like this (GCS_Common.cpp `send_autopilot_version`, KFT fork d91a40ef):
 * board_version = APJ_BOARD_ID << 16; flight_custom_version = the git hash as 8 ASCII characters; vendor_id/product_id
 * = the board's USB ids; uid = 0 always; uid2 = `get_system_id_unformatted`, 12 bytes on STM32 boards, zero-padded to 18.
 * Trailing zero bytes are trimmed, but never below 12, so an STM32 id ending in a zero byte still shows whole.
 */
internal fun autopilotIdentity(m: AutopilotVersion): AutopilotIdentity {
    val hash = m.flightCustomVersion.takeWhile { it != 0.toUByte() }.map { it.toInt().toChar() }.joinToString("")
    val uid = m.uid2.dropLastWhile { it == 0.toUByte() }.let { if (it.isEmpty()) it else m.uid2.take(maxOf(it.size, 12)) }
    return AutopilotIdentity(
        firmware = firmwareVersionName(m.flightSwVersion),
        gitHash = hash.ifEmpty { null },
        boardId = (m.boardVersion shr 16).toInt().takeIf { it != 0 },
        usbId = if (m.vendorId.toInt() == 0 && m.productId.toInt() == 0) null else "${hex4(m.vendorId)}:${hex4(m.productId)}",
        uid = uid.joinToString("") { it.toString(16).padStart(2, '0') }.ifEmpty { null },
    )
}

private fun hex4(v: UShort) = v.toString(16).padStart(4, '0')

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

/**
 * The mode name exactly as ArduPilot writes it, e.g. Copter 5 -> "Loiter", Copter 6 -> "RTL", Plane 5 -> "FBWA".
 * These are the strings of each mode's `name()` in `ArduCopter/mode.h` and `ArduPlane/mode.h` (master, 2026-09),
 * which is what ArduPilot's own messages use. The MAVLink enum names (`COPTER_MODE_*`, `PLANE_MODE_*` in
 * ardupilotmega.xml) are title-cased, and [MODE_NAME_EXCEPTIONS] holds the ones where that gives a different string.
 */
fun flightModeName(kind: VehicleKind, customMode: UInt): String {
    val raw = when (kind) {
        VehicleKind.COPTER -> CopterMode.getEntryFromValueOrNull(customMode)?.name
        VehicleKind.PLANE -> PlaneMode.getEntryFromValueOrNull(customMode)?.name
        VehicleKind.UNKNOWN -> null
    } ?: return "Mode $customMode"
    return MODE_NAME_EXCEPTIONS[raw] ?: raw.split('_').joinToString(" ") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

/** MAVLink enum name -> ArduPilot's `name()`, where title case isn't enough (acronyms, "Q" modes, spelled-out names). */
private val MODE_NAME_EXCEPTIONS = mapOf(
    "RTL" to "RTL", "SMART_RTL" to "Smart RTL", "AUTO_RTL" to "Auto RTL", "ALT_HOLD" to "Altitude Hold",
    "POSHOLD" to "Position Hold", "FLOWHOLD" to "Flow Hold", "GUIDED_NOGPS" to "Guided No GPS", "AVOID_ADSB" to "Avoid ADSB",
    "SYSTEMID" to "SystemID", "ZIGZAG" to "ZigZag",
    "FLY_BY_WIRE_A" to "FBWA", "FLY_BY_WIRE_B" to "FBWB", "INITIALIZING" to "Initialising", "LOITER_ALT_QLAND" to "Loiter to QLand",
    "QSTABILIZE" to "QStabilize", "QHOVER" to "QHover", "QLOITER" to "QLoiter", "QLAND" to "QLand", "QRTL" to "QRTL",
    "QACRO" to "QAcro", "QAUTOTUNE" to "QAutotune",
)

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
internal fun severityOf(value: UInt) = when {
    value <= 3u -> Severity.ERROR
    value == 4u -> Severity.WARNING
    else -> Severity.INFO
}
