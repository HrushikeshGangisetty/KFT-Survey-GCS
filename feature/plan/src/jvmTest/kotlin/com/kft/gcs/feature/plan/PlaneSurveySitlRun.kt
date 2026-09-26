package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geoio.bundledCameras
import com.kft.gcs.core.geoio.encodeWaypoints
import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.vehicle.DefaultMissionRepository
import com.kft.gcs.core.vehicle.VehicleRepository
import java.io.File
import kotlin.test.Test
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
 * The Plane survey field of Pass 16, planned with the Plan screen's own code ([flatten]) and uploaded to a running
 * ArduPlane SITL, for the first-line re-check (Pass 18 item 2). Writes the `.waypoints` export that
 * `tools/sitl/photo_check.py` reads. Skipped unless `KFT_SITL=host:port` is set, like SitlCheck.
 *
 * The field: the Pass 16 plan file wasn't kept, so the corners are measured from `pass16-plane-desktop-done.png`,
 * scaled by the logged 408.9 m line length (0.721 m/px): 453–175 m west and 177 m north to 232 m south of the CMAC
 * home. Settings as logged: Sony RX1R II, 100 m, 60 % side / 65 % front overlap (41 m lines, 24 m trigger), 18 m/s,
 * grid 0°, default 120 m lead-in, RTL at the end.
 *
 * Run: `start-sitl.ps1 -Vehicle plane -Wipe`, then
 * `KFT_SITL=127.0.0.1:5762 KFT_SITL_OUT=<dir> gradlew :feature:plan:jvmTest --tests '*PlaneSurveySitlRun*'`,
 * then fly it with `sitl_pilot.py tcp:127.0.0.1:5763 --photos <dir>/photos.csv`.
 */
class PlaneSurveySitlRun {
    @Test
    fun uploadPass16PlaneField() {
        val (host, port) = System.getenv("KFT_SITL")?.split(":") ?: return println("KFT_SITL not set: skipped")
        val out = File(System.getenv("KFT_SITL_OUT") ?: ".")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ConnectionManager(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod), SerialPorts())
        val vehicles = VehicleRepository(scope, manager.frames, manager.state, manager.gateway, loginKey = null)
        val missions = DefaultMissionRepository(manager.frames, manager.state, vehicles.state, manager.gateway)
        try {
            runBlocking {
                manager.connect(LinkConfig.TcpClient(host, port.toInt()))
                val home = withTimeout(90.seconds) { vehicles.state.first { it.home != null } }.home!!
                val camera = bundledCameras.first { it.name.startsWith("Sony RX1R II") }
                val survey = defaultSurvey(VehicleKind.PLANE, camera).copy(
                    polygon = listOf(offset(home.position, -453.6, -231.5), offset(home.position, -175.2, -231.5),
                        offset(home.position, -175.2, 177.4), offset(home.position, -453.6, 177.4)),
                    sideOverlapPct = 60.0, frontOverlapPct = 65.0, speedMs = 18.0,
                )
                val flat = flatten(listOf(SurveyGroup("Survey", survey)), VehicleKind.PLANE, SurveyLimits())
                val plan = flat.groups.single().plan!!
                println("lines ${plan.stats.lineCount}, spacing ${plan.lineSpacingM}, trigger ${plan.triggerDistanceM}, planned photos ${plan.stats.photoCount}, items ${flat.items.size}")
                missions.upload(flat.items).getOrThrow()
                File(out, "mission.waypoints").writeText(encodeWaypoints(Mission(home, flat.items)))
                println("uploaded; wrote ${File(out, "mission.waypoints").absolutePath}")
            }
        } finally {
            manager.disconnect()
            scope.cancel()
        }
    }

    /** [east] and [north] metres from [p], on a local flat map (exact enough over a field, as in core:geo). */
    private fun offset(p: LatLon, east: Double, north: Double): LatLon {
        val mPerDeg = 6371008.8 * Math.PI / 180
        return LatLon(p.latitude + north / mPerDeg, p.longitude + east / (mPerDeg * Math.cos(Math.toRadians(p.latitude))))
    }
}
