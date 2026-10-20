package com.kft.gcs.feature.fly

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.companion.CompanionRepository
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FleetVehicle
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/**
 * The companion diagnostics panel on the Fly view. Read-only towards the vehicle: its only actions are this GCS's own
 * WebSocket (connect, disconnect, settings). It reads the fleet to know which vehicle is selected and what that FC
 * reports about its estimator; it never writes to the fleet, and the companion store never feeds the FC state.
 *
 * The state is rebuilt on every companion message and also every [TICK], because ages must keep growing (and turn
 * STALE) when messages stop or when they keep arriving with a frozen measurement time.
 */
class CompanionViewModel(
    private val repository: CompanionRepository,
    vehicles: StateFlow<List<FleetVehicle>>,
    selectedId: StateFlow<VehicleId?>,
) : ViewModel() {
    private val ticks = flow {
        while (true) {
            emit(Unit)
            delay(TICK)
        }
    }

    val state: StateFlow<CompanionUiState> =
        combine(repository.state, repository.config, vehicles, selectedId, ticks) { s, config, all, id, _ ->
            companionUi(s, config, repository.now(), all.find { it.id == id })
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            companionUi(repository.state.value, repository.config.value, repository.now(), vehicles.value.find { it.id == selectedId.value }),
        )

    fun onConnectClicked() = repository.connect()

    fun onDisconnectClicked() = repository.disconnect()

    /** Saves the address and vehicle. Returns the problem to show instead, if any. */
    fun onSettingsSaved(url: String, systemId: String): String? {
        val u = url.trim()
        if (!u.startsWith("ws://") && !u.startsWith("wss://")) return "Enter a ws:// address, e.g. ws://192.168.144.30:8765"
        val id = systemId.trim().toIntOrNull()?.takeIf { it in 1..255 } ?: return "Vehicle system id is 1 to 255"
        repository.onConfigChanged(repository.config.value.copy(url = u, systemId = id))
        return null
    }

    companion object {
        /** 10 updates a second: smooth enough for an age display, and a 0.5 s timeout shows within 0.1 s of passing. */
        val TICK = 100.milliseconds
    }
}
