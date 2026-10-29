package com.kft.gcs.feature.fly

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.standard.GlobalPositionInt
import com.kft.gcs.core.companion.CompanionConnector
import com.kft.gcs.core.companion.CompanionRepository
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.map.TileSources
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The companion panel against the visionnav P4 scenarios, played in virtual time through a fake WebSocket, and next
 * to a real [VehicleRepository] so the FC side is the real one.
 */
class CompanionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val drone = VehicleId(link = 1, systemId = 1u)
    private val drone2 = VehicleId(link = 1, systemId = 2u)
    private val selectedId = MutableStateFlow<VehicleId?>(drone)
    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
    private val frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 16)

    /** The fake companion server: what is sent here arrives as text; closing it with a cause drops the connection. */
    private val wire = Channel<String>(Channel.UNLIMITED)
    private val connector: CompanionConnector = { _, onConnected, onText ->
        onConnected()
        for (text in wire) onText(text)
    }

    private class Setup(val companion: CompanionViewModel, val fly: FlyViewModel, val repository: CompanionRepository)

    private fun TestScope.setup(): Setup {
        val fc = VehicleRepository(backgroundScope, frames, link, NoReplySender, loginKey = null, testScheduler.timeSource)
        // A second vehicle (no companion) that the operator can select instead.
        val vehicles = fc.state.map { listOf(FleetVehicle(drone, slot = 0, it), FleetVehicle(drone2, slot = 1, VehicleState(connected = true))) }.stateIn(backgroundScope, SharingStarted.Eagerly, emptyList())
        val repository = CompanionRepository(backgroundScope, store = null, connector, testScheduler.timeSource)
        val companion = CompanionViewModel(repository, vehicles, selectedId)
        val fly = FlyViewModel(vehicles, selectedId, { selectedId.value = it }, MutableStateFlow(listOf(TileSources.Street)), MissionSync())
        backgroundScope.launch { companion.state.collect {} }
        backgroundScope.launch { fly.state.collect {} }
        link.value = LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(1u, 1u, VehicleKind.COPTER, armed = false, customMode = 5u), LinkStats())
        companion.onConnectClicked()
        runCurrent()
        return Setup(companion, fly, repository)
    }

    /** Plays 20 Hz odometry from [fromS] to [toS] (scenario seconds) in virtual time, health every 0.5 s. */
    private fun TestScope.play(fromS: Double, toS: Double, health: (Double) -> String? = { t -> health("OK", null, t) }, odometry: (Double) -> String) {
        var k = (fromS * 20).toInt()
        while (k / 20.0 <= toS + 1e-9) {
            val t = k / 20.0
            wire.trySend(odometry(t))
            if (k % 10 == 0) health(t)?.let { wire.trySend(it) }
            runCurrent()
            advanceTimeBy(50.milliseconds)
            k++
        }
        runCurrent()
    }

    @Test
    fun staleScenarioShowsStaleWhileMessagesKeepArriving() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 4.95) { t -> odom(t) }
        assertEquals(Line("VIO", "TRACKING", Status.OK), s.companion.state.value.vio)
        // From 5 s measurement_time freezes but records keep coming, and health keeps saying OK (P4 `stale`).
        play(5.0, 6.0) { t -> odom(t, measurementS = 5.0) }
        val ui = s.companion.state.value
        assertEquals(Line("VIO", "STALE · last said TRACKING", Status.CRITICAL), ui.vio)
        assertEquals(Status.CRITICAL, ui.rows.first { it.label == "Position age" }.status)
        assertEquals(Status.OK, ui.health.single().status, "health still says OK: the panel must not trust it for freshness")
    }

    /** WE-7: health keeps arriving at 2 Hz but the Pi stops advancing its measurement time at 5 s: the line says STALE. */
    @Test
    fun frozenHealthMeasurementTimeShowsStale() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 4.95) { t -> odom(t) }
        assertEquals(Status.OK, s.companion.state.value.health.single().status)
        play(5.0, 7.0, health = { health("OK", null, 5.0) }) { t -> odom(t) }
        val line = s.companion.state.value.health.single()
        assertEquals(Status.WARN, line.status)
        assertTrue(line.value.startsWith("STALE · OK"), line.value)
    }

    /** NH-H3: the bridge's health line says whether the FC logged us in and follows our odometry; "not following" warns. */
    @Test
    fun bridgeHealthShowsAuthAndFcAcceptance() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 1.95, health = { t -> bridgeHealth("DEGRADED", "\"FC not following\"", "AUTHENTICATED", "NOT_FOLLOWING", t) }) { t -> odom(t) }
        val line = s.companion.state.value.health.single()
        assertEquals("fc_bridge", line.label)
        assertTrue("DEGRADED · FC not following · auth AUTHENTICATED · FC NOT FOLLOWING" in line.value, line.value)
        assertEquals(Status.WARN, line.status)
    }

    @Test
    fun lostResetScenarioShowsLostThenTheReset() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 9.95) { t -> odom(t, x = t) }
        play(10.0, 12.95, health = { t -> health("DEGRADED", "\"tracking lost\"", t) }) { t -> odom(t, state = "LOST") }
        var ui = s.companion.state.value
        assertEquals(Line("VIO", "LOST", Status.CRITICAL), ui.vio)
        assertEquals(Status.WARN, ui.health.single().status)
        play(13.0, 13.45, health = { t -> health("DEGRADED", "\"tracking lost\"", t) }) { t -> odom(t, state = "INITIALIZING") }
        assertEquals(Line("VIO", "INITIALIZING", Status.WARN), s.companion.state.value.vio)
        play(13.5, 14.0) { t -> odom(t, x = t - 13.5, reset = 1) }
        ui = s.companion.state.value
        assertEquals(Line("VIO", "TRACKING", Status.OK), ui.vio)
        assertEquals(Line("Resets", "counter 1 · 1 this run", Status.WARN), ui.rows.first { it.label == "Resets" })
        assertEquals(1, ui.currentSegment)
        assertEquals(setOf(0, 1), ui.track.map { it.segment }.toSet(), "two segments, drawn apart")
    }

    /**
     * The confidently wrong fix (40 m north, see CompanionStateTest) is shown as an accepted map fix, with its age.
     * It arrives 50 ms after the last odometry (published at Pi 9.005 s), so Pi now is 9.055 s and its image (Pi 8.5 s)
     * is 0.555 s old, shown as 0.6 s.
     */
    @Test
    fun wrongMapScenarioShowsTheFixAsAMapFix() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 9.0) { t -> odom(t) }
        wire.trySend(mapFix(9.0, lat = 17.400359))
        runCurrent()
        val fix = s.companion.state.value.rows.first { it.label == "Map fix" }
        assertTrue(fix.value.startsWith("Accepted · 17.400359, 78.5 · σ 3.0 m · 0.6 s old"), fix.value)
        assertEquals(Status.NEUTRAL, fix.status)
        // 6 s later with no new fix it is stale (timeout 5 s).
        play(9.05, 15.0) { t -> odom(t) }
        assertEquals(Status.WARN, s.companion.state.value.rows.first { it.label == "Map fix" }.status)
    }

    @Test
    fun malformedAndWrongVersionRecordsAreCountedNotShown() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 1.0) { t -> odom(t, x = t) }
        wire.trySend("""{"type":"local_odometry","msg":{"schema_version":"0.2",""")
        wire.trySend(odom(1.05, x = 50.0).replace("\"0.2\"", "\"0.3\""))
        runCurrent()
        val ui = s.companion.state.value
        assertEquals(Line("Rejected", "2 records · last: schema_version 0.3, expected 0.2", Status.WARN), ui.rejected)
        assertEquals(1.0, ui.track.last().x, "neither bad record moved the track")
    }

    /** Companion data belongs to the vehicle it is configured for; another selected vehicle hides it. */
    @Test
    fun companionDataIsHiddenUnderAnotherVehicle() = runTest(dispatcher) {
        val s = setup()
        play(0.0, 1.0) { t -> odom(t) }
        selectedId.value = drone2
        runCurrent()
        val ui = s.companion.state.value
        assertEquals("This companion is set up for vehicle 1; vehicle 2 is selected.", ui.hiddenReason)
        assertNull(ui.vio)
        assertTrue(ui.rows.isEmpty() && ui.health.isEmpty() && ui.track.isEmpty())
    }

    /**
     * P7: disconnecting the companion server must not affect FC telemetry. The FC's HUD is the same before and after
     * the drop, FC updates keep arriving, and the companion side goes Failed and then STALE.
     */
    @Test
    fun serverDisconnectLeavesFcTelemetryUntouched() = runTest(dispatcher) {
        val s = setup()
        frames.emit(position(altMm = 12_300))
        play(0.0, 2.0) { t -> odom(t) }
        val hudBefore = s.fly.state.value.hud
        assertEquals("12.3 m", hudBefore.first { it.label == "Alt" }.value)

        wire.close(IllegalStateException("connection reset"))
        runCurrent()
        assertEquals(Line("Link", "Failed: connection reset", Status.WARN), s.companion.state.value.connection)
        assertEquals(hudBefore, s.fly.state.value.hud, "the drop changed nothing on the FC side")
        assertTrue(s.fly.state.value.connected)

        frames.emit(position(altMm = 20_000))
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("20.0 m", s.fly.state.value.hud.first { it.label == "Alt" }.value, "FC telemetry still flows")
        assertEquals(Status.CRITICAL, s.companion.state.value.vio!!.status, "1 s without odometry: STALE")
    }

    private fun position(altMm: Int): MavFrame<out MavMessage<*>> =
        Frame(GlobalPositionInt(lat = -353632610, lon = 1491652300, alt = 584_000 + altMm, relativeAlt = altMm, hdg = 0u))

}

