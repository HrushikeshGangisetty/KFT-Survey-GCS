package com.kft.gcs.core.geoio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What ArduPilot documents about one parameter, from its parameter metadata file (`apm.pdef.json` / `apm.pdef.xml`,
 * generated from the firmware source by `Tools/autotest/param_metadata/param_parse.py`).
 *
 * The files are never bundled with the app: they're generated from GPL-3.0 source, and their licence is unclear (Pass
 * 18). The app downloads them from autotest.ardupilot.org at runtime and keeps a copy per firmware version on the
 * device, or the operator imports one by hand (KFT firmware).
 *
 * @property values the documented values, code to label, e.g. CAM1_TYPE 1 → "Servo". Empty for a free number.
 * @property bitmask the documented bits, bit number to label, e.g. 0 → "Roll". Empty unless the parameter is a mask.
 * @property range the documented range. ArduPilot doesn't enforce it; it's what the developers say is sensible.
 */
data class ParamMeta(
    val name: String,
    val displayName: String,
    val description: String,
    val units: String? = null,
    val range: ClosedFloatingPointRange<Double>? = null,
    val increment: Double? = null,
    val values: List<Pair<Double, String>> = emptyList(),
    val bitmask: List<Pair<Int, String>> = emptyList(),
    val rebootRequired: Boolean = false,
    val readOnly: Boolean = false,
)

/** Reads either format; the first character tells them apart (`{` JSON, `<` XML). Empty result = not a pdef file. */
fun parsePdef(text: String): Map<String, ParamMeta> {
    val body = text.trimStart('﻿', ' ', '\t', '\r', '\n')
    return when {
        body.startsWith("{") -> parsePdefJson(body)
        body.startsWith("<") -> parsePdefXml(body)
        else -> emptyMap()
    }
}

/**
 * `apm.pdef.json`: `{ "<group prefix>": { "<NAME>": { "DisplayName", "Description", "Units", "Range": {"low",
 * "high"}, "Increment", "Values": {"0": "…"}, "Bitmask": {"0": "…"}, "RebootRequired": "True", "ReadOnly": "True",
 * … } }, "json": {"version": 0} }`. The group keys don't matter to us (they aren't always the name's prefix), so every
 * group is flattened into one map by name. Fields we don't use (User, Calibration, Volatile, path) are ignored.
 */
internal fun parsePdefJson(text: String): Map<String, ParamMeta> {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyMap()
    return buildMap {
        root.forEach { (group, params) ->
            if (group == "json") return@forEach // format version marker, not a group
            (params as? JsonObject)?.forEach { (name, p) ->
                val o = p as? JsonObject ?: return@forEach
                fun str(key: String) = (o[key] as? JsonPrimitive)?.content
                fun pairs(key: String) = (o[key] as? JsonObject)?.map { (k, v) -> k to v.jsonPrimitive.content } ?: emptyList()
                val range = (o["Range"] as? JsonObject)?.let { r -> rangeOf(r["low"]?.jsonPrimitive?.content, r["high"]?.jsonPrimitive?.content) }
                put(
                    name,
                    ParamMeta(
                        name = name,
                        displayName = str("DisplayName") ?: "",
                        description = str("Description") ?: "",
                        units = str("Units"),
                        range = range,
                        increment = str("Increment")?.toDoubleOrNull(),
                        values = valuesOf(pairs("Values")),
                        bitmask = bitsOf(pairs("Bitmask")),
                        rebootRequired = str("RebootRequired").isTrue(),
                        readOnly = str("ReadOnly").isTrue(),
                    ),
                )
            }
        }
    }
}

/**
 * `apm.pdef.xml`: `<param name="ArduCopter:SYSID_THISMAV" humanName documentation user>` (vehicle parameters carry a
 * `Vehicle:` prefix, library ones don't) with `<field name="Range">1 255</field>`, `<field name="Bitmask">0:Roll,
 * 1:Pitch</field>` and `<values><value code="0">None</value></values>`. Read with the platform's SAX parser.
 * The versioned downloads (stable-X.Y.Z) exist only in this format.
 */
