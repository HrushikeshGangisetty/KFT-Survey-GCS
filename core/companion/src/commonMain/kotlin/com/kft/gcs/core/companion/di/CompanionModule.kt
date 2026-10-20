package com.kft.gcs.core.companion.di

import com.kft.gcs.core.companion.CompanionRepository
import org.koin.dsl.module

/** One companion link for the app, on the app scope. The shells provide the [com.kft.gcs.core.companion.CompanionConfigStore] file. */
val companionModule = module {
    single { CompanionRepository(get(), getOrNull()) }
}
