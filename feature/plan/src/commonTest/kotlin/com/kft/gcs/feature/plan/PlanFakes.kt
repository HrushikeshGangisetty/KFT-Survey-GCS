package com.kft.gcs.feature.plan

import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.core.mission.Mission
import com.kft.gcs.core.mission.MissionItem
import com.kft.gcs.core.vehicle.MissionRepository
import com.kft.gcs.core.vehicle.MissionTransferException
import kotlinx.coroutines.CompletableDeferred

// Fakes shared by the ViewModel tests (commonTest) and the Compose UI tests (jvmTest).

/** A MissionRepository that records uploads and can hold a transfer open or fail it. */
internal class FakeMissions : MissionRepository {
    var uploaded: List<MissionItem> = emptyList()
    var onVehicle = Mission(null, emptyList())
    var hold: CompletableDeferred<Unit>? = null
    var failWith: String? = null

    override suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit): Result<Unit> {
        failWith?.let { return Result.failure(MissionTransferException(it)) }
        onProgress(2, items.size + 1)
        hold?.await()
        uploaded = items
        return Result.success(Unit)
    }

    override suspend fun download(onProgress: (Int, Int) -> Unit) = Result.success(onVehicle)

    override suspend fun clear() = Result.success(Unit)
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
