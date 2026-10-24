package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.kft.gcs.core.mavlink.signing.SigningStatus
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

    /** Refused by the allowlist or by signing (no key, keys locked). Nothing was written. */
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
 * Signing (ADR-006, PRD "Outbound rules") is decided here too, because this is the one place that knows which vehicle
 * a frame is for: the vehicle's [SigningStatus] picks the key, or refuses the send.
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

    // How each vehicle on the attached link signs, straight from its SigningSource. Read from the byte layer, not from
    // VehicleInfo: a vehicle whose heartbeats don't verify (NoKey, Locked) is never in VehicleInfo, and must still be
    // refused rather than sent unsigned.
    @Volatile
    private var signing: StateFlow<Map<UByte, SigningStatus>>? = null

    private val _rejections = MutableSharedFlow<TxResult.Rejected>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every refused message, for the message log and tests. The allowlist rule says rejections must be logged. */
    val rejections: SharedFlow<TxResult.Rejected> = _rejections.asSharedFlow()

    internal fun attach(frames: FrameSink, signingStatus: StateFlow<Map<UByte, SigningStatus>>) {
        signing = signingStatus
        link = frames
    }

    internal fun detach() {
        link = null
        signing = null
    }

    /** No heartbeat from [systemId] yet counts as unsigned: that is how every vehicle was sent to before signing. */
    private fun signingOf(systemId: UByte): SigningStatus = signing?.value?.get(systemId) ?: SigningStatus.Unsigned

    /**
     * A sender that can only reach the vehicle with [systemId] on this link. Each message is refused unless:
     * 1. the allowlist allows it for that vehicle's armed flag and the pod state;
     * 2. its `target_system` is [systemId]. Not another vehicle's, and not 0, which every vehicle on the link would obey;
     * 3. the system id belongs to one vehicle only ([VehicleInfo.duplicateId]): a command to a duplicated id would be
     *    obeyed by two aircraft;
     * 4. it can be signed the way the vehicle expects: a vehicle that signs gets frames signed with its own drone's
     *    key, an unsigned one gets unsigned frames, and one we hold no key for (or whose key is locked) gets nothing.
     *    Sending it unsigned would only be dropped by the flight controller, and would tell a listener we tried.
     */
    fun forVehicle(systemId: UByte): MavSender = object : MavSender {
        override suspend fun <T : MavMessage<T>> send(message: T): TxResult {
            val classification = TxPolicy.classify(message)
            val info = vehicle(systemId)
            val target = TxPolicy.targetSystem(message)
            val reason = TxPolicy.check(classification.category, podStatus.value, info?.armed)
                ?: "addressed to ${target?.let { "system $it" } ?: "no vehicle"}, but sent for system $systemId".takeIf { target != systemId }
                ?: "two vehicles are using system id $systemId on this link".takeIf { info?.duplicateId == true }
            if (reason != null) return reject(classification.label, reason)
            val signFor = when (val status = signingOf(systemId)) {
                SigningStatus.Unsigned -> null
                is SigningStatus.Signed -> status.droneId
                // Still bound: one bad heartbeat (perhaps a forger's) doesn't change which key the drone holds.
                is SigningStatus.BadSignature -> status.droneId
                SigningStatus.NoKey -> return reject(classification.label, NO_KEY)
                SigningStatus.Locked -> return reject(classification.label, LOCKED)
            }
            return write(message, signFor)
        }
    }

    private fun reject(label: String, reason: String) = TxResult.Rejected(label, reason).also { _rejections.tryEmit(it) }

    /**
     * The GCS's own HEARTBEAT (minimal.xml): the one message with no target, so it is the link's, not a vehicle's.
     *
     * With signing it goes out once per drone bound on this link, signed with that drone's key, because a signing
     * flight controller drops an unsigned one and would raise its GCS failsafe. One more goes out unsigned if any vehicle
     * here is unsigned or none has been heard yet (the first heartbeat is what makes a vehicle start talking). A vehicle
     * we have no key for gets none: it would drop it anyway.
     *
     * Returns the first failure, or [TxResult.Sent] if every copy went out (also when none was due).
     */
    internal suspend fun sendHeartbeat(heartbeat: Heartbeat): TxResult {
        if (link == null) return TxResult.NotConnected
        val statuses = signing?.value.orEmpty().values
        val bound = statuses.mapNotNull {
            when (it) {
                is SigningStatus.Signed -> it.droneId
                is SigningStatus.BadSignature -> it.droneId
                else -> null
            }
        }.distinct()
        val unsigned = statuses.isEmpty() || SigningStatus.Unsigned in statuses
        val copies: List<String?> = bound + if (unsigned) listOf(null) else emptyList()
        return copies.map { write(heartbeat, signFor = it) }.firstOrNull { it != TxResult.Sent } ?: TxResult.Sent
    }

    /**
     * Sends [message] as one MAVLink v2 frame from the GCS ids, signed for [signFor] (a drone id) or unsigned when null.
     * Private: every caller above has checked it.
     */
    private suspend fun <T : MavMessage<T>> write(message: T, signFor: String?): TxResult {
        val frames = link ?: return TxResult.NotConnected
        return try {
            // FrameSink throws IOException if the key went away since the status was read (vault locked mid-send),
            // so a frame meant to be signed is never sent unsigned.
            frames.write(message, GCS_SYSTEM_ID, GCS_COMPONENT_ID, signFor)
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

        internal const val NO_KEY = "vehicle signs its traffic; no key for it on this device"
        internal const val LOCKED = "signing keys are locked; unlock in Settings"
    }
}
