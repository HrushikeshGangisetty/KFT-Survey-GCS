package com.kft.gcs.app

import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.MemoryRecordStore
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.feature.fly.AlertUi
import com.kft.gcs.feature.fly.FleetViewModel
import com.kft.gcs.feature.plan.OpenedFile
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.PlanSettingsRepository
import com.kft.gcs.feature.plan.PlanViewModel
import com.kft.gcs.feature.plan.SettingsStore
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout

/**
 * Pass 32 acceptance, against `start-sitl.ps1 -Count 3`: three drones fly a job; drone 3's link is cut and comes
 * back; drone 1's battery runs low, it returns, and its remaining lines are resumed on drone 2. The fleet dashboard's
 * rows and alerts must say each of these as it happens.
 *
 * Skipped unless `KFT_SITL_FLEET_RUN=1`. Links: drones 1 and 2 over TCP (5762, 5772); drone 3 over UDP 14553 through
 * `tools/sitl/link_relay.py` (5782), so that its link can be killed without stopping the drone. `sitl_pilot.py` is
 * each drone's pilot and drains drone 1's simulated battery (S9: the GCS does neither).
 */
class FleetSitlRun {
    private val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document>
        <Placemark><name>Field A</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1656706,-35.3629913 149.1669922,-35.3629913 149.1669922,-35.3622721 149.1656706,-35.3622721 149.1656706,-35.3629913
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        <Placemark><name>Field B</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1656706,-35.3620024 149.1669922,-35.3620024 149.1669922,-35.3612832 149.1656706,-35.3612832 149.1656706,-35.3620024
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        <Placemark><name>Field C</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
            149.1674328,-35.3629913 149.1687545,-35.3629913 149.1687545,-35.3622721 149.1674328,-35.3622721 149.1674328,-35.3629913
        </coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>
        </Document></kml>"""

    /**
     * `py` is a launcher: it starts python.exe and waits for it, and destroying the launcher leaves python.exe running
     * (a pilot that goes on "draining" a battery after the test has ended). So the scripts are also stopped by name.
     */
    private fun killOrphans(script: String) {
        ProcessHandle.allProcesses().filter { p -> p.info().commandLine().map { script in it }.orElse(false) }.forEach { it.destroyForcibly() }
    }

    @Test
    fun theDashboardFollowsALinkLossABatteryReturnAndAResume() {
        if (System.getenv("KFT_SITL_FLEET_RUN") != "1") return println("KFT_SITL_FLEET_RUN not set: skipped")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/sitl").isDirectory }
        val out = File(System.getenv("KFT_SITL_OUT") ?: ".").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val links = SerialPorts().let { Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), it, T12Link(scope, Dispatchers.IO, it)) }
        val sync = MissionSync()
        val fleet = Fleet(scope, links.open, loginKey = null, sync)
        val records = MissionRecords(scope, fleet.vehicles, MemoryRecordStore(), System::currentTimeMillis)
        Dispatchers.setMain(Dispatchers.Default)
        val processes = mutableListOf<Process>()
        fun python(vararg args: String) = ProcessBuilder(listOf("py", "-3.9") + args).inheritIO().start().also { processes += it }
        fun pilot(port: Int, name: String, vararg extra: String) =
            python(File(root, "tools/sitl/sitl_pilot.py").path, "tcp:127.0.0.1:$port", "--alt", "50", "--photos", File(out, name).path, *extra)
        fun relay() = python(File(root, "tools/sitl/link_relay.py").path, "5782", "14553")
        try {
            runBlocking {
                links.connect(LinkConfig.TcpClient("127.0.0.1", 5762))
                links.connect(LinkConfig.TcpClient("127.0.0.1", 5772))
                links.connect(LinkConfig.UdpListen(14553))
                var radio = relay()
                withTimeout(120.seconds) { fleet.vehicles.first { all -> all.size == 3 && all.all { it.state.connected && it.state.home != null } } }
                val (drone1, drone2, drone3) = fleet.vehicles.value.sortedBy { it.id.systemId }.map { it.id }

                val dashboard = FleetViewModel(fleet.vehicles, fleet.selectedId, fleet::select, records.records, SystemWallClock::timeOfDay)
                val alerts = Collections.synchronizedList(mutableListOf<AlertUi>())
                scope.launch { dashboard.state.collect {} }
                scope.launch { dashboard.effects.collect { alerts += it; println("ALERT ${it.time} ${it.vehicle}: ${it.text}") } }
                suspend fun alert(vehicle: Int, text: String, within: Int) = withTimeout(within.seconds) {
                    while (alerts.none { it.vehicle == "Vehicle $vehicle" && it.text.startsWith(text) }) delay(200)
                }
                fun row(vehicle: Int) = dashboard.state.value.rows.first { it.id.systemId.toInt() == vehicle }
                fun printRows(title: String) = println("$title\n" + dashboard.state.value.rows.joinToString("\n") {
                    "  vehicle ${it.label}: ${it.link} ${if (it.connected) "up" else "DOWN"} · ${it.mode}${if (it.armed) " ARMED" else ""} · ${it.battery} · mission ${it.mission} · photos ${it.photos} · ${it.conditions.map { c -> c.first }}"
                })

                // The job: three fields, a mission each, a vehicle each (as in Pass 30).
                val files = object : PlanFiles {
                    override suspend fun save(suggestedName: String, text: String) = suggestedName
                    override suspend fun open(): OpenedFile? = null
                    override suspend fun openForImport() = ImportFile("fields.kml", kml.encodeToByteArray())
                }
                val settings = PlanSettingsRepository(object : SettingsStore {
                    override fun read(): String? = null
                    override fun write(text: String) = Unit
                })
                val plan = PlanViewModel(fleet.selected, fleet.missions, sync, settings, files, fleet.vehicles, fleet::missionsOf, records)
                scope.launch { plan.state.collect {} }
                plan.onImportClicked()
                withTimeout(10.seconds) { plan.state.first { it.importDialog != null } }
                plan.onImportAsMissions()
                val job = withTimeout(10.seconds) { plan.state.first { it.missions.size == 3 } }.missions
                listOf(drone1, drone2, drone3).forEachIndexed { i, id -> plan.onMissionVehicleSelected(job[i].id, id) }
                plan.onUploadClicked()
                withTimeout(10.seconds) { plan.state.first { it.preview != null } }
                plan.onUploadConfirmed()
                println(withTimeout(120.seconds) { plan.effects.first { it.startsWith("Uploaded") || it.contains("failed") } })

                // Drones 2 and 3 take off first; drone 1 follows while they fly (its battery runs low after 15 photos,
                // which must not happen before the others are in the air).
                val pilot2 = pilot(5773, "fleet2.csv")
                pilot(5783, "fleet3.csv")
                withTimeout(240.seconds) { dashboard.state.first { s -> listOf(2, 3).all { n -> row(n).armed && row(n).mode == "Auto" } } }
                pilot(5763, "fleet1.csv", "--low-battery-after", "15")

                // Drone 3's link is cut for a while, then comes back. The drone flies on meanwhile.
                radio.destroyForcibly()
                killOrphans("link_relay.py")
                alert(3, "Link lost", within = 20)
                assertTrue(!row(3).connected && row(3).conditions.any { it.first == "Link lost" }, "the row shows the lost link")
                printRows("drone 3's link cut:")
                delay(6000)
                radio = relay()
                alert(3, "Link restored", within = 30)
                assertTrue(row(3).connected, "the row shows the link again")

                withTimeout(240.seconds) { dashboard.state.first { s -> s.rows.size == 3 && s.rows.all { it.armed } } }
                printRows("all three flying:")

                // Drone 1: battery low, RTL. Its mission is listed as stopped.
                alert(1, "Battery low", within = 180)
                alert(1, "Returning to launch", within = 60)
                assertTrue(row(1).conditions.map { it.first }.containsAll(listOf("Battery low", "RTL")), "${row(1).conditions}")
                printRows("drone 1 returning:")

                // Drones 2 and 3 finish their own missions.
                alert(2, "Mission complete", within = 420)
                alert(3, "Mission complete", within = 420)
                assertTrue(pilot2.waitFor(60, TimeUnit.SECONDS))
                withTimeout(120.seconds) { dashboard.state.first { !row(2).armed } }
                printRows("drones 2 and 3 done:")

                // Drone 1's remaining lines go to drone 2, which flies them.
                val stopped = withTimeout(60.seconds) { plan.state.first { s -> s.stopped.any { it.canResume && it.title.endsWith("vehicle 1") } } }.stopped.first { it.title.endsWith("vehicle 1") }
                println("stopped: ${stopped.title} · ${stopped.detail}")
                plan.onResumeClicked(stopped.id, cameraName = null)
                val rest = withTimeout(10.seconds) { plan.state.first { it.missions.size == 4 } }.missions.last()
                // Drone 2 gives up Field B (flown) for the rest of Field A; the others keep theirs but aren't sent again.
                listOf(job[0], job[2]).forEach { plan.onMissionVehicleSelected(it.id, null) }
                plan.onMissionVehicleSelected(rest.id, drone2)
                alerts.clear()
                plan.onUploadClicked()
                withTimeout(10.seconds) { plan.state.first { it.preview != null } }
                plan.onUploadConfirmed()
                println(withTimeout(120.seconds) { plan.effects.first { it.startsWith("Uploaded") || it.contains("failed") } })
                val again = pilot(5773, "fleet2-rest.csv")
                alert(2, "Mission complete", within = 480)
                assertTrue(again.waitFor(60, TimeUnit.SECONDS))
                delay(2000)
                printRows("after the resume:")

                // Field A is covered by drone 1 and drone 2 together.
                val planned = records.records.value.first { it.name == "Field A" }.plannedPhotos
                val got = records.records.value.filter { it.name.startsWith("Field A") }.sumOf { it.photos.size }
                println("Field A: planned $planned photos, taken ${records.records.value.filter { it.name.startsWith("Field A") }.map { "${it.name} by vehicle ${it.vehicle.systemId}: ${it.photos.size}" }}")
                assertTrue(got in planned..planned + 8, "Field A is covered: $got of $planned")
                assertEquals(setOf("Field A", "Field B", "Field C", "Field A (rest)"), records.records.value.map { it.name }.toSet())
            }
        } finally {
            processes.forEach { it.destroyForcibly() }
            killOrphans("sitl_pilot.py")
            killOrphans("link_relay.py")
            links.open.value.forEach { links.disconnect(it.id) }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
