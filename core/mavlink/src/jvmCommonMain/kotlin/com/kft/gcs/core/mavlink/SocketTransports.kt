package com.kft.gcs.core.mavlink

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import okio.Buffer
import okio.Sink
import okio.Source
import okio.Timeout
import okio.buffer
import okio.sink
import okio.source

// java.net works the same on Android and desktop, so these are written once in the shared jvmCommon source set.
// Serial differs per platform: SerialPorts is an expect class with jvmMain and androidMain actuals.

internal actual fun createTransport(config: LinkConfig, serialPorts: SerialPorts, t12: T12Link): MavTransport = when (config) {
    is LinkConfig.UdpListen -> UdpTransport(bindPort = config.port, fixedRemote = null)
    is LinkConfig.UdpClient -> UdpTransport(bindPort = 0, fixedRemote = InetSocketAddress(config.host, config.port))
    is LinkConfig.TcpClient -> TcpTransport(config.host, config.port)
    // The port opens inside MavTransport.open(), so a missing or busy port is an ordinary IOException and the
    // connection manager's reconnect loop retries it (which is also how the Android USB permission prompt resolves).
    // Claimed first, so a port the T12 link holds is refused with "COM9 is in use by Skydroid T12" (PortClaims).
    is LinkConfig.Serial -> SerialTransport { claimed(config.port, SERIAL_OWNER) { serialPorts.open(config.port, config.baud) } }
    // The T12's port is shared with its video, so T12Link owns it and hands out a transport over its data channel.
    LinkConfig.T12 -> t12.mavlinkTransport()
}

/**
 * UDP. MAVLink over UDP is one frame per datagram, so the source hands out datagrams as a byte stream and the
 * sink sends one datagram per flush (mavlink-kotlin flushes after every frame).
 *
 * With [fixedRemote] == null (listen mode) every outgoing frame goes to every address that has sent to us (ADR-005
 * §6): with several vehicles behind several senders, "whoever sent last" would deliver a command to the wrong one's
 * address. MAVLink frames carry their target system, so the others ignore it; QGroundControl does the same. Until
 * someone has sent, there's no address, so outgoing frames are dropped. That's normal: SITL and radios speak first.
 */
internal class UdpTransport(private val bindPort: Int, private val fixedRemote: SocketAddress?) : MavTransport {
    // Written by the reader thread, read by the writer thread: a new immutable set is swapped in for each new sender.
    @Volatile private var peers: Set<SocketAddress> = setOfNotNull(fixedRemote)
    private var socket: DatagramSocket? = null

    override fun open(): MavTransport.Streams {
        val s = DatagramSocket(bindPort)
        socket = s
        return MavTransport.Streams(DatagramSource(s).buffer(), DatagramSink(s).buffer())
    }

    override fun close() {
        socket?.close()
    }

    private inner class DatagramSource(private val socket: DatagramSocket) : Source {
        private val packet = DatagramPacket(ByteArray(MAX_DATAGRAM), MAX_DATAGRAM)
        private val pending = Buffer()

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (pending.size == 0L) {
                socket.receive(packet) // blocks; throws SocketException when close() is called
                if (fixedRemote == null && packet.socketAddress !in peers) peers = peers + packet.socketAddress
                pending.write(packet.data, 0, packet.length)
            }
            return pending.read(sink, byteCount)
        }

        override fun timeout() = Timeout.NONE
        override fun close() = socket.close()
    }

    private inner class DatagramSink(private val socket: DatagramSocket) : Sink {
        private val pending = Buffer()

        override fun write(source: Buffer, byteCount: Long) = pending.write(source, byteCount)

        override fun flush() {
            val bytes = pending.readByteArray()
            peers.forEach { socket.send(DatagramPacket(bytes, bytes.size, it)) }
        }

        override fun timeout() = Timeout.NONE
        override fun close() = socket.close()
    }

    private companion object {
        const val MAX_DATAGRAM = 65_535
    }
}

/** TCP client: a plain byte stream, which is what MAVLink framing expects anyway. */
internal class TcpTransport(private val host: String, private val port: Int) : MavTransport {
    private val socket = Socket()

    override fun open(): MavTransport.Streams {
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.tcpNoDelay = true // small frames; don't let Nagle hold a command back waiting for more bytes
        return MavTransport.Streams(socket.source().buffer(), socket.sink().buffer())
    }

    override fun close() = socket.close()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
