package com.kft.gcs.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every expected value here comes from outside our code: Snyder's printed worked example, or PROJ 9 (pyproj),
 * an independent implementation (Karney's series), run once and pasted in with the command that produced it.
 */
class ProjectionsTest {
    /** 1e-7° is about 1 cm on the ground. */
    private fun assertNear(expected: LatLon, actual: LatLon, tolDeg: Double = 1e-7) {
        assertEquals(expected.latitude, actual.latitude, tolDeg, "latitude")
        assertEquals(expected.longitude, actual.longitude, tolDeg, "longitude")
    }

    /**
     * Snyder (1987), Appendix A, the worked Transverse Mercator example on the Clarke 1866 ellipsoid (a = 6 378 206.4 m,
     * e² = 0.00676866), k₀ = 0.9996, central meridian 75° W: x = 127 106.5 m, y = 4 484 124.4 m is 40°30′ N, 73°30′ W.
     * The book prints x and y to 0.1 m, so the check is to 1e-6° (≈ 10 cm).
     */
    @Test
    fun snyderWorkedExample() {
        val clarke1866 = Ellipsoid(6_378_206.4, 1 / 294.9786982)
        val tm = TransverseMercator(centralMeridianDeg = -75.0, falseEasting = 0.0, ellipsoid = clarke1866)
        assertNear(LatLon(40.5, -73.5), tm.toLatLon(127_106.5, 4_484_124.4), tolDeg = 1e-6)
    }

    /**
     * UTM on WGS 84 against PROJ 9.x, e.g.
     * `Transformer.from_crs("EPSG:32643", "EPSG:4326", always_xy=True).transform(700123.4, 2100456.7)`.
     */
    @Test
    fun utmAgainstProj() {
        // Zone 43N (Hyderabad area): on the central meridian, and 200 km east of it.
        assertNear(LatLon(17.184811575, 75.0), TransverseMercator.utm(43, north = true).toLatLon(500_000.0, 1_900_000.0))
        assertNear(LatLon(18.986884817, 76.900986161), TransverseMercator.utm(43, north = true).toLatLon(700_123.4, 2_100_456.7))
        // Zone 55S (CMAC, the SITL home): south hemisphere false northing.
        assertNear(LatLon(-35.377679892, 149.124677701), TransverseMercator.utm(55, north = false).toLatLon(693_000.0, 6_083_000.0))
        // Zone 30N, 200 km west of the central meridian, at 51° N.
        assertNear(LatLon(51.415883589, -5.876291768), TransverseMercator.utm(30, north = true).toLatLon(300_000.0, 5_700_000.0))
    }

    /** PROJ: EPSG:3857 (16 605 000, −4 213 000) → 149.165252928° E, 35.360644948° S. */
    @Test
    fun webMercatorAgainstProj() {
        assertNear(LatLon(-35.360644948, 149.165252928), WebMercator.toLatLon(16_605_000.0, -4_213_000.0))
    }

    @Test
    fun geographicIsLonLat() {
        assertEquals(LatLon(-35.3, 149.1), Geographic.toLatLon(149.1, -35.3))
    }
}
