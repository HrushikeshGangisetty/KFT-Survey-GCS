package com.kft.gcs.core.vehicle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The pure parts of the KFT login (spec S12): HMAC, key and challenge parsing, float packing. */
class KftCryptoTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /**
     * RFC 4231 §4 test vectors for HMAC-SHA-256: independent reference values. Case 5 is the RFC's own truncation
     * case (first 128 bits). Also checked against Python's `hmac` module while writing this pass.
     */
    @Test
    fun rfc4231Vectors() {
        val cases = listOf(
            Triple(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray(),
                "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            Triple("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray(),
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"),
            Triple(ByteArray(20) { 0xaa.toByte() }, ByteArray(50) { 0xdd.toByte() },
                "773ea91e36800e46854db8ebd09181a72959098b3ef8c122d9635514ced565fe"),
            Triple(ByteArray(25) { (it + 1).toByte() }, ByteArray(50) { 0xcd.toByte() },
                "82558a389a443c0ea4cc819899f2083a85f0faa3e578f8077a2e3ff46729665b"),
            Triple(ByteArray(20) { 0x0c }, "Test With Truncation".encodeToByteArray(),
                "a3b6167473100ee06e0c796c2955552b"), // truncated to 128 bits in the RFC
            Triple(ByteArray(131) { 0xaa.toByte() }, "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54"),
            Triple(
                ByteArray(131) { 0xaa.toByte() },
                ("This is a test using a larger than block-size key and a larger than block-size data. The key needs " +
                    "to be hashed before being used by the HMAC algorithm.").encodeToByteArray(),
                "9b09ffa71b942fcb27635fbcd5b0e944bfdc63644f0713938a7f51535c3a35e2",
            ),
        )
        cases.forEachIndexed { i, (key, data, expected) ->
            assertEquals(expected, hmacSha256(key, data).hex().take(expected.length), "RFC 4231 case ${i + 1}")
        }
    }

    @Test
    fun keyMustBeExactly64HexCharacters() {
        val good = "00112233445566778899aabbccddeeff" + "00112233445566778899AABBCCDDEEFF"
        assertContentEquals(hex(good.lowercase()), parseKftKey(good))
        assertContentEquals(hex(good.lowercase()), parseKftKey("  $good\n"), "surrounding whitespace is fine")
        assertNull(parseKftKey(""), "no key")
        assertNull(parseKftKey(good.dropLast(2)), "31 bytes")
        assertNull(parseKftKey(good + "00"), "33 bytes")
        assertNull(parseKftKey(good.replaceFirst('0', 'g')), "not hex")
    }

    /** Exactly the firmware's format: `snprintf("KFTCH1:%02x…")`, 16 bytes each, trailing NULs from the fixed field. */
    @Test
    fun challengeHalves() {
        val h = "000102030405060708090a0b0c0d0eff"
        assertContentEquals(hex(h), parseChallengeHalf("KFTCH1:$h", "KFTCH1:"))
        assertContentEquals(hex(h), parseChallengeHalf("KFTCH2:$h\u0000\u0000", "KFTCH2:"))
        assertNull(parseChallengeHalf("KFTCH2:$h", "KFTCH1:"), "wrong half")
        assertNull(parseChallengeHalf("KFTCH1:${h.dropLast(1)}", "KFTCH1:"), "short")
        assertNull(parseChallengeHalf("KFTCH1:${h.dropLast(2)}zz", "KFTCH1:"), "not hex")
        assertNull(parseChallengeHalf("PreArm: GPS not healthy", "KFTCH1:"))
    }

    /**
     * The packing, checked by hand on the first float: MAC bytes b0 b1 b2 b3 become the little-endian int
     * b3b2b1b0, and the float holds exactly those bits. Every float is compared by its raw bits, never by value
     * (a NaN never equals itself).
     */
    @Test
    fun responseFloatsAreTheMacBytesLittleEndian() {
        val key = ByteArray(32) { it.toByte() }
        val challenge = ByteArray(32) { (255 - it).toByte() }
        val mac = hmacSha256(key, challenge)
        val floats = kftResponseParams(key, challenge)
        assertEquals(6, floats.size, "24 bytes = 6 floats (param2..param7)")
        floats.forEachIndexed { f, value ->
            val bits = value.toRawBits()
            for (b in 0 until 4) assertEquals(mac[4 * f + b], (bits shr (8 * b)).toByte(), "float $f byte $b")
        }
    }
}
