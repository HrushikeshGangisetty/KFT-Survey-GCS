package com.kft.gcs.feature.plan

import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.MissionSync
import java.io.File
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
 * Pass 30 acceptance, against `start-sitl.ps1 -Count 3`: one KML with three polygons becomes three missions for three
 * drones, through the Plan screen's own ViewModel (import → a mission per area → a vehicle each → upload), then all
 * three fly and every one takes the photos its mission planned.
 *
 * Skipped unless `KFT_SITL_FLEET=127.0.0.1:5762,127.0.0.1:5772,127.0.0.1:5782` is set. `sitl_pilot.py` is each
 * drone's pilot (S9: the GCS doesn't arm or start anything) on the next port up (5763, 5773, 5783).
 */
class JobSitlRun {
    // Three fields near the CMAC home, each 120 m × 80 m: A 40–160 m east and 30–110 m north of it, B north of A, C east of A.
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

    @Test
    fun oneKmlThreeFieldsThreeDrones() {
        val addresses = System.getenv("KFT_SITL_FLEET")?.split(",")?.map { it.trim().split(":") } ?: return println("KFT_SITL_FLEET not set: skipped")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val links = SerialPorts().let { Links(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), it, T12Link(scope, Dispatchers.IO, it)) }
        val sync = MissionSync()
        val fleet = Fleet(scope, links.open, loginKey = null, sync)
        // The ViewModel's own coroutines run on "Main"; a test JVM has none, so the default pool stands in.
        Dispatchers.setMain(Dispatchers.Default)
        val pilots = mutableListOf<Process>()
        try {
            runBlocking {
                addresses.forEach { (host, port) -> links.connect(LinkConfig.TcpClient(host, port.toInt())) }
                withTimeout(90.seconds) { fleet.vehicles.first { all -> all.size == addresses.size && all.all { it.state.connected && it.state.home != null } } }
                val vehicles = fleet.vehicles.value

                val files = FakeFiles().apply { toImport = ImportFile("fields.kml", kml.encodeToByteArray()) }
                val settings = PlanSettingsRepository(MemoryStore())
                val vm = PlanViewModel(fleet.selected, fleet.missions, sync, settings, files, fleet.vehicles, fleet::missionsOf)
                scope.launch { vm.state.collect {} } // the screen's collector: `state` only updates while someone collects it
                vm.onImportClicked()
                withTimeout(10.seconds) { vm.state.first { it.importDialog != null } }
                vm.onImportAsMissions()
                val job = withTimeout(10.seconds) { vm.state.first { it.missions.size == 3 } }
                println("job: " + job.missions.joinToString { "${it.tag} ${it.name} (${it.summary})" })
                job.missions.forEachIndexed { i, m -> vm.onMissionVehicleSelected(m.id, vehicles[i].id) }
                vm.onUploadClicked()
                val preview = withTimeout(10.seconds) { vm.state.first { it.preview != null } }.preview!!
                println("preview: ${preview.title} warnings ${preview.warnings}")
                assertEquals("Upload 3 of 3 missions?", preview.title)
                vm.onUploadConfirmed()
                val message = withTimeout(120.seconds) { vm.effects.first { it.startsWith("Uploaded") || it.contains("failed") } }
                println(message)
                assertTrue(message.startsWith("Uploaded 3 missions"), message)

                // What each drone should photograph: its own mission's planned photos, from the plan as the VM saves it.
                vm.onSaveClicked()
                withTimeout(10.seconds) { while (files.saved == null) delay(50) }
                val saved = files.saved!!.second
                val flat = flatten(decodePlan(saved), vehicles.first().state.vehicleKind, SurveyLimits())
                val planned = job.missions.mapIndexed { i, m -> vehicles[i].id to flat.only(m.id).plannedPhotos }.toMap()
                println("planned photos: $planned")

                val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "tools/sitl").isDirectory }
                addresses.forEach { (host, port) ->
                    pilots += ProcessBuilder("py", "-3.9", File(root, "tools/sitl/sitl_pilot.py").path, "tcp:$host:${port.toInt() + 1}", "--alt", "50").inheritIO().start()
                }
                withTimeout(240.seconds) { fleet.vehicles.first { list -> list.all { it.state.armed && it.state.flightMode == "Auto" } } }
                println("all three in AUTO")
                // Complete = every drone has taken its planned photos and is back on the ground, disarmed.
                withTimeout(900.seconds) { fleet.vehicles.first { list -> list.all { !it.state.armed && it.state.photos.size >= planned.getValue(it.id) } } }
                val taken = fleet.vehicles.value.associate { it.id to it.state.photos.size }
                println("photos taken: $taken")
                assertEquals(planned, taken, "each drone took exactly the photos its own mission planned")
            }
        } finally {
            pilots.forEach { it.destroy() }
            links.open.value.forEach { links.disconnect(it.id) }
            scope.cancel()
            Dispatchers.resetMain()
        }
    }
}
