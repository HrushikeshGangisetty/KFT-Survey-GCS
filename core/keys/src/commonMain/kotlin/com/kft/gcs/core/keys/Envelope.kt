package com.kft.gcs.core.keys

/** Why a vault or bundle could not be opened. */
sealed class VaultException(message: String) : Exception(message) {
    /** The secret is wrong, or the file was altered: AES-GCM cannot tell the two apart, and neither can we. */
    class WrongSecret : VaultException("Wrong PIN or passphrase, or the file was altered")

    /** Not a file we understand: wrong magic, version, size, cost parameters or JSON. */
    class Corrupt(detail: String) : VaultException("Unreadable key file: $detail")

    class Locked : VaultException("The key vault is locked")
    class NoVault : VaultException("No key vault exists yet")
}

/** The encrypted body plus what a vault needs to re-save it without asking for the PIN again. */
internal class Opened(val plaintext: ByteArray, val key: ByteArray, val salt: ByteArray)

/**
 * The file layout shared by the vault (`VNKS`), the provisioning bundle (`VNKB`) and the master file (`VNMS`), as written
 * by `tools/signing/provision.py` in GNSS-Denied:
 *
 * ```
 * magic[4] | version u8 | memory KiB u32 LE | iterations u32 LE | parallelism u32 LE | salt[16]   <- 33-byte header
 * nonce[12] | AES-256-GCM(JSON) ciphertext + 16-byte tag                                          <- header is the AAD
 * ```
 *
 * The header is the GCM associated data, so flipping any header byte makes the tag fail (or, for the cost fields, is
 * refused before any memory is spent).
 */
internal object Envelope {
    const val HEADER_SIZE = 33
    private const val NONCE_SIZE = 12
    private const val TAG_SIZE = 16
    private const val SALT_SIZE = 16
    private const val VERSION = 1

    /** Same cap as the Python tool: a hostile file can't make us read gigabytes. */
    private const val MAX_FILE = 4 * 1024 * 1024

    fun newSalt(crypto: VaultCrypto): ByteArray = crypto.randomBytes(SALT_SIZE)

    fun header(magic: String, salt: ByteArray): ByteArray {
        require(magic.length == 4 && salt.size == SALT_SIZE)
        val p = KdfParams.V1
        return magic.encodeToByteArray() + byteArrayOf(VERSION.toByte()) + p.memoryKiB.le() + p.iterations.le() + p.parallelism.le() + salt
    }

    /** [nonce] is random unless a test pins it to compare against the Python vector. */
    fun seal(
        magic: String, key: ByteArray, salt: ByteArray, plaintext: ByteArray, crypto: VaultCrypto,
        nonce: ByteArray = crypto.randomBytes(NONCE_SIZE),
    ): ByteArray {
        val header = header(magic, salt)
        return header + nonce + crypto.encrypt(key, nonce, header, plaintext)
    }

    fun open(magic: String, data: ByteArray, secret: ByteArray, crypto: VaultCrypto): Opened {
        if (data.size !in HEADER_SIZE + NONCE_SIZE + TAG_SIZE..MAX_FILE) throw VaultException.Corrupt("bad length")
        val expectedPrefix = header(magic, ByteArray(SALT_SIZE)).copyOf(HEADER_SIZE - SALT_SIZE)
        // Checked before Argon2 runs: a doctored header must not be able to choose how much memory we allocate.
        if (!data.copyOf(HEADER_SIZE - SALT_SIZE).contentEquals(expectedPrefix)) throw VaultException.Corrupt("unsupported header")
        val salt = data.copyOfRange(HEADER_SIZE - SALT_SIZE, HEADER_SIZE)
        val header = data.copyOf(HEADER_SIZE)
        val nonce = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + NONCE_SIZE)
        val key = crypto.argon2id(secret, salt, KdfParams.V1)
        val plaintext = crypto.decrypt(key, nonce, header, data.copyOfRange(HEADER_SIZE + NONCE_SIZE, data.size))
        if (plaintext == null) {
            key.fill(0)
            throw VaultException.WrongSecret()
        }
        return Opened(plaintext, key, salt)
    }

    private fun Int.le() = byteArrayOf(toByte(), (this shr 8).toByte(), (this shr 16).toByte(), (this shr 24).toByte())
}
