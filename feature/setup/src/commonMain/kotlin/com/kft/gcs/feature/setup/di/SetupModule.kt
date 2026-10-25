package com.kft.gcs.feature.setup.di

import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.di.SelectedVehicle
import com.kft.gcs.feature.setup.CalibrationViewModel
import com.kft.gcs.feature.setup.PreArmViewModel
import com.kft.gcs.feature.setup.SetupViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Setup bindings: every ViewModel acts on the selected vehicle's state, parameters and setup commands (core:vehicle). */
val setupModule = module {
    viewModel { SetupViewModel(get(SelectedVehicle), get(), get<Fleet>().selectedId) }
    viewModel { CalibrationViewModel(get(SelectedVehicle), get(), get(), get<Fleet>().selectedId) }
    viewModel { PreArmViewModel(get(SelectedVehicle), get()) }
}
