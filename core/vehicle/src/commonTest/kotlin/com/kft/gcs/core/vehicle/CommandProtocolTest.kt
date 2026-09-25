package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.definitions.common.CommandAck
import com.divpundir.mavlink.definitions.common.CommandInt
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.kft.gcs.core.mavlink.TxResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/** The command protocol against a scripted flight controller, in virtual time (1.5 s ACK timeout, 3 attempts). */
class CommandProtocolTest {
    private val fc = FakeFc()
    private val protocol = CommandProtocol(fc.frames, fc)
    private val request = CommandLong(targetSystem = 1u, targetComponent = 1u, command = MavEnumValue.of(MavCmd.REQUEST_MESSAGE), param1 = 242f)

    @Test
    fun acceptedOnFirstTry() = runTest {
        fc.reply = { listOf(ackFor(request)) }
        assertEquals(CommandResult.Accepted, protocol.send(request))
        assertEquals(listOf(0.toUByte()), fc.sentOf<CommandLong>().map { it.confirmation })
        assertEquals(0L, testScheduler.currentTime, "no waiting when the ACK is immediate")
    }

    @Test
    fun lostCommandIsResentWithIncrementedConfirmation() = runTest {
        var calls = 0
        fc.reply = { if (++calls == 1) emptyList() else listOf(ackFor(request)) } // the first one is lost
        assertEquals(CommandResult.Accepted, protocol.send(request))
        assertEquals(listOf(0, 1), fc.sentOf<CommandLong>().map { it.confirmation.toInt() })
        assertEquals(1_500L, testScheduler.currentTime, "one ACK timeout before the resend")
    }

    @Test
    fun noAckAfterThreeAttempts() = runTest {
        assertEquals(CommandResult.NoAck, protocol.send(request))
        assertEquals(3, fc.sent.size)
        assertEquals(4_500L, testScheduler.currentTime)
    }

    @Test
    fun deniedAndUnsupportedAreFinalAndNotRetried() = runTest {
        for (result in listOf(MavResult.DENIED, MavResult.UNSUPPORTED, MavResult.TEMPORARILY_REJECTED)) {
            fc.sent.clear()
            fc.reply = { listOf(ackFor(request, result)) }
            assertEquals(CommandResult.Refused(result), protocol.send(request))
            assertEquals(1, fc.sent.size, "$result must not be retried")
        }
    }

    @Test
    fun inProgressStopsResendingAndWaitsForTheFinalAck() = runTest {
        fc.reply = { listOf(ackFor(request, MavResult.IN_PROGRESS)) }
        // The final ACK comes 5 s later, well past the 1.5 s timeout that would otherwise trigger a resend.
        launch { delay(5.seconds); fc.emit(ackFor(request, MavResult.ACCEPTED)) }
        assertEquals(CommandResult.Accepted, protocol.send(request))
        assertEquals(1, fc.sent.size, "no resend while the vehicle says it is working on it")
    }

    @Test
    fun inProgressThatNeverFinishesTimesOut() = runTest {
        fc.reply = { listOf(ackFor(request, MavResult.IN_PROGRESS)) }
        assertEquals(CommandResult.NoAck, protocol.send(request))
        assertEquals(30_000L, testScheduler.currentTime)
    }

    @Test
    fun acksForAnotherCommandSystemOrGcsAreIgnored() = runTest {
        fc.reply = {
            listOf(
                ackFor(request).copy(command = MavEnumValue.of(MavCmd.SET_MESSAGE_INTERVAL)), // different command
                ackFor(request).copy(targetSystem = 254u),                                  // meant for another GCS
            )
        }
        launch { fc.emit(ackFor(request), systemId = 2u) }                                   // a different vehicle
        assertEquals(CommandResult.NoAck, protocol.send(request))
    }

    @Test
    fun olderFirmwareAckWithoutTargetFieldsStillMatches() = runTest {
        fc.reply = { listOf(CommandAck(command = request.command, result = MavEnumValue.of(MavResult.ACCEPTED))) }
        assertEquals(CommandResult.Accepted, protocol.send(request))
    }

    @Test
    fun gatewayRefusalIsReportedAndNotRetried() = runTest {
        val refused = TxResult.Rejected("MAV_CMD_REQUEST_MESSAGE", "test")
        fc.txResult = refused
        assertEquals(CommandResult.NotSent(refused), protocol.send(request))
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun commandIntIsResentUnchanged() = runTest {
        val interval = CommandInt(targetSystem = 1u, targetComponent = 1u, command = MavEnumValue.of(MavCmd.SET_MESSAGE_INTERVAL), param1 = 242f)
        var calls = 0
        fc.reply = { if (++calls < 3) emptyList() else listOf(CommandAck(command = interval.command, result = MavEnumValue.of(MavResult.ACCEPTED))) }
        assertEquals(CommandResult.Accepted, protocol.send(interval))
        assertEquals<List<Any>>(listOf(interval, interval, interval), fc.sent)
    }
}
