package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoImportTest {
    private fun csv(text: String) = importGeometry(ImportFile("points.csv", text.encodeToByteArray()))
    private fun positions(text: String) = csv(text).points.map { it.position }

    private val hyd = LatLon(17.385, 78.4867)
    private val sec = LatLon(17.4399, 78.4983)

    // ---- CSV

    @Test
    fun csvWithAHeaderInAnyColumnOrder() {
        val s = csv("name,alt,longitude,latitude\nHyd,540,78.4867,17.385\nSec,520,78.4983,17.4399\n")
        assertEquals(listOf(NamedPoint("Hyd", hyd), NamedPoint("Sec", sec)), s.points)
    }

    @Test
    fun csvWithoutHeaderIsLatLon() = assertEquals(listOf(hyd, sec), positions("17.385,78.4867\n17.4399,78.4983"))

    /** Values past ±90 can only be longitudes, so this file is lon, lat (CMAC). At 78° E it couldn't be told apart. */
    @Test
    fun csvWithoutHeaderLonFirstIsNoticed() = assertEquals(listOf(LatLon(-35.3632, 149.1652)), positions("149.1652,-35.3632"))

    /** Semicolons with decimal commas (European spreadsheets), tabs, and spaces. */
    @Test
    fun csvSeparators() {
        assertEquals(listOf(hyd, sec), positions("lat;lon\n17,385;78,4867\n17,4399;78,4983"))
        assertEquals(listOf(hyd, sec), positions("lat\tlon\n17.385\t78.4867\n17.4399\t78.4983"))
        assertEquals(listOf(hyd, sec), positions("17.385   78.4867\n17.4399 78.4983"))
    }

    /**
     * Hemisphere letters and degrees-minutes-seconds, by hand: 17°23'06"N = 17 + 23/60 + 6/3600 = 17.385°;
     * 78°29'12.12"E = 78 + 29/60 + 12.12/3600 = 78.4867°. South and west are negative.
     */
    @Test
    fun csvHemispheresAndDms() {
        assertEquals(listOf(hyd), positions("17.385N,78.4867E"))
        assertEquals(listOf(LatLon(-35.3632, -149.1652)), positions("35.3632 S;149.1652 W"))
        val dms = positions("lat,lon\n\"17°23'06\"\"N\",\"78°29'12.12\"\"E\"").single()
        assertEquals(17.385, dms.latitude, 1e-9)
        assertEquals(78.4867, dms.longitude, 1e-9)
        assertEquals(-35.5, parseCoordinate("35 30 00 S", decimalComma = false)!!, 1e-12)
        assertNull(parseCoordinate("17 75 00 N", decimalComma = false)) // 75 minutes isn't a coordinate
    }

    /** Rubbish rows are skipped and reported by line number; blank lines and # comments are ignored silently. */
    @Test
    fun csvSkipsBadRowsAndSaysWhich() {
        val s = csv("# exported by hand\nlat,lon,name\n17.385,78.4867,A\n\nn/a,78.1,B\n95.0,78.1,C\n17.4399,78.4983,D\n")
        assertEquals(listOf("A", "D"), s.points.map { it.name })
        assertEquals("2 row(s) skipped (not coordinates): line 5, 6.", s.notes.single())
    }

    @Test
    fun csvWithoutCoordinatesIsRefused() {
        assertFailsWith<IllegalArgumentException> { csv("name,notes\nA,b\n") }
        assertFailsWith<IllegalArgumentException> { csv("") }
    }

    // ---- GeoJSON (inline; the fixture files are in jvmTest)

    @Test
    fun bareGeoJsonGeometryAndClosedRing() {
        val s = importGeometry(ImportFile("x.json", """{"type":"Polygon","coordinates":[[[78,17],[78.1,17],[78.1,17.1],[78,17]]]}""".encodeToByteArray()))
        assertEquals(listOf(LatLon(17.0, 78.0), LatLon(17.0, 78.1), LatLon(17.1, 78.1)), s.areas.single().points)
    }

    @Test
    fun brokenGeoJsonSaysSo() {
        val e = assertFailsWith<IllegalArgumentException> { importGeometry(ImportFile("x.geojson", "{\"type\":".encodeToByteArray())) }
        assertTrue(e.message!!.startsWith("This isn't valid GeoJSON"))
    }

    /** An unknown extension is recognised by content (here KML in a .xml file). */
    @Test
    fun contentDecidesForUnknownExtensions() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><Point><coordinates>78.4867,17.385</coordinates></Point></Placemark></kml>"""
        assertEquals(hyd, importGeometry(ImportFile("export.xml", kml.encodeToByteArray())).points.single().position)
    }

    // ---- .prj and EPSG (reference values from PROJ 9 / pyproj, commands in tools/fixtures/make_import_fixtures.py)

    /** pyproj: EPSG:4326 (78.47 E, 17.41 N) → EPSG:32644 (231 218.8315 m, 1 926 688.5999 m). */
    @Test
    fun utmPrjFromEsriWkt() {
        val wkt = """PROJCS["WGS_1984_UTM_Zone_44N",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],""" +
            """PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],""" +
            """PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",81.0],PARAMETER["Scale_Factor",0.9996],""" +
            """PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]"""
        val p = Crs.fromPrj(wkt).toLatLon(231_218.83153775084, 1_926_688.5999168)
        assertEquals(17.41, p.latitude, 1e-7)
        assertEquals(78.47, p.longitude, 1e-7)
        assertEquals(p, Crs.fromEpsg(32644).toLatLon(231_218.83153775084, 1_926_688.5999168))
    }

    /**
     * A grid in US survey feet (NAD83 / Florida East, EPSG:2236), so the unit and the feet false easting both count.
     * pyproj: (80.2 W, 26.1 N) → (918 709.2402 ftUS, 642 842.7472 ftUS).
     */
    @Test
    fun transverseMercatorInUsFeet() {
        val wkt = """PROJCS["NAD_1983_StatePlane_Florida_East_FIPS_0901_Feet",GEOGCS["GCS_North_American_1983",DATUM["D_North_American_1983",""" +
            """SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],""" +
            """PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",656166.667],PARAMETER["False_Northing",0.0],""" +
            """PARAMETER["Central_Meridian",-81.0],PARAMETER["Scale_Factor",0.999941177],PARAMETER["Latitude_Of_Origin",24.3333333333333],""" +
            """UNIT["US survey foot",0.304800609601219]]"""
        val p = Crs.fromPrj(wkt).toLatLon(918_709.2401939497, 642_842.7471734254)
        assertEquals(26.1, p.latitude, 1e-7)
        assertEquals(-80.2, p.longitude, 1e-7)
    }

    @Test
    fun unsupportedProjectionsAreNamed() {
        val lcc = """PROJCS["x",GEOGCS["g",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]]],PROJECTION["Lambert_Conformal_Conic"],UNIT["Meter",1.0]]"""
        assertTrue(assertFailsWith<IllegalArgumentException> { Crs.fromPrj(lcc) }.message!!.contains("Lambert_Conformal_Conic"))
        assertFailsWith<IllegalArgumentException> { Crs.fromEpsg(27700) } // British National Grid (OSGB36)
    }
}
