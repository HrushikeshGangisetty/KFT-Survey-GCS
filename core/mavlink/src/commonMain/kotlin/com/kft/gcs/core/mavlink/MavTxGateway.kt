package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import kotlin.concurrent.Volatile
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import okio.IOException

/** What happened to one [MavTxGateway.send] call. */
sealed interface TxResult {
    /** Written to the link. It says nothing about whether the vehicle accepted it; that's what COMMAND_ACK is for. */
    data object Sent : TxResult

    /** Refused by the allowlist. Nothing was written. */
    data class Rejected(val message: String, val reason: String) : TxResult

    /** No link is open, so there was nowhere to write. */
    data object NotConnected : TxResult

    /** The link threw while writing (cable pulled, socket closed). */
    data class Failed(val cause: IOException) : TxResult
}

/**
 * Something that can send MAVLink to one vehicle. The real ones come from [MavTxGateway.forVehicle]. Protocols in
 * `core:vehicle` take this interface so their tests can use a scripted fake flight controller instead of a link.
 */
interface MavSender {
    suspend fun <T : MavMessage<T>> send(message: T): TxResult
}

/**
 * The only code that writes MAVLink to a link (CLAUDE.md §4). One per link, held by its [ConnectionManager].
 *
 * There is no plain "send". Every message goes out through [forVehicle], which names the vehicle it is for (ADR-005 §3):
 * the message must be addressed to that vehicle, and the allowlist in [TxPolicy] is checked against **that vehicle's**
 * armed flag and the current [podStatus]. The one exception is the GCS's own heartbeat ([sendHeartbeat]), which has
 * no target and only this module can send.
 *
 * Nothing outside `core:mavlink` can get the link's [FrameSink]: the gateway holds it privately, and only
 * [ConnectionManager] (same module) can attach or detach it.
 */
class MavTxGateway internal constructor(
    private val podStatus: StateFlow<PodStatus>,
    /** The vehicle with this system id as its latest heartbeat describes it, or null when it isn't heard. Read at send time. */
    private val vehicle: (systemId: UByte) -> VehicleInfo? = { null },
) {
    // Volatile: callers send from any thread (UI, protocol coroutines) while ConnectionManager swaps the link.
    // A send that races a detach just gets NotConnected or an IOException, which callers already handle.
    @Volatile
    private var link: FrameSink? = null

    private val _rejections = MutableSharedFlow<TxResult.Rejected>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every refused message, for the message log and tests. The allowlist rule says rejections must be logged. */
    val rejections: SharedFlow<TxResult.Rejected> = _rejections.asSharedFlow()

    internal fun attach(frames: FrameSink) {
        link = frames
    }

    internal fun detach() {
        link = null
    }

    /**
     * A sender that can only reach the vehicle with [systemId] on this link. Each message is refused unless:
     * 1. the allowlist allows it for that vehicle's armed flag and the pod state;
     * 2. its `target_system` is [systemId]. Not another vehicle's, and not 0, which every vehicle on the link would obey;
     * 3. the system id belongs to one vehicle only ([VehicleInfo.duplicateId]): a command to a duplicated id would be
     *    obeyed by two aircraft.
     */
    fun forVehicle(systemId: UByte): MavSender = object : MavSender {
        override suspend fun <T : MavMessage<T>> send(message: T): TxResult {
            val classification = TxPolicy.classify(message)
            val info = vehicle(systemId)
            val target = TxPolicy.targetSystem(message)
            val reason = TxPolicy.check(classification.category, podStatus.value, info?.armed)
                ?: "addressed to ${target?.let { "system $it" } ?: "no vehicle"}, but sent for system $systemId".takeIf { target != systemId }
                ?: "two vehicles are using system id $systemId on this link".takeIf { info?.duplicateId == true }
            if (reason != null) return TxResult.Rejected(classification.label, reason).also { _rejections.tryEmit(it) }
            return write(message)
        }
    }

    /** The GCS's own HEARTBEAT (minimal.xml): the one message with no target, so it is the link's, not a vehicle's. */
    internal suspend fun sendHeartbeat(heartbeat: Heartbeat): TxResult = write(heartbeat)

    /** Sends [message] as an unsigned MAVLink v2 frame from the GCS ids. Private: every caller above has checked it. */
    private suspend fun <T : MavMessage<T>> write(message: T): TxResult {
        val frames = link ?: return TxResult.NotConnected
        return try {
            // ponytail: always unsigned until S3 picks the key from the target vehicle's SigningStatus.
            frames.write(message, GCS_SYSTEM_ID, GCS_COMPONENT_ID, signFor = null)
            TxResult.Sent
        } catch (e: IOException) {
            TxResult.Failed(e)
        }
    }

    companion object {
        /** 255 is the conventional GCS system id; ArduPilot's SYSID_MYGCS defaults to it. */
        const val GCS_SYSTEM_ID: UByte = 255u

        /** MAV_COMP_ID_MISSIONPLANNER (190): the component id ground stations use. */
        const val GCS_COMPONENT_ID: UByte = 190u
    }
}
