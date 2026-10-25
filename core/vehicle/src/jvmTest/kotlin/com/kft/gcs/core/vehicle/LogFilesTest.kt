package com.kft.gcs.core.vehicle

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest

class LogFilesTest {
    private val dir = Files.createTempDirectory("kft-logs").toFile()
    // 2025-10-25 09:14:02 UTC = 1 761 383 642 s (Python: datetime.fromtimestamp(1761383642, UTC)).
    private val log = FlightLog(12, 4, 1_761_383_642L)

    /** Named by vehicle, list id and the log's own UTC start; complete only once finished; never overwrites. */
    @Test
    fun aFinishedLogIsNamedAndASecondCopyDoesNotOverwrite() = runTest {
        val store = LogFiles(dir, Dispatchers.Unconfined)
        val sink = store.create(1, log)
        sink.write(byteArrayOf(1, 2))
        sink.write(byteArrayOf(3, 4))
        assertEquals(listOf("sys1_log12_2025-10-25_09-14-02Z.bin.part"), dir.list()!!.toList(), "not a .bin until finished")
        val path = sink.finish()
        assertEquals(File(dir, "sys1_log12_2025-10-25_09-14-02Z.bin").path, path)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), File(path).readBytes())

        val again = store.create(1, log).apply { write(byteArrayOf(9)) }.finish()
        assertEquals("sys1_log12_2025-10-25_09-14-02Z-2.bin", File(again).name)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), File(path).readBytes(), "the first copy is untouched")
    }

    /** A log without a time is named after the download (fixed clock: 1 761 390 000 000 ms = 2025-10-25 11:00:00 UTC). */
    @Test
    fun anAbortedLogLeavesNothingAndAnUntimedLogUsesTheClock() = runTest {
        val store = LogFiles(dir, Dispatchers.Unconfined, clockMillis = { 1_761_390_000_000L })
        store.create(2, log.copy(timeUtcSeconds = null)).apply { write(byteArrayOf(1)) }.abort()
        assertEquals(emptyList(), dir.list()!!.toList())
        val path = store.create(2, log.copy(timeUtcSeconds = null)).finish()
        assertEquals("sys2_log12_downloaded-2025-10-25_11-00-00Z.bin", File(path).name)
        assertFalse(File("$path.part").exists())
    }
}
