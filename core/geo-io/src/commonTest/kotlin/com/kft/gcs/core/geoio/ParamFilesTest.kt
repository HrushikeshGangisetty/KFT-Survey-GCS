package com.kft.gcs.core.geoio

import kotlin.test.Test
import kotlin.test.assertEquals

class ParamFilesTest {
    /** Lines as Mission Planner writes them (a "#NOTE" header, NAME,VALUE) plus the space/tab/`=` forms and `@READONLY` of ArduPilot's defaults files. */
    @Test
    fun readsMissionPlannerFiles() {
        val text = "#NOTE: 26/09/2026 Frame : Quad\r\nCAM1_TYPE,1\r\nATC_RAT_RLL_P,0.135\r\nWPNAV_SPEED 1000\nFENCE_ENABLE\t0\nSIM_SPEEDUP=5 @READONLY\n\n"
        val file = parseParamFile(text)
        assertEquals(mapOf("CAM1_TYPE" to 1f, "ATC_RAT_RLL_P" to 0.135f, "WPNAV_SPEED" to 1000f, "FENCE_ENABLE" to 0f, "SIM_SPEEDUP" to 5f), file.values)
        assertEquals(emptyList(), file.badLines)
    }

    @Test
    fun badLinesAreReportedNotGuessed() {
        val file = parseParamFile("CAM1_TYPE,1\ncam1_type,1\nTOO_LONG_NAME_FOR_AP,1\nX,abc\nY,1,2\nZ,nan\nCAM1_TYPE,2\n")
        assertEquals(mapOf("CAM1_TYPE" to 2f), file.values, "the later line wins")
        assertEquals(listOf(2, 3, 4, 5, 6), file.badLines)
    }

    @Test
    fun writesSortedNameValueLinesThatReadBack() {
        val text = encodeParamFile(mapOf("WPNAV_SPEED" to "1000", "ATC_RAT_RLL_P" to "0.135", "CAM1_TYPE" to "1"))
        assertEquals("ATC_RAT_RLL_P,0.135\nCAM1_TYPE,1\nWPNAV_SPEED,1000\n", text)
        assertEquals(mapOf("ATC_RAT_RLL_P" to 0.135f, "CAM1_TYPE" to 1f, "WPNAV_SPEED" to 1000f), parseParamFile(text).values)
    }
}
