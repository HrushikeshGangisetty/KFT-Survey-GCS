package com.kft.gcs.ui.map

import kotlin.test.Test
import kotlin.test.assertEquals

class MbtilesUrlTest {
    /** MapLibre wants an absolute Windows path after the scheme: drive letter first, forward slashes, spaces escaped. */
    @Test
    fun windowsPathBecomesAnAbsoluteMbtilesUrl() {
        if (!System.getProperty("os.name").startsWith("Windows")) return
        assertEquals("mbtiles://C:/maps/my%20farm.mbtiles", mbtilesUrl("C:\\maps\\my farm.mbtiles"))
    }
}
