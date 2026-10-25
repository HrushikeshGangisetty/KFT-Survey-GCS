package com.kft.gcs.core.mavlink

import com.divpundir.mavlink.adapters.coroutines.CoroutinesMavConnection
import com.divpundir.mavlink.api.MavFrame
import com.divpundir.mavlink.api.MavMessage
import com.divpundir.mavlink.connection.MavConnection
import com.divpundir.mavlink.connection.StreamState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wraps a blocking [MavConnection] for coroutines, like mavlink-kotlin's `asCoroutine`, except that the reader waits
 * for [ConnectionManager] instead of dropping frames (Pass 47).
 *
 * The library's adapter reads into a 128-frame `DROP_OLDEST` buffer. On SITL over TCP a 46 KB log chunk arrives in
 * about 0.04 s, faster than the manager's collector is scheduled, and 5 % of all frames were lost there (311 064
 * read, 295 069 delivered). Here [mavFrame] suspends the reader when it is full, so it stops reading the socket:
 * TCP then slows the sender down, and over UDP or serial the operating system's buffer is what overflows, as it would
 * with any reader that is busy.
 *
 * The only collector of [mavFrame] is [ConnectionManager.runSession], which does a little bookkeeping per frame and
 * never waits on anything else, so the reader can't be held up for long. Collectors further on are behind the
 * manager's own buffer, which drops instead of waiting (see [ConnectionManager.FRAME_BUFFER]).
 *
 * Sending is never done through here: [FrameSink] is the link's only writer (ADR-006).
 */
internal class BackpressureConnection(
    private val connection: MavConnection,
    private val io: CoroutineDispatcher,
) : CoroutinesMavConnection {
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Inactive.Stopped)
    override val streamState: StateFlow<StreamState> = _streamState.asStateFlow()

    // SUSPEND (the default) with a small buffer: the reader runs a few frames ahead, then waits.
    private val _mavFrame = MutableSharedFlow<MavFrame<out MavMessage<*>>>(extraBufferCapacity = 64)
    override val mavFrame: SharedFlow<MavFrame<out MavMessage<*>>> = _mavFrame.asSharedFlow()

    override suspend fun connect(readerScope: CoroutineScope) {
        withContext(io) {
            connection.connect()
            _streamState.value = StreamState.Active
        }
        readerScope.launch(io + CoroutineName("mavlink-reader")) { read() }
    }

    private suspend fun read() {
        try {
            while (_streamState.value == StreamState.Active) _mavFrame.emit(connection.next())
        } catch (e: CancellationException) {
            throw e // the link is being shut down; ConnectionManager closes the connection itself
        } catch (e: Exception) {
            // After close() this is the closed socket's exception, which is not a failure.
            if (_streamState.value == StreamState.Active) _streamState.value = StreamState.Inactive.Failed(e)
            runCatching { connection.close() }
        }
    }

    override suspend fun close() = withContext(io) {
        _streamState.value = StreamState.Inactive.Stopped
        connection.close()
    }

    override suspend fun <T : MavMessage<T>> sendV1(systemId: UByte, componentId: UByte, payload: T) =
        error("the GCS writes through FrameSink, never the library's send (ADR-006)")

    override suspend fun <T : MavMessage<T>> sendUnsignedV2(systemId: UByte, componentId: UByte, payload: T) =
        error("the GCS writes through FrameSink, never the library's send (ADR-006)")

    override suspend fun <T : MavMessage<T>> sendSignedV2(
        systemId: UByte, componentId: UByte, payload: T, linkId: UByte, timestamp: UInt, secretKey: ByteArray,
    ) = error("the GCS writes through FrameSink, never the library's send (ADR-006)")
}
