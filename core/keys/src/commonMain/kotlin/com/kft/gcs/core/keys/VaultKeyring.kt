package com.kft.gcs.core.keys

import com.kft.gcs.core.mavlink.signing.SigningKeyring

/**
 * The signing layer's view of the [Vault] (`SigningKeyring` lives in `core:mavlink` so the byte layer never depends on
 * this module). Keys come from the vault and so vanish when it locks; timestamps come from the plain [TimestampFile],
 * which stays readable while locked because it holds nothing secret.
 */
class VaultKeyring(private val vault: Vault, private val timestamps: TimestampFile) : SigningKeyring {
    override val linkId: UByte get() = vault.linkId
    override fun isUnlocked() = vault.isUnlocked()
    override fun candidates() = vault.candidates()
    override fun keyFor(droneId: String) = vault.keyFor(droneId)
    override fun loadTimestamp(droneId: String) = timestamps.load(droneId)
    override fun saveTimestamp(droneId: String, ts: Long) = timestamps.save(droneId, ts)

    /** Writes any timestamp the 10-second limit held back: call on disconnect and app exit. */
    fun flushTimestamps() = timestamps.flush()
}
