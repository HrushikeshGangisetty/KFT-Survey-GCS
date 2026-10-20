// VisionNav companion computer (the Pi): contract JSON parsing, the CompanionState store and its WebSocket client.
// Separate from core:vehicle on purpose: companion data never goes through the FC's MAVLink state (P7 design).
plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization) // the visionnav contract records, and the saved companion settings
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.ktor.client.cio)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
    }
}
