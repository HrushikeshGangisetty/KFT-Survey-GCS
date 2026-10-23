package com.kft.gcs.core.mavlink.signing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MAVLink spec's acceptance rules (message_signing.html, "Accepting Signed Packets"); 60 s = 6 000 000 ticks of 10 µs. */
class ReplayGuardTest {
    private val now = 37_264_320_000_000L // 2026-10-23 00:00:00 UTC
    private val minute = 6_000_000L

    @Test
    fun aNewStreamIsAcceptedUpToExactlyOneMinuteOld() {
        assertTrue(ReplayGuard().accept(0u, 1u, 1u, now, now))
        assertTrue(ReplayGuard().accept(0u, 1u, 1u, now - minute, now))
        assertTrue(ReplayGuard().accept(0u, 1u, 1u, now + minute, now), "a stream ahead of us is fine")
    }

    @Test
    fun aNewStreamMoreThanOneMinuteOldIsRejected() {
        assertFalse(ReplayGuard().accept(0u, 1u, 1u, now - minute - 1, now))
    }

    @Test
    fun aKnownStreamMustStrictlyIncrease() {
        val guard = ReplayGuard()
        assertTrue(guard.accept(0u, 1u, 1u, now, now))
        assertFalse(guard.accept(0u, 1u, 1u, now, now), "same timestamp is a replay")
        assertFalse(guard.accept(0u, 1u, 1u, now - 1, now), "older is a replay")
        assertTrue(guard.accept(0u, 1u, 1u, now + 1, now))
    }

    @Test
    fun onceKnownTheOneMinuteWindowNoLongerApplies() {
        // The spec only bounds the *first* frame of a stream; after that, strictly newer is enough.
        val guard = ReplayGuard()
        assertTrue(guard.accept(0u, 1u, 1u, now - minute, now))
        assertTrue(guard.accept(0u, 1u, 1u, now - minute + 1, now + 10 * minute))
    }

    @Test
    fun aRejectedFrameDoesNotMoveTheStream() {
        val guard = ReplayGuard()
        assertFalse(guard.accept(0u, 1u, 1u, now - minute - 1, now)) // too old: not recorded
        assertTrue(guard.accept(0u, 1u, 1u, now - minute, now))      // still treated as a new stream
    }

    @Test
    fun linkIdSysIdAndCompIdEachMakeASeparateStream() {
        val guard = ReplayGuard()
        assertTrue(guard.accept(1u, 1u, 1u, now, now))
        assertTrue(guard.accept(2u, 1u, 1u, now, now), "other link_id")
        assertTrue(guard.accept(1u, 2u, 1u, now, now), "other sysid")
        assertTrue(guard.accept(1u, 1u, 2u, now, now), "other compid")
        assertTrue(guard.accept(255u, 255u, 255u, now, now), "top of each byte doesn't collide")
        assertFalse(guard.accept(1u, 1u, 1u, now, now), "original stream still remembers")
    }
}
