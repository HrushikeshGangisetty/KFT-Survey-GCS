package com.kft.gcs.feature.fly

import app.cash.turbine.test
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.GpsFix
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.map.MapOverlay
import com.kft.gcs.ui.map.TileSources
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val sync = MissionSync()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    /** A real VehicleRepository, driven by scripted link state and frames instead of a socket. */
    private fun TestScope.viewModel(): FlyViewModel {
        val repo = VehicleRepository(backgroundScope, frames, link, SilentSender, loginKey = null, testScheduler.timeSource)
        return FlyViewModel(repo, listOf(TileSources.Street, TileSources.Satellite), sync).also {
            link.value = LinkState.Connected(LinkConfig.UdpListen(), copter, LinkStats())
            runCurrent()
        }
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
        val hud = hudItems(
            VehicleState(
                armed = true, flightMode = "Loiter", altitudeRelativeM = 12.345, groundspeedMs = 4.96,
                headingDeg = 359.6, gpsFix = GpsFix.FIX_3D, satellites = 14, batteryVolts = 12.61, batteryPercent = 15,
            ),
        ).associateBy { it.label }
        assertEquals("12.3 m", hud.getValue("Alt").value)
        assertEquals("5.0 m/s", hud.getValue("Speed").value)
        assertEquals("0°", hud.getValue("Heading").value, "359.6° rounds to 360°, which is north: 0°")
        assertEquals("3D · 14 sats", hud.getValue("GPS").value)
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
        sync.vehicleHolds(listOf(wp))
        runCurrent()
        assertEquals(null, vm.state.value.missionWarning)
        sync.planChanged(listOf(wp, wp.copy(altitudeM = 40.0)), 0)
        runCurrent()
        assertEquals("Vehicle mission ≠ plan", vm.state.value.missionWarning)
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
