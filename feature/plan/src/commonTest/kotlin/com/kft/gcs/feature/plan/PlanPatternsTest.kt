package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.MapOverlay
import kotlin.test.AfterTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

/** Crosshatch and corridor on the Plan tab: mission items, map clicks, overlays, the panel and plan files. */
class PlanPatternsTest {
    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, vehicleKind = VehicleKind.COPTER))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), FakeFiles()).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    private val camera = PlanSettings().cameras.first()
    private fun offset(east: Double, north: Double) = LatLon(-35.36 + north / 111_000, 149.16 + east / 90_700)
    private val field = listOf(offset(0.0, 0.0), offset(200.0, 0.0), offset(200.0, 150.0), offset(0.0, 150.0))

    /**
     * Crosshatch 20 m higher: after the first set of lines at 50 m, every waypoint of the second set is at 70 m, and
     * the camera is switched on with each set's own trigger distance (the higher one is longer, same overlap).
     */
    @Test
    fun crosshatchItemsFlyTheSecondSetHigher() {
        val survey = defaultSurvey(VehicleKind.COPTER, camera).copy(polygon = field, crosshatch = true, crosshatchOffsetM = 20.0)
        val flat = flatten(listOf(SurveyGroup("S", survey)), VehicleKind.COPTER, SurveyLimits())
        val plan = flat.groups.single().plan!!
        val waypointAlts = flat.items.filter { it.command == MissionCommand.WAYPOINT }.map { it.altitudeM }.distinct()
        assertEquals(listOf(50.0, 70.0), waypointAlts)
        val triggers = flat.items.filter { it.command == MissionCommand.DO_SET_CAM_TRIGG_DIST && it.param1 > 0 }.map { it.param1.toDouble() }.distinct()
        assertEquals(listOf(plan.triggerDistanceM, plan.crosshatch!!.triggerDistanceM).map { it.toFloat().toDouble() }, triggers)
        assertEquals(plan.crosshatch!!.triggerDistanceM / plan.triggerDistanceM, 70.0 / 50.0, 1e-6) // footprint ∝ altitude
    }

    /** A corridor's clicks extend its centre line in order (an area would put a corner into the nearest side). */
    @Test
    fun corridorOnTheMap() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddCorridorClicked()
        vm.onMapClick(offset(0.0, 0.0))
        runCurrent()
        assertEquals("Click the map to draw the corridor's centre line, point by point.", vm.state.value.hint)
        vm.onMapClick(offset(300.0, 0.0))
        vm.onMapClick(offset(300.0, 200.0))
        vm.onMapClick(offset(200.0, 300.0))
        runCurrent()
        val s = vm.state.value
        assertEquals("Corridor", s.groups.last().kind)
        val clicked = listOf(offset(0.0, 0.0), offset(300.0, 0.0), offset(300.0, 200.0), offset(200.0, 300.0))
        assertEquals(clicked, s.overlays.filterIsInstance<MapOverlay.Marker>().filter { it.id.startsWith("corner") }.map { it.position })
        // The outline: 4 points on each edge (left then right), 8 corners.
        assertEquals(8, s.overlays.filterIsInstance<MapOverlay.Polygon>().single().corners.size)
        assertTrue(s.survey!!.stats.any { it.first == "Side overlap" })
        assertTrue(s.overlays.none { it is MapOverlay.Marker && it.id.startsWith("entry") }, "no start-corner options on a corridor")
    }

    /**
     * An L-shaped corridor, one line: after the camera comes on at the line's start, the next waypoint is the bend,
     * so the vehicle follows the corridor round the corner with the camera running.
     */
    @Test
    fun corridorItemsFollowTheBend() {
        val bend = offset(300.0, 0.0)
        val survey = defaultSurvey(VehicleKind.COPTER, camera).copy(
            polygon = listOf(offset(0.0, 0.0), bend, offset(300.0, 300.0)),
            pattern = SurveyPattern.CORRIDOR, leftWidthM = 0.0, rightWidthM = 20.0, corridorLines = 1,
        )
        val flat = flatten(listOf(SurveyGroup("C", survey)), VehicleKind.COPTER, SurveyLimits())
        val items = flat.items
        val on = items.indexOfFirst { it.command == MissionCommand.DO_SET_CAM_TRIGG_DIST && it.param1 > 0 }
        val off = items.indexOfFirst { it.command == MissionCommand.DO_SET_CAM_TRIGG_DIST && it.param1 == 0f }
        val between = items.subList(on + 1, off).filter { it.command == MissionCommand.WAYPOINT }
        assertEquals(2, between.size, "the bend, then the camera-off point")
        assertEquals(flat.groups.single().plan!!.grid.passes.single().via.single(), between[0].position)
    }

    @Test
    fun wholeLinesOnly() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddCorridorClicked()
        vm.onSurveyNumberChanged(SurveyField.CORRIDOR_LINES, 3.5)
        vm.onSurveyNumberChanged(SurveyField.CORRIDOR_LINES, 5.0)
        runCurrent()
        assertEquals(5, vm.state.value.survey!!.settings.corridorLines)
    }

    /** Saved and opened, a corridor and a crosshatch come back as they were; a Pass 24 file (no such fields) is an area. */
    @Test
    fun planFilesKeepThePattern() {
        val corridor = defaultSurvey(VehicleKind.COPTER, camera).copy(pattern = SurveyPattern.CORRIDOR, leftWidthM = 10.0, rightWidthM = 40.0, corridorLines = 4, includeCentreLine = true)
        val cross = defaultSurvey(VehicleKind.COPTER, camera).copy(polygon = field, crosshatch = true, crosshatchOffsetM = -10.0)
        val groups = listOf(SurveyGroup("C", corridor), SurveyGroup("X", cross))
        assertEquals(groups, decodePlan(encodePlan(groups)))
        // The same survey as Pass 24 wrote it: the new keys removed from the JSON.
        val newKeys = setOf("pattern", "crosshatch", "crosshatchOffsetM", "leftWidthM", "rightWidthM", "corridorLines", "includeCentreLine")
        val json = Json.parseToJsonElement(encodePlan(listOf(SurveyGroup("Old", cross)))).jsonObject
        val group = json["groups"]!!.jsonArray[0].jsonObject
        val old = JsonObject(json + ("groups" to JsonArray(listOf(JsonObject(group.filterKeys { it !in newKeys }))))).toString()
        assertEquals(SurveyPattern.AREA, (decodePlan(old).single() as SurveyGroup).survey.pattern)
    }
}
