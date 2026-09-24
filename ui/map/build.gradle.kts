// MapView abstraction + engine adapters (maplibre-compose first). Features talk to MapView, never to MapLibre.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
            // implementation, not api: MapLibre types must not leak into features (CLAUDE.md §2, ADR-001).
            implementation(libs.maplibre.compose)
        }
        androidMain.dependencies {
            runtimeOnly(libs.maplibre.compose.runtime.opengl.android)
        }
        jvmMain.dependencies {
            // ponytail: Windows x64 only (P0 desktop target). Add the linux/macOS runtimes when those builds matter.
            runtimeOnly(libs.maplibre.compose.runtime.vulkan.windows.x64)
        }
    }
}
