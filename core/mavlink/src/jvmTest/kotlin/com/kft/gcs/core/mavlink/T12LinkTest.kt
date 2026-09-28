package com.kft.gcs.core.mavlink

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.IOException

/**
 * [T12Link] against a scripted T12: each poll written takes the next scripted answer (null = the T12 stays silent).
 * Virtual time throughout: the link's delays and its clock are the test scheduler's.
 */
class T12LinkTest {
    private class FakeT12(vararg answers: ByteArray?) : SerialLink {
        private val script = ArrayDeque(answers.toList())
        private var pending: ByteArray? = null
        val polls = mutableListOf<ByteArray>()
        var closed = false
        var unplugged = false

        override fun write(bytes: ByteArray) {
            if (unplugged) throw IOException("device gone")
            polls += bytes
            pending = script.removeFirstOrNull()
        }

        override fun read(buffer: ByteArray): Int {
            val p = pending ?: return 0
            pending = null
            p.copyInto(buffer)
            return p.size
        }

        override fun close() {
            closed = true
        }
    }

    private fun frame(type: Int, vararg payload: Int) = ByteArray(512).also {
        it[0] = 0xFF.toByte(); it[1] = 0; it[2] = payload.size.toByte(); it[3] = type.toByte()
        payload.forEachIndexed { i, b -> it[4 + i] = b.toByte() }
    }

    private fun port(name: String, serial: String?) = SerialPortInfo(name, "CP2102", usb = UsbId(0x10C4, 0xEA60, serial))

    private fun TestScope.link(ports: () -> List<SerialPortInfo>, open: (String) -> SerialLink) =
        T12Link(backgroundScope, StandardTestDispatcher(testScheduler), ports, open, testScheduler.timeSource)

    @Test
    fun videoTurnsTheT12OnAndItsBytesComeOutInOrder() = runTest {
        val t12 = FakeT12(frame(0xA5, 1, 2), frame(0xA5, 3))
        val link = link({ listOf(port("COM9", "0001")) }) { t12 }

        val chunks = link.video.take(2).toList()

        assertEquals(listOf(listOf<Byte>(1, 2), listOf<Byte>(3)), chunks.map { it.toList() })
        // The first poll carries the start-video AT command.
        assertContentEquals(T12.poll(true, T12.TYPE_COMMAND, T12.START_VIDEO.encodeToByteArray()), t12.polls[0])
        // Nobody left watching: the port closes, with video turned off on the way out.
        advanceTimeBy(100); runCurrent()
        assertTrue(t12.closed)
        assertContentEquals(T12.poll(true, T12.TYPE_COMMAND, T12.STOP_VIDEO.encodeToByteArray()), t12.polls.last())
    }

    @Test
    fun aSilentPollIsAnsweredWithAckZeroNextTime() = runTest {
        val t12 = FakeT12(null, frame(0xA5, 7))
        val link = link({ listOf(port("COM9", null)) }) { t12 }

        link.video.take(1).toList()

        assertEquals(0x55.toByte(), t12.polls[0][3])
        assertEquals(0x00.toByte(), t12.polls[1][3]) // after the silent one
    }

    @Test
    fun mavlinkRidesTheDataChannelBothWaysWithoutTurningVideoOn() = runTest {
        val t12 = FakeT12(frame(0xA3, 0xFD, 0x09, 0x00))
        val link = link({ listOf(port("COM9", "0001")) }) { t12 }

        val transport = link.mavlinkTransport()
        val streams = transport.open()
        advanceTimeBy(100); runCurrent()
        assertContentEquals(byteArrayOf(0xFD.toByte(), 0x09, 0x00), streams.source.readByteArray(3))

        streams.sink.write(byteArrayOf(9, 8)).flush()
        advanceTimeBy(100); runCurrent()
        // FF 02 00 <ack> 02 A3 09 08, whatever the ack.
        assertTrue(t12.polls.any { it.size == 8 && it[4] == 2.toByte() && it[5] == T12.TYPE_DATA && it[6] == 9.toByte() && it[7] == 8.toByte() })
        assertTrue(t12.polls.none { it.size > 6 && it[5] == T12.TYPE_COMMAND }, "no AT command without a video user")

        transport.close()
        advanceTimeBy(100); runCurrent()
        assertTrue(t12.closed)
    }

    @Test
    fun withoutAT12TheMavlinkTransportFailsAndTheProblemSaysWhy() = runTest {
        val link = link({ listOf(SerialPortInfo("COM3", "SiK radio", usb = UsbId(0x0403, 0x6001, "A1"))) }) { error("must not open") }

        assertFailsWith<IOException> { link.mavlinkTransport().open() }

        backgroundScope.launchCollect(link)
        advanceTimeBy(100); runCurrent()
        assertTrue(link.problem.value!!.startsWith("No Skydroid T12"))
    }

    @Test
    fun afterAReplugTheDeviceThatAnsweredIsPreferred() = runTest {
        val a = FakeT12(frame(0xA5, 1))
        val a2 = FakeT12(frame(0xA5, 2))
        var ports = listOf(port("COM9", "AAAA"))
        val opened = mutableListOf<String>()
        val link = link({ ports }) { name -> opened += name; if (opened.size == 1) a else a2 }

        val got = mutableListOf<ByteArray>()
        backgroundScope.launchCollect(link, got)
        advanceTimeBy(100); runCurrent()
        assertEquals(1, got.size)

        // Unplugged; it comes back as COM12, and another CP2102 (a radio) is now listed first.
        a.unplugged = true
        ports = listOf(port("COM11", "BBBB"), port("COM12", "AAAA"))
        advanceTimeBy(2_000); runCurrent()

        assertEquals(listOf("COM9", "COM12"), opened)
        assertEquals(2, got.size)
    }

    private fun CoroutineScope.launchCollect(link: T12Link, into: MutableList<ByteArray> = mutableListOf()) {
        launch { link.video.collect { into += it } }
    }
}
