package com.kft.gcs.feature.settings

import app.cash.turbine.test
import com.kft.gcs.ui.map.GeoBounds
import com.kft.gcs.ui.map.MapViewport
import com.kft.gcs.ui.map.OfflineMaps
import com.kft.gcs.ui.map.OfflineRegion
import com.kft.gcs.ui.map.RegionProgress
import com.kft.gcs.ui.map.RegionState
import com.kft.gcs.ui.map.TileSourceConfig
import com.kft.gcs.ui.map.TileSources
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Records what the ViewModel asks for; the regions and basemaps are set by each test. */
private class FakeOfflineMaps : OfflineMaps {
    override val regions = MutableStateFlow<List<OfflineRegion>>(emptyList())
    override val basemaps = MutableStateFlow(listOf(TileSources.Street, TileSources.Satellite))
    val downloads = mutableListOf<Triple<String, GeoBounds, IntRange>>()
    val calls = mutableListOf<String>()
    var importResult: String? = null

    override suspend fun download(name: String, source: TileSourceConfig, bounds: GeoBounds, zooms: IntRange) {
        downloads += Triple(name, bounds, zooms)
    }
    override fun pause(id: Long) { calls += "pause $id" }
    override fun resume(id: Long) { calls += "resume $id" }
    override suspend fun delete(id: Long) { calls += "delete $id" }
    override suspend fun clearCache() { calls += "clear" }
    override suspend fun importMbtiles(path: String): String? { calls += "import $path"; return importResult }
    override suspend fun deleteImported(id: String) { calls += "delete $id" }
}

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineMapsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val maps = FakeOfflineMaps()
    private var picked: String? = "/maps/farm.mbtiles"
    private val vm by lazy { OfflineMapsViewModel(maps) { picked } }

    /**
     * A box just north-east of lat 0, lon 0: 1 column × 2 rows at every zoom (analytic, see OfflineTilesTest).
     * Street at z10–16 costs z10–14 = 10 tiles, × 25 KB = 250 KB.
     */
    private val tiny = MapViewport(GeoBounds(south = 0.0, west = 0.0, north = 0.0001, east = 0.0001), zoom = 18.0)

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test
    fun estimateFollowsTheMapView() = runTest(dispatcher) {
        vm.state.test {
            runCurrent()
            val before = expectMostRecentItem()
            assertFalse(before.canDownload)
            assertEquals("Move the map to the area you need.", before.blockReason)
            assertNull(before.estimate)

            vm.onViewChanged(tiny)
            val after = awaitItem()
            assertEquals("10 tiles, about 250 KB, plus 60 MB of map fonts with the first download", after.estimate)
            assertEquals("11 × 11 m", after.area) // 0.0001° of arc on the equator: 6 371 008.8 m · 0.0001 · π/180 = 11.1 m
            assertTrue(after.canDownload)
        }
    }

    /** GS-1: choosing Satellite blocks the download with Esri's reason and shows no estimate. */
    @Test
    fun satelliteCannotBeDownloaded() = runTest(dispatcher) {
        vm.onViewChanged(tiny)
        vm.onSourceSelected(TileSources.Satellite.id)
        vm.state.test {
            runCurrent()
            val s = expectMostRecentItem()
            assertFalse(s.canDownload)
            assertEquals(TileSources.Satellite.offline.reason, s.blockReason)
            assertNull(s.estimate)
        }
        vm.onDownloadClicked()
        runCurrent()
        assertTrue(maps.downloads.isEmpty())
    }

    /** 20° × 20° at z10–14 is millions of tiles: blocked, with the count and the limit in the message. */
    @Test
    fun tooManyTilesIsBlocked() = runTest(dispatcher) {
        vm.onViewChanged(MapViewport(GeoBounds(-10.0, -10.0, 10.0, 10.0), zoom = 5.0))
        vm.state.test {
            runCurrent()
            val s = expectMostRecentItem()
            assertFalse(s.canDownload)
            assertTrue(s.blockReason!!.startsWith("Too big"))
        }
    }

    @Test
    fun downloadSendsTheViewAndZooms() = runTest(dispatcher) {
        vm.onViewChanged(tiny)
        vm.onZoomRangeChanged(12, 15)
        vm.onDownloadClicked()
        runCurrent()
        val (name, bounds, zooms) = maps.downloads.single()
        assertEquals(tiny.bounds, bounds)
        assertEquals(12..15, zooms)
        assertEquals("Street · 11 × 11 m · z12–15", name)
    }

    /** The slider can't produce an empty or out-of-range range. */
    @Test
    fun zoomRangeIsClamped() = runTest(dispatcher) {
        vm.state.test {
            awaitItem()
            vm.onZoomRangeChanged(-3, 25)
            assertEquals(0 to OfflineMapsViewModel.MAX_ZOOM, awaitItem().let { it.minZoom to it.maxZoom })
            vm.onZoomRangeChanged(14, 12)
            assertEquals(14 to 14, awaitItem().let { it.minZoom to it.maxZoom })
        }
    }

    @Test
    fun regionsAndImportedFilesAreListed() = runTest(dispatcher) {
        val box = GeoBounds(0.0, 0.0, 1.0, 1.0)
        maps.regions.value = listOf(
            OfflineRegion(1, "A", box, 10..14, RegionProgress(RegionState.DOWNLOADING, tiles = 30, bytes = 2_500_000, completedResources = 50, requiredResources = 200)),
            OfflineRegion(2, "B", box, 10..14, RegionProgress(RegionState.COMPLETE, tiles = 200, bytes = 9_800_000)),
        )
        maps.basemaps.value = maps.basemaps.value +
            TileSourceConfig("mbtiles:farm.mbtiles", "farm", TileSourceConfig.Source.Mbtiles("/maps/farm.mbtiles", 1_400_000_000), "Imported")
        vm.state.test {
            runCurrent()
            val s = expectMostRecentItem()
            assertEquals(RegionRow(1, "A", "Downloading · 30 tiles · 3 MB", 0.25f, canPause = true, canResume = false), s.regions[0])
            assertEquals(RegionRow(2, "B", "Done · 200 tiles · 10 MB", null, canPause = false, canResume = false), s.regions[1])
            assertEquals(listOf(ImportedRow("mbtiles:farm.mbtiles", "farm", "1.4 GB")), s.imported)
            assertEquals(2, s.overlays.size) // one outline per region
            // Imported files are basemaps, not download sources.
            assertEquals(listOf("Street", "Satellite"), s.sources.map { it.name })
        }
    }

    @Test
    fun importReportsTheOutcome() = runTest(dispatcher) {
        vm.effects.test {
            maps.importResult = "This MBTiles file holds vector tiles."
            vm.onImportClicked()
            assertEquals(OfflineMapsEffect.ShowMessage("This MBTiles file holds vector tiles."), awaitItem())
            maps.importResult = null
            vm.onImportClicked()
            assertTrue((awaitItem() as OfflineMapsEffect.ShowMessage).text.startsWith("Imported"))
            picked = null // cancelled: nothing imported, nothing said
            vm.onImportClicked()
            runCurrent()
            expectNoEvents()
        }
        assertEquals(listOf("import /maps/farm.mbtiles", "import /maps/farm.mbtiles"), maps.calls)
    }

    @Test
    fun regionButtonsReachTheStore() = runTest(dispatcher) {
        vm.onPauseClicked(3)
        vm.onResumeClicked(3)
        vm.onDeleteRegionConfirmed(3)
        vm.onClearCacheClicked()
        runCurrent()
        assertEquals(listOf("pause 3", "resume 3", "delete 3", "clear"), maps.calls)
    }

    /** Sizes as quoted for downloads (10⁶ bytes to the MB), by hand. */
    @Test
    fun sizes() {
        assertEquals("850 KB", bytesText(850_000))
        assertEquals("12 MB", bytesText(12_400_000))
        assertEquals("1.4 GB", bytesText(1_400_000_000))
    }
}
