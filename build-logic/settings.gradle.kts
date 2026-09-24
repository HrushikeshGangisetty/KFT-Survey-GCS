// build-logic is an "included build": it compiles our convention plugins before the main build
// configures, so every module can apply one line (e.g. id("kft.kmp.library")) instead of repeating setup.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "build-logic"
