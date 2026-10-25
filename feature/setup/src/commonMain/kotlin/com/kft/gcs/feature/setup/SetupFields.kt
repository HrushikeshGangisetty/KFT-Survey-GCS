package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.math.abs

/** How a setup field is edited and checked. */
sealed interface FieldKind {
    /** One documented value: code to label. [danger] codes get a warning before they are sent. */
    data class Choice(val options: List<Pair<Int, String>>, val danger: Set<Int> = emptySet()) : FieldKind

    /** A number in [range] (inclusive) in [unit]. 0 meaning "off" is part of [range] when the parameter allows it. */
    data class Number(val range: ClosedFloatingPointRange<Double>, val unit: String) : FieldKind

    /** A bitmask: bit number to label. */
    data class Bits(val bits: List<Pair<Int, String>>) : FieldKind
}

/**
 * One parameter on a Setup page: its name, what the operator reads instead, and how it is edited. [reboot]: ArduPilot
 * only applies it at boot (@RebootRequired), so the page offers a reboot after it is written.
 */
data class FieldSpec(val param: String, val label: String, val kind: FieldKind, val help: String? = null, val reboot: Boolean = false)

/** A titled group of fields, with an optional [note] under the title. */
data class SectionSpec(val title: String, val fields: List<FieldSpec>, val note: String? = null)

/*
 * Every list below is copied from the KFT fork's own parameter documentation (d91a40ef): the @Values, @Range and
 * @Bitmask comments in ArduCopter/Parameters.cpp, ArduPlane/Parameters.cpp, AP_BattMonitor_Params.cpp and AC_Fence.cpp.
 * Values ArduPilot marks "Removed in 4.0+" are left out. A vehicle that holds a value not listed here still shows it
 * ("3, not a listed value"), so nothing is hidden. Why a fixed list and not the downloaded metadata the Params tab uses:
 * failsafes must be settable in the field without internet, and these few lists change rarely; the Params tab still
 * shows every parameter with its full metadata.
 */

private val COPTER_ACTIONS = listOf(1 to "RTL", 3 to "SmartRTL or RTL", 4 to "SmartRTL or Land", 5 to "Land", 6 to "Auto DO_LAND_START or RTL", 7 to "Brake or Land")

private fun batterySections(low: List<Pair<Int, String>>, critical: List<Pair<Int, String>>, terminate: Set<Int>) = SectionSpec(
    "Battery",
    listOf(
        FieldSpec("BATT_FS_LOW_ACT", "Low battery action", FieldKind.Choice(low, terminate)),
        FieldSpec("BATT_LOW_VOLT", "Low voltage", FieldKind.Number(0.0..100.0, "V"), "0 turns the voltage check off"),
        FieldSpec("BATT_LOW_MAH", "Low remaining capacity", FieldKind.Number(0.0..1_000_000.0, "mAh"), "0 turns the capacity check off"),
        FieldSpec("BATT_FS_CRT_ACT", "Critical battery action", FieldKind.Choice(critical, terminate)),
        FieldSpec("BATT_CRT_VOLT", "Critical voltage", FieldKind.Number(0.0..100.0, "V"), "0 turns the voltage check off"),
        FieldSpec("BATT_CRT_MAH", "Critical remaining capacity", FieldKind.Number(0.0..1_000_000.0, "mAh"), "0 turns the capacity check off"),
    ),
    "First battery monitor. Low must be above critical, or the FC refuses to arm.",
)

private fun fenceSection(actions: List<Pair<Int, String>>) = SectionSpec(
    "Geofence",
    listOf(
        FieldSpec("FENCE_ENABLE", "Fence", FieldKind.Choice(listOf(0 to "Disabled", 1 to "Enabled"))),
        FieldSpec("FENCE_TYPE", "Fence types", FieldKind.Bits(listOf(0 to "Maximum altitude", 1 to "Circle around home", 2 to "Polygons (from a fence file)", 3 to "Minimum altitude"))),
        FieldSpec("FENCE_ACTION", "Breach action", FieldKind.Choice(actions)),
        FieldSpec("FENCE_ALT_MAX", "Maximum altitude", FieldKind.Number(10.0..1000.0, "m"), "Above home"),
        FieldSpec("FENCE_RADIUS", "Circle radius", FieldKind.Number(30.0..10_000.0, "m"), "Around home"),
    ),
)

