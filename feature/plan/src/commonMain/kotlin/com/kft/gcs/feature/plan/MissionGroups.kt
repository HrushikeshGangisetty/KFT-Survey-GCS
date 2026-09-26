package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.planning.Camera
import com.kft.gcs.core.planning.CameraOrientation
import com.kft.gcs.core.planning.EntryCorner
import com.kft.gcs.core.planning.SurveyHeight
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.SurveyParams
import com.kft.gcs.core.planning.SurveyPlan
import com.kft.gcs.core.planning.Turnaround
import com.kft.gcs.core.planning.cameraOff
import com.kft.gcs.core.planning.gsdM
import com.kft.gcs.core.planning.planSurvey
import com.kft.gcs.core.planning.planeTurnRadiusM
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem

/**
 * One block of the mission, in flight order. The plan is a list of these, not a flat item list: a survey keeps its
 * parameters (so changing the overlap re-generates its lines) and the flat list is made only to upload or export
 * ([flatten]). Home is never part of any group; the upload adds it as seq 0 (S11).
 */
sealed interface MissionGroup {
    val name: String
    fun renamed(name: String): MissionGroup
}

/** Hand-placed items: the Pass 8 waypoint editor, now one group among others. */
data class WaypointGroup(override val name: String, val items: List<PlanItem> = emptyList()) : MissionGroup {
    override fun renamed(name: String) = copy(name = name)
}

/** An area survey: its settings, from which the lines and mission items are generated. */
data class SurveyGroup(override val name: String, val survey: SurveySettings) : MissionGroup {
    override fun renamed(name: String) = copy(name = name)
}

/** Which of altitude and GSD the operator typed; the other one is computed ([SurveyHeight]). */
enum class HeightMode { ALTITUDE, GSD }

/**
 * A survey as the operator edits it: plain numbers in the units the panel shows (percent, cm/px). [toParams] turns
 * it into the planning module's [SurveyParams].
 * @property turnaroundM Copter: straight run-in/run-out outside the area. Plane: the lead-in, straight flight on the
 *   line before the area so the plane has finished its turn when the camera starts. The run-out / lead-out is at
 *   least half a trigger distance, where the camera switches off ([com.kft.gcs.core.planning.cameraOff]).
 * @property returnHome end the survey with a Return-to-launch mission item (flown only when the pilot has put the
 *   vehicle in AUTO; it's a plan, not a command, spec S9).
 */
data class SurveySettings(
    val polygon: List<LatLon> = emptyList(),
    val camera: Camera,
    val heightMode: HeightMode = HeightMode.ALTITUDE,
    val altitudeM: Double,
    val gsdCm: Double,
    val orientation: CameraOrientation = CameraOrientation.LANDSCAPE,
    val sideOverlapPct: Double = 70.0,
    val frontOverlapPct: Double = 80.0,
    val gridAngleDeg: Double = 0.0,
    val entry: EntryCorner = EntryCorner.BOTTOM_LEFT,
    val speedMs: Double,
    val turnaroundM: Double,
    val returnHome: Boolean = true,
) {
    fun toParams(kind: VehicleKind?) = SurveyParams(
        polygon = polygon,
        camera = camera,
        height = if (heightMode == HeightMode.ALTITUDE) SurveyHeight.Altitude(altitudeM) else SurveyHeight.Gsd(gsdCm / 100),
        orientation = orientation,
        sideOverlap = sideOverlapPct / 100,
        frontOverlap = frontOverlapPct / 100,
        gridAngleDeg = gridAngleDeg,
        entry = entry,
        speedMs = speedMs,
        turnaround = if (kind == VehicleKind.PLANE) {
            // Lead-out 0: planSurvey makes it the d/2 the camera needs; the plane turns straight after that.
            Turnaround.Plane(planeTurnRadiusM(speedMs, PLANE_BANK_DEG), leadInM = turnaroundM, leadOutM = 0.0)
        } else {
            Turnaround.Copter(turnaroundM)
        },
    )
}

/**
 * Bank angle assumed for the plane turn radius. ArduPlane's default roll limit is 45°; planning at 30° leaves the
 * autopilot bank to spare for wind.
 * ponytail: a constant; make it a setting if a KFT plane flies with a lower roll limit.
 */
const val PLANE_BANK_DEG = 30.0

/**
 * A new survey's starting values. Copter: 50 m, 8 m/s, 10 m run-in. Plane: 100 m, 22 m/s (about ArduPlane's
 * default cruise airspeed), 120 m lead-in: in ArduPlane SITL (NAVL1_PERIOD 15, 18 m/s) the roll was back to 0° about
 * 110 m after a U-turn, and with a 50 m lead-in the first photos of each line were taken still banked 22–25°.
 */
fun defaultSurvey(kind: VehicleKind?, camera: Camera): SurveySettings {
    val plane = kind == VehicleKind.PLANE
    val altitude = if (plane) 100.0 else 50.0
    return SurveySettings(
        camera = camera,
        altitudeM = altitude,
        gsdCm = camera.gsdM(altitude) * 100,
        speedMs = if (plane) 22.0 else 8.0,
        turnaroundM = if (plane) 120.0 else 10.0,
    )
}

/**
 * One group after flattening: its mission items, the seq number of its first item (S11: the mission's first item is
 * 1), and for a survey the worked-out [plan] or the [error] that stopped it (too few corners, bad overlap…).
 */
