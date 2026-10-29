package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.terrain.Elevations
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class TerrainProfileTest {
    private val home = LatLon(31.0, 75.0)
    private val north1km = Geodesy.destination(home, 0.0, 1000.0)
    private val takeoff = MissionItem(MissionCommand.TAKEOFF, altitudeM = 50.0)
    private fun wp(at: LatLon, alt: Double, frame: AltitudeFrame = AltitudeFrame.RELATIVE) = MissionItem(MissionCommand.WAYPOINT, at, alt, frame)

    /** A slope rising 1 m every 10 m north of home, from 200 m: the hand-calculable ground used below. */
    private fun slope(points: List<PathPoint>) = points.map { 200.0 + 0.1 * it.distanceM }

    @Test
    fun aLevelPathOverRisingGroundGoesBelowItHalfwayAlong() {
        // Home 200 m AMSL; take off to 50 m, fly 1 km north at 50 m relative = 250 m AMSL. The ground reaches 250 m at
        // 500 m along (200 + 0.1 × 500) and 300 m at the end: clearance −50 m at waypoint 2.
        val points = pathPoints(home, listOf(takeoff, wp(north1km, 50.0)))
        assertEquals(listOf("H", "1", "2"), points.mapNotNull { it.mark })
        assertEquals(1000.0, points.last().distanceM, 1e-6)
        assertEquals(42, points.size, "home, the takeoff step, and 40 steps of 25 m")

        val profile = assertNotNull(terrainProfile(points, slope(points), homeAmslM = 200.0))
        assertTrue(profile.samples.drop(1).all { it.pathM == 250.0 })
        assertEquals(-50.0, profile.lowestClearanceM, 1e-6)
        assertEquals("2", profile.lowestAt)
        assertEquals(ClearanceStatus.BELOW_GROUND, profile.status)
        val atHalf = profile.samples.first { kotlin.math.abs(it.distanceM - 500.0) < 1e-6 }
        assertEquals(0.0, atHalf.pathM - atHalf.terrainM, 1e-6)
    }

    @Test
    fun aTerrainFrameLegKeepsItsHeightAboveTheGround() {
        val points = pathPoints(home, listOf(takeoff, wp(north1km, 40.0, AltitudeFrame.TERRAIN)))
        val profile = assertNotNull(terrainProfile(points, slope(points), homeAmslM = 200.0))
        assertEquals(340.0, profile.samples.last().pathM, 1e-6) // 300 m ground at 1 km + 40 m
        assertEquals(ClearanceStatus.OK, profile.status)
    }

    @Test
    fun theClimbOutAndTheLandingDescentAreNotFlagged() {
        // Flat ground at 100 m. Home → takeoff 30 m → 1 km at 30 m → land there. The landing ends at 0 m clearance and
        // home starts there, but neither is a warning; the lowest checked clearance is the 30 m cruise. 30 m ≥ 15 m: OK.
        val items = listOf(takeoff.copy(altitudeM = 30.0), wp(north1km, 30.0), MissionItem(MissionCommand.LAND, north1km, 0.0))
        val points = pathPoints(home, items)
        val profile = assertNotNull(terrainProfile(points, points.map { 100.0 }, homeAmslM = 100.0))
        assertEquals(30.0, profile.lowestClearanceM, 1e-6)
        assertEquals(ClearanceStatus.OK, profile.status)
    }

    @Test
    fun withoutHomeTheGroundUnderTheFirstWaypointIsHome() {
        // No vehicle: the path starts at waypoint 1, whose ground (200 m) stands in for home. 10 m relative = 210 m,
        // and 10 m < 15 m is flagged LOW.
        val points = pathPoints(null, listOf(wp(home, 10.0), wp(north1km, 10.0)))
        assertEquals("1", points.first().mark)
        val profile = assertNotNull(terrainProfile(points, points.map { 200.0 }, homeAmslM = null))
        assertEquals(210.0, profile.samples.first().pathM)
        assertEquals(ClearanceStatus.LOW, profile.status)
    }

    @Test
    fun aLongRouteIsSampledCoarserNotLonger() {
        // 20 km at 25 m spacing would be 800 points; the cap of 200 makes it 100 m spacing.
        val far = Geodesy.destination(home, 90.0, 20_000.0)
        val points = pathPoints(null, listOf(wp(home, 50.0), wp(far, 50.0)))
        assertEquals(201, points.size)
        assertEquals(100.0, points[1].distanceM, 1e-6)
        assertTrue(pathPoints(null, listOf(wp(home, 50.0))).isEmpty(), "one point is no profile")
    }

    // ---- the ViewModel ----

    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun thePlanScreenLooksTheTerrainUpOnceTheEditsSettle() = runTest(dispatcher) {
        var requests = 0
        val flat = Elevations { points -> requests++; points.map { 584.0 } }
        val vehicle = MutableStateFlow(VehicleState(connected = true, vehicleKind = VehicleKind.COPTER, home = Home(home, 584.0)))
        val vm = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles(), elevations = flat)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.onMapClick(north1km)
        runCurrent()
        vm.onMapClick(Geodesy.destination(home, 90.0, 500.0))
        advanceTimeBy(TERRAIN_DEBOUNCE_MS - 1)
        runCurrent()
        assertNull(vm.state.value.terrain, "nothing asked while the operator is still clicking")
        advanceTimeBy(2)
        runCurrent()
        val profile = assertNotNull(vm.state.value.terrain?.profile)
        assertEquals(1, requests, "two quick clicks, one lookup")
        // The copter takeoff the planner adds climbs to the plan's default altitude above a flat 584 m ground.
        assertEquals(profile.samples.last().pathM - 584.0, profile.lowestClearanceM, 1e-6)
    }

    @Test
    fun anUnreachableElevationServerSaysSo() = runTest(dispatcher) {
        val offline = Elevations { error("no route to host") }
        val vm = PlanViewModel(MutableStateFlow(VehicleState()), FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles(), elevations = offline)
        backgroundScope.launch { vm.state.collect {} }
        vm.onMapClick(home)
        vm.onMapClick(north1km)
        advanceTimeBy(TERRAIN_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals("Terrain unavailable: no route to host", vm.state.value.terrain?.message)
        assertNull(vm.state.value.terrain?.profile)
    }
}
