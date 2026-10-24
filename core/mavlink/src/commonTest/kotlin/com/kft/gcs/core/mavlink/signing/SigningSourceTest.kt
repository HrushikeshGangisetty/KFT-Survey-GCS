package com.kft.gcs.core.mavlink.signing

import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavRawFrame
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.divpundir.mavlink.definitions.common.Attitude
import com.divpundir.mavlink.definitions.common.RadioStatus
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.kft.gcs.core.mavlink.copterHeartbeat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okio.Buffer
import okio.ByteString.Companion.decodeHex
import okio.EOFException
import okio.Source
import okio.Timeout

/**
 * [SigningSource] against byte streams, one test per inbound rule of the PRD ("Architecture"). Frames are built by
 * mavlink-kotlin (unsigned) and [signV2] (checked against pymavlink in Mav2SigningTest); the one forged-frame test uses
 * pymavlink's own bytes. What reaches "the library" is read back through a real `BufferedMavConnection`.
 */
class SigningSourceTest {
    private val keyA = ByteArray(32) { 0x42 }
    private val keyB = ByteArray(32) { 0x17 }

    /** 2027-01-15 in Unix ms, and the same instant as a signing timestamp. */
    private val nowMs = 1_800_000_000_000L
    private val now = signingTicks(nowMs)

    private fun unsignedV2(message: MavMessage<*>, sysId: UByte, compId: UByte = 1u): ByteArray = with(message.instanceCompanion) {
        MavRawFrame.createUnsignedV2(0u, sysId, compId, id, message.serializeV2(), crcExtra).rawBytes
    }

    private fun v1(message: MavMessage<*>, sysId: UByte): ByteArray = with(message.instanceCompanion) {
        MavRawFrame.createV1(0u, sysId, 1u, id, message.serializeV1(), crcExtra).rawBytes
    }

    private fun signed(message: MavMessage<*>, sysId: UByte, key: ByteArray, ts: Long = now, compId: UByte = 1u): ByteArray =
        signV2(unsignedV2(message, sysId, compId), message.instanceCompanion.crcExtra, key, linkId = 3u, timestamp = ts)

    private val attitude = Attitude(roll = 0.25f)

    /** Hands out [data] at most one byte per read, the worst split a serial link can produce. */
    private class Trickle(private val data: Buffer) : Source {
        override fun read(sink: Buffer, byteCount: Long) = if (data.size == 0L) -1 else data.read(sink, 1)
        override fun timeout() = Timeout.NONE
        override fun close() = Unit
    }

    private val verified = mutableListOf<Pair<String, Long>>()

    private fun filter(keyring: SigningKeyring, vararg chunks: ByteArray, trickle: Boolean = false): SigningSource {
        val upstream = Buffer().apply { chunks.forEach { write(it) } }
        return SigningSource(if (trickle) Trickle(upstream) else upstream, keyring, { nowMs }) { id, ts -> verified += id to ts }
    }

    /** Everything the filter lets through. */
    private fun SigningSource.drain(): ByteArray = Buffer().also { it.writeAll(this) }.readByteArray()

    /** The frames mavlink-kotlin decodes from [bytes]. */
    private fun decode(bytes: ByteArray): List<MavFrame<out MavMessage<*>>> {
        val wire = Buffer().write(bytes)
        val reader = BufferedMavConnection(wire, wire, wire, ArdupilotmegaDialect)
        return buildList {
            while (true) add(try { reader.next() } catch (_: EOFException) { break })
        }
    }

    /**
     * Rules 1, 2 and 5 together, split one byte per read: garbage, MAVLink 1, unsigned v2 and correctly signed v2 all
     * come out byte for byte, in order, and the library decodes every frame.
     */
    @Test
    fun aMixedStreamPassesUnchangedEvenOneByteAtATime() {
        val stream = listOf(
            "hello".encodeToByteArray(),
            v1(copterHeartbeat(), sysId = 3u),
            unsignedV2(copterHeartbeat(), sysId = 4u),
            signed(copterHeartbeat(), sysId = 1u, keyA),
            byteArrayOf(0x00, 0x55, 0x7F),
            signed(attitude, sysId = 1u, keyA, ts = now + 1),
            unsignedV2(attitude, sysId = 4u),
        )
        val source = filter(FakeKeyring(mapOf("d1" to keyA)), *stream.toTypedArray(), trickle = true)
        val out = source.drain()

        assertContentEquals(stream.reduce { a, b -> a + b }, out)
        assertEquals(listOf(3, 4, 1, 1, 4), decode(out).map { it.systemId.toInt() })
        assertEquals(mapOf(1.toUByte() to SigningStatus.Signed("d1"), 3.toUByte() to SigningStatus.Unsigned, 4.toUByte() to SigningStatus.Unsigned), source.status.value)
        assertEquals(SigningCounters(), source.counters.value)
        assertEquals(listOf("d1" to now), verified, "the outgoing clock hears about the verified heartbeat")
    }

    /** Rule 3: an unknown system id is bound by trying each key on its signed HEARTBEAT, and only then trusted. */
    @Test
    fun bindingByTrialOnTheHeartbeat() {
        val beforeBinding = signed(attitude, sysId = 2u, keyB, ts = now - 1)
        val heartbeat = signed(copterHeartbeat(), sysId = 2u, keyB)
        val afterBinding = signed(attitude, sysId = 2u, keyB, ts = now + 1)
        val wrongKey = signed(attitude, sysId = 2u, keyA, ts = now + 2)
        val source = filter(FakeKeyring(mapOf("a" to keyA, "b" to keyB)), beforeBinding, heartbeat, afterBinding, wrongKey)

        assertContentEquals(heartbeat + afterBinding, source.drain())
        assertEquals(SigningStatus.Signed("b"), source.status.value[2u])
        assertEquals(SigningCounters(unverified = 1, badSignature = 1), source.counters.value)
    }

