package com.kft.gcs.core.vehicle

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MissionSyncTest {
    private val wp = MissionItem(MissionCommand.WAYPOINT, LatLon(-35.36326123456, 149.16523098765), 30.0)
    private val trigger = MissionItem(MissionCommand.DO_SET_CAM_TRIGG_DIST, param1 = 20f, param3 = 1f)

    @Test
    fun unknownUntilUploadedThenTracksEdits() {
        val sync = MissionSync()
        sync.planChanged(listOf(wp), 0)
        assertFalse(sync.state.value.differsFrom(null), "nothing uploaded yet: unknown, not different")

        sync.vehicleHolds(listOf(wp))
        assertFalse(sync.state.value.differsFrom(null))

        sync.planChanged(listOf(wp, trigger), 11)
        assertTrue(sync.state.value.differsFrom(null), "an edit makes it differ")
    }

    /**
     * What comes back from the vehicle is the wire form: position rounded to 1e-7°, altitude a Float, and DO items in
     * the AMSL frame. That's still the same mission.
     */
    @Test
    fun aReadBackEqualsWhatWasSent() {
        val readBack = listOf(
            wp.copy(position = LatLon.fromE7(wp.position!!.latE7, wp.position!!.lonE7), altitudeM = 30f.toDouble()),
            trigger.copy(frame = AltitudeFrame.AMSL),
        )
        assertTrue(sameMission(listOf(wp, trigger), readBack))
        assertFalse(sameMission(listOf(wp), listOf(wp.copy(altitudeM = 31.0))))
        assertFalse(sameMission(listOf(wp), listOf(wp.copy(frame = AltitudeFrame.AMSL))), "a waypoint's frame matters")
    }

    /** MAVProxy uploads 5 items behind our back: MISSION_CURRENT's total no longer matches what we sent. */
    @Test
    fun aDifferentCountOnTheVehicleIsCaught() {
        val sync = MissionSync()
        sync.planChanged(listOf(wp), 0)
        sync.vehicleHolds(listOf(wp))
        assertFalse(sync.state.value.differsFrom(1))
        assertTrue(sync.state.value.differsFrom(5))
    }
}
