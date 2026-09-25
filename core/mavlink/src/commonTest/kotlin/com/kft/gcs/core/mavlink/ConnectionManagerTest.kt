package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.definitions.common.MissionSetCurrent
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException

class ConnectionManagerTest {

    private val udp = LinkConfig.UdpListen()

    /** A manager on virtual time whose links come from [links], in order. */
    private fun TestScope.manager(vararg links: FakeMavConnection): ConnectionManager {
        val queue = ArrayDeque(links.toList())
        return ConnectionManager(backgroundScope, { queue.removeFirst() }, testScheduler.timeSource, MutableStateFlow(PodStatus.NoPod))
    }

    @Test
    fun connectsSendsGcsHeartbeatAt1HzAndDetectsTheVehicle() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)

        manager.connect(udp)
        runCurrent()
        assertEquals(LinkState.Connected(udp, vehicle = null, stats = LinkStats()), manager.state.value)

        link.receive(copterHeartbeat(armed = true, customMode = 5u), seq = 0)
        runCurrent()
        val vehicle = assertNotNull((manager.state.value as LinkState.Connected).vehicle)
        assertEquals(VehicleKind.COPTER, vehicle.kind)
        assertTrue(vehicle.armed)
        assertEquals(5u, vehicle.customMode)

        // One heartbeat at t=0, then one per second: t=1, t=2 -> 3 by t=2.5 s.
        advanceTimeBy(2500.milliseconds)
        val heartbeats = link.sent.filterIsInstance<Heartbeat>()
        assertEquals(3, heartbeats.size)
        assertEquals(MavType.GCS.value, heartbeats.first().type.value)
    }

    /** The gateway's armed flag is the one from the vehicle's heartbeat, not a copy that could go stale. */
    @Test
    fun gatewayUsesTheHeartbeatArmedFlag() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        val setCurrent = MissionSetCurrent(seq = 3u)
        assertIs<TxResult.Rejected>(manager.gateway.send(setCurrent), "no vehicle heard yet: fail closed")

        link.receive(copterHeartbeat(armed = false), seq = 0)
        runCurrent()
        assertEquals(TxResult.Sent, manager.gateway.send(setCurrent))

        link.receive(copterHeartbeat(armed = true), seq = 1)
        runCurrent()
        assertIs<TxResult.Rejected>(manager.gateway.send(setCurrent))
    }

    @Test
    fun vehicleIsDroppedAfterHeartbeatTimeout() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        link.receive(copterHeartbeat(), seq = 0)
        runCurrent()

        // Silent for 3 s: still within the timeout. At the 4 s tick it's past 3 s, so the vehicle is gone.
        advanceTimeBy(3.seconds + 1.milliseconds)
        assertNotNull((manager.state.value as LinkState.Connected).vehicle)
        advanceTimeBy(1.seconds)
        assertNull((manager.state.value as LinkState.Connected).vehicle)
    }

    @Test
    fun reconnectsAfterTheLinkFailsWithBackoff() = runTest {
        val first = FakeMavConnection()
        val refused = FakeMavConnection(failOnConnect = IOException("connection refused"))
        val third = FakeMavConnection()
        val manager = manager(first, refused, third)

        manager.connect(udp)
        runCurrent()
        first.fail()
        runCurrent()
        assertTrue(first.closed, "a failed link is closed so its reader thread stops")
        assertEquals(LinkState.Connecting(udp, attempt = 2, lastError = "cable pulled"), manager.state.value)

        // First retry after 1 s (it's refused), the next after a further 2 s (backoff 1, 2, 4, 5 s).
        advanceTimeBy(1.seconds + 1.milliseconds)
        assertEquals(LinkState.Connecting(udp, attempt = 3, lastError = "connection refused"), manager.state.value)
        advanceTimeBy(2.seconds)
        assertIs<LinkState.Connected>(manager.state.value)
    }

    @Test
    fun disconnectClosesTheLinkAndStopsRetrying() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link) // only one link: a retry would throw on the empty queue
        manager.connect(udp)
        runCurrent()

        manager.disconnect()
        runCurrent()
        assertTrue(link.closed)
        assertEquals(LinkState.Disconnected, manager.state.value)
        advanceTimeBy(30.seconds)
        assertEquals(LinkState.Disconnected, manager.state.value)
    }

    @Test
    fun backoffDoublesThenCapsAt5Seconds() {
        assertEquals(listOf(1, 2, 4, 5, 5).map { it.seconds }, (1..5).map { ConnectionManager.backoff(it) })
    }

    @Test
    fun mavTypeMapsToFirmwareFamily() {
        assertEquals(VehicleKind.COPTER, ConnectionManager.vehicleKindOf(MavType.HEXAROTOR.value))
        assertEquals(VehicleKind.PLANE, ConnectionManager.vehicleKindOf(MavType.FIXED_WING.value))
        // A QuadPlane reports a VTOL type but runs ArduPlane.
        assertEquals(VehicleKind.PLANE, ConnectionManager.vehicleKindOf(MavType.VTOL_TILTROTOR.value))
        assertEquals(VehicleKind.UNKNOWN, ConnectionManager.vehicleKindOf(MavType.GROUND_ROVER.value))
    }
}
