package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
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
 * Something that can send MAVLink to the vehicle. [MavTxGateway] is the only real one. Protocols in `core:vehicle`
 * take this interface so their tests can use a scripted fake flight controller instead of a link.
 */
interface MavSender {
    suspend fun <T : MavMessage<T>> send(message: T): TxResult
}

/**
 * The only code that writes MAVLink to a vehicle (CLAUDE.md §4). Every message is classified against the
 * allowlist in [TxPolicy] and checked against the current [podStatus] and [vehicleArmed] before it reaches the link.
 *
 * Nothing outside `core:mavlink` can get the underlying connection: the gateway holds it privately, and only
 * [ConnectionManager] (same module) can attach or detach it.
 */
class MavTxGateway internal constructor(
    private val podStatus: StateFlow<PodStatus>,
    /** The armed flag from the vehicle's latest heartbeat, or null when no vehicle is heard. Read at send time. */
    private val vehicleArmed: () -> Boolean? = { null },
) : MavSender {
    // Volatile: callers send from any thread (UI, protocol coroutines) while ConnectionManager swaps the link.
    // A send that races a detach just gets NotConnected or an IOException, which callers already handle.
    @Volatile
    private var link: CoroutinesMavConnection? = null

    private val _rejections = MutableSharedFlow<TxResult.Rejected>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every refused message, for the message log and tests. The allowlist rule says rejections must be logged. */
    val rejections: SharedFlow<TxResult.Rejected> = _rejections.asSharedFlow()

    internal fun attach(connection: CoroutinesMavConnection) {
        link = connection
    }

    internal fun detach() {
        link = null
    }

    /** Checks [message] against the allowlist and, if allowed, sends it as an unsigned MAVLink v2 frame from the GCS ids. */
    override suspend fun <T : MavMessage<T>> send(message: T): TxResult {
        val classification = TxPolicy.classify(message)
        TxPolicy.check(classification.category, podStatus.value, vehicleArmed())?.let { reason ->
            return TxResult.Rejected(classification.label, reason).also { _rejections.tryEmit(it) }
        }
        val connection = link ?: return TxResult.NotConnected
        return try {
            // COMMAND_LONG goes out bit-exact: the library's own encoder would rewrite NaN bit patterns.
            // ponytail: only COMMAND_LONG; other float messages still use the library encoder. Extend if one ever carries bit patterns.
            if (message is CommandLong) connection.sendUnsignedV2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, BitExactCommandLong(message))
            else connection.sendUnsignedV2(GCS_SYSTEM_ID, GCS_COMPONENT_ID, message)
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
