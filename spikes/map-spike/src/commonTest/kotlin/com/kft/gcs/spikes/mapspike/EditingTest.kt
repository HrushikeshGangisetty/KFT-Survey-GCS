package com.kft.gcs.spikes.mapspike

import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.spatialk.geojson.Position

class EditingTest {

    private fun pt(x: Int, y: Int) = DpOffset(x.dp, y.dp)

    @Test
    fun hitPicksNearestHandleInsideRadius() {
        // Handles at (0,0) and (30,0). Touch at (18,0): 18 dp from the first, 12 dp from the second.
        val handles = listOf(pt(0, 0), pt(30, 0))
        assertEquals(1, hitVertex(pt(18, 0), handles, radius = 20.dp))
    }

    @Test
    fun hitMissesOutsideRadiusAndSkipsOffscreenHandles() {
        // (3,4) is exactly 5 dp from the origin (3-4-5 triangle), so a 4.9 dp radius misses it.
        assertNull(hitVertex(pt(3, 4), listOf(pt(0, 0)), radius = 4.9.dp))
        assertNull(hitVertex(pt(0, 0), listOf(null), radius = 50.dp))
    }

    @Test
    fun insertionGoesOnNearestEdge() {
        // Square (0,0) (100,0) (100,100) (0,100). A press at (50,95) is 5 dp from the bottom edge
        // (vertex 2 -> vertex 3), so the new vertex is inserted at index 3, between them.
        val square = listOf(pt(0, 0), pt(100, 0), pt(100, 100), pt(0, 100))
        assertEquals(3, insertionIndex(pt(50, 95), square))
        // Near the closing edge (vertex 3 -> vertex 0): insert at the end of the list.
        assertEquals(4, insertionIndex(pt(3, 50), square))
    }

    @Test
    fun polygonRingIsClosed() {
        val ring = listOf(Position(1.0, 2.0), Position(3.0, 4.0), Position(5.0, 6.0))
        val json = polygonGeoJson(ring)
        assertTrue(json.contains("[[[1.0,2.0],[3.0,4.0],[5.0,6.0],[1.0,2.0]]]"), json)
    }
}
