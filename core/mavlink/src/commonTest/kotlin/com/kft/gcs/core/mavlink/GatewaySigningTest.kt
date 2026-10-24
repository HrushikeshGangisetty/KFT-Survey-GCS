package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.SetPositionTargetLocalNed
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.kft.gcs.core.mavlink.signing.FakeKeyring
import com.kft.gcs.core.mavlink.signing.SigningStatus
import com.kft.gcs.core.mavlink.signing.trailerOf
import com.kft.gcs.core.mavlink.signing.verifyV2
import app.cash.turbine.test
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * Signing S3: the gateway picks the key from the target vehicle's [SigningStatus] (PRD "Outbound rules"). The signing
 * layer is played by [FakeMavConnection.signing]; the frames are checked with the S1 verifier, which matches pymavlink.
 */
class GatewaySigningTest {

    private val keyA = ByteArray(32) { 0x11 }
    private val keyB = ByteArray(32) { 0x22 }
    private val keyring = FakeKeyring(mapOf("drone-a" to keyA, "drone-b" to keyB), linkId = 7u)

    private fun gateway(link: FakeMavConnection) =
        MavTxGateway(MutableStateFlow(PodStatus.NoPod)) { VehicleInfo(it, 1u, VehicleKind.COPTER, armed = false, customMode = 0u) }
            .apply { attach(link.frameSink, link.signing) }

    private fun FakeMavConnection.statuses(vararg pairs: Pair<Int, SigningStatus>) {
        signing.value = pairs.associate { (id, status) -> id.toUByte() to status }
    }

    private fun ByteArray.isSigned() = this[2].toInt() and 0x01 != 0

    @Test
    fun aSignedVehicleGetsFramesThatVerifyWithItsOwnKey() = runTest {
        val link = FakeMavConnection(keyring = keyring)
        link.statuses(1 to SigningStatus.Signed("drone-a"))
        assertEquals(TxResult.Sent, gateway(link).forVehicle(1u).send(ParamRequestList(targetSystem = 1u)))

        val frame = link.rawFrames.single()
        assertTrue(frame.isSigned(), "incompat flag 0x01 set")
        assertTrue(verifyV2(frame, keyA), "verifies with drone A's key")
        assertFalse(verifyV2(frame, keyB), "and with no other")
        assertEquals(7u.toUByte(), trailerOf(frame)!!.linkId, "stamped with this installation's link id")
        assertEquals(ParamRequestList(targetSystem = 1u), link.sent.single(), "and still decodes to the message")
    }

    /** NFR-5: a vehicle that doesn't sign, or hasn't been heard yet, gets exactly what it got before signing. */
    @Test
    fun anUnsignedVehicleIsSentUnsignedFrames() = runTest {
        val link = FakeMavConnection(keyring = keyring)
        val sender = gateway(link).forVehicle(1u)
        assertEquals(TxResult.Sent, sender.send(ParamRequestList(targetSystem = 1u)), "not heard yet")
        link.statuses(1 to SigningStatus.Unsigned, 2 to SigningStatus.Signed("drone-b"))
        assertEquals(TxResult.Sent, sender.send(ParamRequestList(targetSystem = 1u)), "heard, unsigned")

        val frames = link.rawFrames
        assertEquals(2, frames.size)
        frames.forEach { assertFalse(it.isSigned()); assertEquals(12 + (it[1].toInt() and 0xFF), it.size, "no trailer") }
    }

    @Test
    fun noKeyAndLockedVehiclesAreRefusedAndLogged() = runTest {
        val link = FakeMavConnection(keyring = keyring)
        val gateway = gateway(link)
        link.statuses(1 to SigningStatus.NoKey, 2 to SigningStatus.Locked)
        gateway.rejections.test {
            val noKey = assertIs<TxResult.Rejected>(gateway.forVehicle(1u).send(ParamRequestList(targetSystem = 1u)))
            assertEquals("vehicle signs its traffic; no key for it on this device", noKey.reason)
            assertEquals(noKey, awaitItem())
            assertTrue(noKey.isSigningRefusal, "the app turns this into a toast with an Open signing keys action")
            val locked = assertIs<TxResult.Rejected>(gateway.forVehicle(2u).send(ParamRequestList(targetSystem = 2u)))
            assertEquals("signing keys are locked; unlock in Settings", locked.reason)
            assertEquals(locked, awaitItem())
            // The allowlist still speaks first: a never-sent message says so, not "no key".
            val never = assertIs<TxResult.Rejected>(gateway.forVehicle(1u).send(SetPositionTargetLocalNed(targetSystem = 1u)))
            assertTrue(never.reason.startsWith("never"), never.reason)
            assertFalse(never.isSigningRefusal, "an allowlist refusal is not fixed in the keys screen")
            awaitItem()
        }
        assertEquals(0L, link.wire.size, "nothing unsigned goes to a vehicle that signs")
    }

