// MapView abstraction + engine adapters (maplibre-compose first). Features talk to MapView, never to MapLibre.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
        }
    }
}
