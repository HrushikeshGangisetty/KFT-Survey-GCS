package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Every import format against files written by other tools (`tools/fixtures/make_import_fixtures.py`: pyproj,
 * pyshp, zipfile). The expected coordinates are the script's FIELD / CANAL / POINTS, the values it started from
 * before PROJ projected them, so a shapefile or GeoJSON in UTM metres must come back to them. jvmTest because the
 * fixtures are files.
 */
class ImportFixturesTest {
    private val field = listOf(LatLon(17.4100, 78.4700), LatLon(17.4100, 78.4800), LatLon(17.4020, 78.4810), LatLon(17.4010, 78.4690))
    private val canal = listOf(LatLon(17.4000, 78.4650), LatLon(17.4050, 78.4700), LatLon(17.4120, 78.4720))
    private val gate = LatLon(17.4015, 78.4695)
    private val pump = LatLon(17.4090, 78.4790)

    private fun bytes(name: String): ByteArray? = javaClass.getResourceAsStream("/import/$name")?.readBytes()

    /** A picked file whose siblings (same name, other extension) can be read, like the desktop file dialog. */
    private fun file(name: String, prj: String? = null) = ImportFile(name, bytes(name)!!) { ext ->
        if (ext == "prj" && prj != null) bytes(prj) else bytes(name.substringBeforeLast('.') + ".$ext")
    }

    /** 1e-7° ≈ 1 cm: the UTM round trip through PROJ and back through our inverse. */
    private fun assertSame(expected: List<LatLon>, actual: List<LatLon>, tol: Double = 1e-7) {
        assertEquals(expected.size, actual.size, "corners")
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.latitude, a.latitude, tol)
            assertEquals(e.longitude, a.longitude, tol)
        }
    }

    @Test
    fun kmlFromGoogleEarth() = checkFarmKml(importGeometry(file("farm.kml")))

    /** A KMZ is the same KML zipped (doc.kml), with icons beside it that must be ignored. */
    @Test
    fun kmz() = checkFarmKml(importGeometry(file("farm.kmz")))

    private fun checkFarmKml(s: ImportedShapes) {
        assertEquals(listOf("North field", "Twin plots", "Twin plots"), s.areas.map { it.name })
        assertSame(field, s.areas[0].points, tol = 0.0) // KML is already degrees: exact
        assertEquals(3, s.areas[1].points.size) // closing corner removed
        assertEquals("Canal", s.lines.single().name)
        assertSame(canal, s.lines.single().points, tol = 0.0)
        assertEquals(listOf(NamedPoint("Gate", gate), NamedPoint("Pump", pump)), s.points)
        assertTrue(s.notes.single().startsWith("1 hole"))
    }

    @Test
    fun geoJson() {
        val s = importGeometry(file("farm.geojson"))
        assertEquals("North field", s.areas.single().name)
        assertSame(field, s.areas.single().points, tol = 0.0)
        assertEquals("Canal", s.lines.single().name) // from "Name", not "name"
        assertEquals(listOf(gate, pump), s.points.map { it.position })
        assertTrue(s.notes.single().startsWith("1 hole"))
    }

    /** The 2008 GeoJSON `crs` member naming UTM 44N: metres, not degrees. */
    @Test
    fun geoJsonWithAnOldCrsMember() = assertSame(field, importGeometry(file("farm_utm_crs.geojson")).areas.single().points)

    /** A polygon with a hole, in UTM 44N with its .prj beside it: the outline back in degrees, the hole reported. */
    @Test
    fun shapefileInUtmWithPrj() {
        val s = importGeometry(file("field_utm.shp"))
        assertSame(field, s.areas.single().points)
        assertTrue(s.notes.single().startsWith("1 hole"))
    }

    /** The same set zipped inside a folder: what the Android picker gets (it can't see a .shp's siblings). */
    @Test
    fun zippedShapefile() = assertSame(field, importGeometry(file("field_utm.zip")).areas.single().points)

    @Test
    fun shapefilePolylineInWgs84() = assertSame(canal, importGeometry(file("canal_wgs84.shp")).lines.single().points, tol = 1e-12)

    /** Points in degrees with no .prj: read as WGS 84, with a note saying so. */
    @Test
    fun shapefileWithoutPrjInDegrees() {
        val s = importGeometry(file("points_noprj.shp"))
        assertEquals(listOf(gate, pump), s.points.map { it.position })
        assertTrue(s.notes.single().startsWith("No .prj"))
    }

    /** UTM metres with the .prj missing would land nowhere near the field: refused, not guessed. */
    @Test
    fun metresWithoutPrjAreRefused() {
        val noPrj = ImportFile("field_utm.shp", bytes("field_utm.shp")!!)
        assertTrue(assertFailsWith<IllegalArgumentException> { importGeometry(noPrj) }.message!!.contains("WGS 84 (EPSG:4326)"))
    }

    /** An Everest-datum .prj (Kalianpur 1975) needs a datum shift: refused with the datum's name. */
    @Test
    fun otherDatumIsRefusedByName() {
        val e = assertFailsWith<IllegalArgumentException> { importGeometry(file("field_utm.shp", prj = "kalianpur.prj")) }
        assertTrue(e.message!!.contains("Kalianpur"), e.message)
    }
}
