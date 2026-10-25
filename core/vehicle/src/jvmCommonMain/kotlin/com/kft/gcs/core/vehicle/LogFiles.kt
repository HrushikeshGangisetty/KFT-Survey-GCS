package com.kft.gcs.core.vehicle

import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Writes each downloaded log into [dir] as `sys1_log12_2026-10-25_09-14-02Z.bin`: the vehicle, ArduPilot's list
 * number, and when the log started (UTC, from LOG_ENTRY). Mission Planner, MAVExplorer and PPK tools open `.bin`
 * directly. A log the FC couldn't timestamp is named after the download time instead (`…_downloaded-…`).
 *
 * The file is written as `<name>.part` and renamed when complete, so a cancelled or failed download never leaves a
 * file that looks whole. In `jvmCommonMain` because files and dates are `java.*`, the same on Android and desktop.
 *
 * @param io the dispatcher for the blocking file writes.
 * @param clockMillis wall-clock time, for logs without their own; tests pass a fixed one.
 */
class LogFiles(private val dir: File, private val io: CoroutineDispatcher, private val clockMillis: () -> Long = System::currentTimeMillis) : LogStore {

    override suspend fun create(systemId: Int, log: FlightLog): LogSink = withContext(io) {
        dir.mkdirs()
        val stamp = log.timeUtcSeconds?.let { utc(it * 1000) + "Z" } ?: ("downloaded-" + utc(clockMillis()) + "Z")
        val base = "sys${systemId}_log${log.id}_$stamp"
        // A second download of the same log gets "-2", "-3"…: an earlier copy is never overwritten.
        val target = generateSequence(1) { it + 1 }.map { n -> File(dir, if (n == 1) "$base.bin" else "$base-$n.bin") }.first { !it.exists() }
        val part = File(dir, target.name + ".part")
        FileSink(target, part, part.outputStream().buffered(), io)
    }

    private fun utc(millis: Long) = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis))

    private class FileSink(val target: File, val part: File, val out: OutputStream, val io: CoroutineDispatcher) : LogSink {
        override suspend fun write(bytes: ByteArray) = withContext(io) { out.write(bytes) }

        override suspend fun finish(): String = withContext(io) {
            out.close()
            if (!part.renameTo(target)) throw java.io.IOException("couldn't rename ${part.name}")
            target.path
        }

        override suspend fun abort() {
            withContext(io) {
                runCatching { out.close() }
                part.delete()
            }
        }
    }
}
