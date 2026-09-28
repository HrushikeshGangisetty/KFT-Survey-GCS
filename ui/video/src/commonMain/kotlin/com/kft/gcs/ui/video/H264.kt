package com.kft.gcs.ui.video

import kotlinx.coroutines.channels.Channel

/**
 * Splits an H.264 Annex-B byte stream into NAL units. The bytes arrive in whatever chunks the link delivers (the T12
 * sends 508 at a time), so a NAL unit, or even its start code, can straddle two chunks.
 *
 * Annex-B (ITU-T H.264, Annex B) puts a start code before every NAL unit: `00 00 01`, or `00 00 00 01`. Each unit is
 * returned with its own start code, as MediaCodec and FFmpeg both accept. A unit is complete only once the next
 * start code is seen, so the last one waits in here until more bytes come. Bytes before the first start code (joining
 * mid-stream) are dropped.
 */
class AnnexBSplitter {
    private var data = ByteArray(64 * 1024)
    private var size = 0

    /** Where the current unit starts (-1: no start code yet), and where to resume looking for the next one. */
    private var unitStart = -1
    private var scanFrom = 0

    fun push(chunk: ByteArray): List<ByteArray> {
        if (size + chunk.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + chunk.size))
        chunk.copyInto(data, size)
        size += chunk.size

        val units = mutableListOf<ByteArray>()
        var i = scanFrom
        while (i + 2 < size) {
            if (data[i] == ZERO && data[i + 1] == ZERO && data[i + 2] == ONE) {
                // A zero just before 00 00 01 makes it the 4-byte start code. A NAL unit never ends in a zero byte
                // (its last byte holds the rbsp stop bit), so that zero can't belong to the unit before.
                val start = if (i > 0 && data[i - 1] == ZERO) i - 1 else i
                if (unitStart >= 0 && start > unitStart) units += data.copyOfRange(unitStart, start)
                unitStart = start
                i += 3
            } else {
                i++
            }
        }
        // Keep the unfinished unit, moved to the front. Resume two bytes back: "00 00" at the very end could be the
        // first two bytes of a start code whose "01" is in the next chunk.
        val keepFrom = if (unitStart >= 0) unitStart else maxOf(0, size - 3)
        data.copyInto(data, 0, keepFrom, size)
        size -= keepFrom
        if (unitStart >= 0) unitStart = 0
        scanFrom = maxOf(if (unitStart >= 0) 3 else 0, size - 2)
        return units
    }

    private companion object {
        const val ZERO: Byte = 0
        const val ONE: Byte = 1
    }
}

/** The NAL unit type (the low 5 bits of the header byte after the start code): 5 = IDR, 7 = SPS, 8 = PPS. */
fun nalType(unit: ByteArray): Int {
    val header = if (unit[2] == 1.toByte()) 3 else 4
    return if (unit.size > header) unit[header].toInt() and 0x1F else -1
}

/** NAL unit types a decoder needs to know about (ITU-T H.264 table 7-1). */
object NalType {
    const val IDR = 5
    const val SPS = 7
    const val PPS = 8
}

/**
 * Decides which NAL units reach the decoder. Nothing but the parameter sets passes until an SPS and a PPS have been
 * seen and a keyframe (IDR) has started: a decoder given slices before that shows garbage, or fails. After
 * [skipToKeyframe] (the queue overflowed and dropped units), everything is held back again until the next IDR,
 * because every frame until then refers to pictures that were dropped.
 */
class NalGate {
    private var sps = false
    private var pps = false
    private var waitForIdr = true

    fun accept(unit: ByteArray): Boolean {
        when (nalType(unit)) {
            NalType.SPS -> { sps = true; return true }
            NalType.PPS -> { pps = true; return true }
            NalType.IDR -> if (sps && pps) waitForIdr = false
        }
        return sps && pps && !waitForIdr
    }

    fun skipToKeyframe() {
        waitForIdr = true
    }
}

/**
 * Raw Annex-B bytes in, decodable NAL units out, through a bounded queue between the link (producer) and the decoder
 * (consumer). If the decoder falls behind and the queue fills, the backlog is thrown away and decoding resumes at the
 * next keyframe: an old picture is worse than a skipped one when you're flying.
 */
class NalQueue(capacity: Int = 64) {
    private val channel = Channel<ByteArray>(capacity)
    private val splitter = AnnexBSplitter()
    private val gate = NalGate()

    /** How many times the queue overflowed and skipped to a keyframe. */
    var skips = 0
        private set

    /** Called by the producer only (one coroutine). */
    fun push(chunk: ByteArray) {
        for (unit in splitter.push(chunk)) {
            if (!gate.accept(unit)) continue
            if (channel.trySend(unit).isSuccess) continue
            while (channel.tryReceive().isSuccess) continue // empty the backlog
            skips++
            gate.skipToKeyframe()
            // This unit may be the keyframe itself (or a parameter set), which can start the stream again.
            if (gate.accept(unit)) channel.trySend(unit)
        }
    }

    /** The next unit for the decoder; suspends until one is ready. */
    suspend fun receive(): ByteArray = channel.receive()
}
