package com.kft.gcs.app

import com.kft.gcs.core.keys.BundleImport
import com.kft.gcs.core.keys.ByteStore
import com.kft.gcs.core.keys.DeviceWrap
import com.kft.gcs.core.keys.FileByteStore
import com.kft.gcs.core.keys.JvmVaultCrypto
import com.kft.gcs.core.keys.TimestampFile
import com.kft.gcs.core.keys.Vault
import com.kft.gcs.core.keys.VaultCrypto
import com.kft.gcs.core.keys.VaultKeyring
import com.kft.gcs.core.keys.WrappedByteStore
import com.kft.gcs.core.mavlink.di.IoDispatcher
import com.kft.gcs.core.mavlink.signing.SigningKeyring
import com.kft.gcs.feature.settings.BundlePicker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.dsl.module

/**
 * The signing-key vault bindings, shared by both shells: the encrypted [vaultFile] (`signing.vnks`), the plain
 * [timestampFile] (`signing_ts.json`), and the [SigningKeyring] that `core:mavlink` picks up with `getOrNull`. The shell
 * chooses the folder; Android also passes its Keystore [deviceWrap] as the file's second layer.
 *
 * With no vault created yet the keyring is locked and empty, so every vehicle stays unsigned exactly as before.
 */
fun signingModule(vaultFile: File, timestampFile: File, deviceWrap: DeviceWrap? = null) = module {
    single<VaultCrypto> { JvmVaultCrypto() }
    single {
        val file: ByteStore = FileByteStore(vaultFile)
        Vault(if (deviceWrap == null) file else WrappedByteStore(file, deviceWrap), get(), get(IoDispatcher))
    }
    single { TimestampFile(FileByteStore(timestampFile), nowMs = System::currentTimeMillis) }
    single<SigningKeyring> { VaultKeyring(get(), get()) }
    single { BundleImport(get(), get(), get(IoDispatcher)) }
}

/**
 * A [BundlePicker] over a shell's existing "pick a file and copy it into this folder" dialog: the copy is read into
 * memory and deleted at once, so the bundle (which is encrypted anyway) never lingers on disk.
 */
fun bundlePicker(pickAndCopy: suspend () -> String?): BundlePicker = BundlePicker {
    pickAndCopy()?.let { path ->
        val copy = File(path)
        try {
            withContext(Dispatchers.IO) { copy.readBytes() }
        } finally {
            copy.delete()
        }
    }
}
