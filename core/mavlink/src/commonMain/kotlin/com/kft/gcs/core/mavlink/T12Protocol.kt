package com.kft.gcs.core.mavlink

/**
 * The Skydroid T12's USB serial framing, as pure functions (no I/O, no clock), so every byte is testable without the
 * controller. [T12Link] does the I/O with them.
 *
 * Where these facts come from: the old KFT Android GCS (`AndroidStudioProjects/kftgcs`, `T12SerialVideoSource.kt`),
 * which plays T12 video. That file records that its author worked the protocol out from Skydroid's `uartVideo` .aar.
 * Nothing here was taken from that .aar by us; see ADR-004 for the provenance and the open question it raises.
 *
 * The T12 is a CP2102 USB-serial chip (VID 0x10C4, PID 0xEA60) at 4 Mbaud 8N1 with DTR and RTS raised. It only
 * speaks when polled. Each cycle:
 *  1. The host writes `FF 02 00 <ack> <len> <type> <payload[len]>`. `ack` is 0x55 if the previous read was a good
 *     frame, 0x00 if not. An idle poll (the keep-alive) is `FF 02 00 <ack> 00 A5`.
 *  2. The T12 answers with one 512-byte frame, `FF <lenHi> <lenLo> <type> <payload[508]>`, `len` ≤ 508.
 *
 * Frame types ([TYPE_COMMAND] and [TYPE_VIDEO] share 0xA5; the direction tells them apart):
 *  - host → T12: 0xA5 AT command text, 0xA3 data, 0xA7 GPS, 0xA1 debug.
 *  - T12 → host: 0xA5 H.264 video (Annex-B), 0xA3 data, 0xA7 GPS, 0xA1 debug.
 * 0xA3 "data" runs both ways; whether it carries the air unit's MAVLink is not yet known (ADR-004, Step 0).
 */
object T12 {
    const val VENDOR_ID = 0x10C4
    const val PRODUCT_ID = 0xEA60
    const val BAUD = 4_000_000

    const val TYPE_COMMAND: Byte = 0xA5.toByte()
    const val TYPE_VIDEO: Byte = 0xA5.toByte()
    const val TYPE_DATA: Byte = 0xA3.toByte()

    const val FRAME_SIZE = 512
    const val FRAME_HEADER = 4
    const val MAX_RX_PAYLOAD = FRAME_SIZE - FRAME_HEADER
    const val MAX_TX_PAYLOAD = 250

    /** A cycle is at least this long when the T12 doesn't answer; a good answer starts the next poll at once. */
    const val POLL_PERIOD_MS = 20L

    /** Minimum gap between two queued packets, and between two AT commands (the T12 drops commands sent faster). */
    const val PACKET_SPACING_MS = 30L
    const val COMMAND_SPACING_MS = 180L

    /**
     * Turns video on: 1280×720 ("-m0"), 15 fps, 900 kbit/s, plus the default auto-exposure settings. The same text the
     * old KFT app sends. [STOP_VIDEO] turns it off, so a MAVLink-only session doesn't spend the link on video.
     */
    const val START_VIDEO =
        "AT+VIDEO -m0 -p2 -f15 -b900 -e1\r\n" +
            "AT+AE -o0 -i1 -h1 -r0\r\n" +
            "AT+AEM -a0 -b0 -c0 -d0 -e8192 -f1024 -g1024 -h1024\r\n"
    const val STOP_VIDEO = "AT+VIDEO -e0\r\n"

    /** One poll: `FF 02 00 <ack> <len> <type> <payload>`. [payload] must fit in [MAX_TX_PAYLOAD]. */
    fun poll(lastReadGood: Boolean, type: Byte = TYPE_COMMAND, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_TX_PAYLOAD) { "T12 payload too long: ${payload.size}" }
        return byteArrayOf(0xFF.toByte(), 0x02, 0x00, if (lastReadGood) 0x55 else 0x00, payload.size.toByte(), type) + payload
    }

    /**
     * The frame in the first [size] bytes of [bytes], or null if they aren't one: anything but exactly 512 bytes
     * starting with 0xFF is a bad read (the host then answers with ack 0x00). A length over 508 is capped, as the old
     * app did, rather than trusted.
     */
    fun parseFrame(bytes: ByteArray, size: Int = bytes.size): T12Frame? {
        if (size != FRAME_SIZE || bytes[0] != 0xFF.toByte()) return null
        val len = (((bytes[1].toInt() and 0xFF) shl 8) or (bytes[2].toInt() and 0xFF)).coerceAtMost(MAX_RX_PAYLOAD)
        return T12Frame(bytes[3], bytes.copyOfRange(FRAME_HEADER, FRAME_HEADER + len))
    }

    /** Splits [payload] into packets of at most [MAX_TX_PAYLOAD] bytes, each sent in its own poll. */
    fun packets(type: Byte, payload: ByteArray): List<T12Packet> =
        (payload.indices step MAX_TX_PAYLOAD).map { T12Packet(type, payload.copyOfRange(it, minOf(it + MAX_TX_PAYLOAD, payload.size))) }
}

/** One frame from the T12: its [type] and the payload bytes it carries. */
class T12Frame(val type: Byte, val payload: ByteArray)

/** One packet for the T12, sent inside a poll. */
class T12Packet(val type: Byte, val payload: ByteArray)

/**
 * What the next poll carries. Packets wait in order; one leaves at most every [T12.PACKET_SPACING_MS], and an AT
 * command at most every [T12.COMMAND_SPACING_MS]. Otherwise the poll is idle (the keep-alive). Not thread-safe: the
 * link guards it with a lock, because MAVLink is queued from the gateway's thread and taken on the poll thread.
 */
class T12TxQueue {
    private val queue = ArrayDeque<T12Packet>()
    private var lastSentMs = Long.MIN_VALUE / 2
    private var lastCommandMs = Long.MIN_VALUE / 2

    val size get() = queue.size

    fun add(packets: List<T12Packet>) = queue.addAll(packets)

    fun clear() = queue.clear()

    /** The packet to send at [nowMs], or null for an idle poll. */
    fun next(nowMs: Long): T12Packet? {
        val head = queue.firstOrNull() ?: return null
        if (nowMs - lastSentMs < T12.PACKET_SPACING_MS) return null
        val command = head.type == T12.TYPE_COMMAND
        if (command && nowMs - lastCommandMs < T12.COMMAND_SPACING_MS) return null
        lastSentMs = nowMs
        if (command) lastCommandMs = nowMs
        return queue.removeFirst()
    }
}

/**
 * The one owner of the Skydroid T12's serial port, shared by its video ([video]) and a MAVLink link over its data
 * channel ([LinkConfig.T12]). The port is open while either is in use. See the `jvmCommonMain` actual for how.
 */
expect class T12Link(
    scope: kotlinx.coroutines.CoroutineScope,
    ioDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ports: SerialPorts,
    recorder: ((ByteArray, Int) -> Unit)? = null,
) {
    /** Why there's no link right now, for the operator ("No Skydroid T12 on USB"); null while it works or is unused. */
    val problem: kotlinx.coroutines.flow.StateFlow<String?>

    /** The T12's H.264 video as raw Annex-B bytes. Collecting it opens the port and turns the video on. */
    val video: kotlinx.coroutines.flow.Flow<ByteArray>

    internal fun mavlinkTransport(): MavTransport
}
