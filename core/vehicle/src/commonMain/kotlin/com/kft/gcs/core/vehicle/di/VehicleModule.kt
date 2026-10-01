package com.kft.gcs.core.vehicle.di

import com.kft.gcs.core.mavlink.Links
import com.kft.gcs.core.mavlink.di.IoDispatcher
import com.kft.gcs.core.vehicle.MemoryRecordStore
import com.kft.gcs.core.vehicle.MissionRecords
import com.kft.gcs.core.vehicle.RecordStore
import kotlinx.coroutines.CoroutineDispatcher
import com.kft.gcs.core.vehicle.Fleet
import com.kft.gcs.core.vehicle.KFT_APP_SECRET_HEX
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.parseKftKey
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Koin qualifier for the selected vehicle's `StateFlow<VehicleState>`, which Plan, Params and Links read. */
val SelectedVehicle = named("selectedVehicle")

/**
 * `core:vehicle` bindings: the one [Fleet] over every open link, and the selected vehicle's state, [MissionRepository]
 * and [ParamRepository] taken from it, so screens that act on "the vehicle" get the selected one (ADR-005 §5).
 * The app shell may provide a [com.kft.gcs.core.mavlink.VehicleRecorder] (the tlog files); without one nothing is recorded.
 */
/** The device's clock. The shells bind the system clock; common code has none of its own. */
interface WallClock {
    /** Milliseconds since 1970. */
    fun nowMs(): Long

    /** The local time of day as text, "14:03:22". */
    fun timeOfDay(): String
}

val vehicleModule = module {
    // One for the app: Plan writes the plan into it, the fleet what each vehicle holds, Fly reads it.
    single { MissionSync() }
    single {
        Fleet(
            scope = get(), links = get<Links>().open,
            // Generated at build time from KFT_APP_SECRET (core/vehicle/build.gradle.kts). Invalid or empty → null → NO_KEY.
            loginKey = parseKftKey(KFT_APP_SECRET_HEX),
            sync = get(),
            recorder = getOrNull(),
        )
    }
    // Progress records. The shells provide the folder (a RecordStore) and the clock; without them, memory only.
    single {
        MissionRecords(get(), get<Fleet>().vehicles, getOrNull<RecordStore>() ?: MemoryRecordStore(), getOrNull<WallClock>()?.let { it::nowMs } ?: { 0L }, get<CoroutineDispatcher>(IoDispatcher))
    }
    single<StateFlow<VehicleState>>(SelectedVehicle) { get<Fleet>().selected }
    single<MissionRepository> { get<Fleet>().missions }
    single<ParamRepository> { get<Fleet>().params }
}

