// Plain mission values (MissionItem, Mission, Home) shared by the vehicle protocols and the file formats. No MAVLink.
plugins { id("kft.kmp.library") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:geo"))
        }
    }
}
