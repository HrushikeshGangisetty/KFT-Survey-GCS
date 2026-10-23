package com.kft.gcs.core.mavlink.signing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reference values are hand calculations against the MAVLink epoch (2015-01-01 00:00:00 UTC = Unix 1420070400 s):
 * 2026-10-23 00:00:00 UTC is 4313 days later (11 years 2015–2025 = 11 × 365 + 3 leap days = 4018, plus 295 days
 * Jan 1 → Oct 23 2026), i.e. 372 643 200 s = 37 264 320 000 000 ticks of 10 µs. pymavlink's S1v generator uses the
 * same instant as its "today" timestamp, so the two agree independently.
 */
class SigningClockTest {
    private val oct23Ms = 1_792_713_600_000L // (1_420_070_400 + 372_643_200) s
    private val oct23Ticks = 37_264_320_000_000L

    @Test
    fun startsAtTheWallClockInTenMicrosecondTicks() {
        val clock = SigningClock().apply { start(oct23Ms, persisted = null) }
        assertEquals(oct23Ticks, clock.next())
        assertEquals(oct23Ticks + 1, clock.next())
    }

    @Test
    fun theEpochItselfIsTickZero() {
        assertEquals(0, SigningClock().apply { start(1_420_070_400_000L, null) }.next())
    }

    @Test
    fun aWallClockBefore2015ClampsToZeroInsteadOfGoingNegative() {
        assertEquals(0, SigningClock().apply { start(0, null) }.next())
    }

    @Test
    fun aPersistedValueAheadOfTheWallClockWins() {
        val clock = SigningClock().apply { start(oct23Ms, persisted = oct23Ticks + 500) }
        assertEquals(oct23Ticks + 501, clock.next())
    }

    @Test
    fun aHigherIncomingTimestampPullsTheClockAboveIt() {
        val clock = SigningClock().apply { start(oct23Ms, null) }
        clock.observeIncoming(oct23Ticks + 1_000)
        assertEquals(oct23Ticks + 1_001, clock.next())
        clock.observeIncoming(oct23Ticks) // older: ignored
        assertEquals(oct23Ticks + 1_002, clock.next())
    }

    @Test
    fun monotonicAcrossARestartEvenWhenTheWallClockWentBack() {
        val first = SigningClock().apply { start(oct23Ms, null) }
        val sent = List(1_000) { first.next() }
        val persisted = first.current

        // The laptop's clock is reset an hour back before the app starts again.
        val second = SigningClock().apply { start(oct23Ms - 3_600_000, persisted) }
        val after = second.next()
        assertTrue(after > sent.last(), "restart produced $after, already sent up to ${sent.last()}")
        assertTrue((sent + after).zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun the48BitLimitIsAnErrorNotAWrap() {
        val clock = SigningClock().apply { start(oct23Ms, persisted = MAX_SIGNING_TIMESTAMP - 1) }
        assertEquals(MAX_SIGNING_TIMESTAMP, clock.next()) // 2^48 - 1 is the last legal value
        assertFailsWith<IllegalStateException> { clock.next() }

        val pushed = SigningClock().apply { start(oct23Ms, null) }
        pushed.observeIncoming(MAX_SIGNING_TIMESTAMP)
        assertFailsWith<IllegalStateException> { pushed.next() }

        assertFailsWith<IllegalStateException> { SigningClock().start(oct23Ms, persisted = MAX_SIGNING_TIMESTAMP) }
    }

    @Test
    fun nextBeforeStartIsAnError() {
        assertFailsWith<IllegalStateException> { SigningClock().next() }
    }
}
