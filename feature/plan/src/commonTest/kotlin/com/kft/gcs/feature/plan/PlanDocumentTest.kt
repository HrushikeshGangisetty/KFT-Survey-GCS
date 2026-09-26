package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.vehicle.AltitudeFrame
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PlanDocumentTest {
    private val cam = Camera(13.2, 8.8, 5472, 3648, 8.8, 2.0, 8.0, "P4P", unverified = true)

    /** A plan with every kind of row and a survey comes back identical: parameters, not generated waypoints. */
    @Test
    fun ownPlanFileRoundTrip() {
        val groups = listOf(
            WaypointGroup(
                "Approach",
                listOf(
                    PlanItem.takeoff(30.0),
                    PlanItem(MissionCommand.WAYPOINT, LatLon(-35.36, 149.16), 30.0, speedMs = 7.5),
                    PlanItem(MissionCommand.LAND, passthrough = MissionItem(MissionCommand.LAND, null, 0.0, AltitudeFrame.AMSL, param4 = Float.NaN)),
                ),
            ),
            SurveyGroup(
                "Field north",
                SurveySettings(
                    polygon = listOf(LatLon(-35.36, 149.16), LatLon(-35.36, 149.17), LatLon(-35.35, 149.17)),
                    camera = cam, heightMode = HeightMode.GSD, altitudeM = 72.96, gsdCm = 2.0, gridAngleDeg = 35.0,
                    entry = EntryCorner.TOP_RIGHT, speedMs = 9.0, turnaroundM = 15.0, returnHome = false,
                ),
            ),
        )
        val text = encodePlan(groups)
        assertTrue(isOwnPlan(text))
        assertEquals(groups, decodePlan(text))
    }

    @Test
    fun otherFilesAreRefused() {
        assertFailsWith<IllegalArgumentException> { decodePlan("""{"fileType":"Plan"}""") }
        assertFailsWith<IllegalArgumentException> { decodePlan("""{"format":"kft-plan","version":9,"groups":[]}""") }
    }

    /**
     * A square (0,0) (100,0) (100,100) (0,100) in metres at the equator. A click at (50, −10), just outside the bottom
     * edge: the detour through it is shortest on that edge (0,0)→(100,0) (about 2 × 51 − 100 = 2 m, against more than
     * 100 m for any other), so it goes between corners 0 and 1.
     */
    @Test
    fun aNewCornerGoesIntoTheNearestEdge() {
        val d = 111_195.08
        fun m(x: Double, y: Double) = LatLon(y / d, x / d)
        val square = listOf(m(0.0, 0.0), m(100.0, 0.0), m(100.0, 100.0), m(0.0, 100.0))
        val click = m(50.0, -10.0)
        assertEquals(listOf(square[0], click, square[1], square[2], square[3]), insertCorner(square, click))
        assertEquals(listOf(square[0], square[1], click), insertCorner(square.take(2), click), "the first three just append")
    }
}
