package com.kft.gcs.feature.fly

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.MissionRecord
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.batteryLowVolts
import com.kft.gcs.core.vehicle.vehicleLabels
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Everything the Fleet screen draws: one row per vehicle, and the alerts so far, newest first. */
@Immutable
data class FleetUiState(val rows: List<FleetRow> = emptyList(), val alerts: List<AlertUi> = emptyList())

/**
 * One vehicle on the dashboard. Every value is text ready to show; a dash means "not reported".
 * @property slot picks its colour, the same as on the map and in the switcher.
 * @property conditions what is wrong or notable right now ("Link lost", "Battery low", "RTL", "Mission done"), each
 *   with how serious it is. Empty = nothing to say.
 */
data class FleetRow(
    val id: VehicleId,
    val label: String,
    val slot: Int,
    val selected: Boolean,
    val link: String,
    val connected: Boolean,
    val login: String?,
    val loginWarning: Boolean,
    val battery: String,
    val mode: String,
    val armed: Boolean,
    val mission: String,
    val photos: String,
    val conditions: List<Pair<String, Severity>>,
)

/** One alert in the list: when (the device's time of day), which vehicle, what. */
data class AlertUi(val time: String, val vehicle: String, val text: String, val severity: Severity)

/** Something that just happened to a vehicle. Pure data from [fleetAlerts]; the ViewModel adds the time. */
internal data class FleetAlert(val vehicle: VehicleId, val text: String, val severity: Severity)

/**
 * A vehicle's battery is low when any of these says so:
 * - the percentage is under 20 %;
 * - the voltage is under the vehicle's own failsafe voltage (BATT_LOW_VOLT, read at connect). This is the one that
 *   catches a voltage failsafe: ArduPilot works the percentage out from consumed mAh, so it can still read 50 % while
 *   the voltage has collapsed (seen in SITL: 10.0 V at 52 %);
 * - ArduPilot's own failsafe text is the latest message ("Battery 1 is low 10.00V used 0 mAh",
 *   `AP_BattMonitor::check_failsafes`). A text alone is not enough (the next message replaces it), but it covers a
 *   vehicle whose BATT_LOW_VOLT wasn't read.
 */
internal fun batteryLow(v: VehicleState): Boolean {
    val volts = v.batteryVolts
    val failsafeVolts = v.batteryLowVolts
    return (v.batteryPercent ?: 100) < 20 ||
        (volts != null && failsafeVolts != null && volts < failsafeVolts) ||
        v.lastMessage?.text.orEmpty().let { it.contains("Battery", ignoreCase = true) && (it.contains("low", true) || it.contains("critical", true)) }
}

/**
 * What changed between two looks at the fleet, as alerts. Pure: each rule is a condition that is true [now] and was
 * not true [before], so an alert is raised once when it starts, not on every telemetry update.
 * A vehicle seen for the first time raises nothing (the Links tab already says "Vehicle found").
 */
internal fun fleetAlerts(before: List<FleetVehicle>, now: List<FleetVehicle>): List<FleetAlert> = now.flatMap { v ->
    val was = before.find { it.id == v.id }?.state ?: return@flatMap emptyList()
    val s = v.state
    listOfNotNull(
        FleetAlert(v.id, "Link lost: no heartbeat", Severity.ERROR).takeIf { was.connected && !s.connected },
        FleetAlert(v.id, "Link restored", Severity.INFO).takeIf { !was.connected && s.connected },
        FleetAlert(v.id, "Battery low" + (s.batteryVolts?.let { " (${oneDecimal(it)} V)" } ?: ""), Severity.ERROR).takeIf { batteryLow(s) && !batteryLow(was) },
        FleetAlert(v.id, "Returning to launch (RTL)", Severity.WARNING).takeIf { s.armed && s.flightMode == "RTL" && was.flightMode != "RTL" },
        // From "known to be under way" to complete. A vehicle whose first mission report already says complete
        // (it finished before the GCS connected) isn't news.
        FleetAlert(v.id, "Mission complete", Severity.INFO).takeIf { s.mission?.complete == true && was.mission?.complete == false },
    )
}

