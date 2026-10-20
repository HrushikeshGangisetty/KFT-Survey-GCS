package com.kft.gcs.feature.fly

import com.kft.gcs.core.companion.CompanionRepository
import com.kft.gcs.core.mavlink.VehicleId
import com.kft.gcs.core.vehicle.FleetVehicle
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.ui.design.Status
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * P7 acceptance against the real visionnav P4 feed over a real WebSocket (Ktor CIO client), in real time. Skipped
 * unless `KFT_COMPANION_SCENARIO` (stale, lost_reset or wrong_map) and `KFT_VISIONNAV_DIR` (the visionnav checkout)
 * are set. It starts `tools/synthetic_feed/feed.py --serve` itself, because the scenario clock starts with the server,
 * prints the panel's lines once a second and checks the scenario's expected look. Needs Python with the feed's
 * packages (jsonschema, websockets) as `python` (override with `KFT_PYTHON`).
 */
class CompanionFeedRun {
    @Test
    fun thePanelShowsTheScenario() = runBlocking {
        val scenario = System.getenv("KFT_COMPANION_SCENARIO") ?: return@runBlocking println("KFT_COMPANION_SCENARIO not set: skipped")
        val visionnav = File(System.getenv("KFT_VISIONNAV_DIR") ?: return@runBlocking println("KFT_VISIONNAV_DIR not set: skipped"))
        val port = 18765 // not the feed's default, so a feed someone left running isn't picked up by mistake
        val url = "ws://127.0.0.1:$port"
        val feed = ProcessBuilder(
            System.getenv("KFT_PYTHON") ?: "python", "-u", "tools/synthetic_feed/feed.py", "--scenario", scenario, "--serve", "--port", "$port",
        ).directory(visionnav).redirectErrorStream(true).start()
        // The feed prints one line once it is about to listen; the client's retry covers the last few milliseconds.
        println(feed.inputReader().readLine())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = CompanionRepository(scope, store = null)
        repository.onConfigChanged(repository.config.value.copy(url = url))
        val vehicle = FleetVehicle(VehicleId(link = 1, systemId = 1u), slot = 0, VehicleState(connected = true))
        val seen = mutableListOf<CompanionUiState>()
        repository.connect()
        val runFor = when (scenario) { "stale" -> 8.seconds; "lost_reset" -> 16.seconds; else -> 12.seconds }
        val start = repository.now()
        while (repository.now() - start < runFor) {
            delay(100.milliseconds)
            val ui = companionUi(repository.state.value, repository.config.value, repository.now(), vehicle)
            seen += ui
            if (seen.size % 10 == 0) println("t=${(repository.now() - start).inWholeMilliseconds / 1000.0} s ${ui.connection.value} | ${ui.vio} | ${ui.rows.joinToString(" | ") { "${it.label}: ${it.value}" }} | ${ui.health.joinToString { it.value }}")
        }
        repository.disconnect()
        scope.cancel()
        feed.destroy()

        assertTrue(seen.any { it.connection.status == Status.OK }, "connected")
        assertTrue(seen.none { it.rejected != null }, "every feed record parsed: ${seen.last().rejected}")
        assertTrue(seen.any { it.source?.value?.startsWith("SYNTHETIC") == true }, "source shown")
        when (scenario) {
            "stale" -> {
                assertTrue(seen.any { it.vio?.value == "TRACKING" }, "fresh before 5 s")
                val last = seen.last()
                assertTrue(last.vio?.value?.startsWith("STALE") == true && last.vio?.status == Status.CRITICAL, "stale at the end: ${last.vio}")
                assertTrue(last.health.single().status == Status.OK, "while health still says OK")
                assertTrue(last.connection.status == Status.OK, "and messages were still arriving")
            }
            "lost_reset" -> {
                assertTrue(seen.any { it.vio?.value == "LOST" && it.vio?.status == Status.CRITICAL }, "LOST shown")
                assertTrue(seen.any { it.vio?.value == "INITIALIZING" }, "INITIALIZING shown")
                val last = seen.last()
                assertTrue(last.vio?.value == "TRACKING", "tracking again")
                assertTrue(last.rows.first { it.label == "Resets" }.value == "counter 1 · 1 this run", "reset counted")
                assertTrue(last.track.map { it.segment }.toSet() == setOf(0, 1), "two track segments")
            }
            "wrong_map" -> {
                // The fix with query time 8.5 s is 40 m north: 17.4 + 40 / 6378137 rad = 17.400359°.
                assertTrue(
                    seen.any { ui -> ui.rows.any { it.label == "Map fix" && it.value.startsWith("Accepted · 17.40035") } },
                    "the wrong fix is shown as an accepted map fix",
                )
            }
        }
    }
}
