package com.kft.gcs.core.mavlink.signing

/** A keyring with fixed [keys] (droneId to key) that a test can lock ([unlocked]) and whose saved timestamps it can read. */
class FakeKeyring(
    private val keys: Map<String, ByteArray>,
    override val linkId: UByte = 7u,
    var unlocked: Boolean = true,
) : SigningKeyring {
    val timestamps = HashMap<String, Long>()

    override fun isUnlocked() = unlocked
    override fun candidates() = if (unlocked) keys.toList() else emptyList()
    override fun keyFor(droneId: String) = if (unlocked) keys[droneId] else null
    override fun loadTimestamp(droneId: String) = timestamps[droneId]
    override fun saveTimestamp(droneId: String, ts: Long) {
        timestamps[droneId] = ts
    }
}
