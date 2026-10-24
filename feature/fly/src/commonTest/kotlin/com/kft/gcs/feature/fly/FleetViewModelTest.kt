package com.kft.gcs.feature.fly

import app.cash.turbine.test
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.MissionProgress
import com.kft.gcs.core.vehicle.MissionRecord
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.core.vehicle.StatusMessage
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

class FleetViewModelTest {
    private val drone1 = VehicleId(1, 1u)
    private val drone2 = VehicleId(1, 2u)
    private val flying = VehicleState(connected = true, armed = true, flightMode = "Auto", batteryVolts = 12.4, batteryPercent = 80, login = KftLoginStatus.AUTHENTICATED)

    private fun fleet(one: VehicleState, two: VehicleState = flying) =
        listOf(FleetVehicle(drone1, 0, one, link = "UDP listen :14550"), FleetVehicle(drone2, 1, two, link = "UDP listen :14550"))

    private fun alerts(before: VehicleState, now: VehicleState) = fleetAlerts(fleet(before), fleet(now)).map { it.text to it.severity }

    // ---- the alert rules: each fires once, when its condition starts ----

    @Test
    fun linkLostAndRestored() {
        assertEquals(listOf("Link lost: no heartbeat" to Severity.ERROR), alerts(flying, flying.copy(connected = false)))
        assertEquals(listOf("Link restored" to Severity.INFO), alerts(flying.copy(connected = false), flying))
        assertEquals(emptyList(), alerts(flying.copy(connected = false), flying.copy(connected = false)), "still lost: not said again")
    }

    /** Under 20 %; or under the vehicle's own BATT_LOW_VOLT while the percentage still looks fine; or ArduPilot's failsafe text. */
    @Test
    fun batteryLowByPercentOrByArduPilotsOwnWords() {
        assertEquals(listOf("Battery low (12.4 V)" to Severity.ERROR), alerts(flying, flying.copy(batteryPercent = 19)))
        assertEquals(emptyList(), alerts(flying.copy(batteryPercent = 19), flying.copy(batteryPercent = 18)), "said once")
        assertEquals(emptyList(), alerts(flying, flying.copy(batteryPercent = 20)), "20 % is not under 20 %")
        // 10.0 V at 80 %, with the vehicle's failsafe at 10.5 V: low by voltage. At 10.6 V it isn't.
        val knowsItsLimit = flying.copy(watchedParams = mapOf("BATT_LOW_VOLT" to 10.5f))
        assertEquals(listOf("Battery low (10.0 V)" to Severity.ERROR), alerts(knowsItsLimit, knowsItsLimit.copy(batteryVolts = 10.0)))
        assertFalse(batteryLow(knowsItsLimit.copy(batteryVolts = 10.6)))
        assertFalse(batteryLow(flying.copy(batteryVolts = 3.0, watchedParams = mapOf("BATT_LOW_VOLT" to 0f))), "0 = that failsafe is off")
        val failsafe = flying.copy(batteryVolts = 10.0, lastMessage = StatusMessage("Battery 1 is low 10.00V used 0 mAh", Severity.WARNING))
        assertEquals(listOf("Battery low (10.0 V)" to Severity.ERROR), alerts(flying, failsafe))
        assertFalse(batteryLow(flying.copy(lastMessage = StatusMessage("PreArm: Battery failsafe on", Severity.ERROR))), "only low or critical")
    }

    @Test
    fun rtlAndMissionDone() {
        assertEquals(listOf("Returning to launch (RTL)" to Severity.WARNING), alerts(flying, flying.copy(flightMode = "RTL")))
        assertEquals(emptyList(), alerts(flying, flying.copy(flightMode = "RTL", armed = false)), "RTL selected on the ground isn't a return")
        val done = flying.copy(mission = MissionProgress(current = 39, total = 39, lastReached = 39, complete = true))
        assertEquals(listOf("Mission complete" to Severity.INFO), alerts(flying.copy(mission = MissionProgress(38, 39, 37, false)), done))
        assertEquals(emptyList(), alerts(done, done))
        assertEquals(emptyList(), alerts(flying, done), "its first mission report already says complete: it finished before we connected")
    }

