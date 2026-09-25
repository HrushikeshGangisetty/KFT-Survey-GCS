package com.kft.gcs.core.vehicle

import com.divpundir.mavlink.api.MavEnumValue
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.definitions.common.CommandLong
import com.divpundir.mavlink.definitions.common.MavCmd
import com.divpundir.mavlink.definitions.common.MavResult
import com.divpundir.mavlink.definitions.common.Statustext
import com.kft.gcs.core.mavlink.VehicleInfo
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the KFT login stands (spec S12). [label] is what the Fly HUD and the Links card show.
 * [allowsTraffic]: whether mission transfers and the connect-time requests may go ahead.
 * [warning]: the screens show it in the warning colour, because the operator has something to fix.
 */
enum class KftLoginStatus(val label: String, val allowsTraffic: Boolean, val warning: Boolean) {
    /** No key, or not 64 hex characters. No login is attempted; stock firmware works, a KFT FC will ignore us. */
    NO_KEY("KFT login: no key configured", true, true),
    LOGGING_IN("KFT login: in progress…", false, false),
    AUTHENTICATED("KFT login: OK", true, false),
    /** The flight controller doesn't do KFT login (stock ArduPilot answers UNSUPPORTED). Nothing to unlock. */
    LEGACY_FIRMWARE("KFT login: not needed (firmware without KFT login)", true, false),
    /** Wrong key, or the firmware doesn't know APP_ID 1. Retrying can't help, so we don't. */
    DENIED("KFT login: denied (wrong key)", false, true),
    /** No challenge or no answer. Retried a few times with backoff, then left until the link recovers. */
    FAILED("KFT login: failed (no answer from the flight controller)", false, true),
}

/**
 * KFT fleet login constants, as the Android GCS and ardupilotKFT use them (spec S12).
 * Firmware: `libraries/GCS_MAVLink/GCS_Common.cpp` (USER_1 case, USER_2 intercept) and `KFT_GCSAuth.cpp`.
 */
internal object Kft {
    /** "KFT_GCS_Android" in the firmware's app table. The same fleet key and id as the Android GCS. */
    const val APP_ID = 1

    const val KEY_BYTES = 32
    const val CHALLENGE_BYTES = 32

    /** The firmware checks exactly 24 bytes (KFT_HMAC_TRUNC_LEN) = 6 floats, param2..param7. */
    const val MAC_BYTES = 24

    /** ACK to the challenge request. The Android GCS waits 3 s. */
    val CHALLENGE_ACK_TIMEOUT = 3.seconds

    /** Both KFTCH halves must arrive within this (Android GCS: 8 s; the firmware keeps a challenge valid 10 s). */
    val CHALLENGE_TIMEOUT = 8.seconds

    /** ACK to the response. The Android GCS waits 5 s. */
    val RESPONSE_ACK_TIMEOUT = 5.seconds
}

/** 64 hex characters → the 32-byte key, else null (which the app shows as "no key configured"). */
internal fun parseKftKey(hex: String): ByteArray? = hex.trim().takeIf { it.length == Kft.KEY_BYTES * 2 }?.let(::hexToBytes)

/**
 * The six response floats: HMAC-SHA256(key, challenge), first 24 bytes, each 4-byte group read as a little-endian
 * int and turned into a float **by its bits** ([Float.Companion.fromBits]). No arithmetic touches the value, so
 * every bit pattern (NaNs included) is kept; the gateway then sends them bit-exact (`BitExactCommandLong`).
 * The firmware `memcpy`s the floats straight back into bytes, so byte i of the MAC is byte i of param2..7.
 */
internal fun kftResponseParams(key: ByteArray, challenge: ByteArray): FloatArray {
    val mac = hmacSha256(key, challenge)
    return FloatArray(Kft.MAC_BYTES / 4) { f ->
        val i = f * 4
        Float.fromBits(
            (mac[i].toInt() and 0xFF) or ((mac[i + 1].toInt() and 0xFF) shl 8) or
                ((mac[i + 2].toInt() and 0xFF) shl 16) or ((mac[i + 3].toInt() and 0xFF) shl 24),
        )
    }
}

/** `KFTCH1:<32 hex>` → 16 bytes, when the text starts with [prefix]; anything malformed is ignored (null). */
internal fun parseChallengeHalf(text: String, prefix: String): ByteArray? =
    text.trimEnd('\u0000').takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
        ?.takeIf { it.length == Kft.CHALLENGE_BYTES / 2 * 2 }?.let(::hexToBytes) // half the challenge, 2 hex chars per byte

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    return ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toIntOrNull(16)?.toByte() ?: return null }
}

