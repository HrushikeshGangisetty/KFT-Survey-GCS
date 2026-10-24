package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.MissionSetCurrent
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavType
import com.kft.gcs.core.mavlink.signing.FakeKeyring
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.mavlink.signing.trailerOf
import com.kft.gcs.core.mavlink.signing.verifyV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        return ConnectionManager(backgroundScope, { queue.removeFirst().opened() }, testScheduler.timeSource, MutableStateFlow(PodStatus.NoPod))
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
        val setCurrent = MissionSetCurrent(targetSystem = 1u, seq = 3u)
        val sender = manager.gateway.forVehicle(1u)
        assertIs<TxResult.Rejected>(sender.send(setCurrent), "no vehicle heard yet: fail closed")

        link.receive(copterHeartbeat(armed = false), seq = 0)
        runCurrent()
        assertEquals(TxResult.Sent, sender.send(setCurrent))

        link.receive(copterHeartbeat(armed = true), seq = 1)
        runCurrent()
        assertIs<TxResult.Rejected>(sender.send(setCurrent))
    }

    private fun ConnectionManager.vehicles() = (state.value as LinkState.Connected).vehicles

    /** ADR-005: one link, two system ids = two vehicles, each with its own armed flag, view and timeout. */
    @Test
    fun aLinkCarriesSeveralVehiclesEachWithItsOwnState() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        link.receive(copterHeartbeat(armed = true), seq = 0, systemId = 2u)
        link.receive(copterHeartbeat(armed = false), seq = 0, systemId = 1u)
        runCurrent()
        assertEquals(listOf<UByte>(1u, 2u), manager.vehicles().map { it.systemId }, "by system id, not by arrival")
        assertEquals(listOf(false, true), manager.vehicles().map { it.armed })

        // Each vehicle's view of the link has that vehicle as `vehicle`; a system that isn't there has none.
        assertEquals(2u.toUByte(), (manager.state.value.forVehicle(2u) as LinkState.Connected).vehicle?.systemId)
        assertNull((manager.state.value.forVehicle(3u) as LinkState.Connected).vehicle)

        // The gateway judges each by its own flag: vehicle 2 is armed, vehicle 1 isn't.
        assertEquals(TxResult.Sent, manager.gateway.forVehicle(1u).send(MissionSetCurrent(targetSystem = 1u, seq = 1u)))
        assertIs<TxResult.Rejected>(manager.gateway.forVehicle(2u).send(MissionSetCurrent(targetSystem = 2u, seq = 1u)))

        // Only vehicle 1 keeps sending: after the timeout vehicle 2 is gone and vehicle 1 stays.
        repeat(5) { i ->
            advanceTimeBy(1.seconds)
            link.receive(copterHeartbeat(), seq = i + 1, systemId = 1u)
        }
        runCurrent()
        assertEquals(listOf<UByte>(1u), manager.vehicles().map { it.systemId })
    }

    /**
     * ADR-005 §4. Two autopilots with system id 1, half a second apart: heartbeats at t = 0, 0.5, 1.0, … The 16th
     * arrives at t = 7.5 s, which is when the id is marked duplicate (15 at t = 7.0 is still below DUPLICATE_BEATS).
     */
    @Test
    fun twoVehiclesWithOneSystemIdAreAnErrorNotAMerge() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        var seq = 0
        fun heartbeatsEveryHalfSecond(count: Int) = repeat(count) {
            link.receive(copterHeartbeat(), seq = seq++)
            runCurrent()
            advanceTimeBy(500.milliseconds)
        }
        heartbeatsEveryHalfSecond(15) // t = 0 .. 7.0 s
        assertEquals(listOf(false), manager.vehicles().map { it.duplicateId })
        heartbeatsEveryHalfSecond(1) // the 16th, at t = 7.5 s
        assertEquals(listOf(true), manager.vehicles().map { it.duplicateId })
        val refused = assertIs<TxResult.Rejected>(manager.gateway.forVehicle(1u).send(MissionCount(targetSystem = 1u, count = 1u)))
        assertTrue(refused.reason.contains("two vehicles"), refused.reason)

        // One of them is switched off: 1 Hz again. Once the 10 s window holds one sender's heartbeats, it clears.
        repeat(12) {
            link.receive(copterHeartbeat(), seq = seq++)
            runCurrent()
            advanceTimeBy(1.seconds)
        }
        assertEquals(listOf(false), manager.vehicles().map { it.duplicateId })
        assertEquals(TxResult.Sent, manager.gateway.forVehicle(1u).send(MissionCount(targetSystem = 1u, count = 1u)))
    }

    /** One vehicle at ArduPilot's 1 Hz is never a duplicate, however long it runs: at most 11 heartbeats in any 10 s. */
    @Test
    fun oneVehicleAtOneHertzIsNeverADuplicate() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        repeat(40) { i ->
            link.receive(copterHeartbeat(), seq = i)
            runCurrent()
            assertEquals(listOf(false), manager.vehicles().map { it.duplicateId }, "at $i s")
            advanceTimeBy(1.seconds)
        }
    }

    /** Links: numbered in opening order, the same config never opened twice, a number never reused. */
    @Test
    fun linksAreNumberedAndNeverOpenedTwice() = runTest {
        val made = mutableListOf<Int>()
        val links = Links { id ->
            made += id
            ConnectionManager(backgroundScope, { FakeMavConnection().opened() }, testScheduler.timeSource, MutableStateFlow(PodStatus.NoPod), id)
        }
        val tcp = LinkConfig.TcpClient("127.0.0.1", 5762)
        links.connect(udp)
        links.connect(tcp)
        links.connect(udp) // already open
        runCurrent()
        assertEquals(listOf(1, 2), links.open.value.map { it.id })
        assertEquals(listOf<LinkConfig?>(udp, tcp), links.open.value.map { it.config })

        links.disconnect(1)
        links.connect(udp)
        assertEquals(listOf(2, 3), links.open.value.map { it.id }, "1 is not reused: a VehicleId must never change meaning")
        assertEquals(listOf(1, 2, 3), made)
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

    // ---- signing S3 ----

    private val keyA = ByteArray(32) { 0x11 }
    private val keyB = ByteArray(32) { 0x22 }

    /** The vehicle's signing status rides on its VehicleInfo, merged by the one collector: on its heartbeat, and at each tick. */
    @Test
    fun vehicleInfoCarriesTheSigningStatus() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        link.receive(copterHeartbeat(), seq = 0)
        runCurrent()
        assertEquals(listOf<SigningStatus>(SigningStatus.Unsigned), manager.vehicles().map { it.signing }, "the default")

        // The signing layer sets the status before it passes the heartbeat on, as SigningSource does.
        link.signing.value = mapOf(1u.toUByte() to SigningStatus.Signed("drone-a"))
        link.receive(copterHeartbeat(), seq = 1)
        runCurrent()
        assertEquals(listOf<SigningStatus>(SigningStatus.Signed("drone-a")), manager.vehicles().map { it.signing })

        // A bad heartbeat is dropped, so no frame carries the change: the next tick picks it up.
        link.signing.value = mapOf(1u.toUByte() to SigningStatus.BadSignature("drone-a", 1))
        advanceTimeBy(1.seconds + 1.milliseconds)
        assertEquals(listOf<SigningStatus>(SigningStatus.BadSignature("drone-a", 1)), manager.vehicles().map { it.signing })
    }

    /**
     * A vehicle that signs but has no key here (or the keys are locked) never gets a heartbeat through, so it is not in
     * `vehicles`. The link state still lists it, so the Links screen can say so; once it is readable it moves to `vehicles`.
     */
    @Test
    fun signingVehiclesWeCannotReadAreListedAsBlocked() = runTest {
        val link = FakeMavConnection()
        val manager = manager(link)
        manager.connect(udp)
        runCurrent()
        link.signing.value = mapOf(5u.toUByte() to SigningStatus.Locked, 6u.toUByte() to SigningStatus.NoKey)
        advanceTimeBy(1.seconds + 1.milliseconds)

        val blocked = assertIs<LinkState.Connected>(manager.state.value).signingBlocked
        assertEquals(mapOf(5u.toUByte() to SigningStatus.Locked, 6u.toUByte() to SigningStatus.NoKey), blocked)
        assertTrue(manager.vehicles().isEmpty())

        // The operator unlocks: the vehicle's next signed heartbeat verifies and binds.
        link.signing.value = mapOf(5u.toUByte() to SigningStatus.Signed("drone-a"))
        link.receive(copterHeartbeat(), seq = 0)
        advanceTimeBy(1.seconds + 1.milliseconds)
        assertTrue(assertIs<LinkState.Connected>(manager.state.value).signingBlocked.isEmpty())
    }

    /**
     * Bindings belong to one link session: after a reconnect the new session's signing state is used, whatever it is
     * (the same system id may now be another drone). The old session's clock is saved first, so the new one starts above it.
     */
    @Test
    fun aReconnectRebindsAndCarriesTheSigningClockOver() = runTest {
        val keyring = FakeKeyring(mapOf("drone-a" to keyA, "drone-b" to keyB))
        val first = FakeMavConnection(keyring = keyring)
        val second = FakeMavConnection(keyring = keyring)
        val manager = manager(first, second)
        first.signing.value = mapOf(1u.toUByte() to SigningStatus.Signed("drone-a"))
        manager.connect(udp)
        runCurrent()
        assertTrue(verifyV2(first.rawFrames.single(), keyA), "the GCS heartbeat is signed for drone A")

        first.fail()
        runCurrent()
        val savedA = assertNotNull(keyring.timestamps["drone-a"], "saved when the link went down")
        assertTrue(savedA > 0, "one frame was signed, so the clock moved past 0")

        advanceTimeBy(1.seconds + 1.milliseconds) // backoff, then the second link opens
        assertIs<LinkState.Connected>(manager.state.value)
        assertFalse(second.rawFrames.single().let { it[2].toInt() and 1 != 0 }, "new session, nothing bound yet: unsigned")

        // System 1 is now drone B; drone A turns up as system 2.
        second.signing.value = mapOf(1u.toUByte() to SigningStatus.Signed("drone-b"), 2u.toUByte() to SigningStatus.Signed("drone-a"))
        assertEquals(TxResult.Sent, manager.gateway.forVehicle(1u).send(ParamRequestList(targetSystem = 1u)))
        assertTrue(verifyV2(second.rawFrames.last(), keyB), "system 1 is signed for drone B now")
        assertEquals(TxResult.Sent, manager.gateway.forVehicle(2u).send(ParamRequestList(targetSystem = 2u)))
        val toA = second.rawFrames.last()
        assertTrue(verifyV2(toA, keyA))
        // The fake wall clock is 0 in both sessions, so only the saved value can put drone A's clock above the last
        // timestamp it was sent; without it the FC would drop this frame as a replay.
        assertTrue(trailerOf(toA)!!.timestamp > trailerOf(first.rawFrames.single())!!.timestamp, "the clock carried over")
    }

    /** PRD "Timestamps": each bound drone's clock is saved every 10 s (every 10th 1 Hz heartbeat) and on disconnect. */
    @Test
    fun signingClocksAreSavedEvery10SecondsAndOnDisconnect() = runTest {
        val keyring = FakeKeyring(mapOf("drone-a" to keyA))
        val link = FakeMavConnection(keyring = keyring)
        val manager = manager(link)
        link.signing.value = mapOf(1u.toUByte() to SigningStatus.Signed("drone-a"))
        manager.connect(udp)
        runCurrent()

        // Heartbeats at t = 0, 1, … 9 s: the 10th, at 9 s, saves. The clock starts at 0 (fake wall clock) and each
        // signed heartbeat takes one tick, so after 10 of them the clock to save is 10.
        advanceTimeBy(8.seconds + 500.milliseconds)
        assertEquals(null, keyring.timestamps["drone-a"], "not before the 10th heartbeat")
        advanceTimeBy(1.seconds)
        assertEquals(10L, keyring.timestamps["drone-a"])
        advanceTimeBy(10.seconds)
        assertEquals(20L, keyring.timestamps["drone-a"])

        advanceTimeBy(1.seconds) // heartbeat 21 goes out at t = 20 s
        manager.disconnect()
        runCurrent()
        assertEquals(21L, keyring.timestamps["drone-a"], "and once more as the link closes")
    }
}
