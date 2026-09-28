package com.kft.gcs.feature.fly.di

import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.feature.fly.FlyViewModel
import com.kft.gcs.feature.fly.VideoViewModel
import com.kft.gcs.ui.video.ElementaryStreamSource
import com.kft.gcs.ui.map.OfflineMaps
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Fly bindings. The basemap list comes from [OfflineMaps]: the built-in maps plus imported MBTiles files. The shells
 * provide the [com.kft.gcs.feature.fly.VideoStore] file.
 */
val flyModule = module {
    viewModel { FlyViewModel(get(), get<OfflineMaps>().basemaps, get()) }
    // The T12's video comes from the one T12Link, which also serves a MAVLink link over the same USB port.
    viewModel { VideoViewModel(get(), get<T12Link>().let { ElementaryStreamSource("t12", it.video, it.problem) }) }
}
