package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandInt
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavResult
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.MavTxGateway
import com.kft.gcs.core.mavlink.TxResult
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** How one command ended. */
internal sealed interface CommandResult {
    /** COMMAND_ACK with MAV_RESULT_ACCEPTED. */
    data object Accepted : CommandResult

    /** The vehicle answered with anything but ACCEPTED: DENIED, UNSUPPORTED, FAILED, TEMPORARILY_REJECTED, … */
    data class Refused(val result: MavResult) : CommandResult

    /** No final COMMAND_ACK after every attempt (or an IN_PROGRESS that never finished). */
    data object NoAck : CommandResult

    /** It never left the GCS: the gateway refused it, there was no link, or the write failed. */
    data class NotSent(val tx: TxResult) : CommandResult
}

/**
 * The MAVLink command protocol (https://mavlink.io/en/services/command.html): send COMMAND_LONG or COMMAND_INT,
 * wait for the matching COMMAND_ACK, resend if none comes. Messages are in `common.xml`.
 *
 * ArduPilot specifics (spec S10, `libraries/GCS_MAVLink/GCS_Common.cpp`, master 2026-09):
 * - Every COMMAND_LONG and COMMAND_INT gets exactly one COMMAND_ACK, sent from the autopilot's own ids with
 *   `target_system`/`target_component` set to the sender (us, 255/190). Older firmware leaves them 0.
 * - COMMAND_LONG is converted to COMMAND_INT before it is handled, so both forms behave the same. A build with
 *   COMMAND_LONG compiled out answers `COMMAND_INT_ONLY`; that comes back here as [CommandResult.Refused].
 * - Long-running commands (calibrations, some camera commands) first answer IN_PROGRESS and later send a second
 *   ACK with the final result.
 *
 * One command at a time ([mutex]). ACKs are matched on command id alone, so two of the same command in flight
 * couldn't be told apart; commands are rare (a few at connect), so serialising them costs nothing.
 *
 * @param frames every frame received on the link; the protocol only looks at COMMAND_ACKs from the target.
 */
internal class CommandProtocol(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val sender: MavSender,
) {
    private val mutex = Mutex()

    /**
     * Sends [command] and waits for its result. Retries carry an incremented `confirmation` field, which is how
     * MAVLink tells the vehicle "this is a resend of the same command", not a new one.
     */
    suspend fun send(command: CommandLong): CommandResult =
        exchange(command.command.value, command.targetSystem, command.targetComponent) { attempt ->
            command.copy(confirmation = attempt.toUByte())
        }

    /** Sends [command] and waits for its result. COMMAND_INT has no `confirmation` field, so resends are identical. */
    suspend fun send(command: CommandInt): CommandResult =
        exchange(command.command.value, command.targetSystem, command.targetComponent) { command }

    private suspend fun <T : MavMessage<T>> exchange(
        commandId: UInt,
        targetSystem: UByte,
        targetComponent: UByte,
        build: (attempt: Int) -> T,
    ): CommandResult = mutex.withLock {
        coroutineScope {
            val acks = Channel<CommandAck>(Channel.UNLIMITED)
            // UNDISPATCHED: the listener is subscribed before the first send, so a fast ACK can't be missed.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { frame ->
                    val ack = frame.message as? CommandAck ?: return@collect
                    if (ack.command.value == commandId &&
                        frame.systemId == targetSystem && frame.componentId == targetComponent &&
                        ack.targetSystem.let { it == 0.toUByte() || it == MavTxGateway.GCS_SYSTEM_ID }
                    ) {
                        acks.send(ack)
                    }
                }
            }
            try {
                awaitResult(acks, build)
            } finally {
                listener.cancel()
            }
        }
    }

    private suspend fun <T : MavMessage<T>> awaitResult(acks: ReceiveChannel<CommandAck>, build: (Int) -> T): CommandResult {
        for (attempt in 0 until ATTEMPTS) {
            val tx = sender.send(build(attempt))
            if (tx != TxResult.Sent) return CommandResult.NotSent(tx)
            // A late ACK to an earlier attempt also counts: it's the same command.
            var ack = withTimeoutOrNull(ACK_TIMEOUT) { acks.receive() } ?: continue
            // IN_PROGRESS means the vehicle has the command and is working on it. Resending could restart it, so
            // stop retrying and wait (longer) for the ACK that carries the final result.
            while (ack.result.value == MavResult.IN_PROGRESS.value) {
                ack = withTimeoutOrNull(IN_PROGRESS_TIMEOUT) { acks.receive() } ?: return CommandResult.NoAck
            }
            return if (ack.result.value == MavResult.ACCEPTED.value) {
                CommandResult.Accepted
            } else {
                // entry is null for a result number newer than our dialect; treat it as a plain failure.
                CommandResult.Refused(ack.result.entry ?: MavResult.FAILED)
            }
        }
        return CommandResult.NoAck
    }

    companion object {
        /**
         * ArduPilot answers in the same main-loop pass it reads the command (milliseconds), so the wait is mostly
         * radio round trip. A 57600-baud SiK radio carrying 4 Hz telemetry stays well under 0.5 s; 1.5 s leaves room
         * for a congested link without making a real loss slow to detect.
         */
        val ACK_TIMEOUT = 1.5.seconds

        /** First try plus two resends: about 4.5 s before giving up, which a person waiting on the UI tolerates. */
        const val ATTEMPTS = 3

        /** How long a command may stay IN_PROGRESS. None of the commands we send do; this bounds a bad actor. */
        val IN_PROGRESS_TIMEOUT = 30.seconds
    }
}
