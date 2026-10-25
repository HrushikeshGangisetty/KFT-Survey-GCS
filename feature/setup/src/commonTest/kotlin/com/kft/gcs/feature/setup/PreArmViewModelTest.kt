package com.kft.gcs.feature.setup

import app.cash.turbine.test
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.PreArmReport
import com.kft.gcs.core.vehicle.SensorBits
import com.kft.gcs.core.vehicle.SensorHealth
import com.kft.gcs.core.vehicle.SensorState
import com.kft.gcs.core.vehicle.SetupCommand
import com.kft.gcs.core.vehicle.SetupResult
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PreArmViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))
    private val setup = FakeSetup()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    private fun TestScope.vm() = PreArmViewModel(vehicle, setup).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }

    // common.xml MAV_SYS_STATUS_SENSOR: gyro 0x1, GPS 0x20, PREARM_CHECK 0x10000000.
    private val gyro = 0x1u
    private val gps = 0x20u
    private val prearm = 0x10000000u

    /** The page lists the vehicle's report and sensors as they are; the verdict follows the SYS_STATUS bit. */
    @Test
    fun showsEveryFailureAndTheSensors() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(Readiness.UNKNOWN, vm.state.value.readiness, "nothing heard yet")
        vehicle.value = vehicle.value.copy(
            sensorBits = SensorBits(gyro or gps or prearm, gyro or gps or prearm, gyro),
            preArm = PreArmReport(listOf("GPS 1: Bad fix", "RC not found")),
        )
        runCurrent()
        with(vm.state.value) {
            assertEquals(Readiness.NOT_READY, readiness)
            assertEquals(listOf("GPS 1: Bad fix", "RC not found"), failures)
            assertEquals(listOf(SensorHealth("Gyro", SensorState.HEALTHY), SensorHealth("GPS", SensorState.UNHEALTHY)), sensors)
        }
        vehicle.value = vehicle.value.copy(sensorBits = SensorBits(gyro or gps or prearm, gyro or gps or prearm, gyro or gps or prearm), preArm = PreArmReport())
        runCurrent()
        assertEquals(Readiness.READY, vm.state.value.readiness)
    }

    /**
     * Without the SYS_STATUS bit a listed failure still means "not ready"; ARMING_CHECK = 0 (bit present, not enabled)
     * says the checks are off; armed and not connected are their own answers.
     */
    @Test
    fun readinessCases() {
        val v = VehicleState(connected = true)
        assertEquals(Readiness.UNKNOWN, readiness(v))
        assertEquals(Readiness.NOT_READY, readiness(v.copy(preArm = PreArmReport(listOf("RC not found")))))
        assertEquals(Readiness.CHECKS_OFF, readiness(v.copy(sensorBits = SensorBits(gyro or prearm, gyro, gyro or prearm))))
        assertEquals(Readiness.ARMED, readiness(v.copy(armed = true, sensorBits = SensorBits(prearm, prearm, prearm))))
        assertEquals(Readiness.UNKNOWN, readiness(v.copy(connected = false, preArm = PreArmReport(listOf("RC not found")))))
    }

    /** "Run checks now" sends the one command, is off while it waits, and says how it went. */
    @Test
    fun runChecksSendsTheCommandAndReports() = runTest(dispatcher) {
        val answer = CompletableDeferred<SetupResult>()
        val fake = object : com.kft.gcs.core.vehicle.VehicleSetupRepository by setup {
            override suspend fun run(command: SetupCommand): SetupResult {
                setup.sent += command
                return answer.await()
            }
        }
        val vm = PreArmViewModel(vehicle, fake).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }
        vm.effects.test {
            vm.onRunChecksClicked()
            runCurrent()
            assertTrue(vm.state.value.running)
            assertFalse(vm.state.value.canRun, "one run at a time")
            vm.onRunChecksClicked()
            answer.complete(SetupResult.Done)
            runCurrent()
            assertEquals(listOf<SetupCommand>(SetupCommand.RunPreArmChecks), setup.sent)
            assertContains((awaitItem() as SetupEffect.ShowMessage).text, "ran its pre-arm checks")
            assertFalse(vm.state.value.running)
        }
    }

    @Test
    fun aRefusalIsShownAndNothingIsSentWhenArmedOrLoggingIn() = runTest(dispatcher) {
        setup.answer = { SetupResult.Failed("No answer from the vehicle.") }
        val vm = vm()
        vm.effects.test {
            vm.onRunChecksClicked()
            runCurrent()
            assertEquals("Pre-arm checks: No answer from the vehicle.", (awaitItem() as SetupEffect.ShowMessage).text)
        }
        setup.sent.clear()
        vehicle.value = vehicle.value.copy(armed = true)
        runCurrent()
        assertContains(vm.state.value.hint!!, "armed")
        vm.onRunChecksClicked()
        vehicle.value = vehicle.value.copy(armed = false, login = KftLoginStatus.LOGGING_IN)
        runCurrent()
        assertContains(vm.state.value.hint!!, "wait for the login")
        vm.onRunChecksClicked()
        runCurrent()
        assertTrue(setup.sent.isEmpty())
    }
}
