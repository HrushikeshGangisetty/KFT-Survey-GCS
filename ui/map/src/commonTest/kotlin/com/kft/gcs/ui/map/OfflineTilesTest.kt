package com.kft.gcs.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.maplibre.compose.offline.DownloadProgress
import org.maplibre.compose.offline.DownloadStatus

class OfflineTilesTest {

    /** The whole world is 1 tile at z0, 4 at z1, 16 at z2 (2^z × 2^z): 21 in all. */
    @Test
    fun wholeWorldIsFourToTheZoomPerLevel() {
        val world = GeoBounds(south = -85.0511, west = -180.0, north = 85.0511, east = 180.0)
        assertEquals(1L, TileMath.tileCount(world, 0..0))
        assertEquals(21L, TileMath.tileCount(world, 0..2))
        assertEquals(1L shl 20, TileMath.tileCount(world, 10..10)) // 1024 × 1024
    }

    /**
     * Analytic: lon 0 and lat 0 sit exactly on a tile corner, x = y = 2^(z−1). A box just north-east of that point
     * is one column wide (x stays 2^(z−1)) and two rows tall (lat 0 is row 2^(z−1), anything north of it is the row
     * above), at every zoom.
     */
    @Test
    fun boxAtTheOriginCornerIsOneColumnByTwoRows() {
        assertEquals(512, TileMath.tileX(0.0, 10))
        assertEquals(512, TileMath.tileY(0.0, 10))
        val box = GeoBounds(south = 0.0, west = 0.0, north = 0.0001, east = 0.0001)
        assertEquals(2L, TileMath.tileCount(box, 5..5))
        assertEquals(10L, TileMath.tileCount(box, 10..14))
    }

    /**
     * By hand, with the OpenStreetMap wiki formulas, for a box around CMAC (the SITL home):
     * z10: x = (149.1652 + 180) / 360 · 1024 = 936.29 and (149.18 + 180) / 360 · 1024 = 936.33 → column 936 only;
     *      y(−35.3632) = (1 − ln(tan φ + sec φ) / π) / 2 · 1024 = 619.66, y(−35.35) = 619.61 → row 619 only. 1 tile.
     * z14: x = 14980.67 … 14981.35 → 2 columns; y = 9914.56 … 9913.82 → 2 rows. 4 tiles.
     */
    @Test
    fun cmacBoxByHand() {
        val box = GeoBounds(south = -35.3632, west = 149.1652, north = -35.35, east = 149.18)
        assertEquals(936, TileMath.tileX(149.1652, 10))
        assertEquals(619, TileMath.tileY(-35.3632, 10))
        assertEquals(1L, TileMath.tileCount(box, 10..10))
        assertEquals(4L, TileMath.tileCount(box, 14..14))
    }

    /** The east edge at +180° and the poles stay inside the tile grid instead of counting a column past the end. */
    @Test
    fun edgesOfTheWorldClamp() {
        assertEquals(1023, TileMath.tileX(180.0, 10))
        assertEquals(0, TileMath.tileY(90.0, 10))
        assertEquals(1023, TileMath.tileY(-90.0, 10))
    }

    /** Street tiles stop at z14, so asking for z10–16 costs z10–14; a range entirely past z14 costs nothing. */
    @Test
    fun vectorDownloadStopsAtTheTilesMaxZoom() {
        val box = GeoBounds(south = 0.0, west = 0.0, north = 0.0001, east = 0.0001)
        assertEquals(10L, TileMath.downloadTiles(TileSources.Street.source, box, 10..16))
        assertEquals(0L, TileMath.downloadTiles(TileSources.Street.source, box, 15..18))
        assertEquals(14L, TileMath.downloadTiles(TileSources.Satellite.source, box, 10..16)) // raster goes on to z19
    }

    /** GS-1: Street may be downloaded, Esri imagery may not, and the refusal says why in words. */
    @Test
    fun offlinePolicyPerSource() {
        assertEquals(true, TileSources.Street.offline.allowed)
        assertEquals(false, TileSources.Satellite.offline.allowed)
        assertEquals(true, TileSources.Satellite.offline.reason.contains("Esri"))
    }

    /** MapLibre's progress in our words: the total only counts once MapLibre says it's precise. */
    @Test
    fun downloadProgressMapping() {
        val running = DownloadProgress.Healthy(40, 2_000_000, 30, 1_500_000, DownloadStatus.Downloading, true, 120)
        assertEquals(RegionProgress(RegionState.DOWNLOADING, tiles = 30, bytes = 2_000_000, completedResources = 40, requiredResources = 120), running.toOurs())
        assertNull(running.copy(isRequiredResourceCountPrecise = false).toOurs().requiredResources)
        assertEquals(RegionState.COMPLETE, running.copy(status = DownloadStatus.Complete).toOurs().state)
        assertEquals(RegionState.FAILED, DownloadProgress.Error("Connection", "timed out").toOurs().state)
    }

    @Test
    fun packMetadataRoundTripsAndIgnoresForeignPacks() {
        val meta = Meta(7, "Street · 1.2 × 0.8 km · z10–16")
        assertEquals(meta, Meta.parse(meta.bytes()))
        assertNull(Meta.parse("some other app's pack".encodeToByteArray()))
        assertNull(Meta.parse(null))
    }
}
