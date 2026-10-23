package com.kft.gcs.core.mavlink.signing

/**
 * Replay protection for incoming signed frames: the last accepted timestamp per (link_id, sysid, compid) stream.
 *
 * Pure state, no clock: the caller passes its own signing timestamp as `localNow`. Not thread-safe; `SigningSource`
 * (S2) will use it from the reader thread only. At most 2^24 streams can exist; a real link carries a handful.
 */
class ReplayGuard {
    private val lastByStream = HashMap<Int, Long>()

    /**
     * True if the frame is fresh, and then records [ts] as its stream's latest. Call only after [verifyV2] passed, so a
     * forged frame can't move a stream forward.
     *
     * Spec rules (https://mavlink.io/en/guide/message_signing.html, "Accepting Signed Packets"; the same checks as
     * `mavlink_signature_check` in the MAVLink C library that ArduPilot builds with):
     * - known stream: reject unless [ts] is strictly greater than the last timestamp accepted on it;
     * - new stream: reject if [ts] is more than one minute (6,000,000 ticks) behind [localNow].
     * A rejected frame leaves the stream's state unchanged.
     */
    fun accept(linkId: UByte, sysId: UByte, compId: UByte, ts: Long, localNow: Long): Boolean {
        val stream = (linkId.toInt() shl 16) or (sysId.toInt() shl 8) or compId.toInt()
        val last = lastByStream[stream]
        val fresh = if (last != null) ts > last else ts >= localNow - FIRST_SEEN_WINDOW_TICKS
        if (fresh) lastByStream[stream] = ts
        return fresh
    }

    private companion object {
        /** 60 s in 10 µs ticks. */
        const val FIRST_SEEN_WINDOW_TICKS = 6_000_000L
    }
}
