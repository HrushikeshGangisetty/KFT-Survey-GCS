package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.StringReader
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler

/** `java.util.zip`, on Android and desktop alike (KMZ files and zipped shapefiles). */
internal actual fun unzip(bytes: ByteArray): Map<String, ByteArray> {
    val out = LinkedHashMap<String, ByteArray>()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.filterNot { it.isDirectory }.forEach { out[it.name] = zip.readBytes() }
        }
    } catch (e: ZipException) {
        throw IllegalArgumentException("The archive can't be opened: ${e.message}")
    }
    require(out.isNotEmpty()) { "The archive is empty or isn't a zip file." }
    return out
}

/**
 * KML with SAX, like the parameter XML: element by element, no tree. Namespace-aware, so `<kml:Point>` and `<Point>`
 * read the same. What counts: a Placemark's own `<name>`, and every `<coordinates>` inside it, by what it sits in
 * (Point, LineString, a Polygon's outer or inner boundary). MultiGeometry needs nothing special: its parts are just
 * more coordinates in the same Placemark. KML coordinates are always WGS 84 "lon,lat[,alt]" tuples.
 */
internal actual fun parseKml(text: String): ImportedShapes {
    val out = Collector()
    val handler = object : DefaultHandler() {
        val path = ArrayDeque<String>()
        val chars = StringBuilder()
        var name: String? = null
        var polygon: MutableList<List<LatLon>>? = null

        override fun startElement(uri: String?, localName: String, qName: String?, attributes: Attributes?) {
            path.addLast(localName)
            chars.setLength(0)
            when (localName) {
                "Placemark" -> name = null
                "Polygon" -> polygon = mutableListOf()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            chars.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String, qName: String?) {
            path.removeLast()
            val inPlacemark = "Placemark" in path
            when {
                localName == "name" && path.lastOrNull() == "Placemark" -> name = chars.toString().trim().ifEmpty { null }
                localName == "coordinates" && inPlacemark -> {
                    val points = coordinates(chars.toString())
                    when {
                        "outerBoundaryIs" in path -> polygon?.add(0, points) // the outline goes first
                        "innerBoundaryIs" in path -> polygon?.add(points)
                        path.lastOrNull() == "Point" -> points.firstOrNull()?.let { out.point(name, it) }
                        path.lastOrNull() == "LineString" -> out.line(name, points)
                    }
                }
                localName == "Polygon" -> {
                    polygon?.let { out.polygon(name, it) }
                    polygon = null
                }
            }
            chars.setLength(0)
        }
    }
    try {
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(InputSource(StringReader(text)), handler)
    } catch (e: SAXException) {
        throw IllegalArgumentException("This KML can't be read: ${e.message}")
    }
    return out.shapes()
}

/** "lon,lat[,alt] lon,lat …", separated by any whitespace. Stray spaces after commas (some exporters) are tolerated. */
private fun coordinates(text: String): List<LatLon> =
    text.replace(Regex(""",\s+"""), ",").trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.mapNotNull { tuple ->
        val parts = tuple.split(',')
        val lon = parts.getOrNull(0)?.toDoubleOrNull()
        val lat = parts.getOrNull(1)?.toDoubleOrNull()
        if (lon != null && lat != null) wgs84(lat, lon) else null
    }
