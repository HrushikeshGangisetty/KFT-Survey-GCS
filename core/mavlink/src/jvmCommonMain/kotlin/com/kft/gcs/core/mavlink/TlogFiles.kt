package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Writes one `.tlog` per vehicle into [dir] (ADR-005 §7): `2026-10-01_14-03-22_link1_sys3.tlog`, named from when the
 * vehicle was first heard. Received frames only; see [tlogRecord] for the format.
 *
 * In `jvmCommonMain` because files and dates are `java.*`, the same on Android and desktop.
 *
 * @param io the dispatcher for the blocking file writes.
 * @param clockMillis wall-clock time; tests pass a fixed one.
 */
class TlogFiles(private val dir: File, private val io: CoroutineDispatcher, private val clockMillis: () -> Long = System::currentTimeMillis) : VehicleRecorder {

    /**
     * Records [frames] for [vehicle] until the caller's coroutine is cancelled (the link is disconnected). A disk
     * error ends the recording for this vehicle and nothing else: a full disk must not take the link down with it.
     */
    override suspend fun record(vehicle: VehicleId, frames: Flow<MavFrame<out MavMessage<*>>>) = withContext(io) {
        var out: OutputStream? = null
        var lastFlush = 0L
        try {
            frames.collect { frame ->
                val now = clockMillis()
                // Opened on the first frame, so a vehicle that never sends anything leaves no empty file.
                val stream = out ?: run {
                    dir.mkdirs()
                    val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(now))
                    File(dir, "${stamp}_link${vehicle.link}_sys${vehicle.systemId}.tlog").outputStream().buffered().also { out = it }
                }
                stream.write(tlogRecord(now * 1000, frame))
                // Once a second: an app crash loses at most the last second, without a disk write per frame.
                if (now - lastFlush >= 1000) {
                    stream.flush()
                    lastFlush = now
                }
            }
        } catch (e: java.io.IOException) {
            // Nothing to do but stop: the next link session starts a new file.
        } finally {
            runCatching { out?.close() }
        }
    }
}
