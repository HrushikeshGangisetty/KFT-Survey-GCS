package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.LogData
import com.divpundir.mavlink.definitions.common.LogEntry
import com.divpundir.mavlink.definitions.common.LogRequestData
import com.divpundir.mavlink.definitions.common.LogRequestEnd
import com.divpundir.mavlink.definitions.common.LogRequestList
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The log list and download against a scripted ArduPilot (AP_Logger_MAVLinkLogTransfer.cpp's behaviour, KFT fork
 * d91a40ef), in virtual time. The FC pushes every packet of a request at once, as fast as the link allows.
 */
class LogsTest {
    /** Logs on the fake SD card, by list id (1 = oldest). Contents are a fixed pattern, so any misplaced byte shows. */
    private val logs = mapOf(
        1 to ByteArray(500) { (it * 7).toByte() },
        2 to ByteArray(100_000) { (it * 31 + it / 256).toByte() }, // nine chunks: 8 × 11 520 + 7 840
        3 to ByteArray(90 * 3) { (255 - it).toByte() },             // ends exactly on a packet boundary
    )
    private val times = mapOf(1 to 1_761_383_642L, 2 to 0L, 3 to 1_761_390_000L)

    /** Packets to lose on their first send, by (log id, offset). Each is lost once, like a radio dropout. */
    private val drop = mutableSetOf<Pair<Int, Long>>()
    private val entriesToDrop = mutableSetOf<Int>()

    /** LOG_DATA's size on the wire is fixed at 90 (common.xml); unused bytes are zero. */
    private fun data(id: Int, ofs: Long): LogData? {
        val log = logs.getValue(id)
        val n = (log.size - ofs).coerceIn(0, 90).toInt()
        return LogData(id.toUShort(), ofs.toUInt(), n.toUByte(), (0 until 90).map { if (it < n) log[ofs.toInt() + it].toUByte() else 0u })
    }

    private fun answer(m: MavMessage<*>): List<MavMessage<*>> = when (m) {
        // handle_log_request_list: ids start..end, start clamped up to 1 and end down to num_logs.
        is LogRequestList -> (maxOf(1, m.start.toInt())..minOf(logs.size, m.end.toInt())).filter { entriesToDrop.remove(it).not() }.map { id ->
            LogEntry(id.toUShort(), logs.size.toUShort(), minOf(logs.size, m.end.toInt()).toUShort(), times.getValue(id).toUInt(), logs.getValue(id).size.toUInt())
        }
        // handle_log_request_data + handle_log_send_data: 90-byte packets from ofs, until count bytes or the log's end;
        // a request at or past the end gets one empty packet.
        is LogRequestData -> {
            val size = logs.getValue(m.id.toInt()).size
            val end = minOf(size.toLong(), m.ofs.toLong() + m.count.toLong())
            val offsets = if (m.ofs.toLong() >= size) listOf(m.ofs.toLong()) else (m.ofs.toLong() until end step 90).toList()
            offsets.filter { drop.remove(m.id.toInt() to it).not() }.mapNotNull { data(m.id.toInt(), it) }
        }
        else -> emptyList()
    }

    // Room for a whole chunk's packets at once: the fake pushes them all from inside send().
    private val fc = FakeFc(frames = MutableSharedFlow(extraBufferCapacity = 2_000)).apply { reply = ::answer }
    private val protocol = LogProtocol(fc.frames, fc)
    private val target = MissionProtocol.Target(1u, 1u)

    private suspend fun download(id: Int, progress: MutableList<Long> = mutableListOf()): Result<ByteArray> {
        val out = mutableListOf<Byte>()
        val log = FlightLog(id, logs.getValue(id).size.toLong(), null)
        return protocol.download(target, log, { out += it.toList() }, { progress += it }).map { out.toByteArray() }
    }

    @Test
    fun listsEveryLogOldestFirstWithItsTime() = runTest {
        val list = protocol.list(target).getOrThrow()
        assertEquals(
            listOf(FlightLog(1, 500, 1_761_383_642L), FlightLog(2, 100_000, null), FlightLog(3, 270, 1_761_390_000L)),
            list,
            "time_utc 0 means the FC had no clock: null",
        )
        assertEquals(listOf(LogRequestList(1u, 1u, start = 0u, end = 65535u)), fc.sentOf<LogRequestList>())
    }

