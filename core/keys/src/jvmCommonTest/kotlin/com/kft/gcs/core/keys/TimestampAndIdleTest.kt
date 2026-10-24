package com.kft.gcs.core.keys

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class TimestampFileTest {
    private var now = 0L
    private val store = MemoryStore()
    private val file = TimestampFile(store, nowMs = { now })

    @Test
    fun theDiskIsTouchedAtMostOnceEveryTenSeconds() {
        file.save("a", 100)
        assertEquals(1, store.writes, "the first save writes at once")

        now = 9_999
        file.save("a", 200)
        assertEquals(1, store.writes)
        assertEquals(200L, file.load("a"), "memory is always current")

        now = 10_000
        file.save("a", 300)
        assertEquals(2, store.writes)
        assertEquals("""{"a":300}""", store.bytes!!.decodeToString())
    }

    @Test
    fun flushWritesWhatTheLimitHeldBackOnlyOnce() {
        file.save("a", 1)
        now = 1_000
        file.save("b", 2)
        assertEquals(1, store.writes)

        file.flush()
        file.flush()

        assertEquals(2, store.writes)
        assertEquals("""{"a":1,"b":2}""", store.bytes!!.decodeToString())
    }

    @Test
    fun aTimestampNeverGoesDown() {
        file.save("a", 100)
        file.save("a", 50)
        assertEquals(100L, file.load("a"))
    }

    @Test
    fun valuesComeBackAfterARestartAndADamagedFileIsEmptyHistory() {
        file.save("a", 777)

        assertEquals(777L, TimestampFile(store, { now }).load("a"))
        assertNull(TimestampFile(MemoryStore("not json".encodeToByteArray()), { now }).load("a"))
        assertNull(TimestampFile(MemoryStore(), { now }).load("a"))
    }
}

class IdleAutoLockTest {

    @Test
    fun theVaultLocksOnlyAfterTheTimeoutAndTouchRestartsTheClock() = runTest {
        val vault = createdVault()
        val idle = IdleAutoLock(vault, nowMs = { testScheduler.currentTime }, timeoutMs = 60_000)
        backgroundScope.launch { idle.run(pollMs = 1_000) }

        advanceTimeBy(59_000)
        assertTrue(vault.isUnlocked())

        idle.touch()
        advanceTimeBy(59_000)
        assertTrue(vault.isUnlocked(), "59 s after the touch is still inside the window")

        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(vault.isUnlocked())
    }

    /** Locking mid-flight would zero the keys and the FC would drop every command, so a signed vehicle keeps the vault open. */
    @Test
    fun aBusyVaultIsNeverLockedHoweverLongTheOperatorIsIdle() = runTest {
        val vault = createdVault()
        var signedVehicleConnected = true
        val idle = IdleAutoLock(vault, nowMs = { testScheduler.currentTime }, timeoutMs = 60_000, busy = { signedVehicleConnected })
        backgroundScope.launch { idle.run(pollMs = 1_000) }

        advanceTimeBy(600_000)
        assertTrue(vault.isUnlocked(), "ten minutes with a signed vehicle connected")

        signedVehicleConnected = false
        advanceTimeBy(59_000)
        assertTrue(vault.isUnlocked(), "the idle window restarts when the vehicle goes away")
        advanceTimeBy(3_000)
        runCurrent()
        assertFalse(vault.isUnlocked())
    }

    @Test
    fun theWindowStartsWhenTheVaultIsUnlockedNotWhenTheTimerWasBuilt() = runTest {
        val vault = createdVault().also { it.lock() }
        val idle = IdleAutoLock(vault, nowMs = { testScheduler.currentTime }, timeoutMs = 60_000)
        backgroundScope.launch { idle.run(pollMs = 1_000) }

        advanceTimeBy(500_000) // locked the whole time
        vault.unlock("1234")
        advanceTimeBy(30_000)
        assertTrue(vault.isUnlocked(), "an old construction time must not lock a fresh unlock at once")

        advanceTimeBy(40_000)
        runCurrent()
        assertFalse(vault.isUnlocked())
    }
}
