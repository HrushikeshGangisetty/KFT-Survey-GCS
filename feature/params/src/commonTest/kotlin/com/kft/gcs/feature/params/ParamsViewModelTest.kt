package com.kft.gcs.feature.params

import app.cash.turbine.test
import com.kft.gcs.core.vehicle.KftLoginStatus
import com.kft.gcs.core.vehicle.Param
import com.kft.gcs.core.vehicle.ParamRepository
import com.kft.gcs.core.vehicle.ParamSetResult
import com.kft.gcs.core.vehicle.ParamType
import com.kft.gcs.core.vehicle.VehicleState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A vehicle in memory. [locked] parameters keep their value, like a locked KFT parameter. Records every set. */
private class FakeParamRepository : ParamRepository {
    val vehicle = mutableMapOf(
        "CAM1_TYPE" to Param("CAM1_TYPE", 0f, ParamType.INT8, 0),
        "SERVO9_FUNCTION" to Param("SERVO9_FUNCTION", 0f, ParamType.INT16, 1),
        "WPNAV_SPEED" to Param("WPNAV_SPEED", 1000f, ParamType.REAL32, 2),
    )
    val locked = mutableSetOf<String>()
    val sets = mutableListOf<Pair<String, Float>>()

    override suspend fun downloadAll(onProgress: (Int, Int) -> Unit): Result<List<Param>> {
        onProgress(vehicle.size, vehicle.size)
        return Result.success(vehicle.values.sortedBy { it.index })
    }

    override suspend fun set(param: Param, value: Float): ParamSetResult {
        sets += param.name to value
        val kept = vehicle.getValue(param.name)
        if (param.name in locked) return ParamSetResult.NotApplied("Locked or rejected by the vehicle: it kept 0.", kept)
        return ParamSetResult.Applied(kept.copy(value = value).also { vehicle[param.name] = it })
    }
}

private class FakeFiles : ParamFiles {
    var saved: Pair<String, String>? = null
    var toOpen: Pair<String, String>? = null
    override suspend fun save(suggestedName: String, text: String): String { saved = suggestedName to text; return suggestedName }
    override suspend fun open() = toOpen
}

class ParamsViewModelTest {
    /** The state goes through stateIn on the test dispatcher: let it run, then take the newest item. */
    private fun <T> app.cash.turbine.ReceiveTurbine<T>.latest(): T {
        dispatcher.scheduler.runCurrent()
        return expectMostRecentItem()
    }

    private val dispatcher = StandardTestDispatcher()
    private val repo = FakeParamRepository()
    private val files = FakeFiles()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ParamsViewModel(vehicle, repo, files)

    @Test
    fun downloadSearchAndStatus() = runTest(dispatcher) {
        val vm = vm()
        vm.state.test {
            assertEquals("Not downloaded", awaitItem().status)
            vm.onDownloadClicked()
            runCurrent()
            val loaded = latest()
            assertEquals("3 parameters · 0 modified", loaded.status)
            assertEquals(listOf("CAM1_TYPE", "SERVO9_FUNCTION", "WPNAV_SPEED"), loaded.rows.map { it.name })
            assertEquals("1000", loaded.rows.last().value, "whole floats without .0")
            vm.onQueryChanged("cam")
            assertEquals(listOf("CAM1_TYPE"), awaitItem().rows.map { it.name })
        }
    }

    /** Edit with confirm: Set only checks and asks, Confirm sends; the row then shows the vehicle's value as modified. */
    @Test
    fun editNeedsConfirmationAndMarksTheRowModified() = runTest(dispatcher) {
        val vm = vm()
        vm.state.test {
            vm.onDownloadClicked(); runCurrent()
            vm.onParamClicked("CAM1_TYPE")
            vm.onEditTextChanged("1.5")
            vm.onEditSetClicked()
            assertEquals("Whole numbers only", latest().edit!!.error)
            vm.onEditTextChanged("1")
            vm.onEditSetClicked()
            assertEquals("Change CAM1_TYPE from 0 to 1 on the vehicle?", latest().edit!!.confirm)
            assertTrue(repo.sets.isEmpty(), "nothing is sent before Confirm")
            vm.onEditConfirmClicked(); runCurrent()
            val after = latest()
            assertNull(after.edit)
            assertEquals(listOf("CAM1_TYPE" to 1f), repo.sets)
            val row = after.rows.first { it.name == "CAM1_TYPE" }
            assertEquals("1", row.value)
            assertTrue(row.modified)
            assertEquals("3 parameters · 1 modified", after.status)
        }
    }

