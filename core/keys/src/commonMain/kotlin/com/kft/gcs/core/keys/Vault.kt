@file:OptIn(ExperimentalAtomicApi::class, ExperimentalEncodingApi::class)

package com.kft.gcs.core.keys

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the keys screen may show about a key. Never the key itself. */
data class KeyInfo(
    /** The FC's STM32 UID as 24 lower-case hex characters: the identity of a drone everywhere in software (PRD). */
    val droneId: String,
    val keyVersion: Int,
    val label: String,
    /** The MAVLink system id last seen for this drone, if any: a hint only, since SYSID_THISMAV is a routing label. */
    val sysidHint: Int?,
    /** ISO-8601 time the key was added, as written by the provisioning tool. */
    val addedAt: String,
)

/** A [KeyInfo] with its 32-byte key. The vault copies [key] when it takes the entry, so the caller zeroises its own. */
class VaultEntry(val info: KeyInfo, val key: ByteArray)

/** One entry as it appears in the JSON of both the vault and a bundle (PRD "Keystore file format"). */
@Serializable
internal class EntryJson(
    @SerialName("drone_id") val droneId: String,
    @SerialName("key_b64") val keyB64: String,
    @SerialName("key_version") val keyVersion: Int,
    val label: String,
    @SerialName("sysid_hint") val sysidHint: Int? = null,
    @SerialName("added_at") val addedAt: String,
)

/** The vault's JSON: the entries plus this installation's link_id, which lives inside the encrypted body. */
@Serializable
private class VaultBody(@SerialName("link_id") val linkId: Int, val entries: List<EntryJson>)

internal val vaultJson = Json { ignoreUnknownKeys = true }

private const val KEY_SIZE = 32
private val DRONE_ID = Regex("[0-9a-f]{24}")

/** Same limits as the provisioning tool, so nothing the vault stores can be refused later by the other side. */
private fun VaultEntry.validate() {
    if (!DRONE_ID.matches(info.droneId)) throw VaultException.Corrupt("drone_id must be 24 lower-case hex characters")
    if (key.size != KEY_SIZE) throw VaultException.Corrupt("key must be 32 bytes")
    if (info.keyVersion < 1) throw VaultException.Corrupt("key_version must be 1 or more")
    if (info.label.length !in 1..128) throw VaultException.Corrupt("label must be 1..128 characters")
}

internal fun EntryJson.toEntry(): VaultEntry {
    val key = runCatching { Base64.decode(keyB64) }.getOrNull() ?: throw VaultException.Corrupt("key_b64 is not base64")
    return VaultEntry(KeyInfo(droneId.lowercase(), keyVersion, label, sysidHint, addedAt), key).also(VaultEntry::validate)
}

internal fun VaultEntry.toJson() =
    EntryJson(info.droneId, Base64.encode(key), info.keyVersion, info.label, info.sysidHint, info.addedAt)

/** Everything held while unlocked. [key] is the Argon2 output, kept so a save needs no PIN; [zeroise] wipes all of it. */
private class Unlocked(val key: ByteArray, val salt: ByteArray, val linkId: UByte, val entries: List<VaultEntry>) {
    fun zeroise() {
        key.fill(0)
        entries.forEach { it.key.fill(0) }
    }
}

/**
 * The encrypted signing-key store (PRD "Key management"): create it with a PIN, unlock it, add and remove keys, lock it.
 *
 * **Why the key stays in memory while unlocked:** every save needs the AES key, and asking for the PIN on each edit would
 * be tedious; so we keep the Argon2 output (not the PIN) until [lock], which overwrites it and every drone key with zeros.
 * MAVLink signing hashes `key ‖ frame`, so the drone keys must be in RAM anyway while a vehicle is connected.
 *
 * Reads ([isUnlocked], [keyFor], …) are plain and thread-safe: they look at one atomically swapped snapshot. Changes
 * ([create], [unlock], [addAll], [remove]) take a mutex and run Argon2 / file I/O on [dispatcher].
 */
