// Import/export parsing: camera lists (JSON), QGC .plan and Mission Planner .waypoints. Text in, values out; the
// app decides where the text comes from (files, bundled strings).
plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
            api(project(":core:planning"))
            // Mission files are lists of MissionItem (plain values, no MAVLink types in their API).
            api(project(":core:vehicle"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
