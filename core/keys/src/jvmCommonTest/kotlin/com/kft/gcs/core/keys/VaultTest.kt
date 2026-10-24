package com.kft.gcs.core.keys

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Real Argon2id (64 MiB) and AES-GCM throughout: the vault's job is the crypto, so faking it would test nothing. */
class VaultTest {

    @Test
    fun aVaultSurvivesBeingLockedAndReopenedOnAFreshInstance() = runTest {
        val store = MemoryStore()
        val vault = createdVault(store)
        vault.addAll(listOf(entry('a'), entry('b', version = 2)))
        val linkId = vault.linkId
        assertTrue(linkId in 1u..254u)
        vault.lock()

        val reopened = Vault(store, testCrypto)
        assertTrue(reopened.exists() && !reopened.isUnlocked())
        reopened.unlock("1234")

        assertEquals(listOf(droneId('a'), droneId('b')), reopened.list().map { it.droneId })
        assertEquals(2, reopened.list()[1].keyVersion)
        assertContentEquals(entry('a').key, reopened.keyFor(droneId('a')))
        assertEquals(linkId, reopened.linkId, "the link_id is chosen once and stored")
    }

    /** Screens re-read the vault whenever [Vault.revision] moves, so every change of what they would show must move it. */
    @Test
    fun revisionMovesOnEveryChangeIncludingALockByTheTimer() = runTest {
        val vault = createdVault()
        var seen = vault.revision.value

        fun assertMoved(what: String) {
            assertTrue(vault.revision.value > seen, what)
            seen = vault.revision.value
        }
        vault.add(entry('a')); assertMoved("add")
        vault.remove(droneId('a')); assertMoved("remove")
        vault.lock(); assertMoved("lock")
        vault.unlock("1234"); assertMoved("unlock")

        vault.lock()
        seen = vault.revision.value
        vault.lock() // already locked: nothing changed
        assertEquals(seen, vault.revision.value)
        assertFailsWith<VaultException.WrongSecret> { vault.unlock("nope") }
        assertEquals(seen, vault.revision.value, "a failed unlock changes nothing")
    }

    @Test
    fun aWrongPinIsRefusedAndLeavesTheVaultLocked() = runTest {
        val store = MemoryStore()
        createdVault(store).lock()
        val vault = Vault(store, testCrypto)

        assertFailsWith<VaultException.WrongSecret> { vault.unlock("1235") }
        assertFalse(vault.isUnlocked())
    }

    @Test
    fun unlockingWithNoVaultFileSaysSo() = runTest {
        assertFailsWith<VaultException.NoVault> { Vault(MemoryStore(), testCrypto).unlock("1234") }
    }

    @Test
    fun createRefusesToOverwriteAnExistingVault() = runTest {
        val store = MemoryStore()
        createdVault(store)
        assertFailsWith<IllegalStateException> { Vault(store, testCrypto).create("9999") }
    }

    @Test
    fun anyChangeToTheSaltOrTheBodyFailsAuthentication() = runTest {
        val store = MemoryStore()
        createdVault(store).also { it.add(entry('a')) }.lock()
        val original = store.bytes!!

        // 20 = inside the salt (header AAD), 33 = nonce, 50 = ciphertext, last = GCM tag.
        for (index in listOf(20, 33, 50, original.lastIndex)) {
            val tampered = original.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            val vault = Vault(MemoryStore(tampered), testCrypto)
            assertFailsWith<VaultException.WrongSecret>("byte $index") { vault.unlock("1234") }
            assertFalse(vault.isUnlocked())
        }
    }

    @Test
    fun aDoctoredHeaderIsRefusedBeforeArgon2RunsWithItsCostFields() = runTest {
        val store = MemoryStore()
        createdVault(store).lock()
        val original = store.bytes!!

        // 0 = magic, 4 = version, 5..16 = memory/iterations/parallelism: a hostile file must not choose our memory use.
        for (index in listOf(0, 4, 5, 8, 9, 13)) {
            val tampered = original.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<VaultException.Corrupt>("byte $index") { Vault(MemoryStore(tampered), testCrypto).unlock("1234") }
        }
        assertFailsWith<VaultException.Corrupt> { Vault(MemoryStore(original.copyOf(40)), testCrypto).unlock("1234") }
    }

