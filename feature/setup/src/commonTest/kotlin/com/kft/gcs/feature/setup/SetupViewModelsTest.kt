package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.AccelPosition
import com.kft.gcs.core.vehicle.CompassCalStatus
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.ParamType
import com.kft.gcs.core.vehicle.SetupCommand
import com.kft.gcs.core.vehicle.SetupReport
import com.kft.gcs.core.vehicle.SetupResult
import com.kft.gcs.core.vehicle.Severity
import com.kft.gcs.core.vehicle.StatusMessage
import com.kft.gcs.core.vehicle.VehicleSetupRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A vehicle's parameters in memory. [locked] ones keep their value, like a locked KFT parameter. */
internal class FakeParams(values: Map<String, Float>) : ParamRepository {
    override val known = MutableStateFlow(values.entries.mapIndexed { i, (k, v) -> k to Param(k, v, ParamType.REAL32, i) }.toMap())
    val sets = mutableListOf<Pair<String, Float>>()
    val locked = mutableSetOf<String>()

    override suspend fun downloadAll(onProgress: (Int, Int) -> Unit) = Result.success(known.value.values.toList())

    override suspend fun set(param: Param, value: Float): ParamSetResult {
        sets += param.name to value
        if (param.name in locked) return ParamSetResult.NotApplied("Locked", param)
        val now = param.copy(value = value)
        known.value = known.value + (param.name to now)
        return ParamSetResult.Applied(now)
    }
}

/** A scripted vehicle for setup commands: records what is sent, answers with [answer], reports what the test emits. */
internal class FakeSetup : VehicleSetupRepository {
    val sent = mutableListOf<SetupCommand>()
    var answer: (SetupCommand) -> SetupResult = { SetupResult.Done }
    override val reports = MutableSharedFlow<SetupReport>(extraBufferCapacity = 64)

