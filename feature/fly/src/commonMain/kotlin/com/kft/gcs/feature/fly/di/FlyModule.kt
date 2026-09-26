package com.kft.gcs.feature.fly.di

import com.kft.gcs.feature.fly.FlyViewModel
import com.kft.gcs.ui.map.TileSources
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Fly bindings. The basemap list is read here, once, from what this platform build supports. */
val flyModule = module {
    viewModel { FlyViewModel(get(), TileSources.available(), get()) }
}
