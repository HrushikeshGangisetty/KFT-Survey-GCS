package com.kft.gcs.feature.connections.di

import com.kft.gcs.feature.connections.ConnectionsRepository
import com.kft.gcs.feature.connections.ConnectionsViewModel
import com.kft.gcs.feature.connections.DefaultConnectionsRepository
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Connections bindings. One repository for the app, a fresh ViewModel per screen instance. The platform shell
 * provides the [com.kft.gcs.feature.connections.ProfileStore] (where the profile file lives is platform-specific).
 */
val connectionsModule = module {
    single<ConnectionsRepository> { DefaultConnectionsRepository(get(), get(), get()) }
    viewModelOf(::ConnectionsViewModel)
}
