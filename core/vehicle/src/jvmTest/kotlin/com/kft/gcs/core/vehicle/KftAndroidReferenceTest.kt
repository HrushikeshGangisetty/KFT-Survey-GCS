package com.kft.gcs.core.vehicle

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Our response against a re-implementation of the Android GCS's own code path: `javax.crypto.Mac`, then
 * `ByteBuffer.order(LITTLE_ENDIAN).getFloat()` six times. Ours builds floats from bits by hand, so the two share
 * only the HMAC primitive (checked separately against RFC 4231). Desktop JVM only: it needs java.nio and javax.
 */
class KftAndroidReferenceTest {

    /** How the Android GCS computes the six floats (KFTAuth.kt, as described for this pass). */
    private fun androidReference(key: ByteArray, challenge: ByteArray): IntArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(challenge)
        val buffer = ByteBuffer.wrap(mac.copyOf(24)).order(ByteOrder.LITTLE_ENDIAN)
        // Compared as raw bits: floatToRawIntBits keeps NaN payloads, so a NaN mismatch can't hide.
        return IntArray(6) { java.lang.Float.floatToRawIntBits(buffer.getFloat()) }
    }

    @Test
    fun matchesTheAndroidComputationByteForByte() {
        val random = Random(20260925) // fixed seed: the same 2000 cases every run
        var nanFloats = 0
        repeat(2000) {
            val key = ByteArray(32).also(random::nextBytes)
            val challenge = ByteArray(32).also(random::nextBytes)
            val ours = kftResponseParams(key, challenge).map { it.toRawBits() }
            assertEquals(androidReference(key, challenge).toList(), ours, "case $it")
            nanFloats += ours.count { bits -> Float.fromBits(bits).isNaN() }
        }
        // ~1 in 256 floats is a NaN pattern, so 12 000 floats hold about 47. Make sure the risky case was exercised.
        assertTrue(nanFloats > 10, "only $nanFloats NaN patterns seen; the NaN path wasn't really tested")
    }

    /**
     * PLACEHOLDER: a golden vector recorded from the Android GCS during one real login. Hrushikesh will supply it.
     *
     * What to record, once, on a bench (then remove the logging again): the challenge as 64 hex characters (KFTCH1 +
     * KFTCH2 text), and the six floats the app sent as raw bits, 8 hex digits each
     * (`Integer.toHexString(java.lang.Float.floatToRawIntBits(f))`). Both are safe to commit: a (challenge, response)
     * pair doesn't reveal the key, and the firmware never reuses a challenge. The test takes the key from your
     * KFT_APP_SECRET (local.properties), so it runs on your machine and is skipped where no key is configured (CI).
     * Fill in the two values and remove @Ignore.
     */
    @Ignore
    @Test
    fun goldenVectorFromTheAndroidApp() {
        val challengeHex = "" // 64 hex characters, KFTCH1 then KFTCH2
        val expectedBitsHex = listOf<String>() // 6 × 8 hex digits, param2..param7, as the Android app sent them
        val key = parseKftKey(KFT_APP_SECRET_HEX) ?: return // no key configured here: nothing to compare against
        val challenge = ByteArray(32) { challengeHex.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        assertEquals(expectedBitsHex, kftResponseParams(key, challenge).map { it.toRawBits().toUInt().toString(16).padStart(8, '0') })
    }
}
