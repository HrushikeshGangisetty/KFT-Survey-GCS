package com.kft.gcs.spikes.mapspike

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import org.maplibre.spatialk.geojson.Position

// Pure helpers for check M3 (polygon editing). They work in screen space (dp), because "did the finger
// land on a handle?" is a question about what the user sees, not about metres on the ground.

/**
 * Returns the index of the handle nearest to [touch] if it is within [radius], else null.
 * A null entry in [handles] is a vertex that is currently off screen, so it can't be hit.
 */
fun hitVertex(touch: DpOffset, handles: List<DpOffset?>, radius: Dp): Int? =
    handles.withIndex()
        .mapNotNull { (i, h) -> h?.let { i to distance(touch, it) } }
        .filter { (_, d) -> d <= radius.value }
        .minByOrNull { (_, d) -> d }
        ?.first

/**
 * Where a new vertex at [point] should go in the closed ring [ring]: the index to insert at, chosen as
 * the edge nearest to the point. Appending at the end instead would draw a line across the polygon
 * whenever the user long-presses near any other edge. Same idea as QGC's "insert on nearest edge".
 */
fun insertionIndex(point: DpOffset, ring: List<DpOffset>): Int {
    if (ring.size < 2) return ring.size
    val nearestEdge = ring.indices.minBy { i -> distanceToSegment(point, ring[i], ring[(i + 1) % ring.size]) }
    return nearestEdge + 1
}

/** GeoJSON for a closed polygon. GeoJSON rings must repeat the first position at the end (RFC 7946 §3.1.6). */
fun polygonGeoJson(ring: List<Position>): String {
    // listOf(...) matters: spatialk's Position is itself a List<Double>, so `ring + ring.first()` would append two numbers.
    val coords = (ring + listOf(ring.first())).joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":[[$coords]]}}"""
}

/** GeoJSON MultiPoint, used for the vertex handles and the fake vehicle. */
fun pointsGeoJson(points: List<Position>): String {
    val coords = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","properties":{},"geometry":{"type":"MultiPoint","coordinates":[$coords]}}"""
}

private fun distance(a: DpOffset, b: DpOffset): Float {
    val dx = a.x.value - b.x.value
    val dy = a.y.value - b.y.value
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/** Distance from [p] to the segment [a]–[b]: project p onto the line, clamp to the segment, measure. */
private fun distanceToSegment(p: DpOffset, a: DpOffset, b: DpOffset): Float {
    val abx = b.x.value - a.x.value
    val aby = b.y.value - a.y.value
    val lengthSquared = abx * abx + aby * aby
    if (lengthSquared == 0f) return distance(p, a)
    val t = (((p.x.value - a.x.value) * abx + (p.y.value - a.y.value) * aby) / lengthSquared).coerceIn(0f, 1f)
    return distance(p, DpOffset(Dp(a.x.value + t * abx), Dp(a.y.value + t * aby)))
}
