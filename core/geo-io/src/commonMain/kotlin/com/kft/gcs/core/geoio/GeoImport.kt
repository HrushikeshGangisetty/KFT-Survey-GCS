package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon

/**
 * A file picked for import. [sibling] reads a file next to it with the same base name and another extension
 * ("prj" → `field.prj` next to `field.shp`), or null when there is none or the platform can't see it (the Android
 * picker hands over one file only; there, a shapefile comes as a .zip).
 */
class ImportFile(val name: String, val bytes: ByteArray, val sibling: (extension: String) -> ByteArray? = { null })

/** A named outline or path. Areas are open rings: the closing corner that files repeat is removed. */
data class NamedShape(val name: String?, val points: List<LatLon>)

data class NamedPoint(val name: String?, val position: LatLon)

/**
 * What a file holds, in WGS 84 latitude/longitude, whatever its own coordinate system was. [notes] are things the
 * operator should know (holes ignored, rows skipped), shown after the import.
 */
data class ImportedShapes(
    val areas: List<NamedShape> = emptyList(),
    val lines: List<NamedShape> = emptyList(),
    val points: List<NamedPoint> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = areas.isEmpty() && lines.isEmpty() && points.isEmpty()

    operator fun plus(other: ImportedShapes) =
        ImportedShapes(areas + other.areas, lines + other.lines, points + other.points, notes + other.notes)
}

/**
 * Reads survey areas, lines and points from KML, KMZ, GeoJSON, a shapefile (.shp with its .prj, or a .zip holding
 * them) or a CSV of points. The extension picks the reader; an unknown one is recognised by its content.
 * Throws [IllegalArgumentException] with a sentence for the operator when the file can't be used.
 */
fun importGeometry(file: ImportFile): ImportedShapes {
    val shapes = when (file.name.substringAfterLast('.', "").lowercase()) {
        "kml" -> parseKml(file.bytes.decodeToString())
        "kmz" -> fromArchive(unzip(file.bytes), file.name)
        "geojson", "json" -> parseGeoJson(file.bytes.decodeToString())
        "shp" -> parseShapefile(file.bytes, file.sibling("prj")?.decodeToString())
        "zip" -> fromArchive(unzip(file.bytes), file.name)
        "csv", "txt", "tsv" -> parseCsvPoints(file.bytes.decodeToString())
        else -> bySniffing(file)
    }
    require(!shapes.isEmpty) { "${file.name} has no areas, lines or points in it." }
    return shapes
}

private fun bySniffing(file: ImportFile): ImportedShapes {
    val bytes = file.bytes
    if (bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return fromArchive(unzip(bytes), file.name)
    val text = bytes.decodeToString().trimStart()
    return when {
        text.startsWith("{") -> parseGeoJson(text)
        text.contains("<kml", ignoreCase = true) -> parseKml(text)
        else -> parseCsvPoints(text)
    }
}

/** A KMZ, or a .zip with a shapefile, a KML or a GeoJSON inside. Folders inside the archive don't matter. */
private fun fromArchive(entries: Map<String, ByteArray>, name: String): ImportedShapes {
    fun find(ext: String) = entries.keys.filter { it.lowercase().endsWith(".$ext") }
    // KMZ: the main document is doc.kml by convention, else the first .kml.
    (find("kml").sortedBy { if (it.lowercase().endsWith("doc.kml")) 0 else 1 }.firstOrNull())
        ?.let { return parseKml(entries.getValue(it).decodeToString()) }
    find("shp").firstOrNull()?.let { shp ->
        val prj = entries[shp.dropLast(4) + ".prj"] ?: entries[shp.dropLast(4) + ".PRJ"]
        return parseShapefile(entries.getValue(shp), prj?.decodeToString())
    }
    (find("geojson") + find("json")).firstOrNull()?.let { return parseGeoJson(entries.getValue(it).decodeToString()) }
    throw IllegalArgumentException("$name has no .kml, .shp or .geojson inside.")
}

/**
 * A coordinate read from a file, or a sentence saying why it can't be one. A wrong or missing coordinate system
 * gives metres (231 218, 1 926 688) where degrees belong; [LatLon] would refuse them anyway, but with a message
 * meant for programmers, not for the operator.
 */
internal fun wgs84(latitude: Double, longitude: Double): LatLon {
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        "Some coordinates aren't latitude/longitude ($latitude, $longitude). The file's coordinate system is probably " +
            "missing or not supported: export it as WGS 84 (EPSG:4326) and try again."
    }
    return LatLon(latitude, longitude)
}

/** A ring as files store it (first corner repeated at the end) → our open ring; null if it has under 3 corners. */
internal fun openRing(points: List<LatLon>): List<LatLon>? {
    val ring = if (points.size > 1 && points.first() == points.last()) points.dropLast(1) else points
    return ring.takeIf { it.size >= 3 }
}

internal fun holesNote(holes: Int) =
    if (holes > 0) listOf("$holes hole(s) inside areas were ignored: a survey covers its whole outline.") else emptyList()

/** The file's entries by path. [IllegalArgumentException] if it isn't a zip. */
internal expect fun unzip(bytes: ByteArray): Map<String, ByteArray>

/** KML (Google Earth, QGIS, most GIS tools): Placemarks with Point, LineString and Polygon, at any depth. */
internal expect fun parseKml(text: String): ImportedShapes
