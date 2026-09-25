package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavMissionResult
import com.divpundir.mavlink.definitions.common.MavMissionType
import com.divpundir.mavlink.definitions.common.MissionAck
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MissionRequest
import com.divpundir.mavlink.definitions.common.MissionRequestInt
import com.divpundir.mavlink.definitions.common.MissionRequestList
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.MavTxGateway
import com.kft.gcs.core.mavlink.TxResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Why a mission transfer stopped. The message is written for the operator. */
class MissionTransferException(message: String) : Exception(message)

/**
 * The MAVLink mission protocol (https://mavlink.io/en/services/mission.html) for the plain mission list
 * (MAV_MISSION_TYPE_MISSION). Every message here is in `common.xml`. Works on wire items; [Mission.kt] converts.
 *
 * ```
 * UPLOAD      GCS                               vehicle
 *             MISSION_COUNT(n) ───────────────▶
 *                  ◀─────────────── MISSION_REQUEST(_INT)(k)   ┐ repeats until k = n-1; a repeated k
 *             MISSION_ITEM_INT(k) ────────────▶               ┘ (vehicle missed our item) gets it again
 *                  ◀─────────────── MISSION_ACK(ACCEPTED | error)
 * DOWNLOAD    MISSION_REQUEST_LIST ───────────▶
 *                  ◀─────────────── MISSION_COUNT(n)
 *             MISSION_REQUEST_INT(k) ─────────▶               ┐ k = 0 … n-1
 *                  ◀─────────────── MISSION_ITEM_INT(k)        ┘
 *             MISSION_ACK(ACCEPTED) ──────────▶
 * CLEAR       MISSION_CLEAR_ALL ──────────────▶
 *                  ◀─────────────── MISSION_ACK
 * Any wait:   no answer in 1.5 s → resend what we sent last; 5 tries, then fail.
 * Cancel:     cancelling the coroutine sends MISSION_ACK(OPERATION_CANCELLED).
 * ```
 *
 * ArduPilot specifics (spec S10; `libraries/GCS_MAVLink/MissionItemProtocol.cpp`, `AP_Mission.cpp`, master 2026-09):
 * - It asks for upload items with the **deprecated MISSION_REQUEST**, not MISSION_REQUEST_INT, and accepts
 *   MISSION_ITEM_INT in reply. We treat both requests the same.
 * - It re-requests the current item every 1 s by itself, and gives up after 8 s of silence ("Mission upload
 *   timeout", then MISSION_ACK OPERATION_CANCELLED). Our 1.5 s × 5 tries fits inside that.
 * - An item with an unexpected seq gets MISSION_ACK(INVALID_SEQUENCE) **but the upload carries on**, so during an
 *   upload that ACK is ignored rather than treated as failure. A duplicate of an item we resent can cause it.
 * - It writes items to storage as they arrive and truncates to the new count at MISSION_COUNT. A cancelled or
 *   failed upload therefore leaves a partial mission on the vehicle; the error says so.
 * - It ignores MISSION_ACK from a GCS entirely, so our download-complete and cancel ACKs are for other autopilots.
 * - Downloads are stateless on its side, but refused (DENIED) while someone is uploading.
 *
 * One transfer at a time ([mutex]): MAVLink has no transfer id, so two at once would mix replies.
 */
