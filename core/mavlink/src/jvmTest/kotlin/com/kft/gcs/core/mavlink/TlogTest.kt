package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.api.MavBitmaskValue
import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavState
import com.divpundir.mavlink.definitions.minimal.MavType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class TlogTest {
    private data class Frame(
        override val systemId: UByte,
        override val componentId: UByte,
        override val sequence: UByte,
        override val message: MavMessage<*>,
        override val checksum: UShort = 0u,
    ) : MavFrame<MavMessage<*>>

    // HEARTBEAT from system 3, component 1, seq 7: quadrotor (2), ArduPilot (3), base_mode 81, custom_mode 5, ACTIVE (4),
    // mavlink_version 3.
    private val heartbeat = Frame(
        3u, 1u, 7u,
        Heartbeat(
            type = MavEnumValue.of(MavType.QUADROTOR), autopilot = MavEnumValue.of(MavAutopilot.ARDUPILOTMEGA),
            baseMode = MavBitmaskValue.fromValue(81u), customMode = 5u, systemStatus = MavEnumValue.of(MavState.ACTIVE), mavlinkVersion = 3u,
        ),
    )

    // The same frame from pymavlink 2.4.43 (the library MAVProxy uses), an independent encoder:
    //   mav = ardupilotmega.MAVLink(None, srcSystem=3, srcComponent=1); mav.seq = 7
    //   mav.heartbeat_encode(2, 3, 81, 5, 4, 3).pack(mav).hex()
    private val pymavlinkFrame = "fd0900000703010000000500000002035104034ba0"

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** 0x0001020304050607 µs must come out as the bytes 00 01 02 … 07 (big-endian), then the frame as pymavlink packs it. */
    @Test
    fun aRecordIsABigEndianMicrosecondTimeThenTheFrameBytes() {
        assertEquals("0001020304050607$pymavlinkFrame", hex(tlogRecord(0x0001020304050607, heartbeat)))
    }

    /** The file is named from the vehicle and the time of its first frame, and holds one record per frame. */
    @Test
    fun eachVehicleGetsItsOwnFile() = runTest {
        val dir = Files.createTempDirectory("kft-tlog").toFile()
        var now = 1_790_000_000_000L // ms
        val files = TlogFiles(dir, StandardTestDispatcher(testScheduler)) { now.also { now += 250 } }
        files.record(VehicleId(link = 2, systemId = 3u), flowOf(heartbeat, heartbeat))

        val file = dir.listFiles()!!.single()
        assertTrue(Regex("""\d{4}-\d\d-\d\d_\d\d-\d\d-\d\d_link2_sys3\.tlog""").matches(file.name), file.name)
        assertContentEquals(tlogRecord(1_790_000_000_000_000, heartbeat) + tlogRecord(1_790_000_000_250_000, heartbeat), file.readBytes())
    }

    @Test
    fun aVehicleThatSendsNothingLeavesNoFile() = runTest {
        val dir = Files.createTempDirectory("kft-tlog").toFile()
        TlogFiles(dir, StandardTestDispatcher(testScheduler)).record(VehicleId(1, 1u), flowOf())
        assertEquals(0, dir.listFiles()!!.size)
    }
}
