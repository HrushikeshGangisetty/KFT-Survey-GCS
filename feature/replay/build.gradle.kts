// Replay tab: plays back a GNSS-denied flight from the FC log (and the Pi's odometry) with the vision error against truth.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:geo-io")) // the DataFlash reader and ImportFile
            implementation(project(":core:companion")) // the local_odometry contract the Pi recording is written in
            implementation(project(":ui:design"))
            implementation(project(":ui:map"))
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
