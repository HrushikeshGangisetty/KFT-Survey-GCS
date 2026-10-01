package com.kft.gcs.core.planning

import com.kft.gcs.core.geo.LatLon
import kotlin.math.abs
import kotlin.math.min

/**
 * Cuts a survey area into [parts] pieces for several drones (or several batteries), with every cut **parallel to the
 * flight lines** and **between two lines**, so no line is broken part-way: each piece gets whole lines.
 *
 * ```
 *   grid angle 0, 10 lines, 3 parts            lines:  | | | ¦ | | | | ¦ | | |
 *   (cuts ¦ midway between two lines)          parts:   1     ¦    2    ¦   3
 * ```
 * How:
 * 1. Place the sweep lines exactly as [buildSurveyGrid] does ([sweepLines]) and measure each one's length inside the area.
 * 2. Choose where to cut so the parts have about the same total line length, which is about the same flight time
 *    (an equal number of lines would give one drone the long lines of a triangle and another the short ones).
 *    Boundary j goes after the line where the running length is nearest j/[parts] of the total.
 * 3. Each cut is the midpoint between its two neighbouring lines. Clip the polygon to each strip between cuts
 *    (Sutherland–Hodgman against the two cut lines).
 *
 * Each part is then planned as a survey of its own, which centres its lines in the part. Inside a part that is `k`
 * spacings wide the lines land where they were. In the two end parts they move by at most a quarter spacing towards
 * the cut, so the gap across a cut is between 0.75 and 1 spacing: the side overlap there is the chosen one or more,
 * never less.
 *
 * Fewer lines than [parts] gives one part per line. A concave area can come out with a zero-width bridge along a cut
 * where a notch crosses it; the grid's scan-line clipping ignores those.
 *
 * @throws IllegalArgumentException for what [buildSurveyGrid] rejects, or [parts] < 1.
 */
fun splitArea(polygon: List<LatLon>, gridAngleDeg: Double, lineSpacingM: Double, parts: Int): List<List<LatLon>> {
    requireSurveyablePolygon(polygon)
    require(parts >= 1) { "parts must be at least 1: $parts" }
    val projection = projectionFor(polygon)
    val frame = GridFrame(gridAngleDeg)
    val corners = polygon.map { frame.toGrid(projection.toLocal(it)) }
    val lines = sweepLines(corners, lineSpacingM)
    val count = min(parts, lines.size)
    if (count == 1) return listOf(polygon)

    // 2. Running line length after each line; boundary j after the line whose running length is nearest j/count of it.
    val running = lines.runningFold(0.0) { sum, across -> sum + insideSegments(corners, across).sumOf { (a, b) -> b - a } }.drop(1)
    val total = running.last()
    var previous = 0 // how many lines the parts so far have taken
    val cuts = (1 until count).map { j ->
        // Leave at least one line for this part and one for each part still to come.
        val k = (previous + 1..lines.size - (count - j)).minBy { abs(running[it - 1] - total * j / count) }
        previous = k
        (lines[k - 1] + lines[k]) / 2
    }

    // 3. Strip by strip.
    val bounds = listOf(Double.NEGATIVE_INFINITY) + cuts + Double.POSITIVE_INFINITY
    return bounds.zipWithNext { lo, hi ->
        clip(clip(corners, lo, keepAbove = true), hi, keepAbove = false)
            .map { projection.toLatLon(frame.fromGrid(it.across, it.along)) }
    }.filter { it.size >= 3 }
}

/**
 * The part of a survey area whose lines are still to be flown, when the first [fromLine] lines (counted from the
 * [entry] corner's side, as [Pass.line] counts them) are done: the area from midway between line [fromLine] − 1 and
 * line [fromLine] to the far edge. For planning the rest again with different settings (another camera).
 *
 * The cut is half a spacing before the first unflown line, exactly where the done lines' share of the area ends, so
 * a new grid over the rest starts no more than half of its own spacing from it: no gap at the hand-over.
 *
 * Null when nothing is left ([fromLine] ≥ the number of lines); the whole area when nothing is done.
 */
fun remainingArea(polygon: List<LatLon>, gridAngleDeg: Double, lineSpacingM: Double, entry: EntryCorner, fromLine: Int): List<LatLon>? {
    requireSurveyablePolygon(polygon)
    if (fromLine <= 0) return polygon
    val projection = projectionFor(polygon)
    val frame = GridFrame(gridAngleDeg)
    val corners = polygon.map { frame.toGrid(projection.toLocal(it)) }
    val lines = sweepLines(corners, lineSpacingM)
    if (fromLine >= lines.size) return null
    val fromRight = entry == EntryCorner.BOTTOM_RIGHT || entry == EntryCorner.TOP_RIGHT
    val rest = if (fromRight) clip(corners, lines[lines.lastIndex - fromLine] + lineSpacingM / 2, keepAbove = false)
    else clip(corners, lines[fromLine] - lineSpacingM / 2, keepAbove = true)
    return rest.map { projection.toLatLon(frame.fromGrid(it.across, it.along)) }.takeIf { it.size >= 3 }
}

/**
 * Sutherland–Hodgman against one line `across = at`: keeps the side above it ([keepAbove]) or below, adding the
 * points where edges cross the line. An infinite [at] keeps everything.
 */
private fun clip(polygon: List<GridPoint>, at: Double, keepAbove: Boolean): List<GridPoint> {
    if (at.isInfinite()) return polygon
    fun inside(p: GridPoint) = if (keepAbove) p.across >= at else p.across <= at
    val out = mutableListOf<GridPoint>()
    polygon.indices.forEach { i ->
        val p = polygon[i]
        val q = polygon[(i + 1) % polygon.size]
        if (inside(p)) out += p
        if (inside(p) != inside(q)) out += GridPoint(at, p.along + (at - p.across) / (q.across - p.across) * (q.along - p.along))
    }
    return out
}
