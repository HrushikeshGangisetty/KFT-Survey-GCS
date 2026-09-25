package com.kft.gcs.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalProjectionTest {

    /**
     * Hand value: 0.001° of latitude on the mean-radius sphere = 6 371 008.8 m × 0.001 × π/180 = 111.1951 m.
     * At the equator a degree of longitude is the same length.
     */
    @Test
    fun metresPerDegreeAtTheEquator() {
        val p = LocalProjection(LatLon(0.0, 0.0))
        assertEquals(111.1951, p.toLocal(LatLon(0.001, 0.0)).y, 1e-4)
        assertEquals(111.1951, p.toLocal(LatLon(0.0, 0.001)).x, 1e-4)
        assertEquals(-111.1951, p.toLocal(LatLon(0.0, -0.001)).x, 1e-4)
    }

    /** At 60° a degree of longitude is half as long: cos 60° = 0.5, so 111.1951 / 2 = 55.5975 m. */
    @Test
    fun eastWestScaleShrinksWithLatitude() {
        val p = LocalProjection(LatLon(60.0, 10.0))
        assertEquals(55.5975, p.toLocal(LatLon(60.0, 10.001)).x, 1e-4)
    }

    /**
     * Agrees with the great-circle distance over a kilometre at CMAC (35° S), within the error the KDoc predicts.
     * Worst of these cases, bearing 45°: the point is 707 m north, so the east–west scale is off by about
     * tan 35.4° × (707 / 6 371 009) / 2 = 3.9e-5 on average over the 707 m east leg, 2.8 cm, of which about 2 cm shows
     * in the distance. Due north/south is exact (y is the meridian arc), due east is exact at the origin's latitude.
     */
    @Test
    fun matchesHaversineOverSurveyDistances() {
        val origin = LatLon(-35.363261, 149.165230)
        val p = LocalProjection(origin)
        for ((bearing, tolerance) in listOf(0.0 to 1e-6, 90.0 to 1e-3, 45.0 to 0.03, 200.0 to 0.03)) {
            val far = Geodesy.destination(origin, bearing, 1000.0)
            val local = p.toLocal(far)
            assertEquals(1000.0, kotlin.math.hypot(local.x, local.y), tolerance, "bearing $bearing")
        }
    }

    @Test
    fun roundTrip() {
        val p = LocalProjection(LatLon(-35.36, 149.16))
        val back = p.toLatLon(LocalPoint(123.4, -567.8))
        assertEquals(LocalPoint(123.4, -567.8).x, p.toLocal(back).x, 1e-6)
        assertEquals(LocalPoint(123.4, -567.8).y, p.toLocal(back).y, 1e-6)
    }

    /** Across ±180°: 179.9° E to 179.9° W is 0.2° east = 22 239.0 m (0.2 × 111 195.08), not 359.8° west. */
    @Test
    fun longitudeWrapsAtTheAntimeridian() {
        val p = LocalProjection(LatLon(0.0, 179.9))
        assertEquals(22239.0, p.toLocal(LatLon(0.0, -179.9)).x, 0.1)
        assertEquals(-179.9, p.toLatLon(LocalPoint(22239.016, 0.0)).longitude, 1e-6)
    }
}
