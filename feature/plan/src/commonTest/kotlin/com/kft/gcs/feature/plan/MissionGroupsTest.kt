package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.mission.MissionCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The worked example again (Pass 13): 300 × 200 m at the equator, P4P at 100 m, 70/80 % overlap → 7 north–south
 * lines at x = 15…285 m, 20 m trigger, 11 photos a line, 77 in all.
 */
class MissionGroupsTest {
    private val metresPerDegree = 111_195.08
    private fun m(x: Double, y: Double) = LatLon(y / metresPerDegree, x / metresPerDegree)
    private val p4p = Camera(13.2, 8.8, 5472, 3648, 8.8, name = "P4P")
    private val survey = SurveySettings(
        polygon = listOf(m(0.0, 0.0), m(300.0, 0.0), m(300.0, 200.0), m(0.0, 200.0)),
        camera = p4p, altitudeM = 100.0, gsdCm = 2.74, speedMs = 10.0, turnaroundM = 10.0,
    )

    /**
     * Copter, first in the mission, 10 m run-in: takeoff, speed, then per line 5 items (run-in start, photo start,
     * camera on, camera-off point, camera off), then RTL: 2 + 7 × 5 + 1 = 38 items.
     * First line, northbound at x = 15: (15, −10) → (15, 0) → on(20 m, shoot now) → (15, 210) → off. The camera-off
     * point is (⌊200/20⌋ + ½) × 20 = 210 m, which is also where the 10 m run-out ends, so no separate exit waypoint.
     */
    @Test
    fun copterSurveyItems() {
        val flat = flatten(listOf(SurveyGroup("S", survey)), VehicleKind.COPTER, SurveyLimits())
        val items = flat.items
        assertEquals(38, items.size)
        assertEquals(77, flat.plannedPhotos)
        assertEquals(MissionCommand.TAKEOFF, items[0].command)
        assertEquals(100.0, items[0].altitudeM)
        assertEquals(MissionCommand.DO_CHANGE_SPEED, items[1].command)
        assertEquals(10f, items[1].param2)
        val line = items.subList(2, 7)
        assertEquals(
            listOf(MissionCommand.WAYPOINT, MissionCommand.WAYPOINT, MissionCommand.DO_SET_CAM_TRIGG_DIST, MissionCommand.WAYPOINT, MissionCommand.DO_SET_CAM_TRIGG_DIST),
            line.map { it.command },
        )
        assertAt(15.0, -10.0, line[0].position!!)
        assertAt(15.0, 0.0, line[1].position!!)
        assertEquals(20f, line[2].param1, "a photo every 20 m")
        assertEquals(1f, line[2].param3, "and one straight away")
        assertEquals(0f, line[2].param2, "param2 is dropped by ArduPilot, so it's always 0 (read-back compares equal)")
        assertAt(15.0, 210.0, line[3].position!!)
        assertEquals(0f, line[4].param1, "camera off 10 m past the last photo: no photos in the turn")
        assertEquals(MissionCommand.WAYPOINT, items[7].command, "the next line starts straight after")
        assertTrue(items.filter { it.position != null }.all { it.altitudeM == 100.0 })
        assertEquals(MissionCommand.RETURN_TO_LAUNCH, items.last().command)
    }

    /**
     * Plane: no takeoff (the pilot takes off on the RC), no RTL here, 0 m lead-in: 1 + 7 × 4 = 29 items (the lead-out
     * is the 10 m the camera needs, ending at the camera-off point), plus the first line's lead-in waypoint: that line
     * always gets 2 turn diameters (at 20 m/s and 30° bank, r = 70.6 m), so 30 items.
     */
    @Test
    fun planeSurveyHasNoTakeoff() {
        val plane = survey.copy(turnaroundM = 0.0, returnHome = false, speedMs = 20.0)
        val items = flatten(listOf(SurveyGroup("S", plane)), VehicleKind.PLANE, SurveyLimits()).items
        assertEquals(30, items.size)
        assertEquals(MissionCommand.DO_CHANGE_SPEED, items[0].command)
    }

    /** A survey after a waypoint group starts at the next seq and gets no second takeoff. */
    @Test
    fun groupsFlattenInOrderWithSeqNumbers() {
        val waypoints = WaypointGroup("W", emptyList<PlanItem>().addWaypoint(m(0.0, -50.0), VehicleKind.COPTER).addWaypoint(m(10.0, -50.0), VehicleKind.COPTER))
        val flat = flatten(listOf(waypoints, SurveyGroup("S", survey)), VehicleKind.COPTER, SurveyLimits())
        assertEquals(listOf(1, 4), flat.groups.map { it.firstSeq }, "takeoff + 2 waypoints, then the survey at seq 4")
        assertEquals(MissionCommand.DO_CHANGE_SPEED, flat.groups[1].items.first().command)
        assertEquals(3 + 1 + 35 + 1, flat.items.size)
    }

    @Test
    fun anUnfinishedSurveySaysWhy() {
        val flat = flatten(listOf(SurveyGroup("S", survey.copy(polygon = survey.polygon.take(2)))), VehicleKind.COPTER, SurveyLimits())
        assertTrue(flat.items.isEmpty())
        assertEquals("Click the map to add at least 3 corners.", flat.groups.single().error)
    }

    private fun assertAt(x: Double, y: Double, p: LatLon) {
        assertEquals(x, p.longitude * metresPerDegree, 1e-3)
        assertEquals(y, p.latitude * metresPerDegree, 1e-3)
    }
}
