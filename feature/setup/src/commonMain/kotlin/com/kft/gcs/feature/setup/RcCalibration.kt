package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleKind

/** The lowest and highest PWM a channel was seen at while the sticks and switches were swept. */
data class RcRange(val min: Int, val max: Int)

/** [ranges] widened by one RC_CHANNELS reading. A channel seen for the first time starts at its reading. */
fun widen(ranges: List<RcRange?>, pwm: List<Int>): List<RcRange?> = List(maxOf(ranges.size, pwm.size)) { i ->
    val r = ranges.getOrNull(i)
    val v = pwm.getOrNull(i)?.takeIf { it > 0 } ?: return@List r // 0 = no reading on that channel
    if (r == null) RcRange(v, v) else RcRange(minOf(r.min, v), maxOf(r.max, v))
}

/** One channel's result. [moved] = swept far enough to be calibrated; a channel that didn't move keeps its values. */
data class RcChannelResult(val channel: Int, val role: String?, val min: Int, val trim: Int, val max: Int, val moved: Boolean)

/**
 * What a radio calibration would write. [problems] stop the save; [warnings] are shown but don't.
 * [writes] are (parameter, value) pairs that differ from what the vehicle holds now.
 */
data class RcCalibrationResult(
    val channels: List<RcChannelResult>,
    val problems: List<String>,
    val warnings: List<String>,
    val writes: List<Pair<String, Float>>,
)

/** Channel number → stick, from RCMAP_ROLL/PITCH/THROTTLE/YAW (ArduPilot's defaults 1..4 when not read). */
fun rcRoles(values: Map<String, Float>): Map<Int, String> =
    listOf("RCMAP_ROLL" to "Roll", "RCMAP_PITCH" to "Pitch", "RCMAP_THROTTLE" to "Throttle", "RCMAP_YAW" to "Yaw")
        .mapIndexed { i, (param, role) -> (values[param]?.toInt() ?: (i + 1)) to role }.toMap()

/** A channel counts as moved when its range is at least this wide: a 2-position switch moves ~1000 µs, noise ~10. */
const val RC_MOVED_US = 400

/**
 * Turns the swept [ranges] and the [centre] reading (sticks centred, throttle low) into RCn_MIN / RCn_TRIM / RCn_MAX,
 * the way Mission Planner and QGC do: min and max are the extremes seen, trim is the centre reading (for the throttle,
 * its low position). Checks, from the KFT fork (d91a40ef):
 * - RCn_MIN / RCn_MAX / RCn_TRIM have @Range 800 2200 (RC_Channel.cpp), and ArduPilot treats a reading at or beyond
 *   800/2200 µs as no signal (RC_MIN_LIMIT_PWM / RC_MAX_LIMIT_PWM). A channel swept beyond that is refused.
 * - The four sticks (RCMAP_ROLL/PITCH/THROTTLE/YAW, default 1..4) must all have moved: a stick without a range would
 *   leave the aircraft uncontrollable on that axis.
 * - Copter: the new throttle minimum against FS_THR_VALUE, as in [armingProblems] (a warning).
 *
 * [values] is every parameter the vehicle holds; only RCn_* that exist there are written.
 */
fun rcCalibrationResult(ranges: List<RcRange?>, centre: List<Int>, values: Map<String, Float>, kind: VehicleKind?): RcCalibrationResult {
    val roles = rcRoles(values)
    val problems = mutableListOf<String>()
    if ("RC1_MIN" !in values) problems += "The vehicle's parameters haven't been read, so nothing can be saved. Read them first."
    val writes = mutableListOf<Pair<String, Float>>()
    val channels = ranges.mapIndexedNotNull { i, range ->
        val ch = i + 1
        val r = range ?: return@mapIndexedNotNull null
        val moved = r.max - r.min >= RC_MOVED_US
        val trim = (centre.getOrNull(i)?.takeIf { it > 0 } ?: r.min).coerceIn(r.min, r.max)
        if (moved) {
            if (r.min <= 800 || r.max >= 2200) {
                problems += "Channel $ch went from ${r.min} to ${r.max} µs, beyond what ArduPilot accepts (801–2199). Reduce the transmitter's end points."
            } else {
                listOf("RC${ch}_MIN" to r.min, "RC${ch}_MAX" to r.max, "RC${ch}_TRIM" to trim).forEach { (name, v) ->
                    val now = values[name]
                    if (now != null && now != v.toFloat()) writes += name to v.toFloat()
                }
            }
        }
        RcChannelResult(ch, roles[ch], r.min, trim, r.max, moved)
    }
    roles.forEach { (ch, role) ->
        if (channels.none { it.channel == ch && it.moved }) problems += "$role (channel $ch) didn't move. Move every stick to both ends."
    }
    val warnings = mutableListOf<String>()
    val throttle = roles.entries.first { it.value == "Throttle" }.key
    channels.find { it.channel == throttle && it.moved }?.let { t ->
        val after = values + ("RC${throttle}_MIN" to t.min.toFloat())
        warnings += armingProblems(kind, after).filter { it.startsWith("Throttle failsafe") }
    }
    if (writes.isEmpty() && problems.isEmpty()) warnings += "Nothing to change: the vehicle already holds these values."
    return RcCalibrationResult(channels, problems, warnings, writes)
}
