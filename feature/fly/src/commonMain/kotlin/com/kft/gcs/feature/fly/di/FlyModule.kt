package com.kft.gcs.feature.fly.di

import com.kft.gcs.core.mavlink.T12Link
import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.di.WallClock
import com.kft.gcs.feature.fly.FleetViewModel
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
    viewModel { get<Fleet>().let { fleet -> FlyViewModel(fleet.vehicles, fleet.selectedId, fleet::select, get<OfflineMaps>().basemaps, get()) } }
    viewModel {
        val fleet = get<Fleet>()
        FleetViewModel(fleet.vehicles, fleet.selectedId, fleet::select, get<MissionRecords>().records, getOrNull<WallClock>()?.let { it::timeOfDay } ?: { "" })
    }
    // The T12's video comes from the one T12Link, which also serves a MAVLink link over the same USB port.
    viewModel { VideoViewModel(get(), get<T12Link>().let { ElementaryStreamSource("t12", it.video, it.problem) }) }
}