    @Test
    fun unchangedValueIsNotOffered() = runTest(dispatcher) {
        val vm = vm()
        vm.state.test {
            vm.onDownloadClicked(); runCurrent()
            vm.onParamClicked("WPNAV_SPEED")
            vm.onEditSetClicked()
            assertEquals("That's the current value", latest().edit!!.error)
        }
    }

    /** A locked parameter keeps its value: the row says so once and stays unmodified; the set isn't repeated. */
    @Test
    fun lockedParameterIsShownNotRetried() = runTest(dispatcher) {
        repo.locked += "CAM1_TYPE"
        val vm = vm()
        vm.state.test {
            vm.onDownloadClicked(); runCurrent()
            vm.onParamClicked("CAM1_TYPE"); vm.onEditTextChanged("1"); vm.onEditSetClicked(); vm.onEditConfirmClicked(); runCurrent()
            val row = latest().rows.first { it.name == "CAM1_TYPE" }
            assertEquals("0", row.value)
            assertFalse(row.modified)
            assertContains(row.note!!, "Locked or rejected by the vehicle")
            assertEquals(1, repo.sets.size)
        }
    }

    @Test
    fun noEditingWhileArmedOrBeforeTheLogin() = runTest(dispatcher) {
        val vm = vm()
        vm.state.test {
            vm.onDownloadClicked(); runCurrent()
            vehicle.value = vehicle.value.copy(armed = true)
            val armed = latest()
            assertFalse(armed.canWrite)
            assertEquals("Disarm to change parameters", armed.writeHint)
            vm.onParamClicked("CAM1_TYPE")
            assertNull(vm.state.value.edit)
            vehicle.value = VehicleState(connected = true, login = KftLoginStatus.LOGGING_IN)
            val waiting = latest()
            assertFalse(waiting.canDownload)
            assertContains(waiting.writeHint!!, "wait for the login")
        }
    }

    @Test
    fun saveWritesMissionPlannerFormat() = runTest(dispatcher) {
        val vm = vm()
        vm.onDownloadClicked(); runCurrent()
        vm.onSaveFileClicked(); runCurrent()
        assertEquals("vehicle.param" to "CAM1_TYPE,0\nSERVO9_FUNCTION,0\nWPNAV_SPEED,1000\n", files.saved)
    }

    /** The bench checklist's use: load a file, see only the real changes, confirm, and every one is written and checked. */
    @Test
    fun loadFileListsChangesThenWritesThemOnConfirm() = runTest(dispatcher) {
        repo.locked += "SERVO9_FUNCTION"
        files.toOpen = "camera.param" to "# camera\nCAM1_TYPE,1\nSERVO9_FUNCTION,10\nWPNAV_SPEED,1000\nNOT_ON_VEHICLE,3\nbad line\n"
        val vm = vm()
        val messages = mutableListOf<String>()
        vm.state.test {
            vm.onDownloadClicked(); runCurrent()
            vm.onLoadFileClicked(); runCurrent()
            val load = assertNotNull(latest().fileLoad)
            assertEquals(listOf(ParamChange("CAM1_TYPE", "0", "1"), ParamChange("SERVO9_FUNCTION", "0", "10")), load.changes, "WPNAV_SPEED is unchanged")
            assertEquals("Skipped: 1 not on this vehicle; unreadable lines 6.", load.skipped)
            assertTrue(repo.sets.isEmpty())
            vm.onFileLoadConfirmed(); runCurrent()
            val after = latest()
            assertEquals(listOf("CAM1_TYPE" to 1f, "SERVO9_FUNCTION" to 10f), repo.sets)
            assertNotNull(after.rows.first { it.name == "SERVO9_FUNCTION" }.note)
        }
        vm.effects.first().let { messages += (it as ParamsEffect.ShowMessage).text } // "Downloaded 3 parameters"
        messages += (vm.effects.first() as ParamsEffect.ShowMessage).text
        assertEquals("camera.param: 1 of 2 written, 1 not applied (see the marked rows)", messages.last())
    }
}