/** The failsafe page for [kind]. */
fun failsafeSections(kind: VehicleKind?): List<SectionSpec> = when (kind) {
    VehicleKind.PLANE -> listOf(
        SectionSpec(
            "Radio (RC) loss",
            listOf(
                FieldSpec("THR_FAILSAFE", "Throttle failsafe", FieldKind.Choice(listOf(0 to "Disabled", 1 to "Enabled", 2 to "Enabled, no failsafe action"))),
                FieldSpec("THR_FS_VALUE", "Trigger below", FieldKind.Number(925.0..2200.0, "µs"), "Throttle channel PWM below which RC is counted as lost"),
                FieldSpec("FS_SHORT_ACTN", "Short failsafe action", FieldKind.Choice(listOf(0 to "Circle, or no change in AUTO/GUIDED/LOITER", 1 to "Circle", 2 to "FBWA at zero throttle", 3 to "Disabled", 4 to "FBWB"))),
                FieldSpec("FS_SHORT_TIMEOUT", "Short failsafe after", FieldKind.Number(1.0..100.0, "s")),
                FieldSpec("FS_LONG_ACTN", "Long failsafe action", FieldKind.Choice(listOf(0 to "Continue", 1 to "Return to launch", 2 to "Glide", 3 to "Deploy parachute", 4 to "Auto"))),
                FieldSpec("FS_LONG_TIMEOUT", "Long failsafe after", FieldKind.Number(1.0..300.0, "s")),
            ),
        ),
        SectionSpec(
            "Ground station link loss",
            listOf(FieldSpec("FS_GCS_ENABL", "GCS failsafe", FieldKind.Choice(listOf(0 to "Disabled", 1 to "Heartbeat", 2 to "Heartbeat and REMOTE_STATUS RSSI", 3 to "Heartbeat, in AUTO too")))),
            "A Plane uses the long failsafe action and timeout above when the GCS link is lost.",
        ),
        batterySections(
            low = listOf(0 to "None", 1 to "RTL", 2 to "Land", 3 to "Terminate", 4 to "QLand", 6 to "Loiter to QLand"),
            critical = listOf(0 to "None", 1 to "RTL", 2 to "Land", 3 to "Terminate", 4 to "QLand", 5 to "Parachute", 6 to "Loiter to QLand"),
            terminate = setOf(3),
        ),
        fenceSection(listOf(0 to "Report only", 1 to "RTL", 6 to "Guided", 7 to "Guided, throttle pass-through")),
    )
    // Copter, and the Copter page when the vehicle type isn't known yet (the page says so).
    else -> listOf(
        SectionSpec(
            "Radio (RC) loss",
            listOf(
                FieldSpec("FS_THR_ENABLE", "Throttle failsafe", FieldKind.Choice(COPTER_THROTTLE_ACTIONS)),
                FieldSpec("FS_THR_VALUE", "Trigger below", FieldKind.Number(910.0..1100.0, "µs"), "Throttle channel PWM below which RC is counted as lost"),
            ),
            "Set the receiver to send a throttle below this value (or nothing) when it loses the transmitter.",
        ),
        SectionSpec(
            "Ground station link loss",
            listOf(
                FieldSpec("FS_GCS_ENABLE", "GCS failsafe", FieldKind.Choice(listOf(0 to "Disabled") + COPTER_ACTIONS)),
                FieldSpec("FS_GCS_TIMEOUT", "After", FieldKind.Number(2.0..120.0, "s")),
            ),
        ),
        batterySections(
            low = listOf(0 to "None", 1 to "Land", 2 to "RTL", 3 to "SmartRTL or RTL", 4 to "SmartRTL or Land", 5 to "Terminate", 6 to "Auto DO_LAND_START or RTL", 7 to "Brake or Land"),
            critical = listOf(0 to "None", 1 to "Land", 2 to "RTL", 3 to "SmartRTL or RTL", 4 to "SmartRTL or Land", 5 to "Terminate", 6 to "Auto DO_LAND_START or RTL", 7 to "Brake or Land"),
            terminate = setOf(5),
        ),
        SectionSpec(
            "Position estimate (EKF) and crashes",
            listOf(
                FieldSpec("FS_EKF_ACTION", "EKF failsafe action", FieldKind.Choice(listOf(1 to "Land", 2 to "AltHold", 3 to "Land even in Stabilize"))),
                FieldSpec("FS_EKF_THRESH", "EKF variance limit", FieldKind.Number(0.0..1.0, ""), "0.6 strict, 0.8 default, 1.0 relaxed; 0 turns the EKF failsafe off"),
                FieldSpec("FS_CRASH_CHECK", "Crash check (disarm on crash)", FieldKind.Choice(listOf(0 to "Disabled", 1 to "Enabled"))),
                FieldSpec("FS_VIBE_ENABLE", "Vibration failsafe", FieldKind.Choice(listOf(0 to "Disabled", 1 to "Enabled"))),
            ),
        ),
        fenceSection(listOf(0 to "Report only", 1 to "RTL or Land", 2 to "Always Land", 3 to "SmartRTL or RTL or Land", 4 to "Brake or Land", 5 to "SmartRTL or Land")),
    )
}

