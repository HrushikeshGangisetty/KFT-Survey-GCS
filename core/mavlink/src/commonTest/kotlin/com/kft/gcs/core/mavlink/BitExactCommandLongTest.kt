package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okio.Buffer

/**
 * The KFT login packs HMAC bytes into COMMAND_LONG floats as raw bit patterns (spec S12). These tests prove the
 * patterns reach the wire unchanged, NaNs included.
 */
class BitExactCommandLongTest {

    // Signalling NaN, quiet NaN with a payload, negative NaN with every bit set, and +infinity (not a NaN, the edge).
    private val patterns = listOf(0x7FA12345, 0x7FC00001, -1 /* 0xFFFFFFFF */, 0x7F800000, 0x3F800000 /* 1.0f */, 0x00000001)

    private fun command(bits: List<Int>) = CommandLong(
        targetSystem = 1u, targetComponent = 1u, command = MavEnumValue.of(MavCmd.USER_2), confirmation = 0u,
        param1 = 1f,
        param2 = Float.fromBits(bits[0]), param3 = Float.fromBits(bits[1]), param4 = Float.fromBits(bits[2]),
        param5 = Float.fromBits(bits[3]), param6 = Float.fromBits(bits[4]), param7 = Float.fromBits(bits[5]),
    )

    /** Little-endian int32 at [offset], read straight from bytes (no float conversion anywhere). */
    private fun ByteArray.int32(offset: Int) =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

    /**
     * Characterisation of the library bug this class works around: mavlink-kotlin 1.2.15's own encoder rewrites a
     * NaN to 0x7FC00000. If a library update fixes it, this test fails, and BitExactCommandLong can be deleted.
     */
    @Test
    fun libraryEncoderCanonicalisesNaN() {
        val payload = command(patterns).serializeV1()
        assertEquals(0x7FC00000, payload.int32(4), "param2 0x7FA12345 comes out as the canonical NaN")
    }

    /** For ordinary values our layout must be the library's layout, byte for byte (independent check of the layout). */
    @Test
    fun sameBytesAsTheLibraryForOrdinaryValues() {
        val ordinary = CommandLong(
            targetSystem = 7u, targetComponent = 190u, command = MavEnumValue.of(MavCmd.SET_MESSAGE_INTERVAL),
            confirmation = 2u, param1 = 242f, param2 = 1.0e7f, param3 = -3.5f, param4 = 0f, param5 = 1e-30f,
            param6 = Float.MAX_VALUE, param7 = -0f,
        )
        assertContentEquals(ordinary.serializeV1(), BitExactCommandLong(ordinary).serializeV1())
        assertContentEquals(ordinary.serializeV2(), BitExactCommandLong(ordinary).serializeV2())
        // Trailing zeros are truncated in v2 exactly like the library does.
        val short = CommandLong(command = MavEnumValue.of(MavCmd.REQUEST_MESSAGE), param1 = 148f)
        assertContentEquals(short.serializeV2(), BitExactCommandLong(short).serializeV2())
    }

    /**
     * The test the pass asked for: a COMMAND_LONG with NaN bit patterns in its params goes through the gateway and a
     * real mavlink-kotlin frame encoder, and the frame bytes hold exactly those patterns. Decoding the frame (as the
     * vehicle would) gives the same bits back.
     */
    @Test
    fun nanBitPatternsSurviveTheGatewayToTheWire() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod))
        gateway.attach(link)
        assertEquals(TxResult.Sent, gateway.send(command(patterns)))
        val onTheLink = assertIs<BitExactCommandLong>(link.sent.single(), "the gateway swaps in the bit-exact encoder")

        // Encode it as a real MAVLink 2 frame, the way the connection does, and look at the bytes.
        val wire = Buffer()
        BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect).sendUnsignedV2(255u, 190u, onTheLink)
        val frame = wire.copy().readByteArray()
        val payloadStart = 10 // MAVLink 2 header: magic, len, incompat, compat, seq, sysid, compid, msgid×3
        assertEquals(0xFD, frame[0].toInt() and 0xFF, "MAVLink 2 magic")
        assertEquals(76, frame.int32(7) and 0xFFFFFF, "message id COMMAND_LONG")
        patterns.forEachIndexed { i, bits ->
            assertEquals(bits, frame.int32(payloadStart + 4 + 4 * i), "param${i + 2} bytes on the wire")
        }

        // The vehicle side decodes the frame (checksum included) and gets the same bits.
        val decoded = assertIs<CommandLong>(BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect).next().message)
        val decodedBits = with(decoded) { listOf(param2, param3, param4, param5, param6, param7) }.map { it.toRawBits() }
        assertEquals(patterns, decodedBits)
    }
}
