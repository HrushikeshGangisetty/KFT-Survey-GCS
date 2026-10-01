package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.PlanPosition
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MemoryRecordStore
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.RouteStyle
import kotlin.math.round
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Pass 31: the rest of a mission that stopped part-way. The field and the numbers are the ones worked out by hand in
 * core:planning's `ResumeTest`: 290 m × 104 m, lines 30 m apart at x = 10, 40, 70, …, a photo every 10 m, 11 photos
 * a line, 110 in all; pass 0 flies north on x = 10, pass 1 south on x = 40, pass 2 north on x = 70.
 */
class RemainderTest {
    private val origin = LatLon(-35.363261, 149.165230)
    private val projection = LocalProjection(origin)
    private fun at(east: Double, north: Double) = projection.toLatLon(LocalPoint(east, north))
    private fun metres(p: LatLon) = projection.toLocal(p).let { round(it.x * 10) / 10 + 0.0 to round(it.y * 10) / 10 + 0.0 }

    private val camera = Camera(13.2, 8.8, 5472, 3648, 8.8, 0.0, 8.0, "Camera A")
    // Half the focal length: twice the footprint at the same height, so lines 60 m apart and a photo every 20 m.
    private val wide = Camera(13.2, 8.8, 5472, 3648, 4.4, 0.0, 8.0, "Camera B")
    private val survey = defaultSurvey(VehicleKind.COPTER, camera).copy(
        polygon = listOf(at(0.0, 0.0), at(290.0, 0.0), at(290.0, 104.0), at(0.0, 104.0)),
        altitudeM = 50.0, sideOverlapPct = 60.0, frontOverlapPct = 80.0, speedMs = 5.0, turnaroundM = 0.0,
    )
    private val groups = listOf<MissionGroup>(SurveyGroup("Field", survey))

    private fun planned(k: Int): LatLon {
        val (pass, i) = k / 11 to k % 11
        return at(10.0 + 30 * pass, if (pass % 2 == 0) 10.0 * i else 104.0 - 10 * i)
    }
    private val taken25 = (0 until 25).map(::planned) // two lines and three photos of the third, the last at (70, 20)

    private fun rest(photos: List<LatLon>, reached: Int? = null, other: Camera? = null) =
        remainderOf(groups, photos, reached, VehicleKind.COPTER, SurveyLimits(), other)

    /**
     * Same camera: the survey itself, resumed at the last photo. Flattened, it is takeoff, speed, then the third line
     * from (70, 10): 87 photos (85 not yet taken + 2 again for the overlap), where the whole survey had 110.
     */
    @Test
    fun theSameCameraContinuesTheSameLines() {
        val r = rest(taken25).getOrThrow()
        val group = r.groups.single() as SurveyGroup
        assertEquals("Field (rest)", group.name)
        assertEquals(PlanPosition(0, 2, 20.0), group.survey.resume!!.let { it.copy(alongM = round(it.alongM * 100) / 100) })
        assertContains(r.note, "25 of 110 photos were taken")
        assertContains(r.note, "on line 3")

        val flat = flatten(r.groups, VehicleKind.COPTER, SurveyLimits())
        assertEquals(87, flat.plannedPhotos)
        assertEquals(listOf(MissionCommand.TAKEOFF, MissionCommand.DO_CHANGE_SPEED, MissionCommand.WAYPOINT), flat.items.take(3).map { it.command })
        assertEquals(70.0 to 5.0, metres(flat.items[2].position!!)) // the 5 m run-in before the new start
        assertEquals(70.0 to 10.0, metres(flat.items[3].position!!)) // camera on here: one photo back from (70, 20)
        assertEquals(MissionCommand.DO_SET_CAM_TRIGG_DIST, flat.items[4].command)
        // Lines 1 and 2 whole and line 3 up to the last photo are the covered ones.
        assertEquals(listOf(10.0 to 0.0, 10.0 to 104.0), flat.groups.single().covered.first().map(::metres))
        assertEquals(listOf(70.0 to 0.0, 70.0 to 20.0), flat.groups.single().covered.last().map(::metres))
    }

