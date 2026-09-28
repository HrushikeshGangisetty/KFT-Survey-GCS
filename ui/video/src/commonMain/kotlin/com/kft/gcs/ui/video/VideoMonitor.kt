package com.kft.gcs.ui.video

/** What the video is doing, as the operator sees it. */
sealed interface VideoStatus {
    /** Opening the stream. [attempt] > 1: reconnecting after [lastError]. */
    data class Connecting(val attempt: Int, val lastError: String?) : VideoStatus

    /** Pictures are arriving. [latencyMs]: how long a picture took from arriving in the app to the screen, if known. */
    data class Live(val fps: Int, val latencyMs: Long?) : VideoStatus

    /** It stopped ([reason]); a reconnect follows shortly. */
    data class Lost(val reason: String) : VideoStatus
}

/**
 * Turns decoder events into a [VideoStatus]: frames make it live and give the frame rate, a failure or a stall makes
 * it lost, and [retry] starts the next attempt. Pure: the caller passes the time in, so the rules are tested without
 * a clock or a decoder. Used from one thread (the UI's) only.
 */
class VideoMonitor(nowMs: Long) {
    var status: VideoStatus = VideoStatus.Connecting(1, null)
        private set

    private var attempt = 1
    private var lastError: String? = null
    private var attemptStartMs = nowMs
    private var lastFrameMs = nowMs
    private var windowStartMs = nowMs
    private var framesInWindow = 0
    private var fps = 0
    private var latencyMs: Long? = null

    fun onFrame(nowMs: Long, latencyMs: Long?) {
        if (status is VideoStatus.Lost) return // a late frame from the session that just failed
        lastFrameMs = nowMs
        framesInWindow++
        if (latencyMs != null) this.latencyMs = latencyMs
        if (status is VideoStatus.Connecting) {
            windowStartMs = nowMs
            framesInWindow = 0
            status = VideoStatus.Live(fps = 0, this.latencyMs)
        }
    }

    fun onFailure(reason: String) {
        if (status is VideoStatus.Lost) return
        lastError = reason
        status = VideoStatus.Lost(reason)
    }

    /** Call about twice a second: updates the frame rate, and notices a stall or a connection that never starts. */
    fun tick(nowMs: Long) {
        when (status) {
            is VideoStatus.Live -> when {
                nowMs - lastFrameMs > STALL_MS -> onFailure("No picture for ${STALL_MS / 1000} s")
                nowMs - windowStartMs >= 1_000 -> {
                    fps = (framesInWindow * 1_000 / (nowMs - windowStartMs)).toInt()
                    windowStartMs = nowMs
                    framesInWindow = 0
                    status = VideoStatus.Live(fps, latencyMs)
                }
            }
            is VideoStatus.Connecting -> if (nowMs - attemptStartMs > CONNECT_TIMEOUT_MS) onFailure("No picture after ${CONNECT_TIMEOUT_MS / 1000} s")
            is VideoStatus.Lost -> Unit
        }
    }

    /** Starts the next attempt. */
    fun retry(nowMs: Long) {
        attempt++
        attemptStartMs = nowMs
        latencyMs = null
        status = VideoStatus.Connecting(attempt, lastError)
    }

    companion object {
        /** A live stream with no new picture for this long is lost (a SIYI camera sends 25–30 fps, the T12 15). */
        const val STALL_MS = 3_000L

        /** An attempt that shows nothing for this long is given up and retried (RTSP can hang half-open). */
        const val CONNECT_TIMEOUT_MS = 10_000L

        /** Wait before reconnect number [attempt]: 1, 2, then 3 s. Short: the camera usually comes straight back. */
        fun backoffMs(attempt: Int) = (attempt.coerceIn(1, 3) * 1_000).toLong()
    }
}
