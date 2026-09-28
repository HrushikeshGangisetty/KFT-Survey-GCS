package com.kft.gcs.ui.video

import com.kft.gcs.core.mavlink.T12
import com.kft.gcs.core.mavlink.T12CaptureFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The T12 video path end to end on the desktop, minus the serial port: H.264 bytes → 512-byte T12 frames →
 * [T12.parseFrame] → [AnnexBSplitter] → [NalGate] → [FfmpegDecoder] → pictures.
 */
class T12VideoPipelineTest {
    @Test
    fun aKnownTestPatternSurvivesTheT12FramingAndDecodes() {
        // testPattern: black with a white 64×64 square at (32 + 8i, 64) in frame i.
        val h264 = testPattern(10).reduce(ByteArray::plus)

        val pictures = decodeThroughT12Frames(h264)

        assertEquals(10, pictures.size)
        val last = pictures.last()
        assertEquals(320 to 240, last.width to last.height)
        fun blue(x: Int, y: Int) = last.bgra[(y * last.width + x) * 4].toInt() and 0xFF
        assertTrue(blue(32 + 72 + 32, 96) > 200, "inside the square in frame 10: white")
        assertTrue(blue(10, 10) < 40, "the background: black")
    }

    /**
     * A capture recorded from a real T12 (`tools/t12/capture`), replayed through the same code. Runs once a capture
     * is in `tools/t12/captures/`; until the hardware session there is none, and it passes without checking anything.
     */
    @Test
    fun recordedT12CapturesDecode() {
        val captures = File("../../tools/t12/captures").listFiles { f -> f.name.endsWith(".t12cap") }.orEmpty()
        if (captures.isEmpty()) {
            println("T12ReplayTest: no capture in tools/t12/captures yet (record one on the hardware bench)")
            return
        }
        for (file in captures) {
            val splitter = AnnexBSplitter()
            val gate = NalGate()
            val types = mutableSetOf<Int>()
            var pictures = 0
            FfmpegDecoder().use { decoder ->
                for (answer in T12CaptureFile.read(file)) {
                    val frame = T12.parseFrame(answer) ?: continue
                    if (frame.type != T12.TYPE_VIDEO) continue
                    for (unit in splitter.push(frame.payload)) {
                        types += nalType(unit)
                        if (gate.accept(unit)) decoder.decode(unit) { pictures++ }
                    }
                }
            }
            assertTrue(NalType.SPS in types && NalType.PPS in types && NalType.IDR in types, "${file.name}: SPS, PPS and a keyframe")
            assertTrue(pictures > 0, "${file.name}: decoded pictures")
            println("${file.name}: $pictures pictures, NAL types $types")
        }
    }

    private fun decodeThroughT12Frames(h264: ByteArray): List<Picture> {
        val splitter = AnnexBSplitter()
        val gate = NalGate()
        val pictures = mutableListOf<Picture>()
        FfmpegDecoder().use { decoder ->
            val end = byteArrayOf(0, 0, 1) // closes the last NAL unit, as the next frame's start code would
            for (chunk in (h264 + end).toList().chunked(T12.MAX_RX_PAYLOAD)) {
                // What the T12 sends: FF <lenHi> <lenLo> A5 <payload>, padded to 512 bytes.
                val raw = ByteArray(T12.FRAME_SIZE)
                raw[0] = 0xFF.toByte(); raw[1] = (chunk.size shr 8).toByte(); raw[2] = chunk.size.toByte(); raw[3] = T12.TYPE_VIDEO
                chunk.toByteArray().copyInto(raw, T12.FRAME_HEADER)
                val frame = T12.parseFrame(raw)!!
                for (unit in splitter.push(frame.payload)) if (gate.accept(unit)) decoder.decode(unit) { pictures += it }
            }
        }
        return pictures
    }
}
