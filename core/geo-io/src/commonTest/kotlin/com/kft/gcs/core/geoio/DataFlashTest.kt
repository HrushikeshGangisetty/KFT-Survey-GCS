package com.kft.gcs.core.geoio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataFlashTest {
    /**
     * A hand-built log: FMT for a test message, two records, garbage between them, then a message type with no FMT.
     * Byte values were written out by hand from LogStructure.h's letter table, so the expected numbers are independent
     * of the reader: e.g. Lat 'L' = 0x0A5A_7F34 = 10·16^6 + 90·65536 + 127·256 + 52 = 173 702 964 → 17.3702964°;
     * Spd 'C' = 0x04D2 = 1234 → 12.34 m/s.
     */
    @Test
    fun recordsAreDecodedByTheirOwnFmt() {
        val log = fmt(type = 200, name = "TEST", letters = "QLCfbZ", labels = "TimeUS,Lat,Spd,Err,Tmp,Msg") +
            record(200, u64(25_244_065), u32(0x0A5A7F34), u16(0x04D2), f32(-2.5f), byteArrayOf(-3), str("hello", 64)) +
            byteArrayOf(0xA3.toByte(), 1, 2, 3) + // a torn header: skipped byte by byte
            record(200, u64(26_000_000), u32(0x0A5A7F35), u16(0), f32(0f), byteArrayOf(0), str("", 64)) +
            byteArrayOf(0xA3.toByte(), 0x95.toByte(), 77, 9, 9, 9) // type 77 was never declared

        val test = readDataFlash(log, setOf("TEST"))["TEST"]!!
        assertEquals(2, test.size)
        assertEquals(25_244_065.0, test[0].timeUs)
        assertEquals(17.3702964, test[0].num("Lat")!!, 1e-9)
        assertEquals(12.34, test[0].num("Spd")!!, 1e-9)
        assertEquals(-2.5, test[0].num("Err"))
        assertEquals(-3.0, test[0].num("Tmp"))
        assertEquals("hello", test[0].text("Msg"))
        assertEquals(17.3702965, test[1].num("Lat")!!, 1e-9)
        assertNull(test[0].num("Nope"))
        assertNull(test[0].num("Msg"), "text is not a number")
    }

    @Test
    fun unwantedAndBadlyDeclaredMessagesAreSkipped() {
        // Letters add up to 4 bytes but the FMT claims 3 + 8: the reader can't trust it, so its records are dropped.
        val bad = fmt(type = 201, name = "BAD", letters = "I", labels = "A", length = 11)
        val log = bad + record(201, u32(1), u32(2)) + fmt(type = 202, name = "OK", letters = "B", labels = "A") + record(202, byteArrayOf(5))
        val read = readDataFlash(log, setOf("BAD", "OK"))
        assertNull(read["BAD"])
        assertEquals(5.0, read["OK"]!!.single().num("A"))
        assertTrue(readDataFlash(log, emptySet()).isEmpty())
    }

    private fun header(type: Int) = byteArrayOf(0xA3.toByte(), 0x95.toByte(), type.toByte())
    private fun record(type: Int, vararg parts: ByteArray) = parts.fold(header(type)) { a, b -> a + b }
    private fun fmt(type: Int, name: String, letters: String, labels: String, length: Int? = null): ByteArray {
        val sizes = mapOf('Q' to 8, 'L' to 4, 'C' to 2, 'f' to 4, 'b' to 1, 'Z' to 64, 'I' to 4, 'B' to 1)
        val len = length ?: (3 + letters.sumOf { sizes.getValue(it) })
        return record(128, byteArrayOf(type.toByte(), len.toByte()), str(name, 4), str(letters, 16), str(labels, 64))
    }
    private fun str(s: String, n: Int) = s.encodeToByteArray().copyOf(n)
    private fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
    private fun u16(v: Int) = le(v.toLong(), 2)
    private fun u32(v: Long) = le(v, 4)
    private fun u32(v: Int) = le(v.toLong(), 4)
    private fun u64(v: Long) = le(v, 8)
    private fun f32(v: Float) = le(v.toRawBits().toLong(), 4)
}
