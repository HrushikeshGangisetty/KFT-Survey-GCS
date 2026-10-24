package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
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
        gateway.attach(link.frameSink)
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
        gateway.attach(link.frameSink)
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
        gateway.attach(link.frameSink)

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
        gateway.attach(link.frameSink)
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
        gateway.attach(link.frameSink)
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
        gateway.attach(link.frameSink)
        targeted(0u).forEach { assertIs<TxResult.Rejected>(it.sendVia(gateway.forVehicle(1u)), "${it.message}") }
        assertTrue(link.sent.isEmpty())
    }

    /** Vehicle 1 is flying, vehicle 2 is on the ground: each sender is judged by its own vehicle's armed flag. */
    @Test
    fun theArmedRuleUsesTheNamedVehiclesOwnFlag() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = it == 1u.toUByte(), systemId = it) }
        gateway.attach(link.frameSink)
        assertIs<TxResult.Rejected>(gateway.forVehicle(1u).send(ParamSet(targetSystem = 1u)), "vehicle 1 is armed")
        assertEquals(TxResult.Sent, gateway.forVehicle(2u).send(ParamSet(targetSystem = 2u)), "vehicle 2 is disarmed")
        assertEquals(listOf<UByte>(2u), link.sent.filterIsInstance<ParamSet>().map { it.targetSystem })
    }

    /** ADR-005 §4: with two autopilots on one system id, even a read request is refused: both would answer. */
    @Test
    fun nothingIsSentToADuplicatedSystemId() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it, duplicate = true) }
        gateway.attach(link.frameSink)
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
        val gatewayA = MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }.apply { attach(linkA.frameSink) }
        MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { info(armed = false, systemId = it) }.apply { attach(linkB.frameSink) }
        assertEquals(TxResult.Sent, gatewayA.forVehicle(1u).send(MissionCount(targetSystem = 1u, count = 2u)))
        assertEquals(1, linkA.sent.size)
        assertTrue(linkB.sent.isEmpty())
    }
}
