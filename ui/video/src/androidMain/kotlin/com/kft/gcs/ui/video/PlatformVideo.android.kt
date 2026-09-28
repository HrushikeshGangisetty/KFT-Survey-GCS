package com.kft.gcs.ui.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android: Media3 (ExoPlayer) for RTSP, MediaCodec for the T12's H.264. Both draw into a TextureView
 * ([AndroidEmbeddedExternalSurface]) rather than a SurfaceView: a TextureView is drawn in the normal view order, so
 * the picture-in-picture window and the swap with the map (a change of size and z-order, ADR-001 F10) behave like any
 * other composable. A SurfaceView sits in its own layer behind the window, and the old KFT app found ExoPlayer's
 * picture vanishing under overlays that way. The cost is about one frame of extra latency.
 */
@Composable
internal actual fun PlatformVideo(source: VideoSource, events: VideoEvents, modifier: Modifier) {
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    Box(modifier, contentAlignment = Alignment.Center) {
        val fit = Modifier.aspectRatio(aspect)
        when (source) {
            is PlayerSource -> RtspVideo(source.url, events, fit) { aspect = it }
            is ElementaryStreamSource -> DecodedVideo(source, events, fit) { aspect = it }
        }
    }
}

/**
 * RTSP through ExoPlayer, set up the way the old KFT app plays the SIYI camera: RTP interleaved over the RTSP TCP
 * connection (`setForceUseRtpTcp`), no custom socket factory (binding to a chosen network broke playback where the
 * default route already reaches the camera), and next to no buffering, because a stale picture is worse than a
 * dropped frame. If the player still drifts behind live, it plays 10 % fast until it has caught up: seeking would
 * make ExoPlayer's RTSP source send PAUSE/PLAY to the camera and restart the stream.
 */
@OptIn(UnstableApi::class)
@Composable
private fun RtspVideo(url: String, events: VideoEvents, modifier: Modifier, onAspect: (Float) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(0, MAX_BUFFER_MS, 0, 0)
            .setTargetBufferBytes(C.LENGTH_UNSET)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context).setLoadControl(loadControl).build().apply {
            setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).createMediaSource(MediaItem.fromUri(url)))
            playWhenReady = true
            prepare()
        }
    }
    // How far playback is behind what has arrived: the latency this app adds on top of the camera's own.
    var behindMs by remember { mutableFloatStateOf(0f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = events.failed(describe(error))
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) onAspect(size.width * size.pixelWidthHeightRatio / size.height)
            }
        }
        player.addListener(listener)
        // Called on the playback thread for every frame about to be shown: the frame count for the fps.
        player.setVideoFrameMetadataListener { _, _, _, _ -> events.frame(behindMs.toLong()) }
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        var boosted = false
        while (true) {
            delay(1_000)
            val behind = player.bufferedPosition - player.currentPosition
            behindMs = behind.toFloat()
            if (!boosted && behind > CATCH_UP_ABOVE_MS) { player.setPlaybackSpeed(1.1f); boosted = true }
            if (boosted && behind < CATCH_UP_UNTIL_MS) { player.setPlaybackSpeed(1f); boosted = false }
        }
    }
    AndroidEmbeddedExternalSurface(modifier) {
        onSurface { surface, _, _ ->
            player.setVideoSurface(surface)
            surface.onDestroyed { player.clearVideoSurface(this) }
        }
    }
}

/**
 * The T12's H.264, decoded by MediaCodec straight onto the surface. `KEY_LOW_LATENCY` (Android 11+) asks the decoder
 * to hand each frame out as soon as it's decoded instead of holding a few back. Input (NAL units from [NalQueue]) and
 * output (render and count) run in two loops, so a finished frame is shown at once rather than when the next unit
 * arrives.
 */
@Composable
private fun DecodedVideo(source: ElementaryStreamSource, events: VideoEvents, modifier: Modifier, onAspect: (Float) -> Unit) {
    AndroidEmbeddedExternalSurface(modifier) {
        onSurface { surface, _, _ ->
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                codec.configure(format, surface, null, 0)
                codec.start()
                withContext(Dispatchers.IO) {
                    val queue = NalQueue()
                    launch { source.bytes.collect(queue::push) } // collecting opens the T12's port and turns its video on
                    launch { renderLoop(codec, events, onAspect) }
                    while (isActive) {
                        val unit = queue.receive()
                        var index = codec.dequeueInputBuffer(10_000)
                        while (index < 0 && isActive) index = codec.dequeueInputBuffer(10_000)
                        if (index < 0) break
                        codec.getInputBuffer(index)!!.apply { clear(); put(unit) }
                        // The arrival time as the timestamp: the output side turns it into the decode latency.
                        codec.queueInputBuffer(index, 0, unit.size, System.nanoTime() / 1_000, 0)
                    }
                }
            } catch (e: IllegalStateException) {
                // MediaCodec.CodecException is one: the decoder broke (or the surface went). Retried by VideoView.
                events.failed("Decoder error: ${e.message}")
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        }
    }
}

private suspend fun renderLoop(codec: MediaCodec, events: VideoEvents, onAspect: (Float) -> Unit) = kotlinx.coroutines.coroutineScope {
    val info = MediaCodec.BufferInfo()
    while (isActive) {
        val index = codec.dequeueOutputBuffer(info, 10_000)
        when {
            index >= 0 -> {
                codec.releaseOutputBuffer(index, true) // true: render it to the surface
                events.frame((System.nanoTime() / 1_000 - info.presentationTimeUs) / 1_000)
            }
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> codec.outputFormat.let { f ->
                val w = f.getInteger(MediaFormat.KEY_WIDTH)
                val h = f.getInteger(MediaFormat.KEY_HEIGHT)
                if (w > 0 && h > 0) onAspect(w.toFloat() / h)
            }
        }
    }
}

/** An ExoPlayer error in words a pilot can act on. */
@OptIn(UnstableApi::class)
private fun describe(error: PlaybackException): String {
    // Media3's RTSP reader can't unpack H.265 aggregation packets (NAL type 48), which SIYI cameras send in H.265
    // mode: it throws UnsupportedOperationException. Name the fix instead of showing an error code.
    if (generateSequence(error.cause) { it.cause }.any { it is UnsupportedOperationException }) {
        return "The camera sends H.265, which the tablet can't play over RTSP: set it to H.264"
    }
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Can't reach the camera: is the tablet on the air unit's network?"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The camera has no stream at this path: check the URL"
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Can't decode this stream: set the camera to H.264"
        else -> error.errorCodeName
    }
}

private const val MAX_BUFFER_MS = 1_000
private const val CATCH_UP_ABOVE_MS = 700L
private const val CATCH_UP_UNTIL_MS = 300L
