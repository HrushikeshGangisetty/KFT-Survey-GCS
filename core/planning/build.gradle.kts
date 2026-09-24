// PURE planning geometry: survey grid, corridor, structure, stats. No I/O, no coroutines, no platform code.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
        }
    }
}
