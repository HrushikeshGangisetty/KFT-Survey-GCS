package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import kotlin.test.Test
import com.kft.gcs.core.geo.LatLon
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end checks against a running ArduPilot SITL, over the real TCP transport and the real gateway.
 * Skipped unless `KFT_SITL=host:port` is set, so `./gradlew check` never needs a simulator. Real time on purpose
 * (a simulator can't run on virtual time), which is why these live apart from the unit tests.
 *
 * Run: start SITL (TCP 5760), then `KFT_SITL=127.0.0.1:5760 ./gradlew :core:vehicle:jvmTest --tests '*SitlCheck*'`.
 */
class SitlCheck {
    private val target = System.getenv("KFT_SITL")

    /** Connects, waits for the vehicle and the startup data, runs [block], and always closes the link. */
    private fun withSitl(block: suspend (ConnectionManager, VehicleRepository, MissionRepository) -> Unit) =
        withSitlParams { manager, vehicles, missions, _ -> block(manager, vehicles, missions) }

    private fun withSitlParams(block: suspend (ConnectionManager, VehicleRepository, MissionRepository, ParamRepository) -> Unit) {
        val (host, port) = target?.split(":") ?: return println("KFT_SITL not set: SITL check skipped")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ConnectionManager(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), SerialPorts())
        // Always attempt the KFT login: with no key configured, a dummy one. Stock SITL answers UNSUPPORTED whatever the
        // key (LEGACY_FIRMWARE); a KFT SITL would say DENIED to the dummy, which is the honest answer.
        val key = parseKftKey(KFT_APP_SECRET_HEX) ?: ByteArray(Kft.KEY_BYTES)
        val vehicles = VehicleRepository(scope, manager.frames, manager.state, manager.gateway, key)
        val missions = DefaultMissionRepository(manager.frames, manager.state, vehicles.state, manager.gateway)
        val params = DefaultParamRepository(manager.frames, manager.state, vehicles.state, manager.gateway)
        try {
            runBlocking {
                manager.connect(LinkConfig.TcpClient(host, port.toInt()))
                // SITL sets home ~20 s after boot, once its EKF has a GPS origin.
                withTimeout(60.seconds) { vehicles.state.first { it.connected && it.home != null && it.firmwareVersion != null } }
                block(manager, vehicles, missions, params)
            }
        } finally {
            manager.disconnect()
            scope.cancel()
        }
    }

    /** Pass 12: the login runs first, and on stock ArduPilot it ends LEGACY_FIRMWARE, after which the Pass 6 requests work. */
    @Test
    fun startupRequestsBringVersionAndHome() = withSitl { _, vehicles, _ ->
        val state = vehicles.state.value
        println("SITL: ArduPilot ${state.firmwareVersion}, home ${state.home}, ${state.login?.label}")
        assertNotNull(state.home)
        assertTrue(state.login == KftLoginStatus.LEGACY_FIRMWARE || state.login == KftLoginStatus.AUTHENTICATED, "${state.login}")
    }

    /** Pass 7 acceptance: upload, read back, compare; then clear and read back again. */
    @Test
    fun missionUploadReadBackCompareAndClear() = withSitl { _, vehicles, missions ->
        val home = vehicles.state.value.home!!.position
        val plan = listOf(
            MissionItem(MissionCommand.TAKEOFF, altitudeM = 20.0),
            MissionItem(MissionCommand.DO_CHANGE_SPEED, param1 = 1f, param2 = 8f, param3 = -1f),
            MissionItem(MissionCommand.WAYPOINT, LatLon(home.latitude + 0.001, home.longitude), 30.0),
            MissionItem(MissionCommand.WAYPOINT, LatLon(home.latitude + 0.001, home.longitude + 0.001), 30.0),
            MissionItem(MissionCommand.RETURN_TO_LAUNCH),
        )
        missions.upload(plan) { done, total -> println("upload $done/$total") }.getOrThrow()
        val back = missions.download().getOrThrow()
        println("read back: home ${back.home}")
        back.items.forEach { println("  $it") }
        assertEquals(plan.size, back.items.size)
        plan.zip(back.items).forEach { (sent, read) ->
            assertEquals(sent.command, read.command)
            // Only commands that store a location keep their frame: ArduPilot returns GLOBAL for the rest
            // (AP_Mission `mission_cmd_to_mavlink_int` sets the frame inside `if (stored_in_location(...))`).
            if (sent.command == MissionCommand.WAYPOINT || sent.command == MissionCommand.TAKEOFF) assertEquals(sent.frame, read.frame)
            assertEquals(sent.altitudeM, read.altitudeM, 0.01)
            // ArduPilot stores lat/lon as 1e-7 degree integers, exactly what we sent.
            assertEquals(sent.position?.latE7, read.position?.latE7)
            assertEquals(sent.position?.lonE7, read.position?.lonE7)
            assertEquals(sent.param2, read.param2)
        }
        assertEquals(vehicles.state.value.home!!.position, back.home!!.position)

        missions.clear().getOrThrow()
        assertEquals(emptyList(), missions.download().getOrThrow().items)
    }

    /**
     * Pass 18 acceptance: download every parameter, set CAM1_TYPE, read it back with a fresh download, then see the
     * gateway refuse a set while the vehicle is armed. Arming is the pilot's (S9): `sitl_pilot.py --arm-only` does it
     * on SITL port 5763, as MAVProxy would. Copter only (Plane arms too, but its check isn't needed twice).
     */
    @Test
    fun paramsDownloadSetReadBackAndArmedRefusal() = withSitlParams { _, vehicles, _, params ->
        var lastPrint = 0
        val all = params.downloadAll { done, total -> if (done - lastPrint >= 200 || done == total) { lastPrint = done; println("params $done/$total") } }.getOrThrow()
        println("downloaded ${all.size} parameters")
        assertTrue(all.size > 500, "a real parameter list, not a handful")
        assertEquals((0 until all.size).toList(), all.map { it.index }, "every index, none twice")
        val cam = all.single { it.name == "CAM1_TYPE" }
        println("CAM1_TYPE = ${cam.valueText} (${cam.type})")

        // Any value but the current one; 0 disables the camera, so toggle between Servo (1) and Relay (2).
        val newValue = if (cam.value == 1f) 2f else 1f
        val set = params.set(cam, newValue)
        println("set CAM1_TYPE ${cam.valueText} -> ${formatParamValue(newValue, cam.type)}: $set")
        assertTrue(set is ParamSetResult.Applied, "$set")
        assertEquals(newValue, params.downloadAll().getOrThrow().single { it.name == "CAM1_TYPE" }.value, "read back")
        assertTrue(params.set(cam.copy(value = newValue), cam.value) is ParamSetResult.Applied, "restored")

        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "tools/sitl").isDirectory }
        val host = this.target!!.substringBefore(':')
        val pilot = ProcessBuilder("py", "-3.9", java.io.File(root, "tools/sitl/sitl_pilot.py").path, "tcp:$host:5763", "--arm-only")
            .inheritIO().start()
        withTimeout(60.seconds) { vehicles.state.first { it.armed } }
        val refused = params.set(cam, newValue)
        println("while armed: $refused")
        assertTrue(refused is ParamSetResult.NotApplied && refused.reason.contains("disarmed"), "$refused")
        pilot.waitFor()
        // Copter disarms itself on the ground (DISARM_DELAY); wait so the next check starts disarmed.
        withTimeout(30.seconds) { vehicles.state.first { !it.armed } }
        assertEquals(cam.value, params.downloadAll().getOrThrow().single { it.name == "CAM1_TYPE" }.value, "unchanged by the refused set")
    }
}
