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

internal actual fun createTransport(config: LinkConfig): MavTransport = when (config) {
    is LinkConfig.UdpListen -> UdpTransport(bindPort = config.port, fixedRemote = null)
    is LinkConfig.UdpClient -> UdpTransport(bindPort = 0, fixedRemote = InetSocketAddress(config.host, config.port))
    is LinkConfig.TcpClient -> TcpTransport(config.host, config.port)
}

/**
 * UDP. MAVLink over UDP is one frame per datagram, so the source hands out datagrams as a byte stream and the
 * sink sends one datagram per flush (mavlink-kotlin flushes after every frame).
 *
 * With [fixedRemote] == null (listen mode) we reply to whoever sent to us last. Until someone does, there's no
 * address to reply to, so outgoing frames are dropped. That's normal: SITL and radios speak first.
 */
internal class UdpTransport(private val bindPort: Int, private val fixedRemote: SocketAddress?) : MavTransport {
    // Written by the reader thread, read by the writer thread.
    @Volatile private var replyTo: SocketAddress? = fixedRemote
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
                if (fixedRemote == null) replyTo = packet.socketAddress
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
            val to = replyTo ?: return
            socket.send(DatagramPacket(bytes, bytes.size, to))
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