    /** A bad heartbeat (perhaps a forger's) doesn't unbind the vehicle: the gateway keeps signing for its drone. */
    @Test
    fun aBadSignatureKeepsSigningForTheBoundDrone() = runTest {
        val link = FakeMavConnection(keyring = keyring)
        link.statuses(1 to SigningStatus.BadSignature("drone-a", count = 3))
        assertEquals(TxResult.Sent, gateway(link).forVehicle(1u).send(ParamRequestList(targetSystem = 1u)))
        assertTrue(verifyV2(link.rawFrames.single(), keyA))
    }

    /** The vault locks between the status read and the write: FrameSink refuses, and nothing goes out unsigned. */
    @Test
    fun aKeyThatVanishesMidSendFailsInsteadOfGoingOutUnsigned() = runTest {
        val link = FakeMavConnection(keyring = keyring)
        link.statuses(1 to SigningStatus.Signed("drone-a"))
        keyring.unlocked = false
        assertIs<TxResult.Failed>(gateway(link).forVehicle(1u).send(ParamRequestList(targetSystem = 1u)))
        assertEquals(0L, link.wire.size)
    }

    // ---- heartbeat fan-out (PRD outbound rule 4) ----

    private suspend fun heartbeatsFor(vararg pairs: Pair<Int, SigningStatus>): List<ByteArray> {
        val link = FakeMavConnection(keyring = keyring)
        link.statuses(*pairs)
        assertEquals(TxResult.Sent, gateway(link).sendHeartbeat(copterHeartbeat()))
        link.sent.forEach { assertIs<Heartbeat>(it) }
        return link.rawFrames
    }

    @Test
    fun heartbeatGoesOutOncePerBoundDronePlusOnceUnsigned() = runTest {
        // Nothing heard yet: one unsigned, so vehicles hear a GCS and start talking.
        assertEquals(listOf(false), heartbeatsFor().map { it.isSigned() })
        // 0 bound + 1 unsigned.
        assertEquals(listOf(false), heartbeatsFor(1 to SigningStatus.Unsigned).map { it.isSigned() })

        // 1 bound, nothing unsigned: only the signed one.
        val one = heartbeatsFor(1 to SigningStatus.Signed("drone-a"))
        assertEquals(1, one.size)
        assertTrue(verifyV2(one.single(), keyA))

        // 1 bound + 1 unsigned.
        val mixed = heartbeatsFor(1 to SigningStatus.Signed("drone-a"), 2 to SigningStatus.Unsigned)
        assertEquals(listOf(true, false), mixed.map { it.isSigned() })

        // T9: 2 bound (different keys) + 1 unsigned = 2 signed + 1 unsigned, each signed one with its own drone's key.
        val t9 = heartbeatsFor(1 to SigningStatus.Signed("drone-a"), 2 to SigningStatus.Signed("drone-b"), 3 to SigningStatus.Unsigned)
        assertEquals(3, t9.size)
        assertTrue(verifyV2(t9[0], keyA) && verifyV2(t9[1], keyB), "one per drone, each with its key")
        assertFalse(t9[2].isSigned())

        // One drone on two system ids still gets one heartbeat: it's per key, not per id.
        assertEquals(1, heartbeatsFor(1 to SigningStatus.Signed("drone-a"), 2 to SigningStatus.BadSignature("drone-a", 1)).size)
        // Only a vehicle we can't sign for: nothing it would accept, so nothing.
        assertEquals(0, heartbeatsFor(1 to SigningStatus.NoKey).size)
    }

    /**
     * S12 + signing: the KFT login packs HMAC bytes into COMMAND_LONG floats as raw bit patterns. Signed, the frame
     * still holds exactly those bits (BitExactCommandLong path), and the signature covers them.
     */
    @Test
    fun kftLoginCommandLongIsStillBitExactWhenSigned() = runTest {
        val patterns = listOf(0x7FA12345, 0x7FC00001, -1, 0x7F800000, 0x3F800000, 0x00000001)
        val login = CommandLong(
            targetSystem = 1u, targetComponent = 1u, command = MavEnumValue.of(MavCmd.USER_2), param1 = 1f,
            param2 = Float.fromBits(patterns[0]), param3 = Float.fromBits(patterns[1]), param4 = Float.fromBits(patterns[2]),
            param5 = Float.fromBits(patterns[3]), param6 = Float.fromBits(patterns[4]), param7 = Float.fromBits(patterns[5]),
        )
        val link = FakeMavConnection(keyring = keyring)
        link.statuses(1 to SigningStatus.Signed("drone-a"))
        assertEquals(TxResult.Sent, gateway(link).forVehicle(1u).send(login))

        val frame = link.rawFrames.single()
        assertTrue(verifyV2(frame, keyA))
        fun int32(at: Int) = (0..3).fold(0) { acc, i -> acc or ((frame[at + i].toInt() and 0xFF) shl (8 * i)) }
        // Payload starts after the 10-byte header; param1 is at 0, so param2..7 are at 4, 8, … 24.
        patterns.forEachIndexed { i, bits -> assertEquals(bits, int32(10 + 4 + 4 * i), "param${i + 2}") }
    }
}
