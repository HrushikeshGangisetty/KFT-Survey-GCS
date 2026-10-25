package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.T12Link
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

    /** Where downloaded logs go in these checks: a fresh temporary folder per run. */
    private val logDir = java.nio.file.Files.createTempDirectory("kft-sitl-logs").toFile()

    /**
     * Opens one TCP link per `host:port` in [targets] through the app's own [Links] and [Fleet], waits until that many
     * vehicles are heard with their startup data, runs [block], and always closes the links.
     */
    private fun withFleet(targets: String?, block: suspend (Fleet) -> Unit) {
        val addresses = targets?.split(",")?.map { it.trim().split(":") } ?: return println("SITL address not set: SITL check skipped")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val links = SerialPorts().let { Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), it, T12Link(scope, Dispatchers.IO, it)) }
        // Always attempt the KFT login: with no key configured, a dummy one. Stock SITL answers UNSUPPORTED whatever the
        // key (LEGACY_FIRMWARE); a KFT SITL would say DENIED to the dummy, which is the honest answer.
        val key = parseKftKey(KFT_APP_SECRET_HEX) ?: ByteArray(Kft.KEY_BYTES)
        val fleet = Fleet(scope, links.open, key, MissionSync(), logStore = LogFiles(logDir, Dispatchers.IO))
        try {
            runBlocking {
                addresses.forEach { (host, port) -> links.connect(LinkConfig.TcpClient(host, port.toInt())) }
                // SITL sets home ~20 s after boot, once its EKF has a GPS origin.
                withTimeout(90.seconds) {
                    fleet.vehicles.first { all ->
                        all.size == addresses.size && all.all { it.state.connected && it.state.home != null && it.state.firmwareVersion != null }
                    }
                }
                block(fleet)
            }
        } finally {
            links.open.value.forEach { links.disconnect(it.id) }
            scope.cancel()
        }
    }

    /** One vehicle at `KFT_SITL`; it is the selected one, so `fleet.selected`, `.missions` and `.params` are its. */
    private fun withSitl(block: suspend (Fleet) -> Unit) = withFleet(target, block)

    /**
     * Pass 29 acceptance (ADR-005), against `start-sitl.ps1 -Count 3`: three links, one copter each.
     * `KFT_SITL_FLEET=127.0.0.1:5762,127.0.0.1:5772,127.0.0.1:5782` (each instance's SERIAL1).
     *
     * 1. Telemetry for all three, each with its own system id and its own home.
     * 2. A different mission to each (vehicle n gets n + 1 waypoints, 222 m apart, north of its own home, at its own
     *    altitude), read back from each: every vehicle holds its own mission and nobody else's.
     * 3. With `KFT_SITL_FLY=1`: `sitl_pilot.py` is the pilot of each (ports 5763, 5773, 5783), all three fly in AUTO
     *    at once, and the GCS sees each one finish its own mission (the last item it reaches is its own item count).
     */
    @Test
    fun threeVehiclesEachGetAndFlyTheirOwnMission() = withFleet(System.getenv("KFT_SITL_FLEET")) { fleet ->
        val all = fleet.vehicles.value
        println("fleet: " + all.joinToString { "link ${it.id.link} system ${it.id.systemId} home ${it.state.home?.position}" })
        assertEquals(all.size, all.map { it.id.systemId }.toSet().size, "start-sitl -Count gives each its own system id")
        assertEquals(all.size, all.map { it.state.home!!.position }.toSet().size, "each has its own home")

        fun planFor(v: FleetVehicle): List<MissionItem> {
            val home = v.state.home!!.position
            // 0.002° of latitude is 222 m: long enough legs that all three are still in AUTO together.
            val waypoints = (1..v.id.systemId.toInt() + 1).map { i -> MissionItem(MissionCommand.WAYPOINT, LatLon(home.latitude + 0.002 * i, home.longitude), 15.0 + 5 * v.id.systemId.toInt()) }
            return listOf(MissionItem(MissionCommand.TAKEOFF, altitudeM = 15.0)) + waypoints + MissionItem(MissionCommand.RETURN_TO_LAUNCH)
        }
        for (v in all) {
            fleet.select(v.id)
            fleet.missions.upload(planFor(v)).getOrThrow()
        }
        // Read back in the other order, so a mission that went to the wrong vehicle can't pass by luck.
        for (v in all.reversed()) {
            fleet.select(v.id)
            val back = fleet.missions.download().getOrThrow()
            val sent = planFor(v)
            println("system ${v.id.systemId}: ${back.items.size} items, home ${back.home?.position}")
            assertEquals(sent.size, back.items.size, "system ${v.id.systemId} holds its own item count")
            assertEquals(sent.map { it.position?.latE7 }, back.items.map { it.position?.latE7 }, "and its own waypoints")
            assertEquals(v.state.home!!.position, back.home!!.position, "seq 0 is this vehicle's own home (S11)")
        }

        if (System.getenv("KFT_SITL_FLY") != "1") return@withFleet println("KFT_SITL_FLY not set: the flight is skipped")
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "tools/sitl").isDirectory }
        val pilots = System.getenv("KFT_SITL_FLEET").split(",").map { address ->
            val (host, port) = address.trim().split(":")
            // SERIAL2 of the same instance: one port up from the GCS's SERIAL1.
            ProcessBuilder("py", "-3.9", java.io.File(root, "tools/sitl/sitl_pilot.py").path, "tcp:$host:${port.toInt() + 1}", "--alt", "15").inheritIO().start()
        }
        try {
            // All three in the air in AUTO at the same moment, seen through the GCS's own per-vehicle state.
            withTimeout(180.seconds) { fleet.vehicles.first { list -> list.all { it.state.armed && it.state.flightMode == "Auto" } } }
            println("all ${all.size} are in AUTO at once: " + fleet.vehicles.value.joinToString { "system ${it.id.systemId} alt ${it.state.altitudeRelativeM}" })
            // Each reaches its own last waypoint: item (takeoff + its waypoints) = system id + 2.
            withTimeout(600.seconds) {
                fleet.vehicles.first { list -> list.all { (it.state.mission?.lastReached ?: 0) >= it.id.systemId.toInt() + 2 } }
            }
            println("all missions flown: " + fleet.vehicles.value.joinToString { "system ${it.id.systemId} reached item ${it.state.mission?.lastReached}" })
            withTimeout(300.seconds) { fleet.vehicles.first { list -> list.none { it.state.armed } } }
            println("all landed and disarmed")
        } finally {
            pilots.forEach { it.destroy() }
        }
    }

    /** Pass 12: the login runs first, and on stock ArduPilot it ends LEGACY_FIRMWARE, after which the Pass 6 requests work. */
    @Test
    fun startupRequestsBringVersionAndHome() = withSitl { fleet ->
        val state = fleet.selected.value
        println("SITL: ArduPilot ${state.firmwareVersion}, home ${state.home}, ${state.login?.label}")
        assertNotNull(state.home)
        assertTrue(state.login == KftLoginStatus.LEGACY_FIRMWARE || state.login == KftLoginStatus.AUTHENTICATED, "${state.login}")
    }

    /** Pass 7 acceptance: upload, read back, compare; then clear and read back again. */
    @Test
    fun missionUploadReadBackCompareAndClear() = withSitl { fleet ->
        val missions = fleet.missions
        val home = fleet.selected.value.home!!.position
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
        assertEquals(fleet.selected.value.home!!.position, back.home!!.position)

        missions.clear().getOrThrow()
        assertEquals(emptyList(), missions.download().getOrThrow().items)
    }

    /**
     * Pass 18 acceptance: download every parameter, set CAM1_TYPE, read it back with a fresh download, then see the
     * gateway refuse a set while the vehicle is armed. Arming is the pilot's (S9): `sitl_pilot.py --arm-only` does it
     * on SITL port 5763, as MAVProxy would. Copter only (Plane arms too, but its check isn't needed twice).
     */
    @Test
    fun paramsDownloadSetReadBackAndArmedRefusal() = withSitl { fleet ->
        val params = fleet.params
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
        withTimeout(60.seconds) { fleet.selected.first { it.armed } }
        val refused = params.set(cam, newValue)
        println("while armed: $refused")
        assertTrue(refused is ParamSetResult.NotApplied && refused.reason.contains("disarmed"), "$refused")
        pilot.waitFor()
        // Copter disarms itself on the ground (DISARM_DELAY); wait so the next check starts disarmed.
        withTimeout(30.seconds) { fleet.selected.first { !it.armed } }
        assertEquals(cam.value, params.downloadAll().getOrThrow().single { it.name == "CAM1_TYPE" }.value, "unchanged by the refused set")
    }

    /**
     * Pass 45 acceptance: two pre-arm failures at once both reach the report, a run on request refreshes it, and fixing
     * them clears it (through SYS_STATUS's pre-arm bit). Needs a SITL that is ready to arm (`wait_ready.py` first).
     * The failures come from two parameters, restored at the end: BATT_ARM_VOLT above SITL's 12.6 V battery
     * ("Battery 1 below minimum arming voltage") and RC1_MIN above RC1_TRIM ("RC1_MIN is greater than RC1_TRIM").
     */
    @Test
    fun preArmReportListsEveryFailureAndClears() = withSitl { fleet ->
        val params = fleet.params.downloadAll().getOrThrow().associateBy { it.name }
        val volt = params.getValue("BATT_ARM_VOLT")
        val rcMin = params.getValue("RC1_MIN")
        withTimeout(10.seconds) { fleet.selected.first { it.sensorBits != null } }
        println("sensors: " + fleet.selected.value.sensorBits!!.sensors.joinToString { "${it.name} ${it.state}" })
        assertEquals(SensorState.HEALTHY, fleet.selected.value.sensorBits!!.sensors.single { it.name == "Gyro" }.state)
        try {
            assertTrue(fleet.params.set(volt, 20f) is ParamSetResult.Applied)
            assertTrue(fleet.params.set(rcMin, 1600f) is ParamSetResult.Applied)
            assertEquals(SetupResult.Done, fleet.setup.run(SetupCommand.RunPreArmChecks))
            val failing = withTimeout(10.seconds) { fleet.selected.first { s -> s.preArm.failures.size >= 2 } }
            println("report: ${failing.preArm.failures}")
            assertTrue(failing.preArm.failures.any { "below minimum arming voltage" in it }, "${failing.preArm.failures}")
            assertTrue(failing.preArm.failures.any { "RC1_MIN is greater than RC1_TRIM" in it }, "${failing.preArm.failures}")
            withTimeout(10.seconds) { fleet.selected.first { it.sensorBits?.preArmPassing == false } }
        } finally {
            fleet.params.set(volt.copy(value = 20f), volt.value)
            fleet.params.set(rcMin.copy(value = 1600f), rcMin.value)
        }
        assertEquals(SetupResult.Done, fleet.setup.run(SetupCommand.RunPreArmChecks))
        // ArduPilot refreshes the pre-arm bit from its 1 Hz check; then the list clears.
        val cleared = withTimeout(15.seconds) { fleet.selected.first { it.sensorBits?.preArmPassing == true } }
        println("after restoring: passing, report ${cleared.preArm.failures}")
        assertEquals(emptyList(), cleared.preArm.failures)
    }

    /**
     * Pass 46 acceptance: list the SITL's logs, download one, and compare it byte for byte with the `.BIN` SITL wrote.
     * `KFT_SITL_LOGDIR` is that SITL's log folder (Mission Planner's Windows SITL: Documents\Mission Planner\sitl\copter\logs).
     * The log is the largest one up to 25 MB, matched to its file by size (ArduPilot's list numbers aren't file names).
     */
    @Test
    fun logListAndDownloadMatchTheFileOnDisk() = withSitl { fleet ->
        val folder = System.getenv("KFT_SITL_LOGDIR")?.let { java.io.File(it) } ?: return@withSitl println("KFT_SITL_LOGDIR not set: log check skipped")
        val list = fleet.logs.list().getOrThrow()
        println("SITL lists ${list.size} logs: " + list.joinToString { "${it.id}:${it.sizeBytes}" })
        val onDisk = folder.listFiles { f -> f.name.endsWith(".BIN") }!!.associateBy { it.length() }
        assertEquals(onDisk.size, list.size, "one entry per .BIN file")
        val log = list.filter { it.sizeBytes in 1..25_000_000 }.maxBy { it.sizeBytes }
        val file = onDisk.getValue(log.sizeBytes)
        val started = System.nanoTime()
        var last = 0L
        val path = fleet.logs.download(log) { done -> if (done - last >= 2_000_000 || done == log.sizeBytes) { last = done; println("log ${log.id}: $done / ${log.sizeBytes}") } }.getOrThrow()
        val seconds = (System.nanoTime() - started) / 1e9
        println("log ${log.id} (${file.name}, ${log.sizeBytes} bytes) saved to $path in ${"%.1f".format(seconds)} s, ${"%.0f".format(log.sizeBytes / seconds / 1000)} kB/s")
        assertTrue(java.io.File(path).readBytes().contentEquals(file.readBytes()), "the download is the SITL's own file, byte for byte")
    }
}
