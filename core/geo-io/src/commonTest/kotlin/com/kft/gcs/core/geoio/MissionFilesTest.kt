package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AltitudeFrame
import com.kft.gcs.core.vehicle.Home
import com.kft.gcs.core.vehicle.Mission
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MissionFilesTest {
    private val home = Home(LatLon(-35.363261, 149.16523), 584.0)
    private val survey = Mission(
        home,
        listOf(
            MissionItem(MissionCommand.TAKEOFF, altitudeM = 50.0),
            MissionItem(MissionCommand.WAYPOINT, LatLon(-35.3620, 149.1660), 50.0),
            MissionItem(MissionCommand.DO_SET_CAM_TRIGG_DIST, param1 = 20f, param3 = 1f),
            MissionItem(MissionCommand.WAYPOINT, LatLon(-35.3640, 149.1660), 50.0, param4 = Float.NaN),
            MissionItem(MissionCommand.DO_SET_CAM_TRIGG_DIST),
            MissionItem(MissionCommand.RETURN_TO_LAUNCH),
        ),
    )

    /** NaN is compared by bits in data classes, so equality works for the NaN yaw too. */
    @Test
    fun qgcPlanRoundTrip() {
        val back = decodeQgcPlan(encodeQgcPlan(survey, VehicleKind.COPTER))
        assertEquals(survey, back.mission)
        assertEquals(0, back.skipped)
    }

    @Test
    fun waypointsRoundTrip() {
        assertEquals(survey, decodeWaypoints(encodeWaypoints(survey)).mission)
    }

    /**
     * A plan in QGC's documented layout (hand-written, not produced by our encoder): a takeoff, a waypoint with a
     * null (NaN) yaw, and a Survey ComplexItem we can't expand. Home from plannedHomePosition.
     */
    @Test
    fun readsAHandWrittenQgcPlanAndSkipsComplexItems() {
        val text = """
            {"fileType": "Plan", "version": 1, "groundStation": "QGroundControl",
             "mission": {"version": 2, "firmwareType": 3, "vehicleType": 2, "cruiseSpeed": 15, "hoverSpeed": 5,
               "plannedHomePosition": [-35.363261, 149.16523, 584],
               "items": [
                 {"type": "SimpleItem", "autoContinue": true, "command": 22, "doJumpId": 1, "frame": 3, "params": [0, 0, 0, null, 0, 0, 30]},
                 {"type": "SimpleItem", "autoContinue": true, "command": 16, "doJumpId": 2, "frame": 3, "params": [0, 0, 0, null, -35.362, 149.166, 30]},
                 {"type": "ComplexItem", "complexItemType": "survey", "version": 5}
               ]},
             "geoFence": {"version": 2, "circles": [], "polygons": []}, "rallyPoints": {"version": 2, "points": []}}
        """.trimIndent()
        val read = decodeQgcPlan(text)
        assertEquals(home, read.mission.home)
        assertEquals(1, read.skipped)
        assertEquals(listOf(MissionCommand.TAKEOFF, MissionCommand.WAYPOINT), read.mission.items.map { it.command })
        assertEquals(LatLon(-35.362, 149.166), read.mission.items[1].position)
        assertTrue(read.mission.items[1].param4.isNaN(), "null yaw = NaN = leave unchanged")
    }

    /** Mission Planner's layout: tab-separated, line 0 home (S11), frame 2 for a DO_ item. */
    @Test
    fun readsAHandWrittenWaypointsFile() {
        val text = "QGC WPL 110\n" +
            "0\t1\t0\t16\t0\t0\t0\t0\t-35.363261\t149.165230\t584.000000\t1\n" +
            "1\t0\t3\t22\t0.00000000\t0.00000000\t0.00000000\t0.00000000\t0.00000000\t0.00000000\t30.000000\t1\n" +
            "2\t0\t2\t206\t20.00000000\t0.00000000\t1.00000000\t0.00000000\t0.00000000\t0.00000000\t0.000000\t1\n" +
            "3\t0\t3\t16\t0.00000000\t0.00000000\t0.00000000\t0.00000000\t-35.36200000\t149.16600000\t30.000000\t1\n"
        val read = decodeWaypoints(text).mission
        assertEquals(home, read.home)
        assertEquals(listOf(22, 206, 16), read.items.map { it.command }, "home is never an item")
        assertEquals(20f, read.items[1].param1)
        assertEquals(AltitudeFrame.RELATIVE, read.items[2].frame)
    }

    @Test
    fun wrongFilesSayWhatIsWrong() {
        val e = assertFailsWith<IllegalArgumentException> { decodeQgcPlan("QGC WPL 110\n") }
        assertTrue(e.message!!.startsWith("Not a valid QGC .plan file"), e.message)
        assertFailsWith<IllegalArgumentException> { decodeWaypoints("""{"fileType":"Plan"}""") }
        assertFailsWith<IllegalArgumentException> { decodeWaypoints("QGC WPL 110\n1\t0\t3\t16\n") }
    }
}