/** The dashboard rows. [records] gives each vehicle's photo count against its mission's plan (newest record first). */
internal fun fleetRows(all: List<FleetVehicle>, selectedId: VehicleId?, records: List<MissionRecord>): List<FleetRow> {
    val labels = vehicleLabels(all.map { it.id })
    return all.map { v ->
        val s = v.state
        val record = records.firstOrNull { it.vehicle == v.id }
        FleetRow(
            id = v.id,
            label = labels.getValue(v.id),
            slot = v.slot,
            selected = v.id == selectedId,
            link = v.link.ifEmpty { "Link ${v.id.link}" },
            connected = s.connected,
            login = s.login?.label,
            loginWarning = s.login?.warning == true,
            battery = listOfNotNull(s.batteryVolts?.let { "${oneDecimal(it)} V" }, s.batteryPercent?.let { "$it%" }).joinToString(" · ").ifEmpty { "–" },
            mode = s.flightMode ?: "–",
            armed = s.armed,
            mission = missionText(s.mission),
            photos = record?.takeIf { it.plannedPhotos > 0 }?.let { "${it.photos.size} / ${it.plannedPhotos}" } ?: "${s.photos.size}",
            conditions = listOfNotNull(
                ("Two vehicles on system id ${v.id.systemId}" to Severity.ERROR).takeIf { v.duplicateId },
                ("Link lost" to Severity.ERROR).takeIf { !s.connected },
                ("Battery low" to Severity.ERROR).takeIf { batteryLow(s) },
                ("RTL" to Severity.WARNING).takeIf { s.armed && s.flightMode == "RTL" },
                ("Mission done" to Severity.INFO).takeIf { s.mission?.complete == true },
            ),
        )
    }
}

private fun oneDecimal(v: Double) = (kotlin.math.round(v * 10) / 10).toString()

/** How many alerts the list keeps; older ones drop off the end. */
internal const val MAX_ALERTS = 100

/**
 * The fleet dashboard (Pass 32): every vehicle's link, login, battery, mode, mission and photo progress, and the
 * alerts raised as things happen. Monitoring only, like the Fly view (spec S9): the one thing it does is select a
 * vehicle.
 *
 * Created once for the window, so it watches the fleet whichever tab is showing: an alert is a toast on any tab
 * ([effects]) and a line in the list.
 *
 * @param timeOfDay the device's local time as text ("14:03:22"), for the alert list.
 */
class FleetViewModel(
    private val vehicles: StateFlow<List<FleetVehicle>>,
    private val selectedId: StateFlow<VehicleId?>,
    private val select: (VehicleId) -> Unit,
    records: StateFlow<List<MissionRecord>>,
    private val timeOfDay: () -> String,
) : ViewModel() {
    private val alerts = MutableStateFlow<List<AlertUi>>(emptyList())

    val state: StateFlow<FleetUiState> = combine(vehicles, selectedId, records, alerts) { all, selected, r, a -> FleetUiState(fleetRows(all, selected, r), a) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FleetUiState())

    private val _effects = Channel<AlertUi>(Channel.BUFFERED)

    /** Each new alert once, for a toast. */
    val effects: Flow<AlertUi> = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            var before = vehicles.value
            vehicles.collect { now ->
                val labels = vehicleLabels(now.map { it.id })
                val new = fleetAlerts(before, now).map { AlertUi(timeOfDay(), "Vehicle ${labels[it.vehicle] ?: it.vehicle.systemId}", it.text, it.severity) }
                before = now
                if (new.isEmpty()) return@collect
                alerts.update { (new.reversed() + it).take(MAX_ALERTS) }
                new.forEach { _effects.send(it) }
            }
        }
    }

    fun onVehicleSelected(id: VehicleId) = select(id)

    fun onClearAlertsClicked() {
        alerts.value = emptyList()
    }
}
