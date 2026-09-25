package com.kft.gcs.feature.plan.di

import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.feature.plan.PlanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/** Plan bindings: the editor reads the live vehicle state and transfers through the MissionRepository. */
val planModule = module {
    viewModel { PlanViewModel(get<VehicleRepository>().state, get()) }
}
