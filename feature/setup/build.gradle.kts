// Vehicle setup tab: calibrations (accelerometer, compass, barometer, radio), flight modes, failsafes, hardware ids.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:vehicle"))
            implementation(project(":ui:design"))
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
        // Compose UI tests and screenshots, headless on the desktop JVM (skiko renders off screen).
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Skiko (the UI tests' renderer) loads native code; allowed explicitly, as the desktop app does.
tasks.named<Test>("jvmTest") { jvmArgs("--enable-native-access=ALL-UNNAMED") }
