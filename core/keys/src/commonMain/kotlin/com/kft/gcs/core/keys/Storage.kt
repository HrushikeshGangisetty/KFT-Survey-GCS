package com.kft.gcs.core.keys

/** One file of bytes. The vault and the timestamp file talk to disk only through this, so tests need no filesystem. */
interface ByteStore {
    /** The whole file, or null if it doesn't exist yet. */
    fun read(): ByteArray?

    /** Replaces the whole file. Must be all-or-nothing: a crash leaves the old bytes or the new ones, never a mix. */
    fun write(bytes: ByteArray)
}

/**
 * A second encryption layer applied to the whole vault file, outside the PIN layer. Android implements it with an
 * Android Keystore key (hardware-backed where the device has it); a Windows DPAPI version can plug in here later
 * without touching the file format. Unwrapping needs the device, wrapping alone never reveals the PIN layer.
 */
interface DeviceWrap {
    fun wrap(plain: ByteArray): ByteArray

    /** Throws [VaultException.Corrupt] if [wrapped] was not made by this device's key. */
    fun unwrap(wrapped: ByteArray): ByteArray
}

/** A [ByteStore] that wraps every write and unwraps every read with [wrap]. */
class WrappedByteStore(private val inner: ByteStore, private val wrap: DeviceWrap) : ByteStore {
    override fun read(): ByteArray? = inner.read()?.let(wrap::unwrap)
    override fun write(bytes: ByteArray) = inner.write(wrap.wrap(bytes))
}
