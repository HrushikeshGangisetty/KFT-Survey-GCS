package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanItemsTest {
    private val a = LatLon(-35.3620, 149.1660)
    private val b = LatLon(-35.3640, 149.1670)

    @Test
    fun copterFirstClickAddsTakeoffAsItemOne() {
        val plan = emptyList<PlanItem>().addWaypoint(a, VehicleKind.COPTER)
        assertEquals(listOf(PlanItem.takeoff(30.0), PlanItem.waypoint(a, 30.0)), plan)
        // The second click adds only a waypoint, at the last item's altitude.
        val raised = plan.mapIndexed { i, it -> if (i == 1) it.copy(altitudeM = 45.0) else it }
        assertEquals(PlanItem.waypoint(b, 45.0), raised.addWaypoint(b, VehicleKind.COPTER).last())
        assertEquals(3, raised.addWaypoint(b, VehicleKind.COPTER).size)
    }

    @Test
    fun planeGetsNoAutomaticTakeoffAndAPatternAltitude() {
        assertEquals(listOf(PlanItem.waypoint(a, 100.0)), emptyList<PlanItem>().addWaypoint(a, VehicleKind.PLANE))
        // Unknown vehicle (planning offline) behaves like a copter, the P0 default.
        assertEquals(MissionCommand.TAKEOFF, emptyList<PlanItem>().addWaypoint(a, null).first().command)
    }

    @Test
    fun seqNumbersCountTheSpeedChangeItems() {
        // Takeoff = 1; the waypoint with a speed is preceded by its DO_CHANGE_SPEED (2), so it is 3; the next is 4.
        val plan = listOf(PlanItem.takeoff(30.0), PlanItem.waypoint(a, 30.0).copy(speedMs = 5.0), PlanItem.waypoint(b, 30.0))
        assertEquals(listOf(1, 3, 4), seqNumbers(plan))
        assertEquals(4, toMissionItems(plan).size, "and the vehicle really gets 4 items")
    }

    @Test
    fun rowsBecomeMissionItemsRelativeToHome() {
        val plan = listOf(PlanItem.takeoff(20.0), PlanItem.waypoint(a, 30.0).copy(speedMs = 8.0))
        assertEquals(
            listOf(
                MissionItem(MissionCommand.TAKEOFF, null, 20.0, AltitudeFrame.RELATIVE),
                // Type 0 (Copter: horizontal speed, Plane: airspeed), 8 m/s, throttle -1 = unchanged.
                MissionItem(MissionCommand.DO_CHANGE_SPEED, param1 = 0f, param2 = 8f, param3 = -1f),
                MissionItem(MissionCommand.WAYPOINT, a, 30.0, AltitudeFrame.RELATIVE),
            ),
            toMissionItems(plan),
        )
    }

    @Test
    fun readBackRoundTripsAndKeepsWhatTheEditorCannotEdit() {
        val rtl = MissionItem(MissionCommand.RETURN_TO_LAUNCH)
        val plan = listOf(PlanItem.takeoff(20.0), PlanItem.waypoint(a, 30.0).copy(speedMs = 8.0), PlanItem(rtl.command, passthrough = rtl))
        assertEquals(plan, fromMissionItems(toMissionItems(plan)))

        // A speed change we didn't write (throttle set) and one with nothing after it stay as their own rows.
        val throttle = MissionItem(MissionCommand.DO_CHANGE_SPEED, param1 = 0f, param2 = 12f, param3 = 60f)
        val trailing = MissionItem(MissionCommand.DO_CHANGE_SPEED, param1 = 0f, param2 = 5f, param3 = -1f)
        val wp = MissionItem(MissionCommand.WAYPOINT, b, 30.0, AltitudeFrame.RELATIVE)
        val rows = fromMissionItems(listOf(throttle, wp, trailing))
        assertEquals(listOf(throttle, null, trailing), rows.map { it.passthrough })
        assertEquals(null, rows[1].speedMs)
        assertEquals(listOf(throttle, wp, trailing), toMissionItems(rows), "uploading it again changes nothing")
    }

    @Test
    fun amslWaypointsFromAnotherToolAreNotSilentlyMadeRelative() {
        val amsl = MissionItem(MissionCommand.WAYPOINT, a, 614.0, AltitudeFrame.AMSL)
        val rows = fromMissionItems(listOf(amsl))
        assertEquals(amsl, rows.single().passthrough)
        assertEquals(listOf(amsl), toMissionItems(rows))
    }
}
