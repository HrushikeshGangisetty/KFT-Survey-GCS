package com.kft.gcs.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kft.gcs.core.vehicle.SensorHealth
import com.kft.gcs.core.vehicle.SetupCommand
import com.kft.gcs.core.vehicle.SetupResult
import com.kft.gcs.core.vehicle.VehicleSetupRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the pre-arm page says at the top. */
enum class Readiness(val label: String) {
    UNKNOWN("Not known yet"),
    READY("Ready to arm"),
    NOT_READY("Not ready to arm"),
    ARMED("Armed"),
    CHECKS_OFF("Pre-arm checks are off (ARMING_CHECK = 0)"),
}

/**
 * The pre-arm page.
 * @property failures every reason in the FC's latest pre-arm report ([com.kft.gcs.core.vehicle.PreArmReport]).
 * @property sensors SYS_STATUS's sensor health, empty until the first SYS_STATUS.
 * @property canRun connected, logged in, disarmed, and no run waiting for its answer.
 * @property hint why [canRun] is false, or null.
 */
data class PreArmUiState(
    val readiness: Readiness = Readiness.UNKNOWN,
    val failures: List<String> = emptyList(),
    val sensors: List<SensorHealth> = emptyList(),
    val canRun: Boolean = false,
    val running: Boolean = false,
    val hint: String? = "Connect a vehicle to see why it won't arm.",
)

/**
 * Why won't the drone arm? The pilot arms on the RC (S9); this page shows the FC's reasons, all of them, which the
 * Fly tab's single latest message can't.
 *
 * Everything shown comes from [VehicleState] (the report and the sensor bits). The only thing sent is
 * MAV_CMD_RUN_PREARM_CHECKS, so the report is fresh instead of up to 30 s old.
 */
class PreArmViewModel(
    private val vehicle: StateFlow<VehicleState>,
    private val setup: VehicleSetupRepository,
) : ViewModel() {

    private val running = MutableStateFlow(false)

    val state: StateFlow<PreArmUiState> = combine(vehicle, running, ::build)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), build(vehicle.value, running.value))

    private val _effects = Channel<SetupEffect>(Channel.BUFFERED)
    val effects: Flow<SetupEffect> = _effects.receiveAsFlow()

    /** Asks the FC to run its checks now. Its answer is only "done"; the findings arrive as texts and fill the list. */
    fun onRunChecksClicked() {
        if (!state.value.canRun) return
        running.value = true
        viewModelScope.launch {
            val text = when (val r = setup.run(SetupCommand.RunPreArmChecks)) {
                SetupResult.Done -> "The vehicle ran its pre-arm checks. Anything that fails is listed."
                is SetupResult.Failed -> "Pre-arm checks: ${r.reason}"
            }
            _effects.trySend(SetupEffect.ShowMessage(text))
            running.value = false
        }
    }
}

/**
 * The verdict, from the SYS_STATUS pre-arm bit when the FC reports it. Without the bit (or before the first SYS_STATUS)
 * a listed failure still means "not ready"; no failures then only means nothing was heard yet.
 */
internal fun readiness(v: VehicleState): Readiness {
    val bits = v.sensorBits
    return when {
        !v.connected -> Readiness.UNKNOWN
        v.armed -> Readiness.ARMED
        bits?.preArmPassing == true -> Readiness.READY
        bits?.preArmPassing == false || v.preArm.failures.isNotEmpty() -> Readiness.NOT_READY
        bits?.preArmChecksOff == true -> Readiness.CHECKS_OFF
        else -> Readiness.UNKNOWN
    }
}

private fun build(v: VehicleState, running: Boolean): PreArmUiState {
    val hint = when {
        !v.connected -> "Connect a vehicle to see why it won't arm."
        v.login?.allowsTraffic == false -> "${v.login?.label}. Pre-arm checks wait for the login."
        v.armed -> "The vehicle is armed: it only runs its pre-arm checks on the ground."
        else -> null
    }
    return PreArmUiState(
        readiness = readiness(v),
        failures = if (v.connected) v.preArm.failures else emptyList(),
        sensors = if (v.connected) v.sensorBits?.sensors.orEmpty() else emptyList(),
        canRun = hint == null && !running,
        running = running,
        hint = hint,
    )
}
