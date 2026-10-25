package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.LogData
import com.divpundir.mavlink.definitions.common.LogEntry
import com.divpundir.mavlink.definitions.common.LogRequestData
import com.divpundir.mavlink.definitions.common.LogRequestEnd
import com.divpundir.mavlink.definitions.common.LogRequestList
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import com.kft.gcs.core.mavlink.TxResult
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One log on the flight controller's storage (its SD card), as LOG_ENTRY describes it.
 * @property id ArduPilot's list number, 1 = the oldest log on the card; not the file name's number.
 * @property timeUtcSeconds when the log started, seconds since 1970 UTC, or null when the FC had no time then (no GPS).
 */
data class FlightLog(val id: Int, val sizeBytes: Long, val timeUtcSeconds: Long?)

/** Why a log list or download failed, in the operator's words. */
class LogTransferException(message: String) : Exception(message)

/** Where downloaded logs are written: files on Android and desktop ([LogFiles]), memory in tests. */
fun interface LogStore {
    /** A new, empty file for [log] of the vehicle with system id [systemId]. */
    suspend fun create(systemId: Int, log: FlightLog): LogSink
}

/** One file being written. Exactly one of [finish] or [abort] is called at the end. */
interface LogSink {
    suspend fun write(bytes: ByteArray)

    /** Closes the file and says where it is, for the operator. */
    suspend fun finish(): String

    /** Closes and deletes the partial file. */
    suspend fun abort()
}

/**
 * The flight controller's own logs (dataflash `.BIN`): camera-trigger positions for geotagging, raw GNSS for PPK, and
 * everything for incident analysis. Read-only: nothing here erases or changes a log. An interface so ViewModel tests
 * can use a fake.
 */
interface VehicleLogRepository {
    suspend fun list(): Result<List<FlightLog>>

    /** Downloads [log] into a new file and returns its path. [onProgress] gets the bytes written so far. */
    suspend fun download(log: FlightLog, onProgress: (Long) -> Unit = {}): Result<String>
}

/**
 * [VehicleLogRepository] for one vehicle. Waits for the KFT login like every other transfer. ArduPilot answers log
 * requests only while disarmed (AP_Logger_MAVLinkLogTransfer.cpp `handle_log_message`: "Disarm for log download"), so
 * this says so first, and the TX gateway refuses them while armed too.
 *
 * @param store null when the device has nowhere to put files; then a download says so and nothing is requested.
 */
class DefaultVehicleLogRepository(
    frames: Flow<MavFrame<out MavMessage<*>>>,
    private val link: StateFlow<LinkState>,
    private val vehicle: StateFlow<VehicleState>,
    sender: MavSender,
    private val store: LogStore?,
) : VehicleLogRepository {
    private val protocol = LogProtocol(frames, sender)

    override suspend fun list(): Result<List<FlightLog>> = protocol.list(target() ?: return notReady())

    override suspend fun download(log: FlightLog, onProgress: (Long) -> Unit): Result<String> {
        val target = target() ?: return notReady()
        val sink = store?.create(target.system.toInt(), log) ?: return Result.failure(LogTransferException("This device has no folder for logs."))
        return try {
            protocol.download(target, log, sink::write, onProgress)
                .map { sink.finish() }
                .onFailure { sink.abort() }
        } catch (e: CancellationException) {
            // Cancelled by the operator (or the screen went away): no half file is left behind.
            withContext(NonCancellable) { sink.abort() }
            throw e
        } catch (e: Exception) {
            // The file write failed (disk full, folder gone). The protocol has already ended the transfer.
            sink.abort()
            Result.failure(LogTransferException("Couldn't write the log file: ${e.message}"))
        }
    }

    private fun target() = loggedInTarget(link.value, vehicle.value)?.takeIf { !vehicle.value.armed }

    private fun <T> notReady(): Result<T> = Result.failure(
        LogTransferException(
            if (vehicle.value.armed && link.value is LinkState.Connected) "Disarm first: ArduPilot only sends logs on the ground."
            else notReadyReason(link.value, vehicle.value, "Log downloads"),
        ),
    )
}