    /** Rule 3, no match: a signing vehicle we have no key for (or can't check, vault locked) is dropped entirely. */
    @Test
    fun noKeyAndLockedDropEverything() {
        val keyring = FakeKeyring(mapOf("a" to keyA))
        val source = filter(keyring, signed(copterHeartbeat(), sysId = 5u, keyB))
        assertEquals(0, source.drain().size)
        assertEquals(SigningStatus.NoKey, source.status.value[5u])

        keyring.unlocked = false
        val locked = filter(keyring, signed(copterHeartbeat(), sysId = 6u, keyA))
        assertEquals(0, locked.drain().size)
        assertEquals(SigningStatus.Locked, locked.status.value[6u])
    }

    /** Rule 2's replay check: a repeated frame, and a new stream more than 60 s old, are dropped. */
    @Test
    fun replayedFramesAreDropped() {
        val heartbeat = signed(copterHeartbeat(), sysId = 1u, keyA)
        val staleNewStream = signed(attitude, sysId = 1u, keyA, ts = now - 6_000_001, compId = 2u)
        val recentNewStream = signed(attitude, sysId = 1u, keyA, ts = now - 5_999_000, compId = 3u)
        val source = filter(FakeKeyring(mapOf("d1" to keyA)), heartbeat, heartbeat, staleNewStream, recentNewStream)

        assertContentEquals(heartbeat + recentNewStream, source.drain())
        assertEquals(SigningCounters(replayed = 2), source.counters.value)
    }

    /** Rule 4: once bound, unsigned and MAVLink 1 frames with that id are spoofs, except RADIO_STATUS. */
    @Test
    fun spoofedUnsignedFramesAreDroppedButRadioStatusIsKept() {
        val binding = signed(copterHeartbeat(), sysId = 1u, keyA)
        val radio = unsignedV2(RadioStatus(rssi = 200u, remrssi = 190u), sysId = 1u)
        val otherVehicle = unsignedV2(copterHeartbeat(), sysId = 2u)
        val source = filter(
            FakeKeyring(mapOf("d1" to keyA)),
            binding, unsignedV2(copterHeartbeat(), sysId = 1u), v1(copterHeartbeat(), sysId = 1u), radio, otherVehicle,
        )

        assertContentEquals(binding + radio + otherVehicle, source.drain())
        assertEquals(SigningCounters(spoofed = 2), source.counters.value)
        assertEquals(SigningStatus.Signed("d1"), source.status.value[1u], "a spoofed heartbeat doesn't change the status")
    }

    /**
     * The "after" picture for SigningLibraryProbeTest.forgedSignaturesPassAndBadCrcsAreDropped: there the library
     * decodes a pymavlink frame with a forged signature like a genuine one. Behind the filter it never arrives, whether
     * the vehicle is not bound yet (the forged heartbeat can't bind it) or already bound by the genuine frame.
     */
    @Test
    fun aForgedSignatureNeverReachesTheLibrary() {
        // pymavlink 2.4.50: HEARTBEAT from 1/1, key 32 × 0x42, link id 7, timestamp 0x2234_5678_9ABC (ADR-006).
        val genuine = "fd090100000101000000050000000203510403bb7a07bc9a785634222b31429d76c5".decodeHex().toByteArray()
        val forged = genuine.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        // "Now" is the frame's own time, so the 60 s first-seen window passes.
        val atFrameTime = 1_420_070_400_000L + 0x2234_5678_9ABCL / 100
        fun source(vararg frames: ByteArray) = SigningSource(Buffer().apply { frames.forEach { write(it) } }, FakeKeyring(mapOf("d1" to keyA)), { atFrameTime })

        val unbound = source(forged)
        assertTrue(decode(unbound.drain()).isEmpty(), "forged frame alone: nothing reaches the library")
        assertEquals(SigningStatus.NoKey, unbound.status.value[1u])

        val bound = source(genuine, forged)
        assertEquals(listOf<Heartbeat?>(decode(genuine).single().message as Heartbeat), decode(bound.drain()).map { it.message as? Heartbeat })
        assertEquals(SigningCounters(badSignature = 1), bound.counters.value)
    }

    /**
     * ADR-006 side finding: the library tests `incompat_flags == 1`, so a frame with an unknown incompat bit would be
     * misframed. Like MAVLink's reference parser we abandon it and resync; the next frame still gets through.
     */
    @Test
    fun anUnknownIncompatFlagIsDropped() {
        val unknownFlag = unsignedV2(copterHeartbeat(), sysId = 4u).also { it[2] = 0x02 }
        val next = unsignedV2(attitude, sysId = 4u)
        val source = filter(NoSigning, unknownFlag, next)

        assertEquals(listOf<MavMessage<*>>(attitude), decode(source.drain()).map { it.message })
        assertEquals(SigningCounters(malformed = 1), source.counters.value)
    }

    /** A link that ends inside a frame: the tail is dropped, so the library can't rescan it for a hidden frame. */
    @Test
    fun aFrameCutOffByTheEndOfTheStreamIsDropped() {
        val whole = unsignedV2(copterHeartbeat(), sysId = 4u)
        val cut = unsignedV2(copterHeartbeat(), sysId = 5u).copyOf(6)
        assertContentEquals(whole, filter(NoSigning, whole, cut).drain())
    }
}
