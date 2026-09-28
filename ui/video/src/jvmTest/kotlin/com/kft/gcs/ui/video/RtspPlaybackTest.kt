package com.kft.gcs.ui.video

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The desktop RTSP player ([playRtsp]) against a local RTSP server, over the same RTP-over-TCP transport the SIYI
 * camera is played with. The server is a few lines of test code (below) streaming the OpenH264 test pattern, so the
 * test needs no camera and nothing installed. For a check in the running app, `tools/video/rtsp-test-stream.ps1`
 * serves a test pattern with MediaMTX and FFmpeg.
 */
class RtspPlaybackTest {
    @Test
    fun playsAStreamThenFailsWhenTheCameraGoesAndPlaysAgainWhenItsBack() {
        TinyRtspServer(testPattern(10)).use { server ->
            val url = "rtsp://127.0.0.1:${server.port}/main.264"
            val first = CountDownLatch(5)
            var size = 0 to 0
            // The camera "goes away" after the 5th picture: the read fails, which is what makes VideoView reconnect.
            val error = assertFailsWith<IOException> {
                playRtsp(url, stopped = { false }) { p, _ ->
                    size = p.width to p.height
                    first.countDown()
                    if (first.count == 0L) server.dropClients()
                }
            }
            assertEquals(320 to 240, size)
            println("RTSP drop reported as: ${error.message}")

            // Reconnect by URL: the same call again, as VideoView's next attempt does.
            val again = CountDownLatch(3)
            playRtsp(url, stopped = { again.count == 0L }) { _, _ -> again.countDown() }
            assertTrue(again.await(0, TimeUnit.SECONDS))
        }
    }

    @Test
    fun anUnreachableCameraFailsWithItsAddressInTheMessage() {
        val port = ServerSocket(0).use { it.localPort } // free, and nobody listens there any more
        val error = assertFailsWith<IOException> { playRtsp("rtsp://127.0.0.1:$port/main.264", stopped = { false }) { _, _ -> } }
        assertTrue("127.0.0.1:$port" in error.message!!, error.message)
    }
}

/**
 * Just enough of an RTSP server (RFC 2326) for FFmpeg's client: OPTIONS, DESCRIBE (an SDP for H.264, payload type 96),
 * SETUP (RTP interleaved on the TCP connection, channels 0-1), PLAY, then every frame as RTP (RFC 6184: a NAL unit per
 * packet, or FU-A fragments for bigger ones), repeated for as long as the client reads.
 */
private class TinyRtspServer(private val frames: List<ByteArray>) : AutoCloseable {
    private val server = ServerSocket(0)
    val port = server.localPort
    private val clients = mutableListOf<Socket>()

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: IOException) { break }
                synchronized(clients) { clients += client }
                thread(isDaemon = true) { runCatching { serve(client) } }
            }
        }
    }

    fun dropClients() = synchronized(clients) { clients.forEach { it.close() }; clients.clear() }

    override fun close() {
        server.close()
        dropClients()
    }

    private fun serve(socket: Socket) {
        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val out = DataOutputStream(socket.getOutputStream().buffered())
        while (true) {
            val request = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            if (request.isEmpty()) return
            val cseq = request.first { it.startsWith("CSeq", ignoreCase = true) }.substringAfter(":").trim()
            val url = request[0].split(" ")[1]
            fun reply(headers: String, body: String = "") {
                out.writeBytes("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n$headers" + (if (body.isEmpty()) "" else "Content-Length: ${body.length}\r\n") + "\r\n$body")
                out.flush()
            }
            when (request[0].substringBefore(" ")) {
                "OPTIONS" -> reply("Public: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\n")
                "DESCRIBE" -> reply(
                    "Content-Base: $url/\r\nContent-Type: application/sdp\r\n",
                    "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=test\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n" +
                        "m=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=fmtp:96 packetization-mode=1\r\na=control:track1\r\n",
                )
                "SETUP" -> reply("Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 1234\r\n")
                "PLAY" -> { reply("Session: 1234\r\n"); stream(out); return }
                else -> reply("Session: 1234\r\n")
            }
        }
    }

    /** Like a live camera: the pattern over and over, until the client goes (TCP back-pressure paces it, no sleeps). */
    private fun stream(out: DataOutputStream) {
        var seq = 0
        for (i in generateSequence(0) { it + 1 }) {
            val frame = frames[i % frames.size]
            val units = AnnexBSplitter().let { it.push(frame) + it.push(byteArrayOf(0, 0, 1)) }
                .map { it.copyOfRange(if (it[2] == 1.toByte()) 3 else 4, it.size) } // RTP carries NAL units without start codes
            units.forEachIndexed { u, nal ->
                val packets = if (nal.size <= MTU) listOf(nal) else fuA(nal)
                packets.forEachIndexed { k, payload ->
                    val marker = u == units.lastIndex && k == packets.lastIndex // the frame's last packet
                    val rtp = ByteArrayOutputStream().also { b ->
                        DataOutputStream(b).run {
                            writeByte(0x80) // RTP version 2
                            writeByte(96 or (if (marker) 0x80 else 0))
                            writeShort(seq++)
                            writeInt(i * 6_000) // 90 kHz clock, 15 fps
                            writeInt(0x4B465431) // SSRC "KFT1"
                            write(payload)
                        }
                    }.toByteArray()
                    // Interleaved on the RTSP connection (RFC 2326 §10.12): '$', channel 0, 2-byte length, the packet.
                    out.writeByte('$'.code)
                    out.writeByte(0)
                    out.writeShort(rtp.size)
                    out.write(rtp)
                }
            }
            out.flush()
        }
    }

    private fun fuA(nal: ByteArray): List<ByteArray> {
        val indicator = (nal[0].toInt() and 0xE0) or 28
        val type = nal[0].toInt() and 0x1F
        val body = nal.copyOfRange(1, nal.size).toList().chunked(MTU - 2)
        return body.mapIndexed { i, part ->
            val header = type or (if (i == 0) 0x80 else 0) or (if (i == body.lastIndex) 0x40 else 0)
            byteArrayOf(indicator.toByte(), header.toByte()) + part.toByteArray()
        }
    }

    private companion object {
        const val MTU = 1_400
    }
}
