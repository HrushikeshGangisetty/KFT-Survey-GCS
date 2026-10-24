package com.kft.gcs.core.mavlink.signing

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Source

/**
 * The inbound half of signing (ADR-006): an okio [Source] between the transport and mavlink-kotlin's
 * `BufferedMavConnection`. It finds MAVLink frames in the byte stream, judges each one by the PRD's inbound rules, and
 * passes on only what may reach the library. Frames that pass are copied byte for byte (the library decodes signed
 * frames correctly, CRC included; it just never checks the signature, ADR-006 finding 2).
 *
 * Inbound rules (PRD "Architecture"), by the frame's system id:
 * 1. Bytes before the next 0xFD / 0xFE can't start a frame in any parser, so they pass as they are and the library
 *    skips them as it always has. A MAVLink 2 frame with an incompat flag other than 0x01 is dropped (see [judgeV2]).
 * 2. Signed, from a **bound** system id: verified with that drone's key, then checked for replay. Failures are dropped.
 * 3. Signed HEARTBEAT from an **unbound** system id: every key in the keyring is tried. The first that verifies (and
 *    passes the replay check) binds the system id to that drone for the rest of this link session. None verifies →
 *    [SigningStatus.NoKey] ([SigningStatus.Locked] if the vault is locked), dropped. Any other signed frame from an
 *    unbound id is dropped too: there is no key to check it with, and an unchecked signed frame is what a forger sends.
 * 4. Unsigned or MAVLink 1, claiming a **bound** id: dropped as spoofed, except RADIO_STATUS, which radios inject unsigned.
 * 5. Unsigned or MAVLink 1 from an **unbound** id: passed. That vehicle is [SigningStatus.Unsigned], today's behaviour.
 *
 * [status] changes only on HEARTBEATs (about 1 Hz per vehicle), so a flood of bad frames can't make it flicker; the
 * flood shows in [counters] instead. Once a system id has shown it signs (NoKey, Locked) an unsigned heartbeat with that
 * id doesn't turn it back to Unsigned in this session: that is what a spoofer would send.
 *
 * Runs on the link's reader thread only (one reader per link), so its state needs no locks. It never waits for more
 * bytes than the frame it is looking at needs, and works on okio segments, never byte by byte; it allocates only for
 * signed frames, which must be copied to be hashed.
 */
