package com.kft.gcs.feature.plan

import app.cash.turbine.test
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.geoio.ImportedShapes
import com.kft.gcs.core.geoio.NamedPoint
import com.kft.gcs.core.geoio.NamedShape
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.math.ln
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

/** Import on the Plan tab: file → "survey areas or waypoints?" → groups, one undo step, map moved to them. */
class PlanImportTest {
    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, vehicleKind = VehicleKind.COPTER))
    private val files = FakeFiles()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel() = PlanViewModel(vehicle, FakeMissions(), MissionSync(), PlanSettingsRepository(MemoryStore()), files).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    private val corners = listOf(LatLon(17.41, 78.47), LatLon(17.41, 78.48), LatLon(17.402, 78.481))
    private val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
        <Placemark><name>North field</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            78.47,17.41 78.48,17.41 78.481,17.402 78.47,17.41</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        <Placemark><name>Gate</name><Point><coordinates>78.4695,17.4015</coordinates></Point></Placemark>
        </Document></kml>"""

    @Test
    fun kmlAreaBecomesASurvey() = runTest(dispatcher) {
        val vm = viewModel()
        files.toImport = ImportFile("farm.kml", kml.encodeToByteArray())
        vm.effects.test {
            vm.onImportClicked()
            runCurrent()
            val dialog = vm.state.value.importDialog!!
            assertEquals("1 area, 1 point", dialog.summary)
            assertTrue(dialog.canSurvey)

            vm.onImportAsSurveys()
            assertEquals("Added 1 survey from farm.kml.", awaitItem())
        }
        runCurrent()
        val s = vm.state.value
        assertNull(s.importDialog)
        assertEquals(listOf("Waypoints", "North field"), s.groups.map { it.name })
        assertTrue(s.groups[1].selected)
        assertEquals(corners, s.overlays.filterIsInstance<com.kft.gcs.ui.map.MapOverlay.Polygon>().single().corners)
        // The middle of everything imported, the Gate included: lat (17.4015 + 17.41) / 2, lon (78.4695 + 78.481) / 2.
        assertEquals(LatLon(17.40575, 78.47525), s.cameraRequest!!.target.let { LatLon(round6(it.latitude), round6(it.longitude)) })

        vm.onUndoClicked() // one step removes the whole import
        runCurrent()
        assertEquals(listOf("Waypoints"), vm.state.value.groups.map { it.name })
    }

    /** CSV points as waypoints on a fresh Copter plan: takeoff first, then each point at the default 30 m. */
    @Test
    fun csvPointsBecomeWaypoints() = runTest(dispatcher) {
        val vm = viewModel()
        files.toImport = ImportFile("gates.csv", "lat,lon\n17.4015,78.4695\n17.409,78.479\n".encodeToByteArray())
        vm.onImportClicked()
        runCurrent()
        assertFalse(vm.state.value.importDialog!!.canSurvey) // points only: nothing to survey
        vm.onImportAsWaypoints()
        runCurrent()
        val s = vm.state.value
        assertEquals("gates", s.groups.last().name)
        assertEquals(listOf("1  Takeoff", "2  Waypoint", "3  Waypoint"), s.rows.map { "${it.seq}  ${it.title}" })
    }

    @Test
    fun unusableFileSaysWhyAndOpensNoDialog() = runTest(dispatcher) {
        val vm = viewModel()
        files.toImport = ImportFile("field.shp", ByteArray(120))
        vm.effects.test {
            vm.onImportClicked()
            assertEquals("Couldn't import field.shp: This isn't a shapefile (.shp): its header is wrong.", awaitItem())
        }
        assertNull(vm.state.value.importDialog)
    }

    @Test
    fun cancelLeavesThePlanAlone() = runTest(dispatcher) {
        val vm = viewModel()
        files.toImport = ImportFile("farm.kml", kml.encodeToByteArray())
        vm.onImportClicked()
        runCurrent()
        vm.onImportDismissed()
        runCurrent()
        assertNull(vm.state.value.importDialog)
        assertEquals(1, vm.state.value.groups.size)
    }

    // ---- the pure helpers

    @Test
    fun severalUnnamedAreasAreNumberedAfterTheFile() {
        val shapes = ImportedShapes(areas = listOf(NamedShape(null, corners), NamedShape(null, corners)))
        val template = defaultSurvey(VehicleKind.COPTER, PlanSettings().cameras.first())
        assertEquals(listOf("plots 1", "plots 2"), surveyGroupsFrom(shapes, "plots.geojson", template).map { it.name })
    }

    /** Points go in one group, each line in its own; areas only when there's nothing else. Planes get no takeoff. */
    @Test
    fun waypointGrouping() {
        val shapes = ImportedShapes(
            areas = listOf(NamedShape("Field", corners)),
            lines = listOf(NamedShape("Canal", corners.take(2))),
            points = listOf(NamedPoint("Gate", corners[0])),
        )
        val groups = waypointGroupsFrom(shapes, "farm.kml", VehicleKind.PLANE, startsMission = true)
        assertEquals(listOf("farm points", "Canal"), groups.map { it.name })
        assertTrue(groups.flatMap { it.items }.all { it.command == MissionCommand.WAYPOINT && it.altitudeM == 100.0 })
        val outlineOnly = waypointGroupsFrom(ImportedShapes(areas = shapes.areas), "farm.kml", VehicleKind.COPTER, startsMission = false)
        assertEquals(3, outlineOnly.single().items.size) // three corners, no takeoff (an earlier group has items)
    }

    /**
     * By hand: 0.01° of longitude on the equator, z = log2(703 / 0.01) = log2(70 300) = 16.101; at 60° a 0.01° span of
     * latitude counts double (1/cos 60°), one zoom level out.
     */
    @Test
    fun fitCameraZoom() {
        val eq = fitCamera(listOf(LatLon(0.0, 0.0), LatLon(0.0, 0.01)), 1)!!
        assertEquals(16.101, eq.zoom, 1e-3)
        assertEquals(LatLon(0.0, 0.005), eq.target)
        val north = fitCamera(listOf(LatLon(60.0, 10.0), LatLon(60.01, 10.0)), 2)!!
        assertEquals(ln(703 / 0.02) / ln(2.0), north.zoom, 1e-3)
        assertNull(fitCamera(emptyList(), 3))
        assertIs<Double>(fitCamera(listOf(LatLon(1.0, 1.0)), 4)!!.zoom) // one point: the closest zoom, not infinity
    }

    private fun round6(d: Double) = kotlin.math.round(d * 1e6) / 1e6
}