/** FS_THR_ENABLE's own wording (it differs from FS_GCS_ENABLE's). */
private val COPTER_THROTTLE_ACTIONS = listOf(
    0 to "Disabled", 1 to "Enabled, always RTL", 3 to "Enabled, always Land", 4 to "Enabled, always SmartRTL or RTL",
    5 to "Enabled, always SmartRTL or Land", 6 to "Enabled, Auto DO_LAND_START or RTL", 7 to "Enabled, always Brake or Land",
)

private val COPTER_MODES = listOf(
    0 to "Stabilize", 1 to "Acro", 2 to "AltHold", 3 to "Auto", 4 to "Guided", 5 to "Loiter", 6 to "RTL", 7 to "Circle", 9 to "Land",
    11 to "Drift", 13 to "Sport", 14 to "Flip", 15 to "AutoTune", 16 to "PosHold", 17 to "Brake", 18 to "Throw", 19 to "Avoid_ADSB",
    20 to "Guided_NoGPS", 21 to "Smart_RTL", 22 to "FlowHold", 23 to "Follow", 24 to "ZigZag", 25 to "SystemID", 26 to "Heli_Autorotate",
    27 to "Auto RTL", 28 to "Turtle",
)

private val PLANE_MODES = listOf(
    0 to "Manual", 1 to "Circle", 2 to "Stabilize", 3 to "Training", 4 to "Acro", 5 to "FBWA", 6 to "FBWB", 7 to "Cruise", 8 to "Autotune",
    10 to "Auto", 11 to "RTL", 12 to "Loiter", 13 to "Takeoff", 14 to "Avoid_ADSB", 15 to "Guided", 17 to "QStabilize", 18 to "QHover",
    19 to "QLoiter", 20 to "QLand", 21 to "QRTL", 22 to "QAutotune", 23 to "QAcro", 24 to "Thermal", 25 to "Loiter to QLand",
)

/** The PWM band of each mode-switch position, as ArduPilot reads it (see [modeSlot]). */
val SLOT_BANDS = listOf("≤ 1230 µs", "1231–1360 µs", "1361–1490 µs", "1491–1620 µs", "1621–1749 µs", "≥ 1750 µs")

/** The flight-mode page for [kind]: the switch channel, then FLTMODE1..6. */
fun flightModeSections(kind: VehicleKind?): List<SectionSpec> {
    val plane = kind == VehicleKind.PLANE
    val channels = (if (plane) (1..16) else (5..15)).map { it to "Channel $it" }
    val modes = if (plane) PLANE_MODES else COPTER_MODES
    return listOf(
        SectionSpec(
            "Mode switch",
            listOf(FieldSpec("FLTMODE_CH", "Switch channel", FieldKind.Choice(listOf(0 to "Disabled") + channels))) +
                (1..6).map { FieldSpec("FLTMODE$it", "Position $it", FieldKind.Choice(modes), SLOT_BANDS[it - 1]) },
            "The pilot picks the mode with this switch. The GCS never changes the mode itself.",
        ),
    )
}

/**
 * Which mode-switch position (0..5) a PWM selects, exactly as ArduPilot reads a 6-position switch
 * (RC_Channel.cpp `read_6pos_switch`, KFT fork d91a40ef): below 1231, 1361, 1491, 1621, 1750 µs. At or beyond
 * 800/2200 µs (RC_MIN_LIMIT_PWM / RC_MAX_LIMIT_PWM) the reading is an error and the FC ignores it: null.
 */