private object NoReplySender : MavSender {
    override suspend fun <T : MavMessage<T>> send(message: T) = TxResult.Sent
}

/** A frame from the autopilot (system 1, component 1), as the link would deliver it. */
private data class Frame(
    override val message: MavMessage<*>,
    override val systemId: UByte = 1u,
    override val componentId: UByte = 1u,
    override val sequence: UByte = 0u,
    override val checksum: UShort = 0u,
) : MavFrame<MavMessage<*>>

// Records shaped like the visionnav P4 feed: Pi clock starts at 10^12 ns, published 5 ms after the tick.
private fun ns(s: Double) = 1_000_000_000_000L + (s * 1e9).toLong()

private fun odom(t: Double, measurementS: Double = t, x: Double = 0.0, state: String = "TRACKING", reset: Int = 0): String {
    val known = state == "TRACKING"
    fun vec(vararg v: Double) = if (known) v.joinToString(",", "[", "]") else List(v.size) { "null" }.joinToString(",", "[", "]")
    val cov = List(36) { if (!known) "null" else if (it % 7 == 0) "0.01" else "0.0" }.joinToString(",", "[", "]")
    return """{"type":"local_odometry","msg":{"schema_version":"0.2","source":"synthetic","run_id":"synthetic-test",
        "measurement_time_pi_ns":${ns(measurementS)},"publish_time_pi_ns":${ns(t) + 5_000_000},"frame_id":"odom",
        "child_frame_id":"base_link_frd","position_m":${vec(x, 0.0, 0.0)},"orientation_xyzw":${vec(0.0, 0.0, 0.0, 1.0)},
        "velocity_m_s":${vec(1.0, 0.0, 0.0)},"pose_covariance_6x6":$cov,"pose_covariance_known":$known,"state":"$state",
        "reset_counter":$reset,"last_visual_update_pi_ns":${ns(measurementS)}}}"""
}

