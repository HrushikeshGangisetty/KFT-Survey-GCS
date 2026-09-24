package com.kft.gcs.ui.map

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertTrue

class GeoJsonTest {
    // GeoJSON (RFC 7946 §3.1.1) orders coordinates [longitude, latitude]. Getting it backwards puts SITL home
    // (-35.36, 149.17) in the Antarctic ocean, so this is the one mistake worth a test.
    @Test
    fun coordinatesAreLongitudeFirst() {
        val home = LatLon(-35.363261, 149.16523)
        assertTrue(pointGeoJson(home).contains("[149.16523,-35.363261]"))
        assertTrue(lineGeoJson(listOf(home, LatLon(-35.0, 149.0))).contains("[[149.16523,-35.363261],[149.0,-35.0]]"))
    }
}
