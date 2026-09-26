package com.kft.gcs.feature.params.di

import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.feature.params.ParamsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Params bindings. The app provides the [com.kft.gcs.feature.params.ParamFiles] (the platform's file dialogs). */
val paramsModule = module {
    viewModel { ParamsViewModel(get<VehicleRepository>().state, get(), get()) }
}
