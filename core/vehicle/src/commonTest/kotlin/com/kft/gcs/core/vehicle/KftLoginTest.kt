package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.MavSeverity
import com.divpundir.mavlink.definitions.common.RequestDataStream
import com.divpundir.mavlink.definitions.common.Statustext
import com.divpundir.mavlink.definitions.minimal.Heartbeat
import com.divpundir.mavlink.definitions.minimal.MavAutopilot
import com.divpundir.mavlink.definitions.minimal.MavType
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.LinkState
import com.kft.gcs.core.mavlink.LinkStats
import com.kft.gcs.core.mavlink.VehicleInfo
import com.kft.gcs.core.mavlink.VehicleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The KFT login end to end (spec S12): VehicleRepository + KftLogin + CommandProtocol against a fake flight
 * controller that behaves like ardupilotKFT (`GCS_Common.cpp` USER_1 case and USER_2 intercept, `KFT_GCSAuth.cpp`
 * verify_hmac). Virtual time throughout.
 */
class KftLoginTest {
    private val fleetKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val challenge = ByteArray(32) { (it * 13 + 1).toByte() }
    private val copter = VehicleInfo(systemId = 1u, componentId = 1u, kind = VehicleKind.COPTER, armed = false, customMode = 0u)
    private val link = MutableStateFlow<LinkState>(LinkState.Disconnected)

    /** What the firmware does. Each test changes one behaviour. */
    private inner class KftFirmware(val key: ByteArray = fleetKey) {
        var challengeAck: MavResult? = MavResult.ACCEPTED // null = never answers USER_1
        var sendsSecondHalf = true
        var challengesIssued = 0

        fun reply(m: MavMessage<*>): List<MavMessage<*>> {
            if (m !is CommandLong) return emptyList() // REQUEST_DATA_STREAM: ArduPilot never answers it
            return when (m.command.value) {
                MavCmd.USER_1.value -> {
                    val ack = challengeAck ?: return emptyList()
                    if (ack != MavResult.ACCEPTED) return listOf(ackFor(m, ack))
                    challengesIssued++
                    val hex = challenge.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
                    // The firmware queues the texts; here the ACK even comes first, to prove the order doesn't matter.
                    listOfNotNull(
                        ackFor(m),
                        text("KFTCH1:${hex.take(32)}"),
                        text("KFTCH2:${hex.drop(32)}").takeIf { sendsSecondHalf },
                    )
                }
                MavCmd.USER_2.value -> {
                    // memcpy(&received_hmac[0], &packet.param2, 4) … then compare 24 bytes with its own HMAC.
                    val received = with(m) { listOf(param2, param3, param4, param5, param6, param7) }
                        .flatMap { f -> val b = f.toRawBits(); (0 until 4).map { (b shr (8 * it)).toByte() } }
                    val expected = hmacSha256(key, challenge).take(24)
                    listOf(ackFor(m, if (received == expected && m.param1 == 1f) MavResult.ACCEPTED else MavResult.DENIED))
                }
                else -> listOf(ackFor(m)) // REQUEST_MESSAGE, SET_MESSAGE_INTERVAL
            }
        }
    }

    private fun text(s: String) = Statustext(severity = MavEnumValue.of(MavSeverity.INFO), text = s)
    private val heartbeat = Heartbeat(
        type = MavEnumValue.of(MavType.QUADROTOR), autopilot = MavEnumValue.of(MavAutopilot.ARDUPILOTMEGA), mavlinkVersion = 3u,
    )

    private val fc = FakeFc()
    private fun firmware(f: KftFirmware = KftFirmware()) = f.also { fw -> fc.reply = fw::reply }

    private fun TestScope.repository(key: ByteArray? = fleetKey) =
        VehicleRepository(backgroundScope, fc.frames, link, fc, key, testScheduler.timeSource).also { runCurrent() }

    private fun TestScope.connect() {
        link.value = LinkState.Connected(LinkConfig.UdpListen(), copter, LinkStats())
        runCurrent()
    }

    private fun sentCommands() = fc.sentOf<CommandLong>().map { it.command.value }
    private fun challengeRequests() = sentCommands().count { it == MavCmd.USER_1.value }

    @Test
    fun acceptedLoginThenTheConnectTimeRequests() = runTest {
        firmware()
        val repo = repository()
        connect()
        assertEquals(KftLoginStatus.AUTHENTICATED, repo.state.value.login)
        // Order matters: nothing but the login before AUTHENTICATED, then the Pass 6 requests.
        assertTrue(fc.sent[0] is CommandLong && (fc.sent[0] as CommandLong).command.value == MavCmd.USER_1.value)
        assertEquals(MavCmd.USER_2.value, (fc.sent[1] as CommandLong).command.value)
        assertTrue(fc.sent[2] is RequestDataStream, "streams are requested only after the login")
        val response = fc.sent[1] as CommandLong
        assertEquals(1f, response.param1, "APP_ID 1, as the Android GCS")
        assertEquals(1, fc.sentOf<RequestDataStream>().size)
    }

    @Test
    fun wrongKeyIsDeniedAndNeverRetried() = runTest {
        firmware(KftFirmware(key = ByteArray(32) { 0x55 }))
        val repo = repository()
        connect()
        advanceTimeBy(60.seconds)
        assertEquals(KftLoginStatus.DENIED, repo.state.value.login)
        assertEquals(1, challengeRequests(), "the same key gives the same answer: no retry")
        assertTrue(fc.sentOf<RequestDataStream>().isEmpty(), "nothing else is sent after DENIED")
    }

