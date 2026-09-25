// Connection profiles, connect/disconnect, link stats.
plugins {
    id("kft.kmp.compose")
    alias(libs.plugins.kotlin.serialization) // saved profiles are JSON
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:vehicle"))
            implementation(project(":ui:map"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
