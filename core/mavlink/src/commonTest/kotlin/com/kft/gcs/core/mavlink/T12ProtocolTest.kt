package com.kft.gcs.core.mavlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The T12 framing against byte strings written out by hand from the protocol notes in [T12]. */
class T12ProtocolTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun anIdlePollIsTheKeepAlive() {
        assertContentEquals(bytes(0xFF, 0x02, 0x00, 0x55, 0x00, 0xA5), T12.poll(lastReadGood = true))
    }

    @Test
    fun aPollAfterABadReadAcksWithZeroAndCarriesItsPayload() {
        assertContentEquals(
            bytes(0xFF, 0x02, 0x00, 0x00, 0x03, 0xA3, 0x01, 0x02, 0x03),
            T12.poll(lastReadGood = false, T12.TYPE_DATA, bytes(1, 2, 3)),
        )
    }

    @Test
    fun aGoodFrameGivesItsTypeAndPayload() {
        val raw = ByteArray(512).also { bytes(0xFF, 0x00, 0x05, 0xA5, 9, 8, 7, 6, 5).copyInto(it) }
        val frame = T12.parseFrame(raw)!!
        assertEquals(0xA5.toByte(), frame.type)
        assertContentEquals(bytes(9, 8, 7, 6, 5), frame.payload)
    }

    @Test
    fun aLengthOver508IsCappedNotTrusted() {
        // 0x0300 = 768 > 508: the rest of the 512-byte frame is all there is.
        val raw = ByteArray(512).also { bytes(0xFF, 0x03, 0x00, 0xA3).copyInto(it) }
        assertEquals(508, T12.parseFrame(raw)!!.payload.size)
    }

    @Test
    fun shortOrUnframedReadsAreBad() {
        assertNull(T12.parseFrame(ByteArray(512).also { it[0] = 0xFF.toByte() }, size = 511))
        assertNull(T12.parseFrame(ByteArray(512))) // first byte 0x00, not 0xFF
    }

    @Test
    fun longMessagesAreSplitInto250BytePackets() {
        val sizes = T12.packets(T12.TYPE_DATA, ByteArray(600)).map { it.payload.size }
        assertEquals(listOf(250, 250, 100), sizes)
    }

    @Test
    fun packetsAreSpaced30MsAndCommands180Ms() {
        val q = T12TxQueue()
        q.add(T12.packets(T12.TYPE_COMMAND, bytes(1)) + T12.packets(T12.TYPE_DATA, bytes(2)) + T12.packets(T12.TYPE_COMMAND, bytes(3)))
        assertEquals(1, q.next(0)!!.payload[0].toInt())
        assertNull(q.next(29)) // < 30 ms since the last packet
        assertEquals(2, q.next(30)!!.payload[0].toInt())
        assertNull(q.next(179)) // a command: < 180 ms since the last command, even though 30 ms have passed
        assertEquals(3, q.next(180)!!.payload[0].toInt())
        assertNull(q.next(1_000)) // empty: an idle poll
    }
}
