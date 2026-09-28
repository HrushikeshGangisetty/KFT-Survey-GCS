package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.Ellipsoid
import com.kft.gcs.core.geo.Geographic
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.geo.Projection
import com.kft.gcs.core.geo.TransverseMercator
import com.kft.gcs.core.geo.WebMercator

/**
 * A file's coordinate system: a [projection] from x/y in the file's units ([metresPerUnit] converts them) to WGS 84.
 *
 * Supported, because they cover what survey customers send: WGS 84 longitude/latitude; UTM and other Transverse
 * Mercator grids on WGS 84 / GRS 80 datums (ETRS89, NAD83, GDA are within 1–2 m of WGS 84, below GPS error); and Web
 * Mercator. Anything on another datum (Everest/Kalianpur, OSGB36, ED50…) needs a datum shift we don't do, and is
 * refused with its name, so it's never silently placed tens of metres off.
 */
class Crs(private val projection: Projection, private val metresPerUnit: Double = 1.0) {
    fun toLatLon(x: Double, y: Double): LatLon {
        if (projection == Geographic) return wgs84(y, x)
        // A projection fed numbers far outside its grid can itself produce an impossible longitude.
        val p = try {
            projection.toLatLon(x * metresPerUnit, y * metresPerUnit)
        } catch (e: IllegalArgumentException) {
            return wgs84(Double.NaN, Double.NaN)
        }
        return wgs84(p.latitude, p.longitude)
    }

    companion object {
        val WGS84 = Crs(Geographic)

        /** An EPSG code, as GeoJSON's old `crs` member names it. */
        fun fromEpsg(code: Int): Crs = when (code) {
            4326, 4258, 4269, 4283, 7844 -> WGS84 // WGS 84, ETRS89, NAD83, GDA94, GDA2020 (geographic)
            3857, 900913, 102100 -> Crs(WebMercator)
            in 32601..32660 -> Crs(TransverseMercator.utm(code - 32600, north = true))
            in 32701..32760 -> Crs(TransverseMercator.utm(code - 32700, north = false))
            in 25828..25838 -> Crs(TransverseMercator.utm(code - 25800, north = true).copy(ellipsoid = Ellipsoid.GRS80)) // ETRS89 / UTM
            else -> throw IllegalArgumentException("Coordinate system EPSG:$code isn't supported. Export the file as WGS 84 (EPSG:4326).")
        }

        /**
         * Reads a shapefile's `.prj`: well-known text (WKT 1, ESRI or OGC flavour). Only the parts that decide the
         * maths are read: the datum, the spheroid, the projection's name and parameters, and the linear unit.
         */
        fun fromPrj(wkt: String): Crs {
            val text = wkt.trim()
            val datum = Regex("""DATUM\s*\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: ""
            require(datumIsWgs84Like(datum)) {
                "The file's datum is \"$datum\". Only WGS 84 and its equivalents (ETRS89, NAD83, GDA) are supported: " +
                    "reproject it to WGS 84 (EPSG:4326) in QGIS first."
            }
            if (!text.startsWith("PROJCS", ignoreCase = true) && !text.startsWith("PROJCRS", ignoreCase = true)) return WGS84

            val projection = Regex("""PROJECTION\s*\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: ""
            val name = Regex("""^PROJC(?:RS|S)\s*\[\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1) ?: ""
            val key = projection.lowercase().replace(' ', '_')
            val params = Regex("""PARAMETER\s*\[\s*"([^"]+)"\s*,\s*([-+0-9.eE]+)""", RegexOption.IGNORE_CASE).findAll(text)
                .associate { it.groupValues[1].lowercase().replace(' ', '_') to it.groupValues[2].toDouble() }
            // The linear unit is the last UNIT in a PROJCS (the GEOGCS inside has its own angular one first).
            val unit = Regex("""UNIT\s*\[\s*"[^"]*"\s*,\s*([0-9.eE+-]+)""", RegexOption.IGNORE_CASE).findAll(text).lastOrNull()
                ?.groupValues?.get(1)?.toDouble() ?: 1.0
            val spheroid = Regex("""SPHEROID\s*\[\s*"[^"]*"\s*,\s*([0-9.eE+-]+)\s*,\s*([0-9.eE+-]+)""", RegexOption.IGNORE_CASE).find(text)
                ?.let { Ellipsoid(it.groupValues[1].toDouble(), 1 / it.groupValues[2].toDouble()) } ?: Ellipsoid.WGS84

            return when {
                key == "transverse_mercator" || key == "gauss_kruger" -> Crs(
                    TransverseMercator(
                        centralMeridianDeg = params["central_meridian"] ?: params["longitude_of_center"] ?: 0.0,
                        latitudeOfOriginDeg = params["latitude_of_origin"] ?: 0.0,
                        scale = params["scale_factor"] ?: 1.0,
                        // False easting/northing are in the file's unit too.
                        falseEasting = (params["false_easting"] ?: 0.0) * unit,
                        falseNorthing = (params["false_northing"] ?: 0.0) * unit,
                        ellipsoid = spheroid,
                    ),
                    metresPerUnit = unit,
                )
                key == "mercator_auxiliary_sphere" || key.contains("pseudo_mercator") || name.contains("Web_Mercator", ignoreCase = true) ->
                    Crs(WebMercator, unit)
                else -> throw IllegalArgumentException(
                    "The file's projection is \"${projection.ifEmpty { name }}\", which isn't supported (UTM / Transverse Mercator, " +
                        "Web Mercator and plain latitude/longitude are). Reproject it to WGS 84 (EPSG:4326) in QGIS first.",
                )
            }
        }

        private fun datumIsWgs84Like(datum: String): Boolean {
            val d = datum.uppercase().replace(" ", "_").replace("-", "_")
            return d.isNotEmpty() && listOf("WGS_1984", "WGS84", "WGS_84", "ETRS", "EUROPEAN_TERRESTRIAL_REFERENCE", "NAD_1983", "NAD83",
                "NORTH_AMERICAN_1983", "NORTH_AMERICAN_DATUM_1983", "GDA", "GEOCENTRIC_DATUM_OF_AUSTRALIA", "ITRF").any { it in d }
        }
    }
}
