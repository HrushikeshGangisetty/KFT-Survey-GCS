package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

// Resuming a survey that stopped part-way (battery, link loss, the pilot's decision): find where the last photo was
// taken on the plan, and build the plan for what is left. Pure, like everything in this module.

/**
 * A place on a survey plan: the [leg] (0 = the first set of lines, 1 = a crosshatch's second set), the [pass] in that
 * leg's flight order, and [alongM] metres along the pass from its `photoStart`.
 */
data class PlanPosition(val leg: Int, val pass: Int, val alongM: Double)

/**
 * Where on this plan the last of [photos] was taken (they are in the order taken), or null without photos.
 *
 * Two clues, used together:
 * - **Geometry:** the passes the last photo lies on, i.e. within half a line spacing of the pass's path (further
 *   off, it would be nearer the neighbouring line).
 * - **The count:** n photos taken means the last is about the n-th planned photo, which says which pass was being
 *   flown. On its own it drifts if photos were missed; with the geometry it only has to pick between passes that
 *   cross the same spot, which in a crosshatch are far apart in flight order.
 * The pass chosen is the geometric candidate nearest in flight order to the count's pass; of two equally near, the
 * earlier one (flying a little again is safe, leaving a gap is not). If the photo is on no pass at all (taken in a
 * turn), the count alone decides.
 */
fun SurveyPlan.locateLastPhoto(photos: List<LatLon>): PlanPosition? {
    val last = photos.lastOrNull() ?: return null
    class Candidate(val position: PlanPosition, val order: Int, val distanceM: Double, val photosBefore: Int, val spacingM: Double, val triggerM: Double, val lengthM: Double)

    var photosBefore = 0
    val candidates = legs.flatMapIndexed { l, leg ->
        leg.grid.passes.mapIndexed { p, pass ->
            val (distance, along) = nearestOnPath(pass.photoPath(), last)
            Candidate(PlanPosition(l, p, along), 0, distance, photosBefore, leg.lineSpacingM, leg.triggerDistanceM, pass.photoLengthM)
                .also { photosBefore += photosOnPass(pass.photoLengthM, leg.triggerDistanceM, leg.footprint.alongM) }
        }
    }.mapIndexed { i, c -> Candidate(c.position, i, c.distanceM, c.photosBefore, c.spacingM, c.triggerM, c.lengthM) }
    if (candidates.isEmpty()) return null

    // The pass the n-th planned photo is on.
    val byCount = candidates.lastOrNull { it.photosBefore < photos.size } ?: candidates.first()
    val onALine = candidates.filter { it.distanceM <= it.spacingM / 2 }
    val best = onALine.minWithOrNull(compareBy({ abs(it.order - byCount.order) }, { it.order }))
    return best?.position
        ?: byCount.position.copy(alongM = ((photos.size - 1 - byCount.photosBefore) * byCount.triggerM).coerceIn(0.0, byCount.lengthM))
}

/**
 * The plan for what is left after the last photo at [position]: the rest of that pass, starting **one trigger
 * distance before the last photo** (so the first new photo repeats the last old one and the two flights overlap),
 * then every later pass and leg unchanged.
 *
 * The first pass keeps its run-in, now straight before the new start point, so the vehicle is on the line and at
 * speed (a plane: settled after its turn) when the camera starts. Stats, batteries and data are worked out again for
 * what remains; the area stays the whole area's.
 */
