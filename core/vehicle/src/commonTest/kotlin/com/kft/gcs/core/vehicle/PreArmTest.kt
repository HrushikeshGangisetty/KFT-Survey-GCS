package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.MavSeverity
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.common.SysStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The pre-arm report (bursts of "PreArm:" texts) and SYS_STATUS's sensor health, from hand-built messages. */
class PreArmTest {
    private fun text(s: String, severity: MavSeverity = MavSeverity.CRITICAL) = Statustext(severity = MavEnumValue.of(severity), text = s)

    private fun VehicleState.texts(vararg at: Pair<Duration, String>) = at.fold(this) { s, (t, m) -> s.reduce(text(m), t) }

    /**
     * Values written out from common.xml MAV_SYS_STATUS_SENSOR: gyro 0x1, accel 0x2, mag 0x4, GPS 0x20,
     * RC receiver 0x10000, AHRS 0x200000, PREARM_CHECK 0x10000000.
     */
    private fun sysStatus(present: UInt, enabled: UInt, health: UInt) = SysStatus(
        onboardControlSensorsPresent = MavBitmaskValue.fromValue(present),
        onboardControlSensorsEnabled = MavBitmaskValue.fromValue(enabled),
        onboardControlSensorsHealth = MavBitmaskValue.fromValue(health),
    )

    private val gyro = 0x1u
    private val accel = 0x2u
    private val mag = 0x4u
    private val gps = 0x20u
    private val rc = 0x10000u
    private val ahrs = 0x200000u
    private val prearm = 0x10000000u

    /** One burst = one report: every failure in it is listed, in the FC's order, without the "PreArm: " prefix. */
    @Test
    fun aBurstListsEveryFailure() {
        val s = VehicleState().texts(
            0.seconds to "PreArm: GPS 1: Bad fix",
            100.milliseconds to "PreArm: Battery 1 below minimum arming voltage",
            200.milliseconds to "PreArm: RC not found",
            // A repeat inside the same burst (some checks report twice) is listed once.
            300.milliseconds to "PreArm: GPS 1: Bad fix",
        )
        assertEquals(listOf("GPS 1: Bad fix", "Battery 1 below minimum arming voltage", "RC not found"), s.preArm.failures)
        assertEquals(StatusMessage("PreArm: GPS 1: Bad fix", Severity.ERROR), s.lastMessage, "the latest text is still the latest message")
    }

    /**
     * The next report (30 s later in ArduPilot) replaces the list: the battery is fixed, so it's gone, and the one
     * text just inside the 2 s gap still counts as the same burst.
     */
    @Test
    fun aLaterBurstReplacesTheOldOne() {
        val s = VehicleState().texts(
            0.seconds to "PreArm: GPS 1: Bad fix",
            1.seconds to "PreArm: Battery 1 below minimum arming voltage",
            31.seconds to "PreArm: GPS 1: Bad fix",
            33.seconds to "PreArm: Compass not calibrated",  // exactly 2 s after: same burst
            36.seconds to "PreArm: Logging failed",          // 3 s after: a new burst
        )
        assertEquals(listOf("Logging failed"), s.preArm.failures)
        assertEquals(listOf("GPS 1: Bad fix", "Compass not calibrated"), VehicleState().texts(
            0.seconds to "PreArm: Battery 1 below minimum arming voltage",
            31.seconds to "PreArm: GPS 1: Bad fix",
            33.seconds to "PreArm: Compass not calibrated",
        ).preArm.failures)
    }

    /**
     * "Arm:" texts (checks done only during an arming attempt) are reasons too and keep their prefix. A DEBUG-severity
     * pre-arm text is a check turned off in ARMING_CHECK (AP_Arming::check_failed), and other texts aren't failures.
     */
    @Test
    fun armTextsCountButDisabledChecksAndOtherTextsDont() {
        val s = VehicleState()
            .reduce(text("Arm: Throttle too high"), 0.seconds)
            .reduce(text("PreArm: Hardware safety switch", MavSeverity.DEBUG), 0.1.seconds)
            .reduce(text("EKF3 IMU0 is using GPS"), 0.2.seconds)
            .reduce(text("Throttle armed", MavSeverity.INFO), 0.3.seconds)
        assertEquals(listOf("Arm: Throttle too high"), s.preArm.failures)
        assertNull(preArmFailure("PreArmed"), "the prefix needs its colon and space")
    }

    /** No "all clear" text exists, so the SYS_STATUS pre-arm bit turning healthy (checks pass, or armed) clears the list. */
    @Test
    fun passingChecksClearTheList() {
        val failing = VehicleState().texts(0.seconds to "PreArm: GPS 1: Bad fix")
        val all = gyro or accel or gps or prearm
        val stillFailing = failing.reduce(sysStatus(all, all, gyro or accel))
        assertEquals(false, stillFailing.sensorBits?.preArmPassing)
        assertEquals(listOf("GPS 1: Bad fix"), stillFailing.preArm.failures)
        val passing = stillFailing.reduce(sysStatus(all, all, all))
        assertEquals(true, passing.sensorBits?.preArmPassing)
        assertEquals(PreArmReport(), passing.preArm)
    }

    /** ARMING_CHECK = 0: the bit is present but not enabled, so "passing" is unknown, not true. Old firmware: no bit at all. */
    @Test
    fun preArmBitUnknownWhenChecksAreOff() {
        val off = SensorBits(present = gyro or prearm, enabled = gyro, health = gyro or prearm)
        assertNull(off.preArmPassing)
        assertEquals(true, off.preArmChecksOff)
        val old = SensorBits(present = gyro, enabled = gyro, health = gyro)
        assertNull(old.preArmPassing)
        assertEquals(false, old.preArmChecksOff, "no bit at all is not \"checks off\"")
        assertEquals(false, SensorBits(prearm, prearm, 0u).preArmChecksOff)
    }

    /**
     * Present + enabled + healthy = HEALTHY; present + enabled, not healthy = UNHEALTHY; present, not enabled = DISABLED;
     * not present = not listed. The pre-arm bit itself is not a sensor row. Order is the display order, not bit order.
     */
    @Test
    fun sensorHealthFromTheThreeBitmasks() {
        val s = VehicleState().reduce(
            sysStatus(present = gyro or accel or mag or gps or rc or ahrs or prearm, enabled = gyro or accel or gps or rc or ahrs or prearm, health = gyro or accel or ahrs),
        )
        assertEquals(
            listOf(
                SensorHealth("Gyro", SensorState.HEALTHY),
                SensorHealth("Accelerometer", SensorState.HEALTHY),
                SensorHealth("Compass", SensorState.DISABLED),
                SensorHealth("GPS", SensorState.UNHEALTHY),
                SensorHealth("AHRS / EKF", SensorState.HEALTHY),
                SensorHealth("RC receiver", SensorState.UNHEALTHY),
            ),
            s.sensorBits?.sensors,
        )
    }
}
