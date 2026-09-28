package com.kft.gcs.core.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** An ellipsoid by its semi-major axis [a] (metres) and flattening [f]. */
data class Ellipsoid(val a: Double, val f: Double) {
    /** First eccentricity squared, e² = f(2 − f). */
    val e2: Double get() = f * (2 - f)

    companion object {
        val WGS84 = Ellipsoid(6_378_137.0, 1 / 298.257223563)
        /** GRS 80 (ETRS89, NAD83): differs from WGS 84 by 0.1 mm in the semi-minor axis. */
        val GRS80 = Ellipsoid(6_378_137.0, 1 / 298.257222101)
    }
}

/**
 * Projected x/y (metres) back to latitude/longitude: what an imported file's coordinate system needs. Only the
 * inverse exists: nothing in the app projects *to* a file's system.
 */
sealed interface Projection {
    fun toLatLon(x: Double, y: Double): LatLon
}

/**
 * Transverse Mercator on an ellipsoid (UTM and most national grids). Inverse formulas from J. P. Snyder, *Map
 * Projections: A Working Manual* (USGS PP 1395, 1987), eqs. 8-18 to 8-25 and 3-21 (meridian distance). Accurate to
 * a few millimetres within a UTM zone (±3° of the central meridian), far below GPS error.
 */
data class TransverseMercator(
    val centralMeridianDeg: Double,
    val latitudeOfOriginDeg: Double = 0.0,
    val scale: Double = 0.9996,
    val falseEasting: Double = 500_000.0,
    val falseNorthing: Double = 0.0,
    val ellipsoid: Ellipsoid = Ellipsoid.WGS84,
) : Projection {

    override fun toLatLon(x: Double, y: Double): LatLon {
        val a = ellipsoid.a
        val e2 = ellipsoid.e2
        val ep2 = e2 / (1 - e2) // second eccentricity squared, e′²
        val m = meridianDistance(latitudeOfOriginDeg.toRad()) + (y - falseNorthing) / scale
        val mu = m / (a * (1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * e2.pow(3) / 256))
        val e1 = (1 - sqrt(1 - e2)) / (1 + sqrt(1 - e2))
        // The footpoint latitude: the latitude on the central meridian with the same meridian distance.
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) +
            (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val s = sin(phi1)
        val c1 = ep2 * cos(phi1).pow(2)
        val t1 = tan(phi1).pow(2)
        val n1 = a / sqrt(1 - e2 * s * s)
        val r1 = a * (1 - e2) / (1 - e2 * s * s).pow(1.5)
        val d = (x - falseEasting) / (n1 * scale)
        val phi = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 -
                (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lambda = (
            d - (1 + 2 * t1 + c1) * d.pow(3) / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d.pow(5) / 120
            ) / cos(phi1)
        return LatLon(phi.toDeg(), centralMeridianDeg + lambda.toDeg())
    }

    /** Distance along the meridian from the equator to latitude [phi] (Snyder 3-21). */
    private fun meridianDistance(phi: Double): Double {
        val e2 = ellipsoid.e2
        val e4 = e2 * e2
        val e6 = e4 * e2
        return ellipsoid.a * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    companion object {
        /** UTM [zone] (1–60), north or south of the equator (the south adds 10 000 km false northing). */
        fun utm(zone: Int, north: Boolean) = TransverseMercator(
            centralMeridianDeg = zone * 6.0 - 183.0,
            falseNorthing = if (north) 0.0 else 10_000_000.0,
        )
    }
}

/**
 * "Web Mercator" (EPSG:3857, what web maps and some exports use): spherical Mercator on a sphere of radius 6 378 137 m.
 * Inverse: λ = x / R, φ = 2·atan(e^(y/R)) − π/2 (EPSG Guidance Note 7-2, method 1024).
 */
data object WebMercator : Projection {
    private const val R = 6_378_137.0
    override fun toLatLon(x: Double, y: Double) = LatLon((2 * atan(exp(y / R)) - PI / 2).toDeg(), (x / R).toDeg())
}

/** Plain longitude/latitude in degrees (x = longitude): no projection. */
data object Geographic : Projection {
    override fun toLatLon(x: Double, y: Double) = LatLon(y, x)
}

private fun Double.toRad() = this * PI / 180
private fun Double.toDeg() = this * 180 / PI
