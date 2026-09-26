package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Vehicle parameters for screens: plain [Param]s in and out. An interface so ViewModel tests can use a fake.
 * Both calls wait for the KFT login, like mission transfers, and suspend until the vehicle has answered.
 */
interface ParamRepository {
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

    override suspend fun downloadAll(onProgress: (Int, Int) -> Unit): Result<List<Param>> {
        val target = loggedInTarget(link.value, vehicle.value) ?: return Result.failure(ParamTransferException(notReady()))
        return protocol.download(target, onProgress)
    }

    override suspend fun set(param: Param, value: Float): ParamSetResult {
        val target = loggedInTarget(link.value, vehicle.value) ?: return ParamSetResult.NotApplied(notReady())
        return protocol.set(target, param, value)
    }

    private fun notReady() = notReadyReason(link.value, vehicle.value, "Parameter transfers")
}
