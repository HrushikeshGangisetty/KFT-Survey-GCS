package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.planSurvey
import com.kft.gcs.core.vehicle.Fence
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
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

/** Pass 30: jobs (several missions, each for a vehicle), splitting an area, and the per-mission upload checks. */
class JobTest {
    private val home = LatLon(-35.363261, 149.165230)
    private val cam = Camera(13.2, 8.8, 5472, 3648, 8.8, 2.0, 8.0, "P4P")
    private val projection = LocalProjection(home)
    private fun at(east: Double, north: Double) = projection.toLatLon(LocalPoint(east, north))

    // ---- the estimate and the checks: numbers worked by hand ----

    private val a = Geodesy.destination(home, 0.0, 300.0)  // 300 m north of home
    private val b = Geodesy.destination(a, 90.0, 400.0)    // then 400 m east: 500 m from home (3-4-5 triangle)
    private val triangle = listOf(
        MissionItem(MissionCommand.TAKEOFF, altitudeM = 30.0),
        MissionItem(MissionCommand.DO_CHANGE_SPEED, param2 = 5f),
        MissionItem(MissionCommand.WAYPOINT, a, 30.0),
        MissionItem(MissionCommand.WAYPOINT, b, 30.0),
        MissionItem(MissionCommand.RETURN_TO_LAUNCH),
    )

    /**
     * Copter: 300 + 400 + 500 m = 1200 m at 5 m/s = 240 s; climb 30 m at 2.5 m/s = 12 s; descent 30 m at 1.5 m/s =
     * 20 s. 272 s in all. Highest point 30 m, farthest point B, 500 m from home.
     */
    @Test
    fun aCopterMissionsTimeIsLegsPlusClimbPlusDescent() {
        val e = estimateMission(triangle, home, VehicleKind.COPTER)
        assertEquals(1200.0, e.distanceM, 0.05)
        assertEquals(272.0, e.timeS, 0.02)
        assertEquals(30.0, e.maxAltitudeM)
        assertEquals(500.0, e.farthestM!!, 0.05)
    }

    /** A plane doesn't climb or descend in the estimate (its pilot takes off and lands): just 1200 m / 5 m/s = 240 s. */
    @Test
    fun aPlanesTimeIsItsLegs() {
        assertEquals(240.0, estimateMission(triangle.drop(1), home, VehicleKind.PLANE).timeS, 0.02)
    }

    /** Without a speed item the firmware default applies: Copter 10 m/s, so 700 m out is 70 s. No home: no distance from it. */
    @Test
    fun defaultSpeedAndUnknownHome() {
        val out = listOf(MissionItem(MissionCommand.WAYPOINT, a, 30.0), MissionItem(MissionCommand.WAYPOINT, b, 30.0))
        assertEquals(70.0, estimateMission(out, home, VehicleKind.COPTER).timeS, 0.01)
        val noHome = estimateMission(out, null, VehicleKind.COPTER)
        assertNull(noHome.farthestM)
        assertEquals(400.0, noHome.distanceM, 0.05) // only A → B: there is nowhere to start from
    }

    @Test
    fun everyLimitThatIsBrokenIsSaidInWords() {
        val e = MissionEstimate(distanceM = 1200.0, timeS = 272.0, maxAltitudeM = 130.0, farthestM = 2500.0)
        val all = missionProblems(e, MissionLimits(maxAltitudeM = 120.0, maxDistanceM = 2000.0, batteryS = 240.0, fence = Fence(altMaxM = 100.0, radiusM = 300.0)))
        assertEquals(
            listOf(
                "climbs to 130 m above home, over your 120 m limit.",
                "goes 2.5 km from home, beyond your 2.0 km limit.",
                "needs about 4 min 32 s, more than one battery (4 min 0 s). Split it, or plan a battery change.",
                "reaches 130 m, at or above the vehicle's fence (FENCE_ALT_MAX 100 m): the vehicle will break off there.",
                "goes 2.5 km from home, to or past the vehicle's fence (FENCE_RADIUS 300 m): the vehicle will break off there.",
            ),
            all,
        )
        assertEquals(emptyList(), missionProblems(e, MissionLimits(null, null, null, null)), "no limits, no problems")
        // Exactly at the operator's own limit is inside it; exactly at the fence is a breach (ArduPilot acts at the fence).
        val atLimit = e.copy(maxAltitudeM = 120.0, farthestM = 300.0, timeS = 240.0)
        assertEquals(
            listOf("goes 300 m from home, to or past the vehicle's fence (FENCE_RADIUS 300 m): the vehicle will break off there."),
            missionProblems(atLimit, MissionLimits(120.0, 2000.0, 240.0, Fence(altMaxM = 121.0, radiusM = 300.0))),
        )
    }

    // ---- missions of a job ----

