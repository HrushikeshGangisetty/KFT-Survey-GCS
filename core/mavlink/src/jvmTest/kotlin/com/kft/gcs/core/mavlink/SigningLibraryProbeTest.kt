package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrameV2
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavState
import com.divpundir.mavlink.definitions.minimal.MavType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.Buffer

/**
 * Records what mavlink-kotlin 1.2.15 does with MAVLink 2 **signed** frames (signing stage S0a, ADR-006).
 *
 * This is not a test of our code: it pins the library's behaviour, so the signing layer is built on facts and a
 * library upgrade that changes any of them fails here first. Each test says what the bytecode (`javap -c` on
 * connection-core-jvm 1.2.15, quoted in ADR-006) predicts and checks it against real bytes.
 *
 * The reference frames come from pymavlink 2.4.50, an independent encoder that ArduPilot and MAVProxy use:
 * ```
 * os.environ['MAVLINK20'] = '1'; from pymavlink.dialects.v20 import ardupilotmega as m
 * mav = m.MAVLink(None, srcSystem=1, srcComponent=1); mav.seq = 0
 * mav.signing.secret_key = bytes([0x42] * 32); mav.signing.link_id = 7
 * mav.signing.timestamp = TS; mav.signing.sign_outgoing = True
 * m.MAVLink_heartbeat_message(2, 3, 81, 5, 4, 3).pack(mav).hex()
 * ```
 * pymavlink's own parser, given the same key, accepts both frames.
 */
class SigningLibraryProbeTest {
    private val key = ByteArray(32) { 0x42 }

    // QUADROTOR (2), ARDUPILOTMEGA (3), base_mode 81, custom_mode 5, ACTIVE (4), mavlink_version 3: the same
    // heartbeat TlogTest uses, so its unsigned pymavlink frame differs from these only in the signing parts.
    private val heartbeat = Heartbeat(
        type = MavEnumValue.of(MavType.QUADROTOR), autopilot = MavEnumValue.of(MavAutopilot.ARDUPILOTMEGA),
        baseMode = MavBitmaskValue.fromValue(81u), customMode = 5u, systemStatus = MavEnumValue.of(MavState.ACTIVE), mavlinkVersion = 3u,
    )

    /** A real 48-bit timestamp: 0x2234_5678_9ABC ticks of 10 µs is about 11.9 years after 2015-01-01, i.e. late 2026. */
    private val realTimestamp = 0x2234_5678_9ABCL

    // Layout (MAVLink 2 spec): 10-byte header | 9-byte payload | CRC (2) | link_id (1) | timestamp (6, LE) | signature (6).
    //   fd 09 01 00 00 01 01 000000 | 050000000203510403 | bb7a | 07 | bc9a78563422 | 2b31429d76c5
    // incompat_flags = 0x01 (signed). The CRC bytes are bb 7a, not the unsigned frame's 4b a0, because the CRC covers
    // the flag byte.
    private val pymavlinkRealTs = "fd090100000101000000050000000203510403bb7a07bc9a785634222b31429d76c5"

    /** The same frame with timestamp 0x5678_9ABC, which fits in 32 bits: only the timestamp and signature differ. */
    private val pymavlinkLowTs = "fd090100000101000000050000000203510403bb7a07bc9a78560000d9cc6aa23225"

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun connectionReading(vararg frames: ByteArray): BufferedMavConnection {
        val wire = Buffer()
        frames.forEach { wire.write(it) }
        return BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
    }

    /**
     * Finding 1: `sendSignedV2`'s timestamp is a `UInt` (JVM descriptor `(BBLMavMessage;BI[B)V`, mangled
     * `sendSignedV2-Ndr1L8U`), and `createSignedV2` writes it as `(ts.toLong() and 0xFFFFFFFF)` into the 6-byte field.
     * So the top two bytes are always zero. The closest a caller can get to the real timestamp is `toUInt()`, and the
     * frame that goes out is pymavlink's *low-timestamp* frame byte for byte, not the real one.
     */
    @Test
    fun sendSignedV2CanOnlyWriteA32BitTimestamp() {
        val wire = Buffer()
        BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
            .sendSignedV2(1u, 1u, heartbeat, 7u, realTimestamp.toUInt(), key)
        val sent = wire.readByteArray()

        assertEquals(pymavlinkLowTs, hex(sent), "the high 16 bits of the timestamp are gone")
        // So the latest time it can express is UInt.MAX_VALUE ticks = 4 294 967 295 × 10 µs = 42 949.7 s, 11.9 hours
        // after 2015-01-01.
        assertEquals("bc9a78560000", hex(sent.copyOfRange(22, 28)), "timestamp field: low 4 bytes, then two zeros")
    }

