package com.kft.gcs.core.vehicle.di

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.vehicle.DefaultMissionRepository
import com.kft.gcs.core.vehicle.KFT_APP_SECRET_HEX
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.VehicleRepository
import com.kft.gcs.core.vehicle.parseKftKey
import org.koin.dsl.module

/** `core:vehicle` bindings: one [VehicleRepository], [MissionRepository] and [MissionSync], fed by the app's one [ConnectionManager]. */
val vehicleModule = module {
    single {
        val manager: ConnectionManager = get()
        VehicleRepository(
            scope = get(), frames = manager.frames, link = manager.state, sender = manager.gateway,
            // Generated at build time from KFT_APP_SECRET (core/vehicle/build.gradle.kts). Invalid or empty → null → NO_KEY.
            loginKey = parseKftKey(KFT_APP_SECRET_HEX),
        )
    }
    single<MissionRepository> {
        val manager: ConnectionManager = get()
        DefaultMissionRepository(manager.frames, manager.state, get<VehicleRepository>().state, manager.gateway)
    }
    // One for the app: Plan writes the plan and transfer results into it, Fly reads it.
    single { MissionSync() }
}
