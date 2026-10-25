package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.ardupilotmega.MagCalProgress
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MagCalReport
import com.divpundir.mavlink.definitions.common.MagCalStatus
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.MavSeverity
import com.divpundir.mavlink.definitions.common.RcChannels
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.standard.AutopilotVersion
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/** The Setup tab's commands and reports against a scripted flight controller, in virtual time. */
class SetupTest {
    private val fc = FakeFc()
    private val link = MutableStateFlow<LinkState>(LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(1u, 1u, VehicleKind.COPTER, false, 0u), LinkStats()))
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))
    private val repo = DefaultVehicleSetupRepository(fc.frames, link, vehicle, fc)
    private val target = MissionProtocol.Target(1u, 1u)

    private fun CommandLong.params() = listOf(param1, param2, param3, param4, param5, param6, param7)

    /**
     * The numbers, written out from the KFT fork's handlers (see Setup.kt `toCommand`), not from the code under test. The
     * gateway allows exactly these (MavTxGatewayTest.setupCommandsOnlyWhileDisarmed holds the same lists).
     */
    @Test
    fun eachCommandCarriesArduPilotsNumbers() {
        val expected = mapOf<SetupCommand, Pair<UInt, List<Float>>>(
            SetupCommand.AccelStart to (241u to listOf(0f, 0f, 0f, 0f, 1f, 0f, 0f)),
            SetupCommand.AccelPlaced(AccelPosition.NOSE_DOWN) to (42429u to listOf(4f, 0f, 0f, 0f, 0f, 0f, 0f)),
            SetupCommand.Level to (241u to listOf(0f, 0f, 0f, 0f, 2f, 0f, 0f)),
            SetupCommand.SimpleAccel to (241u to listOf(0f, 0f, 0f, 0f, 4f, 0f, 0f)),
            SetupCommand.Baro to (241u to listOf(0f, 0f, 1f, 0f, 0f, 0f, 0f)),
            SetupCommand.CompassStart to (42424u to listOf(0f, 0f, 1f, 0f, 0f, 0f, 0f)),
            SetupCommand.CompassCancel to (42426u to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            SetupCommand.RcCalibrating(true) to (241u to listOf(0f, 0f, 0f, 1f, 0f, 0f, 0f)),
            SetupCommand.RcCalibrating(false) to (241u to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            SetupCommand.Reboot to (246u to listOf(1f, 0f, 0f, 0f, 0f, 0f, 0f)),
            SetupCommand.RunPreArmChecks to (401u to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
            // FIXED_MAG_CAL_YAW: yaw, compass mask 0 = all, lat 0 / lon 0 = the FC's own position (mag_cal_fixed_yaw).
            SetupCommand.FixedYawCompass(87.5f) to (42006u to listOf(87.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
        )
        for ((command, wire) in expected) {
            val m = command.toCommand(target).message
            assertEquals(wire, m.command.value to m.params(), "$command")
            assertEquals(1u.toUByte() to 1u.toUByte(), m.targetSystem to m.targetComponent)
        }
    }

    /** A calibration ACKs only when it's done (seconds later); it must not be resent meanwhile, or it would start twice. */
    @Test
    fun aCalibrationIsSentOnceAndWaitsForItsSlowAck() = runTest {
        launch { delay(8.seconds); fc.emit(ackFor(fc.sentOf<CommandLong>().single())) }
        assertEquals(SetupResult.Done, repo.run(SetupCommand.AccelStart))
        assertEquals(1, fc.sent.size)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun noAnswerGivesUpAfterArduPilotsOwn30Seconds() = runTest {
        assertEquals(SetupResult.Failed("No answer from the vehicle."), repo.run(SetupCommand.Level))
        assertEquals(1, fc.sent.size)
        assertEquals(30_000L, testScheduler.currentTime)
    }

    /** ArduPilot answers the RC flag with UNSUPPORTED even though it set it (see `toSetupResult`); anything else, no. */
    @Test
    fun unsupportedMeansDoneOnlyForTheRcFlag() = runTest {
        fc.reply = { listOf(ackFor(it as CommandLong, MavResult.UNSUPPORTED)) }
        assertEquals(SetupResult.Done, repo.run(SetupCommand.RcCalibrating(true)))
        assertEquals(SetupResult.Done, repo.run(SetupCommand.RcCalibrating(false)))
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.Baro)).reason, "doesn't support")
        fc.reply = { listOf(ackFor(it as CommandLong, MavResult.FAILED)) }
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.CompassStart)).reason, "refused")
        // A failed fixed-heading calibration names what it needs (a position, healthy compasses).
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.FixedYawCompass(10f))).reason, "GPS position")
    }

    /** On a Plane with an airspeed sensor the baro calibration answers IN_PROGRESS, then the final result. */
    @Test
    fun inProgressWaitsForTheFinalAck() = runTest {
        fc.reply = { listOf(ackFor(it as CommandLong, MavResult.IN_PROGRESS)) }
        launch { delay(4.seconds); fc.emit(ackFor(fc.sentOf<CommandLong>().single())) }
        assertEquals(SetupResult.Done, repo.run(SetupCommand.Baro))
        assertEquals(1, fc.sent.size)
    }

    /**
     * The pre-arm run ACKs at once, so a lost ACK is resent like any quick command; TEMPORARILY_REJECTED is ArduPilot's
     * answer while armed (handle_command_run_prearm_checks) and is worded so.
     */
    @Test
    fun preArmRunIsResentAndExplainsAnArmedRefusal() = runTest {
        fc.reply = { emptyList() }
        assertEquals(SetupResult.Failed("No answer from the vehicle."), repo.run(SetupCommand.RunPreArmChecks))
        assertEquals(CommandProtocol.ATTEMPTS, fc.sent.size, "a quick command: the usual resends")
        fc.reply = { listOf(ackFor(it as CommandLong, MavResult.TEMPORARILY_REJECTED)) }
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.RunPreArmChecks)).reason, "armed")
        fc.reply = { listOf(ackFor(it as CommandLong)) }
        assertEquals(SetupResult.Done, repo.run(SetupCommand.RunPreArmChecks))
    }

    @Test
    fun aGatewayRefusalSaysWhy() = runTest {
        fc.txResult = TxResult.Rejected("MAV_CMD_PREFLIGHT_CALIBRATION", "calibrations and reboots only while the vehicle is disarmed")
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.Baro)).reason, "disarmed")
    }

    /** S12: nothing goes out before the KFT login allows it, as for parameters. */
    @Test
    fun commandsWaitForTheLogin() = runTest {
        vehicle.value = VehicleState(connected = true, login = KftLoginStatus.LOGGING_IN)
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.Reboot)).reason, "wait for the login")
        link.value = LinkState.Disconnected
        assertContains(assertIs<SetupResult.Failed>(repo.run(SetupCommand.Reboot)).reason, "No vehicle")
        assertTrue(fc.sent.isEmpty())
    }

    @Test
    fun reportsComeFromTheAutopilotOnly() = runTest {
        val seen = mutableListOf<SetupReport>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.reports.toList(seen) }
        fun accelPos(p: Float) = CommandLong(command = MavEnumValue.fromValue(42429u), param1 = p)
        fc.emit(accelPos(2f))
        fc.emit(accelPos(16777215f))
        fc.emit(accelPos(16777216f))
        fc.emit(MagCalProgress(compassId = 1u, calMask = 3u, calStatus = MavEnumValue.of(MagCalStatus.MAG_CAL_RUNNING_STEP_TWO), attempt = 1u, completionPct = 42u))
        fc.emit(MagCalReport(compassId = 0u, calStatus = MavEnumValue.of(MagCalStatus.MAG_CAL_SUCCESS), autosaved = 1u, fitness = 8.5f))
        fc.emit(Statustext(severity = MavEnumValue.of(MavSeverity.CRITICAL), text = "Calibration successful"))
        fc.emit(Statustext(text = "KFTCH1:abcdef"))                                // login protocol, not for the operator
        fc.emit(accelPos(3f), componentId = 154u)                                  // a gimbal's frame: not the autopilot
        assertEquals(
            listOf(
                SetupReport.AccelRequest(AccelPosition.LEFT),
                SetupReport.AccelDone(true),
                SetupReport.AccelDone(false),
                SetupReport.CompassProgress(1, 42, CompassCalStatus.RUNNING_2, 1, mask = 3),
                SetupReport.CompassResult(0, CompassCalStatus.SUCCESS, 8.5f, saved = true),
                SetupReport.Text(StatusMessage("Calibration successful", Severity.ERROR)),
            ),
            seen,
        )
    }

    /** RC_CHANNELS: only the channels the receiver provides (chancount), in µs. */
    @Test
    fun rcChannelsKeepChancountChannels() {
        val rc = RcChannels(chancount = 3u, chan1Raw = 1500u, chan2Raw = 1100u, chan3Raw = 1900u, chan4Raw = 1500u)
        assertEquals(listOf(1500, 1100, 1900), VehicleState().reduce(rc).rcChannels)
        assertEquals(emptyList<Int>(), VehicleState().reduce(rc.copy(chancount = 0u)).rcChannels, "no RC input")
    }

    /**
     * AUTOPILOT_VERSION as ArduPilot fills it, decoded by hand: flight_sw_version 0x040603FF = 4.6.3 official;
     * board_version 140 << 16 = 9 175 040 (APJ_BOARD_ID 140, CubeOrange); USB 0x2DAE:0x1016; a 12-byte STM32 UID whose
     * last byte is 0x00, zero-padded to 18: the 12 bytes all show, the padding doesn't.
     */
    @Test
    fun autopilotIdentityDecodesArduPilotsFields() {
        val uid = listOf(0x3a, 0x00, 0x1f, 0x00, 0x0d, 0x51, 0x33, 0x30, 0x34, 0x38, 0x36, 0x00).map { it.toUByte() }
        val m = AutopilotVersion(
            flightSwVersion = 0x040603FFu, boardVersion = 9_175_040u, vendorId = 0x2DAEu, productId = 0x1016u,
            flightCustomVersion = "d91a40ef".map { it.code.toUByte() }, uid2 = uid + List(6) { 0u },
        )
        assertEquals(AutopilotIdentity("4.6.3", "d91a40ef", 140, "2dae:1016", "3a001f000d51333034383600"), autopilotIdentity(m))
        assertEquals(AutopilotIdentity("4.6.3", null, null, null, null), autopilotIdentity(AutopilotVersion(flightSwVersion = 0x040603FFu)), "SITL-like: nothing set")
    }
}
