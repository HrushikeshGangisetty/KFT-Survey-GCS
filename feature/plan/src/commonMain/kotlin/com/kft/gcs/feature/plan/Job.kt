package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.planning.SurveyLimits
import com.kft.gcs.core.planning.gsdM
import com.kft.gcs.core.planning.planSurvey
import com.kft.gcs.core.planning.splitArea
import com.kft.gcs.core.vehicle.Fence
import kotlin.math.max
import kotlin.math.roundToInt

// A job is the plan when it holds several missions (Pass 30, ADR-005). There is no Job class: every group carries
// the number of the mission it belongs to ([MissionGroup.mission]), and the editor keeps which vehicle each mission
// is for. A plan whose groups are all in mission 0 is exactly the single-mission plan of before.

/** The missions in a plan, in the order their first group appears. */
internal fun missionIds(groups: List<MissionGroup>): List<Int> = groups.map { it.mission }.distinct()

/** "M2": a mission's short tag, by its position in [ids]. */
internal fun missionTag(ids: List<Int>, id: Int) = "M${ids.indexOf(id) + 1}"

/**
 * A mission's name: its group's name when one group makes it up (a survey imported from a placemark), else
 * "Mission 2". Groups with nothing in them yet (the empty "Waypoints" every new plan starts with) don't count.
 */
internal fun missionName(groups: List<MissionGroup>, id: Int): String =
    groups.filter { it.mission == id && hasItems(it) }.singleOrNull()?.name ?: "Mission ${missionIds(groups).indexOf(id) + 1}"

/** Whether a group contributes anything: a waypoint group with rows, a survey with enough corners to be an area. */
internal fun hasItems(group: MissionGroup) = when (group) {
    is WaypointGroup -> group.items.isNotEmpty()
    is SurveyGroup -> group.survey.polygon.size >= 3
}

/**
 * The mission the vehicle [selected] is meant to fly: the first one assigned to it. A plan with one mission that is
 * assigned to nobody is for whichever vehicle is selected, as before jobs existed.
 */
internal fun missionFor(selected: VehicleId?, ids: List<Int>, assignments: Map<Int, VehicleId>): Int? =
    ids.firstOrNull { selected != null && assignments[it] == selected } ?: ids.singleOrNull()?.takeIf { it !in assignments }

/**
 * What a mission adds up to, for the upload checks.
 * @property maxAltitudeM the highest point above home. Only items in the relative frame count.
 * @property farthestM the largest distance from home, or null when home isn't known.
 */
data class MissionEstimate(val distanceM: Double, val timeS: Double, val maxAltitudeM: Double, val farthestM: Double?)

/**
 * Walks [items] in flight order from [home] and adds up distance and time.
 * - Each leg at the speed in force: the last DO_CHANGE_SPEED, before that the firmware default (Copter WPNAV_SPEED
 *   10 m/s, Plane about 22 m/s cruise).
 * - RETURN_TO_LAUNCH flies straight home.
 * - Copter only: the takeoff climb at 2.5 m/s and, after RETURN_TO_LAUNCH, the descent from the last altitude at
 *   1.5 m/s (ArduCopter's WPNAV_SPEED_UP and WPNAV_SPEED_DN defaults). A plane's pilot takes off and lands.
 * ponytail: straight legs at constant speed; no wind, no slowing in turns, no RTL climb to RTL_ALT. It is for "does
 * this fit a battery", not for a schedule. Compare with flown times before trusting it closer than about 10 %.
 */
internal fun estimateMission(items: List<MissionItem>, home: LatLon?, kind: VehicleKind?): MissionEstimate {
    val plane = kind == VehicleKind.PLANE
    var speed = if (plane) 22.0 else 10.0
    var at = home
    var altitude = 0.0
    var distance = 0.0
    var time = 0.0
    var maxAltitude = 0.0
    var farthest = 0.0
    fun fly(to: LatLon) {
        at?.let { from -> Geodesy.distanceMeters(from, to).let { d -> distance += d; time += d / speed } }
        at = to
        if (home != null) farthest = max(farthest, Geodesy.distanceMeters(home, to))
    }
    for (item in items) {
        if (item.frame == AltitudeFrame.RELATIVE && (item.position != null || item.command == MissionCommand.TAKEOFF)) {
            maxAltitude = max(maxAltitude, item.altitudeM)
        }
        when {
            item.command == MissionCommand.DO_CHANGE_SPEED -> if (item.param2 > 0f) speed = item.param2.toDouble()
            item.command == MissionCommand.TAKEOFF -> {
                if (!plane) time += item.altitudeM / COPTER_CLIMB_MS
                altitude = item.altitudeM
            }
            item.command == MissionCommand.RETURN_TO_LAUNCH -> {
                home?.let(::fly)
                if (!plane) time += altitude / COPTER_DESCENT_MS
                altitude = 0.0
            }
            else -> item.position?.let { fly(it); altitude = item.altitudeM }
        }
    }
    return MissionEstimate(distance, time, maxAltitude, farthest.takeIf { home != null })
}

