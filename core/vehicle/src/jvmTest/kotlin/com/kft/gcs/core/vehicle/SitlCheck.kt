package com.kft.gcs.core.vehicle

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.PodStatus
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * End-to-end checks against a running ArduPilot SITL, over the real TCP transport and the real gateway.
 * Skipped unless `KFT_SITL=host:port` is set, so `./gradlew check` never needs a simulator. Real time on purpose
 * (a simulator can't run on virtual time), which is why these live apart from the unit tests.
 *
 * Run: start SITL (TCP 5760), then `KFT_SITL=127.0.0.1:5760 ./gradlew :core:vehicle:jvmTest --tests '*SitlCheck*'`.
 */
class SitlCheck {
    private val target = System.getenv("KFT_SITL")

    /** Connects, waits for the vehicle and the startup data, runs [block], and always closes the link. */
    private fun withSitl(block: suspend (ConnectionManager, VehicleRepository) -> Unit) {
        val (host, port) = target?.split(":") ?: return println("KFT_SITL not set: SITL check skipped")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val manager = ConnectionManager(scope, Dispatchers.IO, MutableStateFlow(PodStatus.NoPod))
        val vehicles = VehicleRepository(scope, manager.frames, manager.state, manager.gateway)
        try {
            runBlocking {
                manager.connect(LinkConfig.TcpClient(host, port.toInt()))
                // SITL sets home ~20 s after boot, once its EKF has a GPS origin.
                withTimeout(60.seconds) { vehicles.state.first { it.connected && it.home != null && it.firmwareVersion != null } }
                block(manager, vehicles)
            }
        } finally {
            manager.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun startupRequestsBringVersionAndHome() = withSitl { _, vehicles ->
        val state = vehicles.state.value
        println("SITL: ArduPilot ${state.firmwareVersion}, home ${state.home}")
        assertNotNull(state.home)
    }
}
