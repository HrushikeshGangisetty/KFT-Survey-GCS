package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.MavParamError
import com.divpundir.mavlink.definitions.common.MavParamType
import com.divpundir.mavlink.definitions.common.ParamError
import com.divpundir.mavlink.definitions.common.ParamRequestList
import com.divpundir.mavlink.definitions.common.ParamRequestRead
import com.divpundir.mavlink.definitions.common.ParamSet
import com.divpundir.mavlink.definitions.common.ParamValue
import com.kft.gcs.core.geo.LatLon
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.MavTxGateway
import com.kft.gcs.core.mavlink.TxResult
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import com.kft.gcs.core.mission.Home
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/** The parameter protocol against a fake that answers like ArduPilot's GCS_Param.cpp, in virtual time. */
class ParamProtocolTest {
    private val fc = FakeFc()
    private val ap = ArduPilotParamFake().also { ap -> fc.reply = ap::reply }
    private val protocol = ParamProtocol(fc.frames, fc)
    private val target = MissionProtocol.Target(1u, 1u)

    @Test
    fun downloadsTheWholeListInIndexOrder() = runTest {
        val progress = mutableListOf<Pair<Int, Int>>()
        val params = protocol.download(target) { done, total -> progress += done to total }.getOrThrow()
        assertEquals(ap.names, params.map { it.name })
        assertEquals((0 until ap.names.size).toList(), params.map { it.index })
        assertEquals(ParamType.INT8, params.single { it.name == "CAM1_TYPE" }.type)
        assertEquals(0.15f, params.single { it.name == "ATC_RAT_RLL_P" }.value)
        assertEquals(ap.names.size to ap.names.size, progress.last())
        assertEquals(1, fc.sentOf<ParamRequestList>().size)
        assertEquals(0L, testScheduler.currentTime, "no waiting on a clean link")
    }

    /** Lost stream items are re-requested by index once the stream goes quiet, not by asking for the whole list again. */
    @Test
    fun missingIndicesAreReadOneByOne() = runTest {
        ap.dropFromList += setOf(2, 5)
        val params = protocol.download(target) { _, _ -> }.getOrThrow()
        assertEquals(ap.names, params.map { it.name })
        assertEquals(listOf(2, 5), fc.sentOf<ParamRequestRead>().map { it.paramIndex.toInt() })
        assertEquals(1_500L, testScheduler.currentTime, "one quiet period, then the reads are answered at once")
    }

    /** ArduPilot drops reads beyond its 20-entry queue, so at most BATCH go out per quiet period. */
    @Test
    fun readsGoOutInBatches() = runTest {
        ap.dropFromList += (0 until 25).toSet()
        ap.extra = 30
        protocol.download(target) { _, _ -> }.getOrThrow()
        val reads = fc.sentOf<ParamRequestRead>()
        assertEquals(25, reads.size)
        assertEquals(4_500L, testScheduler.currentTime, "10 + 10 + 5: three quiet periods")
    }

    @Test
    fun anIndexThatNeverArrivesFailsAfterThreeReads() = runTest {
        ap.dropFromList += 3
        ap.neverAnswerRead += 3
        val error = protocol.download(target) { _, _ -> }.exceptionOrNull()!!.message!!
        assertContains(error, "1 of 6 parameters never arrived")
        assertEquals(3, fc.sentOf<ParamRequestRead>().size)
    }

    /** A vehicle still loading its parameters ignores the list request (params_ready); we ask again. */
    @Test
    fun listRequestIsRepeatedUntilTheVehicleAnswers() = runTest {
        ap.ignoreListRequests = 2
        assertEquals(ap.names, protocol.download(target) { _, _ -> }.getOrThrow().map { it.name })
        assertEquals(3, fc.sentOf<ParamRequestList>().size)
    }

    @Test
    fun silentVehicleFailsTheDownload() = runTest {
        fc.reply = { emptyList() }
        assertContains(protocol.download(target) { _, _ -> }.exceptionOrNull()!!.message!!, "no answer")
        assertEquals(ParamProtocol.LIST_ATTEMPTS, fc.sentOf<ParamRequestList>().size)
    }

    @Test
    fun countChangingMidDownloadAsksForAFreshDownload() = runTest {
        ap.countChangesAt = 3
        assertContains(protocol.download(target) { _, _ -> }.exceptionOrNull()!!.message!!, "count changed")
    }

