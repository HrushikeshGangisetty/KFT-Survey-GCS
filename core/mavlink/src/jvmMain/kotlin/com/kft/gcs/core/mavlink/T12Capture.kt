package com.kft.gcs.core.mavlink

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * T12 capture files: every answer the T12 gave to a poll, raw, in order. Recorded on the hardware bench by
 * `tools/t12/capture`, replayed by the tests (`T12ReplayTest` in `ui:video`) through the same framing and H.264 code
 * the app uses.
 *
 * Format: the 8 bytes "T12CAP1\n", then one record per poll: a 2-byte big-endian length and that many bytes (0 when
 * the T12 didn't answer within the poll slot, 512 for a good frame).
 */
object T12CaptureFile {
    private val MAGIC = "T12CAP1\n".encodeToByteArray()

    fun writer(file: File): (ByteArray, Int) -> Unit {
        val out = DataOutputStream(file.outputStream().buffered())
        out.write(MAGIC)
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(out) { out.close() } })
        return { bytes, n -> synchronized(out) { out.writeShort(n); out.write(bytes, 0, n) } }
    }

    fun read(file: File): List<ByteArray> = DataInputStream(file.inputStream().buffered()).use { input ->
        require(input.readNBytes(MAGIC.size).contentEquals(MAGIC)) { "$file is not a T12 capture" }
        buildList {
            while (true) {
                val n = try { input.readUnsignedShort() } catch (e: EOFException) { break }
                add(input.readNBytes(n))
            }
        }
    }
}

/**
 * `gradlew :core:mavlink:t12Capture --args="<file> [seconds]"`: finds the T12 on USB, turns its video on and records
 * every poll answer for [seconds] (default 20), printing what it sees each second. Uses [T12Link] itself, so the
 * capture is exactly what the app would have received.
 */
fun main(args: Array<String>) = runBlocking {
    val file = File(args.getOrNull(0) ?: "t12-capture.t12cap")
    val seconds = args.getOrNull(1)?.toInt() ?: 20
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val link = T12Link(scope, Dispatchers.IO, SerialPorts(), T12CaptureFile.writer(file))
    var videoBytes = 0L
    val video = scope.launch { link.video.collect { videoBytes += it.size } }
    println("Recording the T12 to ${file.absolutePath} for $seconds s …")
    repeat(seconds) {
        delay(1.seconds)
        println("${it + 1} s: ${videoBytes / 1024} KiB of video so far" + (link.problem.value?.let { p -> " · $p" } ?: ""))
    }
    video.cancel()
    delay(200) // let the link send "video off" and close the port
    scope.cancel()
    println("Done: ${file.length() / 1024} KiB. Copy it to tools/t12/captures/ and run gradlew check.")
}