// MISSION_REQUEST is deprecated in common.xml, but ArduPilot still sends it during uploads, so we must read it.
@Suppress("DEPRECATION")
internal class MissionProtocol(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val sender: MavSender,
) {
    private val mutex = Mutex()

    /** Uploads [wire] (seq 0 = home, see [missionToWire]). [onProgress] gets (items sent, total). */
    suspend fun upload(target: Target, wire: List<MissionItemInt>, onProgress: (Int, Int) -> Unit): Result<Unit> =
        transfer(target) { inbox ->
            var reply = exchange(inbox, MissionCount(target.system, target.component, wire.size.toUShort()), "MISSION_COUNT", ::uploadReply)
            while (reply is UploadReply.Request) {
                val seq = reply.seq
                val item = wire.getOrNull(seq) ?: fail("the vehicle asked for item $seq of ${wire.size}")
                onProgress(seq + 1, wire.size)
                reply = exchange(inbox, item, "mission item $seq", ::uploadReply)
            }
        }.recoverPartial()

    /** Reads the whole mission, seq 0 (home) included. [onProgress] gets (items received, total). */
    suspend fun download(target: Target, onProgress: (Int, Int) -> Unit): Result<List<MissionItemInt>> = transfer(target) { inbox ->
        val count = exchange(inbox, MissionRequestList(target.system, target.component), "MISSION_REQUEST_LIST") { m ->
            (m as? MissionCount)?.count?.toInt() ?: failOnErrorAck(m)
        }
        val items = (0 until count).map { seq ->
            onProgress(seq, count)
            exchange(inbox, MissionRequestInt(target.system, target.component, seq.toUShort()), "mission item $seq") { m ->
                (m as? MissionItemInt)?.takeIf { it.seq.toInt() == seq } ?: failOnErrorAck(m)
            }
        }
        onProgress(count, count)
        sender.send(MissionAck(target.system, target.component, MavEnumValue.of(MavMissionResult.MAV_MISSION_ACCEPTED)))
        items
    }

    /** Deletes the vehicle's mission (home stays: ArduPilot keeps index 0). */
    suspend fun clear(target: Target): Result<Unit> = transfer(target) { inbox ->
        exchange(inbox, MissionClearAll(target.system, target.component), "MISSION_CLEAR_ALL") { m ->
            (m as? MissionAck)?.takeIf { it.type.value == MavMissionResult.MAV_MISSION_ACCEPTED.value } ?: failOnErrorAck(m)
        }
    }.map { }

    /** The vehicle's MAVLink ids. */
    data class Target(val system: UByte, val component: UByte)

    private sealed interface UploadReply {
        data class Request(val seq: Int) : UploadReply
        data object Done : UploadReply
    }

    private fun uploadReply(m: MavMessage<*>): UploadReply? = when {
        m is MissionRequestInt -> UploadReply.Request(m.seq.toInt())
        m is MissionRequest -> UploadReply.Request(m.seq.toInt()) // ArduPilot's choice, see the class KDoc
        m is MissionAck && m.type.value == MavMissionResult.MAV_MISSION_ACCEPTED.value -> UploadReply.Done
        m is MissionAck && m.type.value == MavMissionResult.MAV_MISSION_INVALID_SEQUENCE.value -> null // not fatal in ArduPilot
        else -> failOnErrorAck(m)
    }

    /**
     * Opens an inbox of mission replies from [target], runs [block], and on cancellation tells the vehicle.
     * [MissionTransferException] becomes a failed [Result]; cancellation still propagates, as it must.
     */
    private suspend fun <R> transfer(target: Target, block: suspend (ReceiveChannel<MavMessage<*>>) -> R): Result<R> = mutex.withLock {
        coroutineScope {
            val inbox = Channel<MavMessage<*>>(Channel.UNLIMITED)
            // UNDISPATCHED: listening before the first send, so an immediate reply can't be missed (see CommandProtocol).
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { frame ->
                    val m = frame.message
                    if (frame.systemId == target.system && frame.componentId == target.component && isMissionReplyForUs(m)) inbox.send(m)
                }
            }
            try {
                Result.success(block(inbox))
            } catch (e: MissionTransferException) {
                Result.failure(e)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    sender.send(MissionAck(target.system, target.component, MavEnumValue.of(MavMissionResult.MAV_MISSION_OPERATION_CANCELLED)))
                }
                throw e
            } finally {
                listener.cancel()
            }
        }
    }

    /**
     * Sends [message] and waits for [handle] to accept a reply (non-null). Replies it ignores (null) don't restart
     * the clock. No accepted reply within [TIMEOUT] → resend the same message, up to [ATTEMPTS] times.
     */
    private suspend fun <T : MavMessage<T>, R : Any> exchange(
        inbox: ReceiveChannel<MavMessage<*>>,
        message: T,
        what: String,
        handle: (MavMessage<*>) -> R?,
    ): R {
        repeat(ATTEMPTS) {
            val tx = sender.send(message)
            if (tx != TxResult.Sent) fail("couldn't send $what: ${describe(tx)}")
            val reply = withTimeoutOrNull(TIMEOUT) {
                var accepted: R? = null
                while (accepted == null) accepted = handle(inbox.receive())
                accepted
            }
            if (reply != null) return reply
        }
        fail("no answer from the vehicle to $what after $ATTEMPTS tries")
    }

    companion object {
        /** Per-message wait. Same reasoning as CommandProtocol.ACK_TIMEOUT: mostly radio round trip. */
        val TIMEOUT = 1.5.seconds

        /** 5 × 1.5 s = 7.5 s, just inside ArduPilot's own 8 s upload timeout, so we give up before it silently does. */
        const val ATTEMPTS = 5
    }
}

/** Only mission-protocol replies for the plain mission list, addressed to us (or to everyone, on older firmware). */
@Suppress("DEPRECATION") // MissionRequest, see MissionProtocol
private fun isMissionReplyForUs(m: MavMessage<*>): Boolean {
    val (type, target) = when (m) {
        is MissionRequestInt -> m.missionType to m.targetSystem
        is MissionRequest -> m.missionType to m.targetSystem
        is MissionCount -> m.missionType to m.targetSystem
        is MissionItemInt -> m.missionType to m.targetSystem
        is MissionAck -> m.missionType to m.targetSystem
        else -> return false
    }
    return type.value == MavMissionType.MISSION.value && (target == 0.toUByte() || target == MavTxGateway.GCS_SYSTEM_ID)
}

/** A MISSION_ACK carrying an error ends the transfer; anything else is "not the reply we want" (null). */
private fun failOnErrorAck(m: MavMessage<*>): Nothing? {
    if (m is MissionAck && m.type.value != MavMissionResult.MAV_MISSION_ACCEPTED.value) {
        val name = m.type.entry?.name?.removePrefix("MAV_MISSION_") ?: "error ${m.type.value}"
        fail("the vehicle refused: $name")
    }
    return null
}

private fun fail(reason: String): Nothing = throw MissionTransferException(reason)

private fun describe(tx: TxResult) = when (tx) {
    is TxResult.Rejected -> "blocked by the TX gateway (${tx.reason})"
    TxResult.NotConnected -> "no link"
    is TxResult.Failed -> "link error (${tx.cause.message})"
    TxResult.Sent -> "sent"
}

/** An upload that fails midway has already changed the vehicle's mission (ArduPilot writes as it goes): say so. */
private fun Result<Unit>.recoverPartial(): Result<Unit> = recoverCatching { e ->
    throw MissionTransferException("${e.message}. The vehicle may now hold a partial mission: upload again or clear it.")
}