    /** A lost LOG_ENTRY is asked for again by id, after 2 s of quiet. */
    @Test
    fun aLostEntryIsAskedForAgain() = runTest {
        entriesToDrop += 2
        val list = protocol.list(target).getOrThrow()
        assertEquals(listOf(1, 2, 3), list.map { it.id })
        assertEquals(LogRequestList(1u, 1u, start = 2u, end = 2u), fc.sentOf<LogRequestList>().last())
        assertEquals(2_000L, testScheduler.currentTime)
    }

    /** No logs on the card: ArduPilot sends one LOG_ENTRY with id 0 and num_logs 0. */
    @Test
    fun anEmptyCardIsAnEmptyList() = runTest {
        fc.reply = { if (it is LogRequestList) listOf(LogEntry(0u, 0u, 0u, 0u, 0u)) else emptyList() }
        assertEquals(emptyList(), protocol.list(target).getOrThrow())
    }

    @Test
    fun silenceFailsTheListAfterTheRetries() = runTest {
        fc.reply = { emptyList() }
        assertEquals("No answer from the vehicle.", protocol.list(target).exceptionOrNull()?.message)
        assertEquals(1 + LogProtocol.RETRIES, fc.sentOf<LogRequestList>().size)
    }

    /** Nine chunks (128 packets = 11 520 bytes each, the last 7 840), each asked for once, written in order; then LOG_REQUEST_END. */
    @Test
    fun aCleanDownloadIsByteForByteTheLog() = runTest {
        val progress = mutableListOf<Long>()
        assertContentEquals(logs.getValue(2), download(2, progress).getOrThrow())
        assertEquals(
            (0 until 8).map { it * 11_520L to 11_520L } + (92_160L to 7_840L),
            fc.sentOf<LogRequestData>().map { it.ofs.toLong() to it.count.toLong() },
        )
        assertEquals((1..8).map { it * 11_520L } + 100_000L, progress)
        assertIs<LogRequestEnd>(fc.sent.last())
        assertEquals(0L, testScheduler.currentTime, "no waiting when nothing is lost")
    }

    /**
     * Lost packets in the middle and at the end of a chunk: when the FC falls quiet, the span from the first to the last
     * missing packet is asked for again, and the result is still exact.
     */
    @Test
    fun lostPacketsAreAskedForAgain() = runTest {
        drop += listOf(2 to 900L, 2 to 9_000L, 2 to 45_990L, 2 to 99_990L)
        assertContentEquals(logs.getValue(2), download(2).getOrThrow())
        val requests = fc.sentOf<LogRequestData>().map { it.ofs.toLong() to it.count.toLong() }
        assertEquals(
            listOf(
                0L to 11_520L, 900L to 8_190L,           // chunk 0 lost bins 10 and 100: bytes 900 up to the end of bin 100 (9 090)
                11_520L to 11_520L, 23_040L to 11_520L,
                34_560L to 11_520L, 45_990L to 90L,      // chunk 3 lost its last bin, 45 990..46 079
                46_080L to 11_520L, 57_600L to 11_520L, 69_120L to 11_520L, 80_640L to 11_520L,
                92_160L to 7_840L, 99_990L to 10L,       // chunk 8: only the log's last 10 bytes
            ),
            requests,
        )
        assertEquals(6_000L, testScheduler.currentTime, "one 2 s quiet spell per chunk with a loss")
    }

    /** A log that ends exactly on a packet boundary, and one shorter than its LOG_ENTRY said. */
    @Test
    fun theLogsEndIsFoundWhereverItIs() = runTest {
        assertContentEquals(logs.getValue(3), download(3).getOrThrow())
        // LOG_ENTRY said 1 000 bytes, but the file has 500: the short packet at 450 ends it.
        val out = mutableListOf<Byte>()
        protocol.download(target, FlightLog(1, 1_000, null), { out += it.toList() }, {}).getOrThrow()
        assertContentEquals(logs.getValue(1), out.toByteArray())
    }