class Vault(
    private val store: ByteStore,
    private val crypto: VaultCrypto,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val state = AtomicReference<Unlocked?>(null)
    private val mutex = Mutex()
    private val _revision = MutableStateFlow(0)

    /**
     * Goes up every time what [isUnlocked] or [list] would answer may have changed (create, unlock, lock, add, remove,
     * including a lock by the idle timer). Screens collect it and re-read, so they never poll the vault.
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun exists(): Boolean = store.read() != null

    fun isUnlocked(): Boolean = state.load() != null

    /** This installation's `link_id` (1..254), or 0 while locked. Stable for the life of the vault file. */
    val linkId: UByte get() = state.load()?.linkId ?: 0u

    /** The keys' details without the key bytes; empty while locked. */
    fun list(): List<KeyInfo> = state.load()?.entries?.map { it.info }.orEmpty()

    /**
     * The key bytes for [droneId], or null if absent or locked. This is the vault's own array, not a copy: [lock] zeroises
     * it, which is deliberate, so a stale reference held by the signing layer goes dead instead of leaking. Callers must
     * not modify it.
     */
    fun keyFor(droneId: String): ByteArray? = state.load()?.entries?.firstOrNull { it.info.droneId == droneId }?.key

    /** Every (droneId, key) pair; same ownership rules as [keyFor]. Empty while locked. */
    fun candidates(): List<Pair<String, ByteArray>> = state.load()?.entries?.map { it.info.droneId to it.key }.orEmpty()

    /** Makes a new vault protected by [pin] and leaves it unlocked. Refuses to overwrite an existing one. */
    suspend fun create(pin: String) {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        mutex.withLock {
            check(!exists()) { "A vault already exists; wipe it first" }
            withContext(dispatcher) {
                val salt = Envelope.newSalt(crypto)
                val key = deriveKey(pin, salt)
                // 1..254: 0 is "no link" and 255 is reserved in some MAVLink tooling. The modulo bias is irrelevant for an id.
                val linkId = (crypto.randomBytes(1)[0].toInt() and 0xFF) % 254 + 1
                val fresh = Unlocked(key, salt, linkId.toUByte(), emptyList())
                store.write(encode(fresh, fresh.entries))
                state.exchange(fresh)?.zeroise()
                changed()
            }
        }
    }

    /** Opens the vault. Throws [VaultException.WrongSecret] for a wrong PIN, [VaultException.NoVault] / `Corrupt` otherwise. */
    suspend fun unlock(pin: String) {
        mutex.withLock {
            withContext(dispatcher) {
                val data = store.read() ?: throw VaultException.NoVault()
                val secret = pin.encodeToByteArray()
                val opened = try {
                    Envelope.open(MAGIC, data, secret, crypto)
                } finally {
                    secret.fill(0)
                }
                val body = try {
                    vaultJson.decodeFromString<VaultBody>(opened.plaintext.decodeToString())
                } catch (e: Exception) {
                    throw VaultException.Corrupt("bad JSON")
                } finally {
                    opened.plaintext.fill(0)
                }
                val linkId = body.linkId.takeIf { it in 1..254 } ?: throw VaultException.Corrupt("link_id out of range")
                val entries = body.entries.map { it.toEntry() }
                state.exchange(Unlocked(opened.key, opened.salt, linkId.toUByte(), entries))?.zeroise()
                changed()
            }
        }
    }

    /** Overwrites every key in memory with zeros and forgets the AES key. Safe to call at any time, from any thread. */
    fun lock() {
        state.exchange(null)?.let {
            it.zeroise()
            changed()
        }
    }

    /**
     * Adds [entries], replacing a stored key for the same drone only if the new `key_version` is not lower (a stale bundle
     * must not roll a rekeyed drone back). Returns the drone ids that were skipped for that reason. One file write in total.
     */
    suspend fun addAll(entries: List<VaultEntry>): List<String> = mutex.withLock {
        val current = state.load() ?: throw VaultException.Locked()
        val copies = entries.map { VaultEntry(it.info.copy(droneId = it.info.droneId.lowercase()), it.key.copyOf()).also(VaultEntry::validate) }
        val skipped = mutableListOf<String>()
        var merged = current.entries
        for (new in copies) {
            val old = merged.firstOrNull { it.info.droneId == new.info.droneId }
            if (old != null && old.info.keyVersion > new.info.keyVersion) {
                skipped += new.info.droneId
                new.key.fill(0)
            } else {
                merged = merged.filter { it !== old } + new
            }
        }
        commit(current, merged, created = copies.filter { it in merged })
        skipped
    }

    suspend fun add(entry: VaultEntry): List<String> = addAll(listOf(entry))

    /** Removes the key for [droneId] (no-op if absent). */
    suspend fun remove(droneId: String) {
        mutex.withLock {
            val current = state.load() ?: throw VaultException.Locked()
            val kept = current.entries.filter { it.info.droneId != droneId }
            if (kept.size != current.entries.size) commit(current, kept, created = emptyList())
        }
    }

    /**
     * Writes [merged] to disk, then publishes it. Order matters: if the write throws, nothing in memory changed. If [lock]
     * ran meanwhile the swap fails, and the keys we just made ([created]) are wiped (the older ones already were).
     */
    private suspend fun commit(current: Unlocked, merged: List<VaultEntry>, created: List<VaultEntry>) {
        val next = Unlocked(current.key, current.salt, current.linkId, merged)
        try {
            withContext(dispatcher) { store.write(encode(next, merged)) }
        } catch (e: Throwable) {
            created.forEach { it.key.fill(0) }
            throw e
        }
        if (state.compareAndSet(current, next)) {
            current.entries.filter { it !in merged }.forEach { it.key.fill(0) } // replaced or removed keys are retired
            changed()
        } else {
            created.forEach { it.key.fill(0) }
            throw VaultException.Locked()
        }
    }

    private fun changed() = _revision.update { it + 1 }

    private fun encode(unlocked: Unlocked, entries: List<VaultEntry>): ByteArray {
        val json = vaultJson.encodeToString(VaultBody(unlocked.linkId.toInt(), entries.map { it.toJson() })).encodeToByteArray()
        return try {
            Envelope.seal(MAGIC, unlocked.key, unlocked.salt, json, crypto)
        } finally {
            json.fill(0)
        }
    }

    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val secret = pin.encodeToByteArray()
        return try {
            crypto.argon2id(secret, salt, KdfParams.V1)
        } finally {
            secret.fill(0)
        }
    }

    private companion object {
        const val MAGIC = "VNKS"
    }
}
