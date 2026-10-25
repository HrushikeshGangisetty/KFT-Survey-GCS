package com.kft.gcs.feature.setup

import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.vehicle.EkfVariances
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.SetupCommand
import com.kft.gcs.core.vehicle.SetupResult
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.core.vehicle.Vibration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

/** The compass details, frame and vibration/EKF pages: their field tables, the health limits, and the ViewModels. */
class CompassFrameHealthTest {

    /*
     * Two device ids, packed by hand from AP_HAL/Device.h (bus type bits 0-2, bus 3-7, address 8-15, devtype 16-23):
     * - IST8310 (0x0A) on I2C (1) bus 1, address 0x0E: 1 + (1 shl 3) + (0x0E shl 8) + (0x0A shl 16) = 1 + 8 + 3584 + 655360 = 658953
     * - AK09916 (0x09) on SPI (2) bus 4, device 3:     2 + (4 shl 3) + (3 shl 8) + (9 shl 16)       = 2 + 32 + 768 + 589824 = 590626
     */
    private val external = 658953
    private val internal = 590626

    // ---- compass details ----

    /**
     * Priorities choose among the detected compasses (by their chip and bus), USEn go with them, and EXTERNAL/ORIENT come
     * per detected compass with ArduPilot's irregular names (COMPASS_EXTERNAL, then COMPASS_EXTERN2).
     */
    @Test
    fun compassPageListsTheDetectedCompasses() {
        val sections = compassSections(mapOf("COMPASS_DEV_ID" to internal.toFloat(), "COMPASS_DEV_ID2" to external.toFloat(), "COMPASS_DEV_ID3" to 0f))
        assertEquals(listOf("Compasses", "Priority and use", "Compass 1: AK09916, SPI bus 4, device 3", "Compass 2: IST8310, I2C bus 1, address 0x0e"), sections.map { it.title })
        val priority = sections[1].fields
        assertEquals(listOf("COMPASS_PRIO1_ID", "COMPASS_USE", "COMPASS_PRIO2_ID", "COMPASS_USE2", "COMPASS_PRIO3_ID", "COMPASS_USE3"), priority.map { it.param })
        val choices = (priority[0].kind as FieldKind.Choice).options
        assertEquals(listOf(0, internal, external), choices.map { it.first })
        assertTrue(priority[0].reboot, "COMPASS_PRIOn_ID: 'Reboot required after change'")
        assertEquals(listOf("COMPASS_EXTERNAL", "COMPASS_ORIENT"), sections[2].fields.map { it.param })
        assertEquals(listOf("COMPASS_EXTERN2", "COMPASS_ORIENT2"), sections[3].fields.map { it.param })
        // A rotation reads as ArduPilot names it; 41 isn't one.
        assertEquals("Yaw90Roll180", displayValue(sections[2].fields[1].kind, 10f))
        assertEquals("41, not a listed value", displayValue(sections[2].fields[1].kind, 41f))
    }

    @Test
    fun aPriorityThatClashesSaysWhichOtherToChange() {
        val values = mapOf("COMPASS_PRIO1_ID" to internal.toFloat(), "COMPASS_PRIO2_ID" to external.toFloat())
        assertContains(priorityClash("COMPASS_PRIO1_ID", external.toFloat(), values)!!, "COMPASS_PRIO2_ID")
        assertNull(priorityClash("COMPASS_PRIO3_ID", 0f, values), "None never clashes")
        assertNull(priorityClash("COMPASS_USE", 1f, values), "not a priority")
    }

    // ---- frame ----

    @Test
    fun framePageFitsTheVehicle() {
        assertEquals(listOf("FRAME_CLASS", "FRAME_TYPE", "AHRS_ORIENTATION"), frameSections(VehicleKind.COPTER, emptyMap()).flatMap { it.fields }.map { it.param })
        assertEquals(listOf("AHRS_ORIENTATION"), frameSections(VehicleKind.PLANE, mapOf("Q_ENABLE" to 0f)).flatMap { it.fields }.map { it.param }, "fixed wing: no frame class")
        assertEquals(listOf("Q_FRAME_CLASS", "Q_FRAME_TYPE", "AHRS_ORIENTATION"), frameSections(VehicleKind.PLANE, mapOf("Q_ENABLE" to 2f)).flatMap { it.fields }.map { it.param })
        assertTrue(frameSections(VehicleKind.COPTER, emptyMap()).flatMap { it.fields }.all { it.reboot }, "all @RebootRequired")
        val copter = frameSections(VehicleKind.COPTER, emptyMap()).flatMap { it.fields }
        assertEquals("Hexa", displayValue(copter[0].kind, 2f))
        assertEquals("X", displayValue(copter[1].kind, 1f))
    }

