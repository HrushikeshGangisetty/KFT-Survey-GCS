package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GeoJSON (RFC 7946): a FeatureCollection, a single Feature or a bare geometry, any geometry type. RFC 7946 says
 * WGS 84 longitude/latitude, but older files (2008 spec) may name another system in a `crs` member; those are read
 * through [Crs.fromEpsg] rather than misread as degrees. A feature's name comes from `name` / `Name` / `title`.
 */
internal fun parseGeoJson(text: String): ImportedShapes {
    val root = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw IllegalArgumentException("This isn't valid GeoJSON: ${e.message?.lineSequence()?.first()}")
    }
    val crs = crsOf(root)
    val out = Collector()
    fun geometry(g: JsonObject?, name: String?) {
        if (g == null) return
        val c = g["coordinates"]
        when ((g["type"] as? JsonPrimitive)?.contentOrNull) {
            "Point" -> out.point(name, crs.pos(c!!))
            "MultiPoint" -> c!!.jsonArray.forEach { out.point(name, crs.pos(it)) }
            "LineString" -> out.line(name, crs.path(c!!))
            "MultiLineString" -> c!!.jsonArray.forEach { out.line(name, crs.path(it)) }
            "Polygon" -> out.polygon(name, c!!.jsonArray.map { crs.path(it) })
            "MultiPolygon" -> c!!.jsonArray.forEach { poly -> out.polygon(name, poly.jsonArray.map { crs.path(it) }) }
            "GeometryCollection" -> g["geometries"]?.jsonArray?.forEach { geometry(it.jsonObject, name) }
        }
    }
    fun feature(f: JsonObject) {
        val props = f["properties"] as? JsonObject
        val name = listOf("name", "Name", "NAME", "title").firstNotNullOfOrNull { (props?.get(it) as? JsonPrimitive)?.contentOrNull }
        geometry(f["geometry"] as? JsonObject, name)
    }
    when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
        "FeatureCollection" -> root["features"]?.jsonArray?.forEach { feature(it.jsonObject) }
        "Feature" -> feature(root)
        else -> geometry(root, null)
    }
    return out.shapes()
}

/** The old `crs` member: `{"type":"name","properties":{"name":"urn:ogc:def:crs:EPSG::32643"}}`, or none (WGS 84). */
private fun crsOf(root: JsonObject): Crs {
    val name = ((root["crs"] as? JsonObject)?.get("properties") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull ?: return Crs.WGS84
    if (name.endsWith("CRS84")) return Crs.WGS84
    val code = Regex("""EPSG:+(\d+)""").find(name)?.groupValues?.get(1)?.toInt()
        ?: throw IllegalArgumentException("Unknown coordinate system \"$name\" in the GeoJSON.")
    return Crs.fromEpsg(code)
}

// GeoJSON positions are [x, y] (longitude first), with an optional altitude we don't use.
private fun Crs.pos(e: JsonElement): LatLon = e.jsonArray.let { toLatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }
private fun Crs.path(e: JsonElement): List<LatLon> = (e as JsonArray).map { pos(it) }

/** Gathers shapes as a reader finds them; the first ring of a polygon is its outline, the others are holes. */
internal class Collector {
    private val areas = mutableListOf<NamedShape>()
    private val lines = mutableListOf<NamedShape>()
    private val points = mutableListOf<NamedPoint>()
    private var holes = 0

    fun point(name: String?, p: LatLon) { points += NamedPoint(name, p) }
    fun line(name: String?, path: List<LatLon>) { if (path.size >= 2) lines += NamedShape(name, path) }
    fun polygon(name: String?, rings: List<List<LatLon>>) {
        openRing(rings.firstOrNull() ?: return)?.let { areas += NamedShape(name, it) }
        holes += rings.size - 1
    }
    fun hole() { holes++ }
    fun shapes() = ImportedShapes(areas, lines, points, holesNote(holes))
}
