package com.kft.gcs.core.mavlink

/** Link health shown on the Connections screen and the fly view status bar. */
data class LinkStats(
    /** Messages received in the last second. */
    val messagesPerSecond: Int = 0,
    /** Percentage of frames lost since connecting, from gaps in MAVLink sequence numbers. 0.0..100.0. */
    val lossPercent: Double = 0.0,
)

/**
 * Counts received frames and detects lost ones from MAVLink sequence numbers.
 *
 * Every sender (system id + component id) numbers its frames 0..255 and wraps around. If the last frame we saw
 * from a sender had seq 10 and the next has seq 13, frames 11 and 12 were lost. Senders are tracked separately
 * because the autopilot, a gimbal and a companion computer each keep their own counter.
 */
internal class LinkStatsCounter {
    private val lastSeq = HashMap<Int, Int>()
    private var received = 0L
    private var lost = 0L
    private var receivedThisSecond = 0
    private var lastSecond = 0

    fun onFrame(systemId: UByte, componentId: UByte, seq: UByte) {
        val sender = systemId.toInt() shl 8 or componentId.toInt()
        val s = seq.toInt()
        lastSeq[sender]?.let { previous ->
            // (s - previous - 1) mod 256: the number of frames skipped, wrap-around included (255 -> 0 is a gap of 0).
            lost += (s - previous - 1 + 256) % 256
        }
        lastSeq[sender] = s
        received++
        receivedThisSecond++
    }

    /** Closes the current one-second window. Call once per second. */
    fun tick() {
        lastSecond = receivedThisSecond
        receivedThisSecond = 0
    }

    fun snapshot() = LinkStats(
        messagesPerSecond = lastSecond,
        lossPercent = if (received + lost == 0L) 0.0 else 100.0 * lost / (received + lost),
    )
}
