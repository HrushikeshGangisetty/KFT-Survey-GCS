package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.Geodesy.toRadians
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the first line starts, in the grid frame: "up" is the grid angle (the direction lines run), "left" is 90°
 * counter-clockwise from it. For grid angle 0 (north–south lines), BOTTOM_LEFT is the south-west corner.
 * ```
 *  TOP_LEFT ┌─────────┐ TOP_RIGHT      ↑ up = grid angle
 *           │ │ │ │ │ │
 *  BOT_LEFT └─────────┘ BOTTOM_RIGHT
 * ```
 */
enum class EntryCorner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

/** How the vehicle gets from one line to the next. Copter and Plane turn so differently that they are separate. */
sealed interface Turnaround {
    /**
     * A copter stops and turns in place, so the step to the next line is a straight hop. [extensionM] is extra
     * straight flight before and after each line, outside the area, so it's at survey speed by the first photo.
     */
    data class Copter(val extensionM: Double = 0.0) : Turnaround {
        init { require(extensionM >= 0) { "extension must not be negative" } }
    }

    /**
     * A plane can't stop: it flies [leadInM] straight on the line before the area (to settle after the turn) and
     * [leadOutM] after it, then turns with [turnRadiusM] (see [planeTurnRadiusM]) onto the next line.
     */
    data class Plane(val turnRadiusM: Double, val leadInM: Double, val leadOutM: Double) : Turnaround {
        init { require(turnRadiusM > 0 && leadInM >= 0 && leadOutM >= 0) { "turn radius must be positive, lead-in/out not negative" } }
    }
}

/**
 * What to grid: the area, the direction of the lines ([gridAngleDeg], clockwise from true north: 0 = north–south
 * lines, 90 = east–west), their spacing (from [lineSpacingM]), how to turn, and where to start.
 */
data class GridSpec(
    val polygon: List<LatLon>,
    val gridAngleDeg: Double,
    val lineSpacingM: Double,
    val turnaround: Turnaround,
    val entry: EntryCorner = EntryCorner.BOTTOM_LEFT,
)

/**
 * One straight run, in flight order: [entry] → [photoStart] (inside the area, cameras on) → [photoEnd] → [exit].
 * Entry/exit add the run-in/run-out outside the area. A concave area can give one line several passes, with the
 * same [line] number; the vehicle flies straight on between them and only the first/last have a run-in/out.
 */
data class Pass(
    /** Sweep line number in flight order, 0 = first. */
    val line: Int,
    val entry: LatLon,
    val photoStart: LatLon,
    val photoEnd: LatLon,
    val exit: LatLon,
    val runInM: Double,
    val photoLengthM: Double,
    val runOutM: Double,
)

/**
 * The flight lines, in the order they're flown. [connectorsM] holds the length of each leg from one pass's exit to
 * the next pass's entry (so it has one element fewer than [passes]).
 */
data class SurveyGrid(val passes: List<Pass>, val lineCount: Int, val connectorsM: List<Double>)

data class SurveyStats(
    val areaM2: Double,
    val lineCount: Int,
    val photoCount: Int,
    /** From the first entry to the last exit. The legs from and back to home aren't included (they depend on home). */
    val distanceM: Double,
    val flightTimeS: Double,
)

/** More lines than this is a mistake (10 m spacing over 10 km), and would build a huge mission. */
internal const val MAX_LINES = 1000

/**
 * Builds the lawn-mower pattern over [spec]'s polygon. Pure: the same input always gives the same grid.
 *
 * Steps (checked against QGC's `SurveyComplexItem.cc`, master 2026-09: rotate, sweep, clip, order, extend):
 * 1. Project the polygon onto a flat local map in metres ([LocalProjection]).
 * 2. Rotate it into the grid frame: `across` is the distance sideways from the lines, `along` the distance along them.
 * 3. Place the sweep lines: n = ⌈width / spacing⌉ lines, exactly `spacing` apart, centred in the width. The outer
 *    lines are then at most spacing/2 from the edge, and each photo reaches footprint/2 ≥ spacing/2 sideways, so
 *    the edges are covered. A strip narrower than one spacing gets a single line down the middle.
 * 4. Clip each line to the polygon (scan-line crossings, sorted and paired), giving one or more inside segments.
 *    Unlike QGC (which joins the outermost crossings), a line over a concave notch gets separate passes.
 * 5. Order them by the entry corner, alternating direction line by line (boustrophedon).
 * 6. Add the run-in/run-out and measure the legs between passes with the Copter or Plane turn model.
 *
 * @throws IllegalArgumentException for fewer than 3 corners, a zero-area polygon, a polygon crossing the
 *   antimeridian, a non-positive spacing, or more than [MAX_LINES] lines.
 */
