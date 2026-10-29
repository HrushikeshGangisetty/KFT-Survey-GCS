package com.kft.gcs.core.terrain.di

import com.kft.gcs.core.terrain.Elevations
import com.kft.gcs.core.terrain.OpenMeteoElevations
import org.koin.dsl.module

/** One elevation source for the app, so its cache is shared. */
val terrainModule = module {
    single<Elevations> { OpenMeteoElevations() }
}
