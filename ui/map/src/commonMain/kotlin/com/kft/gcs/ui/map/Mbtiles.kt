package com.kft.gcs.ui.map

/** What an MBTiles file holds, as far as the map cares: images it can draw, vector tiles it can't (yet), or neither. */
enum class MbtilesKind { RASTER, VECTOR, UNKNOWN }

/**
 * Reads the tile format of an MBTiles file from its first bytes, without a SQLite library.
 *
 * An MBTiles file is a SQLite database whose `metadata` table holds name/value text rows, and the spec (MBTiles 1.3)
 * requires a `format` row: png, jpg, webp (images) or pbf (vector). SQLite stores a row's text columns back to
 * back, so that row appears in the file as the bytes `format` + `png`. The metadata table is written when the file
 * is created, before the tiles, so it sits in the first pages (true for GDAL, tippecanoe, mb-util, QGIS and
 * MOBAC output).
 *
 * ponytail: byte search, not a SQLite reader. A file whose metadata sits past [head] reads as UNKNOWN and is
 * refused with a message; add a real SQLite reader (sqlite-jdbc / android.database) if that shows up in the field.
 */
fun mbtilesKind(head: ByteArray): MbtilesKind {
    if (!head.startsWith(SQLITE_MAGIC)) return MbtilesKind.UNKNOWN
    val text = head.decodeToString() // invalid bytes become U+FFFD; the ASCII we look for survives intact
    val format = FORMAT_ROW.find(text)?.groupValues?.get(1) ?: return MbtilesKind.UNKNOWN
    return if (format == "pbf") MbtilesKind.VECTOR else MbtilesKind.RASTER
}

/** How much of the file [mbtilesKind] needs to see: the metadata table is in the first few pages. */
const val MBTILES_HEAD_BYTES = 1 shl 20

// Every SQLite 3 database starts with this 16-byte header string, NUL included.
private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()

// The format row's name and value, back to back; the value is one the MBTiles spec lists (pbf, jpg, png, webp) or jpeg, which some tools write.
private val FORMAT_ROW = Regex("format(png|jpeg|jpg|webp|pbf)")

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
