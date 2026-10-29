package com.kft.gcs.feature.replay

import com.kft.gcs.core.geoio.ImportFile
import com.kft.gcs.ui.map.MapOverlay
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** Hands out the queued files in order, as if the operator picked them; null = cancelled. */
private class FakeFiles(vararg files: ImportFile?) : ReplayFiles {
    private val queue = files.toMutableList()
    override suspend fun open() = queue.removeFirst()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    /** 20 s at 10 Hz from FC time 100 s: GPS flying east at 0.0001° of longitude per row, and VISP following it. */
    private fun log(): ByteArray {
        val b = DataFlashBuilder()
            .fmt(1, "GPS", "QBBLLfB", "TimeUS,I,Status,Lat,Lng,Spd,NSats")
            .fmt(2, "VISP", "QQffBB", "TimeUS,RTimeUS,PX,PY,Rst,Ign")
            .fmt(3, "MSG", "QZ", "TimeUS,Message")
            .row(3, 100_000_000L, "EKF3 IMU0 is using external nav data")
        (0..200).forEach { i ->
            val us = 100_000_000L + i * 100_000L
            b.row(1, us, 0, 3, 31.2, 75.9 + i * 1e-4, 9.5f, 15)
            // Pi clock 50 s behind; x forward = east, so PX grows by the same ~9.5 m per second.
            b.row(2, us, us - 50_000_000L, (i * 0.95).toFloat(), 0f, 0, 0)
        }
        return b.build()
    }

    @Test
    fun openingALogShowsTheFlightAndFitsTheMap() = runTest(dispatcher) {
        val vm = ReplayViewModel(FakeFiles(ImportFile("00000042.BIN", log())), dispatcher)
        vm.onOpenLogClicked()
        runCurrent()
        val s = vm.state.value
        assertEquals("00000042.BIN", s.title)
        assertEquals(100.0, s.startS)
        assertEquals(120.0, s.endS)
        assertEquals(3, s.overlays.count { it is MapOverlay.Track })
        assertEquals(15.toString(), s.playhead.first { it.label == "Satellites" }.value)
        assertEquals("0.0 s", s.events.first().time)
        assertNotNull(s.cameraRequest)
        assertNull(s.error)
    }

    @Test
    fun playbackAdvancesBySpeedAndStopsAtTheEnd() = runTest(dispatcher) {
        val vm = ReplayViewModel(FakeFiles(ImportFile("a.bin", log())), dispatcher)
        vm.onOpenLogClicked()
        runCurrent()
        vm.onSpeedSelected(4)
        vm.onPlayPauseClicked()
        advanceTimeBy(1_000)
        runCurrent()
        // 10 ticks of 100 ms at 4× = 4 s of flight.
        assertEquals(104.0, vm.state.value.timeS, 1e-9)
        assertTrue(vm.state.value.playing)
        vm.onPlayPauseClicked()
        assertFalse(vm.state.value.playing)

        vm.onSeek(119.0)
        vm.onPlayPauseClicked()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(120.0, vm.state.value.timeS)
        assertFalse(vm.state.value.playing, "stops at the end")
        vm.onSeek(500.0)
        assertEquals(120.0, vm.state.value.timeS, "seeks are kept inside the flight")
    }

    @Test
    fun aBadOdometryFileKeepsTheFlightAndSaysWhy() = runTest(dispatcher) {
        val bad = ImportFile("odometry.jsonl", """{"schema_version":"0.1"}""".encodeToByteArray())
        val vm = ReplayViewModel(FakeFiles(ImportFile("a.bin", log()), bad, null), dispatcher)
        vm.onOpenLogClicked()
        runCurrent()
        vm.onOpenRecordingClicked()
        runCurrent()
        assertTrue(vm.state.value.error!!.startsWith("odometry.jsonl: schema_version 0.1"), vm.state.value.error)
        assertEquals("a.bin", vm.state.value.title)
        vm.onOpenRecordingClicked() // cancelled: nothing changes
        runCurrent()
        assertNull(vm.state.value.odometryName)
    }

    @Test
    fun aFileThatIsNotALogIsRefused() = runTest(dispatcher) {
        val vm = ReplayViewModel(FakeFiles(ImportFile("notes.txt", "hello".encodeToByteArray())), dispatcher)
        vm.onOpenLogClicked()
        runCurrent()
        assertTrue(vm.state.value.error!!.contains("no GPS, SITL truth or VISP"))
        assertNull(vm.state.value.title)
    }
}
