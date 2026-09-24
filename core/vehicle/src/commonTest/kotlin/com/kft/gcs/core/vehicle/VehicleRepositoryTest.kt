package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavDataStream
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.VfrHud
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class VehicleRepositoryTest {
    private val udp = LinkConfig.UdpListen()
    private val copter = VehicleInfo(systemId = 1u, componentId = 1u, kind = VehicleKind.COPTER, armed = true, customMode = 5u)

    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    private val frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 16)
    private val requests = mutableListOf<RequestDataStream>()

    private fun TestScope.repository() =
        VehicleRepository(backgroundScope, frames, link) { requests += it; TxResult.Sent }.also { runCurrent() }

    private fun frame(message: MavMessage<*>, componentId: UByte = 1u) =
        check(frames.tryEmit(TestFrame(systemId = 1u, componentId = componentId, message = message)))

    @Test
    fun requestsStreamsOncePerVehicleAppearance() = runTest {
        repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        link.value = LinkState.Connected(udp, copter, LinkStats(messagesPerSecond = 5)) // a stats tick, same vehicle
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(MavDataStream.ALL.value, requests.single().reqStreamId.value)
        assertEquals(copter.systemId, requests.single().targetSystem)

        // Heartbeat lost, then back (for example the autopilot rebooted): ask again.
        link.value = LinkState.Connected(udp, null, LinkStats())
        runCurrent()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        assertEquals(2, requests.size)
    }

    @Test
    fun modeAndArmedComeFromTheHeartbeat() = runTest {
        val repo = repository()
        link.value = LinkState.Connected(udp, copter, LinkStats())
        runCurrent()
        assertEquals("Loiter", repo.state.value.flightMode)
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

    private data class TestFrame(
        override val systemId: UByte,
        override val componentId: UByte,
        override val message: MavMessage<*>,
        override val sequence: UByte = 0u,
        override val checksum: UShort = 0u,
    ) : MavFrame<MavMessage<*>>
}
