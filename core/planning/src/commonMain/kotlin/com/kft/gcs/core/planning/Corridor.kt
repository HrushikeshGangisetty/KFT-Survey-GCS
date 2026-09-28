package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.LocalPoint
import com.kft.gcs.core.geo.LocalProjection
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A corridor: a strip [leftWidthM] to the left and [rightWidthM] to the right of a centre line (left/right as seen
 * flying from its first point to its last), covered by [lineCount] lines that follow the centre line's shape.
 * [includeCentreLine]: one of the lines runs exactly on the centre line (a road or pipe itself, photographed from
 * straight above); otherwise the lines split the width into equal strips.
 */
data class CorridorShape(
    val leftWidthM: Double,
    val rightWidthM: Double,
    val lineCount: Int,
    val includeCentreLine: Boolean,
) {
    init {
        require(leftWidthM >= 0 && rightWidthM >= 0 && leftWidthM + rightWidthM > 0) { "the corridor needs a width" }
        require(lineCount in 1..MAX_CORRIDOR_LINES) { "a corridor has 1 to $MAX_CORRIDOR_LINES lines" }
    }
}

internal const val MAX_CORRIDOR_LINES = 50

/**
 * Where the lines sit across the corridor, as signed offsets from the centre line (negative = left), left to right,
 * and the spacing between neighbours. Each line photographs a strip one spacing wide, centred on it.
 * - Without the centre line: n equal strips tile the width exactly, s = (L + R) / n, offsets −L + s·(i + ½).
 * - With it: lines at k·s for k = −a…b (a + b + 1 = n), where a lines go left and b right. The outer strips must
 *   reach the edges: a·s + s/2 ≥ L and b·s + s/2 ≥ R, so s = max(L / (a + ½), R / (b + ½)). The a that gives the
 *   smallest s is used: the most overlap for the same number of lines.
 */
fun corridorOffsets(shape: CorridorShape): Pair<List<Double>, Double> {
    val l = shape.leftWidthM
    val r = shape.rightWidthM
    val n = shape.lineCount
    if (!shape.includeCentreLine) {
        val s = (l + r) / n
        return List(n) { -l + s * (it + 0.5) } to s
    }
    val (a, s) = (0 until n).map { a -> a to maxOf(l / (a + 0.5), r / (n - 1 - a + 0.5)) }.minBy { it.second }
    return List(n) { (it - a) * s } to s
}

/**
 * The corridor's flight lines, in flight order: the leftmost first, flown from the centre line's first end, and then
 * back and forth (a plane skips lines as [buildSurveyGrid] does, [planeLineOrder]). Each line is the centre line
 * offset sideways ([offsetLine]), extended straight at both ends by the run-in/run-out. The same [SurveyGrid] as an
 * area survey, so stats, mission items and the map treat both alike; the bends are in [Pass.via].
 *
 * @throws IllegalArgumentException for fewer than 2 distinct points, or a bend sharper than 120° (the offset lines
 *   would fold over themselves there; add a point to round the bend).
 */
fun buildCorridorGrid(centreLine: List<LatLon>, shape: CorridorShape, turnaround: Turnaround): SurveyGrid {
    val projection = LocalProjection(LatLon(centreLine.sumOf { it.latitude } / centreLine.size, centreLine.sumOf { it.longitude } / centreLine.size))
    val centre = centreLine.map(projection::toLocal).fold(emptyList<LocalPoint>()) { acc, p ->
        if (acc.isNotEmpty() && hypot(p.x - acc.last().x, p.y - acc.last().y) < 0.01) acc else acc + p // drop repeated points
    }
    require(centre.size >= 2) { "a corridor needs a centre line of at least 2 points" }
    val (offsets, spacing) = corridorOffsets(shape)

    val (runIn, runOut) = when (turnaround) {
        is Turnaround.Copter -> turnaround.extensionM to turnaround.extensionM
        is Turnaround.Plane -> turnaround.leadInM to turnaround.leadOutM
    }
    val firstRunIn = (turnaround as? Turnaround.Plane)?.firstLeadInM ?: runIn
    val skip = (turnaround as? Turnaround.Plane)?.let { minLineSkip(spacing, it.turnRadiusM) } ?: 1
    val skipped = planeLineOrder(offsets.size, skip)
    val order = skipped ?: offsets.indices.toList()

    val passes = order.mapIndexed { k, i ->
        val forward = offsetLine(centre, offsets[i])
        val line = if (k % 2 == 0) forward else forward.reversed()
        val inM = if (k == 0) firstRunIn else runIn
        val entry = extend(line[1], line[0], inM)
        val exit = extend(line[line.lastIndex - 1], line.last(), runOut)
        Pass(
            line = k,
            entry = projection.toLatLon(entry),
            photoStart = projection.toLatLon(line.first()),
            photoEnd = projection.toLatLon(line.last()),
            exit = projection.toLatLon(exit),
            runInM = inM,
            photoLengthM = line.zipWithNext { p, q -> hypot(q.x - p.x, q.y - p.y) }.sum(),
            runOutM = runOut,
            via = line.subList(1, line.lastIndex).map(projection::toLatLon),
        )
    }
    // Neighbouring passes end side by side (both lines end square to the centre line's end), so the step between them
    // is the offset difference: a copter hops straight across, a plane makes the 180° turn [planeTurnM] measures.
    val steps = order.zipWithNext { i, j -> abs(offsets[j] - offsets[i]) }
    val connectors = steps.map { step -> if (turnaround is Turnaround.Plane) planeTurnM(step, turnaround.turnRadiusM) else step }
    val loopTurns = if (turnaround is Turnaround.Plane) steps.count { it < 2 * turnaround.turnRadiusM - 1e-9 } else 0
    return SurveyGrid(passes, offsets.size, connectors, lineSkip = if (skipped == null) 1 else skip, loopTurns = loopTurns)
}

