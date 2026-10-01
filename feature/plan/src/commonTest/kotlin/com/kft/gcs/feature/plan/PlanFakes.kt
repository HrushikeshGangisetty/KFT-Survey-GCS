package com.kft.gcs.feature.plan

import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionSync
import com.kft.gcs.core.vehicle.MissionTransferException
import kotlinx.coroutines.CompletableDeferred

// Fakes shared by the ViewModel tests (commonTest) and the Compose UI tests (jvmTest).

/** The one vehicle of the Plan tests. */
internal val TEST_VEHICLE = VehicleId(link = 1, systemId = 1u)

/**
 * A MissionRepository that records uploads and can hold a transfer open or fail it. Like the app's (`Fleet.missions`),
 * it notes in [sync] what the vehicle holds after each successful transfer.
 */
internal class FakeMissions(private val sync: MissionSync? = null) : MissionRepository {
    var uploaded: List<MissionItem> = emptyList()
    var onVehicle = Mission(null, emptyList())
    var hold: CompletableDeferred<Unit>? = null
    var failWith: String? = null

    override suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit): Result<Unit> {
        failWith?.let { return Result.failure(MissionTransferException(it)) }
        onProgress(2, items.size + 1)
        hold?.await()
        uploaded = items
        sync?.vehicleHolds(TEST_VEHICLE, items)
        return Result.success(Unit)
    }

    override suspend fun download(onProgress: (Int, Int) -> Unit) = Result.success(onVehicle).also { sync?.vehicleHolds(TEST_VEHICLE, onVehicle.items) }

    override suspend fun clear() = Result.success(Unit).also { sync?.vehicleHolds(TEST_VEHICLE, emptyList()) }
}

internal class FakeFiles : PlanFiles {
    var saved: Pair<String, String>? = null
    var toOpen: OpenedFile? = null
    var toImport: ImportFile? = null
    override suspend fun save(suggestedName: String, text: String): String { saved = suggestedName to text; return suggestedName }
    override suspend fun open() = toOpen
    override suspend fun openForImport() = toImport
}

internal class MemoryStore : SettingsStore {
    private var text: String? = null
    override fun read() = text
    override fun write(text: String) { this.text = text }
}
