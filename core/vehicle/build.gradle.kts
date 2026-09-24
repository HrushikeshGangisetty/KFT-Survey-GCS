// Vehicle state model, telemetry Flows, command protocol (ACK/retry), mission upload/download state machine.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:mavlink"))
            api(project(":core:geo"))
        }
    }
}