fun buildSurveyGrid(spec: GridSpec): SurveyGrid {
    requireSurveyablePolygon(spec.polygon)
    require(spec.lineSpacingM > 0) { "line spacing must be positive: ${spec.lineSpacingM}" }

    val projection = projectionFor(spec.polygon)
    val frame = GridFrame(spec.gridAngleDeg)
    val corners = spec.polygon.map { frame.toGrid(projection.toLocal(it)) }

    // 3. Sweep lines, centred across the polygon's width.
    val minAcross = corners.minOf { it.across }
    val width = corners.maxOf { it.across } - minAcross
    val lineCount = max(1, ceil(width / spec.lineSpacingM - 1e-9).toInt()) // 1e-9: a width of exactly k spacings is k lines
    require(lineCount <= MAX_LINES) { "$lineCount lines is too many (max $MAX_LINES): check the spacing and the area" }
    val firstAcross = minAcross + (width - (lineCount - 1) * spec.lineSpacingM) / 2
    val sweepOrder = (0 until lineCount).map { firstAcross + it * spec.lineSpacingM }
        .let { if (spec.entry == EntryCorner.BOTTOM_RIGHT || spec.entry == EntryCorner.TOP_RIGHT) it.reversed() else it }

    val (runIn, runOut) = when (val t = spec.turnaround) {
        is Turnaround.Copter -> t.extensionM to t.extensionM
        is Turnaround.Plane -> t.leadInM to t.leadOutM
    }

    // 4–6. Clip, order, extend. `up` = flying in the +along direction on this line.
    val passes = mutableListOf<GridPass>()
    var up = spec.entry == EntryCorner.BOTTOM_LEFT || spec.entry == EntryCorner.BOTTOM_RIGHT
    var flownLines = 0
    for (across in sweepOrder) {
        val inside = insideSegments(corners, across)
        if (inside.isEmpty()) continue
        val dir = if (up) 1.0 else -1.0
        val inFlightOrder = if (up) inside else inside.reversed().map { (a, b) -> b to a }
        inFlightOrder.forEachIndexed { k, (start, end) ->
            val inM = if (k == 0) runIn else 0.0
            val outM = if (k == inFlightOrder.lastIndex) runOut else 0.0
            passes += GridPass(flownLines, across, start - dir * inM, start, end, end + dir * outM, inM, outM)
        }
        flownLines++
        up = !up
    }

    val connectors = passes.zipWithNext { p, q ->
        val step = abs(q.across - p.across)
        val alongGap = abs(q.entry - p.exit)
        when {
            p.line == q.line -> alongGap // straight on, over a gap in a concave area
            spec.turnaround is Turnaround.Plane -> planeTurnM(step, spec.turnaround.turnRadiusM) + alongGap
            else -> hypot(step, alongGap) // copter: a straight hop
        }
    }

    fun at(across: Double, along: Double) = projection.toLatLon(frame.fromGrid(across, along))
    return SurveyGrid(
        passes = passes.map { p ->
            Pass(
                line = p.line,
                entry = at(p.across, p.entry), photoStart = at(p.across, p.start),
                photoEnd = at(p.across, p.end), exit = at(p.across, p.exit),
                runInM = p.runIn, photoLengthM = abs(p.end - p.start), runOutM = p.runOut,
            )
        },
        lineCount = flownLines,
        connectorsM = connectors,
    )
}

/**
 * Totals for a grid.
 * - Photos: a distance trigger (ArduPilot `DO_SET_CAM_TRIGG_DIST`) fires once when it's switched on at the start of a
 *   pass and then every [triggerDistanceM], so a pass of length L takes ⌊L / d⌋ + 1 photos.
 * - Flight time: distance / [speedMs], at survey speed throughout.
 */
fun surveyStats(spec: GridSpec, grid: SurveyGrid, triggerDistanceM: Double, speedMs: Double): SurveyStats {
    require(triggerDistanceM > 0 && speedMs > 0) { "trigger distance and speed must be positive" }
    val distance = grid.passes.sumOf { it.runInM + it.photoLengthM + it.runOutM } + grid.connectorsM.sum()
    return SurveyStats(
        areaM2 = polygonAreaM2(spec.polygon),
        lineCount = grid.lineCount,
        photoCount = grid.passes.sumOf { floor(it.photoLengthM / triggerDistanceM + 1e-9).toInt() + 1 },
        distanceM = distance,
        // ponytail: constant speed, no slow-down in copter turns or wind. Add a per-turn allowance once SITL/field
        // logs show how far off it is.
        flightTimeS = distance / speedMs,
    )
}

/** Polygon area in m², by the shoelace formula on the local flat map. */
fun polygonAreaM2(polygon: List<LatLon>): Double {
    val projection = projectionFor(polygon)
    val p = polygon.map(projection::toLocal)
    return abs(p.indices.sumOf { i -> val a = p[i]; val b = p[(i + 1) % p.size]; a.x * b.y - b.x * a.y }) / 2
}

