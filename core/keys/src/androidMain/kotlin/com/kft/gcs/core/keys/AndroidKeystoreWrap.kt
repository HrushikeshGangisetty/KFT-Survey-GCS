package com.kft.gcs.core.keys

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The vault file's second layer on Android (PRD "Where each key lives"): the whole file is encrypted with an AES-256-GCM
 * key that lives in the Android Keystore and never leaves it (in secure hardware where the device has any). The PIN layer
 * inside is untouched, so a copied file is useless without this device, and this device's file is useless without the PIN.
 *
 * Layout: `version(1) | iv(12) | ciphertext + tag`. If the Keystore key is gone (app data restored on another phone,
 * or the lock screen was removed on some devices), [unwrap] throws [VaultException.Corrupt] and the vault must be
 * recreated from bundles, which is the PRD's "operator forgets PIN" path.
 *
 * Not covered by unit tests: the Keystore exists only on a device or emulator. Check it by hand (pass summary).
 */
class AndroidKeystoreWrap(private val alias: String = "kft-signing-vault") : DeviceWrap {

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) } // Keystore picks a fresh IV
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size < 1 + IV_SIZE + TAG_SIZE || wrapped[0] != VERSION) throw VaultException.Corrupt("not a device-wrapped vault")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_SIZE * 8, wrapped, 1, IV_SIZE))
            }
            cipher.doFinal(wrapped, 1 + IV_SIZE, wrapped.size - 1 - IV_SIZE)
        } catch (e: GeneralSecurityException) {
            throw VaultException.Corrupt("the device key cannot open this vault")
        }
    }

    /** The Keystore key, created on first use. Not exportable, so the raw key bytes can never be read back. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val VERSION: Byte = 1
        const val IV_SIZE = 12
        const val TAG_SIZE = 16
    }
}