    private val drone1 = VehicleId(1, 1u)
    private val drone2 = VehicleId(1, 2u)
    private val drone3 = VehicleId(1, 3u)

    @Test
    fun whichMissionAVehicleFlies() {
        assertEquals(0, missionFor(drone1, listOf(0), emptyMap()), "one unassigned mission is the selected vehicle's")
        assertEquals(0, missionFor(null, listOf(0), emptyMap()), "also before any vehicle is connected")
        assertNull(missionFor(drone1, listOf(0), mapOf(0 to drone2)), "it was given to another vehicle")
        assertEquals(4, missionFor(drone2, listOf(0, 4, 5), mapOf(4 to drone2, 5 to drone3)))
        assertNull(missionFor(drone1, listOf(0, 4), emptyMap()), "a job's missions go to nobody until they are assigned")
    }

    /** Each mission is numbered from 1 and, on a Copter, starts with its own takeoff. */
    @Test
    fun everyMissionOfAJobIsNumberedOnItsOwn() {
        fun square(east: Double) = listOf(at(east, 0.0), at(east + 80, 0.0), at(east + 80, 80.0), at(east, 80.0))
        val groups = listOf(
            SurveyGroup("West", defaultSurvey(VehicleKind.COPTER, cam).copy(polygon = square(100.0)), mission = 0),
            SurveyGroup("East", defaultSurvey(VehicleKind.COPTER, cam).copy(polygon = square(300.0)), mission = 1),
        )
        val flat = flatten(groups, VehicleKind.COPTER, SurveyLimits())
        assertEquals(listOf(0, 1), flat.missions)
        assertEquals(listOf(1, 1), flat.groups.map { it.firstSeq })
        assertEquals(listOf(MissionCommand.TAKEOFF, MissionCommand.TAKEOFF), flat.missions.map { flat.only(it).items.first().command })
        assertEquals(flat.items.size, flat.only(0).items.size + flat.only(1).items.size)
        assertEquals("West" to "M1", missionName(groups, 0) to missionTag(flat.missions, 0))
    }

    @Test
    fun aJobFileKeepsItsMissionsAndVehicles() {
        val groups = listOf(
            WaypointGroup("Approach", listOf(PlanItem.takeoff(30.0)), mission = 0),
            SurveyGroup("North", defaultSurvey(VehicleKind.COPTER, cam).copy(polygon = listOf(at(0.0, 0.0), at(90.0, 0.0), at(0.0, 90.0))), mission = 3),
        )
        val text = encodePlan(groups, mapOf(3 to drone2))
        assertContains(text, "\"version\": 2")
        assertEquals(PlanJob(groups, mapOf(3 to drone2)), decodeJob(text))
        // One mission, nobody assigned: still the version 1 file an older build opens, and it reads back as mission 0.
        val plain = encodePlan(groups.take(1))
        assertContains(plain, "\"version\": 1")
        assertEquals(PlanJob(groups.take(1)), decodeJob(plain))
    }

    // ---- splitting an area ----

    // 290 m east–west by 100 m north–south. P4P at 50 m: footprint 13.2 / 8.8 × 50 = 75 m across; 70 % side overlap
    // leaves 22.5 m between lines; ⌈290 / 22.5⌉ = 13 lines, north–south.
    private val field = SurveyGroup("Field", defaultSurvey(VehicleKind.COPTER, cam).copy(polygon = listOf(at(0.0, 0.0), at(290.0, 0.0), at(290.0, 100.0), at(0.0, 100.0))))

    private fun lines(group: SurveyGroup) = planSurvey(group.survey.toParams(VehicleKind.COPTER)).grid.lineCount

    @Test
    fun threePartsShareThe13LinesAndCanStepInHeight() {
        val parts = splitSurvey(field, VehicleKind.COPTER, SurveyLimits(), parts = 3, altitudeStepM = 0.0).getOrThrow()
        assertEquals(listOf("Field 1/3", "Field 2/3", "Field 3/3"), parts.map { it.name })
        // 13 equal lines: boundaries nearest 4.33 and 8.67 lines are after 4 and after 9.
        assertEquals(listOf(4, 5, 4), parts.map(::lines))

        val stepped = splitSurvey(field, VehicleKind.COPTER, SurveyLimits(), parts = 3, altitudeStepM = 5.0).getOrThrow()
        assertEquals(listOf(50.0, 55.0, 60.0), stepped.map { it.survey.altitudeM })
        assertTrue(stepped.all { it.survey.heightMode == HeightMode.ALTITUDE })
    }

