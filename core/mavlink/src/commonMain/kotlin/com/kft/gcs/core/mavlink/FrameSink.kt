package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.MavRawFrame
import com.divpundir.mavlink.definitions.common.CommandLong
import com.kft.gcs.core.mavlink.signing.SigningClock
import com.kft.gcs.core.mavlink.signing.SigningKeyring
import com.kft.gcs.core.mavlink.signing.signV2
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.IOException

/**
 * The one writer of a link's byte stream (ADR-006). [MavTxGateway] hands it every outgoing message; nothing else
 * writes to the transport's sink, and mavlink-kotlin's own `send*` is never called.
 *
 * Why not the library's `sendUnsignedV2`: it keeps its own sequence counter and takes it outside its lock (ADR-006
 * finding 4), and its signing can only write a 32-bit timestamp (finding 1). Here the sequence number, the signing
 * timestamp and the write happen under one [Mutex], so two senders can never interleave or reuse either.
 *
 * Frames are built with the library's own `MavRawFrame.createUnsignedV2`, so unsigned output is byte-for-byte what the
 * library wrote before ([FrameSinkTest] proves it), and signed with [signV2] when the caller names a drone.
 */
internal class FrameSink(
    private val sink: BufferedSink,
    private val keyring: SigningKeyring,
    /** Unix wall clock in milliseconds; read once per drone, when its signing clock starts. */
    private val nowMs: () -> Long,
    /** The write is blocking I/O, so it runs here (the library's coroutine adapter did the same). */
    private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()

    // Both guarded by mutex.
    private var sequence = 0
    private val clocks = HashMap<String, SigningClock>()

    // The highest verified incoming timestamp per drone. One writer (the reader thread, via observeIncoming) swaps in a
    // new map; the writer path reads it under the mutex. A swap at heartbeat rate costs nothing.
    @Volatile
    private var incoming: Map<String, Long> = emptyMap()

    /**
     * Writes [message] from [sysId]/[compId] as one MAVLink 2 frame and flushes it. With [signFor] = a drone id, the
     * frame is signed with that drone's key and its own 48-bit clock; with null it goes out unsigned.
     *
     * Throws [IOException] if the link fails, or if [signFor] names a drone whose key the keyring can't give (vault
     * locked, key removed). In that case nothing is written: a frame meant to be signed never goes out unsigned.
     */
    suspend fun write(message: MavMessage<*>, sysId: UByte, compId: UByte, signFor: String?) = withContext(io) {
        // COMMAND_LONG goes out bit-exact: the library's own encoder would rewrite NaN bit patterns (BitExactCommandLong).
        // ponytail: only COMMAND_LONG; other float messages still use the library encoder. Extend if one ever carries bit patterns.
        val wire = if (message is CommandLong) BitExactCommandLong(message) else message
        val companion = wire.instanceCompanion
        mutex.withLock {
            // The key first: if it's missing we throw before using a sequence number or writing anything.
            val key = signFor?.let { keyring.keyFor(it) ?: throw IOException("no signing key for drone $it (vault locked or key removed)") }
            val unsigned = MavRawFrame.createUnsignedV2(sequence.toUByte(), sysId, compId, companion.id, wire.serializeV2(), companion.crcExtra).rawBytes
            sequence = (sequence + 1) and 0xFF
            val bytes = if (signFor == null || key == null) unsigned
            else signV2(unsigned, companion.crcExtra, key, keyring.linkId, clockFor(signFor).next())
            sink.write(bytes)
            sink.flush()
        }
    }

    /**
     * Records a verified incoming timestamp from [droneId], so our next frame to it is stamped above it (MAVLink signing
     * spec; PRD FR-10). Called by [com.kft.gcs.core.mavlink.signing.SigningSource] from the reader thread only, and only
     * after the signature checked out, so a forger can't push our clock forward.
     */
    fun observeIncoming(droneId: String, ts: Long) {
        if (ts > (incoming[droneId] ?: -1)) incoming = incoming + (droneId to ts)
    }

    /**
     * Saves every drone's outgoing clock through the keyring, so the next link session starts above it even if the wall
     * clock has gone back (PRD "Timestamps"). Only drones this link has signed for have a clock; the rest have nothing
     * to save. Under the mutex so a value is never saved while a frame is taking the next one.
     */
    suspend fun saveTimestamps() = withContext(io) {
        mutex.withLock { clocks.forEach { (droneId, clock) -> keyring.saveTimestamp(droneId, clock.current) } }
    }

    /** Call under [mutex]. Each drone has its own clock, started from the wall clock and its saved timestamp. */
    private fun clockFor(droneId: String): SigningClock {
        val clock = clocks.getOrPut(droneId) { SigningClock().apply { start(nowMs(), keyring.loadTimestamp(droneId)) } }
        incoming[droneId]?.let(clock::observeIncoming)
        return clock
    }
}