private fun health(status: String, reason: String?, t: Double) =
    """{"type":"health","msg":{"schema_version":"0.4","source":"synthetic","run_id":"synthetic-test","component":"vio",
        "status":"$status","reason":$reason,"last_input_age_ms":50.0,"cpu_pct":40.0,"queue_len":0,
        "measurement_time_pi_ns":${ns(t)}}}"""

private fun bridgeHealth(status: String, reason: String?, auth: String, fc: String, t: Double) =
    """{"type":"health","msg":{"schema_version":"0.5","source":"synthetic","run_id":"synthetic-test","component":"fc_bridge",
        "status":"$status","reason":$reason,"last_input_age_ms":50.0,"cpu_pct":40.0,"queue_len":0,
        "measurement_time_pi_ns":${ns(t)},"auth":"$auth","fc_acceptance":"$fc"}}"""

private fun mapFix(t: Double, lat: Double) =
    """{"type":"map_fix","msg":{"schema_version":"0.2","source":"synthetic","run_id":"synthetic-test",
        "query_exposure_time_pi_ns":${ns(t - 0.5)},"lat_deg":$lat,"lon_deg":78.5,"yaw_rad":0.0,"horizontal_sigma_m":3.0,
        "accepted":true,"reject_reason":null,"map_id":"synthetic_map"}}"""
