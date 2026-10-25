package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts of the Setup tab: field checks, mode-switch bands, sensor ids and the radio calibration's maths. */
class SetupLogicTest {

    /** The band edges from RC_Channel.cpp `read_6pos_switch`, tested on both sides of each edge. */
    @Test
    fun modeSlotFollowsArduPilotsBands() {
        val cases = listOf(
            800 to null, 801 to 0, 1230 to 0, 1231 to 1, 1360 to 1, 1361 to 2, 1490 to 2, 1491 to 3,
            1620 to 3, 1621 to 4, 1749 to 4, 1750 to 5, 2199 to 5, 2200 to null,
        )
        for ((pwm, slot) in cases) assertEquals(slot, modeSlot(pwm), "$pwm µs")
    }

    @Test
    fun valuesReadAsLabelsUnitsAndBitNames() {
        val choice = FieldKind.Choice(listOf(0 to "Disabled", 1 to "RTL"))
        assertEquals("RTL", displayValue(choice, 1f))
        assertEquals("2, not a listed value", displayValue(choice, 2f), "a value we don't list is shown, not hidden")
        assertEquals("10.5 V", displayValue(FieldKind.Number(0.0..100.0, "V"), 10.5f))
        assertEquals("0.8", displayValue(FieldKind.Number(0.0..1.0, ""), 0.8f), "no float noise (0.800000011…)")
        val fence = FieldKind.Bits(listOf(0 to "Max altitude", 1 to "Circle", 2 to "Polygons"))
        assertEquals("Max altitude, Polygons", displayValue(fence, 5f), "5 = bits 0 and 2")
        assertEquals("None", displayValue(fence, 0f))
    }

    @Test
    fun valuesAreCheckedAgainstTheDocumentedLists() {
        val choice = FieldKind.Choice(listOf(0 to "Disabled", 1 to "RTL", 3 to "Land"))
        assertNull(checkValue(choice, 3f))
        assertNotNull(checkValue(choice, 2f), "2 was removed in ArduPilot 4.0")
        val timeout = FieldKind.Number(2.0..120.0, "s")
        assertNull(checkValue(timeout, 2f))
        assertNull(checkValue(timeout, 120f))
        assertEquals("Allowed range is 2 to 120 s", checkValue(timeout, 1f))
        assertNotNull(checkValue(timeout, 121f))
        val bits = FieldKind.Bits(listOf(0 to "A", 1 to "B", 3 to "D"))
        assertNull(checkValue(bits, 11f), "bits 0, 1, 3")
        assertNotNull(checkValue(bits, 4f), "bit 2 isn't listed")
    }

    @Test
    fun everyPageListsEachParameterOnceWithUniqueCodes() {
        for (kind in listOf(VehicleKind.COPTER, VehicleKind.PLANE, null)) {
            val fields = (failsafeSections(kind) + flightModeSections(kind)).flatMap { it.fields }
            assertEquals(fields.size, fields.map { it.param }.toSet().size, "$kind")
            fields.mapNotNull { it.kind as? FieldKind.Choice }.forEach { c -> assertEquals(c.options.size, c.options.map { it.first }.toSet().size) }
        }
        assertTrue(failsafeSections(VehicleKind.PLANE).flatMap { it.fields }.any { it.param == "FS_LONG_ACTN" })
        assertTrue(failsafeSections(VehicleKind.COPTER).flatMap { it.fields }.any { it.param == "FS_THR_ENABLE" })
    }

    /**
     * AP_BattMonitor_Backend `arming_checks`: with both > 0, low must be strictly above critical. ArduCopter
     * AP_Arming `parameter_checks`: throttle min must be more than FS_THR_VALUE + 10, and FS_THR_VALUE >= 910.
     */
    @Test
    fun armingProblemsMatchArduPilotsChecks() {
        val ok = mapOf("BATT_LOW_VOLT" to 14.0f, "BATT_CRT_VOLT" to 13.5f, "FS_THR_ENABLE" to 1f, "FS_THR_VALUE" to 975f, "RC3_MIN" to 986f)
        assertEquals(emptyList<String>(), armingProblems(VehicleKind.COPTER, ok))
        assertEquals(1, armingProblems(VehicleKind.COPTER, ok + ("BATT_LOW_VOLT" to 13.5f)).size, "equal is inverted too")
        assertEquals(emptyList<String>(), armingProblems(VehicleKind.COPTER, ok + ("BATT_CRT_VOLT" to 0f)), "0 = that check is off")
        assertEquals(1, armingProblems(VehicleKind.COPTER, ok + ("RC3_MIN" to 985f)).size, "985 <= 975 + 10")
        assertEquals(1, armingProblems(VehicleKind.COPTER, ok + ("FS_THR_VALUE" to 905f) + ("RC3_MIN" to 1100f)).size, "below 910")
        assertEquals(emptyList<String>(), armingProblems(VehicleKind.COPTER, ok + ("FS_THR_ENABLE" to 0f) + ("RC3_MIN" to 900f)), "failsafe off")
        assertEquals(1, armingProblems(VehicleKind.COPTER, ok + ("RCMAP_THROTTLE" to 1f) + ("RC1_MIN" to 980f)).size, "RCMAP_THROTTLE picks the channel")
        assertEquals(emptyList<String>(), armingProblems(VehicleKind.PLANE, ok + ("RC3_MIN" to 900f)), "a Copter-only check")
    }

