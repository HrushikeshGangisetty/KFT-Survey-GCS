package com.kft.gcs.core.geoio

/**
 * An ESRI shapefile's geometry (.shp), per the ESRI Shapefile Technical Description (July 1998). Point, MultiPoint,
 * PolyLine and Polygon, including their Z and M variants (whose extra values we skip). Coordinates are reprojected
 * with the `.prj` ([Crs.fromPrj]); without one, only values that are plainly degrees are accepted.
 *
 * ponytail: names from the .dbf aren't read (areas are named after the file). Add a .dbf reader if customers name
 * their fields and want those names on the plan.
 */
internal fun parseShapefile(shp: ByteArray, prj: String?): ImportedShapes {
    val r = Bytes(shp)
    require(shp.size >= 100 && r.intBE(0) == 9994) { "This isn't a shapefile (.shp): its header is wrong." }
    val crs = prj?.let(Crs::fromPrj) ?: Crs.WGS84 // checkRanges() refuses metres read as degrees
    val out = Collector()
    val notes = mutableListOf<String>()
    if (prj == null) notes += "No .prj file came with the shapefile, so its coordinates were read as WGS 84 degrees."
    var at = 100
    while (at + 8 <= shp.size) {
        val length = r.intBE(at + 4) * 2 // in 16-bit words
        val c = at + 8 // record content
        if (c + 4 > shp.size) break
        when (val type = r.intLE(c)) {
            0 -> Unit // null shape
            1, 11, 21 -> out.point(null, crs.toLatLon(r.doubleLE(c + 4), r.doubleLE(c + 12)))
            8, 18, 28 -> {
                val n = r.intLE(c + 36)
                repeat(n) { i -> out.point(null, crs.toLatLon(r.doubleLE(c + 40 + 16 * i), r.doubleLE(c + 48 + 16 * i))) }
            }
            3, 13, 23, 5, 15, 25 -> {
                val parts = r.intLE(c + 36)
                val count = r.intLE(c + 40)
                val starts = List(parts) { r.intLE(c + 44 + 4 * it) } + count
                val pts = c + 44 + 4 * parts
                val rings = List(parts) { p ->
                    (starts[p] until starts[p + 1]).map { i -> r.doubleLE(pts + 16 * i) to r.doubleLE(pts + 16 * i + 8) }
                }
                if (type % 10 == 3) {
                    rings.forEach { ring -> out.line(null, ring.map { (x, y) -> crs.toLatLon(x, y) }) }
                } else {
                    // The spec: outer rings run clockwise, holes counter-clockwise (x east, y north).
                    rings.forEach { ring ->
                        if (signedArea(ring) < 0) out.polygon(null, listOf(ring.map { (x, y) -> crs.toLatLon(x, y) })) else out.hole()
                    }
                }
            }
            else -> throw IllegalArgumentException("Shapefile shape type $type isn't supported (MultiPatch 3D surfaces).")
        }
        at = c + length
    }
    return out.shapes().let { it.copy(notes = it.notes + notes) }
}

/** Shoelace formula: negative for a clockwise ring when y points north. */
private fun signedArea(ring: List<Pair<Double, Double>>): Double =
    ring.indices.sumOf { i ->
        val (x1, y1) = ring[i]
        val (x2, y2) = ring[(i + 1) % ring.size]
        x1 * y2 - x2 * y1
    } / 2

/** A shapefile mixes big-endian (file header, record headers) and little-endian (everything else) numbers. */
private class Bytes(private val b: ByteArray) {
    private fun u(i: Int) = b[i].toInt() and 0xFF
    fun intBE(i: Int) = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
    fun intLE(i: Int) = (u(i + 3) shl 24) or (u(i + 2) shl 16) or (u(i + 1) shl 8) or u(i)
    fun doubleLE(i: Int): Double {
        var bits = 0L
        for (k in 7 downTo 0) bits = (bits shl 8) or u(i + k).toLong()
        return Double.fromBits(bits)
    }
}
