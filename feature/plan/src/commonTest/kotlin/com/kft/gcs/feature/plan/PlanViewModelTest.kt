package com.kft.gcs.feature.plan

import app.cash.turbine.test
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.Home
import com.kft.gcs.core.vehicle.Mission
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.MissionRepository
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

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** stateIn(WhileSubscribed) only runs while someone collects, so the test collects like the screen does. */
    private fun TestScope.viewModel() = PlanViewModel(vehicle, missions).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    @Test
    fun clickingTheMapBuildsACopterPlanWithHomeAndNumberedMarkers() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMapClick(b)
        runCurrent()
        val s = vm.state.value
        assertEquals(listOf("1  Takeoff", "2  Waypoint", "3  Waypoint"), s.rows.map { "${it.seq}  ${it.title}" })
        val markers = s.overlays.filterIsInstance<MapOverlay.Marker>()
        assertEquals(listOf("H", "2", "3"), markers.map { it.label }, "home is a marker, never a waypoint (S11)")
        assertEquals(MarkerStyle.SELECTED, markers.last().style, "the newest waypoint is selected for editing")
        assertFalse(markers.first().draggable, "home can't be dragged")
        assertEquals(listOf(home.position, a, b), s.overlays.filterIsInstance<MapOverlay.Route>().single().points)
    }

    /** Upload/clear while armed asks first and names the mode; it never blocks (the buttons stay enabled). */
    @Test
    fun armedVehicleMakesUploadAndClearAskFirst() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        runCurrent()
        assertNull(vm.state.value.uploadWarning, "disarmed: upload goes straight through")
        assertEquals("This deletes the mission on the vehicle and empties the editor.", vm.state.value.clearWarning)

        vehicle.value = vehicle.value.copy(armed = true, flightMode = "Auto")
        runCurrent()
        val s = vm.state.value
        assertEquals("Vehicle is ARMED in AUTO: uploading replaces the mission it is flying.", s.uploadWarning)
        assertEquals("Vehicle is ARMED in AUTO: clearing deletes the mission it is flying.", s.clearWarning)
        assertTrue(s.canUpload && s.canClear, "a warning, not a block")
    }

    @Test
    fun draggingAMarkerMovesThatWaypoint() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMarkerDragged(markerId(1), b)
        runCurrent()
        assertEquals(b, vm.state.value.overlays.filterIsInstance<MapOverlay.Marker>().last().position)
        vm.onMarkerDragged(HOME_MARKER, a) // ignored: home comes from the vehicle
        runCurrent()
        assertEquals(home.position, vm.state.value.overlays.filterIsInstance<MapOverlay.Marker>().first().position)
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

    @Test
    fun uploadSendsTheMissionItemsAndReportsProgressThenTheResult() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        missions.hold = CompletableDeferred()
        vm.effects.test {
            vm.onUploadClicked()
            runCurrent()
            assertEquals("Uploading 2 / 3…", vm.state.value.transfer, "progress from the repository")
            assertFalse(vm.state.value.canUpload, "one transfer at a time")
            missions.hold!!.complete(Unit)
            assertContains(awaitItem(), "Uploaded 2 items")
        }
        runCurrent()
        assertNull(vm.state.value.transfer)
        assertEquals(listOf(MissionCommand.TAKEOFF, MissionCommand.WAYPOINT), missions.uploaded.map { it.command })
    }

    @Test
    fun uploadErrorsReachTheOperator() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        missions.failWith = "Home isn't known yet."
        vm.effects.test {
            vm.onUploadClicked()
            assertEquals("Uploading failed: Home isn't known yet.", awaitItem())
        }
    }

    @Test
    fun cancellingATransferSaysTheMissionMayBeIncomplete() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        missions.hold = CompletableDeferred()
        vm.effects.test {
            vm.onUploadClicked()
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
    }

    @Test
    fun theWaypointBeingFlownIsHighlighted() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onMapClick(a)
        vm.onMapClick(b)
        vm.onRowSelected(0) // select the takeoff, so no marker is "selected"
        vehicle.value = vehicle.value.copy(mission = MissionProgress(current = 3, total = 3, lastReached = 2, complete = false))
        runCurrent()
        val styles = vm.state.value.overlays.filterIsInstance<MapOverlay.Marker>().map { it.style }
        assertEquals(listOf(MarkerStyle.HOME, MarkerStyle.WAYPOINT, MarkerStyle.CURRENT), styles)
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
}
