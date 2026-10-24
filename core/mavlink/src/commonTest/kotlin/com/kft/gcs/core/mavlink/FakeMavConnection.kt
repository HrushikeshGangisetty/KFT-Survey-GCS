package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.StreamState
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavModeFlag
import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.definitions.minimal.MavType
import com.kft.gcs.core.mavlink.signing.NoSigning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import okio.Buffer
import okio.IOException

/**
 * A scripted stand-in for a real link: the test decides what the "vehicle" sends ([receive]), when the link
 * breaks ([fail]) and whether opening works ([failOnConnect]), and reads back everything the GCS sent ([sent]).
 * Sends go through a real [FrameSink] into [wire], as they do on a real link (ADR-006).
 */
internal class FakeMavConnection(private val failOnConnect: IOException? = null) : CoroutinesMavConnection {
    override val streamState = MutableStateFlow<StreamState>(StreamState.Inactive.Stopped)
    override val mavFrame = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 64)
    /** The bytes the GCS wrote, exactly as they would go out on the link. */
    val wire = Buffer()
    val frameSink = FrameSink(wire, NoSigning, { 0L }, Dispatchers.Unconfined)
    var closed = false

    /** Every message the GCS sent, decoded from [wire] the way a vehicle would (COMMAND_LONG comes back as a plain `CommandLong`). */
    val sent: List<MavMessage<*>>
        get() {
            val bytes = wire.copy()
            val reader = BufferedMavConnection(bytes, bytes, bytes, ArdupilotmegaDialect)
            return buildList { while (bytes.size > 0) add(reader.next().message) }
        }

    /** This link as [ConnectionManager] opens it. */
    fun opened() = OpenedLink(this) { frameSink }

    override suspend fun connect(readerScope: CoroutineScope) {
        failOnConnect?.let { throw it }
        streamState.value = StreamState.Active
    }

    override suspend fun close() {
        closed = true
        streamState.value = StreamState.Inactive.Stopped
    }

    override suspend fun <T : MavMessage<T>> sendUnsignedV2(systemId: UByte, componentId: UByte, payload: T) =
        error("the GCS writes through FrameSink, never the library's send (ADR-006)")

    override suspend fun <T : MavMessage<T>> sendV1(systemId: UByte, componentId: UByte, payload: T) =
        error("the GCS only sends MAVLink v2")

    override suspend fun <T : MavMessage<T>> sendSignedV2(
        systemId: UByte, componentId: UByte, payload: T, linkId: UByte, timestamp: UInt, secretKey: ByteArray,
    ) = error("signing is not used yet")

    fun receive(message: MavMessage<*>, seq: Int, systemId: UByte = 1u, componentId: UByte = 1u) {
        check(mavFrame.tryEmit(Frame(seq.toUByte(), systemId, componentId, message)))
    }

    fun fail() {
        streamState.value = StreamState.Inactive.Failed(IOException("cable pulled"))
    }

    private data class Frame(
        override val sequence: UByte,
        override val systemId: UByte,
        override val componentId: UByte,
        override val message: MavMessage<*>,
        override val checksum: UShort = 0u,
    ) : MavFrame<MavMessage<*>>
}

/** A connection over a real [transport], for transport tests: no signing keys, writes on the caller's thread. */
internal fun transportConnection(transport: MavTransport) = TransportMavConnection(transport, NoSigning, { 0L }, Dispatchers.Unconfined)

/** An ArduCopter heartbeat, as SITL sends it once per second. */
fun copterHeartbeat(armed: Boolean = false, customMode: UInt = 0u) = Heartbeat(
    type = MavEnumValue.of(MavType.QUADROTOR),
    autopilot = MavEnumValue.of(MavAutopilot.ARDUPILOTMEGA),
    baseMode = if (armed) MavBitmaskValue.of(MavModeFlag.SAFETY_ARMED) else MavBitmaskValue.fromValue(0u),
    customMode = customMode,
    mavlinkVersion = 3u,
)
