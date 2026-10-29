package com.kft.gcs.feature.plan

import com.kft.gcs.core.geo.Geodesy
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mission.AltitudeFrame
import com.kft.gcs.core.mission.MissionCommand
import com.kft.gcs.core.mission.MissionItem
import kotlin.math.ceil
import kotlin.math.max

/**
 * One point along the planned path. [altitudeM] is the item's altitude in its [frame], interpolated along the leg;
 * it becomes a height above sea level only once the ground under it is known ([terrainProfile]). [mark] is the
 * waypoint number at a waypoint, null between them. [checked] is false on the climb out of home and the descent into a
 * LAND: low clearance there is the point of the leg, not a warning.
 */
internal data class PathPoint(
    val at: LatLon, val distanceM: Double, val altitudeM: Double, val frame: AltitudeFrame, val mark: String?, val checked: Boolean = true,
)

/**
 * The points to look the ground up at: home (on the ground, 0 m above it), every item with a location, and points
 * between them at most [maxSpacingM] apart, but no more than about [maxPoints] in all, so a 20 km mission still costs
 * one or two requests. A takeoff without a location climbs where the vehicle stands (ArduPilot Copter), so it is a
 * vertical step at the previous point.
 *
 * Like QGC's terrain profile, the path between two waypoints is a straight line in the destination's frame: ArduPilot
 * interpolates altitude linearly along a leg (AC_WPNav), and a terrain-frame leg keeps its height above the ground.
 */
internal fun pathPoints(home: LatLon?, items: List<MissionItem>, maxSpacingM: Double = 25.0, maxPoints: Int = 200): List<PathPoint> {
    val vertices = buildList {
        home?.let { add(PathPoint(it, 0.0, 0.0, AltitudeFrame.RELATIVE, "H", checked = false)) }
        var number = 0
        items.forEach { item ->
            number++ // numbered like the mission list, so "lowest at 4" names the row the operator sees
            val at = item.position ?: lastOrNull()?.at?.takeIf { item.command == MissionCommand.TAKEOFF } ?: return@forEach
            add(PathPoint(at, 0.0, item.altitudeM, item.frame, number.toString(), checked = item.command != MissionCommand.LAND))
        }
    }
    if (vertices.size < 2) return emptyList()
    val legs = vertices.zipWithNext { a, b -> Geodesy.distanceMeters(a.at, b.at) }
    val spacing = max(maxSpacingM, legs.sum() / maxPoints)
    val out = mutableListOf(vertices.first())
    var travelled = 0.0
    vertices.zipWithNext().forEachIndexed { i, (a, b) ->
        val steps = max(1, ceil(legs[i] / spacing).toInt())
        val bearing = Geodesy.initialBearingDeg(a.at, b.at)
        for (s in 1..steps) {
            val f = s.toDouble() / steps
            val last = s == steps
            out += PathPoint(
                at = if (last) b.at else Geodesy.destination(a.at, bearing, legs[i] * f),
                distanceM = travelled + legs[i] * f,
                altitudeM = a.altitudeM + (b.altitudeM - a.altitudeM) * f,
                frame = b.frame,
                mark = if (last) b.mark else null,
                checked = b.checked && (i > 0 || a.checked),
            )
        }
        travelled += legs[i]
    }
    return out
}

/** One column of the profile chart: heights above sea level at [distanceM] along the route. */
data class ProfileSample(val distanceM: Double, val terrainM: Double, val pathM: Double, val mark: String?)

/**
 * The chart's data: ground and path heights above sea level along the route, and the lowest clearance (path minus
 * ground) with where it happens. [lowestAt] is the waypoint at or just before it, "H" for the first leg.
 */
data class TerrainProfile(val samples: List<ProfileSample>, val lowestClearanceM: Double, val lowestAt: String) {
    val status: ClearanceStatus get() = when {
        lowestClearanceM < 0 -> ClearanceStatus.BELOW_GROUND
        lowestClearanceM < LOW_CLEARANCE_M -> ClearanceStatus.LOW
        else -> ClearanceStatus.OK
    }

    companion object {
        /** Under this the path is flagged: GLO-90 heights are ±4 m (90 % of cells) before trees and buildings. */
        const val LOW_CLEARANCE_M = 15.0
    }
}

enum class ClearanceStatus { OK, LOW, BELOW_GROUND }

/**
 * Turns [points] and the ground heights under them into the profile. Relative altitudes are above home, and home's
 * height above sea level is [homeAmslM] (the vehicle's HOME_POSITION) or, with no vehicle, the ground under the first
 * point, which is where a planned mission's home is assumed to be. Null when there's nothing to draw.
 */
internal fun terrainProfile(points: List<PathPoint>, groundM: List<Double>, homeAmslM: Double?): TerrainProfile? {
    if (points.size < 2 || groundM.size != points.size) return null
    val home = homeAmslM ?: groundM.first()
    val samples = points.mapIndexed { i, p ->
        val path = when (p.frame) {
            AltitudeFrame.AMSL -> p.altitudeM
            AltitudeFrame.RELATIVE -> home + p.altitudeM
            AltitudeFrame.TERRAIN -> groundM[i] + p.altitudeM
        }
        ProfileSample(p.distanceM, groundM[i], path, p.mark)
    }
    val lowest = samples.withIndex().filter { points[it.index].checked }.minByOrNull { it.value.pathM - it.value.terrainM } ?: return null
    val at = samples.take(lowest.index + 1).lastOrNull { it.mark != null }?.mark ?: "H"
    return TerrainProfile(samples, lowest.value.pathM - lowest.value.terrainM, at)
}
