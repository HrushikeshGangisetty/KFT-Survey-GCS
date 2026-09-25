package com.kft.gcs.core.geo

import com.kft.gcs.core.geo.Geodesy.toDegrees
import com.kft.gcs.core.geo.Geodesy.toRadians
import kotlin.math.cos

/** A point on a flat local map, in metres: [x] east, [y] north of the projection's origin. */
data class LocalPoint(val x: Double, val y: Double)

/**
 * A flat x/y map around [origin], so survey geometry can use plane maths (lines, rotations, areas) in metres.
 *
 * Equirectangular: y = R·Δlat, x = R·Δlon·cos(origin latitude), on the same sphere as [Geodesy]. North–south
 * distances are exact on that sphere. East–west, the scale is right at the origin's latitude and drifts by about
 * tan(lat)·Δlat away from it: at 35° latitude, 5 km north of the origin, that is 0.05 %. That's centimetres over a
 * survey area, far below GPS error. Not for areas tens of kilometres across.
 */
class LocalProjection(val origin: LatLon) {
    private val metresPerDegree = Geodesy.EARTH_RADIUS_M * 1.0.toRadians()
    private val cosLat = cos(origin.latitude.toRadians())

    init {
        require(cosLat > 1e-6) { "no east–west scale at the poles: ${origin.latitude}" }
    }

    fun toLocal(p: LatLon): LocalPoint =
        LocalPoint(x = wrap(p.longitude - origin.longitude) * metresPerDegree * cosLat, y = (p.latitude - origin.latitude) * metresPerDegree)

    fun toLatLon(p: LocalPoint): LatLon =
        LatLon(origin.latitude + p.y / metresPerDegree, wrap(origin.longitude + p.x / (metresPerDegree * cosLat)))

    /** Longitude difference or longitude into [-180, 180), so 179.9° and -179.9° are 0.2° apart, not 359.8°. */
    private fun wrap(deg: Double) = ((deg + 540.0) % 360.0) - 180.0
}