/**
 * ArduPilot's log download over the LOG_* messages, which every ArduPilot build has (MAVLink FTP can fetch logs too,
 * but needs a newer firmware and a file path). Messages are in `common.xml`; handling checked in the KFT fork d91a40ef,
 * `libraries/AP_Logger/AP_Logger_MAVLinkLogTransfer.cpp`:
 *
 * - LOG_REQUEST_LIST(start, end) → one LOG_ENTRY(id, num_logs, last_log_num, time_utc, size) per log, ids start..end
 *   clamped to 1..num_logs. With no logs at all, a single LOG_ENTRY with id 0 and num_logs 0.
 * - LOG_REQUEST_DATA(id, ofs, count) → LOG_DATA(id, ofs, count, data[90]) packets from ofs on, 90 bytes each, pushed
 *   without further requests until count bytes or the end of the log. The FC never resends, so a lost packet is a gap
 *   the GCS asks for again. A packet shorter than 90 bytes is the end of the log.
 * - While it is sending, a second LOG_REQUEST_DATA from the same link is dropped silently, so a gap is only asked for
 *   once the FC has finished the request before it (it falls quiet).
 * - LOG_REQUEST_END stops a transfer and lets logging resume.
 *
 * So the log is fetched in [CHUNK]s: request a chunk, collect its packets, ask again for what's missing when the FC
 * falls quiet, write the chunk out once it is whole. Memory stays at one chunk whatever the log's size, and the file
 * is written in order.
 */