    /**
     * Finding 2: the parser reads all 6 timestamp bytes but keeps them in an `Int` (`safeDecodeUnsignedInteger(6)`,
     * then `l2i`), so `signatureTimestamp` is the low 32 bits. The frame is **passed through** (not dropped): its CRC
     * is checked with the incompat flag in place (the X.25 run covers the raw header bytes), and the payload decodes.
     */
    @Test
    fun aSignedFrameIsDecodedButItsTimestampIsTruncated() {
        val frame = assertIs<MavFrameV2<*>>(connectionReading(unhex(pymavlinkRealTs)).next())

        assertEquals(heartbeat, frame.message)
        assertTrue(frame.isSigned)
        assertEquals(7u, frame.signatureLinkId.toUInt())
        assertEquals(0x5678_9ABCu, frame.signatureTimestamp, "0x2234 above bit 31 is lost")
        assertEquals("2b31429d76c5", hex(frame.signature))
        assertEquals(0x7abb, frame.checksum.toInt(), "bb 7a on the wire, little-endian")
    }

    /**
     * Finding 3: `validateSignature` hashes the frame's **original raw bytes** (header, payload, CRC) and its link id,
     * but the **truncated** timestamp, zero-extended back to 6 bytes. So it fails for every real frame even with the
     * right key, and only works while the timestamp fits in 32 bits.
     */
    @Test
    fun validateSignatureFailsForARealTimestampEvenWithTheRightKey() {
        val real = assertIs<MavFrameV2<*>>(connectionReading(unhex(pymavlinkRealTs)).next())
        assertFalse(real.validateSignature(key), "right key, real 48-bit timestamp: rejected")

        val low = assertIs<MavFrameV2<*>>(connectionReading(unhex(pymavlinkLowTs)).next())
        assertTrue(low.validateSignature(key), "right key, 32-bit timestamp: accepted, so the hash itself is right")
        assertFalse(low.validateSignature(ByteArray(32) { 0x43 }), "wrong key: rejected")
    }

    /**
     * Finding 2, continued: `next()` never checks a signature (it only resolves the message id and checks the CRC), so
     * a frame with a forged signature comes out exactly like a genuine one. A bad CRC, by contrast, is dropped
     * silently and the reader moves on to the next frame.
     */
    @Test
    fun forgedSignaturesPassAndBadCrcsAreDropped() {
        val forged = unhex(pymavlinkRealTs).also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val badCrc = unhex(pymavlinkRealTs).also { it[19] = (it[19] + 1).toByte() }
        val conn = connectionReading(badCrc, forged)

        val out = assertIs<MavFrameV2<*>>(conn.next(), "the bad-CRC frame was skipped; the forged one came out")
        assertEquals(heartbeat, out.message)
        assertContentEquals(forged.copyOfRange(28, 34), out.signature)
        assertFalse(out.validateSignature(key))
    }

    /**
     * Finding 4: each `BufferedMavConnection` keeps its own sequence counter (an `AtomicIntegerFieldUpdater` on a
     * private `sequence` field, taken before its private `writeLock`). Two writers on one sink therefore repeat
     * sequence numbers, and their locks don't exclude each other, so frames could also interleave mid-frame.
     */
    @Test
    fun twoWritersOnOneSinkRepeatSequenceNumbers() {
        val wire = Buffer()
        val a = BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
        val b = BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
        a.sendUnsignedV2(255u, 190u, heartbeat)
        a.sendUnsignedV2(255u, 190u, heartbeat)
        b.sendUnsignedV2(255u, 190u, heartbeat)

        val reader = BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
        assertEquals(listOf(0, 1, 0), List(3) { reader.next().sequence.toInt() })
    }
}
