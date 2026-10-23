package com.kft.gcs.core.mavlink.signing

/**
 * The GCS's outgoing signing timestamp for one stream: 48 bits of 10 µs ticks since 2015-01-01 00:00:00 UTC
 * (Unix 1420070400 s).
 *
 * Pure state: the caller passes the wall clock in and persists [current], so this class never reads a clock or a file.
 * Not thread-safe; `FrameSink` (S2) will call it under its write mutex.
 *
 * Why the timestamp must only go up: the receiver keeps the last timestamp per (link_id, sysid, compid) stream and drops
 * anything not newer (https://mavlink.io/en/guide/message_signing.html). If our clock ever went backwards (laptop clock
 * reset, app restarted before the wall clock caught up) every command would be dropped as a replay. Hence
 * `max(wall clock, persisted + 1, highest incoming + 1)` (PRD FR-10).
 */
class SigningClock {
    /** The timestamp [next] will hand out, or -1 before [start]. Persisting this value is always safe. */
    var current: Long = -1
        private set

    /**
     * Sets the clock at link open. Spec rule: the timestamp follows the wall clock but never repeats a value already
     * used, so it becomes `max(wall clock ticks, persisted + 1)`, and never drops below what [observeIncoming] saw.
     * [wallMs] is Unix time in milliseconds; [persisted] is the [current] value saved last session, or null on first use.
     */
    fun start(wallMs: Long, persisted: Long?) {
        // A wall clock before 2015 is broken, not negative time: clamp it and rely on the persisted value.
        val wallTicks = ((wallMs - EPOCH_UNIX_MS) * TICKS_PER_MS).coerceAtLeast(0)
        current = maxOf(current, wallTicks, if (persisted == null) 0 else persisted + 1)
        checkFits()
    }

    /**
     * The timestamp for the next outgoing frame. Spec rule: each signed frame carries a timestamp greater than the
     * last one sent, so this returns [current] and then adds one tick.
     * Throws once the 48-bit field is exhausted: wrapping to 0 would make every later frame look like a replay.
     */
    fun next(): Long {
        check(current >= 0) { "SigningClock.start() must be called first" }
        checkFits()
        return current++
    }

    /**
     * Spec rule: when a correctly signed incoming frame carries a timestamp at or above ours, move ours to just above
     * it. Call this only after the frame's signature has been verified, or a forger could push our clock to the
     * 48-bit limit.
     */
    fun observeIncoming(ts: Long) {
        if (ts >= current) current = ts + 1
    }

    private fun checkFits() =
        check(current <= MAX_SIGNING_TIMESTAMP) { "signing timestamp $current exceeds 48 bits; the key must be rotated" }

    private companion object {
        /** 2015-01-01 00:00:00 UTC, the MAVLink signing epoch. */
        const val EPOCH_UNIX_MS = 1_420_070_400_000L

        /** 1 ms = 100 ticks of 10 µs. */
        const val TICKS_PER_MS = 100L
    }
}