/**
 * The centre line moved [offsetM] to the side (positive = right), as a parallel line: each segment moves along its
 * right-hand normal, and at a bend the two moved segments meet on the bend's bisector ("miter" join), at
 * offset / cos(half the turn) from the vertex.
 */
internal fun offsetLine(line: List<LocalPoint>, offsetM: Double): List<LocalPoint> {
    val normals = line.zipWithNext { p, q ->
        val len = hypot(q.x - p.x, q.y - p.y)
        LocalPoint((q.y - p.y) / len, -(q.x - p.x) / len) // (dy, −dx)/len: 90° clockwise, to the right
    }
    return line.indices.map { i ->
        val n = when (i) {
            0 -> normals.first()
            line.lastIndex -> normals.last()
            else -> {
                val a = normals[i - 1]
                val b = normals[i]
                val m = LocalPoint(a.x + b.x, a.y + b.y).let { val l = hypot(it.x, it.y); LocalPoint(it.x / l, it.y / l) }
                val cosHalf = m.x * b.x + m.y * b.y
                // cos(θ/2) ≥ 0.5 ⇔ the turn θ ≤ 120°. Sharper, and the miter point runs away (∞ at 180°).
                require(cosHalf >= 0.5 - 1e-9) { "the corridor bends more than 120° at point ${i + 1}: add a point to round the bend" }
                LocalPoint(m.x / cosHalf, m.y / cosHalf)
            }
        }
        LocalPoint(line[i].x + n.x * offsetM, line[i].y + n.y * offsetM)
    }
}

/** [to] moved [distanceM] further along the direction from [from] to [to]. */
private fun extend(from: LocalPoint, to: LocalPoint, distanceM: Double): LocalPoint {
    val len = hypot(to.x - from.x, to.y - from.y)
    return LocalPoint(to.x + (to.x - from.x) / len * distanceM, to.y + (to.y - from.y) / len * distanceM)
}

/** The corridor's area: its centre line's length times its width (bends counted by the miter, near enough). */
internal fun corridorAreaM2(centreLine: List<LatLon>, shape: CorridorShape): Double {
    val projection = LocalProjection(centreLine.first())
    val pts = centreLine.map(projection::toLocal)
    return pts.zipWithNext { p, q -> hypot(q.x - p.x, q.y - p.y) }.sum() * (shape.leftWidthM + shape.rightWidthM)
}

/**
 * The corridor's edges as one closed outline, for the map: the left edge from the first point to the last, then the
 * right edge back. Null while there's no centre line yet (under 2 points) or it can't be offset (a hairpin bend).
 */
fun corridorOutline(centreLine: List<LatLon>, shape: CorridorShape): List<LatLon>? {
    if (centreLine.size < 2) return null
    val projection = LocalProjection(centreLine.first())
    val line = centreLine.map(projection::toLocal)
    return runCatching {
        (offsetLine(line, -shape.leftWidthM) + offsetLine(line, shape.rightWidthM).reversed()).map(projection::toLatLon)
    }.getOrNull()
}