data class FlatGroup(val group: MissionGroup, val items: List<MissionItem>, val firstSeq: Int, val plan: SurveyPlan?, val error: String?)

/** The whole plan as the vehicle will get it. */
data class FlatMission(val groups: List<FlatGroup>) {
    val items: List<MissionItem> get() = groups.flatMap { it.items }
    val plannedPhotos: Int get() = groups.sumOf { it.plan?.stats?.photoCount ?: 0 }
}

/**
 * Groups → items, in order. A copter survey that starts the mission gets a NAV_TAKEOFF first (the waypoint group
 * does the same through [addWaypoint]); a survey that can't be planned yet contributes nothing and reports why.
 */
fun flatten(groups: List<MissionGroup>, kind: VehicleKind?, limits: SurveyLimits): FlatMission {
    var nextSeq = 1
    val flat = groups.map { group ->
        val first = nextSeq
        val result = when (group) {
            is WaypointGroup -> FlatGroup(group, toMissionItems(group.items), first, null, null)
            is SurveyGroup -> {
                val plan = if (group.survey.polygon.size < 3) null else runCatching { planSurvey(group.survey.toParams(kind), limits) }
                val takeoff = first == 1 && kind != VehicleKind.PLANE
                when {
                    plan == null -> FlatGroup(group, emptyList(), first, null, "Click the map to add at least 3 corners.")
                    plan.isFailure -> FlatGroup(group, emptyList(), first, null, plan.exceptionOrNull()?.message)
                    else -> plan.getOrThrow().let { FlatGroup(group, surveyItems(it, group.survey, takeoff), first, it, null) }
                }
            }
        }
        nextSeq += result.items.size
        result
    }
    return FlatMission(flat)
}

/**
 * A survey's mission items. Per pass:
 * ```
 * WAYPOINT entry (run-in start, if any) → WAYPOINT photoStart → CAM_TRIGG_DIST(d, shoot now)
 *   → WAYPOINT camera-off point → CAM_TRIGG_DIST(0 = stop) → WAYPOINT exit (run-out end, if further on)
 * ```
 * A DO_ command runs when the vehicle reaches the NAV item before it (ArduPilot AP_Mission), so the camera starts on
 * the area's edge and stops half a trigger distance after the last photo ([cameraOff], within d/2 of the far edge):
 * no photos in the turns. ArduPilot takes the first photo at once (param3
 * = 1) and then one every d metres, which is the ⌊L / d⌋ + 1 per pass that [com.kft.gcs.core.planning.surveyStats]
 * counts. Before the passes: an optional NAV_TAKEOFF (copter, first in the mission) and the survey speed; after them,
 * an optional RETURN_TO_LAUNCH item. All altitudes are relative to home.
 */
internal fun surveyItems(plan: SurveyPlan, survey: SurveySettings, takeoff: Boolean): List<MissionItem> = buildList {
    val alt = plan.altitudeM
    fun waypoint(at: LatLon) = add(MissionItem(MissionCommand.WAYPOINT, at, alt))
    fun trigger(distanceM: Double, shootNow: Boolean) =
        add(MissionItem(MissionCommand.DO_SET_CAM_TRIGG_DIST, param1 = distanceM.toFloat(), param3 = if (shootNow) 1f else 0f))

    if (takeoff) add(MissionItem(MissionCommand.TAKEOFF, altitudeM = alt))
    add(speedItem(survey.speedMs))
    plan.grid.passes.forEach { p ->
        if (p.runInM > 0) waypoint(p.entry)
        waypoint(p.photoStart)
        trigger(plan.triggerDistanceM, shootNow = true)
        val off = p.cameraOff(plan.triggerDistanceM)
        waypoint(off)
        trigger(0.0, shootNow = false)
        if (Geodesy.distanceMeters(off, p.exit) > 0.01) waypoint(p.exit)
    }
    if (survey.returnHome) add(MissionItem(MissionCommand.RETURN_TO_LAUNCH))
}

/**
 * The group header line: "1.7 km · 2 min 47 s · 2.7 cm/px" for a survey. A waypoint group's time is at an assumed
 * speed (its last speed row, else ArduCopter's default 10 m/s WPNAV_SPEED or about ArduPlane's 22 m/s cruise), so
 * it's marked "≈".
 */
internal fun groupSummary(flat: FlatGroup, kind: VehicleKind?): String = when (val g = flat.group) {
    is SurveyGroup -> flat.plan?.let { "${distanceText(it.stats.distanceM)} · ${durationText(it.stats.flightTimeS)} · ${oneDecimalText(it.gsdM * 100)} cm/px" }
        ?: "not planned yet"
    is WaypointGroup -> {
        val points = g.items.mapNotNull { it.position }
        val distance = points.zipWithNext { a, b -> Geodesy.distanceMeters(a, b) }.sum()
        val speed = g.items.lastOrNull { it.speedMs != null }?.speedMs ?: if (kind == VehicleKind.PLANE) 22.0 else 10.0
        if (points.isEmpty()) "${g.items.size} items" else "${points.size} points · ${distanceText(distance)} · ≈${durationText(distance / speed)}"
    }
}

internal fun distanceText(m: Double) = if (m < 1000) "${m.toInt()} m" else "${oneDecimalText(m / 1000)} km"

internal fun durationText(s: Double): String {
    val total = s.toInt()
    return if (total < 60) "$total s" else "${total / 60} min ${total % 60} s"
}

internal fun oneDecimalText(v: Double): String = (kotlin.math.round(v * 10) / 10).toString()
