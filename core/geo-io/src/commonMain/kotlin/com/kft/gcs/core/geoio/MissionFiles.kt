package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AltitudeFrame
import com.kft.gcs.core.vehicle.Home
import com.kft.gcs.core.vehicle.Mission
import com.kft.gcs.core.vehicle.MissionCommand
import com.kft.gcs.core.vehicle.MissionItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A mission read from a QGC or Mission Planner file. [skipped] counts items we can't turn into plain mission items,
 * such as a QGC Survey (a "ComplexItem" that only QGC knows how to expand), so the operator can be told.
 */
data class MissionImport(val mission: Mission, val skipped: Int)

// ---- QGC .plan (JSON) ----
// Format: QGC's "Plan File Format" (docs.qgroundcontrol.com, dev guide), and QGC master 2026-09
// `src/MissionManager/MissionItem.cc` / `SimpleMissionItem.cc` for SimpleItem. Read, not copied.

/**
 * A QGC `.plan` holding [mission]'s items as plain SimpleItems. QGC needs a planned home; without one the first
 * positioned item stands in (QGC then moves home to the vehicle's on connect). [kind] sets QGC's vehicle type, so QGC
 * shows copter or plane editing options; firmware type 3 is ArduPilot (MAV_AUTOPILOT_ARDUPILOTMEGA).
 */
fun encodeQgcPlan(mission: Mission, kind: VehicleKind?): String {
    val home = mission.home ?: mission.items.firstNotNullOfOrNull { it.position }?.let { Home(it, 0.0) }
    val plan = buildJsonObject {
        put("fileType", "Plan")
        put("version", 1)
        put("groundStation", "KFT GCS")
        putJsonObject("mission") {
            put("version", 2)
            put("firmwareType", 3)
            put("vehicleType", if (kind == VehicleKind.PLANE) 1 else 2) // MAV_TYPE: 1 fixed wing, 2 quadrotor
            put("cruiseSpeed", 15)
            put("hoverSpeed", 5)
            putJsonArray("plannedHomePosition") {
                add(home?.position?.latitude ?: 0.0); add(home?.position?.longitude ?: 0.0); add(home?.altitudeMslM ?: 0.0)
            }
            putJsonArray("items") {
                mission.items.forEachIndexed { i, item ->
                    addJsonObject {
                        put("type", "SimpleItem")
                        put("autoContinue", true)
                        put("command", item.command)
                        put("doJumpId", i + 1)
                        put("frame", item.mavFrame())
                        putJsonArray("params") {
                            // QGC writes NaN ("leave unchanged", e.g. yaw) as null, since JSON has no NaN.
                            listOf(item.param1, item.param2, item.param3, item.param4).forEach { if (it.isNaN()) add(JsonNull) else add(it.toDouble()) }
                            add(item.position?.latitude ?: 0.0); add(item.position?.longitude ?: 0.0); add(item.altitudeM)
                        }
                    }
                }
            }
        }
        putJsonObject("geoFence") { put("version", 2); putJsonArray("circles") {}; putJsonArray("polygons") {} }
        putJsonObject("rallyPoints") { put("version", 2); putJsonArray("points") {} }
    }
    return prettyJson.encodeToString(JsonObject.serializer(), plan)
}

/**
 * Reads a QGC `.plan`. SimpleItems become [MissionItem]s; ComplexItems (QGC's own survey, corridor, structure scan)
 * are counted in [MissionImport.skipped], because only QGC can expand them. Home comes from `plannedHomePosition`.
 * @throws IllegalArgumentException when [text] isn't a QGC plan, with a message for the operator.
 */
fun decodeQgcPlan(text: String): MissionImport = parseOrExplain("QGC .plan") {
    val root = Json.parseToJsonElement(text).jsonObject
    require(root["fileType"]?.jsonPrimitive?.content == "Plan") { "not a QGC plan (fileType isn't \"Plan\")" }
    val mission = root.getValue("mission").jsonObject
    val home = mission["plannedHomePosition"]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull ?: 0.0 }
        ?.takeIf { it.size == 3 && (it[0] != 0.0 || it[1] != 0.0) }
        ?.let { Home(LatLon(it[0], it[1]), it[2]) }
    var skipped = 0
    val items = mission.getValue("items").jsonArray.mapNotNull { element ->
        val item = element.jsonObject
        if (item["type"]?.jsonPrimitive?.content != "SimpleItem") { skipped++; return@mapNotNull null }
        val p = item.getValue("params").jsonArray.map { it.jsonPrimitive.doubleOrNull ?: Double.NaN }
        require(p.size == 7) { "an item has ${p.size} params, expected 7" }
        itemFrom(item.getValue("command").jsonPrimitive.int, item.getValue("frame").jsonPrimitive.int, p)
    }
    MissionImport(Mission(home, items), skipped)
}

