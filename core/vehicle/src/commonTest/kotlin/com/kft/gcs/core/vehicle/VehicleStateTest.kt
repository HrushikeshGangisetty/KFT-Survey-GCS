package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.Home
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.definitions.ardupilotmega.CameraFeedback
import com.divpundir.mavlink.definitions.ardupilotmega.EkfStatusFlags
import com.divpundir.mavlink.definitions.ardupilotmega.EkfStatusReport
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.GpsFixType
import com.divpundir.mavlink.definitions.common.GpsRawInt
import com.divpundir.mavlink.definitions.common.HomePosition
import com.divpundir.mavlink.definitions.common.MissionCurrent
import com.divpundir.mavlink.definitions.common.MissionItemReached
import com.divpundir.mavlink.definitions.common.MissionState
import com.divpundir.mavlink.definitions.common.MavSeverity
import com.divpundir.mavlink.definitions.common.NamedValueInt
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.common.SysStatus
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehicleStateTest {
    private fun param(name: String, value: Float) = com.divpundir.mavlink.definitions.common.ParamValue(paramId = name, paramValue = value)

    /**
     * The fence comes from four PARAM_VALUEs. FENCE_TYPE 3 = bits 0 and 1: altitude and circle. Type 1 is altitude
     * only, so the radius parameter (which always has a value) is not a limit. Disabled or unknown = no fence.
     */
    @Test
    fun theFenceIsReadFromItsParameters() {
        val known = listOf("FENCE_TYPE" to 3f, "FENCE_ALT_MAX" to 100f, "FENCE_RADIUS" to 300f, "WPNAV_SPEED" to 1000f)
            .fold(VehicleState()) { s, (name, value) -> s.reduce(param(name, value)) }
        assertEquals(null, known.fence, "FENCE_ENABLE not reported yet")
        assertEquals(setOf("FENCE_TYPE", "FENCE_ALT_MAX", "FENCE_RADIUS"), known.watchedParams.keys, "other parameters aren't kept")
        assertEquals(Fence(altMaxM = 100.0, radiusM = 300.0), known.reduce(param("FENCE_ENABLE", 1f)).fence)
        assertEquals(Fence(altMaxM = 100.0, radiusM = null), known.reduce(param("FENCE_ENABLE", 1f)).reduce(param("FENCE_TYPE", 1f)).fence)
        assertEquals(null, known.reduce(param("FENCE_ENABLE", 0f)).fence)
    }


    /** Each CAMERA_FEEDBACK adds its position (degrees × 1e7, as GLOBAL_POSITION_INT); 0,0 (no position) adds nothing. */
    @Test
    fun cameraFeedbackAddsAPhoto() {
        val s = VehicleState()
            .reduce(CameraFeedback(lat = -353632610, lng = 1491652300, imgIdx = 1u))
            .reduce(CameraFeedback(lat = 0, lng = 0, imgIdx = 2u))
            .reduce(CameraFeedback(lat = -353630000, lng = 1491650000, imgIdx = 3u))
        assertEquals(listOf(LatLon(-35.363261, 149.16523), LatLon(-35.363, 149.165)), s.photos)
    }

    @Test
    fun globalPositionIntUnits() {
        // SITL home: -35.363261, 149.165230 in degrees x 1e7. 12 345 mm above home, 596 780 mm AMSL, heading 90.00°.
        val s = VehicleState().reduce(GlobalPositionInt(lat = -353632610, lon = 1491652300, alt = 596780, relativeAlt = 12345, hdg = 9000u))
        assertEquals(LatLon(-35.363261, 149.165230), s.position)
        assertEquals(12.345, s.altitudeRelativeM)
        assertEquals(596.78, s.altitudeMslM)
        assertEquals(90.0, s.headingDeg)
    }

    @Test
    fun unknownSentinelsBecomeNullNotZero() {
        // 0,0 = no position yet; hdg 65535 = heading unknown; 65535 mV and -1 % = battery not measured.
        val s = VehicleState(headingDeg = 45.0)
            .reduce(GlobalPositionInt(lat = 0, lon = 0, hdg = 65535u))
            .reduce(SysStatus(voltageBattery = 65535u, batteryRemaining = -1))
        assertNull(s.position)
        assertEquals(45.0, s.headingDeg, "an unknown heading keeps the last known one")
        assertNull(s.batteryVolts)
        assertNull(s.batteryPercent)
    }

    @Test
    fun attitudeRadiansToDegrees() {
        // pi/6 rad = 30°, -pi/12 rad = -15°.
        val s = VehicleState().reduce(Attitude(roll = (PI / 6).toFloat(), pitch = (-PI / 12).toFloat()))
        assertEquals(30.0, s.rollDeg!!, 1e-4)
        assertEquals(-15.0, s.pitchDeg!!, 1e-4)
    }

    @Test
    fun gpsAndBattery() {
        val s = VehicleState()
            .reduce(GpsRawInt(fixType = MavEnumValue.of(GpsFixType.RTK_FIXED), satellitesVisible = 21u))
            .reduce(SysStatus(voltageBattery = 12600u, batteryRemaining = 87))
        assertEquals(GpsFix.RTK_FIXED, s.gpsFix)
        assertEquals(21, s.satellites)
        assertEquals(12.6, s.batteryVolts)
        assertEquals(87, s.batteryPercent)
        // STATIC (7) is not RTK, so it must not show as "RTK fixed".
        assertEquals(GpsFix.FIX_3D, VehicleState().reduce(GpsRawInt(fixType = MavEnumValue.of(GpsFixType.STATIC))).gpsFix)
    }

    @Test
    fun statusTextSeverity() {
        val s = VehicleState().reduce(Statustext(severity = MavEnumValue.of(MavSeverity.CRITICAL), text = "PreArm: GPS not healthy"))
        assertEquals(StatusMessage("PreArm: GPS not healthy", Severity.ERROR), s.lastMessage)
    }

    /** EKF_STATUS_REPORT flags are kept as the FC's raw bitmask; estimator STATUSTEXTs go to their own list. */
    @Test
    fun ekfStatusAndEstimatorMessagesAreKept() {
        // ardupilotmega.xml EKF_STATUS_FLAGS: attitude 1, horiz vel 2, vert vel 4, pos horiz rel 8, pos horiz abs 16,
        // const pos mode 128. 1 + 2 + 4 + 8 + 16 + 128 = 159 = 0x9F.
        val s = VehicleState()
            .reduce(EkfStatusReport(flags = MavBitmaskValue.fromValue(0x9Fu)))
            .reduce(Statustext(severity = MavEnumValue.of(MavSeverity.INFO), text = "EKF3 IMU0 is using external nav data"))
            .reduce(Statustext(severity = MavEnumValue.of(MavSeverity.WARNING), text = "PreArm: Battery 1 low voltage"))
            .reduce(Statustext(severity = MavEnumValue.of(MavSeverity.INFO), text = "Using EKF Source Set 2"))
        assertEquals(0x9Fu, s.ekfFlags)
        assertEquals(EkfStatusFlags.EKF_CONST_POS_MODE.value, 128u, "the bit the panel warns on")
        assertEquals(listOf("EKF3 IMU0 is using external nav data", "Using EKF Source Set 2"), s.estimatorMessages.map { it.text })
        assertEquals("Using EKF Source Set 2", s.lastMessage?.text, "the HUD's last message is unchanged")
        val many = (1..7).fold(s) { st, i -> st.reduce(Statustext(severity = MavEnumValue.of(MavSeverity.CRITICAL), text = "EKF3 lane switch $i")) }
        assertEquals((3..7).map { "EKF3 lane switch $it" }, many.estimatorMessages.map { it.text }, "only the last five")
    }

    @Test
    fun flightModeNames() {
        assertEquals("Loiter", flightModeName(VehicleKind.COPTER, 5u))
        // Expected strings are ArduPilot's own `name()` (ArduCopter/mode.h, ArduPlane/mode.h), not our formatting.
        assertEquals("Altitude Hold", flightModeName(VehicleKind.COPTER, 2u))
        assertEquals("RTL", flightModeName(VehicleKind.COPTER, 6u))
        assertEquals("Smart RTL", flightModeName(VehicleKind.COPTER, 21u))
        assertEquals("Position Hold", flightModeName(VehicleKind.COPTER, 16u))
        assertEquals("Auto", flightModeName(VehicleKind.PLANE, 10u)) // same name as Copter's 3, different number
        assertEquals("FBWA", flightModeName(VehicleKind.PLANE, 5u))
        assertEquals("QRTL", flightModeName(VehicleKind.PLANE, 21u))
        assertEquals("QLoiter", flightModeName(VehicleKind.PLANE, 19u))
        assertEquals("Mode 99", flightModeName(VehicleKind.COPTER, 99u))
    }

    @Test
    fun homePositionUnits() {
        // HOME_POSITION.altitude is mm AMSL: 584 000 mm = 584 m, CMAC's SITL home altitude.
        val s = VehicleState().reduce(HomePosition(latitude = -353632610, longitude = 1491652300, altitude = 584_000))
        assertEquals(Home(LatLon(-35.363261, 149.165230), 584.0), s.home)
    }

    @Test
    fun firmwareVersionBytes() {
        // major.minor.patch.type, one byte each: 0x04 06 03 FF = 4.6.3 official; 0x00 = dev, 0xC0 (192) = rc.
        assertEquals("4.6.3", firmwareVersionName(0x040603FFu))
        assertEquals("4.7.0-dev", firmwareVersionName(0x04070000u))
        assertEquals("4.6.0-rc", firmwareVersionName(0x040600C0u))
        assertEquals("4.5.7-beta", firmwareVersionName(0x04050780u))
    }

    @Test
    fun missionProgress() {
        // Home + 5 items on the vehicle: ArduPilot reports total 5 (home excluded) and seq 1..5.
        val flying = VehicleState()
            .reduce(MissionCurrent(seq = 3u, total = 5u, missionState = MavEnumValue.of(MissionState.ACTIVE)))
            .reduce(MissionItemReached(seq = 2u))
        assertEquals(MissionProgress(current = 3, total = 5, lastReached = 2, complete = false), flying.mission)

        // The next MISSION_CURRENT keeps the last reached item; COMPLETE marks the end.
        val done = flying.reduce(MissionCurrent(seq = 5u, total = 5u, missionState = MavEnumValue.of(MissionState.COMPLETE)))
        assertEquals(MissionProgress(current = 5, total = 5, lastReached = 2, complete = true), done.mission)

        // 65535 = no mission, 0 = total not supported: both unknown.
        assertNull(VehicleState().reduce(MissionCurrent(seq = 0u, total = 65535u)).mission!!.total)
        assertNull(VehicleState().reduce(MissionCurrent(seq = 0u, total = 0u)).mission!!.total)
    }

    /** S8 firmware reports its rejected-frame count as NAMED_VALUE_INT "SIGFAIL"; any other named value is not ours. */
    @Test
    fun theFcSignatureFailureCountIsReadFromItsNamedValue() {
        assertNull(VehicleState().fcSignatureFailures, "a stock ArduPilot never reports one")

        val three = VehicleState().reduce(NamedValueInt(timeBootMs = 0u, name = "SIGFAIL", value = 3))
        assertEquals(3, three.fcSignatureFailures)
        assertEquals(4, three.reduce(NamedValueInt(timeBootMs = 0u, name = "SIGFAIL", value = 4)).fcSignatureFailures)

        // MAVLink pads the 10-character name with NULs; the trim must not hide a match.
        assertEquals(7, VehicleState().reduce(NamedValueInt(timeBootMs = 0u, name = "SIGFAIL\u0000\u0000\u0000", value = 7)).fcSignatureFailures)

        assertEquals(three, three.reduce(NamedValueInt(timeBootMs = 0u, name = "OTHER", value = 99)), "other names change nothing")
    }
}
