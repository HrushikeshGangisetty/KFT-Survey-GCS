package com.kft.gcs.feature.params

import app.cash.turbine.test
import com.kft.gcs.core.mavlink.VehicleKind
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
import kotlinx.coroutines.launch
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

/** Our own small metadata (names match the fake vehicle; texts are ours). JSON served at master, XML in the archive. */
private val META_JSON = """
{"json": {"version": 0},
 "CAM1": {"CAM1_TYPE": {"DisplayName": "Camera trigger", "Description": "How the camera trigger works", "RebootRequired": "True",
                        "Values": {"0": "None", "1": "Servo", "2": "Relay"}}},
 "SERVO": {"SERVO9_FUNCTION": {"DisplayName": "Output 9 use", "Description": "Test bits", "Bitmask": {"0": "A", "1": "B", "2": "C"}}},
 "WPNAV_": {"WPNAV_SPEED": {"DisplayName": "Horizontal speed", "Description": "Speed between waypoints", "Units": "cm/s",
                           "Range": {"low": "20", "high": "2000"}}}}
"""

private val META_XML = """<paramfile><libraries><parameters name="CAM1_">
<param humanName="Camera trigger" name="CAM1_TYPE" documentation="How the camera trigger works">
<values><value code="0">None</value><value code="1">Servo</value></values></param>
</parameters></libraries></paramfile>"""

/** autotest.ardupilot.org in memory: [files] by URL (absent = 404), or no network at all. Records every download. */
private class FakeMetadataSource : MetadataSource {
    val files = mutableMapOf<String, String>()
    val saved = mutableMapOf<String, String>()
    val downloads = mutableListOf<String>()
    var offline = false
    override suspend fun cached(key: String) = saved[key]
    override suspend fun save(key: String, text: String) { saved[key] = text }
    override suspend fun download(url: String): String? {
        downloads += url
        if (offline) throw IllegalStateException("no route to host")
        return files[url]
    }
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
    private val metadata = FakeMetadataSource()
    private val vehicle = MutableStateFlow(VehicleState(connected = true, login = KftLoginStatus.AUTHENTICATED))

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private fun vm() = ParamsViewModel(vehicle, repo, files, metadata)

    private val master = "https://autotest.ardupilot.org/Parameters/ArduCopter/apm.pdef.json"
    private val archive = "https://autotest.ardupilot.org/Parameters/versioned/Copter/stable-4.5.7"

    /** A Copter on [version], with [json] as master's metadata, parameters downloaded. */
    private fun kotlinx.coroutines.test.TestScope.downloadedWithMetadata(version: String = "4.8.0-dev", json: String = META_JSON): ParamsViewModel {
        vehicle.value = vehicle.value.copy(vehicleKind = VehicleKind.COPTER, firmwareVersion = version)
        metadata.files[master] = json
        return vm().also {
            backgroundScope.launch { it.state.collect {} }
            it.onDownloadClicked()
            runCurrent()
        }
    }

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

    /**
     * A release (4.5.7) comes from the versioned archive: .json first (absent there today), then .xml. It's saved
     * under "Copter-4.5.7", and the next session uses the saved copy without downloading.
     */
    @Test
    fun releaseMetadataFallsBackToXmlAndIsKeptForNextTime() = runTest(dispatcher) {
        vehicle.value = vehicle.value.copy(vehicleKind = VehicleKind.COPTER, firmwareVersion = "4.5.7")
        metadata.files["$archive/apm.pdef.xml"] = META_XML
        val vm = vm()
        backgroundScope.launch { vm.state.collect {} }
        vm.onDownloadClicked(); runCurrent()
        assertEquals(listOf("$archive/apm.pdef.json", "$archive/apm.pdef.xml"), metadata.downloads)
        assertEquals(META_XML, metadata.saved["Copter-4.5.7"])
        val s = vm.state.value
        assertEquals("Descriptions: ArduCopter 4.5.7, downloaded", s.metadata)
        val cam = s.rows.first { it.name == "CAM1_TYPE" }
        assertEquals("Camera trigger", cam.displayName)
        assertEquals("None", cam.valueLabel, "CAM1_TYPE is 0 on the fake vehicle")

        metadata.downloads.clear()
        val next = vm()
        backgroundScope.launch { next.state.collect {} }
        next.onDownloadClicked(); runCurrent()
        assertEquals(emptyList(), metadata.downloads, "the saved copy is used offline")
        assertEquals("Descriptions: ArduCopter 4.5.7, saved on this device", next.state.value.metadata)
    }

    @Test
    fun noNetworkLeavesTheListWorkingAndSaysWhy() = runTest(dispatcher) {
        metadata.offline = true
        val vm = downloadedWithMetadata()
        val s = vm.state.value
        assertEquals(3, s.rows.size)
        assertContains(s.metadata!!, "couldn't reach autotest.ardupilot.org")
        assertContains(s.metadata.orEmpty(), "Import a file")
        assertNull(s.rows.first().displayName)
    }

    /** Parameters are grouped by prefix; the search also looks in display names and descriptions. */
    @Test
    fun groupsAndSearchByDescription() = runTest(dispatcher) {
        val vm = downloadedWithMetadata()
        assertEquals(listOf("CAM1", "SERVO9", "WPNAV"), vm.state.value.rows.map { it.group })
        vm.onQueryChanged("between waypoints"); runCurrent()
        assertEquals(listOf("WPNAV_SPEED"), vm.state.value.rows.map { it.name })
        assertEquals("cm/s", vm.state.value.rows.single().units)
    }

