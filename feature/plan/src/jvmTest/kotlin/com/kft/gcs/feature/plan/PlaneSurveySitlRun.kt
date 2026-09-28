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
 * `KFT_SITL=127.0.0.1:5762 KFT_SITL_OUT=<dir> gradlew :feature:plan:jvmTest --tests '*PlaneSurveySitlRun.uploadPass16PlaneField'`,
 * then fly it with `sitl_pilot.py tcp:127.0.0.1:5763 --photos <dir>/photos.csv`.
 *
 * [uploadCopterFarEdgeField] is the Copter check of Pass 21's far-edge photo; [uploadCopterCrosshatchField] and
 * [uploadCopterCorridor] are Pass 25's. All run the same way against Copter SITL.
 */
class PlaneSurveySitlRun {
    @Test
    fun uploadPass16PlaneField() = upload(VehicleKind.PLANE) { home ->
        val camera = bundledCameras.first { it.name.startsWith("Sony RX1R II") }
        defaultSurvey(VehicleKind.PLANE, camera).copy(
            polygon = listOf(offset(home, -453.6, -231.5), offset(home, -175.2, -231.5), offset(home, -175.2, 177.4), offset(home, -453.6, 177.4)),
            sideOverlapPct = 60.0, frontOverlapPct = 65.0, speedMs = 18.0,
        )
    }

    /**
     * Pass 21: a Copter field where every line needs the extra far-edge photo ([com.kft.gcs.core.planning.photosOnPass]).
     * Phantom 4 Pro at 50 m: footprint 75 × 50 m; 40 % front overlap gives a 30 m trigger. Lines 148 m long
     * (grid 0°): photos on the way at 0, 30, …, 120 m, leaving a 28 m strip, more than half the 50 m footprint, so a
     * 6th photo at 150 m. 70 % side overlap (22.5 m lines) over 200 m east–west: 9 lines, 54 photos expected.
     */
    @Test
    fun uploadCopterFarEdgeField() = upload(VehicleKind.COPTER) { home ->
        val camera = bundledCameras.first { it.name.startsWith("DJI Phantom 4 Pro") }
        defaultSurvey(VehicleKind.COPTER, camera).copy(
            polygon = listOf(offset(home, 40.0, 30.0), offset(home, 240.0, 30.0), offset(home, 240.0, 178.0), offset(home, 40.0, 178.0)),
            sideOverlapPct = 70.0, frontOverlapPct = 40.0,
        )
    }

    /**
     * Pass 25, crosshatch: a 160 × 110 m Copter field east of home, P4P at 50 m, 70 % side / 70 % front, flown again at
     * 90° 10 m higher (60 m: spacing and trigger scale by 60/50). photo_check: every photo on one of the two sets of lines.
     */
    @Test
    fun uploadCopterCrosshatchField() = upload(VehicleKind.COPTER) { home ->
        val camera = bundledCameras.first { it.name.startsWith("DJI Phantom 4 Pro") }
        defaultSurvey(VehicleKind.COPTER, camera).copy(
            polygon = listOf(offset(home, 40.0, 30.0), offset(home, 200.0, 30.0), offset(home, 200.0, 140.0), offset(home, 40.0, 140.0)),
            sideOverlapPct = 70.0, frontOverlapPct = 70.0, crosshatch = true, crosshatchOffsetM = 10.0,
        )
    }

    /**
     * Pass 25, corridor: an L-shaped centre line (220 m east, then 150 m north), 20 m each side, 3 lines with one on the
     * centre line, P4P at 50 m, 70 % front. photo_check: every photo on its line, including round the bend.
     */
    @Test
    fun uploadCopterCorridor() = upload(VehicleKind.COPTER) { home ->
        val camera = bundledCameras.first { it.name.startsWith("DJI Phantom 4 Pro") }
        defaultSurvey(VehicleKind.COPTER, camera).copy(
            polygon = listOf(offset(home, 30.0, 40.0), offset(home, 250.0, 40.0), offset(home, 250.0, 190.0)),
            pattern = SurveyPattern.CORRIDOR, leftWidthM = 20.0, rightWidthM = 20.0, corridorLines = 3, includeCentreLine = true,
            frontOverlapPct = 70.0,
        )
    }

    /** Connects to KFT_SITL, plans [survey] (given home) with the Plan screen's [flatten], uploads, writes the export. */
    private fun upload(kind: VehicleKind, survey: (LatLon) -> SurveySettings) {
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
                val flat = flatten(listOf(SurveyGroup("Survey", survey(home.position))), kind, SurveyLimits())
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