    /** The firmware answers USER_1 with DENIED when it doesn't know the app id (`KFT Auth: unknown app`). */
    @Test
    fun unknownAppIdIsDenied() = runTest {
        firmware().challengeAck = MavResult.DENIED
        val repo = repository()
        connect()
        assertEquals(KftLoginStatus.DENIED, repo.state.value.login)
        assertEquals(listOf(MavCmd.USER_1.value), sentCommands())
    }

    @Test
    fun missingSecondHalfFailsAfterTheChallengeTimeoutAndRetriesAreBounded() = runTest {
        val fw = firmware().apply { sendsSecondHalf = false }
        val repo = repository()
        connect()
        advanceTimeBy(Kft.CHALLENGE_TIMEOUT - 1.milliseconds)
        assertEquals(KftLoginStatus.LOGGING_IN, repo.state.value.login, "still waiting for KFTCH2")
        advanceTimeBy(2.milliseconds)
        assertEquals(KftLoginStatus.FAILED, repo.state.value.login)

        // 4 attempts (8 s each) with 2 + 4 + 8 s between them, then nothing more, however long we wait.
        advanceTimeBy(10.minutes())
        assertEquals(4, fw.challengesIssued)
        assertEquals(KftLoginStatus.FAILED, repo.state.value.login)
        assertTrue(fc.sentOf<RequestDataStream>().isEmpty())
    }

    /** Stock ArduPilot: USER_1 isn't a command it knows, so it answers UNSUPPORTED (GCS_Common.cpp, default case). */
    @Test
    fun unsupportedMeansLegacyFirmwareAndEverythingElseProceeds() = runTest {
        firmware().challengeAck = MavResult.UNSUPPORTED
        val repo = repository()
        connect()
        assertEquals(KftLoginStatus.LEGACY_FIRMWARE, repo.state.value.login)
        assertEquals(1, fc.sentOf<RequestDataStream>().size)
        assertEquals(1, challengeRequests(), "one challenge request, never resent (a resend draws a new challenge)")
    }

    @Test
    fun noAckAtAllIsAlsoLegacyAfterThreeSeconds() = runTest {
        firmware().challengeAck = null
        val repo = repository()
        connect()
        advanceTimeBy(Kft.CHALLENGE_ACK_TIMEOUT - 1.milliseconds)
        assertEquals(KftLoginStatus.LOGGING_IN, repo.state.value.login)
        advanceTimeBy(2.milliseconds)
        assertEquals(KftLoginStatus.LEGACY_FIRMWARE, repo.state.value.login)
    }

    @Test
    fun noKeyMeansNoLoginAttempt() = runTest {
        firmware()
        val repo = repository(key = null)
        connect()
        assertEquals(KftLoginStatus.NO_KEY, repo.state.value.login)
        assertEquals("KFT login: no key configured", repo.state.value.login?.label)
        assertEquals(0, challengeRequests())
        assertEquals(1, fc.sentOf<RequestDataStream>().size, "stock firmware still works without a key")
    }

    @Test
    fun heartbeatGapOverSixSecondsLogsInAgain() = runTest {
        val fw = firmware()
        repository()
        connect()
        assertEquals(1, fw.challengesIssued)

        advanceTimeBy(5.seconds)
        fc.emit(heartbeat)
        runCurrent()
        assertEquals(1, fw.challengesIssued, "a 5 s gap is within the firmware's patience")

        advanceTimeBy(7.seconds)
        fc.emit(heartbeat)
        runCurrent()
        assertEquals(2, fw.challengesIssued, "7 s without a heartbeat: log in again")
        assertEquals(2, fc.sentOf<RequestDataStream>().size, "and ask for the streams again")
    }

    /** The vehicle drops out of LinkState after 3 s; when it returns, the link state and the heartbeat frame both see the gap. */
    @Test
    fun oneReloginWhenTheVehicleReappearsAfterALongGap() = runTest {
        val fw = firmware()
        repository()
        connect()
        link.value = LinkState.Connected(LinkConfig.UdpListen(), null, LinkStats()) // heartbeat timeout
        runCurrent()
        advanceTimeBy(9.seconds)
        connect()             // ConnectionManager publishes the vehicle again…
        fc.emit(heartbeat)    // …and then forwards the same heartbeat frame
        runCurrent()
        assertEquals(2, fw.challengesIssued, "one re-login, not two")
    }

    @Test
    fun aNewLinkIsANewSession() = runTest {
        val fw = firmware()
        repository()
        connect()
        link.value = LinkState.Connecting(LinkConfig.UdpListen(), attempt = 2, lastError = "cable pulled")
        runCurrent()
        advanceTimeBy(3.seconds) // past the debounce
        connect()
        assertEquals(2, fw.challengesIssued)
    }

    @Test
    fun challengeTextsFromOtherComponentsAreIgnoredAndNeverShownToThePilot() = runTest {
        val fw = firmware()
        val repo = repository()
        // A companion computer on the same system sends a forged half AFTER the real one. If it were accepted it
        // would overwrite the real half, the MAC would be wrong, and the login would be DENIED.
        fc.reply = { m ->
            val real = fw.reply(m)
            if (m is CommandLong && m.command.value == MavCmd.USER_1.value) {
                real.forEach { fc.emit(it) }
                fc.emit(text("KFTCH1:" + "ff".repeat(16)), componentId = 191u)
                emptyList()
            } else {
                real
            }
        }
        connect()
        assertEquals(KftLoginStatus.AUTHENTICATED, repo.state.value.login)
        assertTrue(repo.state.value.lastMessage?.text?.startsWith("KFTCH") != true, "the challenge isn't a pilot message")
    }

    private fun Int.minutes(): Duration = (this * 60).seconds
}