    @Test
    fun setIsConfirmedByTheEcho() = runTest {
        val cam = ap.param("CAM1_TYPE")
        val result = assertIs<ParamSetResult.Applied>(protocol.set(target, cam, 1f))
        assertEquals(1f, result.param.value)
        assertEquals(1f, ap.values["CAM1_TYPE"])
        val sent = fc.sentOf<ParamSet>().single()
        assertEquals("CAM1_TYPE", sent.paramId)
        assertEquals(MavParamType.INT8.value, sent.paramType.value)
    }

    /** A locked (KFT) or read-only parameter echoes its old value: reported once, never retried. */
    @Test
    fun lockedParameterIsReportedNotRetried() = runTest {
        ap.locked += "CAM1_TYPE"
        val result = assertIs<ParamSetResult.NotApplied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
        assertContains(result.reason, "Locked or rejected by the vehicle")
        assertContains(result.reason, "kept 0")
        assertEquals(0f, result.vehicleValue!!.value)
        assertEquals(1, fc.sentOf<ParamSet>().size)
        assertEquals(0L, testScheduler.currentTime)
    }

    /** Recent ArduPilot also sends PARAM_ERROR; whichever arrives first ends the set. */
    @Test
    fun paramErrorIsReported() = runTest {
        ap.errorFor += "CAM1_TYPE"
        val result = assertIs<ParamSetResult.NotApplied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
        assertContains(result.reason, "PERMISSION_DENIED")
    }

    @Test
    fun lostSetIsResentThenGivenUp() = runTest {
        fc.reply = { emptyList() }
        val result = assertIs<ParamSetResult.NotApplied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
        assertContains(result.reason, "not confirmed")
        assertEquals(ParamProtocol.SET_ATTEMPTS, fc.sentOf<ParamSet>().size)
        assertEquals(4_500L, testScheduler.currentTime)
    }

    @Test
    fun lostEchoIsRecoveredByTheResend() = runTest {
        ap.loseEchoOnce = true
        assertIs<ParamSetResult.Applied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
        assertEquals(2, fc.sentOf<ParamSet>().size)
    }

    /** Unrelated PARAM_VALUEs (another GCS reading, a late stream item) don't count as the echo. */
    @Test
    fun otherParametersDoNotConfirmTheSet() = runTest {
        fc.reply = { m ->
            if (m is ParamSet) listOf(ap.value("ATC_RAT_RLL_P", 65535), ap.echoOf(m)) else emptyList()
        }
        assertIs<ParamSetResult.Applied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
    }

    @Test
    fun armedSetIsNotSent() = runTest {
        fc.txResult = TxResult.Rejected("PARAM_SET", "parameters can only be changed while the vehicle is disarmed")
        val result = assertIs<ParamSetResult.NotApplied>(protocol.set(target, ap.param("CAM1_TYPE"), 1f))
        assertContains(result.reason, "disarmed")
        assertTrue(fc.sent.isEmpty())
    }

    /** S12: same gate as missions. Nothing is sent before the login allows it. */
    @Test
    fun repositoryWaitsForTheKftLogin() = runTest {
        val link = MutableStateFlow<LinkState>(LinkState.Disconnected)
        val vehicle = MutableStateFlow(VehicleState())
        val repo = DefaultParamRepository(fc.frames, link, vehicle, fc)
        assertContains(repo.downloadAll().exceptionOrNull()!!.message!!, "No vehicle")
        link.value = LinkState.Connected(LinkConfig.UdpListen(), VehicleInfo(1u, 1u, VehicleKind.COPTER, false, 0u), LinkStats())
        for (status in listOf(KftLoginStatus.LOGGING_IN, KftLoginStatus.DENIED, KftLoginStatus.FAILED)) {
            vehicle.value = VehicleState(home = Home(LatLon(0.0, 0.0), 0.0), login = status)
            assertContains(repo.downloadAll().exceptionOrNull()!!.message!!, "wait for the login")
            assertContains(assertIs<ParamSetResult.NotApplied>(repo.set(ap.param("CAM1_TYPE"), 1f)).reason, "wait for the login")
        }
        assertTrue(fc.sent.isEmpty())
        vehicle.value = VehicleState(login = KftLoginStatus.AUTHENTICATED)
        assertEquals(ap.names.size, repo.downloadAll().getOrThrow().size)
    }

