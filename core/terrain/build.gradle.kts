// Ground elevation for planning: the Elevations interface and an online source (Open-Meteo, Copernicus GLO-90 DEM).
// DEM tiles on disk and the FC terrain server (P1) will live here too.
plugins {
    id("kft.kmp.library")
    alias(libs.plugins.kotlin.serialization) // the elevation API's JSON reply
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
        }
    }
}
