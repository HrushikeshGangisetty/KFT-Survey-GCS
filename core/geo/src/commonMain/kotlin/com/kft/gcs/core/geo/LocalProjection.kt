package com.kft.gcs.core.geo

import com.kft.gcs.core.geo.Geodesy.toRadians
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on a flat local map, in metres: [x] east, [y] north of the projection's origin. */
data class LocalPoint(val x: Double, val y: Double)

/**
 * A flat x/y map around [origin], so survey geometry can use plane maths (lines, rotations, areas) in metres.
 *
 * Equirectangular on the WGS84 ellipsoid: y = M·Δlat, x = N·cos(lat₀)·Δlon, where M (meridional) and N (prime
 * vertical) are the ellipsoid's two radii of curvature at the origin's latitude lat₀. That makes both scales exact at
 * the origin. One mean radius (what this used to be) is about 0.2 % off in each direction at 35°, in opposite senses,
 * which skews long diagonal lines (Pass 21's comparison with QGC's tangent plane).
 *
 * Away from the origin, the east–west scale drifts by about tan(lat)·Δlat: at 35°, 5 km north of the origin, that is
 * 0.05 %; north–south, M changes by under 1 mm per km. Centimetres over a survey area, far below GPS error. Not for
 * areas tens of kilometres across.
 */
class LocalProjection(val origin: LatLon) {
    private val metresPerDegreeNorth: Double
    private val metresPerDegreeEast: Double

    init {
        val lat = origin.latitude.toRadians()
        require(cos(lat) > 1e-6) { "no east–west scale at the poles: ${origin.latitude}" }
        // Radii of curvature, e.g. Snyder, "Map Projections: A Working Manual" (USGS PP 1395, 1987), eq. 4-18 and 4-20.
        val w = 1 - WGS84_E2 * sin(lat) * sin(lat)
        val meridional = WGS84_A * (1 - WGS84_E2) / (w * sqrt(w))
        val primeVertical = WGS84_A / sqrt(w)
        metresPerDegreeNorth = meridional * 1.0.toRadians()
        metresPerDegreeEast = primeVertical * cos(lat) * 1.0.toRadians()
    }

    fun toLocal(p: LatLon): LocalPoint =
        LocalPoint(x = wrap(p.longitude - origin.longitude) * metresPerDegreeEast, y = (p.latitude - origin.latitude) * metresPerDegreeNorth)

    fun toLatLon(p: LocalPoint): LatLon =
        LatLon(origin.latitude + p.y / metresPerDegreeNorth, wrap(origin.longitude + p.x / metresPerDegreeEast))

    /** Longitude difference or longitude into [-180, 180), so 179.9° and -179.9° are 0.2° apart, not 359.8°. */
    private fun wrap(deg: Double) = ((deg + 540.0) % 360.0) - 180.0

    companion object {
        /** WGS84 semi-major axis, metres (NIMA TR8350.2). */
        const val WGS84_A = 6_378_137.0

        /** WGS84 first eccentricity squared, e² = f(2 − f) with f = 1 / 298.257223563. */
        const val WGS84_E2 = 0.0066943799901413165
    }
}