private const val COPTER_CLIMB_MS = 2.5
private const val COPTER_DESCENT_MS = 1.5

/**
 * What a mission is checked against before upload.
 * @property maxAltitudeM / [maxDistanceM] the operator's own limits (settings; null = no limit).
 * @property batteryS usable flight time of one battery, or null when it isn't set.
 * @property fence the vehicle's own geofence, if it reported one.
 */
data class MissionLimits(val maxAltitudeM: Double?, val maxDistanceM: Double?, val batteryS: Double?, val fence: Fence?)

/**
 * The reasons to look again before uploading, as sentences. An empty list is a mission inside every limit. None of
 * them blocks the upload: the operator may know better (a permit for 150 m), and the pilot decides whether to fly.
 * A fence breach is worded most strongly, because the vehicle itself will stop the mission there.
 */
internal fun missionProblems(e: MissionEstimate, limits: MissionLimits): List<String> = buildList {
    limits.maxAltitudeM?.let { if (e.maxAltitudeM > it + 1e-6) add("climbs to ${e.maxAltitudeM.roundToInt()} m above home, over your ${it.roundToInt()} m limit.") }
    val far = e.farthestM
    limits.maxDistanceM?.let { if (far != null && far > it + 1e-6) add("goes ${distanceText(far)} from home, beyond your ${distanceText(it)} limit.") }
    limits.batteryS?.let { if (e.timeS > it + 1e-6) add("needs about ${durationText(e.timeS)}, more than one battery (${durationText(it)}). Split it, or plan a battery change.") }
    limits.fence?.altMaxM?.let { if (e.maxAltitudeM >= it) add("reaches ${e.maxAltitudeM.roundToInt()} m, at or above the vehicle's fence (FENCE_ALT_MAX ${it.roundToInt()} m): the vehicle will break off there.") }
    limits.fence?.radiusM?.let { if (far != null && far >= it) add("goes ${distanceText(far)} from home, to or past the vehicle's fence (FENCE_RADIUS ${it.roundToInt()} m): the vehicle will break off there.") }
}

/** The most parts "fits one battery" will try before giving up. */
internal const val MAX_SPLIT_PARTS = 20

/**
 * Splits a survey area into parts, each a survey of its own with the same settings ([splitArea]: cuts parallel to the
 * lines, between lines).
 * - [parts] given: that many parts (fewer when the area has fewer lines).
 * - [parts] null, "fits one battery": the smallest number of parts whose survey flight time each fits the battery time
 *   in [limits]. The legs from and to home aren't counted here (they depend on each vehicle's home); the upload
 *   check counts them.
 * - [altitudeStepM]: part i flies i steps above the first, so drones working side by side are also separated in
 *   height. Each part's lines and photo spacing are worked out again for its own altitude, so the overlaps hold.
 *
 * The parts are named "Field 1/3", … and keep the group's mission number; the caller gives each its own.
 */
internal fun splitSurvey(group: SurveyGroup, kind: VehicleKind?, limits: SurveyLimits, parts: Int?, altitudeStepM: Double): Result<List<SurveyGroup>> = runCatching {
    val survey = group.survey
    require(!survey.isCorridor) { "A corridor can't be split: split its centre line into two corridors instead." }
    val whole = planSurvey(survey.toParams(kind), limits)
    fun partsOf(n: Int) = splitArea(survey.polygon, survey.gridAngleDeg, whole.lineSpacingM, n).mapIndexed { i, polygon ->
        val altitude = whole.altitudeM + i * altitudeStepM
        require(altitude > 0) { "Part ${i + 1} would fly at ${altitude.roundToInt()} m: the altitude step is too negative." }
        survey.copy(polygon = polygon, heightMode = HeightMode.ALTITUDE, altitudeM = altitude, gsdCm = survey.camera.gsdM(altitude) * 100)
    }
    val split = if (parts != null) {
        require(parts >= 2) { "Split into at least 2 parts." }
        partsOf(parts)
    } else {
        val battery = requireNotNull(limits.usableFlightTimeS) { "Set the battery time first (Battery & limits), so the parts can be sized to it." }
        (1..MAX_SPLIT_PARTS).asSequence().map(::partsOf)
            .firstOrNull { list -> list.all { planSurvey(it.toParams(kind), limits).stats.flightTimeS <= battery } }
            ?: throw IllegalArgumentException("Even $MAX_SPLIT_PARTS parts don't each fit one battery (${durationText(battery)}). Check the battery time and the survey settings.")
    }
    require(split.size >= 2) {
        if (parts == null) "It already fits one battery (${durationText(whole.stats.flightTimeS)}): nothing to split."
        else "The area has only one flight line: nothing to split."
    }
    split.mapIndexed { i, s -> SurveyGroup("${group.name} ${i + 1}/${split.size}", s, group.mission) }
}