    override suspend fun run(command: SetupCommand): SetupResult {
        sent += command
        return answer(command)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelsTest {
    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED, vehicleKind = VehicleKind.COPTER))
    private val selected = MutableStateFlow<VehicleId?>(VehicleId(1, 1u))
    private val params = FakeParams(
        mapOf(
            "FS_THR_ENABLE" to 1f, "FS_THR_VALUE" to 975f, "BATT_FS_LOW_ACT" to 2f, "BATT_LOW_VOLT" to 14f, "BATT_CRT_VOLT" to 13.5f,
            "FLTMODE_CH" to 5f, "FLTMODE1" to 0f, "FLTMODE4" to 5f,
        ) + (1..4).flatMap { ch -> listOf("RC${ch}_MIN" to 1100f, "RC${ch}_MAX" to 1900f, "RC${ch}_TRIM" to 1500f) },
    )
    private val setup = FakeSetup()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    private fun TestScope.setupVm() = SetupViewModel(vehicle, params, selected).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }
    private fun TestScope.calVm() = CalibrationViewModel(vehicle, setup, params, selected).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }

    // ---- parameter pages ----

    /** Choose → check → confirm → write; the page then shows what the vehicle echoed. */
    @Test
    fun aFailsafeIsCheckedConfirmedThenWritten() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FAILSAFES)
        vm.onFieldClicked("BATT_FS_LOW_ACT")
        vm.onEditTextChanged("5")
        vm.onEditSetClicked()
        runCurrent()
        val confirm = vm.state.value.edit!!.confirm!!
        assertContains(confirm, "from RTL to Terminate")
        assertContains(confirm, "stops the motors", message = "Terminate gets a warning")
        assertTrue(params.sets.isEmpty(), "nothing sent before the confirmation")
        vm.onEditConfirmClicked()
        runCurrent()
        assertEquals(listOf("BATT_FS_LOW_ACT" to 5f), params.sets)
        assertEquals("Terminate", vm.state.value.sections.flatMap { it.rows }.single { it.param == "BATT_FS_LOW_ACT" }.value)
    }

    @Test
    fun outOfRangeAndUnlistedValuesAreRefusedBeforeSending() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FAILSAFES)
        vm.onFieldClicked("FS_THR_VALUE")
        vm.onEditTextChanged("1200")
        vm.onEditSetClicked()
        runCurrent()
        assertEquals("Allowed range is 910 to 1100 µs", vm.state.value.edit!!.error)
        assertNull(vm.state.value.edit!!.confirm)
        vm.onEditDismissed()
        vm.onFieldClicked("FS_THR_ENABLE")
        vm.onEditTextChanged("2") // removed in ArduPilot 4.0
        vm.onEditSetClicked()
        runCurrent()
        assertEquals("Pick one of the listed values", vm.state.value.edit!!.error)
        assertTrue(params.sets.isEmpty())
    }

    /** A write that would leave the FC refusing to arm is allowed (the pair may be fixed next) but says so. */
    @Test
    fun aWriteThatBreaksAnArmingCheckWarns() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FAILSAFES)
        vm.onFieldClicked("BATT_LOW_VOLT")
        vm.onEditTextChanged("13")
        vm.onEditSetClicked()
        runCurrent()
        assertContains(vm.state.value.edit!!.confirm!!, "low voltage must be above the critical voltage")
    }

    @Test
    fun nothingCanBeEditedWhileArmedOrAfterTheVehicleChanged() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FAILSAFES)
        vehicle.value = vehicle.value.copy(armed = true)
        runCurrent()
        assertFalse(vm.state.value.canWrite)
        vm.onFieldClicked("FS_THR_ENABLE")
        assertNull(vm.state.value.edit)
        vehicle.value = vehicle.value.copy(armed = false)
        vm.onFieldClicked("FS_THR_ENABLE")
        vm.onEditTextChanged("3")
        vm.onEditSetClicked()
        runCurrent()
        selected.value = VehicleId(1, 2u)
        vm.onEditConfirmClicked()
        runCurrent()
        assertTrue(params.sets.isEmpty(), "the dialog was for vehicle 1")
    }

    @Test
    fun theModeSwitchPositionIsShownLive() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FLIGHT_MODES)
        vehicle.value = vehicle.value.copy(rcChannels = listOf(1500, 1500, 1000, 1500, 1500))
        runCurrent()
        assertEquals(3, vm.state.value.activeSlot, "1500 µs on channel 5 = position 4")
        assertEquals("Channel 5 at 1500 µs: position 4", vm.state.value.modeSwitch)
        assertEquals("Loiter", vm.state.value.sections.single().rows.single { it.param == "FLTMODE4" }.value)
    }

    // ---- calibrations ----

    @Test
    fun accelerometerCalibrationFollowsTheVehiclesRequests() = runTest(dispatcher) {
        val vm = calVm()
        vm.onAccelStartClicked()
        runCurrent()
        assertEquals(Calibration.ACCEL, vm.state.value.running)
        setup.reports.emit(SetupReport.AccelRequest(AccelPosition.LEVEL))
        runCurrent()
        assertEquals(AccelPosition.LEVEL, vm.state.value.accel.waitingFor)
        vm.onAccelPlacedClicked()
        runCurrent()
        assertEquals(SetupCommand.AccelPlaced(AccelPosition.LEVEL), setup.sent.last())
        setup.reports.emit(SetupReport.AccelRequest(AccelPosition.LEVEL)) // a late repeat while sampling: ignored
        runCurrent()
        assertEquals(AccelPosition.LEVEL, vm.state.value.accel.sampling)
        setup.reports.emit(SetupReport.AccelRequest(AccelPosition.LEFT))
        setup.reports.emit(SetupReport.Text(StatusMessage("Place vehicle on its LEFT side and press any key.", Severity.ERROR)))
        runCurrent()
        assertEquals(AccelPosition.LEFT, vm.state.value.accel.waitingFor)
        assertEquals(1, vm.state.value.log.size)
        setup.reports.emit(SetupReport.AccelDone(true))
        runCurrent()
        assertNull(vm.state.value.running)
        assertTrue(vm.state.value.imuResult!!.ok)
    }

    /** ArduPilot can't cancel an accelerometer calibration over MAVLink, so the screen says a reboot clears it. */
    @Test
    fun cancellingTheAccelerometerAdvisesAReboot() = runTest(dispatcher) {
        val vm = calVm()
        vm.onAccelStartClicked()
        runCurrent()
        vm.onCancelClicked()
        runCurrent()
        assertNull(vm.state.value.running)
        assertContains(vm.state.value.imuResult!!.text, "reboots")
        assertTrue(vm.state.value.rebootAdvice != null)
        vm.onRebootClicked()
        vm.onRebootConfirmed()
        runCurrent()
        assertEquals(SetupCommand.Reboot, setup.sent.last())
        assertNull(vm.state.value.rebootAdvice)
    }

    @Test
    fun compassCalibrationWaitsForEveryCompassInTheMask() = runTest(dispatcher) {
        val vm = calVm()
        setup.reports.emit(SetupReport.CompassResult(0, CompassCalStatus.FAILED, 99f, false)) // before the start: ignored
        vm.onCompassStartClicked()
        runCurrent()
        setup.reports.emit(SetupReport.CompassProgress(0, 40, CompassCalStatus.RUNNING_1, 1, mask = 0b11))
        setup.reports.emit(SetupReport.CompassProgress(1, 35, CompassCalStatus.RUNNING_1, 1, mask = 0b11))
        setup.reports.emit(SetupReport.CompassResult(0, CompassCalStatus.SUCCESS, 7.5f, true))
        runCurrent()
        assertEquals(Calibration.COMPASS, vm.state.value.running, "compass 2 isn't done yet")
        assertEquals(listOf(100, 35), vm.state.value.compass.rows.map { it.percent })
        setup.reports.emit(SetupReport.CompassResult(1, CompassCalStatus.SUCCESS, 9f, true))
        runCurrent()
        assertNull(vm.state.value.running)
        assertTrue(vm.state.value.compass.result!!.ok)
        assertContains(vm.state.value.rebootAdvice!!, "reboot")
    }

    @Test
    fun aFailedCompassSaysWhichAndCancelStopsTheVehicle() = runTest(dispatcher) {
        val vm = calVm()
        vm.onCompassStartClicked()
        runCurrent()
        setup.reports.emit(SetupReport.CompassProgress(0, 10, CompassCalStatus.RUNNING_1, 1, mask = 0b1))
        setup.reports.emit(SetupReport.CompassResult(0, CompassCalStatus.BAD_ORIENTATION, 50f, false))
        runCurrent()
        assertFalse(vm.state.value.compass.result!!.ok)
        assertContains(vm.state.value.compass.result!!.text, "Compass 1: Failed: compass orientation is wrong")
        vm.onCompassStartClicked()
        runCurrent()
        vm.onCancelClicked()
        runCurrent()
        assertEquals(SetupCommand.CompassCancel, setup.sent.last())
    }

    @Test
    fun radioCalibrationSweepsCentresReviewsAndSaves() = runTest(dispatcher) {
        val vm = calVm()
        vehicle.value = vehicle.value.copy(rcChannels = listOf(1500, 1500, 1000, 1500))
        vm.onRadioStartClicked()
        runCurrent()
        assertEquals(listOf<SetupCommand>(SetupCommand.RcCalibrating(true)), setup.sent)
        assertEquals(RadioStep.SWEEP, vm.state.value.radio.step)
        for (rc in listOf(listOf(1000, 1000, 990, 1000), listOf(2000, 2000, 2010, 2000), listOf(1500, 1500, 995, 1500))) {
            vehicle.value = vehicle.value.copy(rcChannels = rc)
            runCurrent()
        }
        vm.onRadioSweepDone()
        vm.onRadioCentred()
        runCurrent()
        val review = vm.state.value.radio.review!!
        assertEquals(emptyList<String>(), review.problems)
        vm.onRadioSaveClicked()
        runCurrent()
        assertEquals(review.writes, params.sets)
        assertEquals(990f, params.known.value.getValue("RC3_MIN").value)
        assertEquals(SetupCommand.RcCalibrating(false), setup.sent.last())
        assertNull(vm.state.value.running)
        assertTrue(vm.state.value.radio.result!!.ok)
    }

    /** A change of vehicle stops the calibration here: commands now go to the newly selected vehicle. */
    @Test
    fun selectingAnotherVehicleStopsTheCalibration() = runTest(dispatcher) {
        val vm = calVm()
        vm.onRadioStartClicked()
        runCurrent()
        selected.value = VehicleId(1, 2u)
        runCurrent()
        assertNull(vm.state.value.running)
        assertFalse(vm.state.value.radio.result!!.ok)
        assertEquals(listOf<SetupCommand>(SetupCommand.RcCalibrating(true)), setup.sent, "nothing sent to the other vehicle")
    }

    @Test
    fun calibrationsNeedAConnectedDisarmedVehicle() = runTest(dispatcher) {
        val vm = calVm()
        vehicle.value = vehicle.value.copy(armed = true)
        runCurrent()
        assertFalse(vm.state.value.canStart)
        assertContains(vm.state.value.hint!!, "Disarm")
        vm.onBaroClicked()
        vm.onRebootClicked()
        runCurrent()
        assertTrue(setup.sent.isEmpty())
        assertFalse(vm.state.value.confirmReboot)
    }

    @Test
    fun aRefusedBaroCalibrationShowsWhy() = runTest(dispatcher) {
        setup.answer = { SetupResult.Failed("No answer from the vehicle.") }
        val vm = calVm()
        vm.onBaroClicked()
        runCurrent()
        assertEquals(Outcome(false, "No answer from the vehicle."), vm.state.value.baroResult)
        assertNull(vm.state.value.running)
    }
}
