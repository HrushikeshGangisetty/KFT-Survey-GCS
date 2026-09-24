// Vehicle state model, telemetry Flows, command protocol (ACK/retry), mission upload/download state machine.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:mavlink"))
            api(project(":core:geo"))
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
    }
}
