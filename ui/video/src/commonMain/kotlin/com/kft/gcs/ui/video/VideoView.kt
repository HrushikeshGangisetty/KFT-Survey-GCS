package com.kft.gcs.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.kft.gcs.ui.design.Spacing
import com.kft.gcs.ui.design.Status
import com.kft.gcs.ui.design.StatusChip
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

/**
 * Plays [source], reconnecting by itself: a failed or stalled stream is shown as lost, then reopened after a short
 * wait, for as long as this is on screen. A status chip says connecting / live / lost; [showStats] adds the frame
 * rate and latency to it.
 *
 * The platform part ([PlatformVideo]) only plays one attempt and reports frames and failures; this wrapper owns the
 * status and the retries, the same on both platforms.
 */
@Composable
fun VideoView(source: VideoSource, modifier: Modifier = Modifier, showStats: Boolean = false) {
    val clock = remember { TimeSource.Monotonic.markNow() }
    fun now() = clock.elapsedNow().inWholeMilliseconds
    val monitor = remember(source) { VideoMonitor(now()) }
    var status by remember(source) { mutableStateOf(monitor.status) }
    var attempt by remember(source) { mutableIntStateOf(1) }

    // Decoders report from their own threads; a SharedFlow hands the events to the UI thread, where the monitor lives.
    val events = remember(source) { MutableSharedFlow<VideoEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST) }
    val sink = remember(events) {
        object : VideoEvents {
            override fun frame(latencyMs: Long?) { events.tryEmit(VideoEvent.Frame(latencyMs)) }
            override fun failed(reason: String) { events.tryEmit(VideoEvent.Failed(reason)) }
        }
    }
    LaunchedEffect(source) {
        val ticks = flow { while (true) { delay(500); emit(VideoEvent.Tick) } }
        merge(events, ticks).collect { event ->
            when (event) {
                is VideoEvent.Frame -> monitor.onFrame(now(), event.latencyMs)
                is VideoEvent.Failed -> monitor.onFailure(event.reason)
                VideoEvent.Tick -> monitor.tick(now())
            }
            status = monitor.status
            val lost = monitor.status
            if (lost is VideoStatus.Lost) {
                delay(VideoMonitor.backoffMs(attempt))
                monitor.retry(now())
                attempt++
                status = monitor.status
            }
        }
    }

    val problem by (source as? ElementaryStreamSource)?.problem?.collectAsState() ?: remember { mutableStateOf<String?>(null) } as State<String?>

    Box(modifier.background(MaterialTheme.colorScheme.scrim)) {
        // A new attempt is a new player: key() throws the old one away and composes a fresh one.
        key(attempt) { PlatformVideo(source, sink, Modifier.fillMaxSize()) }
        StatusLine(status, problem, showStats, Modifier.align(Alignment.BottomStart).padding(Spacing.s))
    }
}

@Composable
private fun StatusLine(status: VideoStatus, problem: String?, showStats: Boolean, modifier: Modifier) {
    when (status) {
        is VideoStatus.Connecting -> {
            val why = problem ?: status.lastError
            StatusChip((if (status.attempt == 1) "Connecting…" else "Reconnecting…") + (why?.let { " $it" } ?: ""), Status.WARN, modifier)
        }
        is VideoStatus.Lost -> StatusChip("Video lost: ${status.reason}", Status.CRITICAL, modifier)
        is VideoStatus.Live -> if (showStats) {
            StatusChip("Live · ${status.fps} fps" + (status.latencyMs?.let { " · $it ms" } ?: ""), Status.OK, modifier)
        }
    }
}

private sealed interface VideoEvent {
    data class Frame(val latencyMs: Long?) : VideoEvent
    data class Failed(val reason: String) : VideoEvent
    data object Tick : VideoEvent
}

/** What a platform player reports. Safe to call from any thread. */
interface VideoEvents {
    /** A picture reached the screen. [latencyMs]: from arriving in the app to the screen, if the player can tell. */
    fun frame(latencyMs: Long?)

    /** This attempt is over; [reason] is shown to the operator. */
    fun failed(reason: String)
}

/**
 * Plays one attempt at [source] until it fails or leaves composition. Android: Media3 for RTSP, MediaCodec for the
 * T12's H.264. Desktop: FFmpeg for both.
 */
@Composable
internal expect fun PlatformVideo(source: VideoSource, events: VideoEvents, modifier: Modifier)
