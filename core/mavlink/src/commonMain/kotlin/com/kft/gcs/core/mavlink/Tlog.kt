package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.MavRawFrame
import kotlinx.coroutines.flow.Flow

/**
 * Writes one vehicle's received frames somewhere until its coroutine is cancelled. The real one is `TlogFiles`
 * (`java.io`, so it lives in `jvmCommonMain` and common code names it through this); tests record into a list.
 */
fun interface VehicleRecorder {
    suspend fun record(vehicle: VehicleId, frames: Flow<MavFrame<out MavMessage<*>>>)
}

/**
 * One telemetry-log record for [frame], in the format MAVProxy and Mission Planner write and read (`.tlog`): the time
 * as 8 bytes, big-endian microseconds since 1970 (pymavlink `mavutil.mavlogfile`), followed by the MAVLink frame.
 *
 * mavlink-kotlin hands us decoded frames without their original bytes, so the frame is serialized again as unsigned
 * MAVLink v2, with the sender's own sequence number and ids. For a v2 frame that is byte for byte what arrived
 * (a test checks one against the same frame packed by pymavlink). A frame that arrived as MAVLink v1 or signed is logged as plain v2.
 */
internal fun tlogRecord(timeMicros: Long, frame: MavFrame<out MavMessage<*>>): ByteArray {
    val bytes = MavRawFrame.createUnsignedV2(
        seq = frame.sequence,
        systemId = frame.systemId,
        componentId = frame.componentId,
        messageId = frame.message.instanceCompanion.id,
        payload = frame.message.serializeV2(),
        crcExtra = frame.message.instanceCompanion.crcExtra,
    ).rawBytes
    return ByteArray(8) { i -> (timeMicros shr (8 * (7 - i))).toByte() } + bytes
}
