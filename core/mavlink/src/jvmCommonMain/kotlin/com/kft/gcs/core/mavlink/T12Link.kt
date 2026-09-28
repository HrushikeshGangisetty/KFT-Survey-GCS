package com.kft.gcs.core.mavlink

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer

/**
 * The one owner of the Skydroid T12's serial port. The T12 has a single CP2102 port that carries its video and its
 * data channel, so nothing else opens it: video subscribes to [video], and a MAVLink link over the T12
 * ([LinkConfig.T12]) gets its bytes through [mavlinkTransport]. There is no separate "who holds the port" check
 * anywhere, because there is only ever one opener.
 *
 * The port is open while anyone uses it: the first subscriber opens it, the last one leaving closes it. While open, a
 * poll loop on [ioDispatcher] polls the T12 back to back ([T12] has the framing). If the T12 is unplugged or the port
 * fails, the loop reopens it every second, preferring the device that last answered (VID/PID + serial number, since
 * Android renames the device on every replug).
 *
 * Step 0 (ADR-004): the T12's 0xA3 data channel runs both ways, but whether the air unit puts MAVLink on it is not
 * known yet. The link carries it anyway; if the hardware session shows it doesn't, a T12 MAVLink profile just never
 * hears a heartbeat, and telemetry goes over another link (UDP, a radio) while video keeps the T12.
 *
 * @param recorder given every poll's raw answer (the bytes and how many were read); the capture tool records them.
 */
