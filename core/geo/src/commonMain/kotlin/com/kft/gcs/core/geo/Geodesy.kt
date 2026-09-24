package com.kft.gcs.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical-earth geodesy. Accurate to ~0.3 % over survey-sized areas, which is far below GPS and
 * planning tolerances. Survey maths will use a local flat projection (next milestone) built on this.
 */
object Geodesy {
    /** Mean earth radius (IUGG), metres. */
    const val EARTH_RADIUS_M = 6_371_008.8

    fun Double.toRadians() = this * PI / 180.0
    fun Double.toDegrees() = this * 180.0 / PI

    /** Great-circle distance in metres (haversine formula). */
    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = (b.latitude - a.latitude).toRadians()
        val dLon = (b.longitude - a.longitude).toRadians()
        val h = sin(dLat / 2).let { it * it } +
            cos(a.latitude.toRadians()) * cos(b.latitude.toRadians()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from true north, in [0, 360). */
    fun initialBearingDeg(a: LatLon, b: LatLon): Double {
        val phi1 = a.latitude.toRadians()
        val phi2 = b.latitude.toRadians()
        val dLon = (b.longitude - a.longitude).toRadians()
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (atan2(y, x).toDegrees() + 360.0) % 360.0
    }

    /** Point reached by travelling [distanceM] metres from [start] on [bearingDeg]. */
    fun destination(start: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = bearingDeg.toRadians()
        val phi1 = start.latitude.toRadians()
        val lambda1 = start.longitude.toRadians()
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda2 = lambda1 + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        val lon = ((lambda2.toDegrees() + 540.0) % 360.0) - 180.0 // normalise to [-180, 180)
        return LatLon(phi2.toDegrees(), lon)
    }
}
