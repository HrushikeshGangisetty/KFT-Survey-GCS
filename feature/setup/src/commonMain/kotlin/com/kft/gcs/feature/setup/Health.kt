package com.kft.gcs.feature.setup

import com.kft.gcs.core.vehicle.VehicleState
import kotlin.math.round

/** A check's result. [UNKNOWN]: the vehicle hasn't reported it. Ordered worst last, so `maxOf` is the page's verdict. */
enum class Verdict(val label: String) { PASS("Pass"), UNKNOWN("Not reported"), WARN("Check"), FAIL("Fail") }

/** One line of the vibration / EKF page: what was measured, the limit it is judged against, and the verdict. */
data class HealthRow(val label: String, val value: String, val limit: String, val verdict: Verdict)

data class HealthSection(val title: String, val rows: List<HealthRow>)

/*
 * The limits, and where they come from:
 * - Vibration: ArduPilot's "Measuring Vibration" page (ardupilot.org, common-measuring-vibration): below 30 m/s/s is
 *   normally fine, above 30 may cause problems, above 60 nearly always causes problems with position or altitude hold.
 *   The value is AP_InertialSensor's vibration level, as VIBRATION sends it (GCS_Common.cpp `send_vibration`).
 * - Clipping: the same page. An accelerometer that clips (hits its measuring limit) loses data; the count should stay 0.
 *   VIBRATION's counts are since boot, so any clip at all is worth a look.
 * - EKF variances: EKF_STATUS_REPORT's test ratios (1.0 = the EKF rejects the measurement). Copter's EKF failsafe and
 *   arming check use FS_EKF_THRESH, default 0.8 (ArduCopter/ekf_check.cpp `ekf_over_threshold`, KFT fork 8e89f41a): a
 *   value at or over it fails here. 0.5 is where Mission Planner's EKF status window turns from green to orange (read
 *   for the number only), so 0.5 up to the threshold is "Check". A Plane has no FS_EKF_THRESH; 0.8 is used.
 * - EKF flags: ardupilotmega.xml EKF_STATUS_FLAGS.
 */
private const val VIBE_WARN = 30.0
private const val VIBE_FAIL = 60.0
private const val EKF_WARN = 0.5
private const val EKF_DEFAULT_THRESH = 0.8

// EKF_STATUS_FLAGS bits.
private const val ATTITUDE = 1u
private const val VELOCITY_HORIZ = 2u
private const val POS_HORIZ_REL = 8u
private const val POS_HORIZ_ABS = 16u
private const val CONST_POS_MODE = 128u
private const val UNINITIALIZED = 1024u
private const val GPS_GLITCHING = 32768u

/**
 * Vibration and EKF health of [v] against ArduPilot's limits (see above). [values] gives FS_EKF_THRESH when the vehicle
 * has it. Read-only and live: it is meant to be watched with the motors running too, since vibration on the ground
 * with the motors off says little.
 */
fun healthSections(v: VehicleState, values: Map<String, Float>): List<HealthSection> {
    val vibeLimit = "under ${number(VIBE_WARN)} good, over ${number(VIBE_FAIL)} fail"
    val vibration = v.vibration?.let { vib ->
        listOf("X" to vib.x, "Y" to vib.y, "Z" to vib.z).map { (axis, level) ->
            HealthRow("Vibration $axis", "${number(round(level * 10) / 10)} m/s/s", vibeLimit, levelVerdict(level, VIBE_WARN, VIBE_FAIL))
        } + HealthRow(
            "Accelerometer clipping",
            vib.clipping.joinToString(" · "),
            "0 since boot",
            if (vib.clipping.all { it == 0L }) Verdict.PASS else Verdict.WARN,
        )
    } ?: listOf(HealthRow("Vibration", "—", vibeLimit, Verdict.UNKNOWN))

    val fsThresh = values["FS_EKF_THRESH"]?.toDouble()?.takeIf { it > 0 }
    val thresh = fsThresh ?: EKF_DEFAULT_THRESH
    val warn = minOf(EKF_WARN, thresh)
    val ekfLimit = "under ${number(warn)} good, ${number(thresh)} or more fail" + if (fsThresh != null) " (FS_EKF_THRESH)" else ""
    val variances = v.ekfVariances?.let { e ->
        listOf("Velocity" to e.velocity, "Horizontal position" to e.posHoriz, "Vertical position" to e.posVert, "Compass" to e.compass)
            .map { (label, ratio) -> HealthRow("EKF $label", number(round(ratio * 100) / 100), ekfLimit, levelVerdict(ratio, warn, thresh)) }
    } ?: listOf(HealthRow("EKF variances", "—", ekfLimit, Verdict.UNKNOWN))

    return listOf(
        HealthSection("Vibration", vibration),
        HealthSection("Position estimate (EKF)", listOf(ekfFlagsRow(v.ekfFlags)) + variances),
    )
}

/** The worst verdict of [sections]: the page's answer. */
fun overallVerdict(sections: List<HealthSection>): Verdict = sections.flatMap { it.rows }.maxOfOrNull { it.verdict } ?: Verdict.UNKNOWN

/** Below [warn] passes, from [warn] to below [fail] is "Check", [fail] and above fails. */
private fun levelVerdict(value: Double, warn: Double, fail: Double) = when {
    value >= fail -> Verdict.FAIL
    value >= warn -> Verdict.WARN
    else -> Verdict.PASS
}

/**
 * The EKF's own flags in one row. Fails when it isn't running (uninitialised, no attitude) or the GPS is glitching.
 * "Check" when it has no horizontal position yet (relative counts: a VisionNav or optical-flow vehicle has no absolute
 * one) or holds a constant position, which ArduPilot does until it has one: Loiter, Auto and RTL need it.
 */
private fun ekfFlagsRow(flags: UInt?): HealthRow {
    val limit = "attitude, velocity and position"
    if (flags == null) return HealthRow("EKF status", "—", limit, Verdict.UNKNOWN)
    fun has(bit: UInt) = flags and bit != 0u
    val (value, verdict) = when {
        has(UNINITIALIZED) || !has(ATTITUDE) -> "not initialised" to Verdict.FAIL
        has(GPS_GLITCHING) -> "GPS glitching" to Verdict.FAIL
        !has(POS_HORIZ_ABS) && !has(POS_HORIZ_REL) -> "no horizontal position yet" to Verdict.WARN
        !has(VELOCITY_HORIZ) -> "no horizontal velocity yet" to Verdict.WARN
        has(CONST_POS_MODE) -> "holding a constant position (no position source)" to Verdict.WARN
        else -> "attitude, velocity and position good" to Verdict.PASS
    }
    return HealthRow("EKF status", value, limit, verdict)
}
