package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.MavSender
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mission transfer for screens: plain [MissionItem]s in, plain [Mission]s out. Home is handled here (spec S11), so
 * a caller never builds seq 0 or sees it. An interface so ViewModel tests can use a fake.
 *
 * Every call suspends until the transfer ends. Cancelling the caller's coroutine cancels the transfer.
 * [onProgress] receives (done, total) where total includes home.
 */
interface MissionRepository {
    /** Replaces the vehicle's mission with [items], home first. Fails if no vehicle or home isn't known yet. */
    suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Unit>

    /** Reads the vehicle's mission. Home comes back separately, never as an item. */
    suspend fun download(onProgress: (Int, Int) -> Unit = { _, _ -> }): Result<Mission>

    /** Deletes the vehicle's mission. */
    suspend fun clear(): Result<Unit>
}

/** [MissionRepository] over the MAVLink mission protocol, addressed to the vehicle currently on the link. */
class DefaultMissionRepository(
    frames: Flow<MavFrame<out MavMessage<*>>>,
    private val link: StateFlow<LinkState>,
    private val vehicle: StateFlow<VehicleState>,
    sender: MavSender,
) : MissionRepository {
    private val protocol = MissionProtocol(frames, sender)

    override suspend fun upload(items: List<MissionItem>, onProgress: (Int, Int) -> Unit): Result<Unit> {
        val target = target() ?: return noVehicle()
        val home = vehicle.value.home
            ?: return Result.failure(MissionTransferException("Home isn't known yet. Wait for the vehicle's GPS fix, then try again."))
        return protocol.upload(target, missionToWire(home, items, target.system, target.component), onProgress)
    }

    override suspend fun download(onProgress: (Int, Int) -> Unit): Result<Mission> {
        val target = target() ?: return noVehicle()
        return protocol.download(target, onProgress).map(::missionFromWire)
    }

    override suspend fun clear(): Result<Unit> {
        val target = target() ?: return noVehicle()
        return protocol.clear(target)
    }

    /**
     * The vehicle to talk to, or null. Mission traffic waits for the KFT login (spec S12): a KFT flight controller
     * drops it until the login succeeds, so starting early would only end in "no answer".
     */
    private fun target() = (link.value as? LinkState.Connected)?.vehicle
        ?.takeIf { vehicle.value.login?.allowsTraffic != false }
        ?.let { MissionProtocol.Target(it.systemId, it.componentId) }

    private fun <T> noVehicle(): Result<T> {
        val login = vehicle.value.login?.takeIf { !it.allowsTraffic && link.value is LinkState.Connected }
        return Result.failure(MissionTransferException(login?.let { "${it.label}. Mission transfers wait for the login." } ?: "No vehicle connected."))
    }
}