    /**
     * Another camera: lines 1 and 2 (x = 10, 40) are done, so the rest of the area starts midway between x = 40 and
     * x = 70, at x = 55, and is planned again: 235 m wide at Camera B's 60 m spacing = 4 lines instead of the 8 left.
     */
    @Test
    fun anotherCameraPlansTheUnflownAreaAgainAndWarns() {
        val r = rest(taken25, other = wide).getOrThrow()
        val group = r.groups.single() as SurveyGroup
        assertEquals("Field (rest, Camera B)", group.name)
        assertNull(group.survey.resume)
        assertEquals(wide, group.survey.camera)
        assertEquals(55.0 to 290.0, group.survey.polygon.map(::metres).let { m -> m.minOf { it.first } to m.maxOf { it.first } })
        assertContains(r.note, "WARNING: the camera is different (Camera B instead of Camera A)")
        val plan = flatten(r.groups, VehicleKind.COPTER, SurveyLimits()).groups.single().plan!!
        assertEquals(60.0, plan.lineSpacingM, 1e-6)
        assertEquals(4, plan.grid.lineCount) // ⌈235 / 60⌉
    }

    /** A crosshatch can't be cut into "the area not yet flown": it says so instead of guessing. */
    @Test
    fun anotherCameraOnACrosshatchIsRefused() {
        val cross = listOf<MissionGroup>(SurveyGroup("Field", survey.copy(crosshatch = true)))
        val message = remainderOf(cross, taken25, null, VehicleKind.COPTER, SurveyLimits(), wide).exceptionOrNull()!!.message!!
        assertContains(message, "can't be planned again for another camera")
    }

    /**
     * A waypoint mission, no photos: takeoff (1), A (2), B (3), C (4). Item 2 was the last reached, so B and C are
     * left, with a takeoff in front again for the Copter.
     */
    @Test
    fun waypointsContinueAfterTheLastItemReached() {
        val rows = listOf(PlanItem.takeoff(30.0), PlanItem.waypoint(at(0.0, 100.0), 30.0), PlanItem.waypoint(at(100.0, 100.0), 40.0), PlanItem.waypoint(at(100.0, 0.0), 40.0))
        val r = remainderOf(listOf(WaypointGroup("Path", rows)), emptyList(), lastReached = 2, VehicleKind.COPTER, SurveyLimits()).getOrThrow()
        val group = r.groups.single() as WaypointGroup
        assertEquals(listOf(PlanItem.takeoff(40.0), rows[2], rows[3]), group.items)
        assertContains(r.note, "the rest starts at item 3")
    }

    @Test
    fun nothingFlownOrEverythingFlownIsSaid() {
        assertContains(rest(emptyList(), reached = null).exceptionOrNull()!!.message!!, "Nothing of this mission was flown")
        val rows = listOf(PlanItem.takeoff(30.0), PlanItem.waypoint(at(0.0, 100.0), 30.0))
        val done = remainderOf(listOf(WaypointGroup("Path", rows)), emptyList(), lastReached = 2, VehicleKind.COPTER, SurveyLimits())
        assertContains(done.exceptionOrNull()!!.message!!, "nothing is left")
    }

    /** The resume point is saved with the plan, and only fits the lines as flown: moving a corner clears it, changing the speed doesn't. */
    @Test
    fun theResumePointIsSavedAndClearedByAGeometryEdit() {
        val resumed = survey.copy(resume = PlanPosition(0, 2, 20.0))
        assertEquals(listOf<MissionGroup>(SurveyGroup("Field (rest)", resumed)), decodePlan(encodePlan(listOf(SurveyGroup("Field (rest)", resumed)))))
        assertEquals(resumed.resume, resumed.copy(speedMs = 7.0, returnHome = false).withoutStaleResume(resumed).resume)
        assertNull(resumed.copy(sideOverlapPct = 70.0).withoutStaleResume(resumed).resume)
        assertNull(resumed.copy(polygon = resumed.polygon.dropLast(1) + at(0.0, 120.0)).withoutStaleResume(resumed).resume)
    }

