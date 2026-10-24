package com.kft.gcs.core.keys

/** Argon2id cost, written into every file header so a later format version can raise it. */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** PRD "Keystore file format": 64 MiB, 3 passes, 1 lane. The only value accepted on read (see [Envelope.open]). */
        val V1 = KdfParams(memoryKiB = 65_536, iterations = 3, parallelism = 1)
    }
}

/**
 * The primitives the vault needs. One JVM implementation serves Android and desktop; the interface exists so the
 * format code stays free of `javax.*` and can be tested with scripted bytes.
 */
interface VaultCrypto {
    /** Argon2id (RFC 9106, version 0x13) of [secret] with [salt]; returns the 32-byte AES key. */
    fun argon2id(secret: ByteArray, salt: ByteArray, params: KdfParams): ByteArray

    /** AES-256-GCM; returns ciphertext followed by the 16-byte tag. [aad] is authenticated but not encrypted. */
    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** The plaintext, or null if the tag doesn't verify (wrong key, or [aad] or [sealed] was altered). */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray?

    fun randomBytes(size: Int): ByteArray
}
