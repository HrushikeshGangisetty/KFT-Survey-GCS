package com.kft.gcs.ui.map

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import kotlin.test.assertNull
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

    @Test
    fun markersCarryLabelsAndRoutesNeedTwoPoints() {
        val home = LatLon(-35.363261, 149.16523)
        val json = markersGeoJson(listOf(MapOverlay.Marker("home", home, "H", MarkerStyle.HOME)))
        assertTrue(json.contains("\"label\":\"H\"") && json.contains("[149.16523,-35.363261]"))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", routeGeoJson(listOf(MapOverlay.Route(listOf(home)))))
    }

    /** A GeoJSON polygon ring must be closed (RFC 7946 §3.1.6): the first corner is repeated at the end. */
    @Test
    fun polygonsAreClosedRingsAndTwoCornersAreALine() {
        val a = LatLon(0.0, 0.0); val b = LatLon(0.0, 1.0); val c = LatLon(1.0, 1.0)
        assertTrue(polygonsGeoJson(listOf(MapOverlay.Polygon(listOf(a, b, c), true))).contains("[[[0.0,0.0],[1.0,0.0],[1.0,1.0],[0.0,0.0]]]"))
        assertTrue(polygonsGeoJson(listOf(MapOverlay.Polygon(listOf(a, b), true))).contains("\"LineString\""))
        assertEquals("""{"type":"FeatureCollection","features":[]}""", polygonsGeoJson(listOf(MapOverlay.Polygon(listOf(a), true))))
    }

    @Test
    fun hitMarkerPicksTheNearestWithinTheRadius() {
        // Touch at (100, 100). Marker 0 is 30 dp away (3-4-5 triangle x6: 18, 24), marker 1 is 5 dp away,
        // marker 2 is off screen. The nearest within 24 dp wins.
        val markers = listOf(DpOffset(118.dp, 124.dp), DpOffset(103.dp, 104.dp), null)
        assertEquals(1, hitMarker(DpOffset(100.dp, 100.dp), markers, 24.dp))
        assertNull(hitMarker(DpOffset(100.dp, 100.dp), listOf(markers[0], null), 24.dp), "30 dp is outside 24 dp")
    }
}