    /**
     * Device ids decoded by hand from the bit layout in AP_HAL/Device.h (bus type bits 0-2, bus 3-7, address 8-15,
     * devtype 16-23):
     * - 3 407 882 = 0x34000A: 0x0A = 0b0000_1010 → bus type 2 (SPI), bus 1; address 0; devtype 0x34 = ICM42688.
     * - 97 539 = 0x017D03: 0x03 → bus type 3 (DroneCAN), bus 0; address 0x7D = node 125; devtype 1.
     * - 658 953 = 0x0A0E09: 0x09 = 0b0000_1001 → I2C, bus 1; address 0x0E; devtype 0x0A = IST8310.
     * - 721 666 = 0x0B0302: SPI bus 0, device 3, devtype 0x0B = MS5611.
     */
    @Test
    fun sensorIdsDecodeToBusAndChip() {
        assertEquals(DeviceId(2, 1, 0, 0x34), decodeDeviceId(3_407_882))
        assertEquals(DeviceId(3, 0, 125, 1), decodeDeviceId(97_539))
        val rows = sensorRows(
            mapOf(
                "COMPASS_DEV_ID" to 97_539f, "COMPASS_DEV_ID2" to 658_953f, "COMPASS_DEV_ID3" to 0f,
                "INS_ACC2_ID" to 3_407_882f, "INS_ACC_ID" to 3_407_882f, "INS4_GYR_ID" to 3_407_882f,
                "BARO1_DEVID" to 721_666f, "BATT_LOW_VOLT" to 14f,
            ),
        )
        assertEquals(listOf("Accelerometer 1", "Accelerometer 2", "Gyro 4", "Compass 1", "Compass 2", "Barometer 1"), rows.map { it.sensor })
        assertEquals("SPI bus 1, device 0" to "ICM42688", rows[0].bus to rows[0].chip)
        assertEquals("DroneCAN 1, node 125" to "DroneCAN sensor", rows[3].bus to rows[3].chip)
        assertEquals("I2C bus 1, address 0x0e" to "IST8310", rows[4].bus to rows[4].chip)
        assertEquals("SPI bus 0, device 3" to "MS5611", rows[5].bus to rows[5].chip)
    }

    @Test
    fun rangesWidenWithEachReading() {
        var r = widen(emptyList(), listOf(1500, 1500))
        r = widen(r, listOf(1100, 1900, 1000))
        r = widen(r, listOf(1950, 0, 2000)) // 0: no reading on channel 2 this time
        assertEquals(listOf(RcRange(1100, 1950), RcRange(1500, 1900), RcRange(1000, 2000)), r)
    }

    private val vehicle = (1..5).flatMap { ch -> listOf("RC${ch}_MIN" to 1100f, "RC${ch}_MAX" to 1900f, "RC${ch}_TRIM" to 1500f) }.toMap()
    private val sticks = listOf(RcRange(1000, 2000), RcRange(1010, 1990), RcRange(990, 2010), RcRange(1000, 2000))

    @Test
    fun aGoodSweepWritesOnlyWhatChanged() {
        // Channel 5 is a switch swept 1100..1900, which the vehicle already holds; centre reading 1100.
        val result = rcCalibrationResult(sticks + RcRange(1100, 1900), listOf(1500, 1502, 995, 1498, 1100), vehicle, VehicleKind.COPTER)
        assertEquals(emptyList<String>(), result.problems)
        assertEquals(listOf("Roll", "Pitch", "Throttle", "Yaw", null), result.channels.map { it.role })
        assertEquals(995, result.channels[2].trim, "throttle trim is its low position")
        assertTrue(result.writes.none { it.first.startsWith("RC5_") && it.first != "RC5_TRIM" }, "unchanged min/max not rewritten")
        assertEquals(1100f, result.writes.toMap()["RC5_TRIM"])
        assertEquals(990f, result.writes.toMap()["RC3_MIN"])
        assertEquals(2010f, result.writes.toMap()["RC3_MAX"])
    }

    @Test
    fun aStickThatDidntMoveOrWentTooFarStopsTheSave() {
        val still = rcCalibrationResult(sticks.take(3) + RcRange(1490, 1510), listOf(1500, 1500, 1000, 1500), vehicle, VehicleKind.COPTER)
        assertTrue(still.problems.single().startsWith("Yaw (channel 4) didn't move"))
        val wide = rcCalibrationResult(sticks.take(3) + RcRange(790, 2000), listOf(1500, 1500, 1000, 1500), vehicle, VehicleKind.COPTER)
        assertTrue(wide.problems.any { "Channel 4" in it }, "800 µs or less reads as no signal in ArduPilot")
        assertTrue(wide.writes.none { it.first.startsWith("RC4_") })
        val unread = rcCalibrationResult(sticks, listOf(1500, 1500, 1000, 1500), emptyMap(), VehicleKind.COPTER)
        assertTrue(unread.problems.first().contains("haven't been read"))
    }

    @Test
    fun aThrottleMinimumTooCloseToTheFailsafeIsAWarning() {
        val withFs = vehicle + ("FS_THR_ENABLE" to 1f) + ("FS_THR_VALUE" to 985f)
        val result = rcCalibrationResult(sticks, listOf(1500, 1500, 990, 1500), withFs, VehicleKind.COPTER)
        assertEquals(emptyList<String>(), result.problems)
        assertTrue(result.warnings.single().startsWith("Throttle failsafe"), "990 <= 985 + 10")
    }
}
