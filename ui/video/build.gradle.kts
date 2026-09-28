// Video playback: RTSP streams (Media3 on Android, FFmpeg on desktop) and raw H.264 from the Skydroid T12. ADR-004.
plugins { id("kft.kmp.compose") }

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":ui:design"))
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.media3.exoplayer)
            implementation(libs.media3.exoplayer.rtsp)
        }
        jvmMain.dependencies {
            // FFmpeg's LGPLv3 build (bytedeco's plain "ffmpeg", never "ffmpeg-gpl"): licence notes in ADR-004 and NOTICE.
            implementation(libs.bytedeco.ffmpeg)
            implementation(libs.bytedeco.javacpp)
            // ponytail: Windows x64 natives only (the P0 desktop target), as for the map runtime. Add linux/macOS
            // classifiers when those builds matter.
            runtimeOnly(project.dependencies.variantOf(libs.bytedeco.ffmpeg) { classifier("windows-x86_64") })
            runtimeOnly(project.dependencies.variantOf(libs.bytedeco.javacpp) { classifier("windows-x86_64") })
        }
        jvmTest.dependencies {
            // The T12 replay test reads a recorded capture through the real T12 framing.
            implementation(project(":core:mavlink"))
        }
    }
}
