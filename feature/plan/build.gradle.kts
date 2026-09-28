// Planning screens: mission groups (waypoints, surveys), survey panel, upload/read/clear, plan files and settings.
plugins {
    id("kft.kmp.compose")
    alias(libs.plugins.kotlin.serialization) // plan settings and plan files are JSON
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:planning"))
            implementation(project(":core:geo-io"))
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":core:vehicle"))
            implementation(project(":ui:design"))
            implementation(project(":ui:map"))
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
        // Compose UI tests of the key flows, headless on the desktop JVM (skiko renders off screen).
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Skiko (the UI tests' renderer) loads native code; allowed explicitly, as the desktop app does.
tasks.named<Test>("jvmTest") { jvmArgs("--enable-native-access=ALL-UNNAMED") }
