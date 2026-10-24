package com.kft.gcs.core.keys

import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * [VaultCrypto] for both targets: AES-GCM from the platform provider (JDK on desktop, Conscrypt on Android) and Argon2id
 * from BouncyCastle's lightweight API, which needs no JCA provider registration and so cannot clash with Android's own.
 */
class JvmVaultCrypto(private val random: SecureRandom = SecureRandom()) : VaultCrypto {

    override fun argon2id(secret: ByteArray, salt: ByteArray, params: KdfParams): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .build()
        return ByteArray(KEY_SIZE).also { Argon2BytesGenerator().apply { init(parameters) }.generateBytes(secret, it) }
    }

    override fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        cipher(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plaintext)

    override fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? =
        try {
            cipher(Cipher.DECRYPT_MODE, key, nonce, aad).doFinal(sealed)
        } catch (e: AEADBadTagException) {
            null
        }

    override fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }

    private companion object {
        const val KEY_SIZE = 32
        const val TAG_BITS = 128
    }
}
