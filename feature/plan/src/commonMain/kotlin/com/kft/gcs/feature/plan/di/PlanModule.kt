package com.kft.gcs.feature.plan.di

import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.feature.plan.PlanSettingsRepository
import com.kft.gcs.feature.plan.PlanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Plan bindings. The platform shell provides the [com.kft.gcs.feature.plan.SettingsStore] (where the settings file
 * lives) and the [com.kft.gcs.feature.plan.PlanFiles] (the platform's open/save dialogs).
 */
val planModule = module {
    single { PlanSettingsRepository(get()) }
    viewModel { PlanViewModel(get<VehicleRepository>().state, get(), get(), get(), get()) }
}
