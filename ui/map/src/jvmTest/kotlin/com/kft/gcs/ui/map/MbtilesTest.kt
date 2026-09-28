package com.kft.gcs.ui.map

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * MBTiles sniffing and import against real SQLite files written by Python's sqlite3
 * (`tools/fixtures/make_mbtiles_fixtures.py`). jvmTest because the fixtures are files on disk.
 */
class MbtilesTest {
    private fun fixture(name: String): ByteArray = MbtilesTest::class.java.getResourceAsStream("/$name")!!.readBytes()

    @Test
    fun rasterVectorAndNotMbtiles() {
        assertEquals(MbtilesKind.RASTER, mbtilesKind(fixture("raster.mbtiles")))
        assertEquals(MbtilesKind.VECTOR, mbtilesKind(fixture("vector.mbtiles")))
        assertEquals(MbtilesKind.UNKNOWN, mbtilesKind(fixture("not-mbtiles.mbtiles")))
    }

    /** A SQLite file with no format row (some other database renamed .mbtiles) is refused, not guessed. */
    @Test
    fun sqliteWithoutAFormatRowIsUnknown() {
        val head = "SQLite format 3\u0000".encodeToByteArray() + ByteArray(100)
        assertEquals(MbtilesKind.UNKNOWN, mbtilesKind(head))
    }

    /**
     * Import through the real [MapLibreOfflineMaps] folder logic: a raster file is listed as a basemap with its size;
     * a vector file is refused, deleted, and not listed. The MapLibre runtime is never touched (no packs used).
     */
    @Test
    fun importKeepsRasterAndRefusesVector() = runTest {
        val dir = createTempDirectory("maps")
        val maps = MapLibreOfflineMaps(dir.toString(), listOf(TileSources.Street))
        val raster = dir.resolve("farm.mbtiles").also { Files.write(it, fixture("raster.mbtiles")) }
        val vector = dir.resolve("city.mbtiles").also { Files.write(it, fixture("vector.mbtiles")) }

        assertNull(maps.importMbtiles(raster.toString()))
        assertTrue(maps.importMbtiles(vector.toString())!!.contains("vector"))
        assertFalse(vector.exists())

        val imported = maps.basemaps.value.last()
        assertEquals(listOf("Street", "farm"), maps.basemaps.value.map { it.name })
        assertEquals(12288L, (imported.source as TileSourceConfig.Source.Mbtiles).sizeBytes)

        maps.deleteImported(imported.id)
        assertFalse(raster.exists())
        assertEquals(listOf("Street"), maps.basemaps.value.map { it.name })
    }
}
