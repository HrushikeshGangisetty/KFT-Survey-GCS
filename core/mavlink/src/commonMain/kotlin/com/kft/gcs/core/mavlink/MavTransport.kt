package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.adapters.coroutines.asCoroutine
import com.divpundir.mavlink.connection.AbstractMavConnection
import com.divpundir.mavlink.connection.BufferedMavConnection
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.definitions.ardupilotmega.ArdupilotmegaDialect
import com.kft.gcs.core.mavlink.signing.NoSigning
import com.kft.gcs.core.mavlink.signing.SigningKeyring
import com.kft.gcs.core.mavlink.signing.SigningSource
import com.kft.gcs.core.mavlink.signing.SigningStatus
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import okio.BufferedSink
import okio.BufferedSource
import okio.IOException
import okio.blackholeSink
import okio.buffer

/**
 * A raw byte pipe to a vehicle (UDP, TCP, serial, Bluetooth, the T12's data channel). MAVLink framing sits on top of it.
 *
 * `internal` on purpose: code outside `core:mavlink` can't even name a transport, so it can't write bytes that
 * bypass [MavTxGateway]. That's how CLAUDE.md §4 ("only the gateway writes") is enforced by the compiler.
 */
internal interface MavTransport {
    /** Blocking. Opens the link and returns its byte streams. Throws [IOException] if it can't. */
    fun open(): Streams

    /** Closes the link. Also unblocks a read in progress (it throws), which is how the reader loop stops. */
    fun close()

    class Streams(val source: BufferedSource, val sink: BufferedSink)
}

/**
 * Adapts a [MavTransport] to mavlink-kotlin: [BufferedMavConnection] does the framing and checksums over our
 * byte streams, with the ArduPilot dialect so ArduPilot-only messages decode too.
 *
 * Signing sits below the library (ADR-006): incoming bytes pass a [SigningSource] before the library sees them, and
 * outgoing frames go through the [FrameSink] made here, never through the library's `send*`.
 */
internal class TransportMavConnection(
    private val transport: MavTransport,
    private val keyring: SigningKeyring,
    private val nowMs: () -> Long,
    private val io: CoroutineDispatcher,
) : AbstractMavConnection() {
    /** The link's only writer, made by [open] (the transport's sink only exists once it is open). Null before that. */
    @Volatile
    var frameSink: FrameSink? = null
        private set

    /** How each vehicle on this link signs, from this open's [SigningSource]. Null before [open]. */
    @Volatile
    var signingStatus: StateFlow<Map<UByte, SigningStatus>>? = null
        private set

    override fun open(): MavConnection {
        val streams = transport.open()
        val frames = FrameSink(streams.sink, keyring, nowMs, io)
        // A new SigningSource on every open, so bindings never outlive the link session they were made on.
        val signing = SigningSource(streams.source, keyring, nowMs, frames::observeIncoming)
        frameSink = frames
        signingStatus = signing.status
        return BufferedMavConnection(
            signing.buffer(),
            // The library gets a sink that goes nowhere, so even a stray call to its send* can't reach the transport
            // behind FrameSink's back (two writers repeat sequence numbers and can mix frames: ADR-006 finding 4).
            blackholeSink().buffer(),
            object : okio.Closeable { override fun close() = transport.close() },
            ArdupilotmegaDialect,
        )
    }

    // Closing the transport is what interrupts a blocking connect/accept.
    override fun interruptOpen() = transport.close()
}

/**
 * One link as [ConnectionManager] drives it: [connection] reads and decodes frames, [frameSink] is the only way to
 * write, and [signingStatus] says how each vehicle on it signs. The last two exist only once [connection] has
 * connected, because the transport opens inside `connect`.
 */
internal class OpenedLink(
    val connection: CoroutinesMavConnection,
    val frameSink: () -> FrameSink,
    val signingStatus: () -> StateFlow<Map<UByte, SigningStatus>>,
)

/**
 * Builds the platform transport for [config], wrapped for coroutines, with its [FrameSink]. Socket code lives in
 * `jvmCommonMain`. [keyring] is [NoSigning] until the vault exists (S4), which keeps every frame unsigned.
 */
internal fun openTransportConnection(
    config: LinkConfig,
    ioDispatcher: CoroutineDispatcher,
    serialPorts: SerialPorts,
    t12: T12Link,
    keyring: SigningKeyring,
): OpenedLink {
    val transport = TransportMavConnection(createTransport(config, serialPorts, t12), keyring, { Clock.System.now().toEpochMilliseconds() }, ioDispatcher)
    return OpenedLink(
        transport.asCoroutine(ioDispatcher),
        { checkNotNull(transport.frameSink) { "the link has not opened yet" } },
        { checkNotNull(transport.signingStatus) { "the link has not opened yet" } },
    )
}

internal expect fun createTransport(config: LinkConfig, serialPorts: SerialPorts, t12: T12Link): MavTransport
