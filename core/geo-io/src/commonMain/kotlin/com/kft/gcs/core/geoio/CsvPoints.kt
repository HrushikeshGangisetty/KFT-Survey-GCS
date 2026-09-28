package com.kft.gcs.core.geoio

import com.kft.gcs.core.geo.LatLon

/**
 * Points from a CSV or text list, as people actually write them:
 * - separated by commas, semicolons, tabs or spaces (whichever splits the lines consistently);
 * - with or without a header; a header's latitude/longitude/name columns are found by name (lat, latitude, y;
 *   lon, lng, long, longitude, x; name, label, id…), and other columns (altitude, notes) are ignored;
 * - decimal degrees, decimal commas when the separator isn't a comma ("17,385;78,486"), hemisphere letters
 *   ("17.385N 78.486E"), or degrees-minutes-seconds ("17°23'06.1\"N"; "17 23 06.1 N" only when the file isn't
 *   space-separated);
 * - with no header, latitude first, unless the first column holds values past ±90, which only a longitude can.
 * Rows that aren't coordinates are skipped and counted in a note, never guessed.
 */
internal fun parseCsvPoints(text: String): ImportedShapes {
    val lines = text.lines().withIndex().filter { (_, l) -> l.isNotBlank() && !l.trimStart().startsWith("#") }
    require(lines.isNotEmpty()) { "The file is empty." }
    val sep = separatorOf(lines.take(10).map { it.value })
    val rows = lines.map { (i, l) -> i + 1 to split(l, sep) }
    val decimalComma = sep != ','

    val first = rows.first().second
    // A data row has at least two coordinates in it; a header has none (or one, like "Point 1").
    val header = first.count { parseCoordinate(it, decimalComma) != null } < 2
    var latCol: Int
    var lonCol: Int
    var nameCol: Int? = null
    val body: List<Pair<Int, List<String>>>
    if (header) {
        val names = first.map { it.lowercase().filter(Char::isLetter) }
        latCol = names.indexOfFirst { it in LAT_NAMES }
        lonCol = names.indexOfFirst { it in LON_NAMES }
        nameCol = names.indexOfFirst { it in NAME_NAMES }.takeIf { it >= 0 }
        require(latCol >= 0 && lonCol >= 0) { "The header (${first.joinToString()}) has no latitude and longitude columns." }
        body = rows.drop(1)
    } else {
        latCol = 0
        lonCol = 1
        body = rows
        // Only a longitude can be past ±90: if the first column ever is, the file is lon, lat.
        if (body.any { (_, cells) -> (cells.getOrNull(0)?.let { parseCoordinate(it, decimalComma) } ?: 0.0).let { kotlin.math.abs(it) > 90 } }) {
            latCol = 1
            lonCol = 0
        }
    }

    val points = mutableListOf<NamedPoint>()
    val skipped = mutableListOf<Int>()
    for ((lineNo, cells) in body) {
        val lat = cells.getOrNull(latCol)?.let { parseCoordinate(it, decimalComma) }
        val lon = cells.getOrNull(lonCol)?.let { parseCoordinate(it, decimalComma) }
        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) skipped += lineNo
        else points += NamedPoint(nameCol?.let { cells.getOrNull(it)?.takeIf(String::isNotBlank) }, LatLon(lat, lon))
    }
    require(points.isNotEmpty()) { "No row of the file has a latitude and a longitude." }
    val notes = if (skipped.isEmpty()) emptyList()
    else listOf("${skipped.size} row(s) skipped (not coordinates): line ${skipped.take(5).joinToString()}${if (skipped.size > 5) "…" else ""}.")
    return ImportedShapes(points = points, notes = notes)
}

// Not easting/northing: those are metres in a projected grid, not degrees.
private val LAT_NAMES = setOf("lat", "latitude", "y", "latdeg", "latdd")
private val LON_NAMES = setOf("lon", "lng", "long", "longitude", "x", "londeg", "londd")
private val NAME_NAMES = setOf("name", "label", "id", "title", "point", "wp", "waypoint", "description")

/**
 * The first of comma, semicolon, tab and space that splits every sample line into the same number (≥ 2) of fields.
 * Order matters: "35.36 S;149.16 W" splits on spaces too, but the semicolon is the separator.
 */
private fun separatorOf(sample: List<String>): Char =
    listOf(',', ';', '\t', ' ').firstOrNull { sep ->
        val counts = sample.map { split(it, sep).size }
        counts.all { it == counts[0] } && counts[0] >= 2
    } ?: ','

/**
 * Splits on [sep] as RFC 4180 does: a quoted cell may hold the separator, and `""` inside it is one quote
 * (`"17°23'06""N"` → `17°23'06"N`). Runs of spaces count as one when splitting on spaces.
 */
private fun split(line: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            quoted && ch == '"' -> quoted = false
            !quoted && ch == '"' && cell.isBlank() -> quoted = true
            !quoted && ch == sep -> { out += cell.toString().trim(); cell.clear() }
            else -> cell.append(ch)
        }
        i++
    }
    out += cell.toString().trim()
    return if (sep == ' ') out.filter { it.isNotEmpty() } else out
}

// 17°23'06.1"N · 17 23 06.1 N · 17d23m06s N · N17°23.1' (degrees, optional minutes and seconds, a hemisphere).
private val DMS = Regex("""^([NSEWnsew])?\s*(-?\d+(?:[.,]\d+)?)\s*(?:°|d|\s)\s*(?:(\d+(?:[.,]\d+)?)\s*(?:'|′|m|\s)\s*)?(?:(\d+(?:[.,]\d+)?)\s*(?:"|″|''|s)?\s*)?([NSEWnsew])?$""")

/**
 * One coordinate in degrees: decimal ("17.385", "-78.5", "17,385" when [decimalComma]), with an optional hemisphere
 * letter (S and W negative), or degrees-minutes-seconds. Null when [text] isn't a coordinate.
 */
internal fun parseCoordinate(text: String, decimalComma: Boolean): Double? {
    val s = text.trim().let { if (decimalComma) it.replace(',', '.') else it }
    if (s.isEmpty()) return null
    val hemi = s.first().takeIf { it.uppercaseChar() in "NSEW" } ?: s.last().takeIf { it.uppercaseChar() in "NSEW" }
    val sign = if (hemi != null && hemi.uppercaseChar() in "SW") -1 else 1
    val core = s.trim { it.uppercaseChar() in "NSEW" || it.isWhitespace() }
    core.toDoubleOrNull()?.let { return sign * it }
    val m = DMS.matchEntire(s.replace(',', '.')) ?: return null
    val deg = m.groupValues[2].toDouble()
    val min = m.groupValues[3].toDoubleOrNull() ?: 0.0
    val sec = m.groupValues[4].toDoubleOrNull() ?: 0.0
    if (min >= 60 || sec >= 60) return null
    val magnitude = kotlin.math.abs(deg) + min / 60 + sec / 3600
    return sign * (if (deg < 0) -magnitude else magnitude)
}
