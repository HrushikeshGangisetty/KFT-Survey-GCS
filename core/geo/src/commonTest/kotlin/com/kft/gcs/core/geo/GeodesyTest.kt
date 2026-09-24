package com.kft.gcs.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GeodesyTest {
    // ArduPilot SITL default home (Canberra, CMAC) — the same point our SITL setup uses.
    private val cmac = LatLon(-35.363261, 149.165230)

    @Test
    fun oneDegreeOfLatitudeIsAbout111km() {
        val d = Geodesy.distanceMeters(LatLon(0.0, 0.0), LatLon(1.0, 0.0))
        assertEquals(111_195.0, d, 1.0) // R * pi / 180
    }

    @Test
    fun equatorToPoleIsQuarterCircumference() {
        // Analytic reference: pi/2 * R. Proves the formula, not just self-consistency.
        val expected = kotlin.math.PI / 2 * Geodesy.EARTH_RADIUS_M
        assertEquals(expected, Geodesy.distanceMeters(LatLon(0.0, 10.0), LatLon(90.0, 10.0)), 1e-6)
    }

    @Test
    fun distanceAcrossTheAntimeridianIsShort() {
        // 179.9E to 179.9W on the equator is 0.2 degrees apart, not 359.8.
        val d = Geodesy.distanceMeters(LatLon(0.0, 179.9), LatLon(0.0, -179.9))
        assertEquals(0.2 * kotlin.math.PI / 180 * Geodesy.EARTH_RADIUS_M, d, 1e-6)
    }

    @Test
    fun bearingToTheEastIs90() {
        assertEquals(90.0, Geodesy.initialBearingDeg(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), 1e-9)
    }

    @Test
    fun destinationRoundTripsWithDistanceAndBearing() {
        for (bearing in listOf(0.0, 45.0, 137.0, 270.0)) {
            val p = Geodesy.destination(cmac, bearing, 250.0)
            assertEquals(250.0, Geodesy.distanceMeters(cmac, p), 1e-6)
            assertEquals(bearing, Geodesy.initialBearingDeg(cmac, p), 1e-6)
        }
    }

    @Test
    fun e7ConversionIsLosslessAtMavlinkPrecision() {
        val back = LatLon.fromE7(cmac.latE7, cmac.lonE7)
        assertEquals(cmac.latitude, back.latitude, 1e-7)
        assertEquals(cmac.longitude, back.longitude, 1e-7)
    }

    @Test
    fun invalidLatitudeIsRejected() {
        assertFailsWith<IllegalArgumentException> { LatLon(91.0, 0.0) }
    }
}