/**
 * One KFT login attempt against [vehicle]: the challenge-response from the Android GCS, re-implemented.
 *
 * ```
 * GCS                                        flight controller (ardupilotKFT)
 *  │ COMMAND_LONG USER_1(param1 = APP_ID) ──▶ draws a 32-byte challenge
 *  │ ◀── COMMAND_ACK(USER_1, ACCEPTED)       (DENIED = unknown app id; stock ArduPilot: UNSUPPORTED)
 *  │ ◀── STATUSTEXT "KFTCH1:<16 bytes hex>"  (the two texts and the ACK may arrive in any order)
 *  │ ◀── STATUSTEXT "KFTCH2:<16 bytes hex>"
 *  │ HMAC-SHA256(key, challenge)[0..24) as 6 raw floats
 *  │ COMMAND_LONG USER_2(APP_ID, f1..f6) ──▶ memcpy back to bytes, constant-time compare
 *  │ ◀── COMMAND_ACK(USER_2, ACCEPTED | DENIED)
 * ```
 * The key is only ever passed to [kftResponseParams]. Nothing here logs, and no type holding it has a toString
 * that prints it.
 */
internal class KftLogin(
    private val frames: Flow<MavFrame<out MavMessage<*>>>,
    private val commands: CommandProtocol,
    private val key: ByteArray,
) {
    /** Returns AUTHENTICATED, LEGACY_FIRMWARE, DENIED or FAILED. */
    suspend fun attempt(vehicle: VehicleInfo): KftLoginStatus = coroutineScope {
        // StateFlows instead of the Android GCS's 100 ms polling loop: `first { both present }` wakes exactly when
        // the second half arrives. Fresh per attempt, so a half from an earlier challenge can't be mixed in.
        val half1 = MutableStateFlow<ByteArray?>(null)
        val half2 = MutableStateFlow<ByteArray?>(null)
        // UNDISPATCHED = subscribed before this line returns, so before USER_1 is sent (replaces the old delay(100)).
        // ArduPilot queues STATUSTEXT (GCS_SEND_TEXT) and flushes it on its own schedule, so the halves may arrive
        // before or after the ACK. Listening from before the send covers both orders.
        val listener = launch(start = CoroutineStart.UNDISPATCHED) {
            frames.collect { frame ->
                val text = (frame.message as? Statustext)?.text ?: return@collect
                if (frame.systemId != vehicle.systemId || frame.componentId != vehicle.componentId) return@collect
                parseChallengeHalf(text, "KFTCH1:")?.let { half1.value = it }
                parseChallengeHalf(text, "KFTCH2:")?.let { half2.value = it }
            }
        }
        try {
            handshake(vehicle, half1, half2)
        } finally {
            listener.cancel()
        }
    }

    private suspend fun handshake(vehicle: VehicleInfo, half1: MutableStateFlow<ByteArray?>, half2: MutableStateFlow<ByteArray?>): KftLoginStatus {
        // One attempt only: every USER_1 makes the firmware draw a new challenge.
        when (val ack = commands.send(command(vehicle, MavCmd.USER_1), attempts = 1, ackTimeout = Kft.CHALLENGE_ACK_TIMEOUT)) {
            CommandResult.Accepted -> Unit
            is CommandResult.Refused ->
                return if (ack.result == MavResult.DENIED) KftLoginStatus.DENIED else KftLoginStatus.LEGACY_FIRMWARE
            // No ACK at all: firmware that ignores unknown commands. Same as the Android GCS: proceed as legacy.
            CommandResult.NoAck -> return KftLoginStatus.LEGACY_FIRMWARE
            is CommandResult.NotSent -> return KftLoginStatus.FAILED
        }

        val challenge = withTimeoutOrNull(Kft.CHALLENGE_TIMEOUT) {
            combine(half1, half2) { a, b -> if (a != null && b != null) a + b else null }.filterNotNull().first()
        } ?: return KftLoginStatus.FAILED

        val p = kftResponseParams(key, challenge)
        val response = command(vehicle, MavCmd.USER_2).copy(
            param2 = p[0], param3 = p[1], param4 = p[2], param5 = p[3], param6 = p[4], param7 = p[5],
        )
        // COMMAND_LONG, never COMMAND_INT: the firmware intercepts USER_2 before ArduPilot converts COMMAND_LONG to
        // COMMAND_INT (which would turn param5/param6 into integers) and DENIES USER_2 sent as COMMAND_INT.
        return when (val ack = commands.send(response, attempts = 1, ackTimeout = Kft.RESPONSE_ACK_TIMEOUT)) {
            CommandResult.Accepted -> KftLoginStatus.AUTHENTICATED
            is CommandResult.Refused -> if (ack.result == MavResult.DENIED) KftLoginStatus.DENIED else KftLoginStatus.FAILED
            else -> KftLoginStatus.FAILED
        }
    }

    private fun command(vehicle: VehicleInfo, cmd: MavCmd) = CommandLong(
        targetSystem = vehicle.systemId,
        targetComponent = vehicle.componentId,
        command = MavEnumValue.of(cmd),
        param1 = Kft.APP_ID.toFloat(),
    )
}
