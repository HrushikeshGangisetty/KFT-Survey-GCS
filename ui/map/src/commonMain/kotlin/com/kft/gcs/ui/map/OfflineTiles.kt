package com.kft.gcs.ui.map

import com.kft.gcs.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A latitude/longitude box, such as the part of the map on screen. [west] < [east]: it doesn't cross ±180°. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    /** The four corners, for drawing the box as a [MapOverlay.Polygon]. */
    val corners: List<LatLon> get() = listOf(LatLon(south, west), LatLon(north, west), LatLon(north, east), LatLon(south, east))
}

/** What the map is showing: the box on screen and the zoom level. Reported by [MapView] when the camera stops. */
data class MapViewport(val bounds: GeoBounds, val zoom: Double)

/**
 * Web Mercator tile maths ("slippy map" tiles, the scheme every tile server here uses), for the estimate shown
 * before a download. Reference: OpenStreetMap wiki, "Slippy map tilenames" (lon/lat → tile numbers).
 */
object TileMath {
    /** Web Mercator stops here: the latitude at which the square world map ends (atan(sinh π)). */
    private const val MAX_LAT = 85.05112878

    /** The column of the tile holding [lon] at zoom [z]: x = ⌊(lon + 180) / 360 · 2^z⌋. */
    fun tileX(lon: Double, z: Int): Int = clampTile(floor((lon + 180.0) / 360.0 * (1 shl z)).toInt(), z)

    /** The row of the tile holding [lat] at zoom [z], counted from the north: y = ⌊(1 − ln(tan φ + sec φ) / π) / 2 · 2^z⌋. */
    fun tileY(lat: Double, z: Int): Int {
        val phi = lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0
        val y = (1.0 - ln(tan(phi) + 1.0 / kotlin.math.cos(phi)) / PI) / 2.0 * (1 shl z)
        return clampTile(floor(y).toInt(), z)
    }

    /** How many tiles cover [bounds] at every zoom in [zooms]. Each zoom is a rectangle of tiles, columns × rows. */
    fun tileCount(bounds: GeoBounds, zooms: IntRange): Long = zooms.sumOf { z ->
        val columns = tileX(bounds.east, z) - tileX(bounds.west, z) + 1
        val rows = tileY(bounds.south, z) - tileY(bounds.north, z) + 1 // y grows southwards
        columns.toLong() * rows
    }

    /**
     * Tiles a download of [source] over [bounds] and [zooms] fetches. A vector style has no tiles past its
     * `tileMaxZoom` (closer zooms reuse them), so those zooms cost nothing; raster sources stop at their `maxZoom`.
     */
    fun downloadTiles(source: TileSourceConfig.Source, bounds: GeoBounds, zooms: IntRange): Long {
        val deepest = when (source) {
            is TileSourceConfig.Source.VectorStyle -> source.tileMaxZoom
            is TileSourceConfig.Source.RasterTiles -> source.maxZoom
            is TileSourceConfig.Source.Mbtiles -> return 0 // already on the device
        }
        val last = minOf(zooms.last, deepest)
        return if (last < zooms.first) 0 else tileCount(bounds, zooms.first..last)
    }

    // The east edge at exactly +180° computes to column 2^z, one past the last; the same for the south pole row.
    private fun clampTile(i: Int, z: Int) = i.coerceIn(0, (1 shl z) - 1)
}