    /**
     * The whole field takes T. With a battery of T / 2.5: two parts would be 6 and 7 of 13 lines, about 0.54 T, too
     * long; three parts are at most 5 of 13 lines, about 0.38 T, which fits. So "one battery each" gives 3 parts.
     */
    @Test
    fun oneBatteryEachTakesTheFewestPartsThatFit() {
        val whole = planSurvey(field.survey.toParams(VehicleKind.COPTER)).stats.flightTimeS
        val limits = SurveyLimits(usableFlightTimeS = whole / 2.5)
        val parts = splitSurvey(field, VehicleKind.COPTER, limits, parts = null, altitudeStepM = 0.0).getOrThrow()
        assertEquals(3, parts.size)
        assertTrue(parts.all { planSurvey(it.survey.toParams(VehicleKind.COPTER)).stats.flightTimeS <= whole / 2.5 })

        assertContains(splitSurvey(field, VehicleKind.COPTER, SurveyLimits(usableFlightTimeS = whole * 2), null, 0.0).exceptionOrNull()!!.message!!, "already fits one battery")
        assertContains(splitSurvey(field, VehicleKind.COPTER, SurveyLimits(), null, 0.0).exceptionOrNull()!!.message!!, "Set the battery time first")
    }

    // ---- the Plan screen with a job: one KML, three fields, three drones ----

    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    // Three fields near CMAC, as lon,lat rings: A and B west (B north of A), C east.
    private val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
        <Placemark><name>Field A</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1656706,-35.3629913 149.1669922,-35.3629913 149.1669922,-35.3622721 149.1656706,-35.3622721 149.1656706,-35.3629913
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        <Placemark><name>Field B</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1656706,-35.3620024 149.1669922,-35.3620024 149.1669922,-35.3612832 149.1656706,-35.3612832 149.1656706,-35.3620024
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        <Placemark><name>Field C</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1674328,-35.3629913 149.1687545,-35.3629913 149.1687545,-35.3622721 149.1674328,-35.3622721 149.1674328,-35.3629913
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        </Document></kml>"""

    private fun drone(id: VehicleId, east: Double) = FleetVehicle(id, id.systemId.toInt() - 1, VehicleState(connected = true, vehicleKind = VehicleKind.COPTER, home = Home(at(east, 0.0), 584.0)))
    private val fleet = MutableStateFlow(listOf(drone(drone1, 0.0), drone(drone2, 10.0), drone(drone3, 20.0)))
    private val selected = MutableStateFlow(fleet.value.first().state)
    private val sync = MissionSync().apply { select(drone1) }
    private val files = FakeFiles()
    private val perVehicle = mapOf(drone1 to FakeMissions(), drone2 to FakeMissions(), drone3 to FakeMissions())

    private fun TestScope.viewModel() =
        PlanViewModel(selected, perVehicle.getValue(drone1), sync, PlanSettingsRepository(MemoryStore()), files, fleet, missionsOf = { perVehicle.getValue(it) }).also { vm ->
            backgroundScope.launch { vm.state.collect {} }
            runCurrent()
        }

    private fun TestScope.importedJob(): PlanViewModel = viewModel().also { vm ->
        files.toImport = ImportFile("fields.kml", kml.encodeToByteArray())
        vm.onImportClicked()
        runCurrent()
        assertTrue(vm.state.value.importDialog!!.canMissions, "three areas can be three missions")
        vm.onImportAsMissions()
        runCurrent()
    }

    @Test
    fun aKmlOfThreeFieldsBecomesThreeMissionsNamedFromItsPlacemarks() = runTest(dispatcher) {
        val vm = importedJob()
        val s = vm.state.value
        assertEquals(listOf("Waypoints" to "M1", "Field A" to "M1", "Field B" to "M2", "Field C" to "M3"), s.groups.map { it.name to it.mission })
        assertEquals(listOf("M1" to "Field A", "M2" to "Field B", "M3" to "Field C"), s.missions.map { it.tag to it.name })
        assertTrue(s.missions.all { it.vehicle == "Not assigned" })
        assertEquals(listOf("Vehicle 1", "Vehicle 2", "Vehicle 3"), s.vehicleChoices.map { it.label })

        vm.onUndoClicked() // the import is one step
        runCurrent()
        assertEquals(listOf("Waypoints"), vm.state.value.groups.map { it.name })
    }

    @Test
    fun eachMissionIsUploadedToItsOwnVehicle() = runTest(dispatcher) {
        val vm = importedJob()
        vm.onMissionVehicleSelected(0, drone1)
        vm.onMissionVehicleSelected(1, drone2)
        vm.onMissionVehicleSelected(2, drone3)
        runCurrent()
        assertEquals(listOf("Vehicle 1", "Vehicle 2", "Vehicle 3"), vm.state.value.missions.map { it.vehicle })

        vm.onUploadClicked()
        runCurrent()
        val preview = vm.state.value.preview!!
        assertEquals("Upload 3 of 3 missions?", preview.title)
        assertEquals(3, preview.lines.count { it.startsWith("— M") }, "one block per mission")
        assertContains(preview.lines.first(), "M1 Field A → vehicle 1")
        vm.onUploadConfirmed()
        runCurrent()

        val sent = listOf(drone1, drone2, drone3).map { perVehicle.getValue(it).uploaded }
        assertTrue(sent.all { it.isNotEmpty() && it.first().command == MissionCommand.TAKEOFF }, "each got a whole mission of its own")
        // Each vehicle got the waypoints of its own field and none of another's: A is south-west, B north, C east.
        fun box(items: List<MissionItem>) = items.mapNotNull { it.position }.let { p -> projection.toLocal(LatLon(p.map { it.latitude }.average(), p.map { it.longitude }.average())) }
        val centres = sent.map(::box)
        assertTrue(centres[1].y > centres[0].y + 80, "vehicle 2's mission is the northern field")
        assertTrue(centres[2].x > centres[0].x + 120, "vehicle 3's mission is the eastern field")
    }

    @Test
    fun aMissionWithoutAVehicleIsLeftOutAndAVehicleFliesOnlyOne() = runTest(dispatcher) {
        val vm = importedJob()
        vm.onMissionVehicleSelected(0, drone1)
        vm.onMissionVehicleSelected(1, drone2)
        vm.onMissionVehicleSelected(2, drone2) // drone 2 is moved to mission 3: mission 2 loses it
        runCurrent()
        assertEquals(listOf("Vehicle 1", "Not assigned", "Vehicle 2"), vm.state.value.missions.map { it.vehicle })

        vm.onUploadClicked()
        runCurrent()
        val preview = vm.state.value.preview!!
        assertEquals("Upload 2 of 3 missions?", preview.title)
        assertContains(preview.warnings, "M2 Field B: no vehicle assigned: it will not be uploaded.")
        vm.onUploadConfirmed()
        runCurrent()
        assertTrue(perVehicle.getValue(drone1).uploaded.isNotEmpty() && perVehicle.getValue(drone2).uploaded.isNotEmpty())
        assertTrue(perVehicle.getValue(drone3).uploaded.isEmpty(), "nothing was assigned to drone 3")
    }

    /** "The plan" Fly compares the vehicle's mission with is the mission assigned to the selected vehicle. */
    @Test
    fun thePlanForTheSelectedVehicleFollowsTheSelection() = runTest(dispatcher) {
        val vm = importedJob()
        assertTrue(sync.state.value.planned.isEmpty(), "a job's missions are nobody's until assigned")
        vm.onMissionVehicleSelected(1, drone2)
        runCurrent()
        assertTrue(sync.state.value.planned.isEmpty(), "drone 1 is selected and has no mission")
        sync.select(drone2)
        runCurrent()
        val planned = sync.state.value.planned
        assertEquals(MissionCommand.TAKEOFF, planned.first().command)
        assertTrue(planned.mapNotNull { it.position }.all { projection.toLocal(it).y > 120 }, "it is Field B, the northern one")
    }

    /** The preview applies the per-mission checks: a survey at 150 m is over the 120 m default, and the fence is nearer than the field. */
    @Test
    fun thePreviewChecksAltitudeAndTheVehiclesFence() = runTest(dispatcher) {
        selected.value = selected.value.copy(watchedParams = mapOf("FENCE_ENABLE" to 1f, "FENCE_TYPE" to 2f, "FENCE_RADIUS" to 100f))
        val vm = viewModel()
        vm.onAddSurveyClicked()
        listOf(at(40.0, 30.0), at(160.0, 30.0), at(160.0, 110.0)).forEach(vm::onMapClick)
        vm.onSurveyNumberChanged(SurveyField.ALTITUDE, 150.0)
        vm.onUploadClicked()
        runCurrent()
        val warnings = vm.state.value.preview!!.warnings
        assertContains(warnings, "The mission climbs to 150 m above home, over your 120 m limit.")
        assertTrue(warnings.any { it.startsWith("The mission goes ") && it.contains("FENCE_RADIUS 100 m") }, warnings.toString())
        assertEquals("Upload ${vm.state.value.preview!!.lines.size - 1} items?", vm.state.value.preview!!.title, "one mission: the plain upload")
    }

    @Test
    fun splittingASurveyMakesAMissionPerPart() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddSurveyClicked()
        field.survey.polygon.forEach(vm::onMapClick)
        vm.onSplitSurvey(parts = 3, altitudeStepM = 5.0)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("Waypoints", "Survey 1 1/3", "Survey 1 2/3", "Survey 1 3/3"), s.groups.map { it.name })
        assertEquals(3, s.missions.size)
        vm.onUndoClicked()
        runCurrent()
        assertEquals(listOf("Waypoints", "Survey 1"), vm.state.value.groups.map { it.name }, "one undo puts the area back")
    }
}