    // ---- vibration and EKF ----

    private fun rows(v: VehicleState, values: Map<String, Float> = emptyMap()) = healthSections(v, values).flatMap { it.rows }.associateBy { it.label }

    /** ArduPilot's vibration guidance: under 30 m/s/s fine, 30 up to 60 a problem, 60 and over fail. Both edges tested. */
    @Test
    fun vibrationIsJudgedAgainstArduPilotsLimits() {
        val r = rows(VehicleState(vibration = Vibration(29.9, 30.0, 60.0, listOf(0, 0, 0))))
        assertEquals(Verdict.PASS, r.getValue("Vibration X").verdict)
        assertEquals(Verdict.WARN, r.getValue("Vibration Y").verdict)
        assertEquals(Verdict.FAIL, r.getValue("Vibration Z").verdict)
        assertEquals("29.9 m/s/s", r.getValue("Vibration X").value)
        assertEquals(Verdict.PASS, r.getValue("Accelerometer clipping").verdict)
        assertEquals(Verdict.WARN, rows(VehicleState(vibration = Vibration(1.0, 1.0, 1.0, listOf(0, 3, 0)))).getValue("Accelerometer clipping").verdict)
        assertEquals(Verdict.UNKNOWN, rows(VehicleState()).getValue("Vibration").verdict, "nothing reported yet")
    }

    /** EKF test ratios: under 0.5 pass, then "Check", fail at FS_EKF_THRESH (0.8 when the vehicle has none). */
    @Test
    fun ekfVariancesFailAtTheFailsafeThreshold() {
        val v = VehicleState(ekfFlags = GOOD, ekfVariances = EkfVariances(velocity = 0.49, posHoriz = 0.5, posVert = 0.79, compass = 0.8, terrain = 0.0))
        val default = rows(v)
        assertEquals(listOf(Verdict.PASS, Verdict.WARN, Verdict.WARN, Verdict.FAIL), listOf("Velocity", "Horizontal position", "Vertical position", "Compass").map { default.getValue("EKF $it").verdict })
        // A strict FS_EKF_THRESH of 0.6 fails the vertical position's 0.79 as well.
        val strict = rows(v, mapOf("FS_EKF_THRESH" to 0.6f))
        assertEquals(Verdict.FAIL, strict.getValue("EKF Vertical position").verdict)
        assertContains(strict.getValue("EKF Velocity").limit, "FS_EKF_THRESH")
        assertEquals(Verdict.FAIL, overallVerdict(healthSections(v, emptyMap())))
    }

    /** EKF_STATUS_FLAGS (ardupilotmega.xml): attitude 1, horizontal velocity 2, relative position 8, absolute position 16. */
    @Test
    fun ekfFlagsNameTheProblem() {
        fun status(flags: UInt) = rows(VehicleState(ekfFlags = flags)).getValue("EKF status")
        assertEquals(Verdict.PASS, status(GOOD).verdict)
        assertEquals(Verdict.PASS, status(1u + 2u + 8u).verdict, "a relative position (VisionNav, optical flow) is enough")
        assertEquals("not initialised", status(GOOD + 1024u).value)
        assertEquals(Verdict.FAIL, status(2u + 16u).verdict, "no attitude")
        assertEquals("GPS glitching", status(GOOD + 32768u).value)
        assertEquals(Verdict.WARN, status(1u + 2u).verdict, "no position")
        assertEquals(Verdict.WARN, status(GOOD + 128u).verdict, "constant position mode")
        assertEquals(Verdict.UNKNOWN, rows(VehicleState()).getValue("EKF status").verdict, "nothing reported yet")
    }

