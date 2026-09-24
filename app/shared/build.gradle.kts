// Shared app root: App() composable, theme, navigation host and the Koin DI graph. Used by both app shells.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":feature:connections"))
            implementation(project(":feature:fly"))
            implementation(project(":feature:plan"))
            implementation(project(":feature:settings"))
            implementation(project(":core:mavlink")) // for mavlinkModule and the IoDispatcher qualifier
            implementation(libs.jb.navigation.compose)
            implementation(libs.jb.lifecycle.viewmodel.compose)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
        }
    }
}
