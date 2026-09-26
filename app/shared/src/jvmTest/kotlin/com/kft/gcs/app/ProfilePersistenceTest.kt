package com.kft.gcs.app

import com.kft.gcs.core.mavlink.ConnectionManager
import com.kft.gcs.core.mavlink.LinkConfig
import com.kft.gcs.core.mavlink.PodStatus
import com.kft.gcs.core.mavlink.SerialPorts
import com.kft.gcs.core.vehicle.VehicleState
import com.kft.gcs.feature.connections.DefaultConnectionsRepository
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/** Desktop-only because it uses a real file and the real repository, exactly as the app does across a restart. */
class ProfilePersistenceTest {

    private val scope = TestScope()

    /** What the app builds at startup. A new instance = a restart; only the file carries over. */
    private fun startApp(store: FileTextStore) = DefaultConnectionsRepository(
        ConnectionManager(scope, StandardTestDispatcher(scope.testScheduler), MutableStateFlow(PodStatus.NoPod), SerialPorts()),
        SerialPorts(),
        store,
        MutableStateFlow(VehicleState()),
    )

    @Test
    fun profilesSurviveARestart() {
        val file = Files.createTempDirectory("kft-profiles").resolve("sub/connection-profiles.json").toFile()
        val first = startApp(FileTextStore(file))
        assertEquals(listOf("SITL (UDP 14550)", "SITL (TCP 5760)"), first.profiles.value.map { it.name }, "first run: defaults")

        first.addProfile("Radio", LinkConfig.Serial("COM7", 57600))
        first.deleteProfile(first.profiles.value.first().id)

        val second = startApp(FileTextStore(file))
        assertEquals(
            listOf("SITL (TCP 5760)" to LinkConfig.TcpClient("127.0.0.1", 5760), "Radio" to LinkConfig.Serial("COM7", 57600)),
            second.profiles.value.map { it.name to it.config },
        )
    }

    @Test
    fun aCorruptFileFallsBackToDefaults() {
        val file = Files.createTempFile("kft-profiles", ".json").toFile().apply { writeText("{ not json") }
        assertEquals(2, startApp(FileTextStore(file)).profiles.value.size)
    }
}
