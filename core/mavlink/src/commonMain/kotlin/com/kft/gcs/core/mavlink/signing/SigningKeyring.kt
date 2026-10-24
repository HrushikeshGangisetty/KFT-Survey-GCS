package com.kft.gcs.core.mavlink.signing

/**
 * What the byte layer ([com.kft.gcs.core.mavlink.FrameSink], [SigningSource]) asks about signing keys. The real one is
 * the vault in `core:keys` (signing stage S4); tests use a fake. Keys are 32 bytes, one per drone (PRD scheme B).
 *
 * Implementations must be thread-safe: [SigningSource] calls from the link's reader thread while `FrameSink` calls from
 * whichever coroutine is sending.
 */
interface SigningKeyring {
    /** The `link_id` this installation stamps on every signed frame (PRD "Identity on the wire"). */
    val linkId: UByte

    /** False while the vault is locked: no key can be read, so signed traffic can be neither sent nor checked. */
    fun isUnlocked(): Boolean

    /** Every key this device holds, as (droneId, key), for binding an unknown vehicle by trial (PRD FR-7). */
    fun candidates(): List<Pair<String, ByteArray>>

    /** The key for [droneId], or null when it isn't held or the vault is locked. */
    fun keyFor(droneId: String): ByteArray?

    /** The last outgoing timestamp saved for [droneId] (10 µs ticks since 2015), or null if none was ever saved. */
    fun loadTimestamp(droneId: String): Long?

    /** Saves the outgoing timestamp for [droneId], so the next session starts above it (PRD FR-10). */
    fun saveTimestamp(droneId: String, ts: Long)
}

/**
 * No keys, never locked: the keyring until the vault exists (S4). With it every outgoing frame is unsigned, exactly as
 * before signing, and a vehicle that signs is shown as [SigningStatus.NoKey] with its signed frames dropped.
 */
object NoSigning : SigningKeyring {
    override val linkId: UByte = 0u
    override fun isUnlocked() = true
    override fun candidates() = emptyList<Pair<String, ByteArray>>()
    override fun keyFor(droneId: String): ByteArray? = null
    override fun loadTimestamp(droneId: String): Long? = null
    override fun saveTimestamp(droneId: String, ts: Long) = Unit
}
