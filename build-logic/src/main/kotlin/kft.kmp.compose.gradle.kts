// Convention plugin for KMP modules that contain Compose UI (ui/*, feature/*, app/shared).
plugins {
    id("kft.kmp.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(libs.lib("compose-runtime"))
            implementation(libs.lib("compose-foundation"))
            implementation(libs.lib("compose-ui"))
            implementation(libs.lib("compose-material3"))
            implementation(libs.lib("compose-ui-tooling-preview"))
        }
    }
}
