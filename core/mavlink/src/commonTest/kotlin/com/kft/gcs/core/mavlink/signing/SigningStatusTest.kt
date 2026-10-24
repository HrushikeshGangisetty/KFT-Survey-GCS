package com.kft.gcs.core.mavlink.signing

import kotlin.test.Test
import kotlin.test.assertEquals

class SigningStatusTest {

    /** PRD FR-8 names four states: Signed, No key, Bad signature, Unsigned link; the vault adds Locked. */
    @Test
    fun everyStatusHasItsBadgeTextAndTone() {
        val drone = "a".repeat(24)
        val expected = listOf(
            SigningStatus.Signed(drone) to ("Signed" to SigningTone.GOOD),
            SigningStatus.Unsigned to ("Unsigned link" to SigningTone.NEUTRAL),
            SigningStatus.NoKey to ("No key" to SigningTone.WARNING),
            SigningStatus.Locked to ("Keys locked" to SigningTone.WARNING),
            SigningStatus.BadSignature(drone, 3) to ("Bad signature (3)" to SigningTone.WARNING),
        )
        for ((status, want) in expected) assertEquals(want, status.label to status.tone, status.toString())
    }
}