    // ---- through the Plan screen: upload → record → the drone stops → resume as a new mission for another drone ----

    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val drone1 = VehicleId(1, 1u)
    private val drone2 = VehicleId(1, 2u)
    private fun state() = VehicleState(connected = true, vehicleKind = VehicleKind.COPTER, home = Home(origin, 584.0))
    private val fleet = MutableStateFlow(listOf(FleetVehicle(drone1, 0, state()), FleetVehicle(drone2, 1, state())))
    private val selected = MutableStateFlow(state())
    private val sync = MissionSync().apply { select(drone1) }
    private val toDrone2 = FakeMissions()

    private fun TestScope.screen(): PlanViewModel {
        val records = MissionRecords(backgroundScope, fleet, MemoryRecordStore(), clockMs = { 1_790_000_000_000 })
        return PlanViewModel(selected, FakeMissions(sync), sync, PlanSettingsRepository(MemoryStore()), FakeFiles(), fleet, missionsOf = { toDrone2 }, records = records).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
        }
    }

    @Test
    fun aStoppedMissionIsListedDrawnAndResumedOnAnotherDrone() = runTest(dispatcher) {
        val vm = screen()
        vm.onAddSurveyClicked()
        survey.polygon.forEach(vm::onMapClick)
        vm.onUploadClicked()
        vm.onUploadConfirmed()
        runCurrent()
        assertTrue(vm.state.value.stopped.isEmpty(), "uploaded, nothing flown yet")

        // Drone 1 takes 20 photos along the uploaded plan's own lines, then leaves AUTO (battery RTL).
        val plan = flatten(decodePlan(encodePlan(listOf(SurveyGroup("x", (vm.state.value.survey!!.settings))))), VehicleKind.COPTER, SurveyLimits()).groups.single().plan!!
        val line = plan.grid.passes.first()
        val shots = (0 until 5).map { i -> LatLon(line.photoStart.latitude + (line.photoEnd.latitude - line.photoStart.latitude) * i / 10, line.photoStart.longitude) }
        fleet.value = listOf(FleetVehicle(drone1, 0, state().copy(photos = shots, flightMode = "RTL", armed = true)), fleet.value[1])
        runCurrent()

        val stopped = vm.state.value.stopped.single()
        assertEquals("Survey 1 · vehicle 1", stopped.title)
        assertContains(stopped.detail, "5 / ${plan.stats.photoCount} photos")
        assertTrue(stopped.canResume)
        val covered = vm.state.value.overlays.filterIsInstance<MapOverlay.Route>().filter { it.style == RouteStyle.COVERED }
        assertEquals(1, covered.size, "the part of the first line already photographed is drawn as covered")

        vm.onResumeClicked(stopped.id, cameraName = null)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("Waypoints", "Survey 1", "Survey 1 (rest)"), s.groups.map { it.name })
        assertEquals(2, s.missions.size, "the rest is a mission of its own")
        vm.onMissionVehicleSelected(s.missions.last().id, drone2)
        vm.onUploadClicked()
        vm.onUploadConfirmed()
        runCurrent()
        assertTrue(toDrone2.uploaded.isNotEmpty(), "the rest went to drone 2")
        assertTrue(toDrone2.uploaded.size < plan.stats.photoCount + 100 && toDrone2.uploaded.first().command == MissionCommand.TAKEOFF)

        vm.onStoppedDismissed(stopped.id)
        runCurrent()
        assertTrue(vm.state.value.stopped.none { it.id == stopped.id })
    }

    /** While its vehicle is still in AUTO the mission is "flying", and Resume waits. */
    @Test
    fun aMissionStillBeingFlownCannotBeResumedYet() = runTest(dispatcher) {
        val vm = screen()
        vm.onAddSurveyClicked()
        survey.polygon.forEach(vm::onMapClick)
        vm.onUploadClicked()
        vm.onUploadConfirmed()
        runCurrent()
        fleet.value = listOf(FleetVehicle(drone1, 0, state().copy(photos = listOf(at(10.0, 0.0)), flightMode = "Auto", armed = true)), fleet.value[1])
        runCurrent()
        val row = vm.state.value.stopped.single()
        assertContains(row.detail, "flying")
        assertTrue(!row.canResume)
    }
}
