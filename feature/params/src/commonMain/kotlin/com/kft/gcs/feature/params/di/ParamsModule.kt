package com.kft.gcs.feature.params.di

import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.feature.params.ParamsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Params bindings. The app provides the [com.kft.gcs.feature.params.ParamFiles] (the platform's file dialogs) and the
 * [com.kft.gcs.feature.params.MetadataSource] (HTTP and a folder for the downloaded descriptions).
 */
val paramsModule = module {
    viewModel { ParamsViewModel(get<VehicleRepository>().state, get(), get(), get()) }
}