    /** Out of the documented range is refused before anything is sent; inside it, the usual question. */
    @Test
    fun rangeIsCheckedBeforeSet() = runTest(dispatcher) {
        val vm = downloadedWithMetadata()
        vm.onParamClicked("WPNAV_SPEED"); runCurrent()
        assertEquals("Units cm/s · Range 20 to 2000", vm.state.value.edit!!.facts)
        vm.onEditTextChanged("5000"); vm.onEditSetClicked(); runCurrent()
        assertEquals("Outside the documented range 20 to 2000 cm/s. Not sent.", vm.state.value.edit!!.error)
        assertNull(vm.state.value.edit!!.confirm)
        vm.onEditTextChanged("1500"); vm.onEditSetClicked(); runCurrent()
        assertEquals("Change WPNAV_SPEED from 1000 to 1500 on the vehicle?", vm.state.value.edit!!.confirm)
        assertTrue(repo.sets.isEmpty())
    }

    /**
     * The one override: the vehicle already holds an out-of-range value (the range doesn't fit this aircraft or
     * firmware), so another out-of-range value may be sent, after a warning in the question.
     */
    @Test
    fun outOfRangeIsOverridableOnlyWhenTheVehicleAlreadyHoldsOne() = runTest(dispatcher) {
        repo.vehicle["WPNAV_SPEED"] = repo.vehicle.getValue("WPNAV_SPEED").copy(value = 3000f)
        val vm = downloadedWithMetadata()
        vm.onParamClicked("WPNAV_SPEED")
        vm.onEditTextChanged("2500"); vm.onEditSetClicked(); runCurrent()
        val edit = vm.state.value.edit!!
        assertNull(edit.error)
        assertEquals(
            "Warning: 2500 is outside the documented range 20 to 2000 cm/s; the vehicle already holds 3000, also outside it. " +
                "Change WPNAV_SPEED from 3000 to 2500 on the vehicle?",
            edit.confirm,
        )
        vm.onEditConfirmClicked(); runCurrent()
        assertEquals(listOf("WPNAV_SPEED" to 2500f), repo.sets)
    }

    /** Values become a dropdown's choices; the question says a restart is needed. */
    @Test
    fun valuesAreChoicesAndRebootIsMentioned() = runTest(dispatcher) {
        val vm = downloadedWithMetadata()
        vm.onParamClicked("CAM1_TYPE"); runCurrent()
        assertEquals(listOf("0" to "None", "1" to "Servo", "2" to "Relay"), vm.state.value.edit!!.choices)
        vm.onEditTextChanged("1") // what picking "1 · Servo" in the dropdown sends
        vm.onEditSetClicked(); runCurrent()
        assertEquals("Change CAM1_TYPE from 0 to 1 on the vehicle? It takes effect after the flight controller restarts.", vm.state.value.edit!!.confirm)
    }

    /** Bitmask checkboxes flip bits of the value: 0 → bit 1 → 2 → bit 0 → 3; the ticks follow the text. */
    @Test
    fun bitmaskCheckboxesFlipBits() = runTest(dispatcher) {
        val vm = downloadedWithMetadata()
        vm.onParamClicked("SERVO9_FUNCTION"); runCurrent()
        assertEquals(listOf(BitChoice(0, "A"), BitChoice(1, "B"), BitChoice(2, "C")), vm.state.value.edit!!.bits)
        assertEquals("2", toggleBit("0", 1))
        assertEquals("3", toggleBit("2", 0))
        assertEquals("1", toggleBit("3", 1))
        assertEquals("4", toggleBit("", 2), "an empty field counts as 0")
        assertEquals(listOf(true, true, false), (0..2).map { hasBit("3", it) })
    }

    /** An imported file (KFT firmware) is saved for this vehicle's version and wins from then on; read-only is refused. */
    @Test
    fun importedMetadataIsSavedAndReadOnlyCantBeSet() = runTest(dispatcher) {
        val vm = downloadedWithMetadata()
        files.toOpen = "kft.pdef.json" to META_JSON.replace("\"RebootRequired\": \"True\"", "\"ReadOnly\": \"True\"")
        vm.onImportMetadataClicked(); runCurrent()
        assertEquals("Descriptions from kft.pdef.json (3)", vm.state.value.metadata)
        assertEquals(files.toOpen!!.second, metadata.saved["Copter-4.8.0-dev"])
        assertTrue(vm.state.value.rows.first { it.name == "CAM1_TYPE" }.readOnly)
        vm.onParamClicked("CAM1_TYPE")
        vm.onEditTextChanged("1"); vm.onEditSetClicked(); runCurrent()
        assertEquals("Read-only: ArduPilot doesn't let this be changed", vm.state.value.edit!!.error)
        assertTrue(repo.sets.isEmpty())

        files.toOpen = "vehicle.param" to "CAM1_TYPE,1\n"
        vm.onImportMetadataClicked(); runCurrent()
        assertEquals("Descriptions from kft.pdef.json (3)", vm.state.value.metadata, "a .param file is refused, the import stays")
    }

    /** A 0.1 stored as a float (0.10000000149…) still counts as inside a range that ends at 0.1. */
    @Test
    fun rangeAllowsForFloatStorage() {
        assertTrue((0.0..0.1).holds(0.1f))
        assertFalse((0.0..0.1).holds(0.11f))
    }
}