    @Test
    fun silenceFailsTheDownloadButStillEndsTheTransfer() = runTest {
        fc.reply = { emptyList() }
        assertContains(download(1).exceptionOrNull()!!.message!!, "no data from the vehicle at byte 0")
        assertEquals(1 + LogProtocol.RETRIES, fc.sentOf<LogRequestData>().size)
        assertIs<LogRequestEnd>(fc.sent.last())
    }

    /** Cancelling (the operator's Cancel, or leaving the screen) still sends LOG_REQUEST_END, so the FC stops sending. */
    @Test
    fun cancellingEndsTheTransfer() = runTest {
        fc.reply = { emptyList() }
        val job = launch { download(2) }
        advanceTimeBy(1.seconds)
        job.cancel()
        runCurrent()
        assertIs<LogRequestEnd>(fc.sent.last())
    }

    // ---- the repository: readiness, the file, and what's left after a failure ----

    private class MemorySink : LogSink {
        val bytes = mutableListOf<Byte>()
        var finished = false
        var aborted = false
        var failWrites = false
        override suspend fun write(bytes: ByteArray) {
            if (failWrites) throw IllegalStateException("disk full")
            this.bytes += bytes.toList()
        }
        override suspend fun finish(): String = "/logs/test.bin".also { finished = true }
        override suspend fun abort() { aborted = true }
    }

    private val link = MutableStateFlow<LinkState>(LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(1u, 1u, VehicleKind.COPTER, false, 0u), LinkStats()))
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))
    private val sink = MemorySink()
    private val repo = DefaultVehicleLogRepository(fc.frames, link, vehicle, fc) { _, _ -> sink }

    @Test
    fun theRepositorySavesTheLogAndSaysWhere() = runTest {
        assertEquals("/logs/test.bin", repo.download(FlightLog(3, 270, null)).getOrThrow())
        assertContentEquals(logs.getValue(3), sink.bytes.toByteArray())
        assertTrue(sink.finished && !sink.aborted)
    }

    @Test
    fun aFailedOrUnwritableDownloadLeavesNoFile() = runTest {
        fc.reply = { emptyList() }
        assertTrue(repo.download(FlightLog(1, 500, null)).isFailure)
        assertTrue(sink.aborted && !sink.finished, "nothing came: partial file removed")

        fc.reply = ::answer
        val unwritable = MemorySink().apply { failWrites = true }
        val repo2 = DefaultVehicleLogRepository(fc.frames, link, vehicle, fc) { _, _ -> unwritable }
        assertContains(repo2.download(FlightLog(1, 500, null)).exceptionOrNull()!!.message!!, "Couldn't write the log file: disk full")
        assertTrue(unwritable.aborted)
        assertIs<LogRequestEnd>(fc.sent.last(), "the FC is told to stop")
    }

    /** ArduPilot ignores log requests while armed, and S12 holds everything until the login: nothing is sent. */
    @Test
    fun nothingIsSentWhileArmedLoggingInOrWithoutStorage() = runTest {
        vehicle.value = vehicle.value.copy(armed = true)
        assertContains(repo.list().exceptionOrNull()!!.message!!, "Disarm first")
        vehicle.value = VehicleState(connected = true, login = KftLoginStatus.LOGGING_IN)
        assertContains(repo.list().exceptionOrNull()!!.message!!, "wait for the login")
        vehicle.value = VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED)
        val noStore = DefaultVehicleLogRepository(fc.frames, link, vehicle, fc, store = null)
        assertContains(noStore.download(FlightLog(1, 500, null)).exceptionOrNull()!!.message!!, "no folder for logs")
        assertTrue(fc.sent.isEmpty())
    }

    @Test
    fun aGatewayRefusalSaysWhy() = runTest {
        fc.txResult = TxResult.Rejected("LOG_REQUEST_LIST", "logs can only be downloaded while the vehicle is disarmed")
        assertContains(protocol.list(target).exceptionOrNull()!!.message!!, "logs can only be downloaded while the vehicle is disarmed")
    }
}