fun modeSlot(pwm: Int): Int? = when {
    pwm <= 800 || pwm >= 2200 -> null
    pwm < 1231 -> 0
    pwm < 1361 -> 1
    pwm < 1491 -> 2
    pwm < 1621 -> 3
    pwm < 1750 -> 4
    else -> 5
}

/** [value] as the operator reads it for [kind]: the choice's label, the number with its unit, the bits' names. */
fun displayValue(kind: FieldKind, value: Float): String = when (kind) {
    is FieldKind.Choice -> kind.options.firstOrNull { it.first.toFloat() == value }?.second ?: "${number(value.toDouble())}, not a listed value"
    is FieldKind.Number -> listOf(number(value.toDouble()), kind.unit).filter { it.isNotEmpty() }.joinToString(" ")
    is FieldKind.Bits -> kind.bits.filter { (bit, _) -> (value.toLong() shr bit) and 1L == 1L }.joinToString(", ") { it.second }.ifEmpty { "None" }
}

/** Why [value] can't be sent for [kind], or null. The type check (whole numbers, storage range) is done before this. */
fun checkValue(kind: FieldKind, value: Float): String? = when (kind) {
    is FieldKind.Choice -> if (kind.options.none { it.first.toFloat() == value }) "Pick one of the listed values" else null
    is FieldKind.Number -> if (value.toDouble() !in kind.range) "Allowed range is ${number(kind.range.start)} to ${number(kind.range.endInclusive)} ${kind.unit}".trimEnd() else null
    is FieldKind.Bits -> {
        val allowed = kind.bits.fold(0L) { mask, (bit, _) -> mask or (1L shl bit) }
        if (value.toLong() and allowed.inv() != 0L) "Only the listed bits can be set" else null
    }
}

/**
 * Combinations the FC refuses to arm with, given every value it holds ([values], by name). Shown on the page, and as a
 * warning before a write that would cause one. Not a refusal to send: the operator may be fixing the pair in two steps.
 *
 * - Battery (AP_BattMonitor_Backend.cpp `arming_checks`): with both set (> 0), low must be above critical, for volts and
 *   for mAh ("voltage failsafe critical >= low").
 * - Copter throttle failsafe (ArduCopter/AP_Arming.cpp `parameter_checks`, "Check FS_THR_VALUE"): while FS_THR_ENABLE is
 *   on, the throttle channel's minimum (RCn_MIN, n = RCMAP_THROTTLE) must be more than 10 µs above FS_THR_VALUE, and
 *   FS_THR_VALUE at least 910.
 */
fun armingProblems(kind: VehicleKind?, values: Map<String, Float>): List<String> = buildList {
    fun inverted(low: String, critical: String): Boolean {
        val l = values[low] ?: return false
        val c = values[critical] ?: return false
        return l > 0 && c > 0 && l <= c
    }
    if (inverted("BATT_LOW_VOLT", "BATT_CRT_VOLT")) add("Battery: the low voltage must be above the critical voltage.")
    if (inverted("BATT_LOW_MAH", "BATT_CRT_MAH")) add("Battery: the low capacity must be above the critical capacity.")
    if (kind != VehicleKind.PLANE && (values["FS_THR_ENABLE"] ?: 0f) != 0f) {
        val fs = values["FS_THR_VALUE"]
        val channel = values["RCMAP_THROTTLE"]?.toInt() ?: 3
        val min = values["RC${channel}_MIN"]
        if (fs != null && min != null && (min <= fs + 10 || fs < 910)) {
            add("Throttle failsafe: RC${channel}_MIN (${number(min.toDouble())} µs) must be more than 10 µs above FS_THR_VALUE (${number(fs.toDouble())} µs). Lower FS_THR_VALUE or recalibrate the radio.")
        }
    }
}

/** A number without a needless ".0", and without float noise ("0.1", not "0.10000000149"). */
internal fun number(d: Double): String {
    val rounded = kotlin.math.round(d * 1000) / 1000
    return if (abs(rounded - kotlin.math.round(rounded)) < 1e-9) kotlin.math.round(rounded).toLong().toString() else rounded.toString()
}
