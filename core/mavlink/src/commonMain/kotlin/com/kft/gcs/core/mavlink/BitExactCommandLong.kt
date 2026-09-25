package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.serialization.truncateZeros

/**
 * COMMAND_LONG (common.xml #76) serialized with every float's **raw** bit pattern.
 *
 * Why this exists: mavlink-kotlin 1.2.15 encodes floats with `Float.floatToIntBits` (checked with `javap` on
 * `SerializationUtilKt.encodeFloat`). That call turns every NaN into the one canonical `0x7FC00000`. Normally
 * harmless, but the KFT login (spec S12) packs 24 bytes of HMAC into param2..param7 as raw bit patterns, and the
 * firmware `memcpy`s them back out (ardupilotKFT `GCS_Common.cpp` USER_2 intercept). Any 4-byte chunk that
 * happens to look like a NaN (about 1 in 256 per float, so roughly 2.3% of logins) would be changed and the
 * login refused. [MavTxGateway] sends every COMMAND_LONG through this class, so the bytes on the wire are exactly
 * the bits the caller put in.
 *
 * Same message id and CRC extra as [CommandLong], so the vehicle can't tell the difference. The byte layout is
 * MAVLink's: fields sorted by size (7 × float32, uint16 command, then 3 × uint8), little-endian. A test checks it
 * byte for byte against mavlink-kotlin's own encoding for ordinary values.
 */
internal class BitExactCommandLong(val command: CommandLong) : MavMessage<BitExactCommandLong> {

    override val instanceCompanion get() = Companion

    override fun serializeV1(): ByteArray {
        val out = ByteArray(SIZE)
        var i = 0
        fun int32(v: Int) {
            for (shift in 0 until 32 step 8) out[i++] = (v shr shift).toByte()
        }
        // floatArrayOf, not listOf: a List<Float> boxes each value, and nothing here should touch the bits.
        val params = with(command) { floatArrayOf(param1, param2, param3, param4, param5, param6, param7) }
        for (p in params) int32(p.toRawBits())
        val cmd = command.command.value.toInt()
        out[i++] = cmd.toByte()
        out[i++] = (cmd shr 8).toByte()
        out[i++] = command.targetSystem.toByte()
        out[i++] = command.targetComponent.toByte()
        out[i] = command.confirmation.toByte()
        return out
    }

    /** MAVLink 2 drops trailing zero bytes from the payload; the library's own helper does exactly that. */
    override fun serializeV2(): ByteArray = serializeV1().truncateZeros()

    companion object : MavMessage.MavCompanion<BitExactCommandLong> {
        private const val SIZE = 33
        override val id: UInt get() = CommandLong.id
        override val crcExtra: Byte get() = CommandLong.crcExtra
        override fun deserialize(bytes: ByteArray) = BitExactCommandLong(CommandLong.deserialize(bytes))
    }
}
