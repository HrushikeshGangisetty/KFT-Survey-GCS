package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MissionItemInt
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavState
import com.divpundir.mavlink.definitions.minimal.MavType
import com.kft.gcs.core.mavlink.signing.FakeKeyring
import com.kft.gcs.core.mavlink.signing.NoSigning
import com.kft.gcs.core.mavlink.signing.SigningKeyring
import com.kft.gcs.core.mavlink.signing.trailerOf
import com.kft.gcs.core.mavlink.signing.verifyV2
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.IOException

/** [FrameSink]: unchanged bytes for unsigned sends, and correctly signed frames with a rising clock per drone. */
class FrameSinkTest {
    private val keyA = ByteArray(32) { 0x42 }
    private val keyB = ByteArray(32) { 0x17 }

    // The heartbeat SigningLibraryProbeTest and TlogTest use: QUADROTOR, ARDUPILOTMEGA, base_mode 81, custom_mode 5.
    private val heartbeat = Heartbeat(
        type = MavEnumValue.of(MavType.QUADROTOR), autopilot = MavEnumValue.of(MavAutopilot.ARDUPILOTMEGA),
        baseMode = MavBitmaskValue.fromValue(81u), customMode = 5u, systemStatus = MavEnumValue.of(MavState.ACTIVE), mavlinkVersion = 3u,
    )

    private fun sinkOver(out: Buffer, keyring: SigningKeyring = NoSigning, nowMs: Long = 0) =
        FrameSink(out, keyring, { nowMs }, Dispatchers.Unconfined)

    /**
     * The S2 promise: for unsigned sends nothing changes on the wire. 300 frames (so the 8-bit sequence wraps) of
     * mixed messages, written by FrameSink and by the library's `sendUnsignedV2` the way the gateway used to call it
     * (COMMAND_LONG through [BitExactCommandLong]), must be the same bytes.
     */
    @Test
    fun unsignedFramesAreByteIdenticalToTheOldSendUnsignedV2() = runTest {
        val ours = Buffer()
        val sink = sinkOver(ours)
        val theirs = Buffer()
        val library = BufferedMavConnection(theirs, theirs, theirs, ArdupilotmegaDialect)

        suspend fun <T : MavMessage<T>> both(message: T) {
            sink.write(message, 255u, 190u, signFor = null)
            if (message is CommandLong) library.sendUnsignedV2(255u, 190u, BitExactCommandLong(message))
            else library.sendUnsignedV2(255u, 190u, message)
        }
        repeat(60) {
            both(heartbeat)
            both(CommandLong(targetSystem = 1u, command = MavEnumValue.of(MavCmd.USER_2), param2 = Float.fromBits(0x7FA12345)))
            both(MissionItemInt(targetSystem = 1u, seq = 3u, x = 123_456_789, y = -98_765_432, z = 50f))
            both(ParamSet(targetSystem = 1u, paramId = "CAM1_TYPE", paramValue = 1f))
            both(RequestDataStream(targetSystem = 1u, reqMessageRate = 4u, startStop = 1u))
        }
        assertEquals(theirs.size, ours.size)
        assertContentEquals(theirs.readByteArray(), ours.readByteArray())
    }

    /**
     * Independent reference: pymavlink 2.4.50 signed this heartbeat (sysid 1, compid 1, seq 0) with key 32 × 0x42,
     * link id 7 and timestamp 0x2234_5678_9ABC; the hex is `pymavlinkRealTs` in SigningLibraryProbeTest (ADR-006).
     * The saved timestamp is one below it and the wall clock is before 2015, so the clock starts exactly there.
     */
    @Test
    fun aSignedFrameMatchesPymavlink() = runTest {
        val keyring = FakeKeyring(mapOf("d1" to keyA), linkId = 7u).apply { timestamps["d1"] = 0x2234_5678_9ABCL - 1 }
        val out = Buffer()
        sinkOver(out, keyring, nowMs = 0).write(heartbeat, 1u, 1u, signFor = "d1")
        assertEquals("fd090100000101000000050000000203510403bb7a07bc9a785634222b31429d76c5", out.readByteString().hex())
    }

    /**
     * Each drone has its own clock (FR-10): it starts at max(wall clock, saved + 1), rises by one per frame, and jumps
     * above a verified incoming timestamp but never goes back for a lower one. One sequence counter covers all frames.
     */
    @Test
    fun eachDroneHasItsOwnRisingClock() = runTest {
        val keyring = FakeKeyring(mapOf("a" to keyA, "b" to keyB)).apply { timestamps["b"] = 9_000_000 }
        val out = Buffer()
        // 2015-01-01 + 1 s: 100 000 ticks of 10 µs.
        val sink = sinkOver(out, keyring, nowMs = 1_420_070_401_000L)
        suspend fun send(droneId: String): ByteArray {
            sink.write(heartbeat, 255u, 190u, signFor = droneId)
            return out.readByteArray()
        }

        val frames = listOf(
            "a" to send("a"), "a" to send("a"),
            "b" to send("b"), // saved 9 000 000 beats the wall clock
            "a" to (sink.observeIncoming("a", 5_000_000).let { send("a") }),
            "a" to (sink.observeIncoming("a", 10).let { send("a") }), // lower: ignored
        )
        assertEquals(listOf(100_000L, 100_001, 9_000_001, 5_000_001, 5_000_002), frames.map { trailerOf(it.second)!!.timestamp })
        frames.forEach { (drone, frame) -> assertTrue(verifyV2(frame, if (drone == "a") keyA else keyB), "signed with $drone's key") }
        assertEquals(listOf(0, 1, 2, 3, 4), frames.map { it.second[4].toInt() }, "one sequence counter for the link")
        assertTrue(frames.all { trailerOf(it.second)!!.linkId == keyring.linkId })
    }

    /** A frame meant to be signed never goes out unsigned: no key (or a locked vault) writes nothing and uses no sequence. */
    @Test
    fun noKeyWritesNothing() = runTest {
        val keyring = FakeKeyring(mapOf("a" to keyA))
        val out = Buffer()
        val sink = sinkOver(out, keyring)
        assertFailsWith<IOException> { sink.write(heartbeat, 255u, 190u, signFor = "unknown") }
        keyring.unlocked = false
        assertFailsWith<IOException> { sink.write(heartbeat, 255u, 190u, signFor = "a") }
        assertEquals(0, out.size)

        sink.write(heartbeat, 255u, 190u, signFor = null)
        assertEquals(0, out.readByteArray()[4].toInt(), "the refused frames didn't use sequence numbers")
    }
}
