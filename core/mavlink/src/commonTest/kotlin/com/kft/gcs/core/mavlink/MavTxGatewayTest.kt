package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
import com.divpundir.mavlink.definitions.ardupilotmega.MavCmd as ArduMavCmd
import com.divpundir.mavlink.definitions.ardupilotmega.PlaneMode
import com.divpundir.mavlink.definitions.common.CommandInt
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.FileTransferProtocol
import com.divpundir.mavlink.definitions.common.ManualControl
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MissionAck
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MissionRequestInt
import com.divpundir.mavlink.definitions.common.MissionRequestList
import com.divpundir.mavlink.definitions.common.MissionSetCurrent
import com.divpundir.mavlink.definitions.common.MissionWritePartialList
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.RcChannelsOverride
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.SetAttitudeTarget
import com.divpundir.mavlink.definitions.common.SetMode
import com.divpundir.mavlink.definitions.common.SetPositionTargetGlobalInt
import com.divpundir.mavlink.definitions.common.SetPositionTargetLocalNed
import com.divpundir.mavlink.definitions.common.SetupSigning
import com.kft.gcs.core.mavlink.signing.SigningStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest

/**
 * One test per row of the TX rules table in docs/spec/02_pod_interface_contract.md §3, as tightened by spec S9
 * (no flight actions from the GCS). If a row's rule changes, exactly one of these should fail, and the pass
 * summary must say why under **Safety**.
 */
class MavTxGatewayTest {

    private val locked = PodStatus(lock = PodLockState.LOCKED)

    private fun command(cmd: MavCmd, param2: Float = 0f) = CommandLong(command = MavEnumValue.of(cmd), param2 = param2)
    private fun commandInt(cmd: MavCmd, param2: Float = 0f) = CommandInt(command = MavEnumValue.of(cmd), param2 = param2)
    private fun setMode(mode: UInt) = SetMode(customMode = mode)

    /** Runs the policy exactly as the gateway does. Null = allowed. */
    private fun verdict(message: MavMessage<*>, pod: PodStatus = PodStatus.NoPod, armed: Boolean? = false): String? =
        TxPolicy.check(TxPolicy.classify(message).category, pod, armed)

    @Test
    fun alwaysAllowedEvenWithPodLockedAndAiEnableHigh() {
        val worstCase = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        listOf(copterHeartbeat(), ParamRequestList(), ParamRequestRead(), MissionRequestList(), command(MavCmd.SET_MESSAGE_INTERVAL), commandInt(MavCmd.REQUEST_MESSAGE))
            .forEach { assertNull(verdict(it, worstCase), "$it should always be allowed") }
    }

    /** S12: the KFT login must work in every pod and armed state, or a KFT flight controller ignores the GCS. */
    @Test
    fun kftLoginCommandsAreAlwaysAllowed() {
        val worstCase = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        for (cmd in listOf(MavCmd.USER_1, MavCmd.USER_2)) {
            assertEquals(TxCategory.ALWAYS, TxPolicy.classify(command(cmd)).category, "$cmd")
            assertNull(verdict(command(cmd), worstCase, armed = true), "$cmd as COMMAND_LONG")
        }
        assertEquals(31010u, MavCmd.USER_1.value, "numbers from common.xml, as ardupilotKFT uses them")
        assertEquals(31011u, MavCmd.USER_2.value)
        // Their neighbours stay unlisted: allowing USER_1/2 isn't "allow all user commands".
        assertTrue(verdict(command(MavCmd.USER_3))!!.contains("allowlist"))
    }

    /**
     * Pass 18: PARAM_SET only on the ground. Unknown armed state (no vehicle heard) fails closed, like
     * MISSION_SET_CURRENT. Reading parameters stays allowed while armed (see alwaysAllowed…).
     */
    @Test
    fun paramSetOnlyWhileDisarmed() {
        assertEquals(TxCategory.PARAM_CHANGE_DISARMED, TxPolicy.classify(ParamSet()).category)
        assertNull(verdict(ParamSet(), armed = false), "allowed on the ground")
        assertTrue(verdict(ParamSet(), armed = true)!!.contains("disarmed"), "rejected while armed")
        assertTrue(verdict(ParamSet(), armed = null)!!.contains("disarmed"), "rejected with no vehicle heard")
        assertNull(verdict(ParamRequestRead(), armed = true), "reading is fine in the air")
    }

