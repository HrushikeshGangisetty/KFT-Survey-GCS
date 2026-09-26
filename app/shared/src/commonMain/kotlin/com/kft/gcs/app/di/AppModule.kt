package com.kft.gcs.app.di

import com.kft.gcs.core.mavlink.di.IoDispatcher
import com.kft.gcs.core.mavlink.di.mavlinkModule
import com.kft.gcs.core.vehicle.di.vehicleModule
import com.kft.gcs.feature.connections.di.connectionsModule
import com.kft.gcs.feature.fly.di.flyModule
import com.kft.gcs.feature.params.ParamFiles
import com.kft.gcs.feature.params.di.paramsModule
import com.kft.gcs.feature.plan.PlanFiles
import com.kft.gcs.feature.plan.di.planModule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import org.koin.dsl.module

/**
 * App-wide things every module may ask for:
 * - the application [CoroutineScope] for long-lived work (the MAVLink link). SupervisorJob so one failed child
 *   (say, a telemetry parser) doesn't cancel the link.
 * - the [IoDispatcher] for blocking socket/serial I/O. Tests construct classes directly and pass their own.
 */
val appModule = module {
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single<CoroutineDispatcher>(IoDispatcher) { Dispatchers.IO }
    // .param files use the same platform dialogs as plan files. Params can't see PlanFiles (features never import
    // each other), so the app, which sees both, adapts one to the other.
    single<ParamFiles> {
        val dialogs = get<PlanFiles>()
        object : ParamFiles {
            override suspend fun save(suggestedName: String, text: String) = dialogs.save(suggestedName, text)
            override suspend fun open() = dialogs.open()?.let { it.name to it.text }
        }
    }
}

/** Every Koin module in the app, in one list, so both shells start the same graph. */
val allModules = listOf(appModule, mavlinkModule, vehicleModule, connectionsModule, flyModule, planModule, paramsModule)