    @Test
    fun inputIsCheckedAgainstTheStorageType() {
        assertEquals(3f, parseParamInput(" 3 ", ParamType.INT8).getOrThrow())
        assertContains(parseParamInput("2.5", ParamType.INT8).exceptionOrNull()!!.message!!, "Whole")
        assertContains(parseParamInput("128", ParamType.INT8).exceptionOrNull()!!.message!!, "range")
        assertEquals(-32768f, parseParamInput("-32768", ParamType.INT16).getOrThrow())
        assertContains(parseParamInput("16777217", ParamType.INT32).exceptionOrNull()!!.message!!, "range")
        assertEquals(0.15f, parseParamInput("0.15", ParamType.REAL32).getOrThrow())
        assertTrue(parseParamInput("NaN", ParamType.REAL32).isFailure, "ArduPilot refuses NaN, so we never send it")
        assertTrue(parseParamInput("abc", ParamType.REAL32).isFailure)
    }

    @Test
    fun valuesReadTheWayTheyAreTyped() {
        assertEquals("3", formatParamValue(3f, ParamType.INT8))
        assertEquals("-1", formatParamValue(-1f, ParamType.INT32))
        assertEquals("0.15", formatParamValue(0.15f, ParamType.REAL32))
        assertEquals("100", formatParamValue(100f, ParamType.REAL32))
    }
}

/**
 * Answers like ArduPilot's GCS_Param.cpp (master 2026-09): the list streamed with count and index, reads by index,
 * a set echoed with index 65535 (the stored value, or the old one when [locked]), PARAM_ERROR for [errorFor].
 */
private class ArduPilotParamFake {
    val names = listOf("ATC_RAT_RLL_P", "BATT_CAPACITY", "CAM1_TYPE", "FENCE_ENABLE", "SERVO9_FUNCTION", "WPNAV_SPEED")
    private val types = listOf(ParamType.REAL32, ParamType.INT32, ParamType.INT8, ParamType.INT8, ParamType.INT16, ParamType.REAL32)
    val values = mutableMapOf("ATC_RAT_RLL_P" to 0.15f, "BATT_CAPACITY" to 3300f, "CAM1_TYPE" to 0f, "FENCE_ENABLE" to 0f, "SERVO9_FUNCTION" to 0f, "WPNAV_SPEED" to 1000f)
    val dropFromList = mutableSetOf<Int>()
    val neverAnswerRead = mutableSetOf<Int>()
    val locked = mutableSetOf<String>()
    val errorFor = mutableSetOf<String>()
    var ignoreListRequests = 0
    var countChangesAt: Int? = null
    var loseEchoOnce = false

    /** Padding parameters, to make the list longer than [names] for batching tests. */
    var extra = 0
    private val total get() = names.size + extra
    private fun nameAt(i: Int) = names.getOrElse(i) { "PAD_${it - names.size}" }

    fun param(name: String) = names.indexOf(name).let { Param(name, values.getValue(name), typeAt(it), it) }

    fun value(name: String, index: Int, count: Int = total) = ParamValue(
        paramId = name, paramValue = values[name] ?: 0f, paramType = MavEnumValue.of(typeAt(names.indexOf(name)).wire()),
        paramCount = count.toUShort(), paramIndex = index.toUShort(),
    )

    fun echoOf(set: ParamSet): ParamValue {
        if (set.paramId !in locked) values[set.paramId] = set.paramValue
        return value(set.paramId, 65535)
    }

    fun reply(m: MavMessage<*>): List<MavMessage<*>> = when (m) {
        is ParamRequestList -> if (ignoreListRequests-- > 0) emptyList()
            else (0 until total).filter { it !in dropFromList }.map { i ->
                value(nameAt(i), i, count = if (countChangesAt != null && i >= countChangesAt!!) total + 1 else total)
            }
        is ParamRequestRead -> m.paramIndex.toInt().let { i -> if (i in neverAnswerRead) emptyList() else listOf(value(nameAt(i), i)) }
        is ParamSet -> when {
            m.paramId in errorFor -> listOf(
                ParamError(MavTxGateway.GCS_SYSTEM_ID, MavTxGateway.GCS_COMPONENT_ID, m.paramId, -1, MavEnumValue.of(MavParamError.PERMISSION_DENIED)),
                value(m.paramId, 65535),
            )
            loseEchoOnce -> { loseEchoOnce = false; echoOf(m); emptyList() }
            else -> listOf(echoOf(m))
        }
        else -> emptyList()
    }

    private fun typeAt(i: Int) = types.getOrElse(i) { ParamType.REAL32 }

    private fun ParamType.wire() = when (this) {
        ParamType.INT8 -> MavParamType.INT8
        ParamType.INT16 -> MavParamType.INT16
        ParamType.INT32 -> MavParamType.INT32
        ParamType.REAL32 -> MavParamType.REAL32
    }
}
