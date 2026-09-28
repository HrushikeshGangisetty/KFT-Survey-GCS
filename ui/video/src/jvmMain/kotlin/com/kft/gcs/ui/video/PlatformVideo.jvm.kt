package com.kft.gcs.ui.video

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * Desktop: FFmpeg decodes both kinds of source on an IO thread; the newest picture is drawn, scaled to fit.
 *
 * Only the UI thread touches the Skia bitmap: the decoder hands over the newest [Picture] and bumps a counter, and
 * the next draw copies it in. A picture that arrives before the previous one was drawn simply replaces it, so a slow
 * screen shows the latest picture rather than falling behind.
 */
@Composable
internal actual fun PlatformVideo(source: VideoSource, events: VideoEvents, modifier: Modifier) {
    val latest = remember { AtomicReference<Picture?>(null) }
    var pictures by remember { mutableIntStateOf(0) }
    val bitmap = remember { Bitmap() }
    DisposableEffect(bitmap) { onDispose { bitmap.close() } }

    LaunchedEffect(source) {
        withContext(Dispatchers.IO) {
            val job = coroutineContext.job
            val onPicture = { p: Picture, arrivedNs: Long ->
                latest.set(p)
                pictures++
                events.frame((System.nanoTime() - arrivedNs) / 1_000_000)
            }
            try {
                when (source) {
                    is PlayerSource -> playRtsp(source.url, stopped = { !job.isActive }, onPicture)
                    is ElementaryStreamSource -> playElementary(source, onPicture)
                }
            } catch (e: IOException) {
                if (job.isActive) events.failed(e.message ?: "The video stopped")
            }
        }
    }

    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") pictures // read, so a new picture redraws
        latest.getAndSet(null)?.let { p ->
            bitmap.installPixels(ImageInfo(p.width, p.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), p.bgra, p.width * 4)
        }
        if (bitmap.width == 0) return@Canvas
        // Fit inside, keeping the aspect ratio; the letterbox bars are the view's background.
        val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
        val w = (bitmap.width * scale).roundToInt()
        val h = (bitmap.height * scale).roundToInt()
        drawImage(
            bitmap.asComposeImageBitmap(),
            dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w, h),
        )
    }
}

/** The T12's H.264: split and gated by [NalQueue], decoded by FFmpeg one NAL unit at a time. */
private suspend fun playElementary(source: ElementaryStreamSource, onPicture: (Picture, Long) -> Unit) = coroutineScope {
    val queue = NalQueue()
    launch { source.bytes.collect(queue::push) } // collecting opens the T12's port and turns its video on
    FfmpegDecoder().use { decoder ->
        while (true) {
            val unit = queue.receive()
            val arrived = System.nanoTime()
            decoder.decode(unit) { onPicture(it, arrived) }
        }
    }
}
