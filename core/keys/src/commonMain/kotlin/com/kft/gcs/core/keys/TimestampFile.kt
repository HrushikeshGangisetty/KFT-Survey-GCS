@file:OptIn(ExperimentalAtomicApi::class)

package com.kft.gcs.core.keys

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The last outgoing signing timestamp per drone (10 us ticks), as plain JSON `{"<drone_id>": ts}` (PRD FR-10).
 *
 * Not secret, so it is not encrypted; losing it is recoverable because the next session starts from the wall clock. It
 * changes on every frame, so [save] touches the disk at most once per [minIntervalMs]; [flush] writes what was held back.
 * [nowMs] is injected so tests can use virtual time.
 *
 * Thread-safe without a lock: the map is an immutable snapshot swapped atomically.
 * ponytail: two threads that both find the interval elapsed can both write, last rename wins; the lost update is at most
 * [minIntervalMs] of ticks and the wall clock dominates after a restart anyway.
 */
class TimestampFile(
    private val store: ByteStore,
    private val nowMs: () -> Long,
    private val minIntervalMs: Long = 10_000,
) {
    private val serializer = MapSerializer(String.serializer(), Long.serializer())
    private val map = AtomicReference<Map<String, Long>?>(null)
    private val lastWriteMs = AtomicLong(Long.MIN_VALUE)
    private val dirty = AtomicBoolean(false)

    fun load(droneId: String): Long? = current()[droneId]

    /** Remembers [ts] (never lowering a stored value) and writes the file if [minIntervalMs] has passed since the last write. */
    fun save(droneId: String, ts: Long) {
        while (true) {
            val old = current()
            if ((old[droneId] ?: Long.MIN_VALUE) >= ts) break
            if (map.compareAndSet(old, old + (droneId to ts))) break
        }
        dirty.store(true)
        val last = lastWriteMs.load()
        if (last == Long.MIN_VALUE || nowMs() - last >= minIntervalMs) flush()
    }

    /** Writes the file now if anything changed since the last write. */
    fun flush() {
        if (!dirty.compareAndSet(true, false)) return
        lastWriteMs.store(nowMs())
        store.write(vaultJson.encodeToString(serializer, current()).encodeToByteArray())
    }

    private fun current(): Map<String, Long> {
        map.load()?.let { return it }
        // A missing or damaged file is the same as no history: the wall clock takes over (PRD: "losing them is recoverable").
        val loaded = runCatching { vaultJson.decodeFromString(serializer, store.read()!!.decodeToString()) }.getOrDefault(emptyMap())
        return if (map.compareAndSet(null, loaded)) loaded else map.load()!!
    }
}
