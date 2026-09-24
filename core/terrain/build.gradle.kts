// DEM tiles, elevation queries, FC terrain server (P1).
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
        }
    }
}
