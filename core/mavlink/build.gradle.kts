// Transports (UDP/TCP/serial/BT behind MavTransport), framing via mavlink-kotlin, connection manager,
// and MavTxGateway: the ONLY class allowed to write bytes to a transport.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.mavlink.api)
            api(libs.mavlink.definitions)
            implementation(libs.mavlink.serialization)
            implementation(libs.mavlink.connection.core)
            implementation(libs.mavlink.adapter.coroutines)
            api(libs.kotlinx.coroutines.core)
        }
    }
}
