package com.kft.gcs.core.mavlink.signing

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * [signV2], [verifyV2] and [trailerOf] against frames made by **pymavlink 2.4.50** (an independent encoder, the one
 * ArduPilot's tools use), never against our own output.
 *
 * The vectors come from S1v: `GNSS-Denied/tools/signing/make_vectors.py` at commit 76241ec, copied unchanged into
 * `src/commonTest/resources/signing/signing_v1.json`. That generator's pytest also checks every signed frame with
 * pymavlink's own verifier. Each positive vector holds the unsigned frame pymavlink packed and the signed frame
 * pymavlink packed for the same message, key, link_id and timestamp; each negative vector is a signed frame with one
 * bit flipped in a named region, which pymavlink rejects.
 */
class Mav2SigningTest {

    @Serializable
    private data class VectorFile(val vectors: List<Vector>)

    @Serializable
    private data class Vector(
        val name: String,
        @SerialName("key_hex") val keyHex: String,
        @SerialName("link_id") val linkId: Int,
        val timestamp: Long,
        @SerialName("unsigned_frame_hex") val unsignedHex: String,
        @SerialName("signed_frame_hex") val signedHex: String,
        @SerialName("crc_extra") val crcExtra: Int,
        val expected: Boolean,
        val mutation: Mutation? = null,
    ) {
        val key get() = keyHex.hex()
        val unsigned get() = unsignedHex.hex()
        val signed get() = signedHex.hex()
    }

    @Serializable
    private data class Mutation(val region: String)

    // Gradle runs both the desktop and the Android host tests with the module directory as the working directory.
    private val vectors: List<Vector> = lenientJson.decodeFromString<VectorFile>(
        FileSystem.SYSTEM.read("src/commonTest/resources/signing/signing_v1.json".toPath()) { readUtf8() },
    ).vectors

    private val positive = vectors.filter { it.expected }
    private val negative = vectors.filter { !it.expected }

    @Test
    fun theVectorFileCoversWhatS1vPromised() {
        // If a regenerated file silently lost cases, every loop below could pass on nothing.
        assertEquals(72, positive.size)
        assertEquals(48, negative.size)
        assertEquals(setOf(0L, 1L, 37_264_320_000_000L, MAX_SIGNING_TIMESTAMP), positive.map { it.timestamp }.toSet())
        assertEquals(setOf(0, 1, 254), positive.map { it.linkId }.toSet())
        assertEquals(
            setOf("magic", "header", "signed_flag", "payload", "crc", "link_id", "timestamp", "signature"),
            negative.map { it.mutation!!.region }.toSet(),
        )
    }

    @Test
    fun signingPymavlinksUnsignedFrameGivesPymavlinksSignedFrame() {
        for (v in positive) {
            val ours = signV2(v.unsigned, v.crcExtra.toByte(), v.key, v.linkId.toUByte(), v.timestamp)
            assertContentEquals(v.signed, ours, v.name)
        }
    }

    @Test
    fun pymavlinksSignedFramesVerify() {
        for (v in positive) assertTrue(verifyV2(v.signed, v.key), v.name)
    }

    @Test
    fun pymavlinksOneBitMutationsAreRejected() {
        // One region per vector: magic, header, signed flag, payload, CRC, link_id, timestamp, signature.
        for (v in negative) assertFalse(verifyV2(v.signed, v.key), v.name)
    }

    @Test
    fun everySingleBitFlipOfEverySignedFrameIsRejected() {
        // Wider than the named mutations: every bit of every pymavlink frame, so no byte escapes the signature.
        for (v in positive) {
            val frame = v.signed
            for (byte in frame.indices) for (bit in 0 until 8) {
                val flipped = frame.copyOf().also { it[byte] = (it[byte].toInt() xor (1 shl bit)).toByte() }
                if (verifyV2(flipped, v.key)) fail("${v.name}: flip of byte $byte bit $bit still verified")
            }
        }
    }

    @Test
    fun setTheSignedFlagAndTheCrcIsRecomputed() {
        for (v in positive) {
            val ours = signV2(v.unsigned, v.crcExtra.toByte(), v.key, v.linkId.toUByte(), v.timestamp)
            val crcAt = v.unsigned.size - 2
            assertEquals(v.unsigned[2].toInt() or 0x01, ours[2].toInt(), v.name)
            // pymavlink's CRC for the flagged header, which can't equal the unflagged one (X.25 catches any 1-bit change).
            assertContentEquals(v.signed.copyOfRange(crcAt, crcAt + 2), ours.copyOfRange(crcAt, crcAt + 2), v.name)
            assertNotEquals(
                v.unsigned.copyOfRange(crcAt, crcAt + 2).toList(), ours.copyOfRange(crcAt, crcAt + 2).toList(), v.name,
            )
        }
    }

    @Test
    fun aWrongKeyFailsVerification() {
        for (v in positive) {
            val wrong = v.key.copyOf().also { it[31] = (it[31].toInt() xor 0x01).toByte() }
            assertFalse(verifyV2(v.signed, wrong), v.name)
        }
    }

    @Test
    fun trailerOfReadsPymavlinksLinkIdAndTimestamp() {
        for (v in positive) assertEquals(SignatureTrailer(v.linkId.toUByte(), v.timestamp), trailerOf(v.signed), v.name)
    }

    @Test
    fun unsignedFramesHaveNoTrailerAndNeverVerify() {
        for (v in positive) {
            assertNull(trailerOf(v.unsigned), v.name)
            assertFalse(verifyV2(v.unsigned, v.key), v.name)
        }
    }

    @Test
    fun timestampsOutside48BitsAreRefused() {
        val v = positive.first()
        // 0 and 2^48 - 1 are signed correctly by the vector tests above; one step past either end is refused.
        assertFailsWith<IllegalArgumentException> { signV2(v.unsigned, v.crcExtra.toByte(), v.key, 0u, -1) }
        assertFailsWith<IllegalArgumentException> { signV2(v.unsigned, v.crcExtra.toByte(), v.key, 0u, 1L shl 48) }
    }

    @Test
    fun badInputsAreRefused() {
        val v = positive.first()
        assertFailsWith<IllegalArgumentException> { signV2(v.unsigned, v.crcExtra.toByte(), ByteArray(31), 0u, 0) }
        assertFailsWith<IllegalArgumentException> { verifyV2(v.signed, ByteArray(33)) }
        assertFailsWith<IllegalArgumentException>("already signed") {
            signV2(v.signed, v.crcExtra.toByte(), v.key, 0u, 0)
        }
        assertFailsWith<IllegalArgumentException>("truncated") {
            signV2(v.unsigned.copyOf(v.unsigned.size - 1), v.crcExtra.toByte(), v.key, 0u, 0)
        }
        assertFalse(verifyV2(v.signed.copyOf(v.signed.size - 1), v.key), "truncated signed frame")
    }
}

// The file also carries derivation vectors and metadata that S4 reads; this test only needs the frames.
private val lenientJson = Json { ignoreUnknownKeys = true }

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
