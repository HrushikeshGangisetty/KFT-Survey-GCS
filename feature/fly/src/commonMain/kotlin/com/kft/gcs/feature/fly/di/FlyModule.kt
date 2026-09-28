package com.kft.gcs.feature.fly.di

import com.kft.gcs.feature.fly.FlyViewModel
import com.kft.gcs.ui.map.OfflineMaps
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Fly bindings. The basemap list comes from [OfflineMaps]: the built-in maps plus imported MBTiles files. */
val flyModule = module {
    viewModel { FlyViewModel(get(), get<OfflineMaps>().basemaps, get()) }
}
