package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.MemoryRecordStore
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.MissionSync
import java.io.File
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
 * Pass 31 acceptance, against `start-sitl.ps1 -Count 2`: drone 1 starts a survey, its battery runs low part-way and
 * ArduPilot's failsafe takes it home; the GCS has recorded every photo, builds the mission for what is left, and
 * drone 2 flies it. `photo_check.py --coverage` then looks at both drones' photos together: every planned photo
 * position has a photo, and there is no gap where drone 2 took over.
 *
 * Skipped unless `KFT_SITL_FLEET=127.0.0.1:5762,127.0.0.1:5772` is set; `KFT_SITL_OUT` is where the mission export
 * and the two photo files go. `sitl_pilot.py` is each drone's pilot, and also the one who "drains" drone 1's
 * simulated battery after its 30th photo (`--low-battery-after 30`): the GCS sends none of that (S9).
 */
class ResumeSitlRun {
    @Test
    fun droneTwoFinishesWhatDroneOneStarted() {
        val addresses = System.getenv("KFT_SITL_FLEET")?.split(",")?.map { it.trim().split(":") } ?: return println("KFT_SITL_FLEET not set: skipped")
        val out = File(System.getenv("KFT_SITL_OUT") ?: ".").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val links = SerialPorts().let { Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), it, T12Link(scope, Dispatchers.IO, it)) }
        val sync = MissionSync()
        val fleet = Fleet(scope, links.open, loginKey = null, sync)
        val records = MissionRecords(scope, fleet.vehicles, MemoryRecordStore(), System::currentTimeMillis)
        Dispatchers.setMain(Dispatchers.Default)
        val pilots = mutableListOf<Process>()
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/sitl").isDirectory }
        fun pilot(address: List<String>, photos: String, vararg extra: String) = ProcessBuilder(
            listOf("py", "-3.9", File(root, "tools/sitl/sitl_pilot.py").path, "tcp:${address[0]}:${address[1].toInt() + 1}", "--alt", "50", "--photos", File(out, photos).path) + extra,
        ).inheritIO().start().also { pilots += it }
        try {
            runBlocking {
                addresses.take(2).forEach { (host, port) -> links.connect(LinkConfig.TcpClient(host, port.toInt())) }
                withTimeout(90.seconds) { fleet.vehicles.first { all -> all.size == 2 && all.all { it.state.connected && it.state.home != null } } }
                val (drone1, drone2) = fleet.vehicles.value.map { it.id }
                val home = fleet.vehicles.value.first().state.home!!.position

                val files = FakeFiles()
                val vm = PlanViewModel(fleet.selected, fleet.missions, sync, PlanSettingsRepository(MemoryStore()), files, fleet.vehicles, fleet::missionsOf, records)
                scope.launch { vm.state.collect {} }
                // A field 160 m × 110 m, 40 m east and 30 m north of home: 8 lines of 12 photos with the default survey.
                vm.onAddSurveyClicked()
                listOf(40.0 to 30.0, 200.0 to 30.0, 200.0 to 140.0, 40.0 to 140.0).forEach { (e, n) -> vm.onMapClick(offset(home, e, n)) }
                val planned = withTimeout(10.seconds) { vm.state.first { s -> s.survey.let { it != null && it.cornerCount == 4 && it.stats.isNotEmpty() } } }.survey!!.stats.toMap().getValue("Photos").toInt()
                vm.onUploadClicked()
                withTimeout(10.seconds) { vm.state.first { it.preview != null } }
                vm.onUploadConfirmed()
                println(withTimeout(60.seconds) { vm.effects.first { it.startsWith("Uploaded") || it.contains("failed") } })
                vm.onExportClicked(ExportFormat.WAYPOINTS)
                withTimeout(10.seconds) { while (files.saved?.first != "mission.waypoints") delay(50) }
                File(out, "mission.waypoints").writeText(files.saved!!.second)
                println("planned photos: $planned; drone 1 = $drone1, drone 2 = $drone2")

                // Drone 1 flies; its battery "runs low" after 30 photos and the failsafe takes it home.
                val first = pilot(addresses[0], "drone1.csv", "--low-battery-after", "30")
                withTimeout(300.seconds) { vm.state.first { s -> s.stopped.any { it.canResume } && fleet.vehicles.value.first().state.flightMode == "RTL" } }
                val stopped = vm.state.value.stopped.single()
                println("stopped: ${stopped.title} · ${stopped.detail}")
                assertTrue(first.waitFor(30, TimeUnit.SECONDS), "drone 1's pilot stops logging once it leaves AUTO")
                val taken1 = records.records.value.single().photos.size
                assertTrue(taken1 in 30 until planned, "it stopped part-way: $taken1 of $planned")

                // Resume on drone 2 with the same camera.
                vm.onResumeClicked(stopped.id, cameraName = null)
                println(withTimeout(10.seconds) { vm.effects.first { it.startsWith("Added the rest") || it.startsWith("Can't") } })
                val rest = withTimeout(10.seconds) { vm.state.first { it.missions.size == 2 } }.missions.last()
                println("rest: ${rest.tag} ${rest.name} (${rest.summary})")
                vm.onMissionVehicleSelected(rest.id, drone2)
                vm.onUploadClicked()
                val preview = withTimeout(10.seconds) { vm.state.first { it.preview != null } }.preview!!
                println("preview: ${preview.title} ${preview.warnings}")
                vm.onUploadConfirmed()
                println(withTimeout(60.seconds) { vm.effects.first { it.startsWith("Uploaded") || it.contains("failed") } })

                val second = pilot(addresses[1], "drone2.csv")
                assertTrue(second.waitFor(420, TimeUnit.SECONDS), "drone 2 finishes the rest")
                delay(2000) // the last photos reach the GCS
                val taken2 = fleet.vehicles.value.first { it.id == drone2 }.state.photos.size
                println("photos: drone 1 took $taken1, drone 2 took $taken2, planned $planned")
                // Together they took every planned photo, plus the few taken twice around the hand-over (the overlap of
                // one photo spacing, and what drone 1 shot between its last recorded photo and leaving AUTO).
                assertTrue(taken1 + taken2 in planned..planned + 8, "nothing missing, little flown twice: $taken1 + $taken2 vs $planned")

                val check = ProcessBuilder("py", "-3.9", File(root, "tools/sitl/photo_check.py").path, File(out, "mission.waypoints").path,
                    File(out, "drone1.csv").path, File(out, "drone2.csv").path, "--coverage").redirectErrorStream(true).start()
                val report = check.inputStream.bufferedReader().readText()
                println(report)
                assertEquals(0, check.waitFor(), "photo_check --coverage found a gap")
            }
        } finally {
            pilots.forEach { it.destroy() }
            links.open.value.forEach { links.disconnect(it.id) }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }

    /** [east] and [north] metres from [p], on a local flat map (exact enough over a field, as in core:geo). */
    private fun offset(p: LatLon, east: Double, north: Double): LatLon {
        val mPerDeg = 6371008.8 * Math.PI / 180
        return LatLon(p.latitude + north / mPerDeg, p.longitude + east / (mPerDeg * Math.cos(Math.toRadians(p.latitude))))
    }
}
