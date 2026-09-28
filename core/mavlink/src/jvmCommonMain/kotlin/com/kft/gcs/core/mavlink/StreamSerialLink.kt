package com.kft.gcs.core.mavlink

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import okio.IOException

/**
 * A [SerialLink] over a plain pair of streams: an Android Bluetooth RFCOMM socket gives exactly that. Unlike the USB
 * and COM-port libraries, a socket's read blocks until bytes come and is unblocked (with an IOException) when the
 * socket is closed, so there's no timeout to poll; [SerialTransport]'s loop works the same either way.
 * In `jvmCommonMain` so it's tested on the desktop JVM with piped streams, without a radio.
 */
internal class StreamSerialLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val closeable: Closeable,
) : SerialLink {
    override fun read(buffer: ByteArray): Int {
        val n = input.read(buffer)
        // -1: the other end closed the link (module switched off, out of range): the reconnect loop takes over.
        if (n < 0) throw IOException("the link was closed by the other end")
        return n
    }

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() = closeable.close()
}
