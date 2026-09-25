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
import com.divpundir.mavlink.definitions.common.MissionClearAll
import com.divpundir.mavlink.definitions.common.MissionCount
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.MissionRequestList
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.RcChannelsOverride
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
    private fun verdict(message: MavMessage<*>, pod: PodStatus = PodStatus.NoPod): String? =
        TxPolicy.check(TxPolicy.classify(message).category, pod)

    @Test
    fun alwaysAllowedEvenWithPodLockedAndAiEnableHigh() {
        val worstCase = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        listOf(copterHeartbeat(), ParamRequestList(), MissionRequestList(), command(MavCmd.SET_MESSAGE_INTERVAL), commandInt(MavCmd.REQUEST_MESSAGE))
            .forEach { assertNull(verdict(it, worstCase), "$it should always be allowed") }
    }

    @Test
    fun paramSetAllowedWithoutPod() {
        assertNull(verdict(ParamSet()))
    }

    @Test
    fun missionChangesBlockedWhilePodLockedTerminalOrEngaged() {
        val upload = listOf(MissionCount(count = 3u), MissionItemInt(), MissionClearAll())
        upload.forEach { assertNull(verdict(it, PodStatus(PodLockState.IDLE)), "$it allowed while pod idle") }
        for (state in listOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)) {
            upload.forEach { assertTrue(verdict(it, PodStatus(state))!!.contains("$state"), "$it must be blocked in $state") }
        }
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
        gateway.attach(link)

        gateway.rejections.test {
            val result = gateway.send(MissionCount(count = 5u))
            assertIs<TxResult.Rejected>(result)
            assertEquals(result, awaitItem(), "every rejection is published for the log")
        }
        assertTrue(link.sent.isEmpty(), "a rejected message must not reach the link")
    }

    @Test
    fun gatewayReadsPodStateAtSendTime() = runTest {
        val pod = MutableStateFlow(PodStatus.NoPod)
        val gateway = MavTxGateway(pod)
        assertEquals(TxResult.NotConnected, gateway.send(copterHeartbeat()))

        val link = FakeMavConnection()
        gateway.attach(link)
        assertEquals(TxResult.Sent, gateway.send(MissionCount(count = 5u)))

        // A lock that arrives mid-upload blocks the very next mission message.
        pod.value = locked
        assertIs<TxResult.Rejected>(gateway.send(MissionItemInt()))
        assertEquals(1, link.sent.size)

        gateway.detach()
        assertEquals(TxResult.NotConnected, gateway.send(copterHeartbeat()))
    }
}