/**
 * A coordinated (level, balanced) turn's radius: r = v² / (g·tan φ), for true airspeed v and bank angle φ. The
 * textbook result: the lift's sideways part, L·sin φ, supplies the centripetal force m·v²/r while L·cos φ = m·g.
 * Pick φ below the plane's `ROLL_LIMIT_DEG`, so the autopilot has bank to spare for wind.
 */
fun planeTurnRadiusM(airspeedMs: Double, bankDeg: Double): Double {
    require(airspeedMs > 0 && bankDeg > 0 && bankDeg < 90) { "airspeed must be positive and bank between 0 and 90°" }
    return airspeedMs * airspeedMs / (STANDARD_GRAVITY * tan(bankDeg.toRadians()))
}

/**
 * Length of a plane's 180° turn from one line onto a parallel line [lateralM] to the side (flying the opposite way),
 * with turn radius r. These are the shortest (Dubins) paths for that manoeuvre:
 * - lateral ≥ 2r: quarter turn, straight across, quarter turn: π·r + (lateral − 2r).
 * - lateral < 2r: the lines are too close to turn straight onto. The plane swings out the other way first, loops
 *   round and comes back in (a "bulb" or keyhole turn: left–right–left arcs). Its length is r·(π + 4γ), where
 *   cos γ = (lateral + 2r) / 4r. At lateral = 2r this equals π·r, so the two cases meet without a jump.
 * The caller adds any difference in where the two lines end (straight flight along the line).
 */
fun planeTurnM(lateralM: Double, radiusM: Double): Double =
    if (lateralM >= 2 * radiusM) kotlin.math.PI * radiusM + (lateralM - 2 * radiusM)
    else radiusM * (kotlin.math.PI + 4 * acos((lateralM + 2 * radiusM) / (4 * radiusM)))

/** Standard gravity (m/s²), as defined by the CGPM. */
private const val STANDARD_GRAVITY = 9.80665

/**
 * Rejects what the grid can't handle. The antimeridian check: an edge whose longitudes differ by more than 180° is
 * really a short hop across ±180°, where longitudes jump from +180 to -180. Survey areas never legitimately span
 * 180° of longitude, so this can't reject a real area.
 */
internal fun requireSurveyablePolygon(polygon: List<LatLon>) {
    require(polygon.size >= 3) { "a survey area needs at least 3 corners, got ${polygon.size}" }
    polygon.indices.forEach { i ->
        val a = polygon[i]
        val b = polygon[(i + 1) % polygon.size]
        require(abs(b.longitude - a.longitude) <= 180.0) { "the area crosses the antimeridian (180°); split it into two surveys" }
    }
    require(polygonAreaM2(polygon) > 1e-3) { "the area has no size: its corners are on one line" }
}

/** Centred on the corners' average, so no corner is far from where the flat map is most exact. */
private fun projectionFor(polygon: List<LatLon>) =
    LocalProjection(LatLon(polygon.sumOf { it.latitude } / polygon.size, polygon.sumOf { it.longitude } / polygon.size))

/** A point in the grid frame: [across] sideways from the lines, [along] in the line direction (metres). */
internal data class GridPoint(val across: Double, val along: Double)

/** Rotation between the local map (x east, y north) and the grid frame for a grid angle clockwise from north. */
internal class GridFrame(gridAngleDeg: Double) {
    private val c = cos(gridAngleDeg.toRadians())
    private val s = sin(gridAngleDeg.toRadians())

    // along = p·(sin θ, cos θ), the line direction. across = p·(cos θ, −sin θ), the direction 90° to the right of
    // the lines, so "left" in EntryCorner is the smaller across value. For θ = 0: along = north, across = east.
    fun toGrid(p: LocalPoint) = GridPoint(across = p.x * c - p.y * s, along = p.x * s + p.y * c)

    fun fromGrid(across: Double, along: Double) = LocalPoint(x = across * c + along * s, y = -across * s + along * c)
}

/**
 * The parts of the line `across = a` inside the polygon, as (start, end) along-ranges, sorted. Scan-line rule: an
 * edge counts when its two ends are on different sides of the line, with "on the line" counted as below. A vertex
 * exactly on the line is then counted once or not at all, so the crossings always pair up.
 */
internal fun insideSegments(polygon: List<GridPoint>, a: Double): List<Pair<Double, Double>> {
    val crossings = mutableListOf<Double>()
    polygon.indices.forEach { i ->
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        if ((p.across <= a) != (q.across <= a)) {
            crossings += p.along + (a - p.across) / (q.across - p.across) * (q.along - p.along)
        }
    }
    crossings.sort()
    return crossings.chunked(2).filter { it.size == 2 && it[1] - it[0] > 1e-6 }.map { it[0] to it[1] }
}

/** A pass while it's still in grid-frame numbers: entry/start/end/exit are `along` values on line `across`. */
private data class GridPass(
    val line: Int, val across: Double,
    val entry: Double, val start: Double, val end: Double, val exit: Double,
    val runIn: Double, val runOut: Double,
)