    /** A heard vehicle as the gateway is told about it; null [armed] = not heard. */
    private fun info(armed: Boolean?, systemId: UByte = 1u, duplicate: Boolean = false) =
        armed?.let { VehicleInfo(systemId, 1u, VehicleKind.COPTER, it, 0u, duplicate) }

    @Test
    fun gatewayRejectsParamSetWhileArmedWithoutWriting() = runTest {
        var armed: Boolean? = false
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed) }
        gateway.attach(link.frameSink, link.signing)
        val sender = gateway.forVehicle(1u)
        assertEquals(TxResult.Sent, sender.send(ParamSet(targetSystem = 1u, paramId = "CAM1_TYPE", paramValue = 1f)))
        armed = true
        val rejected = assertIs<TxResult.Rejected>(sender.send(ParamSet(targetSystem = 1u, paramId = "CAM1_TYPE", paramValue = 0f)))
        assertEquals("PARAM_SET", rejected.message)
        assertEquals(1, link.sent.size, "the armed-time set must not reach the link")
    }

    @Test
    fun missionChangesBlockedWhilePodLockedTerminalOrEngaged() {
        val upload = listOf(MissionCount(count = 3u), MissionItemInt(), MissionClearAll())
        upload.forEach { assertNull(verdict(it, PodStatus(PodLockState.IDLE)), "$it allowed while pod idle") }
        for (state in listOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)) {
            upload.forEach { assertTrue(verdict(it, PodStatus(state))!!.contains("$state"), "$it must be blocked in $state") }
        }
    }

    /**
     * MISSION_SET_CURRENT picks where AUTO starts (resume-from-point). On the ground that's planning; in the air
     * it would jump a flying aircraft to another item, so it's a flight action (S9). Unknown armed state fails closed.
     */
    @Test
    fun missionSetCurrentOnlyWhileDisarmed() {
        val setCurrent = MissionSetCurrent(seq = 4u)
        assertNull(verdict(setCurrent, armed = false), "allowed on the ground")
        assertTrue(verdict(setCurrent, armed = true)!!.contains("disarmed"), "rejected while armed")
        assertTrue(verdict(setCurrent, armed = null)!!.contains("disarmed"), "rejected with no vehicle heard")
        // It's still a mission change, so a locked pod blocks it even on the ground.
        assertTrue(verdict(setCurrent, PodStatus(PodLockState.LOCKED), armed = false)!!.contains("LOCKED"))
        // The armed rule is only for MISSION_SET_CURRENT: uploads stay allowed while armed (the Plan screen asks first).
        assertNull(verdict(MissionCount(count = 3u), armed = true))
    }

    @Test
    fun gatewayReadsArmedStateAtSendTime() = runTest {
        var armed: Boolean? = false
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed) }
        gateway.attach(link.frameSink, link.signing)
        val sender = gateway.forVehicle(1u)
        assertEquals(TxResult.Sent, sender.send(MissionSetCurrent(targetSystem = 1u, seq = 2u)))
        armed = true
        assertIs<TxResult.Rejected>(sender.send(MissionSetCurrent(targetSystem = 1u, seq = 2u)))
        assertEquals(1, link.sent.size, "the armed-time request must not reach the link")
    }

    /** S9: the pilot arms, takes off, changes mode, returns and lands on the RC. The GCS never does, in any pod state. */
    @Test
    fun flightActionsAreRejectedAsImmediateCommands() {
        val flightCommands = listOf(
            MavCmd.COMPONENT_ARM_DISARM, MavCmd.NAV_TAKEOFF, MavCmd.DO_SET_MODE, MavCmd.NAV_RETURN_TO_LAUNCH,
            MavCmd.NAV_LAND, MavCmd.MISSION_START, MavCmd.DO_CHANGE_SPEED, MavCmd.DO_PAUSE_CONTINUE,
        )
        // Every mode number is refused, including the ones the pod contract calls safe-direction (RTL, LOITER, LAND,
        // BRAKE) and GUIDED. Copter numbers are used; the rule doesn't depend on the number at all.
        val modes = listOf(CopterMode.GUIDED, CopterMode.AUTO, CopterMode.RTL, CopterMode.LOITER, CopterMode.LAND, CopterMode.BRAKE)
            .map { it.value } + PlaneMode.QRTL.value
        val messages = flightCommands.flatMap { listOf(command(it), commandInt(it)) } +
            modes.map { setMode(it) } +
            modes.map { command(MavCmd.DO_SET_MODE, param2 = it.toFloat()) }

        for (pod in listOf(PodStatus.NoPod, PodStatus(PodLockState.ENGAGE, aiEnableHigh = true))) {
            messages.forEach { assertTrue(verdict(it, pod)!!.contains("S9"), "$it must be rejected (pod $pod)") }
        }
    }

    /** S9's other half: the same commands are fine inside a mission, because the pilot starts the mission on the RC. */
    @Test
    fun flightCommandsAreAllowedAsMissionItems() {
        listOf(MavCmd.NAV_TAKEOFF, MavCmd.NAV_LAND, MavCmd.NAV_RETURN_TO_LAUNCH, MavCmd.DO_CHANGE_SPEED)
            .forEach { assertNull(verdict(MissionItemInt(command = MavEnumValue.of(it))), "$it as a mission item") }
    }

    @Test
    fun guidanceSetpointsAndRcOverrideAreNeverSent() {
        val never = listOf(
            SetPositionTargetLocalNed(), SetPositionTargetGlobalInt(), SetAttitudeTarget(),
            RcChannelsOverride(), ManualControl(), command(MavCmd.DO_REPOSITION), command(MavCmd.DO_SET_SERVO),
        )
        never.forEach { assertTrue(verdict(it, PodStatus.NoPod)!!.startsWith("never"), "$it must never be sent") }
    }

    @Test
    fun unlistedMessagesAndUnknownVehicleModesAreRejected() {
        assertTrue(verdict(FileTransferProtocol())!!.contains("allowlist"))
        assertTrue(verdict(command(MavCmd.DO_SET_ROI))!!.contains("allowlist"))
        assertTrue(verdict(commandInt(MavCmd.DO_MOTOR_TEST))!!.contains("allowlist"))
    }

    @Test
    fun gatewayRefusesWithoutWritingAndReportsTheRejection() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(locked))
        gateway.attach(link.frameSink, link.signing)

        gateway.rejections.test {
            val result = gateway.forVehicle(1u).send(MissionCount(targetSystem = 1u, count = 5u))
            assertIs<TxResult.Rejected>(result)
            assertEquals(result, awaitItem(), "every rejection is published for the log")
        }
        assertTrue(link.sent.isEmpty(), "a rejected message must not reach the link")
    }

    @Test
    fun gatewayReadsPodStateAtSendTime() = runTest {
        val pod = MutableStateFlow(PodStatus.NoPod)
        val gateway = MavTxGateway(pod)
        val sender = gateway.forVehicle(1u)
        assertEquals(TxResult.NotConnected, sender.send(MissionCount(targetSystem = 1u, count = 5u)))

        val link = FakeMavConnection()
        gateway.attach(link.frameSink, link.signing)
        assertEquals(TxResult.Sent, sender.send(MissionCount(targetSystem = 1u, count = 5u)))

        // A lock that arrives mid-upload blocks the very next mission message.
        pod.value = locked
        assertIs<TxResult.Rejected>(sender.send(MissionItemInt(targetSystem = 1u)))
        assertEquals(1, link.sent.size)

        gateway.detach()
        assertEquals(TxResult.NotConnected, gateway.sendHeartbeat(copterHeartbeat()))
    }

    // ---- ADR-005 §3: every send names its vehicle ----

    /** A message together with "send it through this sender": send() needs the message's own type, which a list loses. */
    private class Sendable(val message: MavMessage<*>, val sendVia: suspend (MavSender) -> TxResult)

    private fun <T : MavMessage<T>> sendable(message: T) = Sendable(message) { it.send(message) }

    /** One of every allowlisted message that has a target, addressed to [system]. */
    private fun targeted(system: UByte): List<Sendable> = listOf(
        sendable(ParamRequestRead(targetSystem = system)), sendable(ParamRequestList(targetSystem = system)),
        sendable(ParamSet(targetSystem = system)), sendable(RequestDataStream(targetSystem = system)),
        sendable(MissionRequestList(targetSystem = system)), sendable(MissionRequestInt(targetSystem = system)),
        sendable(MissionAck(targetSystem = system)), sendable(MissionCount(targetSystem = system)),
        sendable(MissionItemInt(targetSystem = system)), sendable(MissionClearAll(targetSystem = system)),
        sendable(MissionWritePartialList(targetSystem = system)), sendable(MissionSetCurrent(targetSystem = system)),
        sendable(CommandLong(targetSystem = system, command = MavEnumValue.of(MavCmd.REQUEST_MESSAGE))),
        sendable(CommandInt(targetSystem = system, command = MavEnumValue.of(MavCmd.SET_MESSAGE_INTERVAL))),
    )

    /** The gateway reads the target from the message itself; a type missing from the lookup could never be sent. */
    @Test
    fun everyAllowlistedTargetedMessageHasItsTargetRead() {
        targeted(7u).forEach { assertEquals(7u.toUByte(), TxPolicy.targetSystem(it.message), "${it.message}") }
        assertNull(TxPolicy.targetSystem(copterHeartbeat()), "a heartbeat has no target")
    }

    /** The cross-vehicle mistake: a message built for vehicle 1 handed to vehicle 2's sender. Nothing reaches the link. */
    @Test
    fun aMessageAddressedToAnotherVehicleIsNeverWritten() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }
        gateway.attach(link.frameSink, link.signing)
        for (m in targeted(1u)) {
            val result = assertIs<TxResult.Rejected>(m.sendVia(gateway.forVehicle(2u)), "${m.message}")
            assertTrue("system 1" in result.reason && "system 2" in result.reason, result.reason)
        }
        assertTrue(link.sent.isEmpty(), "none of them may reach the link")
        // The same messages through the right sender all go out.
        targeted(1u).forEach { assertEquals(TxResult.Sent, it.sendVia(gateway.forVehicle(1u)), "${it.message}") }
    }

    /** target_system 0 means "every system": on a link with three drones, all three would obey. */
    @Test
    fun aBroadcastTargetIsRefused() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }
        gateway.attach(link.frameSink, link.signing)
        targeted(0u).forEach { assertIs<TxResult.Rejected>(it.sendVia(gateway.forVehicle(1u)), "${it.message}") }
        assertTrue(link.sent.isEmpty())
    }

    /** Vehicle 1 is flying, vehicle 2 is on the ground: each sender is judged by its own vehicle's armed flag. */
    @Test
    fun theArmedRuleUsesTheNamedVehiclesOwnFlag() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = it == 1u.toUByte(), systemId = it) }
        gateway.attach(link.frameSink, link.signing)
        assertIs<TxResult.Rejected>(gateway.forVehicle(1u).send(ParamSet(targetSystem = 1u)), "vehicle 1 is armed")
        assertEquals(TxResult.Sent, gateway.forVehicle(2u).send(ParamSet(targetSystem = 2u)), "vehicle 2 is disarmed")
        assertEquals(listOf<UByte>(2u), link.sent.filterIsInstance<ParamSet>().map { it.targetSystem })
    }

    /** ADR-005 §4: with two autopilots on one system id, even a read request is refused: both would answer. */
    @Test
    fun nothingIsSentToADuplicatedSystemId() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it, duplicate = true) }
        gateway.attach(link.frameSink, link.signing)
        for (m in targeted(1u)) {
            val result = assertIs<TxResult.Rejected>(m.sendVia(gateway.forVehicle(1u)), "${m.message}")
            assertTrue(result.reason.contains("two vehicles"), result.reason)
        }
        assertTrue(link.sent.isEmpty())
    }

    /** Two links, both with a system 1: a sender writes to its own link only. */
    @Test
    fun aSenderWritesOnlyToItsOwnLink() = runTest {
        val (linkA, linkB) = FakeMavConnection() to FakeMavConnection()
        val gatewayA = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }.apply { attach(linkA.frameSink, linkA.signing) }
        MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }.apply { attach(linkB.frameSink, linkB.signing) }
        assertEquals(TxResult.Sent, gatewayA.forVehicle(1u).send(MissionCount(targetSystem = 1u, count = 2u)))
        assertEquals(1, linkA.sent.size)
        assertTrue(linkB.sent.isEmpty())
    }

    /**
     * Signing S3 / PRD FR-14: SETUP_SIGNING carries a key in clear, so the GCS never sends it, in any pod state or armed
     * state, and not even to a vehicle it signs for. Keys are provisioned over USB by the provisioning tool.
     */
    @Test
    fun setupSigningIsNeverSent() = runTest {
        assertEquals(256u, SetupSigning().instanceCompanion.id, "common.xml SETUP_SIGNING")
        val setup = SetupSigning(targetSystem = 1u, targetComponent = 1u, secretKey = List(32) { 0x42u }, initialTimestamp = 1uL)
        assertEquals(TxClassification(TxCategory.NEVER, "SETUP_SIGNING"), TxPolicy.classify(setup))
        for (lock in PodLockState.entries) for (ai in listOf(false, true)) for (armed in listOf(true, false, null)) {
            assertTrue(verdict(setup, PodStatus(lock, ai), armed)!!.startsWith("never"), "pod $lock ai $ai armed $armed")
        }

        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }
        gateway.attach(link.frameSink, link.signing)
        link.signing.value = mapOf(1u.toUByte() to SigningStatus.Signed("drone-a"))
        gateway.rejections.test {
            assertEquals("SETUP_SIGNING", assertIs<TxResult.Rejected>(gateway.forVehicle(1u).send(setup)).message)
            assertEquals("SETUP_SIGNING", awaitItem().message)
        }
        assertTrue(link.wire.size == 0L, "no byte of it reaches the link")
    }

    /** A COMMAND_LONG with param1..7 as listed (missing ones 0). */
    private fun setup(cmd: UInt, vararg p: Float) = CommandLong(
        command = MavEnumValue.fromValue(cmd), targetSystem = 1u,
        param1 = p.getOrElse(0) { 0f }, param2 = p.getOrElse(1) { 0f }, param3 = p.getOrElse(2) { 0f }, param4 = p.getOrElse(3) { 0f },
        param5 = p.getOrElse(4) { 0f }, param6 = p.getOrElse(5) { 0f }, param7 = p.getOrElse(6) { 0f },
    )

    private val calibration = MavCmd.PREFLIGHT_CALIBRATION.value
    private val reboot = MavCmd.PREFLIGHT_REBOOT_SHUTDOWN.value
    private val accelPos = ArduMavCmd.ACCELCAL_VEHICLE_POS.value
    private val startMag = ArduMavCmd.DO_START_MAG_CAL.value
    private val cancelMag = ArduMavCmd.DO_CANCEL_MAG_CAL.value

    /**
     * The Setup tab's commands, exactly as `core:vehicle` builds them (Setup.kt), each checked against the KFT fork's
     * handling (TxPolicy SETUP_PATTERNS). Allowed only on the ground; an unknown armed state fails closed. The pod's state
     * doesn't matter: nothing here moves the aircraft.
     */
    @Test
    fun setupCommandsOnlyWhileDisarmed() {
        assertEquals(241u, calibration, "common.xml")
        assertEquals(246u, reboot, "common.xml")
        assertEquals(listOf(42429u, 42424u, 42426u), listOf(accelPos, startMag, cancelMag), "ardupilotmega.xml")
        val allowed = listOf(
            setup(calibration, 0f, 0f, 1f),               // barometer
            setup(calibration, 0f, 0f, 0f, 1f),           // RC calibration flag on
            setup(calibration),                           // RC calibration flag off
            setup(calibration, 0f, 0f, 0f, 0f, 1f),       // accelerometer, 6 positions
            setup(calibration, 0f, 0f, 0f, 0f, 2f),       // level
            setup(calibration, 0f, 0f, 0f, 0f, 4f),       // simple accelerometer
            setup(startMag, 0f, 0f, 1f),                  // every compass, autosave
            setup(cancelMag),
            setup(reboot, 1f),
        ) + (1..6).map { setup(accelPos, it.toFloat()) }
        val worstPod = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        for (c in allowed) {
            assertEquals(TxCategory.SETUP_DISARMED, TxPolicy.classify(c).category, "$c")
            assertNull(verdict(c, worstPod, armed = false), "$c on the ground")
            assertTrue(verdict(c, armed = true)!!.contains("disarmed"), "$c while armed")
            assertTrue(verdict(c, armed = null)!!.contains("disarmed"), "$c with no vehicle heard")
        }
    }

    /**
     * The same command ids with any other numbers are not on the allowlist. Above all compassmot (param6 = 1), which spins
     * a Copter's motors, an autoreboot after the compass calibration, and ArduPilot's reboot magic numbers.
     */
    @Test
    fun otherSetupParametersAreNotOnTheAllowlist() {
        val refused = listOf(
            setup(calibration, 0f, 0f, 0f, 0f, 0f, 1f),   // compassmot
            setup(calibration, 1f),                       // gyro only
            setup(calibration, 0f, 76f),                  // force the compass calibration valid
            setup(calibration, 0f, 0f, 0f, 0f, 76f),      // force the accel calibration valid
            setup(calibration, 0f, 0f, 1f, 1f),           // two calibrations in one
            setup(calibration, 0f, 0f, 0f, 0f, 0f, 0f, 1f),
            setup(startMag, 0f, 0f, 1f, 0f, 1f),          // autoreboot
            setup(startMag, 0f, 1f, 1f),                  // retry
            setup(accelPos, 0f), setup(accelPos, 7f), setup(accelPos, 16777215f), setup(accelPos, 1.5f),
            setup(reboot, 42f, 24f, 71f, 93f),             // lock-up the main loop
            setup(reboot, 1f, 0f, 0f, 0f, 0f, 20190226f),  // forced reboot while armed
            setup(reboot, 2f),                            // shutdown
            setup(reboot, 3f),                            // reboot to bootloader
        )
        for (c in refused) {
            assertEquals(TxCategory.UNLISTED, TxPolicy.classify(c).category, "$c")
            assertTrue(verdict(c, armed = false)!!.contains("allowlist"), "$c")
        }
    }

    /** COMMAND_INT carries param5..7 as x, y, z: the same patterns apply, so compassmot can't slip through that way. */
    @Test
    fun setupPatternsApplyToCommandIntToo() {
        fun int(cmd: UInt, p1: Float = 0f, x: Int = 0, y: Int = 0) = CommandInt(command = MavEnumValue.fromValue(cmd), param1 = p1, x = x, y = y)
        assertEquals(TxCategory.SETUP_DISARMED, TxPolicy.classify(int(calibration, x = 1)).category, "accelerometer")
        assertEquals(TxCategory.UNLISTED, TxPolicy.classify(int(calibration, y = 1)).category, "compassmot")
        assertEquals(TxCategory.UNLISTED, TxPolicy.classify(int(reboot, 1f, y = 20190226)).category, "forced reboot")
    }

    @Test
    fun gatewayRefusesACalibrationWhileArmedWithoutWriting() = runTest {
        var armed: Boolean? = false
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed) }
        gateway.attach(link.frameSink, link.signing)
        val sender = gateway.forVehicle(1u)
        assertEquals(TxResult.Sent, sender.send(setup(startMag, 0f, 0f, 1f)))
        armed = true
        assertEquals("MAV_CMD_DO_START_MAG_CAL", assertIs<TxResult.Rejected>(sender.send(setup(startMag, 0f, 0f, 1f))).message)
        assertIs<TxResult.Rejected>(sender.send(setup(calibration, 0f, 0f, 0f, 0f, 0f, 1f)))
        assertEquals(1, link.sent.size, "only the disarmed start reaches the link")
    }
}
