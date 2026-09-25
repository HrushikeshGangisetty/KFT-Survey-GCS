package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavResult
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.MavTxGateway
import com.kft.gcs.core.mavlink.TxResult
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A scripted flight controller for protocol tests. Everything the GCS sends lands in [sent]. [reply] decides what
 * the "vehicle" answers to each message (nothing, one frame or several), and [frames] carries those answers back,
 * exactly like `ConnectionManager.frames` does in the app.
 */
class FakeFc(val systemId: UByte = 1u, val componentId: UByte = 1u) : MavSender {
    val frames = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 256)
    val sent = mutableListOf<MavMessage<*>>()

    /** The vehicle's answer to each message the GCS sends. Default: silence. */
    var reply: (MavMessage<*>) -> List<MavMessage<*>> = { emptyList() }

    /** What the "gateway" returns. Anything but Sent means the message never reaches the vehicle. */
    var txResult: TxResult = TxResult.Sent

    override suspend fun <T : MavMessage<T>> send(message: T): TxResult {
        if (txResult != TxResult.Sent) return txResult
        sent += message
        reply(message).forEach { emit(it) }
        return TxResult.Sent
    }

    /** The vehicle sends [message] on its own (a telemetry frame, a late ACK). */
    fun emit(message: MavMessage<*>, systemId: UByte = this.systemId, componentId: UByte = this.componentId) =
        check(frames.tryEmit(TestFrame(systemId, componentId, message)))

    inline fun <reified T : MavMessage<*>> sentOf(): List<T> = sent.filterIsInstance<T>()
}

/** An ACK the way ArduPilot sends it: addressed back to the GCS ids. */
fun ackFor(command: CommandLong, result: MavResult = MavResult.ACCEPTED) = CommandAck(
    command = command.command,
    result = MavEnumValue.of(result),
    targetSystem = MavTxGateway.GCS_SYSTEM_ID,
    targetComponent = MavTxGateway.GCS_COMPONENT_ID,
)

data class TestFrame(
    override val systemId: UByte,
    override val componentId: UByte,
    override val message: MavMessage<*>,
    override val sequence: UByte = 0u,
    override val checksum: UShort = 0u,
) : MavFrame<MavMessage<*>>
