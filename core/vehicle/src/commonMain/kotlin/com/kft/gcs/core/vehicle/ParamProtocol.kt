package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavParamType
import com.divpundir.mavlink.definitions.common.ParamError
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.ParamValue
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

/** Why a parameter download stopped. The message is written for the operator. */
class ParamTransferException(message: String) : Exception(message)

/**
 * The MAVLink parameter protocol (https://mavlink.io/en/services/parameter.html). PARAM_REQUEST_LIST,
 * PARAM_REQUEST_READ, PARAM_SET and PARAM_VALUE are `common.xml` #21, #20, #23, #22; PARAM_ERROR (#345) is newer
 * `common.xml` and only recent ArduPilot sends it.
 *
 * ```
 * DOWNLOAD  PARAM_REQUEST_LIST ─────────▶
 *                ◀──────────── PARAM_VALUE(name, value, type, count, index 0 … count-1), streamed
 *           quiet for QUIET: request every missing index by PARAM_REQUEST_READ(index), BATCH at a time,
 *           READ_ATTEMPTS per index, then fail with the names still missing
 * SET       PARAM_SET(name, value) ─────▶
 *                ◀──────────── PARAM_VALUE(name, value it now holds, index 65535)
 *           same value ─▶ Applied · other value ─▶ NotApplied ("locked or rejected"), never resent
 *           no answer in QUIET ─▶ resend, SET_ATTEMPTS in all
 * ```
 *
 * ArduPilot specifics (spec S10; `libraries/GCS_MAVLink/GCS_Param.cpp` and `AP_Param.cpp`, master 2026-09):
 * - **Encoding:** C cast, both ways: `cast_to_float(type)` out, `set_float(value, type)` in. No raw bits, so the
 *   Pass 12 NaN problem (the library canonicalising NaN) can't arise: a parameter is never NaN, and ArduPilot refuses a
 *   NaN or infinite PARAM_SET (PARAM_ERROR VALUE_OUT_OF_RANGE on master, silence on older firmware).
 * - **The list** is streamed at up to 30 % of the link's bandwidth, and at most 5 per update without flow control,
 *   so a 57600-baud radio takes about 30 s for Copter's ~1300 parameters. It's ignored until parameters are loaded
 *   at boot (`params_ready`), hence the resends when nothing comes back.
 * - **Reads by index** go through a 20-entry queue on the vehicle, and anything beyond it is dropped silently. So
 *   missing indices are asked for in batches of [BATCH], never all at once.
 * - **A set** is applied and queued for saving; the save sends PARAM_VALUE with the stored value to every link
 *   (`AP_Param::save_sync` → `send_parameter`), with index -1 (65535). A read-only or not-settable parameter gets
 *   "Param write denied" as STATUSTEXT, PARAM_ERROR PERMISSION_DENIED (master) and PARAM_VALUE with the **old** value.
 *   An unknown name gets PARAM_ERROR DOES_NOT_EXIST on master, silence before.
 * - `param_type` in PARAM_SET is ignored: ArduPilot uses the type it has stored. We send the type it told us anyway.
 * - KFT firmware's parameter locking is expected to behave the same way (an echo of the old value); either way an echo
 *   that doesn't match is reported, never retried, so a locked parameter can't loop.
 *
 * One transfer at a time ([mutex]): PARAM_VALUE has no request id, so two transfers would read each other's replies.
 */
