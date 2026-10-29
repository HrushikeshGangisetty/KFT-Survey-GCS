package com.kft.gcs.core.terrain

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals

class ElevationsTest {
    @Test
    fun requestListsLatitudesThenLongitudes() {
        // The documented example of the Open-Meteo elevation API: latitude=52.52,48.85&longitude=13.41,2.35.
        assertEquals(
            "https://api.open-meteo.com/v1/elevation?latitude=52.52,48.85&longitude=13.41,2.35",
            elevationUrl(listOf(LatLon(52.52, 13.41), LatLon(48.85, 2.35))),
        )
        assertEquals("https://api.open-meteo.com/v1/elevation?latitude=31.12346&longitude=75.0", elevationUrl(listOf(LatLon(31.123456, 75.0))))
    }

    @Test
    fun replyIsReadInOrder() {
        // The documented reply to that example (Berlin 38 m, Paris 43 m).
        assertEquals(listOf(38.0, 43.0), parseElevations("""{"elevation":[38.0,43.0]}"""))
    }
}
