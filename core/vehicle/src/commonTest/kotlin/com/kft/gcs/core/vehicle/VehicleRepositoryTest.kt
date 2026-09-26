package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.Home
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.HomePosition
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavDataStream
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.VfrHud
import com.divpundir.mavlink.definitions.standard.AutopilotVersion
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class VehicleRepositoryTest {
    private val udp = LinkConfig.UdpListen()
    private val copter = VehicleInfo(systemId = 1u, componentId = 1u, kind = VehicleKind.COPTER, armed = true, customMode = 5u)

    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)

    // The fake FC ACKs every command, like ArduPilot does for REQUEST_MESSAGE and SET_MESSAGE_INTERVAL.
    private val fc = FakeFc().apply { reply = { m -> if (m is CommandLong) listOf(ackFor(m)) else emptyList() } }
    private val requests get() = fc.sentOf<RequestDataStream>()

    private fun TestScope.repository() =
        // No KFT key: these tests are about the connect-time requests, which then go out at once (KftLoginTest covers the login).
        VehicleRepository(backgroundScope, fc.frames, link, fc, loginKey = null, testScheduler.timeSource).also { runCurrent() }

    private fun frame(message: MavMessage<*>, componentId: UByte = 1u) = fc.emit(message, componentId = componentId)

    @Test
    fun requestsStreamsOncePerSessionAndAfterALongGap() = runTest {
        repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        link.value = LinkState.Connected(udp, copter, LinkStats(messagesPerSecond = 5)) // a stats tick, same vehicle
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(MavDataStream.ALL.value, requests.single().reqStreamId.value)
        assertEquals(copter.systemId, requests.single().targetSystem)

        // Heartbeat lost for 3 s, then back: a short dropout, the vehicle still has our stream rates. Don't ask again.
        link.value = LinkState.Connected(udp, null, LinkStats())
        runCurrent()
        advanceTimeBy(3.seconds)
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        assertEquals(1, requests.size)

        // Lost for longer than RELOGIN_GAP (for example the autopilot rebooted): ask again.
        link.value = LinkState.Connected(udp, null, LinkStats())
        runCurrent()
        advanceTimeBy(7.seconds)
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        assertEquals(2, requests.size)
    }

    @Test
    fun asksForVersionAndHomeWhenTheVehicleAppears() = runTest {
        repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        val commands = fc.sentOf<CommandLong>().map { Triple(it.command.value, it.param1, it.param2) }
        assertEquals(
            listOf(
                Triple(MavCmd.REQUEST_MESSAGE.value, 148f, 0f),               // AUTOPILOT_VERSION
                Triple(MavCmd.REQUEST_MESSAGE.value, 242f, 0f),               // HOME_POSITION
                Triple(MavCmd.SET_MESSAGE_INTERVAL.value, 242f, 10_000_000f), // HOME_POSITION every 10 s, in µs
            ),
            commands,
        )
        assertEquals(148u, AutopilotVersion.id, "message ids from common.xml / standard.xml")
        assertEquals(242u, HomePosition.id)
        assertTrue(fc.sent.first() is RequestDataStream, "telemetry streams are asked for first")
    }

    @Test
    fun homeAndVersionReachTheState() = runTest {
        val repo = repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        frame(HomePosition(latitude = -353632610, longitude = 1491652300, altitude = 584_000))
        frame(AutopilotVersion(flightSwVersion = 0x040603FFu))
        runCurrent()
        assertEquals(Home(LatLon(-35.363261, 149.165230), 584.0), repo.state.value.home)
        assertEquals("4.6.3", repo.state.value.firmwareVersion)
    }

    @Test
    fun modeAndArmedComeFromTheHeartbeat() = runTest {
        val repo = repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        assertEquals("Loiter", repo.state.value.flightMode)
        assertEquals(VehicleKind.COPTER, repo.state.value.vehicleKind)
        assertTrue(repo.state.value.armed)
        assertTrue(repo.state.value.connected)

        // Heartbeat lost: marked disconnected, but the last known mode stays on screen.
        link.value = LinkState.Connected(udp, null, LinkStats())
        runCurrent()
        assertEquals(false, repo.state.value.connected)
        assertEquals("Loiter", repo.state.value.flightMode)
    }

    @Test
    fun onlyTheAutopilotsFramesCount() = runTest {
        val repo = repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        frame(VfrHud(groundspeed = 7.5f))
        frame(VfrHud(groundspeed = 99f), componentId = 154u) // a gimbal on the same system
        runCurrent()
        assertEquals(7.5, repo.state.value.groundspeedMs)
    }

    @Test
    fun disconnectClearsTheState() = runTest {
        val repo = repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        frame(VfrHud(groundspeed = 7.5f))
        runCurrent()
        link.value = LinkState.Disconnected
        runCurrent()
        assertNull(repo.state.value.groundspeedMs)
        assertNull(repo.state.value.flightMode)
    }
}