internal expect fun parsePdefXml(text: String): Map<String, ParamMeta>

/** Builds one parameter from the XML's raw strings; shared by the XML readers so both formats end up identical. */
internal fun metaFromXml(name: String, humanName: String, documentation: String, fields: Map<String, String>, values: List<Pair<String, String>>): ParamMeta {
    val range = fields["Range"]?.trim()?.split(Regex("\\s+"))?.takeIf { it.size == 2 }?.let { (low, high) -> rangeOf(low, high) }
    // "0:Roll,1:Pitch" and "0:SwitchPos1, 1:SwitchPos2" both occur.
    val bits = fields["Bitmask"]?.split(',')?.mapNotNull { part -> part.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
    return ParamMeta(
        name = name.substringAfter(':'),
        displayName = humanName,
        description = documentation,
        units = fields["Units"],
        range = range,
        increment = fields["Increment"]?.toDoubleOrNull(),
        values = valuesOf(values),
        bitmask = bitsOf(bits ?: emptyList()),
        rebootRequired = fields["RebootRequired"].isTrue(),
        readOnly = fields["ReadOnly"].isTrue(),
    )
}

private fun rangeOf(low: String?, high: String?): ClosedFloatingPointRange<Double>? {
    val lo = low?.trim()?.toDoubleOrNull() ?: return null
    val hi = high?.trim()?.toDoubleOrNull() ?: return null
    return if (lo <= hi) lo..hi else null
}

private fun valuesOf(pairs: List<Pair<String, String>>) = pairs.mapNotNull { (code, label) -> code.trim().toDoubleOrNull()?.let { it to label } }

private fun bitsOf(pairs: List<Pair<String, String>>) = pairs.mapNotNull { (bit, label) -> bit.trim().toIntOrNull()?.takeIf { it in 0..31 }?.let { it to label } }

private fun String?.isTrue() = this.equals("True", ignoreCase = true)

/** The vehicle names autotest.ardupilot.org uses: one for the latest (master) build, one for the versioned archive. */
enum class PdefVehicle(val latest: String, val versioned: String) {
    COPTER("ArduCopter", "Copter"),
    PLANE("ArduPlane", "Plane"),
}

/**
 * Where to download the metadata for [vehicle] running [version] (the AUTOPILOT_VERSION text, e.g. "4.5.7" or
 * "4.8.0-dev"), in the order to try. Layout of autotest.ardupilot.org/Parameters as of 2026-09:
 * - A release ("4.5.7", no suffix): `versioned/Copter/stable-4.5.7/`. Those folders hold only `apm.pdef.xml`, but
 *   `.json` is tried first in case it's ever added there (it's the smaller, faster format).
 * - Anything else (-dev, -beta, -rc, unknown): the latest build, `ArduCopter/apm.pdef.json`, then `.xml`. There's no
 *   archive for betas, and a dev build is closest to master.
 * A release that isn't in the archive is not silently given master's metadata: the caller says so and offers import.
 */
fun pdefUrls(vehicle: PdefVehicle, version: String?): List<String> {
    val base = "https://autotest.ardupilot.org/Parameters"
    val release = version?.takeIf { Regex("""\d+\.\d+\.\d+""").matches(it) }
    val dir = if (release != null) "$base/versioned/${vehicle.versioned}/stable-$release" else "$base/${vehicle.latest}"
    return listOf("$dir/apm.pdef.json", "$dir/apm.pdef.xml")
}

/** The cache name for one vehicle and firmware version, e.g. "Copter-4.5.7" or "Plane-4.8.0-dev". */
fun pdefCacheKey(vehicle: PdefVehicle, version: String?) = "${vehicle.versioned}-${version ?: "unknown"}"
