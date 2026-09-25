package com.kft.gcs.core.vehicle.di

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.vehicle.VehicleRepository
import org.koin.dsl.module

/** `core:vehicle` bindings: one [VehicleRepository] fed by the app's one [ConnectionManager]. */
val vehicleModule = module {
    single {
        val manager: ConnectionManager = get()
        VehicleRepository(scope = get(), frames = manager.frames, link = manager.state, sender = manager.gateway)
    }
}
