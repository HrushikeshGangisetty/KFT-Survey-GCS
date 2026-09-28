// Fly view: HUD and map with vehicle. Monitoring only: flight actions are the pilot's, on the RC (spec S9).
plugins {
    id("kft.kmp.compose")
    alias(libs.plugins.kotlin.serialization) // video profiles are saved to a file
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:vehicle"))
            implementation(project(":ui:design"))
            implementation(project(":ui:map"))
            implementation(project(":ui:video"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