// ---- Mission Planner .waypoints (text) ----
// Format "QGC WPL 110" (it started in QGC, Mission Planner kept it): one header line, then one tab-separated line per
// item: seq, current, frame, command, param1–4, lat, lon, alt, autocontinue. Line seq 0 is home, as in S11.

/** A Mission Planner `.waypoints` file. Home (seq 0) is [Mission.home], or zeros when unknown (MP then uses its own). */
fun encodeWaypoints(mission: Mission): String = buildString {
    appendLine("QGC WPL 110")
    val home = mission.home
    appendLine(listOf(0, 1, 0, MissionCommand.WAYPOINT, 0, 0, 0, 0, home?.position?.latitude ?: 0.0, home?.position?.longitude ?: 0.0, home?.altitudeMslM ?: 0.0, 1).joinToString("\t"))
    mission.items.forEachIndexed { i, item ->
        val fields = listOf(i + 1, 0, item.mavFrame(), item.command, item.param1, item.param2, item.param3, item.param4,
            item.position?.latitude ?: 0.0, item.position?.longitude ?: 0.0, item.altitudeM, 1)
        appendLine(fields.joinToString("\t"))
    }
}

/**
 * Reads a `.waypoints` file. Line 0 becomes home and never an item (S11).
 * @throws IllegalArgumentException when [text] isn't one, with a message for the operator.
 */
fun decodeWaypoints(text: String): MissionImport = parseOrExplain(".waypoints") {
    val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    require(lines.firstOrNull()?.startsWith("QGC WPL") == true) { "the first line isn't \"QGC WPL 110\"" }
    val rows = lines.drop(1).map { line ->
        val f = line.split(Regex("\\s+"))
        require(f.size >= 12) { "a line has ${f.size} fields, expected 12: $line" }
        f
    }
    val home = rows.firstOrNull { it[0] == "0" }?.let { Home(LatLon(it[8].toDouble(), it[9].toDouble()), it[10].toDouble()) }
        ?.takeIf { it.position.latitude != 0.0 || it.position.longitude != 0.0 }
    val items = rows.filter { it[0] != "0" }.sortedBy { it[0].toInt() }.map { f ->
        itemFrom(f[3].toInt(), f[2].toInt(), (4..10).map { f[it].toDouble() })
    }
    MissionImport(Mission(home, items), skipped = 0)
}

// ---- shared ----

/** p = param1…param4, lat, lon, alt, as both formats store them. Lat/lon 0,0 means "no position" (a DO_ command). */
private fun itemFrom(command: Int, frame: Int, p: List<Double>) = MissionItem(
    command = command,
    position = if (p[4] == 0.0 && p[5] == 0.0 || p[4].isNaN()) null else LatLon(p[4], p[5]),
    altitudeM = p[6].takeIf { !it.isNaN() } ?: 0.0,
    frame = when (frame) {
        0, 5 -> AltitudeFrame.AMSL       // GLOBAL, GLOBAL_INT
        10, 11 -> AltitudeFrame.TERRAIN  // GLOBAL_TERRAIN_ALT(_INT)
        else -> AltitudeFrame.RELATIVE   // 3/6 relative, and 2 (MISSION) for DO_ commands, as ArduPilot reads them
    },
    param1 = p[0].toFloat(), param2 = p[1].toFloat(), param3 = p[2].toFloat(), param4 = p[3].toFloat(),
)

/** MAV_FRAME number: 2 (MISSION) for commands without a position, which is what QGC and MP write for DO_ items. */
private fun MissionItem.mavFrame(): Int = when {
    position == null && command != MissionCommand.TAKEOFF -> 2
    frame == AltitudeFrame.AMSL -> 0
    frame == AltitudeFrame.TERRAIN -> 10
    else -> 3
}

/** Any parse failure (bad JSON, a missing key, a bad number) becomes one IllegalArgumentException naming the format. */
private inline fun <T> parseOrExplain(format: String, parse: () -> T): T = try {
    parse()
} catch (e: IllegalArgumentException) {
    throw IllegalArgumentException("Not a valid $format file: ${e.message}", e)
} catch (e: NoSuchElementException) {
    throw IllegalArgumentException("Not a valid $format file: ${e.message}", e)
} catch (e: IndexOutOfBoundsException) {
    throw IllegalArgumentException("Not a valid $format file: ${e.message}", e)
}

private val prettyJson = Json { prettyPrint = true }
