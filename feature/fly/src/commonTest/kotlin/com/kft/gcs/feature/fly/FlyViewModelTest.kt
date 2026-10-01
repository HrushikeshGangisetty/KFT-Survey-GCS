package com.kft.gcs.feature.fly

import app.cash.turbine.test
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CameraFeedback
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.GpsFix
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.vehicleLabels
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.TileSourceConfig
import com.kft.gcs.ui.map.TileSources
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class FlyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    private val frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 16)
    private val copter = VehicleInfo(1u, 1u, VehicleKind.COPTER, armed = false, customMode = 5u)
    private val home = LatLon(-35.363261, 149.165230)
    private val drone = VehicleId(link = 1, systemId = 1u)
    private val selectedId = MutableStateFlow<VehicleId?>(drone)
    private val sync = MissionSync().apply { select(drone) }

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val basemaps = MutableStateFlow(listOf(TileSources.Street, TileSources.Satellite))

    /** One vehicle: a real VehicleRepository, driven by scripted link state and frames instead of a socket. */
    private fun TestScope.viewModel(): FlyViewModel {
        val repo = VehicleRepository(backgroundScope, frames, link, SilentSender, loginKey = null, testScheduler.timeSource)
        val vehicles = repo.state.map { listOf(FleetVehicle(drone, slot = 0, it)) }.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        return FlyViewModel(vehicles, selectedId, { selectedId.value = it }, basemaps, sync).also {
            link.value = LinkState.Connected(LinkConfig.UdpListen(), copter, LinkStats())
            runCurrent()
        }
    }

    // ---- several vehicles (ADR-005): states set by hand, as the fleet would report them ----

    private val north = LatLon(home.latitude + 0.001, home.longitude)
    private val drone2 = VehicleId(link = 1, systemId = 2u)
    private val fleet = MutableStateFlow(
        listOf(
            FleetVehicle(drone, slot = 0, VehicleState(connected = true, position = home, headingDeg = 90.0, altitudeRelativeM = 10.0)),
            FleetVehicle(drone2, slot = 1, VehicleState(connected = true, position = north, headingDeg = 180.0, altitudeRelativeM = 25.0)),
        ),
    )

    private fun TestScope.fleetViewModel() = FlyViewModel(fleet, selectedId, { selectedId.value = it }, basemaps, sync).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    @Test
    fun everyVehicleIsOnTheMapInItsOwnColourWithItsLabel() = runTest(dispatcher) {
        val vm = fleetViewModel()
        assertEquals(
            listOf(
                MapOverlay.Vehicle(home, 90.0, slot = 0, label = "1", selected = true),
                MapOverlay.Vehicle(north, 180.0, slot = 1, label = "2", selected = false),
            ),
            vm.state.value.overlays.filterIsInstance<MapOverlay.Vehicle>(),
        )
        assertEquals(
            listOf(VehicleChipUi(drone, "1", 0, selected = true, connected = true), VehicleChipUi(drone2, "2", 1, selected = false, connected = true)),
            vm.state.value.vehicles,
        )
        // One track each: both start at their vehicle's own position.
        assertEquals(listOf(listOf(home), listOf(north)), vm.state.value.overlays.filterIsInstance<MapOverlay.Track>().map { it.points })
    }

    @Test
    fun theHudFollowsTheSelectedVehicle() = runTest(dispatcher) {
        val vm = fleetViewModel()
        fun alt() = vm.state.value.hud.single { it.label == "Alt" }.value
        assertEquals("10.0 m", alt())

        vm.onVehicleSelected(drone2)
        runCurrent()
        assertEquals("25.0 m", alt())
        assertEquals(listOf(false, true), vm.state.value.vehicles.map { it.selected })
        assertEquals(listOf(false, true), vm.state.value.overlays.filterIsInstance<MapOverlay.Vehicle>().map { it.selected })

        vm.onCenterClicked()
        runCurrent()
        assertEquals(north, vm.state.value.cameraRequest?.target, "centre goes to the selected vehicle")
    }

    /** ADR-005 §4: a duplicated id is two aircraft answering in turn, so it has no arrow and no track, and says why. */
    @Test
    fun aDuplicatedSystemIdIsNotDrawnAndIsExplained() = runTest(dispatcher) {
        fleet.value = fleet.value.map { if (it.id == drone) it.copy(duplicateId = true) else it }
        val vm = fleetViewModel()
        assertEquals(listOf("2"), vm.state.value.overlays.filterIsInstance<MapOverlay.Vehicle>().map { it.label })
        assertEquals("Two vehicles are using system id 1: nothing can be sent", vm.state.value.vehicleProblem)
        assertEquals(listOf(emptyList(), listOf(north)), vm.state.value.overlays.filterIsInstance<MapOverlay.Track>().map { it.points })
    }

    /** Labels are the system id; two links that both carry a system 1 get the link number too. */
    @Test
    fun labelsNeverCollide() {
        assertEquals(mapOf(drone to "1", drone2 to "2"), vehicleLabels(listOf(drone, drone2)))
        val otherLink = VehicleId(link = 2, systemId = 1u)
        assertEquals(mapOf(drone to "L1·1", otherLink to "L2·1", drone2 to "2"), vehicleLabels(listOf(drone, otherLink, drone2)))
    }

    private fun position(p: LatLon) = check(frames.tryEmit(Frame(GlobalPositionInt(lat = p.latE7, lon = p.lonE7, hdg = 9000u))))

    /** S12: the HUD shows the KFT login. This repository has no key, which is a warning the operator must fix. */
    @Test
    fun hudShowsTheKftLogin() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.test {
            runCurrent()
            assertEquals(LoginUi("KFT login: no key configured", warning = true), expectMostRecentItem().login)
        }
    }

    @Test
    fun firstPositionCentresTheCameraOnceThenLeavesItAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.test {
            skipItems(1)
            position(home)
            runCurrent()
            val first = expectMostRecentItem()
            assertEquals(home, first.cameraRequest?.target)
            assertEquals(MapOverlay.Vehicle(home, 90.0), first.overlays.last())

            // 0.001° further north is ~111 m (1° of latitude = 111 195 m): the vehicle moves, the camera doesn't.
            position(LatLon(home.latitude + 0.001, home.longitude))
            runCurrent()
            assertEquals(first.cameraRequest, expectMostRecentItem().cameraRequest)

            vm.onCenterClicked()
            runCurrent()
            val centred = expectMostRecentItem().cameraRequest!!
            assertEquals(home.latitude + 0.001, centred.target.latitude, 1e-9)
            assertTrue(centred.id > first.cameraRequest!!.id, "a new request id makes the map move even to a similar spot")
        }
    }

    @Test
    fun trackGrowsWithMovementAndClears() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.test {
            skipItems(1)
            position(home)
            runCurrent()
            position(LatLon(home.latitude + 0.001, home.longitude))
            runCurrent()
            assertEquals(2, (expectMostRecentItem().overlays.first() as MapOverlay.Track).points.size)
            vm.onClearTrackClicked()
            runCurrent()
            assertEquals(0, (expectMostRecentItem().overlays.first() as MapOverlay.Track).points.size)
        }
    }

    @Test
    fun basemapSelection() = runTest(dispatcher) {
        val vm = viewModel()
        vm.state.test {
            assertEquals(TileSources.Street, awaitItem().selectedBasemap)
            vm.onBasemapSelected(TileSources.Satellite.id)
            assertEquals(TileSources.Satellite, awaitItem().selectedBasemap)
            vm.onBasemapSelected("no-such-map") // ignored
            expectNoEvents()
        }
    }

    /** An MBTiles file imported on the Maps tab shows up in the menu; deleting it while shown falls back to Street. */
    @Test
    fun importedBasemapComesAndGoes() = runTest(dispatcher) {
        val imported = TileSourceConfig("mbtiles:farm.mbtiles", "farm", TileSourceConfig.Source.Mbtiles("/maps/farm.mbtiles", 1000), "Imported")
        val vm = viewModel()
        vm.state.test {
            assertEquals(2, awaitItem().basemaps.size)
            basemaps.value = basemaps.value + imported
            assertEquals(imported, awaitItem().basemaps.last())
            vm.onBasemapSelected(imported.id)
            assertEquals(imported, awaitItem().selectedBasemap)
            basemaps.value = basemaps.value - imported
            assertEquals(TileSources.Street, awaitItem().selectedBasemap)
        }
    }

    @Test
    fun missionProgressText() {
        assertEquals("–", missionText(null))
        assertEquals("3 / 5", missionText(MissionProgress(current = 3, total = 5, lastReached = 2, complete = false)))
        assertEquals("3", missionText(MissionProgress(current = 3, total = null, lastReached = null, complete = false)))
        assertEquals("Done", missionText(MissionProgress(current = 5, total = 5, lastReached = 5, complete = true)))
    }

    @Test
    fun hudShowsDashesUntilReportedAndFormatsUnits() {
        assertTrue(hudItems(VehicleState()).filter { it.label in setOf("Alt", "Speed", "Heading", "Battery") }.all { it.value == "–" })
        val v = VehicleState(
            armed = true, flightMode = "Loiter", altitudeRelativeM = 12.345, groundspeedMs = 4.96,
            headingDeg = 359.6, gpsFix = GpsFix.FIX_3D, satellites = 14, batteryVolts = 12.61, batteryPercent = 15,
        )
        val hud = hudItems(v).associateBy { it.label }
        assertEquals("12.3 m", hud.getValue("Alt").value)
        assertEquals("5.0 m/s", hud.getValue("Speed").value)
        assertEquals("0°", hud.getValue("Heading").value, "359.6° rounds to 360°, which is north: 0°")
        assertEquals(HudItem("GPS", "3D · 14 sats", warning = false), gpsItem(v))
        assertTrue(gpsItem(VehicleState(gpsFix = GpsFix.FIX_2D)).warning, "below 3D warns")
        assertEquals("12.6 V · 15%", hud.getValue("Battery").value)
        assertTrue(hud.getValue("Battery").warning, "below 20% warns")
        assertTrue(hud.getValue("State").warning, "armed is highlighted")
    }

    /** The HUD warns when the vehicle holds something other than the plan, and only when that's known. */
    @Test
    fun hudWarnsWhenTheVehicleMissionIsNotThePlan() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        val wp = MissionItem(MissionCommand.WAYPOINT, home, 30.0)
        sync.planChanged(listOf(wp), 0)
        runCurrent()
        assertEquals(null, vm.state.value.missionWarning, "nothing uploaded yet: unknown, no warning")
        sync.vehicleHolds(drone, listOf(wp))
        runCurrent()
        assertEquals(null, vm.state.value.missionWarning)
        sync.planChanged(listOf(wp, wp.copy(altitudeM = 40.0)), 0)
        runCurrent()
        assertEquals("Vehicle mission ≠ plan", vm.state.value.missionWarning)
    }

    /**
     * Two CAMERA_FEEDBACKs out of 11 planned: "Photos 2 / 11" and two dots on the map. Clear track starts the count
     * again from 0 for the next flight, and a third photo counts as 1.
     */
    @Test
    fun photosTakenOutOfPlanned() = runTest(dispatcher) {
        val vm = viewModel()
        backgroundScope.launch { vm.state.collect {} }
        sync.planChanged(emptyList(), plannedPhotos = 11)
        fun photo(i: Int) = check(frames.tryEmit(Frame(CameraFeedback(lat = home.latE7 + i * 100, lng = home.lonE7, imgIdx = i.toUShort()))))
        photo(1); photo(2)
        runCurrent()
        fun photos() = vm.state.value.hud.single { it.label == "Photos" }.value
        assertEquals("2 / 11", photos())
        assertEquals(2, vm.state.value.overlays.filterIsInstance<MapOverlay.Photos>().single().points.size)
        vm.onClearTrackClicked()
        photo(3)
        runCurrent()
        assertEquals("1 / 11", photos())
        assertEquals("1", photosItem(1, 0).value, "no survey planned: just the count")
    }

    @Test
    fun trackSkipsTinyMovesAndKeepsTheNewest() {
        val start = listOf(home)
        // 0.000005° of latitude is about 0.56 m: under the 1 m spacing, so not added.
        assertEquals(start, start.appendTrack(LatLon(home.latitude + 0.000005, home.longitude)))
        val far = LatLon(home.latitude + 0.001, home.longitude)
        assertEquals(listOf(home, far), start.appendTrack(far))
        assertEquals(listOf(far), start.appendTrack(far, maxPoints = 1), "over the cap, the oldest point is dropped")
    }

    private data class Frame(
        override val message: MavMessage<*>,
        override val systemId: UByte = 1u,
        override val componentId: UByte = 1u,
        override val sequence: UByte = 0u,
        override val checksum: UShort = 0u,
    ) : MavFrame<MavMessage<*>>
}

/** Accepts every message and never answers, so the connect-time requests simply wait in the background. */
private object SilentSender : MavSender {
    override suspend fun <T : MavMessage<T>> send(message: T) = TxResult.Sent
}
