package com.kft.gcs.core.mavlink.di

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.PodStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the dispatcher that runs blocking I/O. `app:shared` binds it; tests never need Koin. */
val IoDispatcher = named("io")

/**
 * `core:mavlink` bindings. Expects `app:shared` to provide the application `CoroutineScope`, the [IoDispatcher], and
 * the platform's [com.kft.gcs.core.mavlink.SerialPorts] (each shell builds its own; Android's needs a Context).
 * The pod status is fixed at "no pod" until the pod link exists; the gateway already reads it, so wiring the real
 * pod later only replaces this one binding.
 */
val mavlinkModule = module {
    single { ConnectionManager(scope = get(), ioDispatcher = get(IoDispatcher), podStatus = MutableStateFlow(PodStatus.NoPod), serialPorts = get()) }
}
