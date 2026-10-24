package com.kft.gcs.core.keys

/** A [ByteStore] in memory that counts writes, so tests can see when the disk would have been touched. */
class MemoryStore(var bytes: ByteArray? = null) : ByteStore {
    var writes = 0
    override fun read() = bytes?.copyOf()
    override fun write(bytes: ByteArray) {
        this.bytes = bytes.copyOf()
        writes++
    }
}

val testCrypto = JvmVaultCrypto()

fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** A 24-hex-character drone id made of [c] (c must be a hex digit). */
fun droneId(c: Char) = c.toString().repeat(24)

fun entry(c: Char, version: Int = 1, fill: Int = c.code) =
    VaultEntry(KeyInfo(droneId(c), version, "drone $c", null, "2026-10-24T00:00:00+00:00"), ByteArray(32) { fill.toByte() })

/** A vault on [store] that is already created (PIN "1234") and unlocked. */
suspend fun createdVault(store: ByteStore = MemoryStore()): Vault = Vault(store, testCrypto).also { it.create("1234") }
