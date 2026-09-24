// Import (KML/GeoJSON/...) and export (QGC .plan, .waypoints, KML). Parsing only; file access is injected.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
            api(project(":core:planning"))
        }
    }
}