    // ---- ViewModels ----

    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED, vehicleKind = VehicleKind.COPTER))
    private val selected = MutableStateFlow<VehicleId?>(VehicleId(1, 1u))
    private val params = FakeParams(
        mapOf(
            "FRAME_CLASS" to 1f, "FRAME_TYPE" to 1f, "AHRS_ORIENTATION" to 0f,
            "COMPASS_DEV_ID" to internal.toFloat(), "COMPASS_DEV_ID2" to external.toFloat(),
            "COMPASS_PRIO1_ID" to internal.toFloat(), "COMPASS_PRIO2_ID" to external.toFloat(),
        ),
    )
    private val setup = FakeSetup()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    private fun TestScope.setupVm() = SetupViewModel(vehicle, params, selected).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }
    private fun TestScope.calVm() = CalibrationViewModel(vehicle, setup, params, selected).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }

    /** A frame change says before and after that it needs a reboot; the note goes once the link drops (the reboot). */
    @Test
    fun aFrameChangeOffersAReboot() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.FRAME)
        vehicle.value = vehicle.value.copy(rollDeg = 1.24, pitchDeg = -0.5, headingDeg = 87.0)
        runCurrent()
        assertEquals("Live: roll 1.2°, pitch -0.5°, heading 87°", vm.state.value.attitude)
        vm.onFieldClicked("FRAME_CLASS"); vm.onEditTextChanged("2"); vm.onEditSetClicked(); runCurrent()
        assertContains(vm.state.value.edit!!.confirm!!, "from Quad to Hexa")
        assertContains(vm.state.value.edit!!.confirm!!, "after the flight controller reboots")
        vm.onEditConfirmClicked(); runCurrent()
        assertEquals(listOf("FRAME_CLASS" to 2f), params.sets)
        assertEquals("Frame class (FRAME_CLASS) takes effect after the flight controller reboots.", vm.state.value.rebootNote)
        vehicle.value = vehicle.value.copy(connected = false)
        runCurrent()
        assertNull(vm.state.value.rebootNote)
    }

    /** Making the external compass 1st while it is still 2nd: the question says to swap the 2nd too. */
    @Test
    fun aPriorityClashIsNotedBeforeSending() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.COMPASS)
        vm.onFieldClicked("COMPASS_PRIO1_ID"); vm.onEditTextChanged("$external"); vm.onEditSetClicked(); runCurrent()
        val confirm = vm.state.value.edit!!.confirm!!
        assertContains(confirm, "to IST8310, I2C bus 1, address 0x0e")
        assertContains(confirm, "COMPASS_PRIO2_ID holds the same compass")
    }

    /** The health page is read-only: armed is exactly when it matters, so it shows no "disarm first" notice. */
    @Test
    fun theHealthPageWorksArmed() = runTest(dispatcher) {
        val vm = setupVm()
        vm.onPageSelected(SetupPage.HEALTH)
        vehicle.value = vehicle.value.copy(armed = true, vibration = Vibration(70.0, 1.0, 1.0, listOf(0, 0, 0)))
        runCurrent()
        assertNull(vm.state.value.notice)
        assertEquals(Verdict.FAIL, vm.state.value.healthVerdict)
    }

    /** Large Vehicle MagCal: a heading outside 0..<360 isn't sent; a good one is, and its result is shown. */
    @Test
    fun fixedHeadingCompassCalibration() = runTest(dispatcher) {
        val vm = calVm()
        vm.onHeadingChanged("360"); vm.onFixedYawClicked(); runCurrent()
        vm.onHeadingChanged("north"); vm.onFixedYawClicked(); runCurrent()
        assertTrue(setup.sent.isEmpty())
        vm.onHeadingChanged("87,5"); vm.onFixedYawClicked(); runCurrent()
        assertEquals(listOf<SetupCommand>(SetupCommand.FixedYawCompass(87.5f)), setup.sent)
        assertTrue(vm.state.value.compass.result!!.ok)
        assertContains(vm.state.value.compass.result!!.text, "87.5°")

        setup.answer = { SetupResult.Failed("needs a GPS position") }
        vm.onFixedYawClicked(); runCurrent()
        assertFalse(vm.state.value.compass.result!!.ok)
        assertNull(vm.state.value.running)
    }

    private companion object {
        /** Attitude 1 + horizontal velocity 2 + vertical velocity 4 + absolute horizontal position 16. */
        val GOOD = 1u + 2u + 4u + 16u
    }
}
