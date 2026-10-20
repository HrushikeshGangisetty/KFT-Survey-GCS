package com.kft.gcs.core.companion

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Opens a WebSocket to [url] and calls [onText] for each text message until the connection ends. Returns normally
 * when the server closes; throws on a refused or broken connection.
 */
typealias CompanionConnector = suspend (url: String, onConnected: () -> Unit, onText: (String) -> Unit) -> Unit

/** Where the companion settings are saved, as one text blob (a file in the app's data folder; a string in tests). */
interface CompanionConfigStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * The companion (VisionNav Pi) link: one WebSocket, its messages parsed into one [CompanionState]. It has no way to
 * send anything to the vehicle, and it never touches the FC's MAVLink state: the two meet only on the screen.
 *
 * ```
 * connect() ─▶ connector (Ktor WebSocket) ─text─▶ parseRecord() ─▶ CompanionState.reduce(…, now) ─▶ state
 *                   └─ ends or fails ─▶ Failed(reason) ─▶ wait RETRY ─▶ again, until disconnect()
 * ```
 *
 * @param timeSource the clock the record arrival times come from; tests pass virtual time.
 */
class CompanionRepository(
    private val scope: CoroutineScope,
    private val store: CompanionConfigStore?,
    private val connector: CompanionConnector = ::ktorConnector,
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    private val start = timeSource.markNow()

    private val _config = MutableStateFlow(load())
    val config: StateFlow<CompanionConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(CompanionState())
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private var job: Job? = null

    /** Time since this repository started: the "now" that the ages in [CompanionState] are measured against. */
    fun now(): Duration = start.elapsedNow()

    /** Saves new settings. A new URL takes effect on the next connect. */
    fun onConfigChanged(config: CompanionConfig) {
        _config.value = config
        store?.write(json.encodeToString(config))
    }

    /** Starts the link (and keeps retrying it) with a fresh state. Calling it while connected restarts it. */
    fun connect() {
        job?.cancel()
        _state.value = CompanionState(Connection.Connecting)
        val url = config.value.url
        job = scope.launch {
            while (true) {
                try {
                    connector(url, { _state.update { it.copy(connection = Connection.Connected) } }) { text ->
                        _state.update { it.reduce(parseRecord(text), now()) }
                    }
                    _state.update { it.copy(connection = Connection.Failed("closed by the companion")) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) { // refused, reset, bad handshake: all are "no companion data", shown, retried
                    _state.update { it.copy(connection = Connection.Failed(e.message ?: e::class.simpleName ?: "error")) }
                }
                delay(RETRY)
                _state.update { it.copy(connection = Connection.Connecting) }
            }
        }
    }

    /** Stops the link. The last data stays, and its ages keep growing, so it reads as stale rather than vanishing. */
    fun disconnect() {
        job?.cancel()
        job = null
        _state.update { it.copy(connection = Connection.Off) }
    }

    /** A broken or missing file means "nothing saved": the defaults. */
    private fun load(): CompanionConfig = try {
        store?.read()?.let { json.decodeFromString<CompanionConfig>(it) } ?: CompanionConfig()
    } catch (e: IllegalArgumentException) {
        CompanionConfig()
    }

    companion object {
        /** Retry interval after a failed or closed connection: fast enough to catch a Pi reboot, slow enough to be quiet. */
        val RETRY = 2.seconds
        private val json = Json { prettyPrint = true; encodeDefaults = true }
    }
}

// One client for the app's lifetime; CIO is Ktor's pure-Kotlin engine, the same on Android and desktop.
private val client by lazy { HttpClient(CIO) { install(WebSockets) } }

/** The real [CompanionConnector]: Ktor's WebSocket client. Binary frames are not part of the contract and are ignored. */
internal suspend fun ktorConnector(url: String, onConnected: () -> Unit, onText: (String) -> Unit) {
    client.webSocket(url) {
        onConnected()
        for (frame in incoming) if (frame is Frame.Text) onText(frame.readText())
    }
}
