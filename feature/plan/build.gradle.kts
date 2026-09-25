// Planning screens: waypoint editor and mission upload/read/clear now; survey grid (polygon, camera, overlap) in weeks 4-5.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:planning"))
            implementation(project(":core:vehicle"))
            implementation(project(":ui:map"))
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(libs.jb.lifecycle.runtime.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.compose.viewmodel)
        }
    }
}
