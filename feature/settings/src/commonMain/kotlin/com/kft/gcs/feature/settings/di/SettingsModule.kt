package com.kft.gcs.feature.settings.di

import com.kft.gcs.feature.settings.OfflineMapsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Maps-tab bindings. The app shell provides the [com.kft.gcs.ui.map.OfflineMaps] (it picks the maps folder) and the
 * [com.kft.gcs.feature.settings.MbtilesPicker] (the platform's file dialog).
 */
val settingsModule = module {
    viewModel { OfflineMapsViewModel(get(), get()) }
}
