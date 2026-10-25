package com.kft.gcs.feature.setup

import app.cash.turbine.test
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FlightLog
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.LogTransferException
import com.kft.gcs.core.vehicle.VehicleLogRepository
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A vehicle's logs in memory. [download] reports progress in [steps] and then answers [result]. */
private class FakeLogs : VehicleLogRepository {
    var list: Result<List<FlightLog>> = Result.success(emptyList())
    var steps: List<Long> = emptyList()
    var result: suspend () -> Result<String> = { Result.success("/logs/sys1_log2.bin") }
    val downloads = mutableListOf<Int>()
    var cancelled = false

    override suspend fun list() = list

    override suspend fun download(log: FlightLog, onProgress: (Long) -> Unit): Result<String> {
        downloads += log.id
        steps.forEach(onProgress)
        return try { result() } catch (e: kotlinx.coroutines.CancellationException) { cancelled = true; throw e }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))
    private val selected = MutableStateFlow<VehicleId?>(VehicleId(1, 1u))
    private val logs = FakeLogs().apply {
        list = Result.success(listOf(FlightLog(1, 1_804_774, 1_761_383_642L), FlightLog(2, 17_343_196, null)))
    }

    @BeforeTest fun before() = Dispatchers.setMain(dispatcher)
    @AfterTest fun after() = Dispatchers.resetMain()

    private fun TestScope.vm() = LogsViewModel(vehicle, logs, selected, testScheduler.timeSource)
        .also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }

    /** Newest first, with size and UTC date; a log without a GPS time says so. */
    @Test
    fun theListShowsEveryLogNewestFirst() = runTest(dispatcher) {
        val vm = vm()
        assertFalse(vm.state.value.listed)
        vm.onListClicked()
        runCurrent()
        assertEquals(
            listOf(
                LogRow(2, "Log 2", "No date (no GPS time when it started)", "17.3 MB", null),
                LogRow(1, "Log 1", "2025-10-25 09:14 UTC", "1.8 MB", null),
            ),
            vm.state.value.rows,
        )
        assertTrue(vm.state.value.listed)
    }

    /** Progress while it runs, then the saved path on the row. */
    @Test
    fun aDownloadShowsProgressThenWhereItWasSaved() = runTest(dispatcher) {
        val gate = CompletableDeferred<Result<String>>()
        logs.result = { gate.await() }
        val vm = vm()
        vm.onListClicked()
        runCurrent()
        logs.steps = listOf(1_000_000)
        vm.effects.test {
            vm.onDownloadClicked(1)
            runCurrent()
            // The progress came at once (no time passed since the click), so there's no rate yet.
            assertEquals(LogDownloadUi(1, "Log 1", 1_000_000f / 1_804_774, "1.0 MB of 1.8 MB"), vm.state.value.download)
            assertFalse(vm.state.value.canDownload, "one download at a time")
            assertFalse(vm.state.value.canList)
            gate.complete(Result.success("/logs/sys1_log1.bin"))
            runCurrent()
            assertEquals("Log 1 saved to /logs/sys1_log1.bin", (awaitItem() as SetupEffect.ShowMessage).text)
        }
        assertNull(vm.state.value.download)
        assertEquals("/logs/sys1_log1.bin", vm.state.value.rows.single { it.id == 1 }.saved)
        assertTrue(vm.state.value.canDownload)
    }

    /** Virtual time: 1 MB after 2 s is 500 kB/s. */
    @Test
    fun theRateUsesTheTimeSinceTheDownloadStarted() = runTest(dispatcher) {
        val progress = MutableStateFlow<(Long) -> Unit>({})
        val gate = CompletableDeferred<Result<String>>()
        val slow = object : VehicleLogRepository by logs {
            override suspend fun download(log: FlightLog, onProgress: (Long) -> Unit): Result<String> {
                progress.value = onProgress
                return gate.await()
            }
        }
        val vm = LogsViewModel(vehicle, slow, selected, testScheduler.timeSource).also { vm -> backgroundScope.launch { vm.state.collect {} }; runCurrent() }
        vm.onListClicked()
        runCurrent()
        vm.onDownloadClicked(1)
        runCurrent()
        advanceTimeBy(2.seconds)
        progress.value(1_000_000)
        runCurrent()
        assertEquals("1.0 MB of 1.8 MB · 500 kB/s", vm.state.value.download?.text)
        gate.complete(Result.success("x"))
        runCurrent()
    }

    @Test
    fun cancelStopsTheDownloadAndSaysNothingWasSaved() = runTest(dispatcher) {
        logs.result = { awaitCancellation() }
        val vm = vm()
        vm.onListClicked()
        runCurrent()
        vm.effects.test {
            vm.onDownloadClicked(2)
            runCurrent()
            vm.onCancelClicked()
            runCurrent()
            assertEquals("Download cancelled. Nothing was saved.", (awaitItem() as SetupEffect.ShowMessage).text)
        }
        assertTrue(logs.cancelled, "the repository saw the cancellation (it deletes the partial file)")
        assertNull(vm.state.value.download)
    }

    @Test
    fun failuresAreShownAndTheListIsKept() = runTest(dispatcher) {
        logs.result = { Result.failure(LogTransferException("The download stopped: no data from the vehicle at byte 46080.")) }
        val vm = vm()
        vm.onListClicked()
        runCurrent()
        vm.effects.test {
            vm.onDownloadClicked(1)
            runCurrent()
            assertEquals("Log 1 not saved: The download stopped: no data from the vehicle at byte 46080.", (awaitItem() as SetupEffect.ShowMessage).text)
            logs.list = Result.failure(LogTransferException("No answer from the vehicle."))
            vm.onListClicked()
            runCurrent()
            assertEquals("Couldn't list the logs: No answer from the vehicle.", (awaitItem() as SetupEffect.ShowMessage).text)
        }
        assertEquals(2, vm.state.value.rows.size, "a failed re-read keeps the list it had")
    }

    /** The list is one vehicle's: selecting another clears it. Armed or logging in: nothing can be asked. */
    @Test
    fun anotherVehicleClearsTheListAndArmedBlocksEverything() = runTest(dispatcher) {
        val vm = vm()
        vm.onListClicked()
        runCurrent()
        selected.value = VehicleId(1, 2u)
        runCurrent()
        assertFalse(vm.state.value.listed)
        vm.onListClicked()
        runCurrent()
        vehicle.value = vehicle.value.copy(armed = true)
        runCurrent()
        assertContains(vm.state.value.hint!!, "armed")
        assertFalse(vm.state.value.canList)
        vm.onDownloadClicked(1)
        vehicle.value = VehicleState(connected = true, login = KftLoginStatus.LOGGING_IN)
        runCurrent()
        assertContains(vm.state.value.hint!!, "wait for the login")
        assertTrue(logs.downloads.isEmpty())
    }

    /** Sizes in decimal units; dates in UTC, checked against Python's datetime (leap day, the epoch, before it, 2099). */
    @Test
    fun sizesAndDates() {
        assertEquals(listOf("90 B", "999 B", "1 kB", "512 kB", "1.0 MB", "17.3 MB"), listOf(90L, 999, 1_000, 512_400, 1_000_000, 17_343_196).map(::bytes))
        assertEquals("1970-01-01 00:00 UTC", utcText(0))
        assertEquals("2000-02-29 00:00 UTC", utcText(951_782_400))
        assertEquals("2025-10-25 09:14 UTC", utcText(1_761_383_642))
        assertEquals("1969-12-31 23:59 UTC", utcText(-1))
        assertEquals("2099-12-31 23:59 UTC", utcText(4_102_444_740))
    }
}