fun SurveyPlan.remainderFrom(position: PlanPosition, limits: SurveyLimits = SurveyLimits()): SurveyPlan {
    val all = legs
    val leg = all[position.leg]
    val pass = leg.grid.passes[position.pass]
    val from = (position.alongM - leg.triggerDistanceM).coerceIn(0.0, pass.photoLengthM)
    val passes = listOf(pass.startingAt(from)) + leg.grid.passes.drop(position.pass + 1)
    val grid = SurveyGrid(
        passes = passes,
        lineCount = passes.map { it.line }.distinct().size,
        connectorsM = leg.grid.connectorsM.drop(position.pass),
        lineSkip = leg.grid.lineSkip,
        loopTurns = leg.grid.loopTurns, // not counted again: at most what the whole plan had
    )
    val left = listOf(leg.copy(grid = grid)) + all.drop(position.leg + 1)

    val speed = if (stats.flightTimeS > 0) stats.distanceM / stats.flightTimeS else 1.0
    val hop = left.zipWithNext { a, b -> distanceBetween(a.grid.passes.last().exit, b.grid.passes.first().entry) }.sum()
    val distance = left.sumOf { it.grid.distanceM } + hop
    val photos = left.sumOf { l -> l.grid.passes.sumOf { photosOnPass(it.photoLengthM, l.triggerDistanceM, l.footprint.alongM) } }
    val newStats = SurveyStats(stats.areaM2, left.sumOf { it.grid.lineCount }, photos, distance, distance / speed)
    val first = left.first()
    return SurveyPlan(
        altitudeM = first.altitudeM,
        gsdM = first.gsdM,
        footprint = first.footprint,
        lineSpacingM = first.lineSpacingM,
        triggerDistanceM = first.triggerDistanceM,
        grid = first.grid,
        stats = newStats,
        batteries = limits.usableFlightTimeS?.takeIf { it > 0 }?.let { ceil(newStats.flightTimeS / it - 1e-6).toInt() },
        dataMb = if (stats.photoCount > 0) dataMb * photos / stats.photoCount else 0.0,
        warnings = warnings,
        crosshatch = left.getOrNull(1),
    )
}

/**
 * The photo lines already flown up to [position], each as a path: every pass before it in full, and its own pass
 * from the start to the last photo. For the map's "covered" lines.
 */
fun SurveyPlan.coveredLines(position: PlanPosition): List<List<LatLon>> {
    val before = legs.take(position.leg).flatMap { it.grid.passes } + legs[position.leg].grid.passes.take(position.pass)
    val current = legs[position.leg].grid.passes[position.pass]
    val path = current.photoPath()
    val part = listOf(current.photoStart) + current.viaDistancesM().filter { (_, d) -> d < position.alongM }.map { it.first } + pointAlong(path, position.alongM)
    return before.map { it.photoPath() } + listOf(part)
}

/** The path flown with the camera on: start, bends, end. */
fun Pass.photoPath(): List<LatLon> = listOf(photoStart) + via + photoEnd

/** This pass from [alongM] metres after its photoStart: a new start point there, the run-in straight before it. */
private fun Pass.startingAt(alongM: Double): Pass {
    if (alongM <= 0.0) return this
    val path = photoPath()
    val start = pointAlong(path, alongM)
    // The direction of flight at the new start, from a point one metre (or less, near the start) before it.
    val projection = LocalProjection(start)
    val behind = projection.toLocal(pointAlong(path, max(alongM - 1.0, 0.0)))
    val length = hypot(behind.x, behind.y)
    val entry = if (length == 0.0) start else projection.toLatLon(LocalPoint(behind.x / length * runInM, behind.y / length * runInM))
    return copy(
        entry = entry,
        photoStart = start,
        photoLengthM = photoLengthM - alongM,
        via = viaDistancesM().filter { (_, d) -> d > alongM }.map { it.first },
    )
}

/** How far [p] is from [path], and how far along the path its nearest point is. */
private fun nearestOnPath(path: List<LatLon>, p: LatLon): Pair<Double, Double> {
    val projection = LocalProjection(path.first())
    val pts = path.map(projection::toLocal)
    val q = projection.toLocal(p)
    var best = Double.MAX_VALUE to 0.0
    var walked = 0.0
    for (i in 0 until pts.lastIndex) {
        val (a, b) = pts[i] to pts[i + 1]
        val len = hypot(b.x - a.x, b.y - a.y)
        // The nearest point on the segment: the photo's projection onto it, kept between its two ends.
        val t = if (len == 0.0) 0.0 else (((q.x - a.x) * (b.x - a.x) + (q.y - a.y) * (b.y - a.y)) / (len * len)).coerceIn(0.0, 1.0)
        val d = hypot(q.x - (a.x + t * (b.x - a.x)), q.y - (a.y + t * (b.y - a.y)))
        if (d < best.first) best = d to walked + t * len
        walked += len
    }
    return best
}

private fun distanceBetween(a: LatLon, b: LatLon): Double = LocalProjection(a).toLocal(b).let { hypot(it.x, it.y) }
