package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.GpsFixType
import com.divpundir.mavlink.definitions.common.GpsRawInt
import com.divpundir.mavlink.definitions.common.MavSeverity
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

    @Test
    fun flightModeNames() {
        assertEquals("Loiter", flightModeName(VehicleKind.COPTER, 5u))
        assertEquals("Alt Hold", flightModeName(VehicleKind.COPTER, 2u))
        assertEquals("Auto", flightModeName(VehicleKind.PLANE, 10u)) // same name as Copter's 3, different number
        assertEquals("Mode 99", flightModeName(VehicleKind.COPTER, 99u))
    }
}