internal class LogProtocol(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val sender: MavSender,
) {
    // One transfer at a time: ArduPilot serves one log request per link, and LOG_DATA says nothing about who asked.
    private val mutex = Mutex()

    suspend fun list(t: MissionProtocol.Target): Result<List<FlightLog>> = transfer<LogEntry, List<FlightLog>>(t) { inbox ->
        val found = mutableMapOf<Int, FlightLog>()
        var total: Int? = null
        var quiet = 0
        send(LogRequestList(t.system, t.component, start = 0u, end = UShort.MAX_VALUE))
        while (total == null || found.size < total) {
            val entry = withTimeoutOrNull(LIST_TIMEOUT) { inbox.receive() }
            if (entry == null) {
                if (++quiet > RETRIES) fail(if (total == null) "No answer from the vehicle." else "The vehicle listed ${found.size} of $total logs.")
                // Ask again for the ids still missing (or for everything, if nothing came).
                val missing = total?.let { n -> (1..n).filter { it !in found } }
                send(LogRequestList(t.system, t.component, start = (missing?.first() ?: 0).toUShort(), end = (missing?.last() ?: 0xFFFF).toUShort()))
                continue
            }
            quiet = 0
            if (entry.numLogs.toInt() == 0) return@transfer emptyList()
            total = entry.numLogs.toInt()
            found[entry.id.toInt()] = FlightLog(entry.id.toInt(), entry.size.toLong(), entry.timeUtc.toLong().takeIf { it != 0L })
        }
        found.values.sortedBy { it.id }
    }

    /**
     * Downloads [log] chunk by chunk, handing each finished chunk to [write] in order. Always ends with LOG_REQUEST_END,
     * whether it finished, failed or was cancelled, so the FC goes back to normal.
     */
    suspend fun download(
        t: MissionProtocol.Target,
        log: FlightLog,
        write: suspend (ByteArray) -> Unit,
        onProgress: (Long) -> Unit,
    ): Result<Unit> = transfer<LogData, Unit>(t) { inbox ->
        try {
            var start = 0L
            while (start < log.sizeBytes) {
                val length = minOf(CHUNK.toLong(), log.sizeBytes - start).toInt()
                val chunk = fetchChunk(t, log.id, start, length, inbox)
                write(chunk)
                start += chunk.size
                onProgress(start)
                if (chunk.size < length) break // the log ended before its listed size
            }
        } finally {
            withContext(NonCancellable) { sender.send(LogRequestEnd(t.system, t.component)) }
        }
    }

    /**
     * Bytes [start] until [start] + [length] of log [id]. Each 90-byte "bin" is marked when it arrives; when the FC falls
     * quiet with bins missing, the span from the first to the last missing bin is asked for again (already-received bins
     * in between are just overwritten with the same bytes). Gives up after [RETRIES] quiet spells with no progress.
     */
    private suspend fun fetchChunk(t: MissionProtocol.Target, id: Int, start: Long, length: Int, inbox: ReceiveChannel<LogData>): ByteArray {
        val buffer = ByteArray(length)
        val got = BooleanArray((length + BIN - 1) / BIN)
        var end = length // shrinks if the log turns out shorter than listed
        var missing = got.size
        var quiet = 0
        fun bins(bytes: Int) = (bytes + BIN - 1) / BIN
        suspend fun request(fromBin: Int, toBin: Int) {
            val ofs = fromBin * BIN
            send(LogRequestData(t.system, t.component, id.toUShort(), (start + ofs).toUInt(), (minOf((toBin + 1) * BIN, end) - ofs).toUInt()))
        }
        request(0, got.size - 1)
        while (missing > 0) {
            val m = withTimeoutOrNull(DATA_TIMEOUT) {
                var data: LogData
                do {
                    data = inbox.receive()
                } while (data.id.toInt() != id)
                data
            }
            if (m == null) {
                val needed = 0 until bins(end)
                val first = needed.first { !got[it] }
                if (++quiet > RETRIES) fail("The download stopped: no data from the vehicle at byte ${start + first * BIN}.")
                // ponytail: if the FC was still sending an earlier re-request, it drops this one silently (see the class
                // KDoc) and the next quiet spell asks again: 2 s lost, rarely. LOG_REQUEST_END first would avoid it.
                request(first, needed.last { !got[it] })
                continue
            }
            val offset = m.ofs.toLong() - start
            // A late packet from the chunk before, or past the end: not ours.
            if (offset < 0 || offset >= end || offset % BIN != 0L) continue
            val bin = (offset / BIN).toInt()
            val count = m.count.toInt()
            if (count < BIN && offset + count < end) {
                // The log ends here, earlier than LOG_ENTRY said: nothing past this byte will come.
                end = (offset + count).toInt()
                missing = (0 until bins(end)).count { !got[it] }
                if (count == 0) continue
            }
            if (!got[bin]) {
                m.data.take(count).forEachIndexed { i, b -> buffer[offset.toInt() + i] = b.toByte() }
                got[bin] = true
                missing--
                quiet = 0
            }
        }
        return if (end == length) buffer else buffer.copyOf(end)
    }

    /**
     * Runs [block] with the target's [T] messages (LOG_ENTRY or LOG_DATA) in [block]'s inbox, one transfer at a time.
     * A [LogTransferException] from [block] becomes a failed [Result]; cancellation passes through.
     */
    private suspend inline fun <reified T : MavMessage<T>, R> transfer(
        t: MissionProtocol.Target,
        crossinline block: suspend (ReceiveChannel<T>) -> R,
    ): Result<R> = mutex.withLock {
        coroutineScope {
            // Unlimited: the FC pushes up to ~16 000 LOG_DATA a second in SITL (40 per loop at 400 Hz); none may be dropped here.
            val inbox = Channel<T>(Channel.UNLIMITED)
            // UNDISPATCHED: subscribed before the first request goes out, so the first answer can't be missed.
            val listener = launch(start = CoroutineStart.UNDISPATCHED) {
                frames.collect { f ->
                    val m = f.message
                    if (m is T && f.systemId == t.system && f.componentId == t.component) inbox.send(m)
                }
            }
            try {
                Result.success(block(inbox))
            } catch (e: LogTransferException) {
                Result.failure(e)
            } finally {
                listener.cancel()
            }
        }
    }

    private suspend fun <T : MavMessage<T>> send(message: T) {
        val tx = sender.send(message)
        if (tx != TxResult.Sent) fail("Not sent: ${describe(tx)}.")
    }

    private fun fail(reason: String): Nothing = throw LogTransferException(reason)

    companion object {
        /** LOG_DATA.data is 90 bytes (common.xml); ArduPilot fills every packet but the log's last. */
        const val BIN = 90

        /**
         * 128 packets, 11.5 KB. ArduPilot sends a whole request as one burst, and the link's shared frame flow holds 256
         * frames before it drops the oldest (ConnectionManager `_frames`). A bigger burst overflows it on a fast link, and
         * every lost packet costs a quiet spell. Measured on SITL over TCP, 22.4 MB log: 512 packets per chunk 59 kB/s,
         * 128 → 358 kB/s, 64 → 261 kB/s (more round trips).
         * ponytail: tuned to that 256-frame buffer; a lossless (suspending) frame flow would allow bigger chunks.
         */
        const val CHUNK = BIN * 128

        /** ArduPilot sends one LOG_ENTRY per main-loop pass (hundreds a second); 2 s of nothing means it is done or lost. */
        val LIST_TIMEOUT = 2.seconds

        /**
         * Silence that ends a chunk's stream. ArduPilot keeps sending as long as its link has room, so over a 57 600-baud
         * radio packets still come every few tens of milliseconds; 2 s of nothing means the FC finished or the link dropped.
         */
        val DATA_TIMEOUT = 2.seconds

        /** Quiet spells with no progress before giving up, for the list and for each chunk. */
        const val RETRIES = 5
    }
}
