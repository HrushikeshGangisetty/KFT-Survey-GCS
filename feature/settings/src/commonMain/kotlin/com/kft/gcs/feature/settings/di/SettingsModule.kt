package com.kft.gcs.feature.settings.di

import com.kft.gcs.feature.settings.OfflineMapsViewModel
import com.kft.gcs.feature.settings.SigningKeysRepository
import com.kft.gcs.feature.settings.SigningKeysViewModel
import com.kft.gcs.feature.settings.VaultSigningKeysRepository
import kotlin.time.TimeSource
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Maps-tab and signing-keys bindings. The app shell provides the [com.kft.gcs.ui.map.OfflineMaps] (it picks the maps
 * folder), the [com.kft.gcs.feature.settings.MbtilesPicker] and [com.kft.gcs.feature.settings.BundlePicker] (the
 * platform's file dialogs), and the `core:keys` vault and its keyring (`signingModule`).
 */
val settingsModule = module {
    viewModel { OfflineMapsViewModel(get(), get()) }
    single<SigningKeysRepository> {
        // Monotonic, so a clock change can neither lock the vault early nor keep it open.
        val origin = TimeSource.Monotonic.markNow()
        VaultSigningKeysRepository(get(), get(), get(), get(), get(), nowMs = { origin.elapsedNow().inWholeMilliseconds })
    }
    viewModel { SigningKeysViewModel(get(), get()) }
}
