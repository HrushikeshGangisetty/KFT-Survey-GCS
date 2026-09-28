package com.kft.gcs.ui.video

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_make_q

/**
 * [count] frames of the test pattern: black (Y = 16) with a white (Y = 235) 64×64 square at (32 + 8i, 64) in frame i.
 * BT.601 video levels: Y 16 is black and 235 white, so in BGRA the square is near 255 and the background near 0.
 */
fun testPattern(count: Int) = encode(count) { i, x, y -> if (x in 32 + 8 * i until 96 + 8 * i && y in 64 until 128) 235 else 16 }

/**
 * Encodes [count] 320×240 frames whose luma is [luma] with FFmpeg's OpenH264 encoder (it's in the LGPL build), one
 * Annex-B access unit per frame. A keyframe every 5 frames, no B-frames, SPS and PPS in front of each keyframe.
 */
private fun encode(count: Int, luma: (frame: Int, x: Int, y: Int) -> Int): List<ByteArray> {
    val codec = avcodec_find_encoder_by_name("libopenh264") ?: error("no OpenH264 encoder in this FFmpeg build")
    val ctx: AVCodecContext = avcodec_alloc_context3(codec)
    ctx.width(320); ctx.height(240); ctx.pix_fmt(AV_PIX_FMT_YUV420P)
    ctx.time_base(av_make_q(1, 15)); ctx.framerate(av_make_q(15, 1))
    ctx.gop_size(5); ctx.max_b_frames(0); ctx.bit_rate(1_000_000)
    check(avcodec_open2(ctx, codec, null as AVDictionary?) >= 0)
    val frame: AVFrame = av_frame_alloc()
    frame.format(AV_PIX_FMT_YUV420P); frame.width(320); frame.height(240)
    check(av_frame_get_buffer(frame, 0) >= 0)
    val packet = av_packet_alloc()
    val out = mutableListOf<ByteArray>()
    fun drain() {
        while (avcodec_receive_packet(ctx, packet) == 0) {
            out += ByteArray(packet.size()).also { packet.data().get(it) }
            av_packet_unref(packet)
        }
    }
    for (i in 0 until count) {
        for (y in 0 until 240) for (x in 0 until 320) frame.data(0).put(y.toLong() * frame.linesize(0) + x, luma(i, x, y).toByte())
        for (plane in 1..2) for (y in 0 until 120) for (x in 0 until 160) frame.data(plane).put(y.toLong() * frame.linesize(plane) + x, 128.toByte())
        frame.pts(i.toLong())
        check(avcodec_send_frame(ctx, frame) >= 0)
        drain()
    }
    avcodec_send_frame(ctx, null)
    drain()
    av_packet_free(packet)
    av_frame_free(frame)
    avcodec_free_context(ctx)
    return out
}
