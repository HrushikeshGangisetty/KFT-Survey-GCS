package com.kft.gcs.core.geo

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalProjectionTest {

    /**
     * Published WGS84 lengths of a degree at the equator: latitude 110.574 km, longitude 111.320 km (e.g. the
     * "Length of a degree" table in Wikipedia's "Latitude" article, from the WGS84 radii of curvature). By hand:
     * M(0) = a(1 − e²) = 6 335 439.33 m → 110 574.28 m per degree; N(0) = a = 6 378 137 m → 111 319.49 m.
     * The old single-radius map gave 111.195 for both, 0.56 % long north–south.
     */
    @Test
    fun metresPerDegreeAtTheEquator() {
        val p = LocalProjection(LatLon(0.0, 0.0))
        assertEquals(110.5743, p.toLocal(LatLon(0.001, 0.0)).y, 1e-4)
        assertEquals(111.3195, p.toLocal(LatLon(0.0, 0.001)).x, 1e-4)
        assertEquals(-111.3195, p.toLocal(LatLon(0.0, -0.001)).x, 1e-4)
    }

    /** Same table at 60°: latitude 111.412 km, longitude 55.800 km per degree. */
    @Test
    fun scalesAt60Degrees() {
        val p = LocalProjection(LatLon(60.0, 10.0))
        assertEquals(111.412, p.toLocal(LatLon(60.001, 10.0)).y, 1e-3)
        assertEquals(55.800, p.toLocal(LatLon(60.0, 10.001)).x, 1e-3)
    }

    /**
     * North–south against the true meridian arc at CMAC (35° S): 0.01° of latitude (1.1 km). The reference is the arc
     * length ∫M(φ)dφ, integrated here with Simpson's rule (100 steps), not the projection's formula: 1109.4715 m. The
     * projection uses M at the origin only, so it's off by (dM/dφ)·Δφ²/2 ≈ 6·10⁴ m × 1.5·10⁻⁸ ≈ 0.9 mm.
     */
    @Test
    fun northSouthMatchesTheMeridianArc() {
        val origin = LatLon(-35.363261, 149.165230)
        val y = LocalProjection(origin).toLocal(LatLon(origin.latitude + 0.01, origin.longitude)).y

        val a = LocalProjection.WGS84_A
        val e2 = LocalProjection.WGS84_E2
        fun m(phi: Double) = a * (1 - e2) / (1 - e2 * sin(phi).pow(2)).pow(1.5)
        val phi0 = origin.latitude * PI / 180
        val phi1 = (origin.latitude + 0.01) * PI / 180
        val n = 100
        val h = (phi1 - phi0) / n
        val arc = h / 3 * (m(phi0) + m(phi1) + (1 until n).sumOf { (if (it % 2 == 1) 4 else 2) * m(phi0 + it * h) })

        assertEquals(1109.4715, arc, 1e-4) // the reference itself, by an independent (Python) integration
        assertEquals(arc, y, 2e-3)
    }

    @Test
    fun roundTrip() {
        val p = LocalProjection(LatLon(-35.36, 149.16))
        val back = p.toLatLon(LocalPoint(123.4, -567.8))
        assertEquals(LocalPoint(123.4, -567.8).x, p.toLocal(back).x, 1e-6)
        assertEquals(LocalPoint(123.4, -567.8).y, p.toLocal(back).y, 1e-6)
    }

    /** Across ±180°: 179.9° E to 179.9° W is 0.2° east = 22 263.9 m at the equator (0.2 × 111 319.49), not 359.8° west. */
    @Test
    fun longitudeWrapsAtTheAntimeridian() {
        val p = LocalProjection(LatLon(0.0, 179.9))
        assertEquals(22263.9, p.toLocal(LatLon(0.0, -179.9)).x, 0.1)
        assertEquals(-179.9, p.toLatLon(LocalPoint(22263.898, 0.0)).longitude, 1e-6)
    }
}
