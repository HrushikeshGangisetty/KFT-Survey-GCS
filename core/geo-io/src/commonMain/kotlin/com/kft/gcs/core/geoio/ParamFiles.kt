package com.kft.gcs.core.geoio

// ---- Mission Planner .param ----
// Format: one parameter per line, `NAME,VALUE`; `#` starts a comment line. Mission Planner also reads a space or tab
// in place of the comma. ArduPilot's own defaults-file parser (AP_Param::parse_param_line, master 2026-09) splits on
// any of ", =\t" and allows a third field `@READONLY`, so SITL files like tools/sitl/camera.parm read here too.
// Checked against both, read not copied.

/** A `.param` file read back. [badLines] are 1-based line numbers that weren't `NAME,VALUE`, for the operator. */
data class ParamFileImport(val values: Map<String, Float>, val badLines: List<Int>)

/**
 * Mission Planner `.param` text: `NAME,VALUE` lines sorted by name. [values] are already formatted the way the
 * vehicle's type needs ("3", not "3.0", for an integer), so this only lays them out.
 */
fun encodeParamFile(values: Map<String, String>): String =
    values.entries.sortedBy { it.key }.joinToString("") { (name, value) -> "$name,$value\n" }

/**
 * Reads `.param` text. A name repeated later wins, as it does in ArduPilot's defaults files. Names follow ArduPilot's
 * rule: 1–16 characters of A–Z, 0–9 and `_` (AP_MAX_NAME_SIZE); anything else is a bad line, not a guess.
 */
fun parseParamFile(text: String): ParamFileImport {
    val values = LinkedHashMap<String, Float>()
    val bad = mutableListOf<Int>()
    text.lines().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        // A trailing @READONLY (ArduPilot defaults files) is the vehicle's business, not a value: drop it.
        val parts = line.split(',', ' ', '=', '\t').filter { it.isNotEmpty() }.let { if (it.getOrNull(2) == "@READONLY") it.take(2) else it }
        val value = parts.getOrNull(1)?.toFloatOrNull()?.takeIf { it.isFinite() }
        if (parts.size != 2 || value == null || !NAME.matches(parts[0])) bad += i + 1 else values[parts[0]] = value
    }
    return ParamFileImport(values, bad)
}

private val NAME = Regex("[A-Z0-9_]{1,16}")
