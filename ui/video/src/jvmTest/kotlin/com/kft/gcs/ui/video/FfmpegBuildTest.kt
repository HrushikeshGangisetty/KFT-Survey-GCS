package com.kft.gcs.ui.video

import kotlin.test.Test
import kotlin.test.assertTrue
import org.bytedeco.ffmpeg.global.avcodec.avcodec_configuration
import org.bytedeco.ffmpeg.global.avcodec.avcodec_license

/**
 * The FFmpeg the desktop app ships is the LGPL build, asked of the library itself (not the artifact's name). A GPL
 * build (bytedeco's `ffmpeg-gpl`) would make the whole desktop app GPL. Release checklist, docs/checklists/release.md.
 */
class FfmpegBuildTest {
    @Test
    fun theShippedFfmpegIsLgplNotGpl() {
        val licence = avcodec_license().string
        val configuration = avcodec_configuration().string
        assertTrue(licence.startsWith("LGPL"), "FFmpeg licence: $licence")
        assertTrue("--enable-gpl" !in configuration && "--enable-nonfree" !in configuration, configuration)
    }
}
