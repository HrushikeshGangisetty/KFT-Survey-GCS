@file:OptIn(ExperimentalAtomicApi::class)

package com.kft.gcs.core.keys

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Locks the [vault] after [timeoutMs] with no [touch]. The caller supplies the activity (UI input, a vehicle using its
 * key) and [nowMs], a monotonic clock, so tests run on virtual time.
 *
 * The idle window starts when the vault is first seen unlocked, so unlocking needs no hook into this class.
 *
 * [busy] says the keys are in use right now (a vehicle is connected and signing). While it is true the vault never
 * locks: locking mid-flight would zero the keys, and the FC would then drop every command we send.
 */
class IdleAutoLock(
    private val vault: Vault,
    private val nowMs: () -> Long,
    private val timeoutMs: Long = 5 * 60_000,
    private val busy: () -> Boolean = { false },
) {
    private val lastActivityMs = AtomicLong(nowMs())

    /** Call on any operator input or other sign the vault is in use. */
    fun touch() = lastActivityMs.store(nowMs())

    /** Locks if idle too long; returns true if it did. */
    fun lockIfIdle(): Boolean {
        if (!vault.isUnlocked()) {
            touch()
            return false
        }
        if (busy()) {
            touch()
            return false
        }
        if (nowMs() - lastActivityMs.load() < timeoutMs) return false
        vault.lock()
        return true
    }

    /** Checks every [pollMs] until cancelled: launch it in the application scope. */
    suspend fun run(pollMs: Long = 1_000) {
        while (currentCoroutineContext().isActive) {
            delay(pollMs)
            lockIfIdle()
        }
    }
}
