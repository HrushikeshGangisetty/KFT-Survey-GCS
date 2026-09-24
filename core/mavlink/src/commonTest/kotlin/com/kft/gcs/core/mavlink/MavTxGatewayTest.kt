package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.ardupilotmega.CopterMode
import com.divpundir.mavlink.definitions.ardupilotmega.PlaneMode
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
 * One test per row of the TX rules table in docs/spec/02_pod_interface_contract.md §3. If a row's rule changes,
 * exactly one of these should fail, and the pass summary must say why under **Safety**.
 */
class MavTxGatewayTest {

    private val locked = PodStatus(lock = PodLockState.LOCKED)
    private val aiEnable = PodStatus(lock = PodLockState.IDLE, aiEnableHigh = true)

    private fun command(cmd: MavCmd, param2: Float = 0f) = CommandLong(command = MavEnumValue.of(cmd), param2 = param2)
    private fun setMode(mode: UInt) = SetMode(customMode = mode)

    /** Runs the policy exactly as the gateway does. Null = allowed. */
    private fun verdict(message: MavMessage<*>, pod: PodStatus = PodStatus.NoPod, vehicle: VehicleKind = VehicleKind.COPTER): String? =
        TxPolicy.check(TxPolicy.classify(message, vehicle).category, pod)

    @Test
    fun alwaysAllowedEvenWithPodLockedAndAiEnableHigh() {
        val worstCase = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        listOf(copterHeartbeat(), ParamRequestList(), MissionRequestList(), command(MavCmd.SET_MESSAGE_INTERVAL))
            .forEach { assertNull(verdict(it, worstCase), "$it should always be allowed") }
    }

    @Test
    fun operatorCommandsAllowedWithoutPod() {
        listOf(command(MavCmd.COMPONENT_ARM_DISARM, 1f), command(MavCmd.NAV_TAKEOFF), ParamSet(), setMode(CopterMode.AUTO.value))
            .forEach { assertNull(verdict(it), "$it should be allowed with no pod") }
    }

    @Test
    fun missionChangesBlockedWhilePodLockedTerminalOrEngaged() {
        val upload = listOf(MissionCount(count = 3u), MissionItemInt(), MissionClearAll())
        upload.forEach { assertNull(verdict(it, PodStatus(PodLockState.IDLE)), "$it allowed while pod idle") }
        for (state in listOf(PodLockState.LOCKED, PodLockState.TERMINAL, PodLockState.ENGAGE)) {
            upload.forEach { assertTrue(verdict(it, PodStatus(state))!!.contains("$state"), "$it must be blocked in $state") }
        }
    }

    @Test
    fun safeDirectionModesAlwaysAllowedOnCopterAndPlane() {
        val copterSafe = listOf(CopterMode.RTL, CopterMode.LOITER, CopterMode.LAND, CopterMode.BRAKE).map { it.value }
        val planeSafe = listOf(PlaneMode.RTL, PlaneMode.LOITER, PlaneMode.QLAND, PlaneMode.QRTL).map { it.value }
        val worstCase = PodStatus(PodLockState.ENGAGE, aiEnableHigh = true)
        copterSafe.forEach { assertNull(verdict(setMode(it), worstCase, VehicleKind.COPTER), "copter mode $it") }
        planeSafe.forEach { assertNull(verdict(setMode(it), worstCase, VehicleKind.PLANE), "plane mode $it") }
        // The command forms of RTL and LAND are safe-direction too, and so is DO_SET_MODE with RTL in param2.
        assertNull(verdict(command(MavCmd.NAV_RETURN_TO_LAUNCH), worstCase))
        assertNull(verdict(command(MavCmd.NAV_LAND), worstCase))
        assertNull(verdict(command(MavCmd.DO_SET_MODE, param2 = CopterMode.RTL.value.toFloat()), worstCase))
    }

    @Test
    fun enteringGuidedBlockedWhileAiEnableHigh() {
        // GUIDED is 4 on Copter but 15 on Plane: the vehicle kind decides which number means GUIDED.
        assertNull(verdict(setMode(CopterMode.GUIDED.value), vehicle = VehicleKind.COPTER))
        assertTrue(verdict(setMode(CopterMode.GUIDED.value), aiEnable, VehicleKind.COPTER)!!.contains("GUIDED"))
        assertTrue(verdict(setMode(PlaneMode.GUIDED.value), aiEnable, VehicleKind.PLANE)!!.contains("GUIDED"))
        assertTrue(verdict(command(MavCmd.DO_SET_MODE, CopterMode.GUIDED.value.toFloat()), aiEnable)!!.contains("GUIDED"))
        // Plane mode 4 is ACRO, not GUIDED, so it isn't caught by the GUIDED rule.
        assertNull(verdict(setMode(4u), aiEnable, VehicleKind.PLANE))
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
        // Before the first heartbeat we don't know the vehicle type, so a mode number could secretly be GUIDED.
        assertTrue(verdict(setMode(CopterMode.AUTO.value), vehicle = VehicleKind.UNKNOWN)!!.contains("allowlist"))
    }

    @Test
    fun gatewayRefusesWithoutWritingAndReportsTheRejection() = runTest {
        val link = FakeMavConnection()
        val gateway = MavTxGateway(MutableStateFlow(locked)) { VehicleKind.COPTER }
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
        val gateway = MavTxGateway(pod) { VehicleKind.COPTER }
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
