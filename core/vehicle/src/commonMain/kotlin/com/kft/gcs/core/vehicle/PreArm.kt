package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.definitions.common.MavSysStatusSensor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Why the vehicle won't arm: every failure in the flight controller's latest pre-arm report.
 *
 * ArduPilot runs all its pre-arm checks in one pass and sends one STATUSTEXT per failed check, all at once
 * (`AP_Arming::pre_arm_checks(true)`, which ANDs every check with `&`, so one failure doesn't hide the next). It does
 * that every 30 s while disarmed (`PREARM_DISPLAY_PERIOD`), 4 s after the checks go from passing to failing, when the
 * pilot tries to arm, and on MAV_CMD_RUN_PREARM_CHECKS (KFT fork d91a40ef, AP_Arming.cpp `update`). So a burst of
 * "PreArm:" texts is one complete report, and a new burst replaces the old one: a check that passes now simply isn't
 * in it any more.
 *
 * @property failures the latest report's failures, in the order the FC sent them, without the "PreArm: " prefix.
 *   Empty when the FC said nothing yet or its checks pass.
 * @property lastAt when the latest text of the report arrived, on [VehicleRepository]'s clock. Only used to tell
 *   one burst from the next.
 */
data class PreArmReport(val failures: List<String> = emptyList(), val lastAt: Duration? = null)

/**
 * Texts further apart than this belong to different reports. One report arrives within a second even over a slow
 * radio; the closest two reports can be is 4 s (the "checks just started failing" report after a passing one).
 * ponytail: a report asked for within 2 s of a periodic one merges with it; the same failures, so nothing shows wrong
 * unless a check was fixed in exactly those 2 s. The next report (at most 30 s later) corrects it.
 */
internal val PREARM_REPORT_GAP = 2.seconds

/**
 * The failure in a pre-arm text, or null if [text] isn't one. "PreArm: " is the prefix of the checks
 * (`AP_Arming::check_failed`); "Arm: " is the same function during an arming attempt, for the checks only done then
 * (throttle position, for example). Both are reasons the vehicle won't arm. "Arm: " stays on its texts so the operator
 * can tell they came from an arming attempt.
 */
fun preArmFailure(text: String): String? = when {
    text.startsWith("PreArm: ") -> text.removePrefix("PreArm: ")
    text.startsWith("Arm: ") -> text
    else -> null
}

/** This report with [failure] added, or a new report holding only [failure] if the last text was more than [PREARM_REPORT_GAP] ago. */
internal fun PreArmReport.plusFailure(failure: String, now: Duration): PreArmReport {
    val sameBurst = lastAt != null && now - lastAt <= PREARM_REPORT_GAP
    val kept = if (sameBurst) failures else emptyList()
    return PreArmReport(if (failure in kept) kept else kept + failure, now)
}

/**
 * SYS_STATUS's three sensor bitmasks (common.xml MAV_SYS_STATUS_SENSOR), as the FC sent them: which sensors and
 * controllers it has ([present]), which it uses ([enabled]), and which are working ([health]).
 */
data class SensorBits(val present: UInt, val enabled: UInt, val health: UInt) {
    /**
     * Whether the pre-arm checks pass, from the MAV_SYS_STATUS_PREARM_CHECK bit. ArduPilot sets it present always,
     * enabled when any ARMING_CHECK bit is on, and healthy when armed or the checks pass (GCS.cpp
     * `update_sensor_status_flags`, KFT fork d91a40ef). Null when the checks are off (ARMING_CHECK = 0) or the
     * firmware doesn't report the bit.
     */
    val preArmPassing: Boolean?
        get() = if (present and PREARM == 0u || enabled and PREARM == 0u) null else health and PREARM != 0u

    /** The FC reports the pre-arm bit but doesn't use it: every optional check is off (ARMING_CHECK = 0). */
    val preArmChecksOff: Boolean
        get() = present and PREARM != 0u && enabled and PREARM == 0u

    /** Every sensor the FC has, in [SENSOR_LABELS] order, with its state. */
    val sensors: List<SensorHealth>
        get() = SENSOR_LABELS.mapNotNull { (sensor, label) ->
            val bit = sensor.value
            when {
                present and bit == 0u -> null
                enabled and bit == 0u -> SensorHealth(label, SensorState.DISABLED)
                health and bit == 0u -> SensorHealth(label, SensorState.UNHEALTHY)
                else -> SensorHealth(label, SensorState.HEALTHY)
            }
        }

    private companion object {
        val PREARM = MavSysStatusSensor.MAV_SYS_STATUS_PREARM_CHECK.value
    }
}

enum class SensorState { HEALTHY, UNHEALTHY, DISABLED }

/** One sensor or controller from SYS_STATUS, by the name the operator knows it by. */
data class SensorHealth(val name: String, val state: SensorState)

/**
 * The SYS_STATUS bits shown as sensors, with ArduPilot's meaning where the MAVLink name differs: LASER_POSITION is the
 * rangefinder, VISION_POSITION visual odometry, AHRS the attitude/position estimator (GCS.cpp
 * `update_sensor_status_flags`). The pre-arm bit is shown on its own ([SensorBits.preArmPassing]), and
 * EXTENSION_USED only says another bitmask exists, so neither is here.
 */
private val SENSOR_LABELS = listOf(
    MavSysStatusSensor._3D_GYRO to "Gyro",
    MavSysStatusSensor._3D_GYRO2 to "Gyro 2",
    MavSysStatusSensor._3D_ACCEL to "Accelerometer",
    MavSysStatusSensor._3D_ACCEL2 to "Accelerometer 2",
    MavSysStatusSensor._3D_MAG to "Compass",
    MavSysStatusSensor._3D_MAG2 to "Compass 2",
    MavSysStatusSensor.ABSOLUTE_PRESSURE to "Barometer",
    MavSysStatusSensor.DIFFERENTIAL_PRESSURE to "Airspeed",
    MavSysStatusSensor.GPS to "GPS",
    MavSysStatusSensor.MAV_SYS_STATUS_AHRS to "AHRS / EKF",
    MavSysStatusSensor.OPTICAL_FLOW to "Optical flow",
    MavSysStatusSensor.VISION_POSITION to "Visual odometry",
    MavSysStatusSensor.LASER_POSITION to "Rangefinder",
    MavSysStatusSensor.EXTERNAL_GROUND_TRUTH to "External ground truth",
    MavSysStatusSensor.RC_RECEIVER to "RC receiver",
    MavSysStatusSensor.BATTERY to "Battery",
    MavSysStatusSensor.MOTOR_OUTPUTS to "Motor outputs",
    MavSysStatusSensor.MAV_SYS_STATUS_REVERSE_MOTOR to "Reverse motor",
    MavSysStatusSensor.PROPULSION to "Propulsion",
    MavSysStatusSensor.ANGULAR_RATE_CONTROL to "Rate control",
    MavSysStatusSensor.ATTITUDE_STABILIZATION to "Attitude control",
    MavSysStatusSensor.YAW_POSITION to "Yaw control",
    MavSysStatusSensor.Z_ALTITUDE_CONTROL to "Altitude control",
    MavSysStatusSensor.XY_POSITION_CONTROL to "Position control",
    MavSysStatusSensor.MAV_SYS_STATUS_GEOFENCE to "Geofence",
    MavSysStatusSensor.MAV_SYS_STATUS_TERRAIN to "Terrain",
    MavSysStatusSensor.MAV_SYS_STATUS_LOGGING to "Logging",
    MavSysStatusSensor.PROXIMITY to "Proximity",
    MavSysStatusSensor.MAV_SYS_STATUS_OBSTACLE_AVOIDANCE to "Obstacle avoidance",
    MavSysStatusSensor.SATCOM to "Satcom",
)
