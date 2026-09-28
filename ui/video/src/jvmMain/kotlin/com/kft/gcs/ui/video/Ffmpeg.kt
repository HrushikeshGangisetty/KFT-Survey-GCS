package com.kft.gcs.ui.video

import java.io.IOException
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG2_CHUNKS
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_LOW_DELAY
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_H264
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_network_init
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EAGAIN
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/** One decoded picture: [width] × [height] pixels, 4 bytes each in B, G, R, A order. */
internal class Picture(val width: Int, val height: Int, val bgra: ByteArray)

/**
 * An FFmpeg video decoder (the desktop's). For RTSP it's built from the stream's own parameters, so H.265 works too;
 * for the T12 it's H.264 fed one NAL unit at a time, which [AV_CODEC_FLAG2_CHUNKS] allows ("packets may end
 * anywhere, not only at frame boundaries"). [AV_CODEC_FLAG_LOW_DELAY] hands each picture out as soon as it's
 * decoded instead of holding frames back for reordering: our cameras send no B-frames, so nothing is lost.
 *
 * Not thread-safe: one decoding thread per instance. [close] frees the native memory.
 */
internal class FfmpegDecoder(parameters: AVCodecParameters? = null) : AutoCloseable {
    private val context: AVCodecContext
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()
    private var sws: SwsContext? = null
    private var bgra = BytePointer()

    /** sws_scale's output: one plane (BGRA is packed) and its bytes per row. */
    private val planes = PointerPointer<BytePointer>(1L)
    private val strides = IntPointer(1L)

    init {
        quietLogs
        val codec = avcodec_find_decoder(parameters?.codec_id() ?: AV_CODEC_ID_H264)
            ?: throw IOException("FFmpeg has no decoder for this video")
        context = avcodec_alloc_context3(codec)
        if (parameters != null) avcodec_parameters_to_context(context, parameters)
        context.flags(context.flags() or AV_CODEC_FLAG_LOW_DELAY)
        context.flags2(context.flags2() or AV_CODEC_FLAG2_CHUNKS)
        check(avcodec_open2(context, codec, null as AVDictionary?), "open the decoder")
    }

    /** Decodes one NAL unit (T12). */
    fun decode(unit: ByteArray, onPicture: (Picture) -> Unit) {
        check(av_new_packet(packet, unit.size), "allocate a packet")
        packet.data().put(unit, 0, unit.size)
        try {
            decode(packet, onPicture)
        } finally {
            av_packet_unref(packet)
        }
    }

    /** Decodes one demuxed packet (RTSP). Corrupt data is skipped, as a player should; only a broken decoder throws. */
    fun decode(packet: AVPacket, onPicture: (Picture) -> Unit) {
        if (avcodec_send_packet(context, packet) < 0) return // a damaged packet: the next keyframe repairs the picture
        while (avcodec_receive_frame(context, frame) == 0) onPicture(convert(frame))
    }

    /** YUV (whatever the stream uses) to BGRA, the byte order Skia's BGRA_8888 bitmaps take. */
    private fun convert(f: AVFrame): Picture {
        val w = f.width()
        val h = f.height()
        sws = sws_getCachedContext(sws, w, h, f.format(), w, h, AV_PIX_FMT_BGRA, SWS_BILINEAR, null, null, null as DoublePointer?)
        val bytes = w * h * 4
        if (bgra.capacity() < bytes) {
            bgra.close()
            bgra = BytePointer(bytes.toLong())
            // Careful: PointerPointer(bgra) would be JavaCPP's cast constructor (bgra's own bytes read as pointers).
            planes.put(0L, bgra)
        }
        strides.put(0L, w * 4)
        sws_scale(sws, f.data(), f.linesize(), 0, h, planes, strides)
        return Picture(w, h, ByteArray(bytes).also { bgra.position(0).get(it) })
    }

    override fun close() {
        sws?.let(::sws_freeContext)
        av_frame_free(frame)
        av_packet_free(packet)
        avcodec_free_context(context)
        bgra.close()
        planes.close()
        strides.close()
    }
}

/**
 * Reads an RTSP stream with FFmpeg and decodes its video until [stopped] turns true (then it returns) or the stream
 * fails (then it throws [IOException] with a readable reason). Options for a live picture, not a smooth one:
 * - `rtsp_transport tcp`: RTP interleaved on the RTSP connection, as the old KFT app does with the SIYI camera. No
 *   lost packets to smear the picture, and no UDP ports for Windows' firewall to block.
 * - `fflags nobuffer`, `flags low_delay`, `max_delay 0`: don't queue packets before decoding them.
 * - `timeout 5 s`: a camera that goes quiet fails the read, which starts a reconnect.
 * [stopped] is also polled by FFmpeg's interrupt callback, so closing the view ends a blocked read at once.
 */
internal fun playRtsp(url: String, stopped: () -> Boolean, onPicture: (Picture, arrivedNs: Long) -> Unit) {
    quietLogs
    val format = avformat_alloc_context() ?: throw IOException("FFmpeg out of memory")
    val interrupt = object : AVIOInterruptCB.Callback_Pointer() {
        override fun call(opaque: Pointer?) = if (stopped()) 1 else 0
    }
    format.interrupt_callback().callback(interrupt)
    val options = AVDictionary(null)
    av_dict_set(options, "rtsp_transport", "tcp", 0)
    av_dict_set(options, "fflags", "nobuffer", 0)
    av_dict_set(options, "flags", "low_delay", 0)
    av_dict_set(options, "max_delay", "0", 0)
    av_dict_set(options, "timeout", "5000000", 0) // microseconds
    var packet: AVPacket? = null
    var decoder: FfmpegDecoder? = null
    try {
        val opened = avformat_open_input(format, url, null, options)
        if (stopped()) return
        if (opened < 0) throw IOException("Can't open $url: ${errorText(opened)}")
        check(avformat_find_stream_info(format, null as PointerPointer<*>?), "read the stream's format")
        val index = (0 until format.nb_streams()).firstOrNull { format.streams(it).codecpar().codec_type() == AVMEDIA_TYPE_VIDEO }
            ?: throw IOException("$url has no video")
        decoder = FfmpegDecoder(format.streams(index).codecpar())
        packet = av_packet_alloc()
        while (!stopped()) {
            val read = av_read_frame(format, packet)
            if (stopped()) return
            if (read < 0) throw IOException("The stream stopped: ${errorText(read)}")
            val arrived = System.nanoTime()
            if (packet.stream_index() == index) decoder.decode(packet) { onPicture(it, arrived) }
            av_packet_unref(packet)
        }
    } finally {
        packet?.let(::av_packet_free)
        decoder?.close()
        avformat_close_input(format)
        av_dict_free(options)
        interrupt.close()
    }
}

/** Once per process: FFmpeg's own chatter (every damaged packet) would flood the console; errors still show. */
private val quietLogs: Unit by lazy {
    av_log_set_level(AV_LOG_ERROR)
    avformat_network_init()
    Unit
}

private fun check(result: Int, what: String) {
    if (result < 0) throw IOException("FFmpeg couldn't $what: ${errorText(result)}")
}

private fun errorText(code: Int): String {
    if (code == AVERROR_EAGAIN()) return "try again"
    val buffer = ByteArray(256)
    av_strerror(code, buffer, buffer.size.toLong())
    return String(buffer, Charsets.US_ASCII).substringBefore('\u0000') // a C string: up to its terminating zero
}