internal class ParamProtocol(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val sender: MavSender,
) {
    private val mutex = Mutex()

    /** Reads every parameter, ordered by index. [onProgress] gets (received, total); total is 0 until the first reply. */
    suspend fun download(target: MissionProtocol.Target, onProgress: (Int, Int) -> Unit): Result<List<Param>> = transfer(target) { inbox ->
        val received = HashMap<Int, Param>()
        var count: Int? = null
        val readTries = HashMap<Int, Int>()
        send(ParamRequestList(target.system, target.component), "PARAM_REQUEST_LIST")
        var listRequests = 1
        while (count == null || received.size < count) {
            val reply = withTimeoutOrNull(QUIET) { inbox.receive() }
            if (reply == null) {
                val total = count
                if (total == null) {
                    // Nothing yet: the request was lost, or the vehicle is still loading its parameters.
                    if (listRequests == LIST_ATTEMPTS) fail("no answer from the vehicle to PARAM_REQUEST_LIST after $LIST_ATTEMPTS tries")
                    send(ParamRequestList(target.system, target.component), "PARAM_REQUEST_LIST")
                    listRequests++
                } else {
                    val missing = (0 until total).filter { it !in received }
                    val next = missing.filter { (readTries[it] ?: 0) < READ_ATTEMPTS }.take(BATCH)
                    if (next.isEmpty()) fail("${missing.size} of $total parameters never arrived. Download again.")
                    next.forEach { i ->
                        readTries[i] = (readTries[i] ?: 0) + 1
                        send(ParamRequestRead(target.system, target.component, paramIndex = i.toShort()), "PARAM_REQUEST_READ")
                    }
                }
                continue
            }
            if (reply !is ParamValue) continue
            val total = reply.paramCount.toInt()
            // A changed count means parameters were added or removed (a feature was enabled): the indices we hold
            // no longer line up, so start over rather than merge two different lists.
            if (count != null && total != count) fail("the vehicle's parameter count changed from $count to $total during the download. Download again.")
            count = total
            val index = reply.paramIndex.toInt()
            val name = reply.name()
            // Index 65535 is a reply to a set or a by-name read, not a list position: it only refreshes a value we have.
            if (index in 0 until total && name.isNotEmpty()) received[index] = Param(name, reply.paramValue, reply.paramType.toParamType(), index)
            else received.entries.find { it.value.name == name }?.let { (i, p) -> received[i] = p.copy(value = reply.paramValue) }
            onProgress(received.size, total)
        }
        received.values.sortedBy { it.index }
    }

    /** Writes [value] to [param] and waits for the vehicle to report what it now holds. */
    suspend fun set(target: MissionProtocol.Target, param: Param, value: Float): ParamSetResult {
        val result = transfer(target) { inbox ->
            val message = ParamSet(target.system, target.component, param.name, value, MavEnumValue.of(param.type.toWire()))
            repeat(SET_ATTEMPTS) {
                when (val tx = sender.send(message)) {
                    TxResult.Sent -> {}
                    is TxResult.Rejected -> return@transfer ParamSetResult.NotApplied("Not sent: ${tx.reason}.")
                    TxResult.NotConnected -> return@transfer ParamSetResult.NotApplied("Not sent: no link.")
                    is TxResult.Failed -> return@transfer ParamSetResult.NotApplied("Not sent: link error (${tx.cause.message}).")
                }
                val reply = withTimeoutOrNull(QUIET) {
                    var r: MavMessage<*>
                    do r = inbox.receive() while (!(r is ParamValue && r.name() == param.name) && !(r is ParamError && r.paramId.trimEnd('\u0000') == param.name))
                    r
                }
                when (reply) {
                    null -> {} // lost on the way there or back: resend, the same value twice does no harm
                    is ParamError -> return@transfer ParamSetResult.NotApplied(
                        "Rejected by the vehicle (${reply.error.entry?.name ?: "error ${reply.error.value}"}).",
                    )
                    else -> {
                        val now = param.copy(value = (reply as ParamValue).paramValue)
                        return@transfer if (now.value == value) ParamSetResult.Applied(now)
                        else ParamSetResult.NotApplied("Locked or rejected by the vehicle: it kept ${now.valueText}.", now)
                    }
                }
            }
            ParamSetResult.NotApplied("No answer from the vehicle after $SET_ATTEMPTS tries: the change is not confirmed. Download to check.")
        }
        return result.getOrElse { ParamSetResult.NotApplied(it.message ?: "Failed") }
    }

    /** Opens an inbox of PARAM_VALUE / PARAM_ERROR from [target] for the length of [block]. */
    private suspend fun <R> transfer(target: MissionProtocol.Target, block: suspend (ReceiveChannel<MavMessage<*>>) -> R): Result<R> =
        mutex.withLock {
            coroutineScope {
                val inbox = Channel<MavMessage<*>>(Channel.UNLIMITED)
                // UNDISPATCHED: listening before the first send (see CommandProtocol).
                val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                    frames.collect { frame ->
                        val m = frame.message
                        if (frame.systemId == target.system && frame.componentId == target.component && isForUs(m)) inbox.send(m)
                    }
                }
                try {
                    Result.success(block(inbox))
                } catch (e: ParamTransferException) {
                    Result.failure(e)
                } finally {
                    listener.cancel()
                }
            }
        }

    private suspend fun <T : MavMessage<T>> send(message: T, what: String) {
        val tx = sender.send(message)
        if (tx is TxResult.Rejected) fail("couldn't send $what: blocked by the TX gateway (${tx.reason})")
        if (tx != TxResult.Sent) fail("couldn't send $what: no link")
    }

    companion object {
        /** How long a quiet link means "the stream stopped" or "the reply was lost". Same as the mission protocol. */
        val QUIET = 1.5.seconds
        const val LIST_ATTEMPTS = 5
        const val READ_ATTEMPTS = 3
        const val SET_ATTEMPTS = 3

        /** Reads in flight at once: half of ArduPilot's 20-entry request queue, so a busy vehicle still has room. */
        const val BATCH = 10
    }
}

/** PARAM_VALUE is broadcast; PARAM_ERROR is addressed, to us or (older senders) to everyone. */
private fun isForUs(m: MavMessage<*>) =
    m is ParamValue || (m is ParamError && (m.targetSystem == 0.toUByte() || m.targetSystem == MavTxGateway.GCS_SYSTEM_ID))

/** param_id is 16 chars, NUL-padded unless it uses all 16 (common.xml). */
private fun ParamValue.name() = paramId.trimEnd('\u0000')

/** ArduPilot sends only these four; anything else is shown as a float and written as one (its type is ignored on set). */
private fun MavEnumValue<MavParamType>.toParamType() = when (value) {
    MavParamType.INT8.value -> ParamType.INT8
    MavParamType.INT16.value -> ParamType.INT16
    MavParamType.INT32.value -> ParamType.INT32
    else -> ParamType.REAL32
}

private fun ParamType.toWire() = when (this) {
    ParamType.INT8 -> MavParamType.INT8
    ParamType.INT16 -> MavParamType.INT16
    ParamType.INT32 -> MavParamType.INT32
    ParamType.REAL32 -> MavParamType.REAL32
}

private fun fail(reason: String): Nothing = throw ParamTransferException(reason)
