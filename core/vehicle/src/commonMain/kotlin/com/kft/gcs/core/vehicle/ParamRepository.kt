package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Vehicle parameters for screens: plain [Param]s in and out. An interface so ViewModel tests can use a fake.
 * Both calls wait for the KFT login, like mission transfers, and suspend until the vehicle has answered.
 */
interface ParamRepository {
    /**
     * Every parameter as last read, by name: filled by [downloadAll] and kept current by [set]. Empty until the first
     * download. Screens that need a few values (Setup's failsafes, sensor ids) read them here instead of downloading again.
     */
    val known: StateFlow<Map<String, Param>>

    /** Reads every parameter, ordered by index. [onProgress] gets (received, total). */
    suspend fun downloadAll(onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<List<Param>>

    /** Writes [value] to [param] and reports what the vehicle now holds. Refused while armed (TX gateway). */
    suspend fun set(param: Param, value: Float): ParamSetResult
}

/** [ParamRepository] over the MAVLink parameter protocol, addressed to the vehicle currently on the link. */
class DefaultParamRepository(
    frames: Flow<MavFrame<out MavMessage<*>>>,
    private val link: StateFlow<LinkState>,
    private val vehicle: StateFlow<VehicleState>,
    sender: MavSender,
) : ParamRepository {
    private val protocol = ParamProtocol(frames, sender)

    private val _known = MutableStateFlow<Map<String, Param>>(emptyMap())
    override val known: StateFlow<Map<String, Param>> = _known.asStateFlow()

    override suspend fun downloadAll(onProgress: (Int, Int) -> Unit): Result<List<Param>> {
        val target = loggedInTarget(link.value, vehicle.value) ?: return Result.failure(ParamTransferException(notReady()))
        return protocol.download(target, onProgress).onSuccess { list -> _known.value = list.associateBy { it.name } }
    }

    override suspend fun set(param: Param, value: Float): ParamSetResult {
        val target = loggedInTarget(link.value, vehicle.value) ?: return ParamSetResult.NotApplied(notReady())
        return protocol.set(target, param, value).also { result ->
            // What the vehicle said it holds, whether or not the write took (a locked parameter echoes its old value).
            val now = (result as? ParamSetResult.Applied)?.param ?: (result as? ParamSetResult.NotApplied)?.vehicleValue
            if (now != null) _known.update { it + (now.name to now) }
        }
    }

    private fun notReady() = notReadyReason(link.value, vehicle.value, "Parameter transfers")
}
