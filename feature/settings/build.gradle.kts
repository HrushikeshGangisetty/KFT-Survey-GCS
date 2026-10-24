// Maps tab: offline areas (download what's on screen), imported MBTiles, the browsing cache (Pass 23).
// Keys tab: the signing-key vault screen, the unlock prompt and the signing refusals (S5).
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:design"))
            implementation(project(":ui:map"))
            implementation(project(":core:keys")) // Vault, BundleImport
            implementation(project(":core:mavlink")) // Links (signing refusals, busy check), SigningKeyring
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
        // The signing-keys screen's UI test and screenshots, headless on the desktop JVM (skiko renders off screen).
        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            implementation(compose.desktop.currentOs)
        }
    }
}

// Skiko (the UI tests' renderer) loads native code; allowed explicitly, as the desktop app does.
tasks.named<Test>("jvmTest") { jvmArgs("--enable-native-access=ALL-UNNAMED") }