internal class SigningSource(
    private val upstream: Source,
    private val keyring: SigningKeyring,
    /** Unix wall clock in ms: "now" for the replay window of a stream seen for the first time. */
    private val nowMs: () -> Long,
    /** Told about each verified HEARTBEAT, so the outgoing clock for that drone stays above the vehicle's. */
    private val onVerifiedHeartbeat: (droneId: String, ts: Long) -> Unit = { _, _ -> },
) : Source {
    private val input = Buffer() // read from upstream, not judged yet
    private val output = Buffer() // judged, for the library to read
    private val replay = ReplayGuard()
    private val boundTo = arrayOfNulls<String>(256) // system id -> drone id, for this link session
    private val badSignatures = IntArray(256) // by system id

    private val _status = MutableStateFlow<Map<UByte, SigningStatus>>(emptyMap())

    /** Each system id heard on this link and how it signs. An id missing here has sent no heartbeat yet. */
    val status: StateFlow<Map<UByte, SigningStatus>> = _status.asStateFlow()

    private val _counters = MutableStateFlow(SigningCounters())

    /** Frames dropped so far on this link, by reason. */
    val counters: StateFlow<SigningCounters> = _counters.asStateFlow()

    override fun read(sink: Buffer, byteCount: Long): Long {
        while (output.size == 0L) {
            if (judgeNext()) continue
            if (upstream.read(input, READ_SIZE) == -1L) {
                // The link ended inside a frame. Passing the tail on would let the library rescan it byte by byte and
                // find a frame hidden inside that never went through these rules, so it is dropped.
                input.clear()
                return -1
            }
        }
        return output.read(sink, byteCount)
    }

    /** Moves the front of [input] to [output] or drops it. False when it needs more bytes to decide. */
    private fun judgeNext(): Boolean {
        if (input.size == 0L) return false
        val start = input.indexOfElement(MARKERS)
        if (start != 0L) {
            output.write(input, if (start == -1L) input.size else start)
            return true
        }
        return if (input[0] == STX_V1) judgeV1() else judgeV2()
    }

    /** MAVLink 1: `0xFE len seq sysid compid msgid payload crc(2)`. It can't be signed. */
    private fun judgeV1(): Boolean {
        if (input.size < 2) return false
        val size = V1_OVERHEAD + u(input[1])
        if (input.size < size) return false
        return judgeUnsigned(size, sysId = u(input[3]), msgId = u(input[5]))
    }

    /**
     * MAVLink 2: `0xFD len incompat compat seq sysid compid msgid(3, LE) payload crc(2)`, plus a 13-byte trailer when
     * incompat bit 0x01 is set. The flag is tested as a bit, not compared with 1 (the library's `== 1` is ADR-006's side
     * finding).
     */
    private fun judgeV2(): Boolean {
        if (input.size < 3) return false
        val flags = u(input[2])
        if (flags and INCOMPAT_SIGNED.inv() != 0) {
            // A flag we don't know means a layout we don't know. MAVLink's reference parser (mavlink_frame_char in
            // mavlink_helpers.h) abandons such a frame after these three bytes and resyncs, and so do we. The library
            // would misread it as unsigned (its `== 1` test) and misframe the stream, so it must never see it.
            input.skip(3)
            _counters.update { it.copy(malformed = it.malformed + 1) }
            return true
        }
        val signed = flags and INCOMPAT_SIGNED != 0
        val size = V2_OVERHEAD + u(input[1]) + if (signed) SIGNATURE_TRAILER else 0
        if (input.size < size) return false
        val sysId = u(input[5])
        val msgId = u(input[7]) or (u(input[8]) shl 8) or (u(input[9]) shl 16)
        if (!signed) return judgeUnsigned(size, sysId, msgId)

        val frame = input.readByteArray(size.toLong())
        if (judgeSigned(frame, sysId, compId = u(frame[6]), msgId)) output.write(frame)
        return true
    }

    /** Rules 4 and 5 for the unsigned frame of [size] bytes at the front of [input]. */
    private fun judgeUnsigned(size: Int, sysId: Int, msgId: Int): Boolean {
        if (boundTo[sysId] != null && msgId != RADIO_STATUS) {
            input.skip(size.toLong())
            _counters.update { it.copy(spoofed = it.spoofed + 1) }
            return true
        }
        val current = _status.value[sysId.toUByte()]
        if (msgId == HEARTBEAT && (current == null || current == SigningStatus.Unsigned)) setStatus(sysId, SigningStatus.Unsigned)
        output.write(input, size.toLong())
        return true
    }

    /** Rules 2 and 3. True if [frame] may go on to the library. */
    private fun judgeSigned(frame: ByteArray, sysId: Int, compId: Int, msgId: Int): Boolean {
        val heartbeat = msgId == HEARTBEAT
        // Our own reader of the full 6-byte timestamp. The library's MavFrameV2.signatureTimestamp keeps only 32 bits.
        val trailer = trailerOf(frame) ?: return false

        val bound = boundTo[sysId]
        val droneId = if (bound != null) {
            val key = keyring.keyFor(bound)
            if (key == null) {
                if (heartbeat) setStatus(sysId, if (keyring.isUnlocked()) SigningStatus.NoKey else SigningStatus.Locked)
                return drop { it.copy(unverified = it.unverified + 1) }
            }
            if (!verifyV2(frame, key)) {
                badSignatures[sysId]++
                if (heartbeat) setStatus(sysId, SigningStatus.BadSignature(bound, badSignatures[sysId]))
                return drop { it.copy(badSignature = it.badSignature + 1) }
            }
            bound
        } else {
            if (!heartbeat) return drop { it.copy(unverified = it.unverified + 1) }
            if (!keyring.isUnlocked()) {
                setStatus(sysId, SigningStatus.Locked)
                return drop { it.copy(unverified = it.unverified + 1) }
            }
            keyring.candidates().firstOrNull { (_, key) -> verifyV2(frame, key) }?.first ?: run {
                setStatus(sysId, SigningStatus.NoKey)
                return drop { it.copy(unverified = it.unverified + 1) }
            }
        }

        // Only after the signature checked out, so a forged frame can't move a stream forward or bind an id.
        val fresh = replay.accept(trailer.linkId, sysId.toUByte(), compId.toUByte(), trailer.timestamp, signingTicks(nowMs()))
        if (!fresh) return drop { it.copy(replayed = it.replayed + 1) }
        if (heartbeat) {
            boundTo[sysId] = droneId
            setStatus(sysId, SigningStatus.Signed(droneId))
            onVerifiedHeartbeat(droneId, trailer.timestamp)
        }
        return true
    }

    private inline fun drop(count: (SigningCounters) -> SigningCounters): Boolean {
        _counters.update(count)
        return false
    }

    private fun setStatus(sysId: Int, status: SigningStatus) {
        val id = sysId.toUByte()
        if (_status.value[id] != status) _status.update { it + (id to status) }
    }

    override fun timeout() = upstream.timeout()

    override fun close() = upstream.close()

    private companion object {
        const val READ_SIZE = 8192L
        const val STX_V1: Byte = 0xFE.toByte()
        val MARKERS = byteArrayOf(0xFD.toByte(), STX_V1).toByteString()

        /** v1: 6-byte header + 2-byte CRC around the payload. */
        const val V1_OVERHEAD = 8

        /** v2: 10-byte header + 2-byte CRC around the payload. */
        const val V2_OVERHEAD = 12

        /** link_id (1) + timestamp (6) + signature (6). */
        const val SIGNATURE_TRAILER = 13
        const val INCOMPAT_SIGNED = 0x01

        /** HEARTBEAT (minimal.xml #0) and RADIO_STATUS (common.xml #109). */
        const val HEARTBEAT = 0
        const val RADIO_STATUS = 109

        fun u(b: Byte) = b.toInt() and 0xFF
    }
}
