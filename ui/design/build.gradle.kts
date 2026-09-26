// KFT design system: Material 3 theme (light, dark, high contrast), tokens, icons, logo and shared components.
plugins { id("kft.kmp.compose") }

kotlin {
    // AGP 9's KMP library plugin leaves Android resources off by default, which silently drops composeResources
    // (the icons and the logo) from the APK. Found in the map spike (spikes/map-spike/build.gradle.kts).
    android { androidResources { enable = true } }
    sourceSets {
        commonMain.dependencies {
            // Material Symbols and the logo ship as compose resources: one copy of each file for both platforms.
            implementation(libs.compose.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "com.kft.gcs.ui.design.res"
}
