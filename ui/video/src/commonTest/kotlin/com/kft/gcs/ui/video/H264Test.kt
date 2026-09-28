package com.kft.gcs.ui.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Annex-B splitting and gating against a stream written out by hand. The NAL header bytes follow ITU-T H.264 7.3.1
 * (forbidden_zero_bit, nal_ref_idc, nal_unit_type): 0x67 = SPS (type 7), 0x68 = PPS (8), 0x65 = IDR slice (5),
 * 0x41 = non-IDR slice (1).
 */
class H264Test {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private val sps = bytes(0, 0, 0, 1, 0x67, 0xAA, 0xAB)
    private val pps = bytes(0, 0, 1, 0x68, 0xBB) // a 3-byte start code
    private val idr = bytes(0, 0, 0, 1, 0x65, 0xCC, 0x00, 0x00, 0x03, 0xDD) // 00 00 03: emulation prevention, not a start code
    private val slice = bytes(0, 0, 0, 1, 0x41, 0xEE)
    private val stream = sps + pps + idr + slice

    private fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun unitsComeOutWithTheirStartCodesWhereverTheChunksAreCut() {
        // Every single cut point, plus one byte at a time: start codes and units straddle chunk boundaries.
        val cuttings = (0..stream.size).map { listOf(stream.copyOfRange(0, it), stream.copyOfRange(it, stream.size)) } +
            listOf(stream.map { byteArrayOf(it) })
        for (chunks in cuttings) {
            val splitter = AnnexBSplitter()
            val units = chunks.flatMap { splitter.push(it) }
            // The slice is still open: its end is only known when the next start code arrives.
            assertEquals(listOf(sps, pps, idr).map { it.hex() }, units.map { it.hex() }, "cut ${chunks.map { it.size }}")
            assertEquals(listOf(slice.hex()), splitter.push(bytes(0, 0, 1, 0x41)).map { it.hex() })
        }
    }

    @Test
    fun bytesBeforeTheFirstStartCodeAreDropped() {
        val units = AnnexBSplitter().push(bytes(0x12, 0x34, 0x00) + sps + pps)
        assertEquals(listOf(sps.hex()), units.map { it.hex() })
    }

    @Test
    fun nalTypesAreReadAfterEitherStartCode() {
        assertEquals(listOf(7, 8, 5, 1), listOf(sps, pps, idr, slice).map(::nalType))
    }

    @Test
    fun theGateWaitsForParameterSetsAndAKeyframe() {
        val gate = NalGate()
        assertFalse(gate.accept(slice), "a slice before any SPS/PPS")
        assertTrue(gate.accept(sps))
        assertTrue(gate.accept(pps))
        assertFalse(gate.accept(slice), "a slice before the first keyframe")
        assertTrue(gate.accept(idr))
        assertTrue(gate.accept(slice))
        gate.skipToKeyframe()
        assertFalse(gate.accept(slice), "after an overflow, slices wait for the next keyframe")
        assertTrue(gate.accept(sps), "parameter sets always pass")
        assertTrue(gate.accept(idr))
        assertTrue(gate.accept(slice))
    }

    @Test
    fun anOverflowingQueueDropsTheBacklogAndRestartsAtTheNextKeyframe() = runTest {
        val queue = NalQueue(capacity = 4)
        // SPS, PPS, IDR, slice fill the queue; the next slice overflows it. Units are pushed one at a time, each one
        // closed by the start code at the head of the next push.
        queue.push(sps + pps + idr + slice + slice + slice)
        assertEquals(1, queue.skips)
        queue.push(idr + slice + bytes(0, 0, 1)) // a keyframe comes again; the trailing start code closes the slice
        assertEquals(listOf(idr, slice).map { it.hex() }, List(2) { queue.receive().hex() })
    }
}
