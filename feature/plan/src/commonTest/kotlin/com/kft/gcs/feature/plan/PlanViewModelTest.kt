package com.kft.gcs.feature.plan

import app.cash.turbine.test
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.altitudeForGsdM
import com.kft.gcs.core.mission.Home
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.MissionTransferException
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.MarkerStyle
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class PlanViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val home = Home(LatLon(-35.363261, 149.165230), 584.0)
    private val a = LatLon(-35.3620, 149.1660)
    private val b = LatLon(-35.3640, 149.1670)
    private val vehicle = MutableStateFlow(VehicleState(connected = true, vehicleKind = VehicleKind.COPTER, home = home))
    private val missions = FakeMissions()
    private val sync = MissionSync()
    private val files = FakeFiles()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** stateIn(WhileSubscribed) only runs while someone collects, so the test collects like the screen does. */
    private fun TestScope.viewModel() = PlanViewModel(vehicle, missions, sync, PlanSettingsRepository(MemoryStore()), files).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    private fun markers(vm: PlanViewModel) = vm.state.value.overlays.filterIsInstance<MapOverlay.Marker>()

    @Test
    fun clickingTheMapBuildsACopterPlanWithHomeAndNumberedMarkers() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMapClick(b)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("1  Takeoff", "2  Waypoint", "3  Waypoint"), s.rows.map { "${it.seq}  ${it.title}" })
        val m = markers(vm)
        assertEquals(listOf("H", "2", "3"), m.map { it.label }, "home is a marker, never a waypoint (S11)")
        assertEquals(MarkerStyle.SELECTED, m.last().style, "the newest waypoint is selected for editing")
        assertFalse(m.first().draggable, "home can't be dragged")
        assertEquals(listOf(home.position, a, b), s.overlays.filterIsInstance<MapOverlay.Route>().single().points)
    }

    @Test
    fun draggingAMarkerMovesThatWaypoint() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMarkerDragged(markerId(0, 1), b)
        runCurrent()
        assertEquals(b, markers(vm).last().position)
        vm.onMarkerDragged(HOME_MARKER, a) // ignored: home comes from the vehicle
        runCurrent()
        assertEquals(home.position, markers(vm).first().position)
    }

    @Test
    fun editingAltitudeAndSpeed() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onAltitudeChanged("45")
        vm.onSpeedChanged("7.5")
        runCurrent()
        assertEquals("45.0 m · 7.5 m/s", vm.state.value.rows[1].detail)
        assertEquals(3, vm.state.value.rows[1].seq, "a speed change takes seq 2, so the waypoint becomes 3")

        vm.onAltitudeChanged("abc")
        runCurrent()
        assertEquals("Altitude must be a number", vm.state.value.form!!.error)
        assertEquals("45.0 m · 7.5 m/s", vm.state.value.rows[1].detail, "a bad edit doesn't change the item")
    }

    /** Upload opens a preview first: seq 0 is home (S11), then every item. Nothing is sent until confirmed. */
    @Test
    fun uploadShowsAPreviewThenSendsAndReportsProgress() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onUploadClicked()
        runCurrent()
        val preview = vm.state.value.preview!!
        assertEquals("0  Home (the vehicle's own, sent as seq 0)", preview.lines[0])
        assertEquals(listOf("1  Takeoff · 30.0 m", "2  Waypoint · -35.362, 149.166 · 30.0 m"), preview.lines.drop(1))
        assertTrue(preview.warnings.isEmpty(), "${preview.warnings}")
        assertEquals(emptyList(), missions.uploaded, "nothing sent yet")

        missions.hold = CompletableDeferred()
        vm.effects.test {
            vm.onUploadConfirmed()
            runCurrent()
            assertNull(vm.state.value.preview)
            assertEquals("Uploading 2 / 3…", vm.state.value.transfer, "progress from the repository")
            assertFalse(vm.state.value.canUpload, "one transfer at a time")
            missions.hold!!.complete(Unit)
            assertContains(awaitItem(), "Uploaded 2 items")
        }
        runCurrent()
        assertNull(vm.state.value.transfer)
        assertEquals(listOf(MissionCommand.TAKEOFF, MissionCommand.WAYPOINT), missions.uploaded.map { it.command })
    }

    /** Armed: the preview names the mode and what uploading does; still a warning, not a block. */
    @Test
    fun armedVehicleWarnsInThePreviewAndClearAsksFirst() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        runCurrent()
        assertEquals("This deletes the mission on the vehicle and empties the editor.", vm.state.value.clearWarning)

        vehicle.value = vehicle.value.copy(armed = true, flightMode = "Auto")
        vm.onUploadClicked()
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("Vehicle is ARMED in AUTO: uploading replaces the mission it is flying."), s.preview!!.warnings)
        assertEquals("Vehicle is ARMED in AUTO: clearing deletes the mission it is flying.", s.clearWarning)
        assertTrue(s.canUpload && s.canClear, "a warning, not a block")
    }

    /** "Not uploaded" after any edit; "On vehicle" once the upload succeeds; the shared sync knows it for Fly. */
    @Test
    fun planVersusVehicleState() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        runCurrent()
        assertEquals(SyncUi("Not uploaded", true), vm.state.value.sync)
        vm.onUploadConfirmed()
        runCurrent()
        assertEquals(SyncUi("On vehicle", false), vm.state.value.sync)
        assertFalse(sync.state.value.differsFrom(null))
        vm.onMapClick(b)
        runCurrent()
        assertEquals(SyncUi("Not uploaded", true), vm.state.value.sync)
        assertTrue(sync.state.value.differsFrom(null), "Fly sees it too")
        // MAVProxy uploads 5 items behind our back: MISSION_CURRENT reports a total that isn't what we hold.
        vm.onUndoClicked()
        vehicle.value = vehicle.value.copy(mission = MissionProgress(current = 1, total = 5, lastReached = null, complete = false))
        runCurrent()
        assertEquals(SyncUi("Vehicle mission changed elsewhere", true), vm.state.value.sync)
    }

    @Test
    fun uploadErrorsReachTheOperator() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        missions.failWith = "Home isn't known yet."
        vm.effects.test {
            vm.onUploadConfirmed()
            assertEquals("Uploading failed: Home isn't known yet.", awaitItem())
        }
        assertNull(sync.state.value.onVehicle, "a failed upload doesn't count as on the vehicle")
    }

    @Test
    fun cancellingATransferSaysTheMissionMayBeIncomplete() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        missions.hold = CompletableDeferred()
        vm.effects.test {
            vm.onUploadConfirmed()
            runCurrent()
            vm.onCancelTransferClicked()
            assertContains(awaitItem(), "cancelled")
        }
        runCurrent()
        assertNull(vm.state.value.transfer)
    }

    @Test
    fun readReplacesThePlanWithTheVehiclesMission() = runTest(dispatcher) {
        val vm = viewModel()
        missions.onVehicle = Mission(home, listOf(MissionItem(MissionCommand.WAYPOINT, b, 60.0), MissionItem(MissionCommand.RETURN_TO_LAUNCH)))
        vm.onReadClicked()
        runCurrent()
        assertEquals(listOf("Waypoint", "Return to launch"), vm.state.value.rows.map { it.title })
        assertEquals("read from vehicle", vm.state.value.rows[1].detail)
        assertEquals(SyncUi("On vehicle", false), vm.state.value.sync, "what was just read is what the vehicle holds")
    }

    @Test
    fun theWaypointBeingFlownIsHighlighted() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMapClick(b)
        vm.onRowSelected(0) // select the takeoff, so no marker is "selected"
        vehicle.value = vehicle.value.copy(mission = MissionProgress(current = 3, total = 3, lastReached = 2, complete = false))
        runCurrent()
        assertEquals(listOf(MarkerStyle.HOME, MarkerStyle.WAYPOINT, MarkerStyle.CURRENT), markers(vm).map { it.style })
    }

    @Test
    fun withoutAVehicleYouCanPlanButNotTransfer() = runTest(dispatcher) {
        vehicle.value = VehicleState()
        val vm = viewModel()
        vm.onMapClick(a)
        runCurrent()
        val s = vm.state.value
        assertEquals(2, s.rows.size, "planning offline works; an unknown vehicle gets the copter takeoff")
        assertFalse(s.canUpload || s.canRead || s.canClear)
        assertTrue(s.hint.startsWith("No vehicle"))
    }

    /**
     * Survey on the map: + Survey, four clicks = four corners, the area and corner handles on the map, the survey's
     * lines in the route, and live stats. Dragging a corner moves it and re-plans.
     */
    @Test
    fun drawingASurveyOnTheMap() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddSurveyClicked()
        val corners = listOf(LatLon(-35.3620, 149.1650), LatLon(-35.3620, 149.1680), LatLon(-35.3640, 149.1680), LatLon(-35.3640, 149.1650))
        corners.forEach(vm::onMapClick)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("Waypoints", "Survey"), s.groups.map { it.kind })
        assertTrue(s.groups[1].selected)
        assertEquals(corners, s.overlays.filterIsInstance<MapOverlay.Polygon>().single().corners)
        assertEquals(4, markers(vm).count { it.id.startsWith("corner-") })
        val panel = s.survey!!
        assertNull(panel.error)
        assertTrue(panel.stats.any { it.first == "Photos" }, "${panel.stats}")
        val route = s.overlays.filterIsInstance<MapOverlay.Route>().single().points
        assertTrue(route.size > 10, "the grid preview is in the route: ${route.size} points")

        val before = panel.stats
        vm.onMarkerDragged(cornerId(1, 1), LatLon(-35.3620, 149.1700))
        runCurrent()
        assertEquals(LatLon(-35.3620, 149.1700), vm.state.value.overlays.filterIsInstance<MapOverlay.Polygon>().single().corners[1])
        assertTrue(vm.state.value.survey!!.stats != before, "the grid is re-planned live")
    }

    /** A drag sends many move events but is one undo step; redo puts it back; a new edit clears redo. */
    @Test
    fun undoAndRedo() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        repeat(5) { i -> vm.onMarkerDragged(markerId(0, 1), LatLon(-35.3620 - i * 0.0001, 149.1660)) }
        vm.onMarkerDragFinished()
        vm.onMarkerDragged(markerId(0, 1), LatLon(-35.3630, 149.1660)) // a second drag of the same marker
        vm.onMarkerDragFinished()
        runCurrent()
        vm.onUndoClicked()
        runCurrent()
        assertEquals(LatLon(-35.3624, 149.1660), markers(vm).last().position, "the second drag is its own step")
        assertTrue(vm.state.value.canUndo)
        vm.onUndoClicked()
        runCurrent()
        assertEquals(a, markers(vm).last().position, "one undo reverts the whole drag")
        vm.onUndoClicked()
        runCurrent()
        assertEquals(emptyList(), vm.state.value.rows, "and the next one the click")
        assertFalse(vm.state.value.canUndo)
        vm.onRedoClicked()
        vm.onRedoClicked()
        runCurrent()
        assertTrue(vm.state.value.canRedo, "the second drag is still ahead")
        assertEquals(LatLon(-35.3624, 149.1660), markers(vm).last().position)
        vm.onUndoClicked()
        vm.onMapClick(b)
        runCurrent()
        assertFalse(vm.state.value.canRedo, "a new edit starts a new future")
    }

    /** Switching altitude-first → GSD-first keeps the survey where it was: GSD starts at what the altitude gives. */
    @Test
    fun switchingToGsdFirstKeepsTheSurvey() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddSurveyClicked()
        runCurrent()
        val altitude = vm.state.value.survey!!.settings.altitudeM
        vm.onHeightModeSelected(HeightMode.GSD)
        vm.onSurveyNumberChanged(SurveyField.SIDE_OVERLAP, 150.0) // out of range: ignored
        runCurrent()
        val s = vm.state.value.survey!!.settings
        assertEquals(HeightMode.GSD, s.heightMode)
        assertEquals(altitude, s.camera.altitudeForGsdM(s.gsdCm / 100), 1e-9)
        assertEquals(70.0, s.sideOverlapPct)
    }

    /** Open tells the formats apart by content: here a QGC plan, imported as one waypoint group. */
    @Test
    fun openingAQgcPlanImportsItsItems() = runTest(dispatcher) {
        val vm = viewModel()
        files.toOpen = OpenedFile(
            "field.plan",
            """{"fileType":"Plan","version":1,"mission":{"version":2,"plannedHomePosition":[0,0,0],"items":[
               {"type":"SimpleItem","command":16,"frame":3,"params":[0,0,0,0,-35.362,149.166,40],"autoContinue":true}]}}""",
        )
        vm.effects.test {
            vm.onOpenClicked()
            assertEquals("Imported 1 items from field.plan.", awaitItem())
        }
        assertEquals(listOf("field"), vm.state.value.groups.map { it.name })
        assertEquals(listOf("Waypoint"), vm.state.value.rows.map { it.title })
    }

    /** Save writes our own format, which Open reads back as the same groups. */
    @Test
    fun saveThenOpen() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onAddSurveyClicked()
        listOf(a, b, LatLon(-35.3640, 149.1650)).forEach(vm::onMapClick)
        vm.onSaveClicked()
        runCurrent()
        val saved = files.saved!!
        assertEquals("mission.kftplan", saved.first)
        vm.onGroupDeleted(1)
        files.toOpen = OpenedFile("mine.kftplan", saved.second)
        vm.onOpenClicked()
        runCurrent()
        assertIs<SurveyGroup>(decodePlan(saved.second)[1])
        assertEquals(listOf("Waypoints", "Survey"), vm.state.value.groups.map { it.kind })
    }

    /** A MissionRepository that records uploads and can hold a transfer open or fail it. */
    private class FakeMissions : MissionRepository {
        var uploaded: List<MissionItem> = emptyList()
        var onVehicle = Mission(null, emptyList())
        var hold: CompletableDeferred<Unit>? = null
        var failWith: String? = null

        override suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit): Result<Unit> {
            failWith?.let { return Result.failure(MissionTransferException(it)) }
            onProgress(2, items.size + 1)
            hold?.await()
            uploaded = items
            return Result.success(Unit)
        }

        override suspend fun download(onProgress: (Int, Int) -> Unit) = Result.success(onVehicle)

        override suspend fun clear() = Result.success(Unit)
    }

    private class FakeFiles : PlanFiles {
        var saved: Pair<String, String>? = null
        var toOpen: OpenedFile? = null
        override suspend fun save(suggestedName: String, text: String): String { saved = suggestedName to text; return suggestedName }
        override suspend fun open() = toOpen
    }

    private class MemoryStore : SettingsStore {
        private var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
    }
}
