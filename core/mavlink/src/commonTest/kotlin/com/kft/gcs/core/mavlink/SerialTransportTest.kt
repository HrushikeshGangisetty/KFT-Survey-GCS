package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.Buffer
import okio.IOException

/** [SerialTransport] against a scripted port: no hardware, no threads. Real-port checks are manual (pass summary). */
class SerialTransportTest {

    /** A port whose incoming bytes are scripted chunk by chunk; an empty chunk is a read timeout. */
    private class FakePort(vararg incoming: ByteArray) : SerialLink {
        val incoming = ArrayDeque(incoming.toList())
        val writes = mutableListOf<ByteArray>()
        var closed = false
        var failReads = false

        override fun read(buffer: ByteArray): Int {
            if (failReads) throw IOException("device detached")
            val chunk = incoming.removeFirstOrNull() ?: return 0
            chunk.copyInto(buffer)
            return chunk.size
        }

        override fun write(bytes: ByteArray) { writes += bytes }
        override fun close() { closed = true }
    }

    @Test
    fun bytesPassThroughAfterReadTimeouts() {
        val port = FakePort(ByteArray(0), ByteArray(0), byteArrayOf(0xFD.toByte(), 1, 2))
        val streams = SerialTransport { port }.open()
        assertContentEquals(byteArrayOf(0xFD.toByte(), 1, 2), streams.source.readByteArray(3), "timeouts (0 bytes) are just waited out")
    }

    @Test
    fun oneWritePerFlushSoAFrameIsNotSplit() {
        val port = FakePort()
        val streams = SerialTransport { port }.open()
        streams.sink.write(byteArrayOf(1, 2)).write(byteArrayOf(3))
        streams.sink.flush()
        assertEquals(1, port.writes.size)
        assertContentEquals(byteArrayOf(1, 2, 3), port.writes.single())
    }

    @Test
    fun closingEndsThePendingReadWithAnIOException() {
        val port = FakePort()
        val transport = SerialTransport { port }
        val streams = transport.open()
        transport.close()
        assertTrue(port.closed)
        // The reader thread would be polling; its next timed-out read turns the close into the error that ends the link.
        assertFailsWith<IOException> { streams.source.readByte() }
    }

    @Test
    fun aDetachedDeviceSurfacesAsIOException() {
        val port = FakePort().apply { failReads = true }
        assertFailsWith<IOException> { SerialTransport { port }.open().source.readByte() }
    }

    @Test
    fun openFailuresSurfaceFromOpen() {
        // e.g. "Waiting for USB permission": thrown from open(), so the connection manager retries it.
        val error = assertFailsWith<IOException> { SerialTransport { throw IOException("Can't open COM7") }.open() }
        assertEquals("Can't open COM7", error.message)
    }

    @Test
    fun aMavlinkFrameCrossesTheSerialTransport() {
        // Encode a vehicle heartbeat the way the other end of the cable would, then read it through our transport.
        val wire = Buffer()
        val vehicleSide = BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
        vehicleSide.sendUnsignedV2(1u, 1u, Heartbeat(type = MavEnumValue.of(MavType.QUADROTOR), mavlinkVersion = 3u))
        val port = FakePort(ByteArray(0), wire.readByteArray())

        val gcs = TransportMavConnection(SerialTransport { port }).apply { connect() }
        val frame = gcs.next()
        assertIs<Heartbeat>(frame.message)
        assertEquals(1.toUByte(), frame.systemId)
        gcs.close()
    }

    @Test
    fun serialConfigValidation() {
        assertEquals("Serial COM7 @ 57600", LinkConfig.Serial("COM7").summary)
        assertFailsWith<IllegalArgumentException> { LinkConfig.Serial(" ") }
        assertFailsWith<IllegalArgumentException> { LinkConfig.Serial("COM7", 0) }
        assertTrue(57600 in STANDARD_BAUD_RATES && 115200 in STANDARD_BAUD_RATES)
    }
}