actual class T12Link internal constructor(
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val listPorts: () -> List<SerialPortInfo>,
    private val openPort: (String) -> SerialLink,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val recorder: ((ByteArray, Int) -> Unit)? = null,
) {
    /** Production constructor: the platform's serial ports, at the T12's 4 Mbaud with DTR and RTS raised. */
    actual constructor(scope: CoroutineScope, ioDispatcher: CoroutineDispatcher, ports: SerialPorts, recorder: ((ByteArray, Int) -> Unit)?) : this(
        scope, ioDispatcher, ports::list,
        { name -> ports.open(name, T12.BAUD, readTimeoutMs = T12.POLL_PERIOD_MS.toInt(), rts = true) },
        recorder = recorder,
    )

    private val videoUsers = MutableStateFlow(0)
    private val mavUsers = MutableStateFlow(0)

    // DROP_OLDEST: the poll loop must never wait for a slow video consumer, or MAVLink would stall with it. A drop
    // breaks a NAL unit; the H.264 side then skips to the next keyframe.
    private val videoBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val tx = T12TxQueue()
    private val chunk = ByteArray(T12.FRAME_SIZE * 2) // only the poll loop reads, one session at a time

    /** Where 0xA3 data goes: the open MAVLink transport's queue, or nowhere. */
    @Volatile private var dataSink: ((ByteArray) -> Unit)? = null

    /** The device that last answered, found again first after a replug. */
    @Volatile private var remembered: UsbId? = null

    private val _problem = MutableStateFlow<String?>(null)

    /** Why there's no link right now, for the operator ("No Skydroid T12 on USB"); null while it works or is unused. */
    actual val problem: StateFlow<String?> = _problem.asStateFlow()

    /**
     * The T12's H.264 video, as raw Annex-B bytes in the chunks they arrived in (NAL units split across chunks).
     * Collecting it opens the port and turns the T12's video on; the last collector leaving turns it off.
     */
    actual val video: Flow<ByteArray> = flow {
        videoUsers.update { it + 1 }
        try {
            emitAll(videoBytes)
        } finally {
            videoUsers.update { it - 1 }
        }
    }

    init {
        scope.launch {
            combine(videoUsers, mavUsers) { v, m -> v + m > 0 }.distinctUntilChanged().collectLatest { inUse ->
                if (inUse) keepOpen() else _problem.value = null
            }
        }
    }

    /** A MAVLink byte stream over the T12's data channel, for the connection manager. Internal like every transport. */
    internal actual fun mavlinkTransport(): MavTransport = T12MavTransport()

    /** Opens the port and runs the poll loop, reopening it a second after any failure, until nobody needs it. */
    private suspend fun keepOpen() = withContext(ioDispatcher) {
        while (true) {
            try {
                session()
            } catch (e: IOException) {
                _problem.value = e.message
            }
            delay(REOPEN_DELAY_MS)
        }
    }

    private suspend fun session() {
        val port = findPort() ?: throw IOException("No Skydroid T12 on USB: plug the controller in (USB-C, data port)")
        val link = openPort(port.name)
        _problem.value = null
        synchronized(tx) { tx.clear() }
        var videoOn = false
        var lastGood = true
        val started = timeSource.markNow()
        var lastVideo = started
        val buffer = ByteArray(T12.FRAME_SIZE * 2)
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val wantVideo = videoUsers.value > 0
                if (wantVideo != videoOn) {
                    enqueue(T12.TYPE_COMMAND, (if (wantVideo) T12.START_VIDEO else T12.STOP_VIDEO).encodeToByteArray())
                    videoOn = wantVideo
                    lastVideo = timeSource.markNow()
                }
                val cycle = timeSource.markNow()
                val packet = synchronized(tx) { tx.next(started.elapsedNow().inWholeMilliseconds) }
                link.write(if (packet == null) T12.poll(lastGood) else T12.poll(lastGood, packet.type, packet.payload))

                val n = readAnswer(link, buffer)
                recorder?.invoke(buffer, n)
                val frame = T12.parseFrame(buffer, n)
                lastGood = frame != null
                if (frame == null) {
                    // No (or a broken) answer: wait out the rest of the 20 ms slot, as the T12 expects.
                    val left = T12.POLL_PERIOD_MS - cycle.elapsedNow().inWholeMilliseconds
                    if (left > 0) delay(left)
                } else {
                    remembered = port.usb
                    when (frame.type) {
                        T12.TYPE_VIDEO -> if (frame.payload.isNotEmpty()) {
                            lastVideo = timeSource.markNow()
                            videoBytes.tryEmit(frame.payload)
                        }
                        T12.TYPE_DATA -> if (frame.payload.isNotEmpty()) dataSink?.invoke(frame.payload)
                    }
                }
                if (videoOn && lastVideo.elapsedNow().inWholeMilliseconds > NO_VIDEO_MS) {
                    _problem.value = "The T12 answers but sends no video: is the air unit on and linked?"
                } else if (_problem.value != null) {
                    _problem.value = null
                }
            }
        } finally {
            // Best effort: leave the T12 with its video off, so it isn't streaming into a closed port.
            if (videoOn) runCatching { link.write(T12.poll(true, T12.TYPE_COMMAND, T12.STOP_VIDEO.encodeToByteArray())) }
            link.close()
        }
    }

    /** One answer: reads until a whole frame is in, or the T12 goes quiet for a read timeout. Returns the byte count. */
    private fun readAnswer(link: SerialLink, buffer: ByteArray): Int {
        var n = 0
        while (n < T12.FRAME_SIZE) {
            val got = link.read(chunk)
            if (got == 0) break
            chunk.copyInto(buffer, n, 0, minOf(got, buffer.size - n))
            n = minOf(n + got, buffer.size)
        }
        return n
    }

    /** The T12 port: the remembered device if it's back, otherwise the first CP2102 with the T12's IDs. */
    private fun findPort(): SerialPortInfo? {
        val candidates = listPorts().filter { it.usb?.vendorId == T12.VENDOR_ID && it.usb.productId == T12.PRODUCT_ID }
        val known = remembered?.serialNumber
        // ponytail: with two CP2102 devices plugged in and none remembered yet, the first one is tried. Add a device
        // picker to the video/link profile if that ever happens in the field.
        return candidates.firstOrNull { known != null && it.usb?.serialNumber == known } ?: candidates.firstOrNull()
    }

    private fun enqueue(type: Byte, payload: ByteArray) = synchronized(tx) {
        // Bounded: while the T12 is unplugged the GCS heartbeat would otherwise pile up. MAVLink tolerates a drop.
        if (tx.size < MAX_QUEUED) tx.add(T12.packets(type, payload))
    }

    /** MAVLink over the 0xA3 data channel. One per connection attempt, as the connection manager expects. */
    private inner class T12MavTransport : MavTransport {
        private val incoming = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
        @Volatile private var closed = false
        @Volatile private var opened = false

        override fun open(): MavTransport.Streams {
            // Fail like a missing serial port would, so the Links screen says why and the reconnect loop retries.
            if (listPorts().none { it.usb?.vendorId == T12.VENDOR_ID && it.usb.productId == T12.PRODUCT_ID }) {
                throw IOException("No Skydroid T12 on USB")
            }
            dataSink = { bytes -> incoming.offer(bytes) }
            opened = true
            mavUsers.update { it + 1 }
            return MavTransport.Streams(DataSource().buffer(), DataSink().buffer())
        }

        override fun close() {
            if (closed) return
            closed = true
            if (opened) {
                dataSink = null
                mavUsers.update { it - 1 }
            }
        }

        private inner class DataSource : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                while (true) {
                    if (closed) throw IOException("T12 link closed")
                    // A timed wait, so close() is noticed within a moment (nothing else would wake this thread).
                    val bytes = incoming.poll(200, TimeUnit.MILLISECONDS) ?: continue
                    sink.write(bytes)
                    return bytes.size.toLong()
                }
            }

            override fun timeout() = Timeout.NONE
            override fun close() = this@T12MavTransport.close()
        }

        /** Every flush is one MAVLink frame from the gateway; it goes out in 0xA3 packets on the next polls. */
        private inner class DataSink : Sink {
            private val pending = Buffer()

            override fun write(source: Buffer, byteCount: Long) = pending.write(source, byteCount)

            override fun flush() {
                if (pending.size > 0) enqueue(T12.TYPE_DATA, pending.readByteArray())
            }

            override fun timeout() = Timeout.NONE
            override fun close() = this@T12MavTransport.close()
        }
    }

    private companion object {
        const val REOPEN_DELAY_MS = 1_000L
        const val NO_VIDEO_MS = 8_000L
        const val MAX_QUEUED = 256
    }
}
