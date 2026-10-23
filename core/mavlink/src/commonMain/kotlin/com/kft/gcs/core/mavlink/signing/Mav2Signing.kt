package com.kft.gcs.core.mavlink.signing

import com.divpundir.mavlink.serialization.CrcX25

// MAVLink 2 frame layout (https://mavlink.io/en/guide/serialization.html#mavlink2_packet_format):
// magic 0xFD | len | incompat_flags | compat_flags | seq | sysid | compid | msgid (3, LE) | payload (len) | CRC (2, LE)
// and, when incompat_flags bit 0x01 is set, a 13-byte trailer: link_id (1) | timestamp (6, LE) | signature (6).
private const val STX_V2 = 0xFD
private const val HEADER_SIZE = 10
private const val CRC_SIZE = 2
private const val SIGNATURE_SIZE = 6
private const val TRAILER_SIZE = 1 + 6 + SIGNATURE_SIZE
private const val INCOMPAT_SIGNED = 0x01
private const val KEY_SIZE = 32

/** Largest signing timestamp: the field is 48 bits on the wire. Reaching past it is an error, never a wrap. */
internal const val MAX_SIGNING_TIMESTAMP = (1L shl 48) - 1

/** The parts of a signed frame's trailer that replay protection needs. [timestamp] is in 10 µs ticks since 2015-01-01. */
data class SignatureTrailer(val linkId: UByte, val timestamp: Long)

/**
 * Signs a complete **unsigned** MAVLink 2 frame and returns the signed frame.
 *
 * Spec rules (https://mavlink.io/en/guide/message_signing.html):
 * - the signed flag is bit 0x01 of `incompat_flags`. Setting it changes the header, so the CRC-16/MCRF4XX (X.25)
 *   is recomputed over bytes 1..end of payload plus the message's [crcExtra]; the receiver checks the CRC
 *   before it looks at the signature;
 * - the trailer is `link_id`, then the timestamp as 6 bytes little-endian, then the signature;
 * - the signature is the first 6 bytes of SHA-256(secret_key ‖ header ‖ payload ‖ CRC ‖ link_id ‖ timestamp),
 *   with the header starting at the 0xFD magic byte. It is plain SHA-256 over the concatenation, not HMAC.
 *
 * [key] must be the 32-byte secret key and [timestamp] must fit in 48 bits (0 until 2^48).
 */
fun signV2(unsigned: ByteArray, crcExtra: Byte, key: ByteArray, linkId: UByte, timestamp: Long): ByteArray {
    require(key.size == KEY_SIZE) { "signing key must be $KEY_SIZE bytes, was ${key.size}" }
    require(timestamp in 0..MAX_SIGNING_TIMESTAMP) { "signing timestamp $timestamp does not fit in 48 bits" }
    require(isV2(unsigned) && unsigned.size == HEADER_SIZE + payloadLength(unsigned) + CRC_SIZE) {
        "not a complete unsigned MAVLink 2 frame (${unsigned.size} bytes)"
    }
    require(unsigned[2].toInt() and INCOMPAT_SIGNED == 0) { "frame is already signed" }

    val signed = unsigned.copyOf(unsigned.size + TRAILER_SIZE)
    signed[2] = (signed[2].toInt() or INCOMPAT_SIGNED).toByte()

    val crcAt = HEADER_SIZE + payloadLength(signed)
    // mavlink-kotlin's own X.25 CRC (the one it checks incoming frames with); the magic byte is not checksummed.
    val crc = CrcX25().apply {
        accumulate(signed, 1, crcAt)
        accumulate(crcExtra)
    }.get().toInt()
    signed[crcAt] = crc.toByte()
    signed[crcAt + 1] = (crc shr 8).toByte()

    var i = crcAt + CRC_SIZE
    signed[i++] = linkId.toByte()
    for (shift in 0 until 48 step 8) signed[i++] = (timestamp shr shift).toByte()
    signatureOf(key, signed).copyInto(signed, i)
    return signed
}

/**
 * True when [signed] is a signed MAVLink 2 frame whose 6-byte signature matches [key].
 *
 * Spec rule (https://mavlink.io/en/guide/message_signing.html): recompute
 * SHA-256(secret_key ‖ header ‖ payload ‖ CRC ‖ link_id ‖ timestamp), keep the first 6 bytes and compare them with
 * the frame's signature. The comparison is constant-time, so the time it takes does not tell an attacker how many
 * leading bytes of a forged signature were right.
 *
 * This checks the signature only. The CRC bytes are covered by the signature, so a damaged CRC fails here too; the
 * CRC itself (which needs the message's crc_extra) is still checked by the library when it decodes the frame, and
 * replay is [ReplayGuard]'s job. A frame without the signed flag, or of the wrong length, is never valid.
 */
fun verifyV2(signed: ByteArray, key: ByteArray): Boolean {
    require(key.size == KEY_SIZE) { "signing key must be $KEY_SIZE bytes, was ${key.size}" }
    if (!isSignedV2(signed)) return false
    val expected = signatureOf(key, signed)
    val start = signed.size - SIGNATURE_SIZE
    var diff = 0
    for (k in 0 until SIGNATURE_SIZE) diff = diff or (expected[k].toInt() xor signed[start + k].toInt())
    return diff == 0
}

/**
 * The `link_id` and timestamp of a signed MAVLink 2 frame, or null if [signed] is not one.
 *
 * Spec rule (https://mavlink.io/en/guide/message_signing.html): the 13-byte trailer after the CRC is `link_id`
 * (uint8), `timestamp` (uint48, little-endian, 10 µs units since 2015-01-01 00:00:00 UTC), then the 6-byte signature.
 * Trust it only after [verifyV2] passes: an unverified timestamp is whatever the sender chose.
 */
fun trailerOf(signed: ByteArray): SignatureTrailer? {
    if (!isSignedV2(signed)) return null
    val at = signed.size - TRAILER_SIZE
    var timestamp = 0L
    for (k in 6 downTo 1) timestamp = (timestamp shl 8) or (signed[at + k].toLong() and 0xFF)
    return SignatureTrailer(signed[at].toUByte(), timestamp)
}

private fun isV2(frame: ByteArray) = frame.size >= HEADER_SIZE && frame[0].toInt() and 0xFF == STX_V2

private fun payloadLength(frame: ByteArray) = frame[1].toInt() and 0xFF

private fun isSignedV2(frame: ByteArray) =
    isV2(frame) && frame[2].toInt() and INCOMPAT_SIGNED != 0 &&
        frame.size == HEADER_SIZE + payloadLength(frame) + CRC_SIZE + TRAILER_SIZE

/** First 6 bytes of SHA-256(key ‖ everything in [signed] before the signature). */
private fun signatureOf(key: ByteArray, signed: ByteArray): ByteArray =
    sha256(key + signed.copyOf(signed.size - SIGNATURE_SIZE)).copyOf(SIGNATURE_SIZE)
