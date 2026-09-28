package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket as TcpSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import okio.Buffer
import okio.IOException

/**
 * The Bluetooth byte path without a radio: an Android RFCOMM socket is a pair of blocking streams, which pipes stand
 * in for here. What's checked is everything after `socket.connect()`: frames in and out, the other end hanging up,
 * and a disconnect unblocking the reader. The RFCOMM connect itself needs a tablet and a module (manual steps in the
 * pass summary).
 */
class StreamSerialLinkTest {
    private class FakeSocket : Closeable {
        var closed = false
        override fun close() { closed = true }
    }

    /** A vehicle heartbeat arriving over the "socket" decodes through the same transport the USB and COM ports use. */
    @Test
    fun aHeartbeatCrossesTheBluetoothStreams() {
        val wire = Buffer()
        BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
            .sendUnsignedV2(1u, 1u, Heartbeat(type = MavEnumValue.of(MavType.QUADROTOR), mavlinkVersion = 3u))
        val link = StreamSerialLink(ByteArrayInputStream(wire.readByteArray()), ByteArrayOutputStream(), FakeSocket())

        val gcs = TransportMavConnection(SerialTransport { link }).apply { connect() }
        val frame = gcs.next()
        assertIs<Heartbeat>(frame.message)
        assertEquals(1.toUByte(), frame.systemId)
    }

    @Test
    fun framesGoOutWhole() {
        val out = ByteArrayOutputStream()
        val streams = SerialTransport { StreamSerialLink(ByteArrayInputStream(ByteArray(0)), out, FakeSocket()) }.open()
        streams.sink.write(byteArrayOf(0xFD.toByte(), 9, 0)).flush()
        assertContentEquals(byteArrayOf(0xFD.toByte(), 9, 0), out.toByteArray())
    }

    /** The module switched off or out of range: the stream ends, and that becomes the IOException that ends the link. */
    @Test
    fun theOtherEndHangingUpEndsTheLink() {
        val link = StreamSerialLink(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream(), FakeSocket())
        assertFailsWith<IOException> { SerialTransport { link }.open().source.readByte() }
    }

    /**
     * Disconnect while the reader is blocked waiting for bytes: closing the transport closes the socket, and the
     * blocked read ends with an IOException instead of hanging. A real (localhost TCP) socket stands in for the RFCOMM
     * one here: both abort a blocked read on close (Android documents it for `BluetoothSocket.close()`). A pipe
     * doesn't, which is how the first version of this test failed.
     */
    @Test
    fun disconnectUnblocksAWaitingRead() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val socket = TcpSocket(InetAddress.getLoopbackAddress(), server.localPort)
        val peer = server.accept() // the "module": connected, but never sends
        val link = StreamSerialLink(socket.getInputStream(), socket.getOutputStream(), socket)
        val transport = SerialTransport { link }
        val streams = transport.open()
        var failure: Throwable? = null
        val reader = thread { failure = runCatching { streams.source.readByte() }.exceptionOrNull() }
        Thread.sleep(100) // let the reader block; real time is fine here: this is about threads, not timing
        transport.close()
        reader.join(5_000)
        assertTrue(!reader.isAlive, "the read is still blocked")
        assertIs<java.io.IOException>(failure)
        peer.close()
        server.close()
    }

    /** A paired device is a serial port named "bt:<address>"; its profile reads "Bluetooth <address>". */
    @Test
    fun bluetoothPortNames() {
        val name = bluetoothPortName("00:21:13:0A:BC:DE")
        assertEquals("bt:00:21:13:0A:BC:DE", name)
        assertEquals("00:21:13:0A:BC:DE", bluetoothAddressOf(name))
        assertEquals(null, bluetoothAddressOf("COM7"))
        assertEquals("Bluetooth 00:21:13:0A:BC:DE", LinkConfig.Serial(name).summary)
    }
}