    /** A vehicle seen for the first time raises nothing, whatever state it is in; two things at once are two alerts. */
    @Test
    fun aNewVehicleIsQuietAndSeveralThingsAtOnceAreAllSaid() {
        assertEquals(emptyList(), fleetAlerts(emptyList(), fleet(flying.copy(connected = false, batteryPercent = 5))))
        assertEquals(
            listOf("Battery low (12.4 V)", "Returning to launch (RTL)"),
            alerts(flying, flying.copy(batteryPercent = 10, flightMode = "RTL")).map { it.first },
        )
    }

    // ---- the dashboard rows ----

    /** PRD FR-8: each row says how its vehicle signs, and what the FC itself counted as rejected (S8's SIGFAIL). */
    @Test
    fun aRowCarriesHowItsVehicleSigns() {
        val signed = flying.copy(signing = SigningStatus.Signed("a".repeat(24)), fcSignatureFailures = 3)
        val (first, second) = fleetRows(fleet(signed, flying), drone1, emptyList())

        assertEquals(SigningStatus.Signed("a".repeat(24)), first.signing)
        assertEquals(3, first.fcSignatureFailures)
        assertEquals(SigningStatus.Unsigned, second.signing, "the default, and most of the fleet today")
        assertEquals(null, second.fcSignatureFailures, "a stock FC reports no count")
    }

    @Test
    fun aRowSaysEverythingAboutItsVehicle() {
        val record = MissionRecord("id", "Field A", drone1, "plan", itemCount = 39, plannedPhotos = 88, photos = List(33) { LatLon(0.0, 0.0) })
        val one = flying.copy(flightMode = "RTL", batteryVolts = 10.04, batteryPercent = 12, mission = MissionProgress(20, 51, 20, false))
        val (first, second) = fleetRows(fleet(one, flying.copy(connected = false, photos = List(4) { LatLon(0.0, 0.0) })), drone1, listOf(record))
        assertEquals(
            FleetRow(
                drone1, "1", 0, selected = true, link = "UDP listen :14550", connected = true, login = "KFT login: OK", loginWarning = false,
                battery = "10.0 V · 12%", mode = "RTL", armed = true, mission = "20 / 51", photos = "33 / 88",
                conditions = listOf("Battery low" to Severity.ERROR, "RTL" to Severity.WARNING),
            ),
            first,
        )
        assertEquals(listOf("Link lost" to Severity.ERROR), second.conditions)
        assertEquals("4", second.photos, "no mission record for vehicle 2: just the count")
        assertFalse(second.selected)
    }

    // ---- the ViewModel: a list, newest first, with the time; each alert once as a toast ----

    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val vehicles = MutableStateFlow(fleet(flying))
    private val selected = MutableStateFlow<VehicleId?>(drone1)
    private var clock = "14:03:22"

    private fun TestScope.viewModel() = FleetViewModel(vehicles, selected, { selected.value = it }, MutableStateFlow(emptyList()), { clock }).also { vm ->
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
    }

    @Test
    fun alertsAreToastsAndAList() = runTest(dispatcher) {
        val vm = viewModel()
        vm.effects.test {
            vehicles.value = fleet(flying, flying.copy(connected = false))
            assertEquals(AlertUi("14:03:22", "Vehicle 2", "Link lost: no heartbeat", Severity.ERROR), awaitItem())
            clock = "14:03:40"
            vehicles.value = fleet(flying.copy(flightMode = "RTL"), flying)
            assertEquals("Returning to launch (RTL)", awaitItem().text)
            assertEquals("Link restored", awaitItem().text)
        }
        runCurrent()
        assertEquals(
            listOf("14:03:40 Vehicle 2: Link restored", "14:03:40 Vehicle 1: Returning to launch (RTL)", "14:03:22 Vehicle 2: Link lost: no heartbeat"),
            vm.state.value.alerts.map { "${it.time} ${it.vehicle}: ${it.text}" },
            "newest first",
        )
        assertEquals(listOf("RTL" to Severity.WARNING), vm.state.value.rows.first().conditions)

        vm.onVehicleSelected(drone2)
        runCurrent()
        assertEquals(listOf(false, true), vm.state.value.rows.map { it.selected })
        vm.onClearAlertsClicked()
        runCurrent()
        assertTrue(vm.state.value.alerts.isEmpty())
    }
}
