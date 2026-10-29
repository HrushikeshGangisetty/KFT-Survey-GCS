package com.kft.gcs.core.geoio

/**
 * One record of an ArduPilot DataFlash log (.bin), with its fields by name. Numbers come back as [Double] already
 * scaled the way the format letter says (see [readDataFlash]); text fields as [String].
 */
class DataFlashRecord internal constructor(private val columns: Map<String, Int>, private val values: Array<Any>) {
    /** A numeric field, or null when this message has no such field (or it is text). */
    fun num(name: String): Double? = columns[name]?.let { values[it] as? Double }

    /** A text field ("n", "N", "Z" letters), trailing NULs removed, or null when there is none. */
    fun text(name: String): String? = columns[name]?.let { values[it] as? String }

    /** TimeUS, the FC's microseconds since boot, which almost every message starts with. */
    val timeUs: Double? get() = num("TimeUS")
}

/**
 * Reads the messages named in [wanted] from a DataFlash log: the binary format ArduPilot writes to its SD card and that
 * the Logs page downloads (Pass 46).
 *
 * The format describes itself. Every message is `A3 95 <type>` then a payload. Type 128 is FMT: it says, for one type
 * number, the message's name, total length, a format string (one letter per field) and the field names. So the reader
 * learns the layout from the log itself, which is why one reader works for every ArduPilot version and for the KFT
 * fork's own messages.
 *
 * Format letters and sizes are from ArduPilot `libraries/AP_Logger/LogStructure.h` (the comment block at the top) and
 * how pymavlink's DFReader scales them: c/C/e/E are hundredths (÷100), L is degrees × 10^7 (÷10^7). Little-endian.
 * Read for the maths only; no code copied.
 *
 * A damaged stretch (a power cut mid-write, a corrupt block) is skipped one byte at a time until the next `A3 95`
 * header, as ArduPilot's own tools do, so one bad byte costs one message, not the rest of the flight. Message types
 * never declared by an FMT are skipped the same way. Records come back in log order, per name.
 */
fun readDataFlash(bytes: ByteArray, wanted: Set<String>): Map<String, List<DataFlashRecord>> {
    val formats = HashMap<Int, Format>()
    val out = HashMap<String, MutableList<DataFlashRecord>>()
    var i = 0
    while (i + 3 <= bytes.size) {
        if (bytes[i] != HEAD1 || bytes[i + 1] != HEAD2) { i++; continue }
        val type = bytes[i + 2].toInt() and 0xFF
        if (type == FMT_TYPE) {
            if (i + FMT_LENGTH > bytes.size) break
            parseFmt(bytes, i + 3)?.let { formats[it.type] = it }
            i += FMT_LENGTH
            continue
        }
        val format = formats[type]
        if (format == null || i + format.length > bytes.size) { i++; continue }
        if (format.name in wanted) out.getOrPut(format.name) { ArrayList() }.add(format.decode(bytes, i + 3))
        i += format.length
    }
    return out
}

private const val HEAD1 = 0xA3.toByte()
private const val HEAD2 = 0x95.toByte()
private const val FMT_TYPE = 128

/** FMT is 3 header bytes + Type(B) Length(B) Name(n) Format(N) Columns(Z): 3 + 1 + 1 + 4 + 16 + 64. */
private const val FMT_LENGTH = 89

private class Format(val type: Int, val length: Int, val name: String, val letters: String, labels: List<String>) {
    private val columns = labels.withIndex().associate { (index, label) -> label to index }

    fun decode(b: ByteArray, start: Int): DataFlashRecord {
        var p = start
        val values = Array<Any>(letters.length) { k ->
            val letter = letters[k]
            val size = LETTER_SIZE.getValue(letter)
            val v: Any = when (letter) {
                'b' -> b[p].toDouble()
                'B', 'M' -> (b[p].toInt() and 0xFF).toDouble()
                'h' -> le(b, p, 2).toShort().toDouble()
                'H' -> le(b, p, 2).toDouble()
                'c' -> le(b, p, 2).toShort() / 100.0
                'C' -> le(b, p, 2) / 100.0
                'i' -> le(b, p, 4).toInt().toDouble()
                'I' -> le(b, p, 4).toDouble()
                'e' -> le(b, p, 4).toInt() / 100.0
                'E' -> le(b, p, 4) / 100.0
                'L' -> le(b, p, 4).toInt() / 1e7
                'f' -> Float.fromBits(le(b, p, 4).toInt()).toDouble()
                'd' -> Double.fromBits(le(b, p, 8))
                // ponytail: uint64 above 2^63 reads negative; TimeUS would need 292 000 years of uptime to get there.
                'q', 'Q' -> le(b, p, 8).toDouble()
                'n', 'N', 'Z' -> text(b, p, size)
                else -> 0.0 // 'a' (int16[32]): no field we read uses it, so it is only skipped.
            }
            p += size
            v
        }
        return DataFlashRecord(columns, values)
    }
}

/** Bytes per format letter (LogStructure.h). An FMT with any other letter is ignored, so its messages are skipped. */
private val LETTER_SIZE = mapOf(
    'b' to 1, 'B' to 1, 'M' to 1, 'h' to 2, 'H' to 2, 'c' to 2, 'C' to 2, 'i' to 4, 'I' to 4, 'e' to 4, 'E' to 4,
    'L' to 4, 'f' to 4, 'd' to 8, 'q' to 8, 'Q' to 8, 'n' to 4, 'N' to 16, 'Z' to 64, 'a' to 64,
)

private fun parseFmt(b: ByteArray, p: Int): Format? {
    val type = b[p].toInt() and 0xFF
    val length = b[p + 1].toInt() and 0xFF
    val name = text(b, p + 2, 4)
    val letters = text(b, p + 6, 16)
    val labels = text(b, p + 22, 64).split(',')
    // A format whose letters don't add up to its length can't be decoded safely; better to skip its messages.
    val payload = letters.sumOf { LETTER_SIZE[it] ?: return null }
    if (payload + 3 != length || labels.size != letters.length) return null
    return Format(type, length, name, letters, labels)
}

private fun le(b: ByteArray, p: Int, n: Int): Long {
    var v = 0L
    for (k in n - 1 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
    return v
}

private fun text(b: ByteArray, p: Int, n: Int): String {
    var end = p
    while (end < p + n && b[end] != 0.toByte()) end++
    return b.decodeToString(p, end)
}