    @Test
    fun lockOverwritesKeyBytesEvenInReferencesAlreadyHandedOut() = runTest {
        val vault = createdVault()
        vault.add(entry('a'))
        val held = vault.keyFor(droneId('a'))!!
        assertTrue(held.any { it != 0.toByte() })

        vault.lock()

        assertTrue(held.all { it == 0.toByte() }, "a stale reference must not keep a usable key")
        assertNull(vault.keyFor(droneId('a')))
        assertTrue(vault.candidates().isEmpty() && vault.list().isEmpty())
        assertEquals(0.toUByte(), vault.linkId)
        assertFailsWith<VaultException.Locked> { vault.add(entry('b')) }
    }

    @Test
    fun theVaultKeepsItsOwnCopyOfAnAddedKey() = runTest {
        val vault = createdVault()
        val mine = entry('a')
        vault.add(mine)
        mine.key.fill(0) // what BundleImport does with its decoded keys

        assertContentEquals(entry('a').key, vault.keyFor(droneId('a')))
    }

    @Test
    fun aLowerKeyVersionNeverReplacesANewerOne() = runTest {
        val vault = createdVault()
        vault.add(entry('a', version = 2, fill = 2))

        assertEquals(listOf(droneId('a')), vault.add(entry('a', version = 1, fill = 1)))
        assertEquals(2.toByte(), vault.keyFor(droneId('a'))!![0])

        assertEquals(emptyList(), vault.add(entry('a', version = 3, fill = 3)))
        assertEquals(3.toByte(), vault.keyFor(droneId('a'))!![0])
        assertEquals(1, vault.list().size)
    }

    @Test
    fun removedKeysAreGoneFromTheFileAndZeroed() = runTest {
        val store = MemoryStore()
        val vault = createdVault(store)
        vault.addAll(listOf(entry('a'), entry('b')))
        val held = vault.keyFor(droneId('a'))!!

        vault.remove(droneId('a'))

        assertTrue(held.all { it == 0.toByte() })
        vault.lock()
        val reopened = Vault(store, testCrypto).also { it.unlock("1234") }
        assertEquals(listOf(droneId('b')), reopened.list().map { it.droneId })
    }

    @Test
    fun badEntriesAreRefusedBeforeTheyReachTheFile() = runTest {
        val store = MemoryStore()
        val vault = createdVault(store)
        val writes = store.writes
        val shortKey = VaultEntry(entry('a').info, ByteArray(31))
        val badId = VaultEntry(entry('a').info.copy(droneId = "xyz"), ByteArray(32))

        assertFailsWith<VaultException.Corrupt> { vault.add(shortKey) }
        assertFailsWith<VaultException.Corrupt> { vault.add(badId) }
        assertEquals(writes, store.writes)
    }

    @Test
    fun theKeyringShowsTheVaultAndKeepsTimestampsWhileLocked() = runTest {
        val vault = createdVault()
        vault.add(entry('a'))
        val timestamps = TimestampFile(MemoryStore(), nowMs = { 0 })
        val keyring = VaultKeyring(vault, timestamps)

        assertTrue(keyring.isUnlocked())
        assertEquals(vault.linkId, keyring.linkId)
        assertEquals(listOf(droneId('a')), keyring.candidates().map { it.first })
        assertNotNull(keyring.keyFor(droneId('a')))
        keyring.saveTimestamp(droneId('a'), 42)

        vault.lock()

        assertFalse(keyring.isUnlocked())
        assertNull(keyring.keyFor(droneId('a')))
        assertEquals(42L, keyring.loadTimestamp(droneId('a')))
    }

    @Test
    fun aCrashBetweenWritingAndRenamingLeavesTheOldVaultUsable() = runTest {
        val dir = Files.createTempDirectory("vault-crash").toFile()
        val file = File(dir, "signing.vnks")
        var crash = false
        val store = FileByteStore(file) { tmp, target ->
            if (crash) throw IOException("power cut after the temp file was written")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        val vault = Vault(store, testCrypto).also { it.create("1234"); it.add(entry('a')) }

        crash = true
        assertFailsWith<IOException> { vault.add(entry('b')) }

        assertEquals(listOf(droneId('a')), vault.list().map { it.droneId }, "memory must not run ahead of the disk")
        assertNull(vault.keyFor(droneId('b')))
        assertTrue(File(file.path + ".tmp").exists(), "the crash left the temp file behind")
        val afterRestart = Vault(FileByteStore(file), testCrypto).also { it.unlock("1234") }
        assertEquals(listOf(droneId('a')), afterRestart.list().map { it.droneId }, "the old vault is intact on disk")

        crash = false
        vault.add(entry('b'))
        assertFalse(File(file.path + ".tmp").exists())
        Vault(FileByteStore(file), testCrypto).also { it.unlock("1234") }.let { assertEquals(2, it.list().size) }
        dir.deleteRecursively()
    }
}
